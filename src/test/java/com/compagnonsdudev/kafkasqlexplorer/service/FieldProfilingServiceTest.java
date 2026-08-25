// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.ProcessMiningConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.FieldProfileResult;
import com.compagnonsdudev.kafkasqlexplorer.domain.LlmResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.PayloadDigest;
import com.compagnonsdudev.kafkasqlexplorer.domain.SnapshotConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.SnapshotRead;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FieldProfilingServiceTest {

    private static final PayloadDigestService DIGEST_SERVICE =
        new PayloadDigestService(new ProcessMiningConfig());

    private KafkaSnapshotReader snapshotReader;
    private ClaudeConfig claudeConfig;
    private FieldProfilingService fieldProfilingService;

    /** Digests a raw payload the way the snapshot reader does, so tests exercise the real format. */
    private static PayloadDigest digestOf(String topic, String key, String value) {
        return DIGEST_SERVICE.digest(topic, 0, 1L, 1000L, key,
            value.getBytes(StandardCharsets.UTF_8), Set.of());
    }

    @BeforeEach
    void setUp() {
        snapshotReader = mock(KafkaSnapshotReader.class);
        claudeConfig = new ClaudeConfig();
        claudeConfig.setApiKey("test-key");
        // Using actual service but it will internally create a client
        // For testing we might want to mock the client, but it's created in constructor
    }

    /** Stubs the snapshot read with these digests, as a read that ran to completion. */
    private void givenDigests(List<PayloadDigest> digests) {
        when(snapshotReader.readSnapshot(anyList(), any(), any(), anyInt()))
            .thenReturn(SnapshotRead.of(digests));
    }

    /**
     * A profiling run that did not happen is not one that found nothing.
     *
     * <p>Both used to answer with an empty {@code topics} list and the reason in {@code warnings},
     * so an unreachable model and a set of empty topics were the same response — and the two send
     * an operator to opposite places. This is the distinction {@code ProcessMiningResult.error}
     * already draws for the analysis half of the pipeline.
     */
    @Test
    void reportsAnUnreachableModelAsAFailureRatherThanAnEmptyProfile() {
        LlmClient client = mock(LlmClient.class);
        when(client.generateWithMeta(anyString(), anyString(), any()))
            .thenThrow(new RuntimeException("Connection refused"));
        givenDigests(List.of(digestOf("topic1", "k", "{\"id\":\"1\"}")));
        fieldProfilingService = new FieldProfilingService(snapshotReader, claudeConfig, client);

        FieldProfileResult result = fieldProfilingService.profile(List.of("topic1"), SnapshotConfig.latestN(10));

        assertNotNull(result.error(), "an endpoint that could not be reached is a failure");
        assertTrue(result.error().contains("Connection refused"), result.error());
        assertTrue(result.topics().isEmpty());
    }

    /**
     * ...and topics that hold nothing carry no error either: that is a finding about the cluster,
     * which is where the page sends the operator, not an endpoint to go and fix.
     *
     * <p>The model is not asked, though. The prompt would be headings, and what comes back is a
     * proposal about topics nobody showed it — an invented mapping the next step invites the
     * operator to validate. So the reason is stated instead, and the call is not paid for.
     */
    @Test
    void topicsThatHoldNothingAreReportedWithoutSpendingAModelCall() {
        LlmClient client = mock(LlmClient.class);
        givenDigests(List.of());
        fieldProfilingService = new FieldProfilingService(snapshotReader, claudeConfig, client);

        FieldProfileResult result = fieldProfilingService.profile(List.of("topic1"), SnapshotConfig.latestN(10));

        assertNull(result.error(),
            "that the cluster had nothing to profile is a finding, not a fault");
        assertTrue(result.topics().isEmpty());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("no message")),
            result.warnings().toString());
        verify(client, never()).generateWithMeta(anyString(), anyString(), any());
    }

    /** A read that failed is the other case: the profiling did not happen, and that is an error. */
    @Test
    void aBrokerThatCouldNotBeReadIsAFailureRatherThanAnEmptyProfile() {
        LlmClient client = mock(LlmClient.class);
        when(snapshotReader.readSnapshot(anyList(), any(), any(), anyInt())).thenReturn(
            new SnapshotRead(List.of(), Map.of("topic1", 0), List.of(),
                "Broker not available", false));
        fieldProfilingService = new FieldProfilingService(snapshotReader, claudeConfig, client);

        FieldProfileResult result = fieldProfilingService.profile(List.of("topic1"), SnapshotConfig.latestN(10));

        assertNotNull(result.error());
        assertTrue(result.error().contains("Broker not available"), result.error());
        verify(client, never()).generateWithMeta(anyString(), anyString(), any());
    }

    /**
     * A topic that was read and one that was never resolved both produce no row in the validation
     * panel, so the second is indistinguishable there from a topic the model looked at and had
     * nothing to say about. The scope note is what separates them.
     */
    @Test
    void namesTheTopicsTheReadCouldNotCover() {
        LlmClient client = mock(LlmClient.class);
        when(client.generateWithMeta(anyString(), anyString(), any()))
            .thenReturn(new LlmResponse("{\"topics\":[],\"warnings\":[\"model note\"]}",
                List.of(), null));
        when(snapshotReader.readSnapshot(anyList(), any(), any(), anyInt())).thenReturn(
            new SnapshotRead(List.of(digestOf("topic1", "k", "{\"id\":\"1\"}")),
                Map.of("topic1", 1, "typo", 0), List.of("typo"), null, false));
        fieldProfilingService = new FieldProfilingService(snapshotReader, claudeConfig, client);

        FieldProfileResult result = fieldProfilingService.profile(
            List.of("topic1", "typo"), SnapshotConfig.latestN(10));

        assertTrue(result.warnings().get(0).contains("typo"),
            "the scope note comes first: it explains an absence the model cannot know about");
        assertTrue(result.warnings().contains("model note"),
            "and the model's own warnings are kept");
    }

    @Test
    void testProfileWithMissingApiKeyAnthropic() {
        claudeConfig.setProvider(ClaudeConfig.Provider.ANTHROPIC);
        claudeConfig.setApiKey("");
        fieldProfilingService = new FieldProfilingService(snapshotReader, claudeConfig, mock(LlmClient.class));

        FieldProfileResult result = fieldProfilingService.profile(List.of("topic1"), SnapshotConfig.latestN(10));

        assertTrue(result.warnings().contains("LLM API key not configured."));
    }

    @Test
    void testProfileWithMissingApiKeyOpenAiCompatible() {
        claudeConfig.setProvider(ClaudeConfig.Provider.OPENAI_COMPATIBLE);
        claudeConfig.setApiKey("");
        LlmClient llmClient = mock(LlmClient.class);
        fieldProfilingService = new FieldProfilingService(snapshotReader, claudeConfig, llmClient);

        givenDigests(List.of(
            digestOf("topic1", "key1", "{\"id\":1}")
        ));
        when(llmClient.generateWithMeta(anyString(), anyString(), any()))
            .thenReturn(new LlmResponse("{\"topics\": [], \"warnings\": []}", List.of()));

        FieldProfileResult result = fieldProfilingService.profile(List.of("topic1"), SnapshotConfig.latestN(10));

        assertNotNull(result);
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void testProfileSuccess() {
        LlmClient llmClient = mock(LlmClient.class);
        fieldProfilingService = new FieldProfilingService(snapshotReader, claudeConfig, llmClient);

        givenDigests(List.of(
            digestOf("topic1", "key1", "{\"id\":1}")
        ));

        when(llmClient.generateWithMeta(anyString(), anyString(), any()))
            .thenReturn(new LlmResponse("{\"topics\": [], \"warnings\": []}", List.of()));

        FieldProfileResult result = fieldProfilingService.profile(List.of("topic1"), SnapshotConfig.latestN(10));

        assertNotNull(result);
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void testProfileParsesJsonWrappedInProse() {
        LlmClient llmClient = mock(LlmClient.class);
        fieldProfilingService = new FieldProfilingService(snapshotReader, claudeConfig, llmClient);

        givenDigests(List.of(
            digestOf("topic1", "key1", "{\"id\":1}")
        ));

        when(llmClient.generateWithMeta(anyString(), anyString(), any())).thenReturn(new LlmResponse("""
            Here is the requested payload:
            ```json
            {"topics": [], "warnings": []}
            ```
            """, List.of()));

        FieldProfileResult result = fieldProfilingService.profile(List.of("topic1"), SnapshotConfig.latestN(10));

        assertNotNull(result);
        assertTrue(result.warnings().isEmpty());
    }

    /**
     * The prompt this pipeline runs <em>first</em> must respect the global character budget.
     *
     * <p>It sized itself as {@code max(4 000, budget / topics)} per topic and multiplied that by the
     * topic count — which is not a budget: "Select all" on a hundred-topic cluster claimed 400 000
     * characters against a {@code prompt-char-budget} of 120 000. The identical defect was corrected
     * in {@code LlmAnalysisService.appendMessages} and left standing here, in the one prompt that
     * runs on every pipeline whether or not an analysis follows.
     */
    @Test
    void testProfilingPromptRespectsTheGlobalCharBudget() {
        LlmClient llmClient = mock(LlmClient.class);
        ProcessMiningConfig config = new ProcessMiningConfig();
        fieldProfilingService = new FieldProfilingService(snapshotReader, claudeConfig, config,
            DIGEST_SERVICE, () -> llmClient);

        // 100 topics, each with a payload rich enough to fill its per-topic share several times over.
        List<String> topics = new java.util.ArrayList<>();
        List<PayloadDigest> digests = new java.util.ArrayList<>();
        for (int t = 0; t < 100; t++) {
            String topic = "topic-" + t;
            topics.add(topic);
            StringBuilder payload = new StringBuilder("{");
            for (int f = 0; f < 60; f++) {
                if (f > 0) payload.append(",");
                payload.append("\"field").append(f).append("\":\"").append("v".repeat(120)).append('"');
            }
            digests.add(digestOf(topic, "k", payload.append('}').toString()));
        }
        givenDigests(digests);
        when(llmClient.generateWithMeta(anyString(), anyString(), any()))
            .thenReturn(new LlmResponse("{\"topics\": [], \"warnings\": []}", List.of()));

        fieldProfilingService.profile(topics, SnapshotConfig.latestN(10));

        org.mockito.ArgumentCaptor<String> prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(llmClient).generateWithMeta(anyString(), prompt.capture(), any());
        String sent = prompt.getValue();

        // Generous headroom over the budget for the fixed sections (instructions, JSON template,
        // the per-topic headers) — what is being pinned is that the total no longer scales with the
        // topic count, not an exact size.
        int budget = config.getPromptCharBudget();
        assertTrue(sent.length() < budget * 2,
            "prompt was " + sent.length() + " chars for a budget of " + budget);
        // And the topics that did not fit are named rather than silently absent.
        assertTrue(sent.contains("budget global du prompt atteint"), "no note about omitted topics");
    }

    /** A reasoning model that never reached an answer is not a malformed answer. */
    @Test
    void testAnswerThatIsOnlyAReasoningTraceIsReportedAsSuch() {
        LlmClient llmClient = mock(LlmClient.class);
        fieldProfilingService = new FieldProfilingService(snapshotReader, claudeConfig, llmClient);

        givenDigests(List.of(
            digestOf("topic1", "key1", "{\"id\":1}")
        ));
        when(llmClient.generateWithMeta(anyString(), anyString(), any())).thenReturn(new LlmResponse(
            "<think>Looking at {topic1}, the id field looks like a", List.of()));

        FieldProfileResult result = fieldProfilingService.profile(List.of("topic1"), SnapshotConfig.latestN(10));

        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("claude.max-tokens"), result.warnings().get(0));
    }

    /** An extra key the model volunteered must not throw the whole profile away. */
    @Test
    void testExtraKeysDoNotFailTheProfile() {
        LlmClient llmClient = mock(LlmClient.class);
        fieldProfilingService = new FieldProfilingService(snapshotReader, claudeConfig, llmClient);

        givenDigests(List.of(
            digestOf("topic1", "key1", "{\"id\":1}")
        ));
        when(llmClient.generateWithMeta(anyString(), anyString(), any())).thenReturn(new LlmResponse(
            "{\"topics\": [], \"warnings\": [], \"notes\": \"volunteered by the model\"}", List.of()));

        FieldProfileResult result = fieldProfilingService.profile(List.of("topic1"), SnapshotConfig.latestN(10));

        assertNotNull(result);
        assertTrue(result.warnings().isEmpty(), () -> "unexpected warnings: " + result.warnings());
    }
}
