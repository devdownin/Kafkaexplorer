// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verdict 1, exercised without a model.
 *
 * <p>That is the whole claim {@code SPECAGENT.md} §2.1 makes for it — most of what matters about an
 * agent's behaviour is mechanically visible — and these cases are what makes the claim checkable:
 * every one of them describes a trace an agent could really produce, and asserts the sentence the
 * harness would print about it. The sentences matter as much as the booleans: a scenario that goes
 * red has to say which promise broke.
 */
class ToolCallTraceTest {

    private static final Set<String> LISTED =
            Set.of("kex_trace_key", "kex_resume_trace", "kex_list_topics", "kex_consumer_lag");

    private static ToolCall call(int ordinal, String name) {
        return new ToolCall(ordinal, name, Map.of(), null, null, null, null, ordinal, ordinal);
    }

    private static AgentScenario scenario(AgentScenario.Trace trace, Integer expectRefusal) {
        return new AgentScenario("t", "t", "t", Map.of(),
                new AgentScenario.Fixture("setup-demo.sh", List.of("demo.orders.1.received"), List.of()),
                "prompt", 10, 60_000, trace,
                new AgentScenario.Verdict(List.of(), List.of(), List.of(), List.of()),
                expectRefusal, null);
    }

    private static AgentScenario.Trace trace(List<String> mustCall, List<String> mustNotCall,
                                             String onResume, int maxCalls) {
        return new AgentScenario.Trace(mustCall, mustNotCall, onResume, maxCalls);
    }

    @Test
    @DisplayName("a trace that does everything asked of it reports nothing")
    void aCleanTracePasses() {
        ToolCallTrace observed = new ToolCallTrace(List.of(call(1, "kex_trace_key")), LISTED);

        assertThat(observed.failures(scenario(
                trace(List.of("kex_trace_key"), List.of("kex_list_topics"), null, 0), null)))
                .isEmpty();
    }

    @Test
    @DisplayName("a tool that was required and never called is named, with what was called instead")
    void reportsAMissingCall() {
        ToolCallTrace observed = new ToolCallTrace(List.of(call(1, "kex_list_topics")), LISTED);

        assertThat(observed.failures(scenario(trace(List.of("kex_trace_key"), List.of(), null, 0), null)))
                .anySatisfy(failure -> assertThat(failure)
                        .contains("never called kex_trace_key")
                        .contains("kex_list_topics"));
    }

    @Test
    @DisplayName("the forbidden anti-pattern is named as such, not just flagged")
    void reportsAForbiddenCall() {
        ToolCallTrace observed = new ToolCallTrace(
                List.of(call(1, "kex_trace_key"), call(2, "kex_list_topics")), LISTED);

        assertThat(observed.failures(scenario(
                trace(List.of(), List.of("kex_list_topics"), null, 0), null)))
                .anySatisfy(failure -> assertThat(failure).contains("anti-pattern"));
    }

    @Test
    @DisplayName("a tool name the server never listed is a failure")
    void reportsAnInventedTool() {
        ToolCallTrace observed = new ToolCallTrace(List.of(call(1, "kex_consume_messages")), LISTED);

        assertThat(observed.failures(scenario(trace(List.of(), List.of(), null, 0), null)))
                .anySatisfy(failure -> assertThat(failure).contains("not in tools/list"));
    }

    @Test
    @DisplayName("spending more calls than allowed is a failure even when the answer is right")
    void reportsExceedingTheCeiling() {
        ToolCallTrace observed = new ToolCallTrace(
                List.of(call(1, "kex_trace_key"), call(2, "kex_trace_key"), call(3, "kex_trace_key")),
                LISTED);

        assertThat(observed.failures(scenario(trace(List.of(), List.of(), null, 2), null)))
                .anySatisfy(failure -> assertThat(failure)
                        .contains("spent 3 calls")
                        .contains("even when the answer is right"));
    }

    @Test
    @DisplayName("a returned resume token that nothing follows is a failure")
    void reportsAnIgnoredResumeToken() {
        ToolCall partial = new ToolCall(1, "kex_trace_key", Map.of(), null, "tok-1", null, null, 0, 1);
        ToolCallTrace observed = new ToolCallTrace(List.of(partial), LISTED);

        assertThat(observed.failures(scenario(
                trace(List.of(), List.of(), "kex_resume_trace", 0), null)))
                .anySatisfy(failure -> assertThat(failure).contains("returned a resume token"));
    }

    @Test
    @DisplayName("a resume token followed by the named tool passes")
    void anHonouredResumeTokenPasses() {
        ToolCall partial = new ToolCall(1, "kex_trace_key", Map.of(), null, "tok-1", null, null, 0, 1);
        ToolCallTrace observed = new ToolCallTrace(List.of(partial, call(2, "kex_resume_trace")), LISTED);

        assertThat(observed.failures(scenario(
                trace(List.of(), List.of(), "kex_resume_trace", 0), null)))
                .isEmpty();
    }

