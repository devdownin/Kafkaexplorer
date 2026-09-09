// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * What one scenario produced, including when it produced nothing.
 *
 * <p>This record is where {@code SPECAGENT.md} §7 lives, and its rules are the ones that make a
 * harness worth trusting:
 *
 * <ul>
 *   <li><b>A skipped scenario is not a green one.</b> {@link Outcome#SKIPPED} is its own value and
 *       carries why. "18/18" over twelve that never ran is the false number under the true label
 *       this repository hunts everywhere else.</li>
 *   <li><b>Non-determinism is measured, not averaged away.</b> With {@code AGENT_EVAL_REPEAT} each
 *       scenario runs n times and the report carries every attempt. Three passes out of five is not
 *       a pass; it is information, and hiding it behind a lucky last run would be the lie the whole
 *       module fights. So {@link #outcome()} requires <b>every</b> attempt to pass.</li>
 *   <li><b>A failure keeps its material.</b> Each attempt keeps the agent's answer and the judge's
 *       reasons, because a red run whose evidence cannot be re-read is one people end up
 *       disabling.</li>
 * </ul>
 */
record ScenarioReport(String id,
                      String title,
                      String invariant,
                      String skipReason,
                      List<Attempt> attempts) {

    enum Outcome { PASSED, FAILED, SKIPPED }

    /**
     * One run of the scenario.
     *
     * @param number        1-based, so a repeat run reads as "attempt 2 of 5"
     * @param traceFailures verdict 1
     * @param judge         verdict 2
     * @param overrun       the bound that stopped the loop, or null. Its own field rather than a
     *                      trace failure because the trace records what was spent and the ceiling
     *                      assertion reads that — this says the run was cut short, which is why
     *                      the answer may be missing
     * @param answer        kept whatever the outcome
     */
    record Attempt(int number, List<String> traceFailures, JudgeVerdict judge,
                   String overrun, String answer) {

        Attempt {
            traceFailures = traceFailures == null ? List.of() : List.copyOf(traceFailures);
        }

        boolean passed() {
            return traceFailures.isEmpty() && overrun == null && judge.passed();
        }

        List<String> failures() {
            List<String> all = new ArrayList<>(traceFailures);
            if (overrun != null) {
                all.add(overrun);
            }
            all.addAll(judge.failures());
            return List.copyOf(all);
        }
    }

    ScenarioReport {
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
    }

    static ScenarioReport skipped(AgentScenario scenario, String reason) {
        return new ScenarioReport(scenario.id(), scenario.title(), scenario.invariant(),
                reason, List.of());
    }

    static ScenarioReport of(AgentScenario scenario, List<Attempt> attempts) {
        return new ScenarioReport(scenario.id(), scenario.title(), scenario.invariant(),
                null, attempts);
    }

    Outcome outcome() {
        if (skipReason != null) {
            return Outcome.SKIPPED;
        }
        // Every attempt, not a majority and not the last: see the class comment.
        return !attempts.isEmpty() && attempts.stream().allMatch(Attempt::passed)
                ? Outcome.PASSED : Outcome.FAILED;
    }

    int passes() {
        return (int) attempts.stream().filter(Attempt::passed).count();
    }

    /**
     * The report as a human reads it.
     *
     * <p>A green scenario prints one line; a red one prints the invariant it broke first, because
     * "which promise did this break" is the question a failure has to answer, and "assertion failed"
     * does not answer it.
     */
    String render() {
        StringBuilder text = new StringBuilder();
        switch (outcome()) {
            case SKIPPED -> text.append("SKIPPED  ").append(id).append(" — ").append(skipReason);
            case PASSED -> {
                text.append("PASSED   ").append(id);
                if (attempts.size() > 1) {
                    text.append("  (").append(attempts.size()).append("/").append(attempts.size())
                            .append(" attempts)");
                }
            }
            case FAILED -> {
                text.append("FAILED   ").append(id).append("  (").append(passes()).append('/')
                        .append(attempts.size()).append(" attempts passed)")
                        .append("\n  invariant: ").append(invariant.strip());
                for (Attempt attempt : attempts) {
                    if (attempt.passed()) {
                        continue;
                    }
                    text.append("\n  attempt ").append(attempt.number()).append(':');
                    for (String failure : attempt.failures()) {
                        text.append("\n    · ").append(failure);
                    }
                    text.append("\n    the agent answered: ")
                            .append(attempt.answer() == null || attempt.answer().isBlank()
                                    ? "(nothing)" : attempt.answer().strip());
                }
            }
        }
        return text.toString();
    }

    /**
     * The suite total, which never counts a skip as a pass.
     *
     * <p>It is a separate line rather than a ratio for that reason: {@code 6 passed, 0 failed,
     * 12 skipped} is honest where {@code 6/18} invites the reader to round it up.
     */
    static String renderSuite(List<ScenarioReport> reports) {
        long passed = reports.stream().filter(r -> r.outcome() == Outcome.PASSED).count();
        long failed = reports.stream().filter(r -> r.outcome() == Outcome.FAILED).count();
        long skipped = reports.stream().filter(r -> r.outcome() == Outcome.SKIPPED).count();
        return passed + " passed, " + failed + " failed, " + skipped + " skipped"
                + (skipped > 0 ? " — a skipped scenario is not a passing one" : "");
    }
}
