package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.domain.MetricConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MetricServiceTest {

    @Test
    void testPreconfiguredMetrics() {
        MetricService service = new MetricService();
        List<MetricConfig> metrics = service.getAllMetrics();

        assertEquals(3, metrics.size());
        assertTrue(metrics.stream().anyMatch(m -> m.name().equals("Events Total")));
        assertTrue(metrics.stream().anyMatch(m -> m.name().equals("Avg Latency")));
        assertTrue(metrics.stream().anyMatch(m -> m.name().equals("Events per Minute")));
    }

    @Test
    void testSaveAndDeleteMetric() {
        MetricService service = new MetricService();
        MetricConfig newMetric = new MetricConfig(null, "Test Metric", "COUNTER", "SELECT 1 as metric_value", "Test Description");

        service.save(newMetric);
        List<MetricConfig> metrics = service.getAllMetrics();
        assertEquals(4, metrics.size());

        MetricConfig saved = metrics.stream().filter(m -> m.name().equals("Test Metric")).findFirst().orElseThrow();
        assertNotNull(saved.id());

        service.delete(saved.id());
        assertEquals(3, service.getAllMetrics().size());
        assertFalse(service.getById(saved.id()).isPresent());
    }

    @Test
    void testUpdateMetric() {
        MetricService service = new MetricService();
        MetricConfig original = service.getAllMetrics().get(0);

        MetricConfig updated = new MetricConfig(original.id(), "Updated Name", "GAUGE", "SELECT 2 as metric_value", "Updated Desc");
        service.save(updated);

        Optional<MetricConfig> found = service.getById(original.id());
        assertTrue(found.isPresent());
        assertEquals("Updated Name", found.get().name());
        assertEquals("GAUGE", found.get().type());
    }
}
