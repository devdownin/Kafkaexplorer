package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.config.ExplorerConfig;
import com.yourcompany.kafkasqlexplorer.config.KafkaConfig;
import com.yourcompany.kafkasqlexplorer.domain.MetricConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MetricServiceTest {

    private MetricService service;
    private FlinkSqlService flinkSqlService;
    private MeterRegistry meterRegistry;
    private KafkaConfig kafkaConfig;
    private ExplorerConfig explorerConfig;

    @BeforeEach
    void setUp() {
        flinkSqlService = Mockito.mock(FlinkSqlService.class);
        meterRegistry = new SimpleMeterRegistry();
        kafkaConfig = Mockito.mock(KafkaConfig.class);
        explorerConfig = Mockito.mock(ExplorerConfig.class);

        Mockito.when(explorerConfig.getMetricsConfigTopic()).thenReturn("internal.metrics.config");

        service = new MetricService(flinkSqlService, meterRegistry, kafkaConfig, explorerConfig);
    }

    @Test
    void testPreconfiguredMetrics() {
        service.init();
        List<MetricConfig> metrics = service.getAllMetrics();

        assertEquals(2, metrics.size());
        assertTrue(metrics.stream().anyMatch(m -> m.name().equals("business_events_total")));
        assertTrue(metrics.stream().anyMatch(m -> m.name().equals("business_events_by_type")));
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
}
