// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.KafkaMessage;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricPreviewResult;
import com.compagnonsdudev.kafkasqlexplorer.domain.PartitionTimeLag;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryResult;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicTimeLag;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.MockConsumer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MetricServiceTest {

    private MetricService service;
    private FlinkSqlService flinkSqlService;
    private MeterRegistry meterRegistry;
    private KafkaConfig kafkaConfig;
    private ExplorerConfig explorerConfig;
    private KafkaAdminService kafkaAdminService;
    private MessageFieldExtractorService messageFieldExtractorService;

    @BeforeEach
    void setUp() {
        flinkSqlService = Mockito.mock(FlinkSqlService.class);
        meterRegistry = new SimpleMeterRegistry();
        kafkaConfig = Mockito.mock(KafkaConfig.class);
        explorerConfig = Mockito.mock(ExplorerConfig.class);
        kafkaAdminService = Mockito.mock(KafkaAdminService.class);
        messageFieldExtractorService = new MessageFieldExtractorService();

        Mockito.when(explorerConfig.getMetricsConfigTopic()).thenReturn("internal.metrics.config");
        Mockito.when(kafkaConfig.getKafkaProperties()).thenReturn(Map.of());
        // listTables() must return non-empty so seedDefaultMetrics() actually seeds metrics
        Mockito.when(flinkSqlService.listTables()).thenReturn(List.of("demo_orders_in"));
        wirePairDelegation();

        service = newService();
    }

    /**
     * A service whose restore <em>completes</em> on an absent topic.
     *
     * <p>This used to be a plain {@code new MetricService(…)} and the restore reached a real
     * {@code KafkaConsumer} with no bootstrap address, i.e. it threw — which the seeding then read
     * as "no metric is configured". That is precisely the conflation {@code init()} was fixed for,
     * so the seam is what lets the test say which of the two it means. An empty {@code MockConsumer}
     * answers {@code partitionsFor} with nothing, which is a complete answer about an empty store.
     */
    private MetricService newService() {
        return new MetricService(
            flinkSqlService,
            meterRegistry,
            kafkaConfig,
            explorerConfig,
            kafkaAdminService,
            messageFieldExtractorService,
            new StartupRestore(new ExplorerConfig())
        ) {
            @Override
            Consumer<String, String> createConsumer() {
                return new MockConsumer<>("earliest");
            }
        };
    }

    @Test
    void testPreconfiguredMetrics() {
        service.init();
        List<MetricConfig> metrics = service.getAllMetrics();

        // seedDefaultMetrics() seeds GAUGE + COUNTER + HISTOGRAM + SUMMARY = 4 metrics
        assertEquals(4, metrics.size());
        assertTrue(metrics.stream().anyMatch(m -> m.type().equals("GAUGE")));
        assertTrue(metrics.stream().anyMatch(m -> m.type().equals("COUNTER")));
        assertTrue(metrics.stream().anyMatch(m -> m.type().equals("HISTOGRAM")));
        assertTrue(metrics.stream().anyMatch(m -> m.type().equals("SUMMARY")));
    }

    @Test
    void testSaveAndDeleteMetric() {
        service.init();
        int initialSize = service.getAllMetrics().size();
        MetricConfig newMetric = new MetricConfig(null, "Test Metric", "COUNTER", "SELECT 1 as metric_value", "Test Description", null, null, null, null, null, null);

        service.save(newMetric);
        List<MetricConfig> metrics = service.getAllMetrics();
        assertEquals(initialSize + 1, metrics.size());

        MetricConfig saved = metrics.stream().filter(m -> m.name().equals("Test Metric")).findFirst().orElseThrow();
        assertNotNull(saved.id());

        service.delete(saved.id());
        assertEquals(initialSize, service.getAllMetrics().size());
        assertFalse(service.getById(saved.id()).isPresent());
    }

    @Test
    void testUpdateMetric() {
        service.init();
        MetricConfig original = service.getAllMetrics().get(0);

        MetricConfig updated = new MetricConfig(original.id(), "Updated Name", "GAUGE", "SELECT 2 as metric_value", "Updated Desc", 10.0, 50.0, null, null, null, null);
        service.save(updated);

        Optional<MetricConfig> found = service.getById(original.id());
        assertTrue(found.isPresent());
        assertEquals("Updated Name", found.get().name());
        assertEquals("GAUGE", found.get().type());
    }

    @Test
    void previewCountDeltaTemplateComputesDifference() {
        stubBySql(Map.of("topic_a", directCount(12.0), "topic_b", directCount(7.0)));

        MetricConfig metric = new MetricConfig(
            null, "delta", "GAUGE", null, null, null, null, null, null, null, List.of(), Map.of(), null,
            "TOPIC_COUNT_DELTA",
            Map.of(
                "leftSql", "SELECT COUNT(*) AS metric_value FROM topic_a",
                "rightSql", "SELECT COUNT(*) AS metric_value FROM topic_b",
                "operation", "LEFT_MINUS_RIGHT"
            ),
            null, null, List.of()
        );

        MetricPreviewResult preview = service.previewMetric(metric);

        assertNull(preview.error());
        assertEquals(5.0, preview.value());
        assertEquals(5.0, preview.rows().get(0).get("metric_value"));
        assertEquals(12.0, preview.summary().get("leftValue"));
        assertEquals(7.0, preview.summary().get("rightValue"));
    }

    @Test
    void previewTransitLatencyTemplateComputesAverageAndCounts() {
        Mockito.when(flinkSqlService.executeSql(Mockito.any()))
            .thenReturn(
                new QueryResult(
                    List.of("match_key", "event_time"),
                    List.of(
                        Map.of("match_key", "A", "event_time", "2026-03-24T10:00:00Z"),
                        Map.of("match_key", "B", "event_time", "2026-03-24T10:01:00Z")
                    ),
                    10L,
                    null
                ),
                new QueryResult(
                    List.of("match_key", "event_time"),
                    List.of(
                        Map.of("match_key", "A", "event_time", "2026-03-24T10:00:05Z"),
                        Map.of("match_key", "B", "event_time", "2026-03-24T10:01:12Z")
                    ),
                    10L,
                    null
                )
            );

        MetricConfig metric = new MetricConfig(
            null, "latency", "GAUGE", null, null, null, null, null, null, null, List.of(), Map.of(), null,
            "TOPIC_TRANSIT_LATENCY",
            Map.of(
                "sourceSql", "SELECT order_id AS match_key, created_at AS event_time FROM topic_a",
                "targetSql", "SELECT order_id AS match_key, processed_at AS event_time FROM topic_b"
            ),
            null, null, List.of()
        );

        MetricPreviewResult preview = service.previewMetric(metric);

        assertNull(preview.error());
        assertEquals(8500.0, preview.value());
        assertEquals(8500.0, preview.summary().get("avgLatencyMs"));
        assertEquals(2, preview.summary().get("matchedCount"));
        assertEquals(0, preview.summary().get("unmatchedSourceCount"));
    }

    @Test
    void refreshMetricsPersistsTemplateSummary() {
        stubBySql(Map.of("left_topic", directCount(10.0), "right_topic", directCount(4.0)));

        MetricConfig metric = new MetricConfig(
            null, "delta-live", "GAUGE", null, null, null, null, null, null, null, List.of(), Map.of(), null,
            "TOPIC_COUNT_DELTA",
            Map.of(
                "leftSql", "SELECT COUNT(*) AS metric_value FROM left_topic",
                "rightSql", "SELECT COUNT(*) AS metric_value FROM right_topic",
                "operation", "LEFT_MINUS_RIGHT"
            ),
            "TEMPLATE_BOUNDED_SCAN", null, List.of()
        );

        service.save(metric);
        MetricConfig saved = service.getAllMetrics().stream()
            .filter(m -> "delta-live".equals(m.name()))
            .findFirst()
            .orElseThrow();

        service.refreshMetrics();

        MetricConfig refreshed = service.getById(saved.id()).orElseThrow();
        assertNotNull(refreshed.lastSummary());
        assertEquals(10.0, refreshed.lastSummary().get("leftValue"));
        assertEquals(4.0, refreshed.lastSummary().get("rightValue"));
        assertEquals("LEFT_MINUS_RIGHT", refreshed.lastSummary().get("operation"));
    }

    @Test
    void refreshMetricsMarksManagedJobMetricsAsPlanned() {
        service.init();

        MetricConfig metric = new MetricConfig(
            null, "delta-managed", "GAUGE", null, null, null, null, null, null, null, List.of(), Map.of(), null,
            "TOPIC_COUNT_DELTA",
            Map.of(
                "leftSql", "SELECT COUNT(*) AS metric_value FROM left_topic",
                "rightSql", "SELECT COUNT(*) AS metric_value FROM right_topic",
                "operation", "LEFT_MINUS_RIGHT"
            ),
            "FLINK_MANAGED_JOB", null, List.of()
        );

        service.save(metric);
        MetricConfig saved = service.getAllMetrics().stream()
            .filter(m -> "delta-managed".equals(m.name()))
            .findFirst()
            .orElseThrow();

        service.refreshMetrics();

        MetricConfig refreshed = service.getById(saved.id()).orElseThrow();
        assertNull(refreshed.lastValue());
        assertNull(refreshed.errorMessage());
        assertEquals("PLANNED", refreshed.lastSummary().get("managedJobStatus"));
        assertEquals("FLINK_MANAGED_JOB", refreshed.lastSummary().get("requestedExecutionMode"));
    }

    @Test
    void previewManagedJobMetricStillUsesBoundedPreview() {
        stubBySql(Map.of("left_topic", directCount(8.0), "right_topic", directCount(3.0)));

        MetricConfig metric = new MetricConfig(
            null, "delta-managed-preview", "GAUGE", null, null, null, null, null, null, null, List.of(), Map.of(), null,
            "TOPIC_COUNT_DELTA",
            Map.of(
                "leftSql", "SELECT COUNT(*) AS metric_value FROM left_topic",
                "rightSql", "SELECT COUNT(*) AS metric_value FROM right_topic",
                "operation", "LEFT_MINUS_RIGHT"
            ),
            "FLINK_MANAGED_JOB", null, List.of()
        );

        MetricPreviewResult preview = service.previewMetric(metric);

        assertNull(preview.error());
        assertEquals(5.0, preview.value());
        assertEquals("FLINK_MANAGED_JOB", preview.summary().get("plannedExecutionMode"));
        assertEquals("TEMPLATE_BOUNDED_SCAN", preview.summary().get("previewExecutionMode"));
        assertEquals("PLANNED", preview.summary().get("managedJobStatus"));
    }

    @Test
    void editingMetricTypePurgesStaleMeterSeries() {
        // A GAUGE metric that produces one series, then re-saved as a COUNTER.
        Mockito.when(flinkSqlService.executeSql(Mockito.any()))
            .thenReturn(new QueryResult(
                List.of("metric_value"),
                List.of(Map.of("metric_value", 5.0)),
                10L,
                null
            ));

        MetricConfig gauge = new MetricConfig(
            null, "shape_shift", "GAUGE", "SELECT 5 AS metric_value", null, null, null,
            null, null, null, List.of(), Map.of(), null, null, null, null, null, List.of());
        service.save(gauge);
        String id = service.getAllMetrics().stream()
            .filter(m -> "shape_shift".equals(m.name()))
            .findFirst().orElseThrow().id();

        service.refreshMetrics();
        assertFalse(meterRegistry.find("explorer_metric_gauge").tag("metric_id", id).meters().isEmpty(),
            "gauge series should exist after first refresh");

        // Re-save with the same id but a different type — the stale gauge must be removed.
        service.save(new MetricConfig(
            id, "shape_shift", "COUNTER", "SELECT 5 AS metric_value", null, null, null,
            null, null, null, List.of(), Map.of(), null, null, null, null, null, List.of()));

        assertTrue(meterRegistry.find("explorer_metric_gauge").tag("metric_id", id).meters().isEmpty(),
            "stale gauge series must be purged when the metric type changes");

        service.refreshMetrics();
        assertFalse(meterRegistry.find("explorer_metric_counter").tag("metric_id", id).meters().isEmpty(),
            "new counter series should exist after refresh");
        assertTrue(meterRegistry.find("explorer_metric_gauge").tag("metric_id", id).meters().isEmpty(),
            "gauge series must not reappear after switching to COUNTER");
    }

    @Test
    void histogramDoesNotRecordTheSameBacklogEveryRefresh() {
        // The bounded earliest-offset scan re-reads the full backlog every cycle: same 3 rows.
        QueryResult threeRows = new QueryResult(
            List.of("metric_value"),
            List.of(Map.of("metric_value", 1.0), Map.of("metric_value", 2.0), Map.of("metric_value", 3.0)),
            10L,
            null);
        Mockito.when(flinkSqlService.executeSql(Mockito.any())).thenReturn(threeRows);

        service.save(new MetricConfig(
            null, "hist", "HISTOGRAM", "SELECT amount AS metric_value FROM t", null, null, null,
            null, null, null, List.of(), Map.of(), null, null, null, null, null, List.of()));
        String id = service.getAllMetrics().stream()
            .filter(m -> "hist".equals(m.name()))
            .findFirst().orElseThrow().id();

        service.refreshMetrics();
        service.refreshMetrics();
        service.refreshMetrics();

        var summary = meterRegistry.find("explorer_metric_histogram").tag("metric_id", id).summary();
        assertNotNull(summary);
        assertEquals(3, summary.count(),
            "a static 3-row backlog must be recorded once, not once per refresh");
    }

    @Test
    void summaryRecordsOnlyNewlyAppendedObservationsAsBacklogGrows() {
        Mockito.when(flinkSqlService.executeSql(Mockito.any()))
            .thenReturn(
                new QueryResult(List.of("metric_value"),
                    List.of(Map.of("metric_value", 10.0), Map.of("metric_value", 20.0)), 10L, null),
                new QueryResult(List.of("metric_value"),
                    List.of(Map.of("metric_value", 10.0), Map.of("metric_value", 20.0),
                            Map.of("metric_value", 30.0)), 10L, null));

        service.save(new MetricConfig(
            null, "summ", "SUMMARY", "SELECT latency AS metric_value FROM t", null, null, null,
            null, null, null, List.of(), Map.of(), null, null, null, null, null, List.of()));
        String id = service.getAllMetrics().stream()
            .filter(m -> "summ".equals(m.name()))
            .findFirst().orElseThrow().id();

        service.refreshMetrics(); // records 10, 20  → count 2
        service.refreshMetrics(); // backlog grew by one (30) → records only 30 → count 3

        var summary = meterRegistry.find("explorer_metric_summary").tag("metric_id", id).summary();
        assertNotNull(summary);
        assertEquals(3, summary.count(), "only the newly appended observation should be recorded");
        assertEquals(60.0, summary.totalAmount(), 0.0001, "10 + 20 + 30 recorded exactly once each");
    }

    @Test
    void savePreservesRealSecretWhenEditedDdlComesBackMasked() {
        String ddlWithSecret = "CREATE TABLE t (id BIGINT) WITH (\n" +
            "  'properties.ssl.truststore.password' = 'super-secret'\n);";
        service.save(new MetricConfig(
            null, "sec", "GAUGE", "SELECT 1 AS metric_value FROM t", null, null, null,
            null, null, null, List.of(), Map.of(), ddlWithSecret, null, null, null, null, List.of()));
        String id = service.getAllMetrics().stream()
            .filter(m -> "sec".equals(m.name()))
            .findFirst().orElseThrow().id();

        // The UI re-saves the metric with the password echoed back masked.
        String maskedDdl = "CREATE TABLE t (id BIGINT) WITH (\n" +
            "  'properties.ssl.truststore.password' = '******'\n);";
        service.save(new MetricConfig(
            id, "sec", "GAUGE", "SELECT 1 AS metric_value FROM t", null, null, null,
            null, null, null, List.of(), Map.of(), maskedDdl, null, null, null, null, List.of()));

        MetricConfig stored = service.getById(id).orElseThrow();
        assertTrue(stored.createTableSql().contains("super-secret"),
            "the real secret must survive an edit that echoed it back masked");
        assertFalse(stored.createTableSql().contains("******"));
    }

    @Test
    void refreshMetricRecomputesASingleMetricOnDemand() {
        Mockito.when(flinkSqlService.executeSql(Mockito.any()))
            .thenReturn(new QueryResult(
                List.of("metric_value"), List.of(Map.of("metric_value", 42.0)), 10L, null));

        service.save(new MetricConfig(
            null, "on_demand", "GAUGE", "SELECT 42 AS metric_value FROM t", null, null, null,
            null, null, null, List.of(), Map.of(), null, null, null, null, null, List.of()));
        String id = service.getAllMetrics().stream()
            .filter(m -> "on_demand".equals(m.name()))
            .findFirst().orElseThrow().id();

        Optional<MetricConfig> refreshed = service.refreshMetric(id);

        assertTrue(refreshed.isPresent());
        assertEquals(42.0, refreshed.get().lastValue());
        assertNull(refreshed.get().errorMessage());
        assertTrue(service.refreshMetric("does-not-exist").isEmpty());
    }

    @Test
    void counterSumsRowsSharingALabelKeyInsteadOfMergingDeltas() {
        // Two rows with no distinct label column → same (empty) label key. Their cumulative
        // totals must sum (4 + 6 = 10), not collapse to the last row's value (which the old
        // per-row delta path produced: 4 + (6-4) = 6).
        Mockito.when(flinkSqlService.executeSql(Mockito.any()))
            .thenReturn(new QueryResult(
                List.of("metric_value"),
                List.of(Map.of("metric_value", 4.0), Map.of("metric_value", 6.0)),
                10L,
                null));

        service.save(new MetricConfig(
            null, "multi_counter", "COUNTER", "SELECT cnt AS metric_value FROM t", null, null, null,
            null, null, null, List.of(), Map.of(), null, null, null, null, null, List.of()));
        String id = service.getAllMetrics().stream()
            .filter(m -> "multi_counter".equals(m.name()))
            .findFirst().orElseThrow().id();

        service.refreshMetrics();

        var counter = meterRegistry.find("explorer_metric_counter").tag("metric_id", id).counter();
        assertNotNull(counter);
        assertEquals(10.0, counter.count(), 0.0001,
            "rows sharing a label key must sum into one cumulative counter value");
    }

    private static Map<String, Object> row(String labelValue, double metricValue) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("region", labelValue);
        row.put("metric_value", metricValue);
        return row;
    }

    @Test
    void staleLabelSeriesArePrunedWhenALabelValueStopsAppearing() {
        // Cycle 1 returns two label series (us, eu); cycle 2 drops eu.
        Mockito.when(flinkSqlService.executeSql(Mockito.any()))
            .thenReturn(
                new QueryResult(List.of("region", "metric_value"),
                    List.of(row("us", 1.0), row("eu", 2.0)), 10L, null),
                new QueryResult(List.of("region", "metric_value"),
                    List.of(row("us", 3.0)), 10L, null));

        service.save(new MetricConfig(
            null, "by_region", "GAUGE", "SELECT region, COUNT(*) AS metric_value FROM t GROUP BY region",
            null, null, null, null, null, null, List.of(), Map.of(), null, null, null, null, null, List.of()));
        String id = service.getAllMetrics().stream()
            .filter(m -> "by_region".equals(m.name()))
            .findFirst().orElseThrow().id();

        service.refreshMetrics();
        assertFalse(meterRegistry.find("explorer_metric_gauge").tag("metric_id", id).tag("region", "eu").gauges().isEmpty());
        assertFalse(meterRegistry.find("explorer_metric_gauge").tag("metric_id", id).tag("region", "us").gauges().isEmpty());

        service.refreshMetrics();
        assertTrue(meterRegistry.find("explorer_metric_gauge").tag("metric_id", id).tag("region", "eu").gauges().isEmpty(),
            "series for a label value that stopped appearing must be pruned");
        assertFalse(meterRegistry.find("explorer_metric_gauge").tag("metric_id", id).tag("region", "us").gauges().isEmpty(),
            "series still present in the latest cycle must be kept");
    }

    @Test
    void configuredLabelsFetchLatestMessageOncePerTopicPerCycle() {
        Mockito.when(flinkSqlService.executeSql(Mockito.any()))
            .thenReturn(new QueryResult(List.of("metric_value"), List.of(Map.of("metric_value", 1.0)), 10L, null));
        Mockito.when(kafkaAdminService.getLatestMessage("orders"))
            .thenReturn(Optional.of(new KafkaMessage(
                "orders", 0, 1L, 1_711_274_400_000L, null, "{\"status\":\"OK\",\"region\":\"eu\"}")));

        // Two metrics labelling off the same topic — the expensive latest-message read must be
        // shared within one refresh cycle, not repeated per metric.
        service.save(new MetricConfig(null, "m1", "GAUGE", "SELECT 1 AS metric_value", null, null, null,
            null, null, null, List.of(), Map.of(), null, null, null, null, "orders", List.of("status")));
        service.save(new MetricConfig(null, "m2", "GAUGE", "SELECT 1 AS metric_value", null, null, null,
            null, null, null, List.of(), Map.of(), null, null, null, null, "orders", List.of("region")));

        service.refreshMetrics();

        Mockito.verify(kafkaAdminService, Mockito.times(1)).getLatestMessage("orders");
    }

    @Test
    void refreshMetricsAddsLabelsFromLatestKafkaMessage() {
        Mockito.when(flinkSqlService.executeSql(Mockito.any()))
            .thenReturn(new QueryResult(
                List.of("metric_value"),
                List.of(Map.of("metric_value", 9.0)),
                10L,
                null
            ));
        Mockito.when(kafkaAdminService.getLatestMessage("orders"))
            .thenReturn(Optional.of(new KafkaMessage(
                "orders",
                0,
                12L,
                1_711_274_400_000L,
                null,
                "{\"customer\":{\"id\":\"C-42\"},\"status\":\"READY\"}"
            )));

        service.save(new MetricConfig(
            null, "labeled_metric", "GAUGE", "SELECT 9 AS metric_value", null, null, null, null, null, null, List.of(), Map.of(), null,
            null,
            null,
            null, "orders", List.of("customer.id", "status")
        ));

        service.refreshMetrics();

        MetricConfig refreshed = service.getAllMetrics().stream()
            .filter(metric -> "labeled_metric".equals(metric.name()))
            .findFirst()
            .orElseThrow();

        assertEquals(9.0, refreshed.lastValue());
        assertFalse(meterRegistry.find("explorer_metric_gauge")
            .tag("metric_id", refreshed.id())
            .meters()
            .isEmpty());
        assertEquals("C-42", meterRegistry.find("explorer_metric_gauge")
            .tag("metric_id", refreshed.id())
            .tag("customer_id", "C-42")
            .gauge()
            .getId()
            .getTag("customer_id"));
        assertEquals("READY", meterRegistry.find("explorer_metric_gauge")
            .tag("metric_id", refreshed.id())
            .tag("status", "READY")
            .gauge()
            .getId()
            .getTag("status"));
    }

    // ── CONSUMER_TIME_LAG — the backlog in time rather than in records ────────

    private static MetricConfig timeLagMetric(Map<String, Object> params) {
        return new MetricConfig(
            null, "orders_delay", "GAUGE", null, null, null, null, null, null, null,
            List.of(), Map.of(), null, "CONSUMER_TIME_LAG", params, null, null, List.of());
    }

    private static TopicTimeLag measured(long maxMs, long avgMs, int measured, int unknown) {
        return new TopicTimeLag("demo.orders", "orders-api",
            List.of(new PartitionTimeLag(0, 100L, 150L, 50L, maxMs, 1L, null)),
            maxMs, avgMs, measured, 0, 0, unknown, true, null, List.of());
    }

    @Test
    void timeLagMetricPublishesTheWorstPartitionAndLabelsBothPinnedValues() {
        Mockito.when(kafkaAdminService.getConsumerTimeLag("demo.orders", "orders-api"))
            .thenReturn(measured(62_000L, 31_000L, 2, 0));

        MetricPreviewResult preview = service.previewMetric(timeLagMetric(
            new LinkedHashMap<>(Map.of("topic", "demo.orders", "group", "orders-api"))));

        assertNull(preview.error());
        assertEquals(62_000.0, preview.value());
        assertEquals("MAX", preview.summary().get("aggregation"));
        // Both labels come from the configuration, so the series cannot change subject silently.
        assertEquals("demo.orders", preview.rows().get(0).get("topic"));
        assertEquals("orders-api", preview.rows().get(0).get("group"));
    }

    @Test
    void timeLagMetricHonoursTheAverageAggregation() {
        Mockito.when(kafkaAdminService.getConsumerTimeLag("demo.orders", "orders-api"))
            .thenReturn(measured(62_000L, 31_000L, 2, 0));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("topic", "demo.orders");
        params.put("group", "orders-api");
        params.put("aggregation", "AVG");

        assertEquals(31_000.0, service.previewMetric(timeLagMetric(params)).value());
    }

    @Test
    void timeLagMetricSaysWhenItsValueIsAFloorRatherThanAMaximum() {
        Mockito.when(kafkaAdminService.getConsumerTimeLag("demo.orders", "orders-api"))
            .thenReturn(new TopicTimeLag("demo.orders", "orders-api",
                List.of(), 62_000L, 62_000L, 1, 0, 0, 3, true, null,
                List.of("The read budget was spent before 3 partition(s) answered")));

        MetricPreviewResult preview = service.previewMetric(timeLagMetric(
            new LinkedHashMap<>(Map.of("topic", "demo.orders", "group", "orders-api"))));

        assertEquals(62_000.0, preview.value());
        assertEquals(false, preview.summary().get("complete"));
        assertTrue(String.valueOf(preview.summary().get("scopeNote")).contains("floor"),
            "a maximum over some of the partitions must not pass for the maximum");
    }

    @Test
    void aDelayThatCouldNotBeMeasuredIsAnErrorNeverAZero() {
        Mockito.when(kafkaAdminService.getConsumerTimeLag("demo.orders", "orders-api"))
            .thenReturn(TopicTimeLag.unavailable("demo.orders", "orders-api",
                "Group 'orders-api' has no committed offset on this topic."));

        MetricPreviewResult preview = service.previewMetric(timeLagMetric(
            new LinkedHashMap<>(Map.of("topic", "demo.orders", "group", "orders-api"))));

        assertNull(preview.value(), "0 would be exported as 'the backlog is gone'");
        assertTrue(preview.error().contains("no committed offset"), preview.error());
    }

    @Test
    void timeLagMetricRefusesAnUnnamedGroupAndANonGaugeType() {
        MetricPreviewResult missingGroup = service.previewMetric(timeLagMetric(
            new LinkedHashMap<>(Map.of("topic", "demo.orders"))));
        assertTrue(missingGroup.error().contains("group"), missingGroup.error());

        MetricConfig asCounter = new MetricConfig(
            null, "orders_delay", "COUNTER", null, null, null, null, null, null, null,
            List.of(), Map.of(), null, "CONSUMER_TIME_LAG",
            new LinkedHashMap<>(Map.of("topic", "demo.orders", "group", "orders-api")),
            null, null, List.of());
        assertTrue(service.previewMetric(asCounter).error().contains("GAUGE"));
    }

    /**
     * "No metric is configured" and "the metric configurations could not be read" are two
     * different answers, and only the first one may seed.
     *
     * <p>Seeding mints four metrics with fresh ids and writes them back to
     * {@code internal.metrics.config}. Doing that because the broker did not answer means adding
     * examples beside an operator's own metrics, on a topic this process failed to read — every
     * restart that catches the broker down leaving another four. The guard used to be
     * {@code metrics.isEmpty()} alone, which cannot tell the two apart; it was harmless only
     * because Flink holds no table at boot, and an accident is not a guard.
     */
    @Test
    void aRestoreThatFailedSeedsNothing() {
        MetricService failing = new MetricService(
            flinkSqlService, meterRegistry, kafkaConfig, explorerConfig,
            kafkaAdminService, messageFieldExtractorService, new StartupRestore(new ExplorerConfig())
        ) {
            @Override
            Consumer<String, String> createConsumer() {
                throw new IllegalStateException("no broker");
            }
        };

        failing.init();   // must not throw: an unreachable broker never keeps the app down

        assertTrue(failing.getAllMetrics().isEmpty(),
            "an unread configuration must not be mistaken for an empty one");
    }

    /** The same store, read successfully and genuinely empty, is the first-run case that seeds. */
    @Test
    void aRestoreThatCompletedOnAnEmptyTopicSeedsTheExamples() {
        MetricService fresh = newService();

        fresh.init();

        assertEquals(4, fresh.getAllMetrics().size());
    }

    // ── The two templates that compare the results of two queries ────────────────
    //
    // See METRICS-TWO-QUERY-AUDIT.md. Every case below was checked against the code it
    // describes: each fails on the revision before the fix and passes after it.

    /** A metric shaped like the ones MetricSuggestionService proposes. */
    private MetricConfig countDelta(Map<String, Object> extraParams) {
        Map<String, Object> params = new LinkedHashMap<>(Map.of(
            "leftSql", "SELECT COUNT(*) AS metric_value FROM topic_a",
            "rightSql", "SELECT COUNT(*) AS metric_value FROM topic_b",
            "operation", "LEFT_MINUS_RIGHT"
        ));
        params.putAll(extraParams);
        return new MetricConfig(
            null, "delta", "GAUGE", null, null, null, null, null, null, null, List.of(), Map.of(), null,
            "TOPIC_COUNT_DELTA", params, null, null, List.of());
    }

    private MetricConfig transitLatency(Map<String, Object> extraParams) {
        Map<String, Object> params = new LinkedHashMap<>(Map.of(
            "sourceSql", "SELECT order_id AS match_key, event_time AS event_time FROM topic_a",
            "targetSql", "SELECT order_id AS match_key, event_time AS event_time FROM topic_b"
        ));
        params.putAll(extraParams);
        return new MetricConfig(
            null, "latency", "GAUGE", null, null, null, null, null, null, null, List.of(), Map.of(), null,
            "TOPIC_TRANSIT_LATENCY", params, null, null, List.of());
    }

    /**
     * Answer each read by the table its SQL names, never by call order.
     *
     * <p>Which of the two sides is read first is a <em>decision of the code under test</em> — the
     * right one goes first so the gap can only be overstated, see D4 — so a stub handing values out
     * in call order silently swaps the two the day that decision changes. It did: eight tests
     * inverted at once, and each still asserted a number, so the suite reported a wrong delta as a
     * wrong expectation rather than as the ordering change it was. Keyed on the SQL, a test says
     * which topic holds what and stops caring when each is read.
     */
    // ── the window, the tail, the cadence and the shared read ────────────────

    @Test
    void aLatencyWindowReadsBothSidesFromTheSameInstant() {
        Mockito.when(flinkSqlService.executeSql(Mockito.any())).thenReturn(
            new QueryResult(List.of("match_key", "event_time"),
                List.of(Map.of("match_key", "A", "event_time", "2026-03-24T10:00:00Z")), 5L, null, false, "KAFKA_DIRECT"),
            new QueryResult(List.of("match_key", "event_time"),
                List.of(Map.of("match_key", "A", "event_time", "2026-03-24T10:00:05Z")), 5L, null, false, "KAFKA_DIRECT"));

        long before = System.currentTimeMillis();
        MetricPreviewResult preview = service.previewMetric(transitLatency(Map.of("windowMs", 600_000L)));

        assertNull(preview.error());
        assertEquals(5000.0, preview.value());
        /*
         * The point is not that each side gets a window but that they get the *same* one: a row
         * cap over two topics of different throughputs reads two different stretches of time, and
         * the match rate is then depressed by that misalignment as much as by a real loss. The
         * instant is computed once, so both requests carry it verbatim.
         */
        List<String> modes = capturedRequests().stream().map(QueryRequest::readMode).distinct().toList();
        assertEquals(1, modes.size(), "both sides must carry one instant, not one duration each: " + modes);
        String mode = modes.get(0);
        assertTrue(mode.startsWith("since:"), mode);
        long since = Long.parseLong(mode.substring("since:".length()));
        assertTrue(since >= before - 600_000L && since <= System.currentTimeMillis() - 600_000L, mode);
        assertEquals(600_000L, preview.summary().get("windowMs"));
        // A source produced near the end of the window has its target outside both reads, which is
        // not a defect and looks exactly like one — so it is named rather than corrected.
        assertTrue(String.valueOf(preview.summary().get("scopeNote")).contains("trailing edge"),
            String.valueOf(preview.summary().get("scopeNote")));
    }

    @Test
    void aWindowIsRefusedOnAQueryTheDirectReaderCannotAnswer() {
        // A window is a direct-reader instruction, and a window silently ignored on one side is
        // worse than none: the summary would claim one stretch of time while the reads covered two.
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> service.save(withIdAndType(transitLatency(Map.of(
                "windowMs", 600_000L,
                "targetSql", "SELECT a.id AS match_key, a.ts AS event_time FROM a JOIN b ON a.id = b.id")),
                "win", "GAUGE")));
        assertTrue(refused.getMessage().contains("target"), refused.getMessage());
        assertTrue(refused.getMessage().contains("windowMs"), refused.getMessage());
    }

    @Test
    void aGaugeLatencyPublishesItsP95BecauseNothingElseCarriesOne() {
        Mockito.when(flinkSqlService.executeSql(Mockito.any())).thenReturn(
            new QueryResult(List.of("match_key", "event_time"),
                List.of(Map.of("match_key", "A", "event_time", "2026-03-24T10:00:00Z"),
                        Map.of("match_key", "B", "event_time", "2026-03-24T10:00:00Z")), 5L, null, false, "KAFKA_DIRECT"),
            new QueryResult(List.of("match_key", "event_time"),
                List.of(Map.of("match_key", "A", "event_time", "2026-03-24T10:00:01Z"),
                        Map.of("match_key", "B", "event_time", "2026-03-24T10:00:20Z")), 5L, null, false, "KAFKA_DIRECT"));

        service.save(withIdAndType(transitLatency(Map.of()), "lat", "GAUGE"));
        service.refreshMetric("lat");

        // The average holds still while the worst decile doubles, which is the case the template
        // exists to catch — and the p95 was computed, put in the summary, and alerted on by nobody.
        Gauge p95 = meterRegistry.find("explorer_metric_correlation_latency_p95_ms")
            .tag("metric_id", "lat").gauge();
        assertNotNull(p95, "a GAUGE latency carries no quantiles of its own");
        assertEquals(20_000.0, p95.value());
    }

    @Test
    void aSummaryLatencyPublishesNoP95CompanionBecauseItAlreadyHasOne() {
        Mockito.when(flinkSqlService.executeSql(Mockito.any())).thenReturn(
            new QueryResult(List.of("match_key", "event_time"),
                List.of(Map.of("match_key", "A", "event_time", "2026-03-24T10:00:00Z")), 5L, null, false, "KAFKA_DIRECT"),
            new QueryResult(List.of("match_key", "event_time"),
                List.of(Map.of("match_key", "A", "event_time", "2026-03-24T10:00:01Z")), 5L, null, false, "KAFKA_DIRECT"));

        service.save(withIdAndType(transitLatency(Map.of()), "lat-summary", "SUMMARY"));
        service.refreshMetric("lat-summary");

        // explorer_metric_summary{quantile="0.95"} already answers this. Two answers to one
        // question is the shape this codebase keeps removing.
        assertNull(meterRegistry.find("explorer_metric_correlation_latency_p95_ms")
            .tag("metric_id", "lat-summary").gauge());
    }

    @Test
    void aMetricWithItsOwnIntervalIsSkippedUntilItIsDue() {
        stubBySql(Map.of("topic_a", directCount(12.0), "topic_b", directCount(7.0)));

        service.save(withIdAndType(countDelta(Map.of("refreshIntervalMs", 3_600_000L)), "slow", "GAUGE"));
        service.refreshMetrics();
        int afterFirst = Mockito.mockingDetails(flinkSqlService).getInvocations().size();
        service.refreshMetrics();
        int afterSecond = Mockito.mockingDetails(flinkSqlService).getInvocations().size();

        // Two topics read every thirty seconds because a single-row gauge beside them wants that
        // cadence is how the refresh loop becomes the most expensive thing this application does.
        assertEquals(afterFirst, afterSecond, "an hourly metric must not read the broker again a moment later");
        assertNotNull(service.getAllMetrics().stream()
            .filter(m -> "slow".equals(m.id())).findFirst().orElseThrow().lastValue(),
            "skipping keeps the value it was last measured at — it was measured, just not now");
    }

    @Test
    void pressingRefreshIgnoresTheMetricsOwnInterval() {
        stubBySql(Map.of("topic_a", directCount(12.0), "topic_b", directCount(7.0)));

        service.save(withIdAndType(countDelta(Map.of("refreshIntervalMs", 3_600_000L)), "slow", "GAUGE"));
        service.refreshMetrics();
        int afterCycle = Mockito.mockingDetails(flinkSqlService).getInvocations().size();

        service.refreshMetric("slow");

        // An explicit gesture is never a cadence to be rationed.
        assertTrue(Mockito.mockingDetails(flinkSqlService).getInvocations().size() > afterCycle);
    }

    @Test
    void anIntervalOutsideItsBoundsIsRefusedAtSaveTime() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> service.save(withIdAndType(countDelta(Map.of("refreshIntervalMs", 10L)), "tick", "GAUGE")));
        assertTrue(refused.getMessage().contains("refreshIntervalMs"), refused.getMessage());
    }

    @Test
    void editingWhatTheTwoQueriesMeasureDropsTheIntervalBaseline() {
        stubBySql(Map.of("topic_a", directCount(100.0), "topic_b", directCount(90.0)));
        MetricConfig metric = withIdAndType(
            countDelta(Map.of("window", "SINCE_LAST_REFRESH")), "windowed", "GAUGE");
        service.save(metric);
        service.refreshMetric("windowed");   // establishes the baseline, publishes nothing
        service.refreshMetric("windowed");   // reports the interval

        // Re-saving with different queries makes it a different measurement, so the baseline it
        // would subtract from describes a question this metric no longer asks.
        service.save(withIdAndType(countDelta(Map.of(
            "window", "SINCE_LAST_REFRESH",
            "leftSql", "SELECT COUNT(*) AS metric_value FROM topic_a WHERE status = 'OK'")),
            "windowed", "GAUGE"));
        service.refreshMetric("windowed");

        MetricConfig after = service.getAllMetrics().stream()
            .filter(m -> "windowed".equals(m.id())).findFirst().orElseThrow();
        assertNull(after.lastValue());
        assertTrue(String.valueOf(after.errorMessage()).contains("Baseline established"),
            String.valueOf(after.errorMessage()));
    }

    @Test
    void theTwoSidesAreKeptAsSeriesBesideTheValueTheyMake() {
        stubBySql(Map.of("topic_a", directCount(12.0), "topic_b", directCount(7.0)));
        service.save(withIdAndType(countDelta(Map.of()), "gap", "GAUGE"));
        service.refreshMetric("gap");
        service.refreshMetric("gap");

        MetricConfig saved = service.getAllMetrics().stream()
            .filter(m -> "gap".equals(m.id())).findFirst().orElseThrow();
        // history holds the *comparison*; what an operator needs to see move is the two counts.
        assertEquals(List.of(5.0, 5.0), saved.history());
        assertEquals(List.of(12.0, 12.0), saved.componentHistory().get("leftValue"));
        assertEquals(List.of(7.0, 7.0), saved.componentHistory().get("rightValue"));
    }

    @Test
    void aRefreshThatMeasuredNoComponentAppendsNullRatherThanZero() {
        // Two cycles that measure the sides, then one that fails: a zero there would draw a fall
        // to nothing that never happened, on the metric whose whole job is to report a fall.
        AtomicInteger call = new AtomicInteger();
        Mockito.when(flinkSqlService.executeSql(Mockito.any())).thenAnswer(invocation -> {
            QueryRequest request = invocation.getArgument(0);
            boolean left = request.sql() != null && request.sql().contains("topic_a");
            return call.incrementAndGet() > 4
                ? new QueryResult(List.of(), List.of(), 0L, "broker went away")
                : directCount(left ? 12.0 : 7.0);
        });

        service.save(withIdAndType(countDelta(Map.of()), "gap", "GAUGE"));
        service.refreshMetric("gap");
        service.refreshMetric("gap");
        service.refreshMetric("gap");

        MetricConfig saved = service.getAllMetrics().stream()
            .filter(m -> "gap".equals(m.id())).findFirst().orElseThrow();
        // A failed refresh publishes no value at all, so nothing is appended anywhere and every
        // series stays exactly as long as the history it is drawn against.
        assertEquals(2, saved.history().size());
        saved.componentHistory().forEach((key, values) ->
            assertEquals(saved.history().size(), values.size(), key + " must stay aligned with history"));
    }

    @Test
    void aSeriesSeenForTheFirstTimeIsBackFilledSoTheIndexStaysOneRefresh() {
        // A latency metric edited into a gap changes which components exist. Index i has to keep
        // meaning "the same refresh" in every series, or the chart shifts one line against another.
        Mockito.when(flinkSqlService.executeSql(Mockito.any())).thenReturn(
            new QueryResult(List.of("match_key", "event_time"),
                List.of(Map.of("match_key", "A", "event_time", "2026-03-24T10:00:00Z")), 5L, null, false, "KAFKA_DIRECT"),
            new QueryResult(List.of("match_key", "event_time"),
                List.of(Map.of("match_key", "A", "event_time", "2026-03-24T10:00:01Z")), 5L, null, false, "KAFKA_DIRECT"));
        service.save(withIdAndType(transitLatency(Map.of()), "shifting", "GAUGE"));
        service.refreshMetric("shifting");

        Mockito.reset(flinkSqlService);
        wirePairDelegation();
        Mockito.when(flinkSqlService.listTables()).thenReturn(List.of("demo_orders_in"));
        stubBySql(Map.of("topic_a", directCount(12.0), "topic_b", directCount(7.0)));
        service.save(withIdAndType(countDelta(Map.of()), "shifting", "GAUGE"));
        service.refreshMetric("shifting");

        MetricConfig saved = service.getAllMetrics().stream()
            .filter(m -> "shifting".equals(m.id())).findFirst().orElseThrow();
        saved.componentHistory().forEach((key, values) ->
            assertEquals(saved.history().size(), values.size(), key + " must stay aligned with history"));
        // The series that did not exist on the first refresh carries a null for it, not a value.
        assertNull(saved.componentHistory().get("leftValue").get(0));
        assertEquals(12.0, saved.componentHistory().get("leftValue").get(1));
    }

    @Test
    void twoCountsOverOneTopicComeOutOfOneReadAndSayTheyDid() {
        // doReturn, not when(...): the delegating stub wired in setUp would run inside when()'s
        // own argument evaluation and Mockito would read its answer as the return value.
        Mockito.doReturn(new FlinkSqlService.QueryPair(directCount(7.0), directCount(12.0), true))
            .when(flinkSqlService).executeSqlPair(Mockito.any(), Mockito.any());

        MetricPreviewResult preview = service.previewMetric(countDelta(Map.of(
            "leftSql", "SELECT COUNT(*) AS metric_value FROM topic_a WHERE status = 'OK'",
            "rightSql", "SELECT COUNT(*) AS metric_value FROM topic_a WHERE status = 'KO'")));

        assertNull(preview.error());
        assertEquals(5.0, preview.value());
        // Not "nobody measured the gap" but "there is none": one read served both, so the two
        // counts describe the same instant — the same thing the offsets count gets, for a
        // different reason.
        assertEquals(0L, preview.summary().get("readGapMs"));
        // Stated, not inferred from the zero: two separate reads can land in one millisecond, and
        // the card grades "same instant" off this flag rather than off the number.
        assertEquals(Boolean.TRUE, preview.summary().get("sharedScan"));
        assertTrue(String.valueOf(preview.summary().get("scopeNote")).contains("same instant"),
            String.valueOf(preview.summary().get("scopeNote")));
    }

    private void stubBySql(Map<String, QueryResult> byTableMarker) {
        Mockito.when(flinkSqlService.executeSql(Mockito.any()))
            .thenAnswer(invocation -> answerFor(byTableMarker, invocation.getArgument(0)));
    }

    /**
     * The pair entry point delegates to {@code executeSql}, so every stub in this class keeps
     * describing both paths.
     *
     * <p>The count delta asks the engine for its two sides as a pair, so that two counts over one
     * topic can come out of a single read. Stubbing that separately would mean every test stating
     * its reads twice, and the two statements drifting is exactly what the {@code stubBySql}
     * rewrite was needed for once already. Delegating keeps one rule — including the consecutive
     * {@code thenReturn(a, b)} form, which still yields right side then left, the real order — and
     * reports no sharing, since a mock reads no records. The test that asserts sharing overrides
     * this; so does any test that resets the mock.
     */
    private void wirePairDelegation() {
        Mockito.when(flinkSqlService.executeSqlPair(Mockito.any(), Mockito.any()))
            .thenAnswer(invocation -> new FlinkSqlService.QueryPair(
                flinkSqlService.executeSql(invocation.getArgument(0)),
                flinkSqlService.executeSql(invocation.getArgument(1)),
                false));
    }

    private static QueryResult answerFor(Map<String, QueryResult> byTableMarker, QueryRequest request) {
        String sql = request == null || request.sql() == null ? "" : request.sql();
        return byTableMarker.entrySet().stream()
            .filter(e -> sql.contains(e.getKey()))
            .map(Map.Entry::getValue)
            .findFirst()
            // A query no marker names becomes a failed read rather than a thrown Error:
            // refreshMetrics() also refreshes the seeded example metrics, whose SQL no test
            // stubs, and the refresh loop catches Exception — not Error. A mis-keyed stub then
            // surfaces as this message inside the assertion that was going to read the value.
            .orElse(new QueryResult(List.of(), List.of(), 0L, "no stub matches this query: " + sql));
    }

    /** The same metric under a known id and Prometheus type, so a meter can be looked up. */
    private MetricConfig withIdAndType(MetricConfig metric, String id, String type) {
        return new MetricConfig(
            id, metric.name(), type, metric.sql(), metric.description(),
            metric.warningThreshold(), metric.criticalThreshold(), null, null, null,
            List.of(), Map.of(), metric.createTableSql(), metric.templateType(),
            metric.templateParams(), metric.executionMode(), metric.labelTopic(), List.of());
    }

    private static QueryResult flinkRows(List<Map<String, Object>> rows) {
        return new QueryResult(List.of("metric_value"), rows, 10L, null, false, "FLINK");
    }

    private static QueryResult directCount(double value) {
        return new QueryResult(List.of("metric_value"),
            List.of(Map.of("metric_value", value)), 10L, null, false, "KAFKA_DIRECT");
    }

    private List<QueryRequest> capturedRequests() {
        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        Mockito.verify(flinkSqlService, Mockito.atLeastOnce()).executeSql(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void theScanHintCarriesOnlyWhatThisConnectorCanExpress() {
        Mockito.when(flinkSqlService.executeSql(Mockito.any())).thenReturn(directCount(12.0), directCount(7.0));

        service.previewMetric(countDelta(Map.of()));

        for (QueryRequest request : capturedRequests()) {
            assertTrue(request.sql().contains("'scan.startup.mode'='earliest-offset'"), request.sql());
            /*
             * scan.bounded.mode is what would end the scan, and this connector does not have it:
             * flink-connector-kafka:5.0.0-2.2 answers a hint carrying it with "Unsupported options
             * found for 'kafka'", which FlinkSqlService reads as an engine failure and swallows by
             * falling back — so the caller sees rows and no error, and three of them trip the
             * process-wide circuit breaker. KafkaClusterIntegrationTest measures that; this pins
             * that the option is not sent meanwhile.
             */
            assertFalse(request.sql().contains("scan.bounded.mode"),
                "an option this connector rejects must not travel: " + request.sql());
        }
    }

    @Test
    void aCountIsTheLastChangelogRowNotTheFirst() {
        // What a streaming COUNT(*) really returns: +I(1), then -U/+U per record. The kind is
        // dropped by the collector, so the rows arrive as plain values in order.
        stubBySql(Map.of(
            "topic_a", flinkRows(List.of(
                Map.of("metric_value", 1.0), Map.of("metric_value", 1.0),
                Map.of("metric_value", 2.0), Map.of("metric_value", 2.0),
                Map.of("metric_value", 3.0))),
            "topic_b", flinkRows(List.of(Map.of("metric_value", 1.0), Map.of("metric_value", 1.0)))));

        MetricPreviewResult preview = service.previewMetric(countDelta(Map.of()));

        assertNull(preview.error());
        assertEquals(3.0, preview.summary().get("leftValue"), "the final aggregate, not +I(1)");
        assertEquals(1.0, preview.summary().get("rightValue"));
        assertEquals(2.0, preview.value());
    }

    @Test
    void aChangelogThatFilledItsRowBudgetIsRefusedRatherThanReadAsATotal() {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 1; i <= 5; i++) rows.add(Map.of("metric_value", (double) i));
        // A join is what the planner really answers here — the generated shape goes to the direct
        // reader, which returns one row and cannot truncate a changelog it never produces.
        stubBySql(Map.of("JOIN", flinkRows(rows), "topic_b", directCount(2.0)));

        MetricPreviewResult preview = service.previewMetric(countDelta(Map.of(
            "maxRowsPerSide", "5",
            "leftSql", "SELECT COUNT(*) AS metric_value FROM a JOIN b ON a.id = b.id")));

        assertNotNull(preview.error());
        assertTrue(preview.error().contains("maxRowsPerSide"), preview.error());
        assertTrue(preview.error().contains("partial count"), preview.error());
    }

    @Test
    void aCountThatStoppedOnTheReadersCeilingIsNeverComparedToAnother() {
        QueryResult capped = directCount(100_000.0)
            .withWarnings(List.of(FlinkSqlService.AGGREGATE_SCAN_CAPPED + " — the aggregate covers the first 100000 record(s)"));
        stubBySql(Map.of("topic_a", capped, "topic_b", directCount(100_000.0)));

        MetricPreviewResult preview = service.previewMetric(countDelta(Map.of("operation", "PERCENT_GAP")));

        // Two floors differ by nothing, and "no gap" is the one answer this alarm must not invent.
        assertNotNull(preview.error());
        assertTrue(preview.error().contains("floor"), preview.error());
        assertNull(preview.value());
    }

    /*
     * There was a case here for a degrade-once latch: send the bounded scan option, and on a
     * connector that refuses it retry without and remember. It was removed with the latch, because
     * the measurement showed the refusal never reaches this class — FlinkSqlService classifies it
     * as an engine failure and falls back to the direct reader, so what comes back carries rows
     * and no error. A latch that cannot observe its own trigger is not a safety net; the option is
     * simply not sent. See theScanHintCarriesOnlyWhatThisConnectorCanExpress above, and the
     * differential in KafkaClusterIntegrationTest that established it.
     */

    @Test
    void theLatencyTemplateReadsTheMostRecentRecordsAndTheCountReadsFromTheStart() {
        Mockito.when(flinkSqlService.executeSql(Mockito.any())).thenReturn(
            new QueryResult(List.of("match_key", "event_time"),
                List.of(Map.of("match_key", "A", "event_time", "2026-03-24T10:00:00Z")), 5L, null, false, "KAFKA_DIRECT"),
            new QueryResult(List.of("match_key", "event_time"),
                List.of(Map.of("match_key", "A", "event_time", "2026-03-24T10:00:05Z")), 5L, null, false, "KAFKA_DIRECT"));

        MetricPreviewResult preview = service.previewMetric(transitLatency(Map.of()));

        assertNull(preview.error());
        assertEquals(5000.0, preview.value());
        for (QueryRequest request : capturedRequests()) {
            assertEquals("latest-offset", request.readMode(),
                "a latency is a question about now, so it reads the recent end");
        }

        Mockito.reset(flinkSqlService);
        wirePairDelegation();
        Mockito.when(flinkSqlService.executeSql(Mockito.any())).thenReturn(directCount(1.0), directCount(1.0));
        service.previewMetric(countDelta(Map.of()));
        for (QueryRequest request : capturedRequests()) {
            assertEquals("earliest-offset", request.readMode(), "a count must see the whole topic");
        }
    }

    @Test
    void aSingleTableReadIsAskedOfTheDirectReaderAndAnythingElseIsNot() {
        assertTrue(MetricService.isSingleTableRead("SELECT COUNT(*) AS metric_value FROM orders"));
        assertTrue(MetricService.isSingleTableRead(
            "SELECT id AS match_key, ts AS event_time\nFROM demo_orders WHERE status = 'OK'"));
        // Shapes whose rows the direct reader would silently get wrong.
        assertFalse(MetricService.isSingleTableRead("SELECT COUNT(*) AS metric_value FROM a JOIN b ON a.id = b.id"));
        assertFalse(MetricService.isSingleTableRead("SELECT COUNT(*) AS metric_value FROM a, b"));
        assertFalse(MetricService.isSingleTableRead(
            "SELECT COUNT(*) AS metric_value FROM (SELECT id FROM orders)"));
        assertFalse(MetricService.isSingleTableRead(
            "WITH recent AS (SELECT * FROM orders) SELECT COUNT(*) AS metric_value FROM recent"));
        assertFalse(MetricService.isSingleTableRead("SELECT 1 AS metric_value"));

        stubBySql(Map.of("topic_a", directCount(2.0), "JOIN", flinkRows(List.of(Map.of("metric_value", 1.0)))));
        service.previewMetric(countDelta(Map.of(
            "rightSql", "SELECT COUNT(*) AS metric_value FROM a JOIN b ON a.id = b.id")));

        Map<Boolean, QueryRequest> bySide = capturedRequests().stream()
            .collect(java.util.stream.Collectors.toMap(r -> r.sql().contains("topic_a"), r -> r, (a, b) -> a));
        assertTrue(bySide.get(true).wantsDirectRead(), "the generated shape goes to the direct reader");
        assertFalse(bySide.get(false).wantsDirectRead(), "a join needs the planner, whatever it costs");
    }

    @Test
    void theLatencySummarySaysWhatTheTwoReadsCoveredAndWhatTheyDropped() {
        Mockito.when(flinkSqlService.executeSql(Mockito.any())).thenReturn(
            new QueryResult(List.of("match_key", "event_time"),
                List.of(Map.of("match_key", "A", "event_time", "2026-03-24T10:00:00Z"),
                        // A row the projection did not alias: dropped in silence before this.
                        Map.of("other", "B")),
                5L, null, false, "KAFKA_DIRECT"),
            new QueryResult(List.of("match_key", "event_time"),
                List.of(Map.of("match_key", "A", "event_time", "2026-03-24T10:00:02Z")), 5L, null, false, "KAFKA_DIRECT"));

        MetricPreviewResult preview = service.previewMetric(transitLatency(Map.of()));

        assertNull(preview.error());
        assertEquals(2, preview.summary().get("sourceRowsRead"));
        assertEquals(1, preview.summary().get("sourceEventsUsed"));
        assertEquals("KAFKA_DIRECT", preview.summary().get("sourceEngine"));
        assertTrue(String.valueOf(preview.summary().get("scopeNote")).contains("most recent"));
    }

    @Test
    void anEmptySideNamesItselfAndTheReadItCameFrom() {
        Mockito.when(flinkSqlService.executeSql(Mockito.any())).thenReturn(
            new QueryResult(List.of("match_key", "event_time"),
                List.of(Map.of("match_key", "A", "event_time", "2026-03-24T10:00:00Z")), 5L, null, false, "KAFKA_DIRECT"),
            new QueryResult(List.of("match_key", "event_time"), List.of(), 5L, null, false, "KAFKA_DIRECT"));

        MetricPreviewResult preview = service.previewMetric(transitLatency(Map.of()));

        assertNotNull(preview.error());
        assertTrue(preview.error().startsWith("The target query"), preview.error());
        assertTrue(preview.error().contains("most recent"), preview.error());
    }

    @Test
    void theScanParametersAreRefusedWhenTheMetricIsSavedRatherThanOnEveryRefresh() {
        assertThrows(IllegalArgumentException.class,
            () -> service.save(countDelta(Map.of("maxRowsPerSide", "10k"))));
        assertThrows(IllegalArgumentException.class,
            () -> service.save(countDelta(Map.of("maxRowsPerSide", "0"))));
        assertThrows(IllegalArgumentException.class,
            () -> service.save(countDelta(Map.of("timeoutMs", "10"))));
        assertThrows(IllegalArgumentException.class,
            () -> service.save(countDelta(Map.of("readMode", "group-offsets"))));
        // The operation was the last one still checked from inside the refresh loop, where an
        // unrecognised value threw every thirty seconds on a metric the API had accepted.
        IllegalArgumentException badOperation = assertThrows(IllegalArgumentException.class,
            () -> service.save(countDelta(Map.of("operation", "LEFT_OVER_RIGHT"))));
        assertTrue(badOperation.getMessage().contains("PERCENT_GAP"), badOperation.getMessage());
        // And the ordinary ones still save, whatever the case they are written in.
        service.save(countDelta(Map.of("maxRowsPerSide", "50000", "readMode", "latest-offset")));
        service.save(countDelta(Map.of("operation", "percent_gap")));
    }

    @Test
    void aRightSideOfZeroSaysWhatToUseInstead() {
        stubBySql(Map.of("topic_a", directCount(41.0), "topic_b", directCount(0.0)));

        MetricPreviewResult preview = service.previewMetric(countDelta(Map.of("operation", "RATIO")));

        assertNotNull(preview.error());
        assertTrue(preview.error().contains("LEFT_MINUS_RIGHT"), preview.error());
        assertTrue(preview.error().contains("41"), preview.error());
    }

    @Test
    void everythingProducedAndNothingArrivedIsAHundredPercent() {
        stubBySql(Map.of("topic_a", directCount(41.0), "topic_b", directCount(0.0)));

        MetricPreviewResult preview = service.previewMetric(countDelta(Map.of("operation", "PERCENT_GAP")));

        // The state this metric exists to catch was the one it refused to publish: PERCENT_GAP
        // divides by the right side, so total loss came back as a division by zero and the alert
        // stayed silent on it while firing happily at 3 %.
        assertNull(preview.error());
        assertEquals(100.0, preview.value());
        assertEquals(Boolean.TRUE, preview.summary().get("totalLoss"));
        assertTrue(String.valueOf(preview.summary().get("scopeNote")).contains("100 %"),
            String.valueOf(preview.summary().get("scopeNote")));
    }

    @Test
    void neitherSideProducingAnythingIsNotALoss() {
        stubBySql(Map.of("topic_a", directCount(0.0), "topic_b", directCount(0.0)));

        MetricPreviewResult preview = service.previewMetric(countDelta(Map.of("operation", "PERCENT_GAP")));

        // Nothing was produced and nothing was missed. Reporting 100 % here would wake somebody
        // for a pipeline that is merely idle.
        assertNull(preview.error());
        assertEquals(0.0, preview.value());
        assertNull(preview.summary().get("totalLoss"));
    }


    // ── D4, D6, D7, D8 ───────────────────────────────────────────────────────────

    private QueryResult correlationRows(List<Map<String, Object>> rows) {
        return new QueryResult(List.of("match_key", "event_time"), rows, 5L, null, false, "KAFKA_DIRECT");
    }

    private static Map<String, Object> event(String key, String time) {
        return Map.of("match_key", key, "event_time", time);
    }

    @Test
    void theRightSideIsCountedFirstSoTheGapCanOnlyBeOverstated() {
        List<String> order = new java.util.ArrayList<>();
        Mockito.when(flinkSqlService.executeSql(Mockito.any())).thenAnswer(invocation -> {
            QueryRequest request = invocation.getArgument(0);
            order.add(request.sql().contains("topic_a") ? "left" : "right");
            return directCount(order.size() == 1 ? 7.0 : 12.0);
        });

        MetricPreviewResult preview = service.previewMetric(countDelta(Map.of("operation", "PERCENT_GAP")));

        // Reading the left side last lets the interval's traffic land in the numerator, so a gap
        // that survives is real. The other order hides the loss the metric exists to report.
        assertEquals(List.of("right", "left"), order);
        assertNull(preview.error());
        assertEquals(12.0, preview.summary().get("leftValue"));
        assertEquals(7.0, preview.summary().get("rightValue"));
        assertNotNull(preview.summary().get("readGapMs"));
        assertTrue(String.valueOf(preview.summary().get("scopeNote")).contains("only be overstated"),
            String.valueOf(preview.summary().get("scopeNote")));
    }

    @Test
    void anAbsDiffSaysItsErrorCanGoEitherWayBecauseNoOrderingHelpsIt() {
        stubBySql(Map.of("topic_a", directCount(12.0), "topic_b", directCount(7.0)));

        MetricPreviewResult preview = service.previewMetric(countDelta(Map.of("operation", "ABS_DIFF")));

        assertTrue(String.valueOf(preview.summary().get("scopeNote")).contains("either way"),
            String.valueOf(preview.summary().get("scopeNote")));
    }

    @Test
    void aLatencyReportsWhatItCouldNotPairRatherThanAveragingWhatItCould() {
        Mockito.when(flinkSqlService.executeSql(Mockito.any())).thenReturn(
            correlationRows(List.of(
                event("A", "2026-03-24T10:00:00Z"),
                event("B", "2026-03-24T10:00:00Z"),
                event("C", "2026-03-24T10:00:00Z"),
                event("D", "2026-03-24T10:00:10Z"))),
            correlationRows(List.of(
                event("A", "2026-03-24T10:00:01Z"),
                // Stamped before its source: a clock disagreement, counted rather than absorbed.
                event("D", "2026-03-24T10:00:05Z"),
                // Claimed by no source at all.
                event("Z", "2026-03-24T10:00:09Z"))));

        MetricPreviewResult preview = service.previewMetric(transitLatency(Map.of()));

        assertNull(preview.error());
        assertEquals(1000.0, preview.value(), "only A paired, and the average is of A alone");
        assertEquals(1, preview.summary().get("matchedCount"));
        assertEquals(3, preview.summary().get("unmatchedSourceCount"));
        assertEquals(1, preview.summary().get("outOfOrderCount"));
        assertEquals(1, preview.summary().get("unmatchedTargetCount"));
        // The rate is what stops 1 000 ms being read as a verdict on the pipeline.
        assertEquals(0.25, (Double) preview.summary().get("matchRate"), 1e-9);
    }

    @Test
    void aLatencyThatPairedNothingSaysWhatItReadAndWhetherAClockDisagreed() {
        Mockito.when(flinkSqlService.executeSql(Mockito.any())).thenReturn(
            correlationRows(List.of(event("A", "2026-03-24T10:00:10Z"))),
            correlationRows(List.of(event("A", "2026-03-24T10:00:00Z"))));

        MetricPreviewResult preview = service.previewMetric(transitLatency(Map.of()));

        assertNotNull(preview.error());
        assertTrue(preview.error().contains("clock disagreement"), preview.error());
    }

    @Test
    void aDistributionRecordsEachObservationOnceEvenWhenTheWindowSlides() {
        service.save(withIdAndType(transitLatency(Map.of()), "latency-1", "SUMMARY"));

        // Two refreshes of a window that slides: the first observation falls off the front, a new
        // one arrives at the back, and the count never changes — which positional dedup reads as
        // "nothing new" for ever.
        Mockito.when(flinkSqlService.executeSql(Mockito.any())).thenReturn(
            correlationRows(List.of(event("A", "2026-03-24T10:00:00Z"), event("B", "2026-03-24T10:00:10Z"))),
            correlationRows(List.of(event("A", "2026-03-24T10:00:01Z"), event("B", "2026-03-24T10:00:12Z"))),
            correlationRows(List.of(event("B", "2026-03-24T10:00:10Z"), event("C", "2026-03-24T10:00:20Z"))),
            correlationRows(List.of(event("B", "2026-03-24T10:00:12Z"), event("C", "2026-03-24T10:00:23Z"))));

        service.refreshMetric("latency-1");
        service.refreshMetric("latency-1");

        DistributionSummary summary = meterRegistry.find("explorer_metric_summary")
            .tag("metric_id", "latency-1").summary();
        assertNotNull(summary);
        // A (1 s), B (2 s) and C (3 s): three observations, B recorded once across both cycles.
        assertEquals(3, summary.count());
        assertEquals(6000.0, summary.totalAmount(), 1.0);
    }

    @Test
    void aSuccessfulRefreshDatesItselfSoAFrozenGaugeCanBeToldFromAFreshOne() {
        service.save(withIdAndType(countDelta(Map.of()), "delta-1", "GAUGE"));
        stubBySql(Map.of("topic_a", directCount(12.0), "topic_b", directCount(7.0)));
        service.refreshMetric("delta-1");

        Gauge lastSuccess = meterRegistry.find("explorer_metric_last_success_timestamp_seconds")
            .tag("metric_id", "delta-1").gauge();
        assertNotNull(lastSuccess, "an alert cannot date a gauge nothing dates");
        double firstSeen = lastSuccess.value();
        assertTrue(firstSeen > 1_700_000_000.0, "epoch seconds, not millis: " + firstSeen);

        // A refresh that fails keeps the previous value — deliberately — and must not move the
        // timestamp, or the staleness the series exists to expose would be papered over.
        Mockito.reset(flinkSqlService);
        Mockito.when(flinkSqlService.executeSql(Mockito.any()))
            .thenReturn(new QueryResult(List.of(), List.of(), 1L, "Broker unreachable"));
        service.refreshMetric("delta-1");

        assertEquals(firstSeen, lastSuccess.value());
        assertEquals(5.0, meterRegistry.find("explorer_metric_gauge").tag("metric_id", "delta-1").gauge().value(),
            "the value stays frozen, which is why it has to be dated");
    }

    @Test
    void theMatchRateIsExportedBesideTheLatencyRatherThanOnlySummarised() {
        service.save(withIdAndType(transitLatency(Map.of()), "latency-2", "GAUGE"));
        Mockito.when(flinkSqlService.executeSql(Mockito.any())).thenReturn(
            correlationRows(List.of(event("A", "2026-03-24T10:00:00Z"), event("B", "2026-03-24T10:00:00Z"))),
            correlationRows(List.of(event("A", "2026-03-24T10:00:01Z"))));

        service.refreshMetric("latency-2");

        Gauge rate = meterRegistry.find("explorer_metric_correlation_match_rate")
            .tag("metric_id", "latency-2").gauge();
        assertNotNull(rate);
        assertEquals(0.5, rate.value(), 1e-9);
        // And it carries the metric's identity only — a companion must not multiply with the
        // label series it describes.
        assertEquals(3, rate.getId().getTags().size());
    }


    // ── Counting by offsets, and comparing a window ──────────────────────────────

    /** A count-delta naming its two topics, which is what an offset count needs. */
    private MetricConfig offsetDelta(Map<String, Object> extraParams) {
        Map<String, Object> params = new LinkedHashMap<>(Map.of(
            "leftSql", "SELECT COUNT(*) AS metric_value\nFROM topic_a",
            "rightSql", "SELECT COUNT(*) AS metric_value\nFROM topic_b",
            "leftTopic", "demo.orders.1.received",
            "rightTopic", "demo.orders.2.validated",
            "operation", "LEFT_MINUS_RIGHT"
        ));
        params.putAll(extraParams);
        return new MetricConfig(
            null, "gap", "GAUGE", null, null, null, null, null, null, null, List.of(), Map.of(), null,
            "TOPIC_COUNT_DELTA", params, null, null, List.of());
    }

    /**
     * A broker that answers, described through both contracts.
     *
     * <p>{@code getTopicRecordCounts} is what the offset count reads — it omits what it could not
     * measure, which is what lets a failed read be refused instead of published as two zeros.
     * {@code getTopicsSize} is stubbed alongside it because it is the same measurement under the
     * lenient contract, and a helper that described only one of them would let the two drift.
     */
    private void stubOffsets(long left, long right) throws Exception {
        Map<String, Long> counts = Map.of(
            "demo.orders.1.received", left, "demo.orders.2.validated", right);
        Mockito.when(kafkaAdminService.listTopics())
            .thenReturn(List.of("demo.orders.1.received", "demo.orders.2.validated"));
        Mockito.when(kafkaAdminService.getTopicRecordCounts(Mockito.anyList())).thenReturn(counts);
        Mockito.when(kafkaAdminService.getTopicsSize(Mockito.anyList())).thenReturn(counts);
    }

    @Test
    void aPlainWholeTopicCountIsAnsweredByTheLogsOffsetsAndReadsNoRecord() throws Exception {
        stubOffsets(1_500_000L, 1_499_000L);

        MetricPreviewResult preview = service.previewMetric(offsetDelta(Map.of()));

        assertNull(preview.error());
        assertEquals(1000.0, preview.value());
        assertEquals("OFFSETS", preview.summary().get("countedBy"));
        assertEquals("KAFKA_OFFSETS", preview.summary().get("engine"));
        // No query ran at all: no record read, no parsing, and no 100 000-record ceiling — which
        // is what makes a topic of this size countable in the first place.
        Mockito.verify(flinkSqlService, Mockito.never()).executeSql(Mockito.any());
        // And both sides come out of one call, so there is no interval to lean.
        assertTrue(String.valueOf(preview.summary().get("scopeNote")).contains("same instant"));
    }

    @Test
    void aQueryThatFiltersIsStillCountedByReadingRecords() {
        Mockito.when(flinkSqlService.executeSql(Mockito.any())).thenReturn(directCount(7.0), directCount(12.0));

        MetricPreviewResult preview = service.previewMetric(offsetDelta(Map.of(
            "leftSql", "SELECT COUNT(*) AS metric_value FROM topic_a WHERE status = 'OK'")));

        // Offsets cannot honour a predicate, so AUTO does not pretend they can.
        assertEquals("RECORDS", preview.summary().get("countedBy"));
        assertEquals(5.0, preview.value());
        Mockito.verify(kafkaAdminService, Mockito.never()).getTopicRecordCounts(Mockito.anyList());
    }

    @Test
    void anOffsetCountRefusesATopicThatDoesNotExistRatherThanCountingItAsZero() throws Exception {
        Mockito.when(kafkaAdminService.listTopics()).thenReturn(List.of("demo.orders.1.received"));

        MetricPreviewResult preview = service.previewMetric(offsetDelta(Map.of()));

        // getTopicsSize answers 0 for an unknown name exactly as for an empty topic, and a zero
        // against a real count on the other side reads as total loss.
        assertNotNull(preview.error());
        assertTrue(preview.error().contains("demo.orders.2.validated"), preview.error());
        assertTrue(preview.error().contains("total loss"), preview.error());
    }

    /**
     * The failure this whole path is written against, arriving by the one door nobody had shut.
     *
     * <p>Both topics exist, so the {@code listTopics} guard above lets the read through — and then
     * the offsets read itself fails. {@code getTopicsSize} pre-seeds every requested topic at
     * {@code 0} and swallows the failure, so both sides come back zero and {@code PERCENT_GAP}
     * answers {@code 0.0}: "nothing is being lost", published by the alarm whose entire job is to
     * report loss. The guard meant to catch it ({@code left == null || right == null}) could never
     * fire, because that map always carries a key for every name it was asked about.
     */
    @Test
    void anOffsetCountThatCouldNotBeTakenIsRefusedRatherThanPublishedAsNoLoss() throws Exception {
        Mockito.when(kafkaAdminService.listTopics())
            .thenReturn(List.of("demo.orders.1.received", "demo.orders.2.validated"));
        // One broker state, described through both contracts: the lenient read answers zeros for a
        // failure it swallowed, the honest one omits what it could not measure.
        Mockito.when(kafkaAdminService.getTopicsSize(Mockito.anyList())).thenReturn(Map.of(
            "demo.orders.1.received", 0L, "demo.orders.2.validated", 0L));
        Mockito.when(kafkaAdminService.getTopicRecordCounts(Mockito.anyList())).thenReturn(Map.of());

        MetricPreviewResult preview = service.previewMetric(offsetDelta(Map.of(
            "operation", "PERCENT_GAP")));

        assertNotNull(preview.error(),
            "a gap that could not be measured must not publish 0 % — that reads as 'nothing is "
                + "being lost' on the metric that exists to report loss");
        assertNull(preview.value());
    }

    /**
     * The narrower half of the same fault: one side measured, the other not. Reporting the pair
     * would put a real count against a fabricated zero, which is the *most* alarming reading this
     * metric can produce and the one least entitled to be believed.
     */
    @Test
    void anOffsetCountRefusesThePairWhenOnlyOneSideCouldBeMeasured() throws Exception {
        Mockito.when(kafkaAdminService.listTopics())
            .thenReturn(List.of("demo.orders.1.received", "demo.orders.2.validated"));
        Mockito.when(kafkaAdminService.getTopicsSize(Mockito.anyList())).thenReturn(Map.of(
            "demo.orders.1.received", 1_000L, "demo.orders.2.validated", 0L));
        Mockito.when(kafkaAdminService.getTopicRecordCounts(Mockito.anyList()))
            .thenReturn(Map.of("demo.orders.1.received", 1_000L));

        MetricPreviewResult preview = service.previewMetric(offsetDelta(Map.of(
            "operation", "PERCENT_GAP")));

        assertNotNull(preview.error(), "a real count against an unmeasured side is not a 100 % gap");
        assertNull(preview.value());
    }

    @Test
    void aWindowedComparisonReportsWhatWasProducedSinceTheLastRefreshRatherThanTheLifetimeTotals() throws Exception {
        service.save(withIdAndType(offsetDelta(Map.of("window", "SINCE_LAST_REFRESH")), "gap-1", "GAUGE"));

        // Two topics that have been running for a while: the lifetime gap is 1 000 in 1 500 000,
        // which is 0.07 % and under any threshold worth setting.
        stubOffsets(1_500_000L, 1_499_000L);
        service.refreshMetric("gap-1");
        MetricConfig afterFirst = service.getById("gap-1").orElseThrow();
        assertNotNull(afterFirst.errorMessage(), "the first refresh has nothing to subtract");
        assertTrue(afterFirst.errorMessage().contains("Baseline established"), afterFirst.errorMessage());
        assertNull(afterFirst.lastValue());

        // Next interval: the left topic produced 1 000 and the right one 200. The lifetime gap has
        // barely moved; what happened in the interval is that four fifths of it went missing.
        stubOffsets(1_501_000L, 1_499_200L);
        service.refreshMetric("gap-1");
        MetricConfig afterSecond = service.getById("gap-1").orElseThrow();

        assertNull(afterSecond.errorMessage(), String.valueOf(afterSecond.errorMessage()));
        assertEquals(800.0, afterSecond.lastValue());
        assertEquals(1000.0, afterSecond.lastSummary().get("leftValue"));
        assertEquals(200.0, afterSecond.lastSummary().get("rightValue"));
        assertEquals(1_501_000.0, afterSecond.lastSummary().get("leftTotal"));
    }

    @Test
    void aPreviewOfAWindowedMetricReportsTotalsAndLeavesTheRunningBaselineAlone() throws Exception {
        service.save(withIdAndType(offsetDelta(Map.of("window", "SINCE_LAST_REFRESH")), "gap-2", "GAUGE"));
        stubOffsets(1000L, 900L);
        service.refreshMetric("gap-2");   // baseline at 1000/900

        // A preview must not write a baseline: the running metric would then subtract from an
        // instant nobody measured.
        MetricPreviewResult preview = service.previewMetric(
            withIdAndType(offsetDelta(Map.of("window", "SINCE_LAST_REFRESH")), "gap-2", "GAUGE"));
        assertNull(preview.error());
        assertEquals(100.0, preview.value(), "previewed as totals");
        assertTrue(String.valueOf(preview.summary().get("scopeNote")).contains("no previous refresh"));

        stubOffsets(1200L, 1050L);
        service.refreshMetric("gap-2");
        MetricConfig refreshed = service.getById("gap-2").orElseThrow();
        // 200 produced on the left and 150 on the right since the baseline the *refresh* wrote.
        assertEquals(50.0, refreshed.lastValue());
    }

    @Test
    void aCountThatWentBackwardsIsRefusedAndTheBaselineIsReEstablished() throws Exception {
        service.save(withIdAndType(offsetDelta(Map.of("window", "SINCE_LAST_REFRESH")), "gap-3", "GAUGE"));
        stubOffsets(5000L, 4000L);
        service.refreshMetric("gap-3");

        // The topic was recreated: the offsets restart below the baseline.
        stubOffsets(10L, 8L);
        service.refreshMetric("gap-3");
        assertTrue(service.getById("gap-3").orElseThrow().errorMessage().contains("went backwards"));

        // And the next interval reports normally against the new baseline.
        stubOffsets(60L, 50L);
        service.refreshMetric("gap-3");
        MetricConfig recovered = service.getById("gap-3").orElseThrow();
        assertNull(recovered.errorMessage(), String.valueOf(recovered.errorMessage()));
        assertEquals(8.0, recovered.lastValue());
    }

    @Test
    void theTwoCountsAreMeasurementsAndNeverPrometheusLabels() throws Exception {
        service.save(withIdAndType(offsetDelta(Map.of()), "gap-4", "GAUGE"));
        stubOffsets(1000L, 900L);
        service.refreshMetric("gap-4");

        Gauge gauge = meterRegistry.find("explorer_metric_gauge").tag("metric_id", "gap-4").gauge();
        assertNotNull(gauge);
        List<String> tagKeys = gauge.getId().getTags().stream().map(io.micrometer.core.instrument.Tag::getKey).toList();
        /*
         * left_value and right_value used to be ordinary row columns, so they became labels — and
         * on a live topic they move at every refresh, which mints a new time series per scrape and
         * leaves each one carrying a single data point. The metric could not be graphed or alerted
         * on at all, which is the only thing it is for.
         */
        assertFalse(tagKeys.contains("left_value"), tagKeys.toString());
        assertFalse(tagKeys.contains("__left_value"), tagKeys.toString());
        assertTrue(tagKeys.contains("operation"), "a constant is a fine label: " + tagKeys);
        assertTrue(tagKeys.contains("left_topic"), tagKeys.toString());

        // A second refresh at different counts must land on the same series, not a new one.
        stubOffsets(2000L, 1500L);
        service.refreshMetric("gap-4");
        assertEquals(1, meterRegistry.find("explorer_metric_gauge").tag("metric_id", "gap-4").gauges().size());
        assertEquals(500.0, meterRegistry.find("explorer_metric_gauge").tag("metric_id", "gap-4").gauge().value());
    }

    @Test
    void theTwoNewParametersAreRefusedWhenTheMetricIsSaved() {
        assertThrows(IllegalArgumentException.class,
            () -> service.save(offsetDelta(Map.of("countBy", "MAGIC"))));
        assertThrows(IllegalArgumentException.class,
            () -> service.save(offsetDelta(Map.of("window", "LAST_HOUR"))));
        // An offsets metric needs its topics rather than its queries, and says so.
        Map<String, Object> noTopics = new LinkedHashMap<>(Map.of("countBy", "OFFSETS"));
        assertThrows(IllegalArgumentException.class, () -> service.save(new MetricConfig(
            null, "gap", "GAUGE", null, null, null, null, null, null, null, List.of(), Map.of(), null,
            "TOPIC_COUNT_DELTA", noTopics, null, null, List.of())));
        service.save(offsetDelta(Map.of("countBy", "OFFSETS", "window", "SINCE_LAST_REFRESH")));
    }

}
