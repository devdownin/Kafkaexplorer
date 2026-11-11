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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The assistant's pipeline with a scripted model: what reaches the page in each case, and what
 * never does. Everything that does not need a real model is tested here, in {@code mvn verify}.
 */
class AssistantServiceTest {

    private static final String OK_ANSWER =
        "{\"status\":\"OK\",\"sql\":\"SELECT status FROM orders LIMIT 100\","
            + "\"explanation\":\"Lists statuses.\",\"missing\":null}";

    /** A model that answers from a script and remembers what it was asked. */
    private static final class ScriptedModel implements LlmClient {
        final Deque<Object> answers = new ArrayDeque<>();
        final List<String> prompts = new ArrayList<>();
        final List<String> systems = new ArrayList<>();

        @Override
        public String generate(String systemPrompt, String userPrompt) {
            systems.add(systemPrompt);
            prompts.add(userPrompt);
            Object next = answers.removeFirst();
            if (next instanceof RuntimeException e) throw e;
            return (String) next;
        }
    }

    private AssistantProperties properties;
    private ScriptedModel model;
    private AssistantContextService context;
    private AssistantSqlGate gate;
    private SimpleMeterRegistry meters;
    private AssistantService service;

    @BeforeEach
    void setUp() {
        ClaudeConfig claude = new ClaudeConfig();
        claude.setProvider(ClaudeConfig.Provider.OLLAMA);
        claude.setModel("gemma4:e2b");
        properties = new AssistantProperties();
        properties.setEnabled(true);
        model = new ScriptedModel();
        context = mock(AssistantContextService.class);
        when(context.build(any(), any(), any())).thenReturn(new AssistantContextService.Context(
            List.of(new AssistantContextService.TableContext("orders", Map.of("status", "STRING"), 0)),
            List.of("payments")));
        gate = mock(AssistantSqlGate.class);
        meters = new SimpleMeterRegistry();
        service = new AssistantService(properties, new AssistantLlm(claude, properties, c -> model),
            context, gate, meters);
    }

    private static AssistantSqlGate.Result verdict(AssistantSqlGate.Verdict v, String message, String sql) {
        return new AssistantSqlGate.Result(v, message, sql);
    }

    private static AssistantSqlRequest generate(String question) {
        return new AssistantSqlRequest("GENERATE", question, null, null, List.of());
    }

    @Test
    void anAcceptedProposalSaysSoAndNamesWhatItSaw() {
        model.answers.add(OK_ANSWER);
        when(gate.check(anyString(), anyList(), eq(false)))
            .thenReturn(verdict(AssistantSqlGate.Verdict.ACCEPTED, null, "SELECT status FROM orders LIMIT 100"));

        AssistantSqlResponse response = service.sql(generate("statuses of orders"));

        assertEquals("OK", response.status());
        assertTrue(response.validated());
        assertEquals("SELECT status FROM orders LIMIT 100", response.sql());
        assertEquals("Lists statuses.", response.explanation());
        assertEquals(List.of("orders"), response.tablesConsidered());
        assertEquals(List.of("payments"), response.tablesOmitted());
        assertEquals("gemma4:e2b", response.model());
        assertEquals(1.0, meters.counter("explorer_assistant_requests", "mode", "generate", "outcome", "validated").count());
        assertTrue(model.prompts.get(0).contains("table `orders`"), model.prompts.get(0));
    }

    @Test
    void thePlannersSentenceGoesBackOnceAndARepairIsCounted() {
        model.answers.add(OK_ANSWER);
        model.answers.add("{\"status\":\"OK\",\"sql\":\"SELECT `status` FROM orders\",\"explanation\":\"Fixed.\",\"missing\":null}");
        when(gate.check(anyString(), anyList(), eq(false)))
            .thenReturn(verdict(AssistantSqlGate.Verdict.REPAIRABLE, "Column 'stat' not found", "SELECT stat FROM orders"))
            .thenReturn(verdict(AssistantSqlGate.Verdict.ACCEPTED, null, "SELECT `status` FROM orders"));

        AssistantSqlResponse response = service.sql(generate("statuses"));

        assertTrue(response.validated());
        assertEquals(1, response.repairRounds());
        assertEquals("SELECT `status` FROM orders", response.sql());
        assertEquals("Fixed.", response.explanation());
        assertTrue(model.prompts.get(1).contains("Column 'stat' not found"), model.prompts.get(1));
        assertEquals(1.0, meters.counter("explorer_assistant_requests", "mode", "generate", "outcome", "repaired").count());
    }

