// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.ProcessMiningConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.FieldProfileResult;
import com.compagnonsdudev.kafkasqlexplorer.domain.LlmResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.PayloadDigest;
import com.compagnonsdudev.kafkasqlexplorer.domain.SnapshotConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
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

        when(snapshotReader.readDigested(anyList(), any(), any(), anyInt())).thenReturn(List.of(
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

        when(snapshotReader.readDigested(anyList(), any(), any(), anyInt())).thenReturn(List.of(
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

        when(snapshotReader.readDigested(anyList(), any(), any(), anyInt())).thenReturn(List.of(
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
        when(snapshotReader.readDigested(anyList(), any(), any(), anyInt())).thenReturn(digests);
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

        when(snapshotReader.readDigested(anyList(), any(), any(), anyInt())).thenReturn(List.of(
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

        when(snapshotReader.readDigested(anyList(), any(), any(), anyInt())).thenReturn(List.of(
            digestOf("topic1", "key1", "{\"id\":1}")
        ));
        when(llmClient.generateWithMeta(anyString(), anyString(), any())).thenReturn(new LlmResponse(
            "{\"topics\": [], \"warnings\": [], \"notes\": \"volunteered by the model\"}", List.of()));

        FieldProfileResult result = fieldProfilingService.profile(List.of("topic1"), SnapshotConfig.latestN(10));

        assertNotNull(result);
        assertTrue(result.warnings().isEmpty(), () -> "unexpected warnings: " + result.warnings());
    }
}
