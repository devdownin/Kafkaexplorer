// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval.agent;

import java.util.List;
import java.util.Map;

/**
 * One scenario of the MCP agent harness, as read from {@code src/test/resources/eval/agent/*.yaml}.
 *
 * <p>Declarative on purpose, and the reason is in {@code SPECAGENT.md} §3: the harness holds no
 * scenario in code, so adding a case is a file rather than a recompilation. Every field here is a
 * field of that format; nothing is derived and nothing is defaulted silently — see
 * {@link ScenarioLoader}, which refuses a file it cannot resolve rather than filling a blank.
 *
 * <p><b>The two verdicts are separate record components because they are separate verdicts.</b>
 * {@link #trace()} is mechanical and deterministic — the sequence of tool calls, asserted by
 * {@link ToolCallTrace} without a model in the loop. {@link #verdict()} is judged by a second model
 * call on a grid of decisions. A scenario passes only if both pass, and folding them into one
 * structure would invite the harness to trade one against the other.
 *
 * @param id           identity, and the JUnit case name
 * @param title        one line, for the report
 * @param invariant    the honesty promise under test, quoted in the failure report: a red scenario
 *                     has to say which promise broke, not only that it is red
 * @param serverConfig {@code explorer.mcp.*} settings applied before the session and restored
 *                     after. Public settings only — a harness that reached into internal state
 *                     would be testing a server nobody deploys
 * @param fixture      what the cluster must contain, resolved against the seeder by
 *                     {@code docs/check-agent-scenarios.py}
 * @param prompt       the task, in the words an operator would use
 * @param maxToolCalls a hard bound; exceeding it fails the scenario rather than truncating it
 * @param budgetMs     the same, in time
 * @param expectRefusal the JSON-RPC code a guard scenario expects ({@code -32041}, {@code -32042},
 *                     {@code -32029}…), or null when the scenario expects the tools to run
 */
record AgentScenario(
        String id,
        String title,
        String invariant,
        Map<String, String> serverConfig,
        Fixture fixture,
        String prompt,
        int maxToolCalls,
        long budgetMs,
        Trace trace,
        Verdict verdict,
        Integer expectRefusal) {

    /**
     * What the scenario assumes the seeded cluster holds.
     *
     * <p>It exists to be resolved, not to be read by the runner: {@code check-agent-scenarios.py}
     * matches every topic and key here against {@code setup-demo.sh}, on the precedent
     * {@code check-eval-fixture.py} set — a fixture that has drifted from the dataset it names does
     * not fail, it evaluates the wrong thing, confidently.
     */
    record Fixture(String seeder, List<String> topics, List<String> keys) {
    }

    /**
     * Verdict 1 — assertions over the call sequence ({@code SPECAGENT.md} §2.1).
     *
     * @param mustCall    tools that have to appear
     * @param mustNotCall tools that must not — usually the hand-rolled anti-pattern this server
     *                    exists to replace
     * @param onResumeTokenReturned the tool that must follow a returned resume token, or null when
     *                    the scenario does not care. Parsed from the format's
     *                    {@code mustCall(kex_resume_trace)} spelling
     * @param maxCalls    a per-trace ceiling; {@code 0} means "use {@link #maxToolCalls()}"
     */
    record Trace(List<String> mustCall,
                 List<String> mustNotCall,
                 String onResumeTokenReturned,
                 int maxCalls) {
    }

    /**
     * Verdict 2 — the grid the judge scores ({@code SPECAGENT.md} §2.2).
     *
     * <p>Every entry is a <b>decision</b>, never a wording. The rule comes from this repository's
     * own precedent ({@code LlmAnalysisEvalTest}): a tighter assertion on a model's prose is a test
     * that fails on a paraphrase, which teaches nobody anything.
     *
     * <p>{@link #mustQualify()} and {@link #mustCite()} are what catch the right answer for the
     * wrong reason. An agent that answers correctly without citing the coverage it was served did
     * not read the coverage; it was lucky, and it will not be next time.
     */
    record Verdict(List<String> mustAssert,
                   List<String> mustNotAssert,
                   List<String> mustQualify,
                   List<String> mustCite) {

        /** True when the judge has nothing to score — a trace-only scenario. */
        boolean isEmpty() {
            return mustAssert.isEmpty() && mustNotAssert.isEmpty()
                    && mustQualify.isEmpty() && mustCite.isEmpty();
        }
    }

    /** The ceiling that actually applies to the call count: the trace's when it names one. */
    int effectiveMaxCalls() {
        return trace.maxCalls() > 0 ? trace.maxCalls() : maxToolCalls;
    }
}
