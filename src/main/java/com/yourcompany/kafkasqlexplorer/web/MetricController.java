package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.domain.MetricConfig;
import com.yourcompany.kafkasqlexplorer.service.FlinkSqlService;
import com.yourcompany.kafkasqlexplorer.service.MetricService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/metrics")
public class MetricController {

    private final MetricService metricService;
    private final FlinkSqlService flinkSqlService;

    public MetricController(MetricService metricService, FlinkSqlService flinkSqlService) {
        this.metricService = metricService;
        this.flinkSqlService = flinkSqlService;
    }

    @GetMapping
    public List<MetricConfig> list() {
        return metricService.getAllMetrics();
    }

    @GetMapping("/metadata")
    public Map<String, List<String>> getMetadata() {
        Map<String, List<String>> metadata = new HashMap<>();
        List<String> tables = flinkSqlService.listTables();
        for (String table : tables) {
            metadata.put(table, List.copyOf(flinkSqlService.getTableSchema(table).keySet()));
        }
        return metadata;
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
