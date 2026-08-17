// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.ProcessMiningConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.FieldMapping;
import com.compagnonsdudev.kafkasqlexplorer.domain.KafkaMessage;
import com.compagnonsdudev.kafkasqlexplorer.domain.PayloadDigest;
import com.compagnonsdudev.kafkasqlexplorer.domain.LlmResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.ProcessMiningResult;
import com.compagnonsdudev.kafkasqlexplorer.domain.RagSource;
import com.compagnonsdudev.kafkasqlexplorer.domain.SnapshotConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LlmAnalysisServiceTest {

    private static final PayloadDigestService DIGEST_SERVICE =
        new PayloadDigestService(new ProcessMiningConfig());

    private KafkaSnapshotReader snapshotReader;
    private ClaudeConfig claudeConfig;
    private LlmClient llmClient;
    private LlmAnalysisService llmAnalysisService;

    /** Digests a raw payload the way the snapshot reader does, so tests exercise the real format. */
    private static PayloadDigest digestOf(String topic, String key, String value) {
        return DIGEST_SERVICE.digest(topic, 0, 1L, 1000L, key,
            value.getBytes(StandardCharsets.UTF_8), Set.of());
    }

    /** A ~1 MB order document with a 5 000-element line-item array. */
    private static String oneMegabyteOrder() {
        StringBuilder sb = new StringBuilder(1_200_000);
        sb.append("{\"order\": {\"id\": \"ORD-1\", \"items\": [");
        for (int i = 0; i < 5_000; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"sku\": \"SKU-").append(i)
              .append("\", \"label\": \"").append("d".repeat(300)).append("\"}");
        }
        sb.append("]}}");
        return sb.toString();
    }

    @BeforeEach
    void setUp() {
        snapshotReader = mock(KafkaSnapshotReader.class);
        claudeConfig = new ClaudeConfig();
        claudeConfig.setApiKey("test-key");
        llmClient = mock(LlmClient.class);
        llmAnalysisService = new LlmAnalysisService(snapshotReader, claudeConfig,
            new ProcessMiningConfig(), DIGEST_SERVICE, () -> llmClient);
    }

    @Test
    void testAnalyzeSnapshotSuccess() {
        when(snapshotReader.readDigested(anyList(), any(), any(), anyInt())).thenReturn(List.of(
            digestOf("topic1", "key1", "{\"val\":1}")
        ));

        String jsonResponse = """
            {
              "flowchart": "flowchart TD\\n[Topic1]",
              "comments": "Analysis",
              "hypotheses": [],
              "blindSpots": [],
              "anomalies": []
            }
            """;
        when(llmClient.generateWithMeta(anyString(), anyString(), any()))
            .thenReturn(new LlmResponse(jsonResponse, List.of()));

        ProcessMiningResult result = llmAnalysisService.analyzeSnapshot(
            List.of("topic1"), SnapshotConfig.latestN(10), null);

        assertNotNull(result);
        assertEquals("flowchart TD\n[Topic1]", result.flowchart());
    }

    @Test
    void testAnalyzeLiveSuccess() {
        String jsonResponse = """
            {
              "flowchart": "NO_CHANGE",
              "comments": "Live Analysis",
              "hypotheses": [],
              "blindSpots": [],
              "anomalies": []
            }
            """;
        when(llmClient.generateWithMeta(anyString(), anyString(), any()))
            .thenReturn(new LlmResponse(jsonResponse, List.of()));

        ProcessMiningResult result = llmAnalysisService.analyzeLive(
            List.of(new KafkaMessage("topic1", 0, 1L, 1000L, "k", "v")),
            null, "flowchart TD\\n[Ref]");

        assertNotNull(result);
        assertEquals("NO_CHANGE", result.flowchart());
    }

    @Test
    void testAuditFocusIsInjectedIntoPrompt() {
        when(snapshotReader.readDigested(anyList(), any(), any(), anyInt())).thenReturn(List.of(
            digestOf("topic1", "key1", "{\"val\":1}")
        ));
        when(llmClient.generateWithMeta(anyString(), anyString(), any())).thenReturn(new LlmResponse(
            "{\"flowchart\":\"x\",\"comments\":\"\",\"hypotheses\":[],\"blindSpots\":[],\"anomalies\":[]}",
            List.of()));

        String focus = "- [Duplicates] Détecte les messages dupliqués.";
        llmAnalysisService.analyzeSnapshot(List.of("topic1"), SnapshotConfig.latestN(10), null, focus);

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(llmClient).generateWithMeta(anyString(), userPrompt.capture(), any());
        assertTrue(userPrompt.getValue().contains("AUDIT CIBLÉ"));
        assertTrue(userPrompt.getValue().contains("Détecte les messages dupliqués."));
    }

    @Test
    void testNoAuditSectionWhenFocusAbsent() {
        when(snapshotReader.readDigested(anyList(), any(), any(), anyInt())).thenReturn(List.of(
            digestOf("topic1", "key1", "{\"val\":1}")
        ));
        when(llmClient.generateWithMeta(anyString(), anyString(), any())).thenReturn(new LlmResponse(
            "{\"flowchart\":\"x\",\"comments\":\"\",\"hypotheses\":[],\"blindSpots\":[],\"anomalies\":[]}",
            List.of()));

        llmAnalysisService.analyzeSnapshot(List.of("topic1"), SnapshotConfig.latestN(10), null);

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(llmClient).generateWithMeta(anyString(), userPrompt.capture(), any());
        assertFalse(userPrompt.getValue().contains("AUDIT CIBLÉ"));
    }

    @Test
    void testAnalyzeSnapshotParsesJsonWrappedInMarkdown() {
        when(snapshotReader.readDigested(anyList(), any(), any(), anyInt())).thenReturn(List.of(
            digestOf("topic1", "key1", "{\"val\":1}")
        ));

        when(llmClient.generateWithMeta(anyString(), anyString(), any())).thenReturn(new LlmResponse("""
            Analysis completed.
            ```json
            {
              "flowchart": "flowchart TD\\n[A] --> [B]",
              "comments": "Analysis",
              "hypotheses": [],
              "blindSpots": [],
              "anomalies": []
            }
            ```
            """, List.of()));

        ProcessMiningResult result = llmAnalysisService.analyzeSnapshot(
            List.of("topic1"), SnapshotConfig.latestN(10), null);

        assertNotNull(result);
        assertEquals("flowchart TD\n[A] --> [B]", result.flowchart());
    }

    @Test
    void testLivePromptStaysWithinBudgetForMegabytePayloads() {
        when(llmClient.generateWithMeta(anyString(), anyString(), any())).thenReturn(new LlmResponse(
            "{\"flowchart\":\"x\",\"comments\":\"\",\"hypotheses\":[],\"blindSpots\":[],\"anomalies\":[]}",
            List.of()));

        // 300 documents of ~1 MB each: verbatim this would be a 300 MB prompt.
        PayloadDigest template = digestOf("orders", "k", oneMegabyteOrder());
        List<PayloadDigest> window = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            window.add(new PayloadDigest(template.topic(), 0, i, 1000L + i, "k" + i,
                template.payloadBytes(), template.format(), template.shapeId(), template.fields(),
                template.sample(), template.arrayCounts(), template.preview(), template.truncated(),
                template.parseError()));
        }

        llmAnalysisService.analyzeLiveDigests(window, null, null, null);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llmClient).generateWithMeta(anyString(), prompt.capture(), any());
        String userPrompt = prompt.getValue();

        ProcessMiningConfig defaults = new ProcessMiningConfig();
        assertTrue(userPrompt.length() < defaults.getPromptCharBudget() * 2,
            "prompt must stay bounded, was " + userPrompt.length() + " chars");
        assertTrue(userPrompt.contains("STRUCTURES DE PAYLOAD"), "shapes must be described once");
        assertTrue(userPrompt.contains(template.shapeId()));
        assertTrue(userPrompt.contains("message(s) non inclus"),
            "the prompt must say that the window was sampled");
        assertFalse(userPrompt.contains("d".repeat(300)),
            "bulky payload values must never reach the prompt");
    }

    @Test
    void testEvenSampleKeepsBothEnds() {
        List<Integer> values = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            values.add(i);
        }

        List<Integer> sampled = LlmAnalysisService.evenSample(values, 5);

        assertEquals(5, sampled.size());
        assertEquals(0, sampled.get(0));
        assertEquals(99, sampled.get(4));
    }

    @Test
    void testRagSourcesAreAttachedToResult() {
        when(snapshotReader.readDigested(anyList(), any(), any(), anyInt())).thenReturn(List.of(
            digestOf("topic1", "key1", "{\"val\":1}")
        ));
        when(llmClient.generateWithMeta(anyString(), anyString(), any())).thenReturn(new LlmResponse(
            "{\"flowchart\":\"x\",\"comments\":\"\",\"hypotheses\":[],\"blindSpots\":[],\"anomalies\":[]}",
            List.of(new RagSource("cited passage", "spec.pdf", 0.9))));

        ProcessMiningResult result = llmAnalysisService.analyzeSnapshot(
            List.of("topic1"), SnapshotConfig.latestN(10), null);

        assertEquals(1, result.ragSources().size());
        assertEquals("spec.pdf", result.ragSources().get(0).sourceFile());
    }

    /**
     * A failure has to be reported as one. It used to travel in {@code comments}, where the page
     * renders it as analysis prose under an empty diagram.
     */
    @Test
    void testCallFailureIsReportedAsAnErrorNotAsCommentary() {
        when(snapshotReader.readDigested(anyList(), any(), any(), anyInt())).thenReturn(List.of(
            digestOf("topic1", "key1", "{\"val\":1}")
        ));
        when(llmClient.generateWithMeta(anyString(), anyString(), any()))
            .thenThrow(new RuntimeException("Connection refused"));

        ProcessMiningResult result = llmAnalysisService.analyzeSnapshot(
            List.of("topic1"), SnapshotConfig.latestN(10), null);

        assertNotNull(result.error(), "a failed call must set error()");
        assertTrue(result.error().contains("Connection refused"), result.error());
        assertNull(result.comments(), "the reason must not masquerade as analysis commentary");
        assertNull(result.flowchart());
    }

    @Test
    void testUnparseableAnswerIsReportedAsAnError() {
        when(snapshotReader.readDigested(anyList(), any(), any(), anyInt())).thenReturn(List.of(
            digestOf("topic1", "key1", "{\"val\":1}")
        ));
        when(llmClient.generateWithMeta(anyString(), anyString(), any()))
            .thenReturn(new LlmResponse("I am afraid I cannot do that.", List.of()));

        ProcessMiningResult result = llmAnalysisService.analyzeSnapshot(
            List.of("topic1"), SnapshotConfig.latestN(10), null);

        assertNotNull(result.error());
        assertNull(result.comments());
    }

    /** A JSON object cut off at the output cap is the commonest parse failure — say so. */
    @Test
    void testTruncatedAnswerNamesTheOutputCap() {
        when(snapshotReader.readDigested(anyList(), any(), any(), anyInt())).thenReturn(List.of(
            digestOf("topic1", "key1", "{\"val\":1}")
        ));
        when(llmClient.generateWithMeta(anyString(), anyString(), any())).thenReturn(new LlmResponse(
            "{\"flowchart\":\"flowchart TD\\nA-->B\",\"anomalies\":[{\"id\":\"ANO-001\"", List.of()));

        ProcessMiningResult result = llmAnalysisService.analyzeSnapshot(
            List.of("topic1"), SnapshotConfig.latestN(10), null);

        assertNotNull(result.error());
        assertTrue(result.error().contains("claude.max-tokens"), result.error());
    }

    /**
     * A model that volunteers an extra key must not cost the whole analysis.
     *
     * <p>The parser used a bare {@code new ObjectMapper()}, which keeps Jackson's default
     * {@code FAIL_ON_UNKNOWN_PROPERTIES}: one {@code summary} beside {@code comments}, or one
     * {@code confidence} on an anomaly, and a complete answer was thrown away — reported with a hint
     * naming {@code claude.max-tokens}, which would not have fixed it. Every unconstrained path
     * lands here: SpectraLLM, an arbitrary OpenAI-compatible gateway, {@code structured-output: OFF},
     * and any endpoint that refused a schema once.
     */
    @Test
    void testExtraKeysDoNotFailTheAnalysis() {
        when(snapshotReader.readDigested(anyList(), any(), any(), anyInt())).thenReturn(List.of(
            digestOf("topic1", "key1", "{\"val\":1}")
        ));
        when(llmClient.generateWithMeta(anyString(), anyString(), any())).thenReturn(new LlmResponse("""
            {
              "flowchart": "flowchart TD\\nA-->B",
              "comments": "Analysis",
              "summary": "an extra key the model volunteered",
              "hypotheses": [],
              "blindSpots": [],
              "anomalies": [
                {"id": "ANO-001", "topic": "topic1", "type": "SEQUENCE", "severity": "MINOR",
                 "fields": [], "description": "d", "probableCause": "c", "ksqlSuggestion": "s",
                 "confidence": 0.8}
              ]
            }
            """, List.of()));

        ProcessMiningResult result = llmAnalysisService.analyzeSnapshot(
            List.of("topic1"), SnapshotConfig.latestN(10), null);

        assertNull(result.error(), () -> "unexpected failure: " + result.error());
        assertEquals("flowchart TD\nA-->B", result.flowchart());
        assertEquals(1, result.anomalies().size());
    }

    /**
     * The default model ({@code qwen3:4b}) reasons before answering. Its trace quotes the JSON it is
     * about to write, so the first brace in the answer sits inside the deliberation — and the
     * balanced scan used to return a fragment of it, which parsed cleanly into a flowchart the model
     * never proposed. Being silently wrong is worse than failing.
     */
    @Test
    void testReasoningTraceIsNotMistakenForTheAnswer() {
        when(snapshotReader.readDigested(anyList(), any(), any(), anyInt())).thenReturn(List.of(
            digestOf("topic1", "key1", "{\"val\":1}")
        ));
        when(llmClient.generateWithMeta(anyString(), anyString(), any())).thenReturn(new LlmResponse("""
            <think>
            I should answer with {"flowchart": "TODO"} once I have worked out the flow.
            </think>
            {"flowchart": "flowchart TD\\nA-->B", "comments": "real", "hypotheses": [],
             "blindSpots": [], "anomalies": []}
            """, List.of()));

        ProcessMiningResult result = llmAnalysisService.analyzeSnapshot(
            List.of("topic1"), SnapshotConfig.latestN(10), null);

        assertNull(result.error(), () -> "unexpected failure: " + result.error());
        assertEquals("flowchart TD\nA-->B", result.flowchart());
        assertEquals("real", result.comments());
    }

    /** A model still thinking when it hit the cap produced no answer — say that, not "bad JSON". */
    @Test
    void testAnswerThatIsOnlyAReasoningTraceNamesTheOutputCap() {
        when(snapshotReader.readDigested(anyList(), any(), any(), anyInt())).thenReturn(List.of(
            digestOf("topic1", "key1", "{\"val\":1}")
        ));
        when(llmClient.generateWithMeta(anyString(), anyString(), any())).thenReturn(new LlmResponse(
            "<think>Let me consider each topic in turn. First {topic1}, which seems", List.of()));

        ProcessMiningResult result = llmAnalysisService.analyzeSnapshot(
            List.of("topic1"), SnapshotConfig.latestN(10), null);

        assertNotNull(result.error());
        assertTrue(result.error().contains("claude.max-tokens"), result.error());
        assertTrue(result.error().contains("reasoning"), result.error());
    }

    /**
     * The client is resolved per call, so a provider swapped through POST /api/config takes effect
     * on the next analysis. It used to be captured in the constructor, which left every analysis on
     * the provider configured at startup while the settings page reported the new one reachable.
     */
    @Test
    void testClientIsResolvedPerCall() {
        LlmClient first = mock(LlmClient.class);
        LlmClient second = mock(LlmClient.class);
        List<LlmClient> current = new ArrayList<>(List.of(first));
        String answer = "{\"flowchart\":\"x\",\"comments\":\"c\",\"hypotheses\":[],"
            + "\"blindSpots\":[],\"anomalies\":[]}";
        when(first.generateWithMeta(anyString(), anyString(), any())).thenReturn(new LlmResponse(answer, List.of()));
        when(second.generateWithMeta(anyString(), anyString(), any())).thenReturn(new LlmResponse(answer, List.of()));
        when(snapshotReader.readDigested(anyList(), any(), any(), anyInt())).thenReturn(List.of(
            digestOf("topic1", "key1", "{\"val\":1}")
        ));

        LlmAnalysisService service = new LlmAnalysisService(snapshotReader, claudeConfig,
            new ProcessMiningConfig(), DIGEST_SERVICE, () -> current.get(0));

        service.analyzeSnapshot(List.of("topic1"), SnapshotConfig.latestN(10), null);
        current.set(0, second);
        service.analyzeSnapshot(List.of("topic1"), SnapshotConfig.latestN(10), null);

        verify(first, times(1)).generateWithMeta(anyString(), anyString(), any());
        verify(second, times(1)).generateWithMeta(anyString(), anyString(), any());
    }
}
