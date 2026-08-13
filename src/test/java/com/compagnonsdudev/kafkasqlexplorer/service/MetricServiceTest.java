package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.KafkaMessage;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricPreviewResult;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
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

        service = new MetricService(
            flinkSqlService,
            meterRegistry,
            kafkaConfig,
            explorerConfig,
            kafkaAdminService,
            messageFieldExtractorService
        );
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
        Mockito.when(flinkSqlService.executeSql(Mockito.any()))
            .thenReturn(
                new QueryResult(List.of("metric_value"), List.of(Map.of("metric_value", 12.0)), 10L, null),
                new QueryResult(List.of("metric_value"), List.of(Map.of("metric_value", 7.0)), 10L, null)
            );

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
        Mockito.when(flinkSqlService.executeSql(Mockito.any()))
            .thenReturn(
                new QueryResult(List.of("metric_value"), List.of(Map.of("metric_value", 10.0)), 10L, null),
                new QueryResult(List.of("metric_value"), List.of(Map.of("metric_value", 4.0)), 10L, null)
            );

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
        Mockito.when(flinkSqlService.executeSql(Mockito.any()))
            .thenReturn(
                new QueryResult(List.of("metric_value"), List.of(Map.of("metric_value", 8.0)), 10L, null),
                new QueryResult(List.of("metric_value"), List.of(Map.of("metric_value", 3.0)), 10L, null)
            );

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
}
