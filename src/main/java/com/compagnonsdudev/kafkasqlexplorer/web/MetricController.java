// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.web;

import com.compagnonsdudev.kafkasqlexplorer.domain.MetricConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricLabelPreview;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricPreviewResult;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricTemplateDescriptor;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryRequest;
import com.compagnonsdudev.kafkasqlexplorer.service.DdlGeneratorService;
import com.compagnonsdudev.kafkasqlexplorer.service.FlinkSqlService;
import com.compagnonsdudev.kafkasqlexplorer.service.KafkaAdminService;
import com.compagnonsdudev.kafkasqlexplorer.service.MessageFieldExtractorService;
import com.compagnonsdudev.kafkasqlexplorer.service.MessageFormatterService;
import com.compagnonsdudev.kafkasqlexplorer.service.MetricService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/metrics")
public class MetricController {

    private final MetricService metricService;
    private final FlinkSqlService flinkSqlService;
    private final KafkaAdminService kafkaAdminService;
    private final MessageFormatterService messageFormatterService;
    private final MessageFieldExtractorService messageFieldExtractorService;

    public MetricController(MetricService metricService,
                            FlinkSqlService flinkSqlService,
                            KafkaAdminService kafkaAdminService,
                            MessageFormatterService messageFormatterService,
                            MessageFieldExtractorService messageFieldExtractorService) {
        this.metricService = metricService;
        this.flinkSqlService = flinkSqlService;
        this.kafkaAdminService = kafkaAdminService;
        this.messageFormatterService = messageFormatterService;
        this.messageFieldExtractorService = messageFieldExtractorService;
    }

    @GetMapping
    public List<MetricConfig> list() {
        return metricService.getAllMetrics().stream().map(this::maskForDisplay).toList();
    }

    /**
     * Redacts credentials embedded in a metric's CREATE TABLE DDL before it reaches the browser.
     * The service keeps the unmasked DDL internally for Flink registration; save() restores any
     * secrets the UI echoes back masked.
     */
    private MetricConfig maskForDisplay(MetricConfig m) {
        if (m.createTableSql() == null) return m;
        String masked = DdlGeneratorService.maskSensitiveProperties(m.createTableSql());
        if (masked.equals(m.createTableSql())) return m;   // nothing sensitive to hide
        return new MetricConfig(
            m.id(), m.name(), m.type(), m.sql(), m.description(),
            m.warningThreshold(), m.criticalThreshold(), m.lastValue(), m.lastUpdateTime(),
            m.errorMessage(), m.history(), m.lastSummary(), masked, m.templateType(),
            m.templateParams(), m.executionMode(), m.labelTopic(), m.labelFields());
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
    public ResponseEntity<?> save(@RequestBody MetricConfig metric) {
        try {
            metricService.save(metric);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        metricService.delete(id);
    }

    /** Recompute a single metric immediately and return its refreshed (credential-masked) state. */
    @PostMapping("/{id}/refresh")
    public ResponseEntity<MetricConfig> refresh(@PathVariable String id) {
        return metricService.refreshMetric(id)
            .map(this::maskForDisplay)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/templates")
    public List<MetricTemplateDescriptor> templates() {
        return metricService.listTemplates();
    }

    @GetMapping("/label-preview")
    public MetricLabelPreview getLabelPreview(@RequestParam String topic) {
        return kafkaAdminService.getLatestMessage(topic)
            .map(message -> new MetricLabelPreview(
                topic,
                message.timestamp(),
                messageFormatterService.format(message.value()),
                messageFieldExtractorService.extractLeafFields(message.value())
            ))
            .orElseGet(() -> new MetricLabelPreview(topic, null, null, Map.of()));
    }

    /** Run SQL immediately and return the first metric_value for preview purposes. */
    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestBody Map<String, String> body) {
        String sql = body.get("sql");
        if (sql == null || sql.isBlank()) {
            return Map.of("error", "SQL is required");
        }
        try {
            // Use earliest-offset (bounded scan) to match how scheduled metrics execute.
            // With latest-offset an aggregate like COUNT(*) sees no backlog and returns
            // "No rows returned", making the preview misleading for working metrics.
            var result = flinkSqlService.executeSql(QueryRequest.sql(sql, 10, 5000L, "earliest-offset"));
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

    @PostMapping("/preview-template")
    public MetricPreviewResult previewTemplate(@RequestBody MetricConfig metric) {
        return metricService.previewMetric(metric);
    }
}