    @Test
    void costsThatWereNotReportedAreSummedWithoutFailing() {
        // A local model reports tokens and no cost: the shape every repair round summed on Ollama.
        com.compagnonsdudev.kafkasqlexplorer.domain.LlmUsage local =
            new com.compagnonsdudev.kafkasqlexplorer.domain.LlmUsage(100L, 20L, null, null, null, 1000L, "Ollama", "m");
        com.compagnonsdudev.kafkasqlexplorer.domain.LlmUsage sum = AssistantService.sum(local, local);

        assertNull(sum.costUsd(), "no cost reported is still no cost, not zero");
        assertEquals(200L, sum.inputTokens());
        assertEquals(2000L, sum.durationMs());
        assertEquals(0.3, AssistantService.sum(
            new com.compagnonsdudev.kafkasqlexplorer.domain.LlmUsage(1L, 1L, 0.1, null, null, 1L, "p", "m"),
            new com.compagnonsdudev.kafkasqlexplorer.domain.LlmUsage(1L, 1L, 0.2, null, null, 1L, "p", "m")).costUsd(), 1e-9);
    }

    @Test
    void aStatementStillRefusedIsShownWithTheRefusalNotWithout() {
        model.answers.add(OK_ANSWER);
        model.answers.add(OK_ANSWER);
        when(gate.check(anyString(), anyList(), eq(false)))
            .thenReturn(verdict(AssistantSqlGate.Verdict.REPAIRABLE, "Column 'stat' not found", "SELECT stat FROM orders"));

        AssistantSqlResponse response = service.sql(generate("statuses"));

        assertEquals("OK", response.status());
        assertFalse(response.validated());
        assertEquals("Column 'stat' not found", response.validationError());
        assertEquals(1, response.repairRounds(), "one round, not a loop");
        assertEquals(2, model.prompts.size());
    }

    @Test
    void aStatementThatIsNotAReadIsWithheld() {
        model.answers.add(OK_ANSWER);
        when(gate.check(anyString(), anyList(), eq(false)))
            .thenReturn(verdict(AssistantSqlGate.Verdict.FORBIDDEN, "Only a SELECT is accepted", "DROP TABLE orders"));

        AssistantSqlResponse response = service.sql(generate("drop it"));

        assertNull(response.sql(), "a statement that is not a read never reaches the page");
        assertEquals("OUT_OF_SCOPE", response.status());
        assertEquals(1, model.prompts.size(), "a forbidden statement is not repaired into an allowed one");
    }

    @Test
    void theModelsOwnClarificationPassesThroughWithoutTheGate() {
        model.answers.add("{\"status\":\"NEEDS_CLARIFICATION\",\"sql\":null,\"explanation\":\"\","
            + "\"missing\":\"No customer table is described.\"}");

        AssistantSqlResponse response = service.sql(generate("customers"));

        assertEquals("NEEDS_CLARIFICATION", response.status());
        assertEquals("No customer table is described.", response.missing());
        verifyNoInteractions(gate);
    }

    @Test
    void noTableMeansNoModelCall() {
        when(context.build(any(), any(), any())).thenReturn(new AssistantContextService.Context(List.of(), List.of()));

        AssistantSqlResponse response = service.sql(generate("anything"));

        assertEquals("NEEDS_CLARIFICATION", response.status());
        assertTrue(response.missing().contains("Name a table"), response.missing());
        assertTrue(model.prompts.isEmpty());
    }

    @Test
    void aFailedCallReportsTheRealReason() {
        model.answers.add(new IllegalStateException("connection refused: ollama:11434"));

        AssistantSqlResponse response = service.sql(generate("statuses"));

        assertEquals("ERROR", response.status());
        assertTrue(response.error().contains("connection refused"), response.error());
    }

    @Test
    void proseInsteadOfJsonIsAnError() {
        model.answers.add("Sure! Here is your query.");

        AssistantSqlResponse response = service.sql(generate("statuses"));

        assertEquals("ERROR", response.status());
        assertTrue(response.error().contains("JSON"), response.error());
    }

    @Test
    void offMeansNoCallAndSaysHowToTurnItOn() {
        properties.setEnabled(false);

        AssistantSqlResponse response = service.sql(generate("statuses"));

        assertEquals("DISABLED", response.status());
        assertTrue(response.error().contains("explorer.assistant.enabled"));
        assertTrue(model.prompts.isEmpty());
        verifyNoInteractions(context);
    }

    @Test
    void fixSendsTheStatementAndThePlannersMessage() {
        model.answers.add(OK_ANSWER);
        when(gate.check(anyString(), anyList(), eq(false)))
            .thenReturn(verdict(AssistantSqlGate.Verdict.ACCEPTED, null, "SELECT status FROM orders LIMIT 100"));

        service.sql(new AssistantSqlRequest("FIX", null, "SELECT status, FROM orders",
            "Encountered \"FROM\" at line 1, column 16", null));

        assertTrue(model.prompts.get(0).contains("SELECT status, FROM orders"));
        assertTrue(model.prompts.get(0).contains("line 1, column 16"));
        verify(context).build(isNull(), eq("SELECT status, FROM orders"), isNull());
    }

