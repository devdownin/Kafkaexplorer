// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval;

import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.ProcessMiningConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.ProcessMiningResult;
import com.compagnonsdudev.kafkasqlexplorer.domain.ProcessModel;
import com.compagnonsdudev.kafkasqlexplorer.domain.SnapshotConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.SnapshotRead;
import com.compagnonsdudev.kafkasqlexplorer.service.KafkaSnapshotReader;
import com.compagnonsdudev.kafkasqlexplorer.service.LlmAnalysisService;
import com.compagnonsdudev.kafkasqlexplorer.service.LlmClient;
import com.compagnonsdudev.kafkasqlexplorer.service.LlmClientFactory;
import com.compagnonsdudev.kafkasqlexplorer.service.PayloadDigestService;
import com.compagnonsdudev.kafkasqlexplorer.service.ProcessModelBuilder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The loose half of the Process Mining eval — a real model, reading the measured process.
 *
 * <p>{@link ProcessModelEvalTest} asserts the aggregate exactly, and that is the half that can be
 * asserted exactly: it is arithmetic over records. What no unit test can answer is whether a model
 * handed that aggregate draws the pipeline it was given rather than the one the topic names
 * suggest — which is the failure this whole line of work is about, and it is silent by
 * construction, a plausible invented answer reading exactly like an observed one.
 *
 * <p>So the assertions here are deliberately loose: that the flowchart names the order topics and
 * puts an arrow between two of them, that nothing is claimed about a topic the model was told stays
 * outside the log, and that the call came back parseable with a usable narrative. A tighter
 * assertion on a model's prose is a test that fails on a paraphrase, which teaches nobody anything.
 *
 * <h2>Why it is excluded from the build</h2>
 *
 * <p>It calls a real endpoint: it costs money, needs the network, and its verdict depends on which
 * model is configured. None of those belong in {@code mvn verify}, which has to be reproducible and
 * free. It carries {@code @Tag("llm-eval")}, surefire excludes that tag by default, and
 * {@code ./verify-offline.sh} excludes it too. Run it deliberately:
 *
 * <pre>{@code
 * CLAUDE_PROVIDER=OPENROUTER OPENROUTER_API_KEY=sk-... CLAUDE_MODEL=openai/gpt-4o-mini \
 *   ./mvnw test -P llm-eval -Dtest=LlmAnalysisEvalTest
 * }</pre>
 *
 * <p>And it <b>skips rather than fails</b> when nothing is configured. A test that goes red because
 * the person running it has no API key is a test people learn to ignore; an assumption prints why
 * it did not run, which is the honest answer to "was this checked?".
 *
 * <p>It reads its configuration from the environment directly rather than through
 * {@code LlmClientProvider}, which is the one way the application obtains a client. The exception is
 * the rule's own logic rather than a lapse: that rule exists so a probe cannot claim to have tested
 * what the engine uses, and this is not the engine — it is a deliberate measurement of a named
 * endpoint, with no bean mutated and nothing persisted, the same reasoning as the candidate-model
 * probe in {@code ConfigController}.
 */
