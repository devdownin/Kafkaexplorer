// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.web;

import com.compagnonsdudev.kafkasqlexplorer.domain.QueryInitResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryResult;
import com.compagnonsdudev.kafkasqlexplorer.domain.FlinkManagedJobDetails;
import com.compagnonsdudev.kafkasqlexplorer.domain.FlinkJobSummary;
import com.compagnonsdudev.kafkasqlexplorer.domain.MessageFormat;
import com.compagnonsdudev.kafkasqlexplorer.service.DdlGeneratorService;
import com.compagnonsdudev.kafkasqlexplorer.service.FlinkJobService;
import com.compagnonsdudev.kafkasqlexplorer.service.FlinkSqlService;
import com.compagnonsdudev.kafkasqlexplorer.service.KafkaAdminService;
import com.compagnonsdudev.kafkasqlexplorer.service.SchemaInferenceService;
import com.compagnonsdudev.kafkasqlexplorer.service.SqlErrorClassifier;
import com.compagnonsdudev.kafkasqlexplorer.service.SqlExplorationService;
import com.compagnonsdudev.kafkasqlexplorer.service.SqlQueryValidator;
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

    /**
     * The schema browser's payload — and, when it comes back empty, the reason.
     *
     * <p>Both halves used to end in an empty catch ({@code // Ignore and show empty list}), so an
     * unreachable broker, a wrong bootstrap address and a Flink runtime still starting up all
     * rendered as "Engine offline · 0 tables · 0 topics" with nothing to diagnose from. The two
     * probes stay independent — Flink being unavailable must not hide the topic list, and the
     * reverse — but each now reports what stopped it.
     */
    @GetMapping("/init")
    public QueryInitResponse init() {
        List<String> topics = Collections.emptyList();
        List<String> tables = Collections.emptyList();
        String kafkaError = null;
        String flinkError = null;

        KafkaAdminService.PingResult ping = kafkaAdminService.pingDetail();
        boolean isConnected = ping.reachable();
        if (!isConnected) {
            kafkaError = ping.error();
        } else {
            try {
                List<String> allTopics = kafkaAdminService.listTopics();
                Map<String, Long> sizes = kafkaAdminService.getTopicsSize(allTopics);
                topics = allTopics.stream()
                        .filter(t -> sizes.getOrDefault(t, 0L) > 0)
                        .sorted()
                        .collect(Collectors.toList());
            } catch (Exception e) {
                // Reachable but unreadable — a metadata call can fail on its own (authorisation,
                // a timeout on a very large cluster). That is not the same as "offline", so the
                // health flag stays true and the reason is reported beside it.
                kafkaError = SqlErrorClassifier.explain(e);
            }
        }

        try {
            tables = flinkSqlService.listTables();
        } catch (Exception e) {
            // Flink might be starting up — which the caller can now say, instead of showing an
            // empty catalogue that looks like a cluster with no tables in it.
            flinkError = SqlErrorClassifier.explain(e);
        }

        return new QueryInitResponse(topics, tables, isConnected, kafkaError, flinkError);
    }

    // There used to be a second, path-less `@PostMapping` here calling exactly the same thing as
    // `/run-sync`. Nothing in this repository ever posted to it — the SPA has always used
    // `/run-sync` — so it was a second public entry point to the query engine that no test
    // exercised and no caller needed. Two paths to one behaviour is how they drift.
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

    /**
     * Drops a table from Flink's catalogue and stops keeping its definition.
     *
     * <p>It exists because keeping hand-written {@code CREATE TABLE} statements across restarts
     * takes an escape hatch away: restarting used to be the only way to clear the in-memory
     * catalogue. A store that could only grow — replaying a definition nobody could get rid of on
     * every boot — would be a worse defect than the one it fixes.
     *
     * <p>400 on a name that could not go into a statement, rather than quoting it and hoping: a
     * backtick in a path variable is SQL injection into an engine that runs whatever DDL it is
     * given, and the refused text is not echoed back. Otherwise 200 with the outcome named — the
     * same rule as {@code cancel}, where "nothing to do" is the result of a well-formed request
     * rather than a client error, and the caller has to be able to tell the cases apart.
     * {@link FlinkSqlService.DropOutcome} has three of them because a boolean had only two and so
     * reported "no such table" whenever the engine had refused to drop one that plainly exists.
     */
    @DeleteMapping(value = "/table/{tableName}", produces = "application/json")
    public Map<String, Object> dropTable(@PathVariable("tableName") String tableName) {
        FlinkSqlService.DropOutcome outcome;
        try {
            outcome = flinkSqlService.dropTable(tableName);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        Map<String, Object> result = new HashMap<>();
        result.put("table", tableName);
        result.put("dropped", outcome == FlinkSqlService.DropOutcome.DROPPED);
        result.put("outcome", outcome.name());
        // Three outcomes, three sentences. The boolean alone reported "there was no such table"
        // whenever the engine had refused to drop one that plainly exists.
        result.put("message", switch (outcome) {
            case DROPPED -> "Table " + tableName + " was dropped and will not be restored at startup.";
            case NOT_FOUND -> "No table named " + tableName + " was registered or stored.";
            case REFUSED -> "Table " + tableName + " could not be dropped — see the server log.";
        });
        return result;
    }

    /**
     * Cancels a running query, and <em>says what that achieved</em>.
     *
     * <p>Both endpoints used to return {@code void} and answer 200 whatever happened, so the caller
     * could not tell a cancelled Flink job from an id with no live job behind it. That is a real
     * distinction rather than a detail: a {@code KAFKA_DIRECT} scan has no Flink job by
     * construction, so a UI that reports "cancelled" on the strength of a 200 promises more than
     * took place — which is exactly the trap the editor's own Stop button had to be fixed for.
     *
     * <p>Still 200 in both cases: "there was nothing to cancel" is a legitimate outcome of a
     * well-formed request, not a client error, and the caller has aborted its own HTTP request
     * regardless.
     */
    @PostMapping("/cancel/{queryId}")
    public Map<String, Object> cancel(@PathVariable("queryId") String queryId) {
        FlinkSqlService.CancelOutcome outcome = flinkJobService.cancel(queryId);
        return Map.of(
            "cancelled", outcome == FlinkSqlService.CancelOutcome.CANCELLED,
            "outcome", outcome.name());
    }

    @PostMapping("/jobs/{queryId}/cancel")
    public Map<String, Object> cancelJob(@PathVariable("queryId") String queryId) {
        return cancel(queryId);
    }

    @GetMapping("/ddl-preview")
    public Map<String, String> ddlPreview(@RequestParam("topic") String topic) {
        try {
            MessageFormat format = schemaInferenceService.detectFormat(topic);
            Map<String, String> schema = schemaInferenceService.inferSchema(topic, format);
            String ddl = DdlGeneratorService.maskSensitiveProperties(
                    ddlGeneratorService.generateDdl(topic, schema, format));
            return Map.of("ddl", ddl);
        } catch (Exception e) {
            // SqlErrorClassifier.explain, not e.getMessage(): that is null for a
            // NullPointerException, and Map.of rejects a null value — so the one failure mode
            // where the caller most needs a reason turned this handler's own error path into a
            // 500, which the UI could only report as a generic "Failed to generate DDL preview".
            // explain() is documented never to return null or blank, and it flattens the cause
            // chain, where schema inference habitually keeps the useful text.
            return Map.of("error", SqlErrorClassifier.explain(e));
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
