package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.domain.MetricConfig;
import com.yourcompany.kafkasqlexplorer.service.MetricService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/metrics")
public class MetricController {

    private final MetricService metricService;

    public MetricController(MetricService metricService) {
        this.metricService = metricService;
    }

    @GetMapping
    public List<MetricConfig> list() {
        return metricService.getAllMetrics();
    }

    @PostMapping
    public void save(@RequestBody MetricConfig metric) {
        metricService.save(metric);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        metricService.delete(id);
    }
}
