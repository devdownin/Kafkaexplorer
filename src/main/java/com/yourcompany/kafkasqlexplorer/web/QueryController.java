// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.domain.QueryInitResponse;
import com.yourcompany.kafkasqlexplorer.domain.QueryRequest;
import com.yourcompany.kafkasqlexplorer.domain.QueryResult;
import com.yourcompany.kafkasqlexplorer.domain.FlinkManagedJobDetails;
import com.yourcompany.kafkasqlexplorer.domain.FlinkJobSummary;
import com.yourcompany.kafkasqlexplorer.domain.MessageFormat;
import com.yourcompany.kafkasqlexplorer.service.DdlGeneratorService;
import com.yourcompany.kafkasqlexplorer.service.FlinkJobService;
import com.yourcompany.kafkasqlexplorer.service.FlinkSqlService;
import com.yourcompany.kafkasqlexplorer.service.KafkaAdminService;
import com.yourcompany.kafkasqlexplorer.service.SchemaInferenceService;
import com.yourcompany.kafkasqlexplorer.service.SqlExplorationService;
import com.yourcompany.kafkasqlexplorer.service.SqlQueryValidator;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/query")
public class QueryController {

    private final FlinkSqlService flinkSqlService;
    private final SqlExplorationService sqlExplorationService;
    private final FlinkJobService flinkJobService;
    private final KafkaAdminService kafkaAdminService;
    private final SqlQueryValidator sqlQueryValidator;
    private final SchemaInferenceService schemaInferenceService;
    private final DdlGeneratorService ddlGeneratorService;

    public QueryController(FlinkSqlService flinkSqlService, SqlExplorationService sqlExplorationService,
                           FlinkJobService flinkJobService, KafkaAdminService kafkaAdminService,
                           SqlQueryValidator sqlQueryValidator, SchemaInferenceService schemaInferenceService,
                           DdlGeneratorService ddlGeneratorService) {
        this.flinkSqlService = flinkSqlService;
        this.sqlExplorationService = sqlExplorationService;
        this.flinkJobService = flinkJobService;
        this.kafkaAdminService = kafkaAdminService;
        this.sqlQueryValidator = sqlQueryValidator;
        this.schemaInferenceService = schemaInferenceService;
        this.ddlGeneratorService = ddlGeneratorService;
    }

    @GetMapping("/init")
    public QueryInitResponse init() {
        boolean isConnected = false;
        List<String> topics = Collections.emptyList();
        List<String> tables = Collections.emptyList();

        try {
            isConnected = kafkaAdminService.ping();
            if (isConnected) {
                List<String> allTopics = kafkaAdminService.listTopics();
                Map<String, Long> sizes = kafkaAdminService.getTopicsSize(allTopics);
                topics = allTopics.stream()
                        .filter(t -> sizes.getOrDefault(t, 0L) > 0)
                        .sorted()
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            // Ignore and show empty list
        }

        try {
            tables = flinkSqlService.listTables();
        } catch (Exception e) {
            // Flink might be starting up
        }

        return new QueryInitResponse(topics, tables, isConnected);
    }

    @PostMapping(produces = "application/json")
    public QueryResult execute(@RequestBody QueryRequest request) {
        return sqlExplorationService.runSync(request);
    }

    @PostMapping(value = "/run-sync", produces = "application/json")
    public QueryResult runSync(@RequestBody QueryRequest request) {
        return sqlExplorationService.runSync(request);
    }

    @PostMapping(value = "/jobs", produces = "application/json")
    public FlinkJobSummary submitJob(@RequestBody QueryRequest request) {
        try {
            return flinkJobService.submit(request);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @GetMapping(value = "/jobs", produces = "application/json")
    public List<FlinkJobSummary> listJobs() {
        return flinkJobService.listJobs();
    }

    @GetMapping(value = "/jobs/{queryId}", produces = "application/json")
    public FlinkManagedJobDetails getJob(@PathVariable String queryId) {
        return flinkJobService.getJob(queryId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found: " + queryId));
    }

    @GetMapping(value = "/schema/{tableName}", produces = "application/json")
    public Map<String, String> getSchema(@PathVariable String tableName) {
        return flinkSqlService.getTableSchema(tableName);
    }

    @PostMapping("/cancel/{queryId}")
    public void cancel(@PathVariable String queryId) {
        flinkJobService.cancel(queryId);
    }

    @PostMapping("/jobs/{queryId}/cancel")
    public void cancelJob(@PathVariable String queryId) {
        flinkJobService.cancel(queryId);
    }

    @GetMapping("/ddl-preview")
    public Map<String, String> ddlPreview(@RequestParam String topic) {
        try {
            MessageFormat format = schemaInferenceService.detectFormat(topic);
            Map<String, String> schema = schemaInferenceService.inferSchema(topic, format);
            String ddl = DdlGeneratorService.maskSensitiveProperties(
                    ddlGeneratorService.generateDdl(topic, schema, format));
            return Map.of("ddl", ddl);
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody QueryRequest request) {
        Map<String, Object> result = new HashMap<>();
        try {
            sqlQueryValidator.validate(request.sql());
            result.put("valid", true);
        } catch (IllegalArgumentException e) {
            result.put("valid", false);
            result.put("error", e.getMessage());
        }
        return result;
    }
}
