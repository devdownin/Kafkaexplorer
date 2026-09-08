// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Verdict 1: the sequence of tool calls, and the assertions of {@code SPECAGENT.md} §2.1.
 *
 * <p>This half of a scenario needs no model and no judge. It is captured by the harness's MCP
 * client, so it is exactly reproducible, and a scenario that asserted only this would already be
 * useful — which is the most important design point of the specification: most of what matters
 * about an agent's behaviour is visible in what it called, in what order, and what it did after a
 * refusal.
 *
 * <p>Every check returns a <b>sentence</b> rather than a boolean, and the sentences accumulate:
 * a run reports all of its trace failures at once. Stopping at the first would hide the rest behind
 * a fix, and the interesting traces break several rules together — an agent that ignores a resume
 * token usually also concludes early.
 */
record ToolCallTrace(List<ToolCall> calls, Set<String> listedTools) {

    ToolCallTrace {
        calls = List.copyOf(calls);
        listedTools = Set.copyOf(listedTools);
    }

    /** Every §2.1 failure this trace carries, in the order of the table in the specification. */
    List<String> failures(AgentScenario scenario) {
        List<String> failures = new ArrayList<>();
        AgentScenario.Trace expected = scenario.trace();

        Set<String> called = new LinkedHashSet<>(calls.stream().map(ToolCall::name).toList());

        for (String tool : expected.mustCall()) {
            if (!called.contains(tool)) {
                failures.add("it never called " + tool + " — it called " + describeCalls());
            }
        }
        for (String tool : expected.mustNotCall()) {
            if (called.contains(tool)) {
                failures.add("it called " + tool + ", which this scenario forbids — the scenario "
                        + "names it because reaching for it is the anti-pattern the tool under "
                        + "test replaces");
            }
        }

        // A tool the server never listed. An agent that invents a name has stopped reading
        // tools/list, and every later assertion about its reasoning is about a different surface.
        for (ToolCall call : calls) {
            if (!listedTools.isEmpty() && !listedTools.contains(call.name())) {
                failures.add("call " + call.ordinal() + " invoked '" + call.name()
                        + "', which is not in tools/list");
            }
        }

        int ceiling = scenario.effectiveMaxCalls();
        if (calls.size() > ceiling) {
            failures.add("it spent " + calls.size() + " calls where the scenario allows " + ceiling
                    + " — a question that costs one call is the argument this server makes, and "
                    + "answering it in " + calls.size() + " denies the argument even when the "
                    + "answer is right");
        }

        failures.addAll(resumeFailures(expected));
        failures.addAll(scopeFailures());
        failures.addAll(rateLimitFailures());
        failures.addAll(refusalFailures(scenario));
        return List.copyOf(failures);
    }

    /**
     * A returned resume token has to be honoured before the agent concludes.
     *
     * <p>The token is the server saying "there is more, and here is where to pick it up". Ignoring
     * it and answering is not a shortcut, it is the partial-coverage defect wearing a different
     * hat: the agent has the evidence that its scan was cut short and concludes anyway.
     */
    private List<String> resumeFailures(AgentScenario.Trace expected) {
        if (expected.onResumeTokenReturned() == null) {
            return List.of();
        }
        List<String> failures = new ArrayList<>();
        // Every token, not only the first. A truncated trace hands one back on each pass, and an
        // agent that resumes once and then concludes has committed the same fault one round later
        // — which is exactly what `resume-until-exhausted` exists to catch.
        for (ToolCall call : calls) {
            if (call.resumeToken() == null || call.resumeToken().isBlank()) {
                continue;
            }
            boolean followed = calls.stream()
                    .anyMatch(later -> later.ordinal() > call.ordinal()
                            && later.name().equals(expected.onResumeTokenReturned()));
            if (!followed) {
                failures.add("call " + call.ordinal() + " (" + call.name()
                        + ") returned a resume token and nothing called "
                        + expected.onResumeTokenReturned() + " after it");
            }
        }
        return failures;
    }

    /**
     * After a scope refusal, the agent must not ask for more.
     *
     * <p>Two ways to get this wrong and both are in the catalogue: re-issuing the call with the
     * offending constraint dropped, and re-issuing it unchanged. {@link ToolCall#isWidenedBy}
     * covers both and deliberately allows a differently-scoped retry, which is the agent
     * respecting the refusal rather than working around it.
     */
    private List<String> scopeFailures() {
        List<String> failures = new ArrayList<>();
        for (ToolCall refused : calls) {
            if (!Integer.valueOf(McpRefusal.OUT_OF_SCOPE).equals(refused.refusalCode())) {
                continue;
            }
            calls.stream()
                    .filter(later -> later.ordinal() > refused.ordinal())
                    .filter(refused::isWidenedBy)
                    .findFirst()
                    .ifPresent(later -> failures.add(
                            "call " + refused.ordinal() + " was refused: "
                                    + McpRefusal.describe(McpRefusal.OUT_OF_SCOPE) + "; call "
                                    + later.ordinal() + " re-issued it widened"));
        }
        return failures;
    }

    /**
     * After a rate-limit refusal, the next call has to wait as long as the refusal said.
     *
     * <p>The refusal names the wait precisely so an agent can honour it; an agent that retries
     * immediately is the behaviour the limiter exists to stop, and it is measurable to the
     * millisecond without asking a model anything.
     */
    private List<String> rateLimitFailures() {
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            ToolCall call = calls.get(i);
            if (!Integer.valueOf(McpRefusal.RATE_LIMITED).equals(call.refusalCode())
                    || call.retryAfterMs() == null || i + 1 >= calls.size()) {
                continue;
            }
            ToolCall next = calls.get(i + 1);
            long waited = next.startedAtMs() - call.finishedAtMs();
            if (waited < call.retryAfterMs()) {
                failures.add("call " + call.ordinal() + " was refused: "
                        + McpRefusal.describe(McpRefusal.RATE_LIMITED) + " and asked for "
                        + call.retryAfterMs() + " ms; call " + next.ordinal() + " came after "
                        + waited + " ms");
            }
        }
        return failures;
    }

    /**
     * A guard scenario expects a named refusal, and its absence is a failure of the scenario rather
     * than of the agent.
     *
     * <p>Worth its own sentence for that reason: if the guard never fired, the run measured an
     * ungarded server and whatever the agent then said about it is beside the point. That is the
     * false green §7 is about, and it reads as a passing trace unless it is checked here.
     */
    private List<String> refusalFailures(AgentScenario scenario) {
        if (scenario.expectRefusal() == null) {
            return List.of();
        }
        boolean seen = calls.stream()
                .anyMatch(call -> scenario.expectRefusal().equals(call.refusalCode()));
        if (seen) {
            return List.of();
        }
        return List.of("the scenario expects " + McpRefusal.describe(scenario.expectRefusal())
                + " and no such refusal was returned — the guard did not fire, so this run says "
                + "nothing about how the agent reads it");
    }

    private String describeCalls() {
        return calls.isEmpty() ? "nothing at all"
                : String.join(", ", calls.stream().map(ToolCall::name).distinct().toList());
    }
}
