// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.config.ClaudeConfig;
import com.yourcompany.kafkasqlexplorer.domain.FieldMapping;
import com.yourcompany.kafkasqlexplorer.domain.KafkaMessage;
import com.yourcompany.kafkasqlexplorer.domain.LlmResponse;
import com.yourcompany.kafkasqlexplorer.domain.ProcessMiningResult;
import com.yourcompany.kafkasqlexplorer.domain.RagSource;
import com.yourcompany.kafkasqlexplorer.domain.SnapshotConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LlmAnalysisServiceTest {

    private KafkaSnapshotReader snapshotReader;
    private ClaudeConfig claudeConfig;
    private LlmClient llmClient;
    private LlmAnalysisService llmAnalysisService;

    @BeforeEach
    void setUp() {
        snapshotReader = mock(KafkaSnapshotReader.class);
        claudeConfig = new ClaudeConfig();
        claudeConfig.setApiKey("test-key");
        llmClient = mock(LlmClient.class);
        llmAnalysisService = new LlmAnalysisService(snapshotReader, claudeConfig, llmClient);
    }

    @Test
    void testAnalyzeSnapshotSuccess() {
        when(snapshotReader.read(anyList(), any())).thenReturn(List.of(
            new KafkaMessage("topic1", 0, 1L, 1000L, "key1", "{\"val\":1}")
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
        when(snapshotReader.read(anyList(), any())).thenReturn(List.of(
            new KafkaMessage("topic1", 0, 1L, 1000L, "key1", "{\"val\":1}")
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
        when(snapshotReader.read(anyList(), any())).thenReturn(List.of(
            new KafkaMessage("topic1", 0, 1L, 1000L, "key1", "{\"val\":1}")
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
        when(snapshotReader.read(anyList(), any())).thenReturn(List.of(
            new KafkaMessage("topic1", 0, 1L, 1000L, "key1", "{\"val\":1}")
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
    void testRagSourcesAreAttachedToResult() {
        when(snapshotReader.read(anyList(), any())).thenReturn(List.of(
            new KafkaMessage("topic1", 0, 1L, 1000L, "key1", "{\"val\":1}")
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