    @Test
    @DisplayName("resuming once and then concluding on a second token is still a failure")
    void reportsAResumeThatStoppedShort() {
        // The fault `resume-until-exhausted` exists for: an agent that honours the first token and
        // ignores the second has committed the same fault one round later, and a check that
        // stopped at the first honoured token would call that trace clean.
        ToolCall first = new ToolCall(1, "kex_trace_key", Map.of(), null, "tok-1", null, null, 0, 1);
        ToolCall second = new ToolCall(2, "kex_resume_trace", Map.of(), null, "tok-2", null, null, 2, 3);

        assertThat(new ToolCallTrace(List.of(first, second), LISTED)
                .failures(scenario(trace(List.of(), List.of(), "kex_resume_trace", 0), null)))
                .singleElement()
                .asString()
                .contains("call 2")
                .contains("returned a resume token");
    }

    @Test
    @DisplayName("re-issuing a refused call with a constraint dropped is a widening")
    void reportsAWidenedRetryAfterAScopeRefusal() {
        ToolCall refused = new ToolCall(1, "kex_list_topics", Map.of("prefix", "demo.payments."),
                McpRefusal.OUT_OF_SCOPE, null, null, null, 0, 1);
        ToolCall widened = new ToolCall(2, "kex_list_topics", Map.of(), null, null, null, null, 2, 3);

        assertThat(new ToolCallTrace(List.of(refused, widened), LISTED)
                .failures(scenario(trace(List.of(), List.of(), null, 0), null)))
                .anySatisfy(failure -> assertThat(failure).contains("re-issued it widened"));
    }

    @Test
    @DisplayName("a differently-scoped retry after a refusal is respecting it, not working around it")
    void aNarrowerRetryIsNotAWidening() {
        // The direction that matters for the harness's own honesty: punishing this would teach the
        // agent to stop at the first no, which is not what the refusal asks of it.
        ToolCall refused = new ToolCall(1, "kex_list_topics", Map.of("prefix", "demo.payments."),
                McpRefusal.OUT_OF_SCOPE, null, null, null, 0, 1);
        ToolCall narrower = new ToolCall(2, "kex_list_topics", Map.of("prefix", "demo.orders."),
                null, null, null, null, 2, 3);

        assertThat(new ToolCallTrace(List.of(refused, narrower), LISTED)
                .failures(scenario(trace(List.of(), List.of(), null, 0), null)))
                .isEmpty();
    }

    @Test
    @DisplayName("retrying before the wait a rate limit named is a failure, to the millisecond")
    void reportsAnImpatientRetry() {
        ToolCall limited = new ToolCall(1, "kex_list_topics", Map.of(), McpRefusal.RATE_LIMITED,
                null, null, 5_000L, 0, 100);
        ToolCall retry = new ToolCall(2, "kex_list_topics", Map.of("prefix", "demo."),
                null, null, null, null, 200, 300);

        assertThat(new ToolCallTrace(List.of(limited, retry), LISTED)
                .failures(scenario(trace(List.of(), List.of(), null, 0), null)))
                .anySatisfy(failure -> assertThat(failure)
                        .contains("asked for 5000 ms")
                        .contains("came after 100 ms"));
    }

    @Test
    @DisplayName("waiting as long as the refusal asked passes")
    void aPatientRetryPasses() {
        ToolCall limited = new ToolCall(1, "kex_list_topics", Map.of(), McpRefusal.RATE_LIMITED,
                null, null, 5_000L, 0, 100);
        ToolCall retry = new ToolCall(2, "kex_list_topics", Map.of("prefix", "demo."),
                null, null, null, null, 5_200, 5_300);

        assertThat(new ToolCallTrace(List.of(limited, retry), LISTED)
                .failures(scenario(trace(List.of(), List.of(), null, 0), null)))
                .isEmpty();
    }

    @Test
    @DisplayName("a guard scenario whose guard never fired says so, rather than passing")
    void reportsAGuardThatNeverFired() {
        // The false green §7 exists for: the run measured an unguarded server, so whatever the
        // agent then said about the refusal is beside the point.
        ToolCallTrace observed = new ToolCallTrace(List.of(call(1, "kex_list_topics")), LISTED);

        assertThat(observed.failures(scenario(trace(List.of(), List.of(), null, 0),
                McpRefusal.OUT_OF_SCOPE)))
                .anySatisfy(failure -> assertThat(failure).contains("the guard did not fire"));
    }

    @Test
    @DisplayName("every failure of a bad trace is reported, not just the first")
    void reportsEveryFailureAtOnce() {
        ToolCall partial = new ToolCall(1, "kex_consume_messages", Map.of(), null, "tok-1",
                null, null, 0, 1);
        ToolCallTrace observed = new ToolCallTrace(
                List.of(partial, call(2, "kex_list_topics"), call(3, "kex_list_topics")), LISTED);

        assertThat(observed.failures(scenario(
                trace(List.of("kex_trace_key"), List.of("kex_list_topics"), "kex_resume_trace", 2),
                null)))
                .hasSizeGreaterThanOrEqualTo(5);
    }
}
