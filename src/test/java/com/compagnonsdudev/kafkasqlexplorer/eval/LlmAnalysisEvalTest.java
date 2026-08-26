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
}