    @Test
    void malformedRequestsAreRefusedByName() {
        assertThrows(IllegalArgumentException.class, () -> service.sql(generate(" ")));
        assertThrows(IllegalArgumentException.class,
            () -> service.sql(new AssistantSqlRequest("FIX", null, "SELECT 1", null, null)));
        IllegalArgumentException mode = assertThrows(IllegalArgumentException.class,
            () -> service.sql(new AssistantSqlRequest("EXECUTE", "q", null, null, null)));
        assertTrue(mode.getMessage().contains("GENERATE or FIX"));
    }

    @Test
    void aMetricDraftIsGatedAsAMetricAndItsTypeNormalised() {
        model.answers.add("{\"status\":\"OK\",\"name\":\"orders_per_minute\",\"type\":\"counter\","
            + "\"sql\":\"SELECT COUNT(*) AS metric_value FROM orders\",\"description\":\"Orders.\","
            + "\"explanation\":\"Counts.\",\"missing\":null}");
        when(gate.check(anyString(), anyList(), eq(true)))
            .thenReturn(verdict(AssistantSqlGate.Verdict.ACCEPTED, null, "SELECT COUNT(*) AS metric_value FROM orders"));

        AssistantMetricResponse response = service.metric(new AssistantMetricRequest("orders per minute", "demo.orders"));

        assertEquals("COUNTER", response.type());
        assertEquals("orders_per_minute", response.name());
        assertEquals("Orders.", response.description());
        assertTrue(response.proposal().validated());
        assertTrue(model.systems.get(0).contains("metric_value"));
        verify(context).build(eq("orders per minute"), isNull(), eq(List.of("demo.orders")));
    }

    @Test
    void anUnknownMetricTypeFallsBackToGauge() {
        model.answers.add("{\"status\":\"OK\",\"name\":\"x\",\"type\":\"RATE\","
            + "\"sql\":\"SELECT 1 AS metric_value FROM orders\",\"description\":null,"
            + "\"explanation\":\"x\",\"missing\":null}");
        when(gate.check(anyString(), anyList(), eq(true)))
            .thenReturn(verdict(AssistantSqlGate.Verdict.ACCEPTED, null, "SELECT 1 AS metric_value FROM orders"));

        assertEquals("GAUGE", service.metric(new AssistantMetricRequest("x", null)).type());
    }

    @Test
    void statusNamesTheEffectiveModelAndWhetherItIsLocal() {
        AssistantStatus status = service.status();

        assertTrue(status.enabled());
        assertNull(status.problem());
        assertEquals("Ollama", status.provider());
        assertEquals("gemma4:e2b", status.model());
        assertTrue(status.localDeployment(), "localhost:11434 is a loopback address");
        assertEquals("http://localhost:11434/v1", status.baseUrl());
        assertEquals(60, status.requestTimeoutSeconds(), "inherited from claude.*");
        assertEquals(12_000, status.promptCharBudget(), "no window declared: the budget as configured");
        assertEquals(0, status.contextWindowTokens());
    }

    @Test
    void aDeclaredWindowCapsTheAssistantsBudget() {
        ClaudeConfig claude = new ClaudeConfig();
        claude.setProvider(ClaudeConfig.Provider.OLLAMA);
        claude.setMaxTokens(4096);
        claude.setContextWindowTokens(6_000);
        AssistantService capped = new AssistantService(properties,
            new AssistantLlm(claude, properties, c -> model), context, gate, meters);

        // (6 000 - 4 096) x 3 = 5 712 characters, under the 12 000 configured.
        assertEquals(5_712, capped.status().promptCharBudget());
        assertEquals(5_712, capped.effectivePromptCharBudget());
    }

    @Test
    void theTestCallsTheAssistantsOwnModel() {
        model.answers.add("OK");

        com.compagnonsdudev.kafkasqlexplorer.domain.LlmTestResponse result = service.test();

        assertTrue(result.ok(), result.message());
        assertEquals("gemma4:e2b", result.model());
        assertTrue(result.message().contains("Sample reply: OK"), result.message());
        assertEquals(List.of("Reply with the word OK."), model.prompts);
    }

    @Test
    void theTestReportsAFailedCallAsAFailure() {
        model.answers.add(new IllegalStateException("connection refused"));

        com.compagnonsdudev.kafkasqlexplorer.domain.LlmTestResponse result = service.test();

        assertFalse(result.ok());
        assertTrue(result.message().contains("connection refused"), result.message());
    }
}
