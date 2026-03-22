package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.domain.MetricConfig;
import com.yourcompany.kafkasqlexplorer.domain.QueryRequest;
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

    /** Run SQL immediately and return the first metric_value for preview purposes. */
    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestBody Map<String, String> body) {
        String sql = body.get("sql");
        if (sql == null || sql.isBlank()) {
            return Map.of("error", "SQL is required");
        }
        try {
            var result = flinkSqlService.executeSql(new QueryRequest(sql, "latest-offset", 10, 5000L, null));
            if (result.error() != null) return Map.of("error", result.error());
            if (result.rows().isEmpty()) return Map.of("error", "No rows returned");
            Object val = result.rows().get(0).entrySet().stream()
                .filter(e -> "metric_value".equalsIgnoreCase(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(result.rows().get(0).values().iterator().next());
            return Map.of("value", val, "rows", result.rows());
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }
}
