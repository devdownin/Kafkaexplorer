// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.AssistantProperties;
import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.AssistantMetricRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.AssistantMetricResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.AssistantSqlRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.AssistantSqlResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.AssistantStatus;
import com.compagnonsdudev.kafkasqlexplorer.domain.LlmResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.LlmUsage;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;

/**
 * The SQL and metric assistant: a question or a refused statement in, a proposal out, with what is
 * known about it. Design and reasons: {@code EMBEDDED-LLM-SCOPE.md}.
 *
 * <p>Three invariants, the ones the rest of this application already keeps.
 * <ol>
 *   <li><b>Nothing runs or is saved here.</b> A proposal opens a tab or fills a form; Run and Save
 *       stay the user's gestures.</li>
 *   <li><b>A proposal the planner did not accept says so</b>, with the planner's sentence, and one
 *       that is not a read is withheld altogether.</li>
 *   <li><b>The answer says what it saw</b>: the tables described and, by name, those left out.</li>
 * </ol>
 *
 * <p><b>One call at a time.</b> A CPU-served model has one slot; a second request would queue
 * behind the first for tens of seconds with nothing on screen saying so. It is refused as
 * {@code BUSY} instead — a wait, said as a wait, the reading {@code ENGINE_BUSY} gives the planner.
 *
 * <p>A model call that fails is reported with its real message, never swallowed into a null
 * ({@code CLAUDE.md}, Claude API section).
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    static final String STATUS_OK = "OK";
    static final String STATUS_CLARIFY = "NEEDS_CLARIFICATION";
    static final String STATUS_OUT_OF_SCOPE = "OUT_OF_SCOPE";
    static final String STATUS_BUSY = "BUSY";
    static final String STATUS_DISABLED = "DISABLED";
    static final String STATUS_ERROR = "ERROR";

    private static final Set<String> METRIC_TYPES = Set.of("GAUGE", "COUNTER", "HISTOGRAM", "SUMMARY");
    private static final int MAX_QUESTION_CHARS = 2_000;
    private static final int MAX_SQL_CHARS = 20_000;

    private static final LlmOutputSchema SQL_SCHEMA =
        new LlmOutputSchema("assistant_sql", LlmSchemas.assistantSql());
    private static final LlmOutputSchema METRIC_SCHEMA =
        new LlmOutputSchema("assistant_metric", LlmSchemas.assistantMetric());

    private final AssistantProperties properties;
    private final AssistantLlm llm;
    private final AssistantContextService contextService;
    private final AssistantSqlGate gate;
    private final MeterRegistry meterRegistry;
    private final Semaphore permit = new Semaphore(1);
    private final ObjectMapper mapper = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    public AssistantService(AssistantProperties properties, AssistantLlm llm,
                            AssistantContextService contextService, AssistantSqlGate gate,
                            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.llm = llm;
        this.contextService = contextService;
        this.gate = gate;
        this.meterRegistry = meterRegistry;
    }

    public AssistantStatus status() {
        String problem = llm.problem();
        try {
            ClaudeConfig effective = llm.effectiveConfig();
            return new AssistantStatus(properties.isEnabled(), problem, effective.getProviderLabel(),
                effective.getModel(), effective.isLocalDeployment());
        } catch (IllegalArgumentException e) {
            return new AssistantStatus(properties.isEnabled(), problem, null, null, false);
        }
    }

    /** {@code GENERATE} or {@code FIX}. */
    public AssistantSqlResponse sql(AssistantSqlRequest request) {
        if (request == null) throw new IllegalArgumentException("A request body is required.");
        String mode = request.mode() == null ? "GENERATE" : request.mode().strip().toUpperCase(Locale.ROOT);
        String outcomeMode = mode.toLowerCase(Locale.ROOT);
        Job job;
        switch (mode) {
            case "GENERATE" -> {
                String question = required(request.question(), "question", MAX_QUESTION_CHARS);
                job = new Job(question, null, request.tables(), AssistantPrompts.SQL_SYSTEM, SQL_SCHEMA,
                    false, catalogue -> AssistantPrompts.generate(catalogue, question));
            }
            case "FIX" -> {
                String sql = required(request.sql(), "sql", MAX_SQL_CHARS);
                String error = required(request.error(), "error", MAX_SQL_CHARS);
                job = new Job(null, sql, request.tables(), AssistantPrompts.SQL_SYSTEM, SQL_SCHEMA,
                    false, catalogue -> AssistantPrompts.fix(catalogue, sql, error));
            }
            default -> throw new IllegalArgumentException("mode must be GENERATE or FIX, not '" + mode + "'.");
        }
        Run run = run(job);
        record(outcomeMode, run.response());
        return run.response();
    }

    public AssistantMetricResponse metric(AssistantMetricRequest request) {
        if (request == null) throw new IllegalArgumentException("A request body is required.");
        String description = required(request.description(), "description", MAX_QUESTION_CHARS);
        List<String> tables = request.topic() == null || request.topic().isBlank()
            ? List.of() : List.of(request.topic().strip());
        Job job = new Job(description, null, tables, AssistantPrompts.metricSystem(), METRIC_SCHEMA,
            true, catalogue -> AssistantPrompts.generate(catalogue, description));
        Run run = run(job);
        record("metric", run.response());
        JsonNode answer = run.answer();
        String type = text(answer, "type");
        type = type == null ? null : type.toUpperCase(Locale.ROOT);
        return new AssistantMetricResponse(run.response(),
            text(answer, "name"),
            type != null && METRIC_TYPES.contains(type) ? type : "GAUGE",
            text(answer, "description"));
    }

    // ── the pipeline ─────────────────────────────────────────────────────────────────────────

    private record Job(String question, String sql, List<String> tables, String system,
                       LlmOutputSchema schema, boolean metric,
                       java.util.function.Function<String, String> prompt) {
    }

    private record Run(AssistantSqlResponse response, JsonNode answer) {
    }

    private Run run(Job job) {
        long started = System.nanoTime();
        String problem = llm.problem();
        if (problem != null) {
            String status = properties.isEnabled() ? STATUS_ERROR : STATUS_DISABLED;
            return new Run(AssistantSqlResponse.refused(status, problem), null);
        }
        if (!permit.tryAcquire()) {
            return new Run(AssistantSqlResponse.refused(STATUS_BUSY,
                "The assistant is answering another request; try again when it has finished."), null);
        }
        try {
            return answer(job, started);
        } finally {
            permit.release();
        }
    }

    private Run answer(Job job, long started) {
        AssistantContextService.Context context = contextService.build(job.question(), job.sql(), job.tables());
        AssistantPrompts.Catalogue catalogue =
            AssistantPrompts.catalogue(context.tables(), properties.getPromptCharBudget());
        List<String> omitted = new ArrayList<>(catalogue.tablesDropped());
        omitted.addAll(context.omitted());
        List<String> shown = catalogue.tablesShown();
        ClaudeConfig effective = llm.effectiveConfig();

        if (shown.isEmpty()) {
            String missing = omitted.isEmpty()
                ? "No table matches the request. Name a table or a topic, or select tables in the schema browser."
                : "None of the matching tables could be described (no schema could be inferred): "
                    + String.join(", ", omitted) + ".";
            return new Run(new AssistantSqlResponse(STATUS_CLARIFY, null, null, missing, false, null, 0,
                shown, omitted, effective.getModel(), null, elapsed(started), null), null);
        }

        String prompt = job.prompt().apply(catalogue.text());
        LlmUsage usage = null;
        String model = effective.getModel();
        JsonNode answer;
        try {
            Ask first = ask(effective, job, prompt);
            usage = first.usage();
            model = first.model() != null ? first.model() : model;
            answer = first.answer();
            String status = statusOf(answer);
            String sql = text(answer, "sql");
            if (!STATUS_OK.equals(status) || sql == null) {
                return new Run(new AssistantSqlResponse(STATUS_OK.equals(status) ? STATUS_CLARIFY : status,
                    null, text(answer, "explanation"),
                    text(answer, "missing") != null ? text(answer, "missing")
                        : (STATUS_OK.equals(status) ? "The model returned no statement." : null),
                    false, null, 0, shown, omitted, model, usage, elapsed(started), null), answer);
            }

            int rounds = 0;
            AssistantSqlGate.Result verdict = gate.check(sql, shown, job.metric());
            String lastPrompt = prompt;
            while (verdict.verdict() == AssistantSqlGate.Verdict.REPAIRABLE && rounds < properties.getRepairRounds()) {
                rounds++;
                lastPrompt = AssistantPrompts.repair(lastPrompt, verdict.sql(), verdict.message());
                Ask repaired = ask(effective, job, lastPrompt);
                usage = sum(usage, repaired.usage());
                if (repaired.model() != null) model = repaired.model();
                if (!STATUS_OK.equals(statusOf(repaired.answer())) || text(repaired.answer(), "sql") == null) {
                    // A model that gives up on the repair keeps its first, refused attempt on
                    // screen with the planner's sentence: more useful than an empty answer.
                    break;
                }
                answer = repaired.answer();
                verdict = gate.check(text(answer, "sql"), shown, job.metric());
            }

            boolean forbidden = verdict.verdict() == AssistantSqlGate.Verdict.FORBIDDEN;
            return new Run(new AssistantSqlResponse(
                forbidden ? STATUS_OUT_OF_SCOPE : STATUS_OK,
                forbidden ? null : verdict.sql(),
                text(answer, "explanation"),
                null,
                verdict.accepted(),
                verdict.message(),
                rounds, shown, omitted, model, usage, elapsed(started), null), answer);
        } catch (AnswerFormatException e) {
            return new Run(failure(e.getMessage(), shown, omitted, model, usage, started), null);
        } catch (RuntimeException e) {
            log.warn("Assistant model call failed: {}", e.toString());
            return new Run(failure("The model call failed: " + SqlErrorClassifier.explain(e),
                shown, omitted, model, usage, started), null);
        }
    }

    private record Ask(JsonNode answer, LlmUsage usage, String model) {
    }

    private Ask ask(ClaudeConfig effective, Job job, String prompt) {
        LlmResponse response = llm.client(effective).generateWithMeta(job.system(), prompt, job.schema());
        String raw = response.text();
        String payload = raw == null ? null : LlmJsonSupport.extractJsonPayload(raw);
        if (payload == null || payload.isBlank()) {
            throw new AnswerFormatException("The model did not return the JSON object it was asked for.");
        }
        try {
            JsonNode node = mapper.readTree(payload);
            if (node == null || !node.isObject()) throw new AnswerFormatException(
                "The model's answer is not a JSON object.");
            LlmUsage usage = response.usage();
            return new Ask(node, usage, usage == null ? null : usage.model());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AnswerFormatException("The model's answer is not valid JSON: " + e.getOriginalMessage());
        }
    }

    private static final class AnswerFormatException extends RuntimeException {
        AnswerFormatException(String message) {
            super(message);
        }
    }

    private static AssistantSqlResponse failure(String error, List<String> shown, List<String> omitted,
                                                String model, LlmUsage usage, long started) {
        return new AssistantSqlResponse(STATUS_ERROR, null, null, null, false, null, 0,
            shown, omitted, model, usage, elapsed(started), error);
    }

    private static String statusOf(JsonNode answer) {
        String status = text(answer, "status");
        if (status == null) return STATUS_OK;
        status = status.strip().toUpperCase(Locale.ROOT);
        return switch (status) {
            case STATUS_OK, STATUS_CLARIFY, STATUS_OUT_OF_SCOPE -> status;
            default -> STATUS_CLARIFY;
        };
    }

    static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String text = value.asText();
        return text == null || text.isBlank() ? null : text.strip();
    }

    /** Two calls' cost, summed field by field; a field neither reported stays unreported. */
    static LlmUsage sum(LlmUsage a, LlmUsage b) {
        if (a == null) return b;
        if (b == null) return a;
        return new LlmUsage(add(a.inputTokens(), b.inputTokens()), add(a.outputTokens(), b.outputTokens()),
            a.costUsd() == null ? b.costUsd() : b.costUsd() == null ? a.costUsd() : a.costUsd() + b.costUsd(),
            add(a.cachedInputTokens(), b.cachedInputTokens()), add(a.reasoningTokens(), b.reasoningTokens()),
            a.durationMs() + b.durationMs(), b.provider() != null ? b.provider() : a.provider(),
            b.model() != null ? b.model() : a.model());
    }

    private static Long add(Long a, Long b) {
        if (a == null) return b;
        if (b == null) return a;
        return a + b;
    }

    private static String required(String value, String field, int maxChars) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("'" + field + "' is required.");
        }
        if (value.length() > maxChars) {
            throw new IllegalArgumentException("'" + field + "' is longer than " + maxChars + " characters.");
        }
        return value.strip();
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    /**
     * {@code explorer_assistant_requests_total{mode, outcome}} — the rate of {@code unvalidated} in
     * production is the continuing measurement of what the scope document's evaluation measures
     * once.
     */
    private void record(String mode, AssistantSqlResponse response) {
        String outcome = switch (response.status()) {
            case STATUS_OK -> response.validated()
                ? (response.repairRounds() > 0 ? "repaired" : "validated")
                : "unvalidated";
            case STATUS_CLARIFY, STATUS_OUT_OF_SCOPE -> "clarification";
            case STATUS_BUSY -> "busy";
            case STATUS_DISABLED -> "disabled";
            default -> "error";
        };
        Counter.builder("explorer_assistant_requests")
            .description("SQL assistant requests by mode and outcome")
            .tags("mode", mode, "outcome", outcome)
            .register(meterRegistry)
            .increment();
    }
}
