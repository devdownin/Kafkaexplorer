// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.ProcessMiningConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.FieldMapping;
import com.compagnonsdudev.kafkasqlexplorer.domain.KafkaMessage;
import com.compagnonsdudev.kafkasqlexplorer.domain.PayloadDigest;
import com.compagnonsdudev.kafkasqlexplorer.domain.LlmResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.ProcessMiningCoverage;
import com.compagnonsdudev.kafkasqlexplorer.domain.ProcessMiningResult;
import com.compagnonsdudev.kafkasqlexplorer.domain.RagSource;
import com.compagnonsdudev.kafkasqlexplorer.domain.SnapshotConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.SnapshotRead;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicCoverage;
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

    /**
     * Stubs the snapshot read with these digests, as a read that ran to completion.
     *
     * <p>The service asks for {@link com.compagnonsdudev.kafkasqlexplorer.domain.SnapshotRead}
     * rather than a bare list, because a short list has three meanings and the analysis has to be
     * able to state which one it got.
     */
    private void givenDigests(List<PayloadDigest> digests) {
        when(snapshotReader.readSnapshot(anyList(), any(), any(), anyInt()))
            .thenReturn(SnapshotRead.of(digests));
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
        givenDigests(List.of(
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
        givenDigests(List.of(
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
        givenDigests(List.of(
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
        givenDigests(List.of(
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
        givenDigests(List.of(
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
        givenDigests(List.of(
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
        givenDigests(List.of(
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
        givenDigests(List.of(
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
        givenDigests(List.of(
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
        givenDigests(List.of(
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
        givenDigests(List.of(
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
        givenDigests(List.of(
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

    // ────────────────────────────────────────────────────────────────────────────────────────
    // What the answer rests on.
    //
    // A flowchart and a list of anomalies cannot state their own scope, so the run has to. Every
    // case below is one where the answer used to look complete: topics read but never shown to the
    // model, topics that resolve to nothing on the cluster, a read that failed halfway. The
    // model's silence about such a topic then reads as a finding about it rather than as the
    // absence of a question.
    // ────────────────────────────────────────────────────────────────────────────────────────

    private static final String ANSWER =
        "{\"flowchart\":\"x\",\"comments\":\"c\",\"hypotheses\":[],\"blindSpots\":[],\"anomalies\":[]}";

    private void givenModelAnswers() {
        when(llmClient.generateWithMeta(anyString(), anyString(), any()))
            .thenReturn(new LlmResponse(ANSWER, List.of()));
    }

    @Test
    void coverageSaysWhatWasReadAndWhatReachedTheModel() {
        givenDigests(List.of(
            digestOf("topic1", "k1", "{\"val\":1}"),
            digestOf("topic1", "k2", "{\"val\":2}"),
            digestOf("topic2", "k3", "{\"val\":3}")
        ));
        givenModelAnswers();

        ProcessMiningResult result = llmAnalysisService.analyzeSnapshot(
            List.of("topic1", "topic2"), SnapshotConfig.latestN(10), null);

        ProcessMiningCoverage coverage = result.coverage();
        assertNotNull(coverage, "an analysis has to be able to say what it looked at");
        assertEquals(3, coverage.messagesRead());
        assertEquals(3, coverage.messagesAnalysed());
        assertEquals(2, coverage.topics().size());
        assertTrue(coverage.topics().stream().allMatch(TopicCoverage::readable));
        assertTrue(coverage.promptChars() > 0);
        assertEquals(new ProcessMiningConfig().getPromptCharBudget(), coverage.promptCharBudget());
    }

    /**
     * The case that motivated all of this: the prompt's character budget silently decides which
     * topics reach the model, and it was told only to the model. A topic read in full whose records
     * never made it in is invisible in the answer.
     */
    @Test
    void aTopicTheBudgetLeftOutIsCountedAsAnalysedZero() {
        // The floor is what makes this reachable at all: a per-topic share below 2 000 characters
        // is raised to it, so exhausting the global budget means one busy topic spending it.
        ProcessMiningConfig tight = new ProcessMiningConfig();
        tight.setPromptCharBudget(1);
        LlmAnalysisService service = new LlmAnalysisService(snapshotReader, claudeConfig,
            tight, DIGEST_SERVICE, () -> llmClient);

        List<PayloadDigest> digests = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            digests.add(digestOf("topic1", "k" + i,
                "{\"id\":\"ORD-" + i + "\",\"status\":\"RECEIVED\",\"amount\":" + i + "}"));
        }
        digests.add(digestOf("topic2", "k", "{\"id\":\"PAY-1\"}"));
        givenDigests(digests);
        givenModelAnswers();

        ProcessMiningResult result = service.analyzeSnapshot(
            List.of("topic1", "topic2"), SnapshotConfig.latestN(100), null);

        ProcessMiningCoverage coverage = result.coverage();
        assertEquals(41, coverage.messagesRead());
        assertTrue(coverage.messagesAnalysed() < coverage.messagesRead(),
            "the budget dropped messages, and the answer has to say so");
        TopicCoverage second = coverage.topics().stream()
            .filter(t -> t.topic().equals("topic2")).findFirst().orElseThrow();
        assertEquals(1, second.messagesRead());
        assertEquals(0, second.messagesAnalysed(),
            "a topic read but never shown to the model is the one this exists to name");
        assertTrue(second.readable(), "it resolved perfectly well — it just never reached the prompt");
    }

    /**
     * A read that came back with nothing is not a question worth asking. The model would be handed
     * a prompt of headings and would answer about it — inventing a plausible pipeline, or reporting
     * an empty cluster — and either way the operator pays for a call whose subject is the absence
     * of data.
     */
    @Test
    void anEmptyReadIsRefusedRatherThanSentToTheModel() {
        when(snapshotReader.readSnapshot(anyList(), any(), any(), anyInt()))
            .thenReturn(SnapshotRead.of(List.of()));

        ProcessMiningResult result = llmAnalysisService.analyzeSnapshot(
            List.of("topic1"), SnapshotConfig.latestN(10), null);

        verify(llmClient, never()).generateWithMeta(anyString(), anyString(), any());
        assertNotNull(result.error());
        assertTrue(result.error().contains("no message"), result.error());
        assertNotNull(result.coverage(), "a refusal states its scope like any other answer");
    }

    /** And it names which of the three empties it was: nothing there, or nothing resolvable. */
    @Test
    void anEmptyReadNamesTheTopicsThatCouldNotBeResolved() {
        when(snapshotReader.readSnapshot(anyList(), any(), any(), anyInt())).thenReturn(
            new SnapshotRead(List.of(), Map.of("typo", 0), List.of("typo"), null, false));

        ProcessMiningResult result = llmAnalysisService.analyzeSnapshot(
            List.of("typo"), SnapshotConfig.latestN(10), null);

        assertTrue(result.error().contains("typo"), result.error());
        assertTrue(result.error().contains("resolved"), result.error());
        assertFalse(result.coverage().topics().get(0).readable());
    }

    /** A read that broke says so, and the analysis of what did arrive is not passed off as whole. */
    @Test
    void aFailedReadTravelsOnTheCoverage() {
        when(snapshotReader.readSnapshot(anyList(), any(), any(), anyInt())).thenReturn(
            new SnapshotRead(List.of(digestOf("topic1", "k", "{\"val\":1}")),
                Map.of("topic1", 1), List.of(), "Broker not available", true));
        givenModelAnswers();

        ProcessMiningResult result = llmAnalysisService.analyzeSnapshot(
            List.of("topic1"), SnapshotConfig.latestN(10), null);

        assertEquals("Broker not available", result.coverage().readError());
        assertTrue(result.coverage().readTruncated());
        assertNull(result.error(), "the analysis itself succeeded — only its scope was reduced");
    }

    /**
     * The scope survives a failed analysis. A run that read four hundred messages and then lost the
     * model still knows what it read, and that is what sizes the next attempt.
     */
    @Test
    void aFailedAnalysisStillReportsWhatItHadRead() {
        givenDigests(List.of(digestOf("topic1", "k", "{\"val\":1}")));
        when(llmClient.generateWithMeta(anyString(), anyString(), any()))
            .thenThrow(new RuntimeException("connection refused"));

        ProcessMiningResult result = llmAnalysisService.analyzeSnapshot(
            List.of("topic1"), SnapshotConfig.latestN(10), null);

        assertNotNull(result.error());
        assertNotNull(result.coverage());
        assertEquals(1, result.coverage().messagesRead());
    }

    /** The live path reports its scope per window, so it carries none of this. */
    @Test
    void theLivePathCarriesNoCoverage() {
        givenModelAnswers();

        ProcessMiningResult result = llmAnalysisService.analyzeLive(
            List.of(new KafkaMessage("topic1", 0, 1L, 1000L, "k", "{\"val\":1}")), null, null);

        assertNull(result.coverage());
    }

    /**
     * A model cannot state its own bill or its own scope.
     *
     * <p>This record is what the model's JSON is parsed into, so a volunteered {@code usage} or
     * {@code coverage} would be bound like any other key — and on the live path, where nothing
     * overwrites them afterwards, believed. The two fields are measurements *about* the call, made
     * on this side of it.
     */
    @Test
    void theModelCannotSupplyItsOwnUsageOrCoverage() {
        when(llmClient.generateWithMeta(anyString(), anyString(), any())).thenReturn(new LlmResponse("""
            {"flowchart":"x","comments":"c","hypotheses":[],"blindSpots":[],"anomalies":[],
             "usage":{"inputTokens":1,"outputTokens":1,"costUsd":0.0,"durationMs":1,
                      "provider":"free","model":"free"},
             "coverage":{"topics":[],"messagesRead":9999,"messagesAnalysed":9999,"promptChars":0,
                         "promptCharBudget":0,"readTruncated":false,"readError":null,"warnings":[]}}
            """, List.of()));

        ProcessMiningResult result = llmAnalysisService.analyzeLive(
            List.of(new KafkaMessage("topic1", 0, 1L, 1000L, "k", "{\"val\":1}")), null, null);

        assertNull(result.usage(), "the client reported none, so none is what the caller gets");
        assertNull(result.coverage());
    }
}
