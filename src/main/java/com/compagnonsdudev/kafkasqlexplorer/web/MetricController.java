// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.web;

import com.compagnonsdudev.kafkasqlexplorer.domain.ApiError;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricLabelPreview;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricPreviewResult;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricSuggestionRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricSuggestions;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricTemplateDescriptor;
import com.compagnonsdudev.kafkasqlexplorer.service.DdlGeneratorService;
import com.compagnonsdudev.kafkasqlexplorer.service.FlinkSqlService;
import com.compagnonsdudev.kafkasqlexplorer.service.KafkaAdminService;
import com.compagnonsdudev.kafkasqlexplorer.service.MessageFieldExtractorService;
import com.compagnonsdudev.kafkasqlexplorer.service.MessageFormatterService;
import com.compagnonsdudev.kafkasqlexplorer.service.MetricService;
import com.compagnonsdudev.kafkasqlexplorer.service.MetricSuggestionService;
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
    private final MetricSuggestionService metricSuggestionService;

    public MetricController(MetricService metricService,
                            FlinkSqlService flinkSqlService,
                            KafkaAdminService kafkaAdminService,
                            MessageFormatterService messageFormatterService,
                            MessageFieldExtractorService messageFieldExtractorService,
                            MetricSuggestionService metricSuggestionService) {
        this.metricService = metricService;
        this.flinkSqlService = flinkSqlService;
        this.kafkaAdminService = kafkaAdminService;
        this.messageFormatterService = messageFormatterService;
        this.messageFieldExtractorService = messageFieldExtractorService;
        this.metricSuggestionService = metricSuggestionService;
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
            // ApiError.of, et non Map.of("error", e.getMessage()) : `getMessage()` est nul sur une
            // NPE et Map.of refuse un nul, donc ce chemin répondait 500 sans corps exactement
            // quand l'appelant a besoin d'une raison — le défaut corrigé sur `ddl-preview`,
            // resté debout ici. La forme sur le fil est la même, le navigateur lit `error`.
            return ResponseEntity.badRequest().body(ApiError.of(e));
        }
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable("id") String id) {
        metricService.delete(id);
    }

    /** Recompute a single metric immediately and return its refreshed (credential-masked) state. */
    @PostMapping("/{id}/refresh")
    public ResponseEntity<MetricConfig> refresh(@PathVariable("id") String id) {
        return metricService.refreshMetric(id)
            .map(this::maskForDisplay)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/templates")
    public List<MetricTemplateDescriptor> templates() {
        return metricService.listTemplates();
    }

    /**
     * Contextual KPIs proposed for this cluster, derived from what has actually been measured on
     * it — the last audit report, and the Stream Flow traces the caller carries back.
     *
     * <p>A POST because the browser contributes evidence: traces live in {@code localStorage} and
     * the server has never seen one. The body is optional, so a plain call answers with the
     * audit-derived proposals alone; nothing is created either way — a suggestion is a pre-filled
     * form for {@code POST /api/metrics}, opened and previewed by hand.
     */
    @PostMapping("/suggestions")
    public MetricSuggestions suggestions(@RequestBody(required = false) MetricSuggestionRequest request) {
        return metricSuggestionService.suggest(request);
    }

    @GetMapping("/label-preview")
    public MetricLabelPreview getLabelPreview(@RequestParam("topic") String topic) {
        return kafkaAdminService.getLatestMessage(topic)
            .map(message -> new MetricLabelPreview(
                topic,
                message.timestamp(),
                messageFormatterService.format(message.value()),
                messageFieldExtractorService.extractLeafFields(message.value())
            ))
            .orElseGet(() -> new MetricLabelPreview(topic, null, null, Map.of()));
    }

    @PostMapping("/preview-template")
    public MetricPreviewResult previewTemplate(@RequestBody MetricConfig metric) {
        return metricService.previewMetric(metric);
    }
}
