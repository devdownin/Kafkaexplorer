package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.config.ExplorerConfig;
import com.yourcompany.kafkasqlexplorer.config.KafkaConfig;
import com.yourcompany.kafkasqlexplorer.domain.KafkaMessage;
import com.yourcompany.kafkasqlexplorer.domain.MetricConfig;
import com.yourcompany.kafkasqlexplorer.domain.MetricPreviewResult;
import com.yourcompany.kafkasqlexplorer.domain.QueryResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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
