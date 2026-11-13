// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval;

import com.compagnonsdudev.kafkasqlexplorer.config.AssistantProperties;
import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.AssistantMetricRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.AssistantSqlRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.AssistantSqlResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.LlmUsage;
import com.compagnonsdudev.kafkasqlexplorer.service.AssistantContextService;
import com.compagnonsdudev.kafkasqlexplorer.service.AssistantLlm;
import com.compagnonsdudev.kafkasqlexplorer.service.AssistantService;
import com.compagnonsdudev.kafkasqlexplorer.service.AssistantSqlGate;
import com.compagnonsdudev.kafkasqlexplorer.service.KafkaAdminService;
import com.compagnonsdudev.kafkasqlexplorer.service.SqlErrorClassifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * Lot 0 of {@code EMBEDDED-LLM-SCOPE.md}: the SQL assistant, end to end, against a real model.
 *
 * <p>Every case goes through the real {@link AssistantService} — context selection, prompt, the
 * model, the gate, the repair round — on the fixture's tables, and an accepted statement is then
 * <b>run</b> and compared with the reference by what it returns. The score that matters is not
 * "accepted by the planner" but the share accepted <em>and wrong</em>: a valid statement that
 * answers another question is the plausible wrong answer the gate cannot see.
 *
 * <p>A report, not a gate: it writes {@code target/assistant-eval/<model>.json} and {@code .md} and
 * asserts only that it ran. The thresholds that decide what ships are in the scope document §7 and
 * belong to a person reading the report, not to a build that would go red on a slow laptop.
 *
 * <p>Tagged {@code assistant-eval} and excluded from every default build (surefire and
 * {@code verify-offline.sh}); it <b>skips</b> when no model is named. Run it with
 * {@code bin/llm-eval.sh} (both consumers of the model, one summary), or:
 *
 * <pre>
 * ASSISTANT_EVAL_MODEL=qwen3:4b-instruct ./mvnw test -P assistant-eval
 * </pre>
 *
 * Settings, each also readable as a system property of the same name in lower case with dots:
 * {@code ASSISTANT_EVAL_MODEL} (required), {@code ASSISTANT_EVAL_PROVIDER} (default
 * {@code LLAMA_CPP}), {@code ASSISTANT_EVAL_BASE_URL} (default the provider's),
 * {@code ASSISTANT_EVAL_API_KEY}, {@code ASSISTANT_EVAL_TIMEOUT_SECONDS} (per model call, default
 * 300), {@code ASSISTANT_EVAL_CASES} (ids or families, comma-separated, to run a subset).
 */
@Tag("assistant-eval")
class AssistantEvalTest {

    /** What one case came to. */
    record Outcome(String id, String family, String kind, String status, boolean validated,
                   boolean executed, boolean correct, int repairRounds, long durationMs,
                   Long inputTokens, Long outputTokens, String sql, String validationError,
                   String executionError, String error) {
        String bucket() {
            if (validated && correct) return "correct";
            if (validated && executed) return "valid_but_wrong";
            if (validated) return "valid_not_run";
            if (sql != null) return "unvalidated";
            if ("ERROR".equals(status)) return "error";
            return "no_statement";
        }
    }

    @Test
    void runsTheAssistantOnEveryCase() throws Exception {
        String model = setting("ASSISTANT_EVAL_MODEL");
        assumeTrue(model != null && !model.isBlank(),
            "No model named for the assistant eval — set ASSISTANT_EVAL_MODEL (and ASSISTANT_EVAL_PROVIDER / "
                + "ASSISTANT_EVAL_BASE_URL for anything but a local llama.cpp). Skipped, not failed.");

        ClaudeConfig claude = new ClaudeConfig();
        String provider = setting("ASSISTANT_EVAL_PROVIDER");
        claude.setProvider(ClaudeConfig.Provider.valueOf(
            (provider == null || provider.isBlank() ? "LLAMA_CPP" : provider.strip()).toUpperCase(Locale.ROOT)));
        String baseUrl = setting("ASSISTANT_EVAL_BASE_URL");
        claude.setBaseUrl(baseUrl == null ? "" : baseUrl.strip());
        claude.setModel(model.strip());
        String key = setting("ASSISTANT_EVAL_API_KEY");
        if (key != null) claude.setApiKey(key.strip());

        AssistantProperties properties = new AssistantProperties();
        properties.setEnabled(true);
        String timeout = setting("ASSISTANT_EVAL_TIMEOUT_SECONDS");
        properties.setRequestTimeoutSeconds(timeout == null ? 300 : Integer.parseInt(timeout.strip()));

        Set<String> only = subset(setting("ASSISTANT_EVAL_CASES"));
        List<Outcome> outcomes = new ArrayList<>();
        try (AssistantEvalFixture fixture = AssistantEvalFixture.load()) {
            AssistantLlm llm = new AssistantLlm(claude, properties);
            String problem = llm.problem();
            assumeTrue(problem == null, "The configured model cannot be called: " + problem);
            AssistantService service = new AssistantService(properties, llm,
                new AssistantContextService(fixture.flinkSqlService, mock(KafkaAdminService.class), properties),
                new AssistantSqlGate(fixture.flinkSqlService, fixture.validator), new SimpleMeterRegistry());

            for (AssistantEvalFixture.Case c : AssistantEvalFixture.cases()) {
                if (!only.isEmpty() && !only.contains(c.id()) && !only.contains(c.family())) continue;
                Outcome outcome = run(fixture, service, c);
                outcomes.add(outcome);
                System.out.printf(Locale.ROOT, "%-4s %-9s %-15s %6.1f s  %s%n", c.id(), c.family(),
                    outcome.bucket(), outcome.durationMs() / 1000.0,
                    outcome.sql() == null ? Objects2.firstNonNull(outcome.error(), outcome.status()) : oneLine(outcome.sql()));
            }
            llm.shutdown();
        }
        assertFalse(outcomes.isEmpty(), "no case was selected");
        write(model, claude, outcomes);
    }

    private static Outcome run(AssistantEvalFixture fixture, AssistantService service, AssistantEvalFixture.Case c) {
        AssistantSqlResponse response;
        switch (c.kind()) {
            case "METRIC" -> response = service.metric(new AssistantMetricRequest(c.question(), c.topic())).proposal();
            case "FIX" -> {
                // The planner's own sentence, exactly as the editor would hand it over.
                String error = fixture.flinkSqlService.planCheck(c.broken()).message();
                response = service.sql(new AssistantSqlRequest("FIX", null, c.broken(), error, null));
            }
            default -> response = service.sql(new AssistantSqlRequest("GENERATE", c.question(), null, null, null));
        }

        boolean executed = false;
        boolean correct = false;
        String executionError = null;
        if (response.validated() && response.sql() != null) {
            try {
                List<List<String>> expected = fixture.execute(c.reference(), 120);
                List<List<String>> actual = fixture.execute(response.sql(), 120);
                executed = true;
                correct = AssistantEvalFixture.answers(expected, actual);
            } catch (Exception e) {
                executionError = SqlErrorClassifier.explain(e);
            }
        }
        LlmUsage usage = response.usage();
        return new Outcome(c.id(), c.family(), c.kind(), response.status(), response.validated(), executed,
            correct, response.repairRounds(), response.durationMs(),
            usage == null ? null : usage.inputTokens(), usage == null ? null : usage.outputTokens(),
            response.sql(), response.validationError(), executionError, response.error());
    }

    // ── the report ───────────────────────────────────────────────────────────────────────────

    private static void write(String model, ClaudeConfig claude, List<Outcome> outcomes) throws Exception {
        Path dir = Path.of("target", "assistant-eval");
        Files.createDirectories(dir);
        String slug = model.replaceAll("[^A-Za-z0-9._-]+", "_");

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("model", model);
        summary.put("provider", claude.getProviderLabel());
        summary.put("endpoint", claude.getResolvedBaseUrl());
        summary.put("cases", outcomes.size());
        summary.put("overall", tally(outcomes));
        Map<String, Object> byFamily = new TreeMap<>();
        outcomes.stream().collect(Collectors.groupingBy(Outcome::family))
            .forEach((family, list) -> byFamily.put(family, tally(list)));
        summary.put("byFamily", byFamily);
        summary.put("outcomes", outcomes);
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
            .writeValue(dir.resolve(slug + ".json").toFile(), summary);

        StringBuilder md = new StringBuilder();
        md.append("# Assistant eval — ").append(model).append("\n\n")
            .append(claude.getProviderLabel()).append(" at ").append(claude.getResolvedBaseUrl())
            .append(", ").append(outcomes.size()).append(" cases.\n\n")
            .append("| family | cases | accepted | correct | valid but wrong | unvalidated | no statement / error | p50 s | p95 s |\n")
            .append("|---|---|---|---|---|---|---|---|---|\n");
        byFamily.forEach((family, t) -> row(md, family, (Map<?, ?>) t));
        row(md, "**all**", tally(outcomes));
        md.append("\n`accepted` = the planner accepted the statement; `correct` = accepted and it returns the ")
            .append("reference's rows; `valid but wrong` = accepted, ran, and answered something else.\n");
        Files.writeString(dir.resolve(slug + ".md"), md);
        System.out.println(md);
    }

    private static Map<String, Object> tally(List<Outcome> outcomes) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("cases", outcomes.size());
        t.put("accepted", outcomes.stream().filter(Outcome::validated).count());
        t.put("correct", outcomes.stream().filter(o -> o.bucket().equals("correct")).count());
        t.put("validButWrong", outcomes.stream().filter(o -> o.bucket().equals("valid_but_wrong")).count());
        t.put("unvalidated", outcomes.stream().filter(o -> o.bucket().equals("unvalidated")).count());
        t.put("noStatementOrError", outcomes.stream()
            .filter(o -> o.bucket().equals("no_statement") || o.bucket().equals("error")).count());
        t.put("repaired", outcomes.stream().filter(o -> o.validated() && o.repairRounds() > 0).count());
        long[] durations = outcomes.stream().mapToLong(Outcome::durationMs).sorted().toArray();
        t.put("p50Seconds", percentile(durations, 0.50));
        t.put("p95Seconds", percentile(durations, 0.95));
        return t;
    }

    private static double percentile(long[] sorted, double q) {
        if (sorted.length == 0) return 0;
        int index = (int) Math.ceil(q * sorted.length) - 1;
        return Math.round(sorted[Math.max(0, Math.min(sorted.length - 1, index))] / 100.0) / 10.0;
    }

    private static void row(StringBuilder md, String name, Map<?, ?> t) {
        long cases = ((Number) t.get("cases")).longValue();
        md.append("| ").append(name).append(" | ").append(cases)
            .append(" | ").append(share(t.get("accepted"), cases))
            .append(" | ").append(share(t.get("correct"), cases))
            .append(" | ").append(share(t.get("validButWrong"), cases))
            .append(" | ").append(share(t.get("unvalidated"), cases))
            .append(" | ").append(share(t.get("noStatementOrError"), cases))
            .append(" | ").append(t.get("p50Seconds")).append(" | ").append(t.get("p95Seconds")).append(" |\n");
    }

    private static String share(Object count, long cases) {
        long n = ((Number) count).longValue();
        return n + " (" + (cases == 0 ? 0 : Math.round(100.0 * n / cases)) + " %)";
    }

    private static Set<String> subset(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty())
            .collect(Collectors.toSet());
    }

    /** {@code ASSISTANT_EVAL_MODEL} or {@code -Dassistant.eval.model}, the property winning. */
    private static String setting(String env) {
        String property = System.getProperty(env.toLowerCase(Locale.ROOT).replace('_', '.'));
        return property != null ? property : System.getenv(env);
    }

    private static String oneLine(String sql) {
        String line = sql.replaceAll("\\s+", " ").strip();
        return line.length() > 110 ? line.substring(0, 107) + "..." : line;
    }

    private static final class Objects2 {
        static String firstNonNull(String a, String b) {
            return a != null ? a : b;
        }
    }
}