@Tag("llm-eval")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LlmAnalysisEvalTest {

    /** What the seeded pipeline is: the model must draw this, not something adjacent to it. */
    private static final List<String> EXPECTED_IN_FLOWCHART = List.of(
        "demo.orders.1.received", "demo.orders.2.validated", "demo.orders.6.delivered");

    private ClaudeConfig configFromEnvironment() {
        ClaudeConfig config = new ClaudeConfig();
        String provider = setting("CLAUDE_PROVIDER", "claude.provider");
        if (provider != null && !provider.isBlank()) {
            config.setProvider(ClaudeConfig.Provider.valueOf(provider.trim().toUpperCase(Locale.ROOT)));
        }
        String baseUrl = setting("CLAUDE_BASE_URL", "claude.base-url");
        if (baseUrl != null && !baseUrl.isBlank()) config.setBaseUrl(baseUrl.trim());
        String model = setting("CLAUDE_MODEL", "claude.model");
        if (model != null && !model.isBlank()) config.setModel(model.trim());
        String key = firstNonBlank(
            setting("CLAUDE_API_KEY", "claude.api-key"),
            System.getenv("OPENROUTER_API_KEY"),
            System.getenv("ANTHROPIC_API_KEY"));
        if (key != null) config.setApiKey(key.trim());
        // The stack's own timeout, read like the stack reads it: compose/ollama.yml sets 300 s for a
        // CPU-served model, and without this the eval kept the 60 s default and measured a slow
        // model as a broken one.
        String timeout = setting("CLAUDE_REQUEST_TIMEOUT_SECONDS", "claude.request-timeout-seconds");
        if (timeout != null && !timeout.isBlank()) config.setRequestTimeoutSeconds(Integer.parseInt(timeout.trim()));
        return config;
    }

    /** A system property wins over the environment, so a single `-D` overrides a shell export. */
    private static String setting(String envName, String propertyName) {
        String property = System.getProperty(propertyName);
        return property != null ? property : System.getenv(envName);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private ProcessMiningResult analyse() {
        ClaudeConfig config = configFromEnvironment();
        assumeTrue(config.isApiKeyRequired() ? hasKey(config) : hasEndpoint(config),
            "No LLM is configured for the eval — set CLAUDE_PROVIDER / CLAUDE_MODEL and a key "
                + "(or a base URL for a local endpoint). Skipped, not failed: an absent key is a "
                + "question that was not asked, never an answer.");

        DemoPipelineFixture fixture = DemoPipelineFixture.load();
        KafkaSnapshotReader reader = mock(KafkaSnapshotReader.class);
        when(reader.readSnapshot(anyList(), any(), any(), anyInt()))
            .thenReturn(SnapshotRead.of(fixture.digests()));

        LlmClient client = LlmClientFactory.create(config);
        ProcessMiningConfig ingestion = new ProcessMiningConfig();
        LlmAnalysisService service = new LlmAnalysisService(reader, config, ingestion,
            new PayloadDigestService(ingestion), new ProcessModelBuilder(ingestion), () -> client);

        return service.analyzeSnapshot(fixture.topics(), SnapshotConfig.earliest(500), fixture.mapping());
    }

    private static boolean hasKey(ClaudeConfig config) {
        return config.getApiKey() != null && !config.getApiKey().isBlank();
    }

    private static boolean hasEndpoint(ClaudeConfig config) {
        return config.getBaseUrl() != null && !config.getBaseUrl().isBlank();
    }

    /**
     * The one thing that mattered before any of this: does the answer describe the pipeline the
     * records show? The aggregate is in the prompt and the instructions say to draw the flowchart
     * from its transitions, so a flowchart that names the seeded topics is the observable
     * difference between reading a measurement and guessing from names.
     */
    @Test
    void theFlowchartDrawsThePipelineThatWasMeasured() {
        ProcessMiningResult result = analyse();

        assertNull(result.error(), "the analysis failed: " + result.error());
        assertNotNull(result.flowchart(), "no flowchart came back");
        String flowchart = result.flowchart();
        for (String topic : EXPECTED_IN_FLOWCHART) {
            assertTrue(flowchart.contains(topic),
                "the flowchart does not name " + topic + ":\n" + flowchart);
        }
        assertTrue(flowchart.contains("-->") || flowchart.contains("->"),
            "the flowchart draws no edge at all:\n" + flowchart);
    }

    /**
     * The measurement travels whatever the model says, and the model must not be able to overwrite
     * it: {@code processModel} is {@code READ_ONLY} for exactly this reason. Asserted against a
     * live answer rather than a stub, since that annotation is the only thing standing between a
     * model's JSON and the figures the operator reads as measured.
     */
    @Test
    void theMeasurementSurvivesTheAnswerUntouched() {
        ProcessMiningResult result = analyse();

        ProcessModel model = result.processModel();
        assertNotNull(model, "the measured process did not travel with the answer");
        assertTrue(model.available(), model.unavailableReason());
        assertTrue(model.cases() == 6 && model.events() == 14,
            "the model rewrote the measurement: " + model.cases() + " cases, " + model.events()
                + " events — the fixture holds 6 and 14");
    }

    /**
     * How often the shipped prompt actually comes back in the format it asked for.
     *
     * <p>This is the first half of an experiment `PROCESS-MINING-LLM-SCOPE.md` records as a
     * suspicion rather than a defect: the system prompt is English, the user prompt's headings and
     * instructions are French, the enums are English, and small models are reputed to hold a
     * format less well across a language switch — which matters here because this application is
     * routinely pointed at a 3B model. Nobody had measured it *on this prompt*.
     *
     * <p>Measuring the shipped prompt is deliberately the first step, and an English variant is
     * deliberately <b>not</b> shipped to compare against. A second production prompt is a second
     * surface to keep in step for ever, and it would be built on a belief; if the prompt as it
     * stands holds the format every time on the model under test, the suspicion is answered for
     * that model and nothing needs building. Only a run that shows failures justifies the variant,
     * and it will then be justified by a number.
     *
     * <p>What it reports is therefore the rate, not a verdict on a language: the assertion is that
     * every run held, and the message carries how many did and why the others did not — a parse
     * failure, a model that spent its whole output budget reasoning, and an unreachable endpoint
     * are three different answers. {@code -Dllm.eval.runs=N} buys a tighter number for N times the
     * tokens; three is enough to see a format that fails half the time, and not enough to measure
     * one that fails one time in twenty.
     */
    @Test
    void theShippedPromptHoldsItsFormat() {
        int runs = Math.max(1, Integer.getInteger("llm.eval.runs", 3));
        List<String> failures = new ArrayList<>();
        int held = 0;

        for (int i = 0; i < runs; i++) {
            ProcessMiningResult result = analyse();
            if (result.error() != null) {
                failures.add("run " + (i + 1) + ": " + result.error());
            } else if (result.flowchart() == null || result.flowchart().isBlank()) {
                // Parsed, and still nothing to render: the schema was satisfied by an empty answer.
                failures.add("run " + (i + 1) + ": parsed, but carried no flowchart");
            } else {
                held++;
            }
        }

        assertTrue(failures.isEmpty(),
            "the shipped prompt held its format " + held + "/" + runs + " times on this model. "
                + "That is the measurement PROCESS-MINING-LLM-SCOPE.md asks for — record it there "
                + "before changing anything, and note that a failure here is not by itself "
                + "evidence about the prompt's bilingualism: an endpoint that refused the request "
                + "fails the same way. Reasons:\n  " + String.join("\n  ", failures));
    }

    /**
     * A run that costs tokens has to say so. Loose on purpose: a gateway may report no accounting
     * at all, and a zero there would be the lie {@code LlmUsage} boxes its counts to avoid — so
     * what is asserted is that the duration was measured on this side, which is always real.
     */
    @Test
    void theRunReportsWhatItCost() {
        ProcessMiningResult result = analyse();

        assertNotNull(result.usage(), "no usage travelled with the answer");
        assertTrue(result.usage().durationMs() > 0,
            "the call reported no duration, which is measured on this side and cannot be unknown");
    }

    /**
     * The facts the seeder planted, which a reading of the measurement — rather than of the topic
     * names — finds: an order rejected at validation that leaves the flow (ORD-102), two
     * redeliveries (ORD-103, ORD-105), two truncated payloads (ORD-666, ORD-667), and a 3 → 4 hop
     * three times slower than the others. Matched on the words an answer would use for each, across
     * the anomalies, the comments and the hypotheses: loose, and a report rather than an assertion.
     */
    private static final Map<String, List<String>> PLANTED = Map.of(
        "rejection", List.of("ord-102", "reject"),
        "duplicates", List.of("ord-103", "ord-105", "duplicat", "redeliver", "twice"),
        "corruptPayloads", List.of("ord-666", "ord-667", "corrupt", "malformed", "truncat", "unparse", "invalid json"),
        "slowHop", List.of("transform"));

    /**
     * A comparable report per model, for choosing one — the other tests here say whether a model
     * fails, this says how it does. {@code -Dllm.eval.runs=N} runs (default 3), each recorded:
     * whether the format held, whether the flowchart names the pipeline, which planted facts the
     * answer mentions, how many KPI priorities survived the id check, and what the call cost.
     * Written to {@code target/llm-eval/<model>.json}; asserts only that it ran, like
     * {@code AssistantEvalTest}, whose report it mirrors so the two can be read side by side.
     */
    @Test
    void reportsTheAnalysisForComparison() throws Exception {
        int runs = Math.max(1, Integer.getInteger("llm.eval.runs", 3));
        List<Map<String, Object>> outcomes = new ArrayList<>();
        for (int i = 0; i < runs; i++) {
            ProcessMiningResult result = analyse();
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("run", i + 1);
            run.put("error", result.error());
            String flowchart = result.flowchart() == null ? "" : result.flowchart();
            run.put("formatHeld", result.error() == null && !flowchart.isBlank());
            run.put("flowchartNamesPipeline", EXPECTED_IN_FLOWCHART.stream().allMatch(flowchart::contains)
                && (flowchart.contains("-->") || flowchart.contains("->")));
            StringBuilder said = new StringBuilder();
            if (result.comments() != null) said.append(result.comments()).append('\n');
            if (result.hypotheses() != null) result.hypotheses().forEach(h -> said.append(h).append('\n'));
            if (result.anomalies() != null) result.anomalies().forEach(a -> said.append(a.id()).append(' ')
                .append(a.topic()).append(' ').append(a.description()).append(' ')
                .append(a.probableCause()).append('\n'));
            String text = said.toString().toLowerCase(Locale.ROOT);
            Map<String, Boolean> found = new LinkedHashMap<>();
            PLANTED.forEach((fact, words) -> found.put(fact,
                "slowHop".equals(fact)
                    ? text.contains("transform") && (text.contains("slow") || text.contains("latenc")
                        || text.contains("delay") || text.contains("lent"))
                    : words.stream().anyMatch(text::contains)));
            run.put("plantedFactsFound", new java.util.TreeMap<>(found));
            run.put("anomalies", result.anomalies() == null ? 0 : result.anomalies().size());
            run.put("metricPriorities", result.metricPriorities() == null ? 0 : result.metricPriorities().size());
            run.put("durationMs", result.usage() == null ? null : result.usage().durationMs());
            run.put("inputTokens", result.usage() == null ? null : result.usage().inputTokens());
            run.put("outputTokens", result.usage() == null ? null : result.usage().outputTokens());
            run.put("promptChars", result.coverage() == null ? null : result.coverage().promptChars());
            outcomes.add(run);
            System.out.println("run " + (i + 1) + ": " + run);
        }
        ClaudeConfig config = configFromEnvironment();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("model", config.getModel());
        report.put("provider", config.getProviderLabel());
        report.put("runs", outcomes);
        Path dir = Path.of("target", "llm-eval");
        Files.createDirectories(dir);
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(
            dir.resolve(config.getModel().replaceAll("[^A-Za-z0-9._-]+", "_") + ".json").toFile(), report);
        assertTrue(!outcomes.isEmpty());
    }
}
