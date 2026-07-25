// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.config.ClaudeConfig;
import com.yourcompany.kafkasqlexplorer.config.ProcessMiningConfig;
import com.yourcompany.kafkasqlexplorer.domain.FieldMapping;
import com.yourcompany.kafkasqlexplorer.domain.KafkaMessage;
import com.yourcompany.kafkasqlexplorer.domain.PayloadDigest;
import com.yourcompany.kafkasqlexplorer.domain.LlmResponse;
import com.yourcompany.kafkasqlexplorer.domain.ProcessMiningResult;
import com.yourcompany.kafkasqlexplorer.domain.RagSource;
import com.yourcompany.kafkasqlexplorer.domain.SnapshotConfig;
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
            new ProcessMiningConfig(), DIGEST_SERVICE, llmClient);
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
        when(llmClient.generateWithMeta(anyString(), anyString()))
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
        when(llmClient.generateWithMeta(anyString(), anyString()))
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
        when(llmClient.generateWithMeta(anyString(), anyString())).thenReturn(new LlmResponse(
            "{\"flowchart\":\"x\",\"comments\":\"\",\"hypotheses\":[],\"blindSpots\":[],\"anomalies\":[]}",
            List.of()));

        String focus = "- [Duplicates] Détecte les messages dupliqués.";
        llmAnalysisService.analyzeSnapshot(List.of("topic1"), SnapshotConfig.latestN(10), null, focus);

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(llmClient).generateWithMeta(anyString(), userPrompt.capture());
        assertTrue(userPrompt.getValue().contains("AUDIT CIBLÉ"));
        assertTrue(userPrompt.getValue().contains("Détecte les messages dupliqués."));
    }

    @Test
    void testNoAuditSectionWhenFocusAbsent() {
        when(snapshotReader.readDigested(anyList(), any(), any(), anyInt())).thenReturn(List.of(
            digestOf("topic1", "key1", "{\"val\":1}")
        ));
        when(llmClient.generateWithMeta(anyString(), anyString())).thenReturn(new LlmResponse(
            "{\"flowchart\":\"x\",\"comments\":\"\",\"hypotheses\":[],\"blindSpots\":[],\"anomalies\":[]}",
            List.of()));

        llmAnalysisService.analyzeSnapshot(List.of("topic1"), SnapshotConfig.latestN(10), null);

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(llmClient).generateWithMeta(anyString(), userPrompt.capture());
        assertFalse(userPrompt.getValue().contains("AUDIT CIBLÉ"));
    }

    @Test
    void testAnalyzeSnapshotParsesJsonWrappedInMarkdown() {
        when(snapshotReader.readDigested(anyList(), any(), any(), anyInt())).thenReturn(List.of(
            digestOf("topic1", "key1", "{\"val\":1}")
        ));

        when(llmClient.generateWithMeta(anyString(), anyString())).thenReturn(new LlmResponse("""
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
        when(llmClient.generateWithMeta(anyString(), anyString())).thenReturn(new LlmResponse(
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
        verify(llmClient).generateWithMeta(anyString(), prompt.capture());
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
        when(llmClient.generateWithMeta(anyString(), anyString())).thenReturn(new LlmResponse(
            "{\"flowchart\":\"x\",\"comments\":\"\",\"hypotheses\":[],\"blindSpots\":[],\"anomalies\":[]}",
            List.of(new RagSource("cited passage", "spec.pdf", 0.9))));

        ProcessMiningResult result = llmAnalysisService.analyzeSnapshot(
            List.of("topic1"), SnapshotConfig.latestN(10), null);

        assertEquals(1, result.ragSources().size());
        assertEquals("spec.pdf", result.ragSources().get(0).sourceFile());
    }
}
