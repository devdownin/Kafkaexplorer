package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.domain.MetricConfig;
import com.yourcompany.kafkasqlexplorer.service.MetricService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/metrics")
public class MetricRestController {

    private final MetricService metricService;

    public MetricRestController(MetricService metricService) {
        this.metricService = metricService;
    }

    @GetMapping
    public List<MetricConfig> getAllMetrics() {
        return metricService.getAllMetrics();
    }

    @PostMapping
    public void saveMetric(@RequestBody MetricConfig metric) {
        metricService.save(metric);
    }

    @DeleteMapping("/{id}")
    public void deleteMetric(@PathVariable String id) {
        metricService.delete(id);
    }
}
