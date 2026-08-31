// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.web;

import com.compagnonsdudev.kafkasqlexplorer.domain.SqlValidationResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.DdlPreviewResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryCancelResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryInitResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryResult;
import com.compagnonsdudev.kafkasqlexplorer.domain.MessageFormat;
import com.compagnonsdudev.kafkasqlexplorer.service.DdlGeneratorService;
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
    private final KafkaAdminService kafkaAdminService;
    private final SqlQueryValidator sqlQueryValidator;
    private final SchemaInferenceService schemaInferenceService;
    private final DdlGeneratorService ddlGeneratorService;

    public QueryController(FlinkSqlService flinkSqlService, SqlExplorationService sqlExplorationService,
                           KafkaAdminService kafkaAdminService,
                           SqlQueryValidator sqlQueryValidator, SchemaInferenceService schemaInferenceService,
                           DdlGeneratorService ddlGeneratorService) {
        this.flinkSqlService = flinkSqlService;
        this.sqlExplorationService = sqlExplorationService;
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

    /*
     * Il n'y a plus rien sous `/jobs`.
     *
     * <p>Il y a eu quatre routes. `POST /jobs` soumettait un `INSERT INTO` comme job Flink continu,
     * pour le mode « Flink job » du SQL editor ; ce mode ne fonctionnait pas et a été retiré de
     * l'éditeur, l'endpoint partant avec plutôt que de rester un second chemin non authentifié vers
     * le moteur de requêtes que plus aucun appelant n'exerce — la forme que ce paquet a déjà
     * supprimée deux fois (`POST /api/metrics/preview`, `TableController`).
     *
     * <p>Les trois lectures qui lui ont survécu — `GET /jobs`, `GET /jobs/{queryId}` et
     * `POST /jobs/{queryId}/cancel` — servaient le tableau « Flink SQL Jobs » du tableau de bord, et
     * lui seul. Sans soumission, ce tableau ne listait plus des jobs : il listait les lectures
     * synchrones déjà terminées que `FlinkJobStore` avait enregistrées au passage, une par
     * rafraîchissement de métrique. Le tableau est parti, ces routes avec, et le magasin qui les
     * alimentait aussi.
     *
     * <p>Ce qui reste est `POST /cancel/{queryId}` ci-dessous, que le bouton Stop de l'éditeur
     * appelle : il lit le registre en mémoire, alimenté par les lectures synchrones qui y déposent
     * leur `JobClient` le temps de leur requête HTTP. `POST /jobs/{queryId}/cancel` en était un
     * alias, appelé par la carte du tableau de bord ; deux chemins vers un même comportement est
     * la forme qui dérive, et il n'a plus d'appelant.
     */

    @GetMapping(value = "/schema/{tableName}", produces = "application/json")
    public Map<String, String> getSchema(@PathVariable("tableName") String tableName) {
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
     * <p>It used to return {@code void} and answer 200 whatever happened, so the caller
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
    public QueryCancelResponse cancel(@PathVariable("queryId") String queryId) {
        return QueryCancelResponse.of(flinkSqlService.cancelQuery(queryId));
    }

    @GetMapping("/ddl-preview")
    public DdlPreviewResponse ddlPreview(@RequestParam("topic") String topic) {
        try {
            MessageFormat format = schemaInferenceService.detectFormat(topic);
            Map<String, String> schema = schemaInferenceService.inferSchema(topic, format);
            String ddl = DdlGeneratorService.maskSensitiveProperties(
                    ddlGeneratorService.generateDdl(topic, schema, format));
            return DdlPreviewResponse.of(ddl);
        } catch (Exception e) {
            // SqlErrorClassifier.explain, not e.getMessage(): that is null for a
            // NullPointerException, and Map.of rejects a null value — so the one failure mode
            // where the caller most needs a reason turned this handler's own error path into a
            // 500, which the UI could only report as a generic "Failed to generate DDL preview".
            // explain() is documented never to return null or blank, and it flattens the cause
            // chain, where schema inference habitually keeps the useful text.
            return DdlPreviewResponse.failed(SqlErrorClassifier.explain(e));
        }
    }

    @PostMapping("/validate")
    public SqlValidationResponse validate(@RequestBody QueryRequest request) {
        try {
            sqlQueryValidator.validate(request.sql());
            return SqlValidationResponse.accepted();
        } catch (IllegalArgumentException e) {
            return SqlValidationResponse.rejected(e.getMessage());
        }
    }
}
