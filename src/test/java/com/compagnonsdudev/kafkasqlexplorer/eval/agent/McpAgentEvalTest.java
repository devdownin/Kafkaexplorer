// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval.agent;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * The MCP agent harness: one JUnit case per scenario, a real model in front of a real {@code /mcp}.
 *
 * <p>What it tests is the thing 1 442 unit tests cannot reach. {@code SPEC-MCP.md} §3(b) makes a
 * claim about what a <em>model concludes</em> — that an empty result carrying
 * {@code stopReason != EXHAUSTED} is not read as "it does not exist" — and a false conclusion is
 * only falsifiable by a model. A unit test can assert the field; it cannot assert that an agent
 * reading it does not answer "that order was never delivered".
 *
 * <h2>How to run it</h2>
 *
 * <pre>{@code
 * docker compose -f docker-compose.yml -f compose/mcp.yml up -d
 * ./setup-demo.sh localhost:9092
 *
 * CLAUDE_PROVIDER=ANTHROPIC ANTHROPIC_API_KEY=sk-ant-… \
 * AGENT_EVAL_MODEL=claude-opus-5 AGENT_EVAL_JUDGE_MODEL=claude-sonnet-5 \
 *   ./mvnw test -P mcp-agent-eval
 *
 * ./mvnw test -P mcp-agent-eval -Dagent.eval.scenario=kpi-no-invented-threshold
 * }</pre>
 *
 * <h2>It skips rather than fails, and says why</h2>
 *
 * <p>No key, no stack, no scenario: the case aborts with the sentence naming what to set. A suite
 * that goes red because the person running it has no API key is one people learn to ignore — the
 * rule {@code CLAUDE.md} already states for {@code llm-eval} — and a suite that skips without saying
 * why becomes the same thing a day later. <b>A skipped scenario is never counted as a passing
 * one</b>: {@link ScenarioReport} keeps the three outcomes apart, and the summary line says so in
 * words.
 *
 * <p>It is not in {@code ci.yml}. A scenario that calls a real model is not deterministic in the
 * sense a merge gate requires, and letting it guard a pull request would hand a third party's
 * weather a vote on a merge.
 */
@Tag("mcp-agent-eval")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpAgentEvalTest {

    private static final Path SCENARIOS = Path.of("src/test/resources/eval/agent");
    private static final Path REPOSITORY_ROOT = Path.of(".").toAbsolutePath().normalize();

    /**
     * Held on the instance so {@link #restoreTheStack()} can reach it.
     *
     * <p>§7: {@code serverConfig} is restored <b>even on failure</b>. A scenario that narrowed a
     * scope and died leaving it narrowed fails the next one for a reason that does not belong to it
     * — the fault the two {@code FlinkSqlService} suites already paid for and that earned them their
     * {@code @AfterEach}. Restoring from the summary case alone would not do it: a run interrupted,
     * or aborted before that case, would leave the override in place.
     */
    private StackReconfigurer stack;

    @AfterAll
    void restoreTheStack() {
        if (stack != null) {
            stack.close();
        }
    }

    @TestFactory
    Stream<DynamicTest> everyScenario() {
        List<AgentScenario> scenarios = ScenarioLoader.loadAll(SCENARIOS).stream()
                .filter(McpAgentEvalTest::selected)
                .toList();
        if (scenarios.isEmpty()) {
            return Stream.of(DynamicTest.dynamicTest("no scenario selected",
                    () -> abort("No scenario matched " + SCENARIOS
                            + (System.getProperty("agent.eval.scenario") == null
                            ? "" : " and -Dagent.eval.scenario"))));
        }

        AgentModels models = AgentModels.fromEnvironment();
        Optional<String> unconfigured = models.unconfigured();
        URI endpoint = URI.create(endpointUrl());
        int repeats = repeats();

        // One reconfigurer for the whole factory: scenarios sharing a serverConfig share a boot, and
        // the last one hands the stack back on the overlay's defaults.
        stack = new StackReconfigurer(REPOSITORY_ROOT, unconfigured.isEmpty());
        List<ScenarioReport> reports = new ArrayList<>();
        // One client per role for the whole factory: rebuilding them per attempt would open a
        // connection pool and a selector thread for every scenario.
        AgentModel agent = unconfigured.isEmpty() ? models.agent() : null;
        VerdictJudge judge = unconfigured.isEmpty() ? new VerdictJudge(models.judge()) : null;

        Stream<DynamicTest> cases = scenarios.stream().map(scenario ->
                DynamicTest.dynamicTest(scenario.id(), () -> {
                    if (unconfigured.isPresent()) {
                        ScenarioReport skipped = ScenarioReport.skipped(scenario, unconfigured.get());
                        reports.add(skipped);
                        abort(skipped.render());
                    }
                    ScenarioReport report = run(scenario, agent, judge, endpoint, repeats);
                    reports.add(report);
                    if (report.outcome() == ScenarioReport.Outcome.SKIPPED) {
                        abort(report.render());
                    }
                    if (report.outcome() == ScenarioReport.Outcome.FAILED) {
                        fail(report.render());
                    }
                    System.out.println(report.render());
                }));

        // The summary is a case of its own so it runs after the others and is visible whatever they
        // did — a total printed from an @AfterAll is swallowed by most reporters.
        String judgeCaveat = models.judgeIsTheAgent() && unconfigured.isEmpty()
                ? "\n  NOTE: the judge is the model under evaluation — set AGENT_EVAL_JUDGE_MODEL. "
                + "Judging with the model being graded is asking it whether it is pleased with itself."
                : "";
        Stream<DynamicTest> summary = Stream.of(DynamicTest.dynamicTest("summary",
                () -> System.out.println(ScenarioReport.renderSuite(reports) + judgeCaveat)));
        return Stream.concat(cases, summary);
    }

    private ScenarioReport run(AgentScenario scenario, AgentModel agent, VerdictJudge judge,
                               URI endpoint, int repeats) {
        try {
            stack.applyTo(scenario);
        } catch (RuntimeException e) {
            // A configuration that cannot be applied is a scenario that did not run. Reporting it
            // as failed would blame the agent for the harness's own inability to set the world up.
            return ScenarioReport.skipped(scenario, e.getMessage());
        }

        List<ScenarioReport.Attempt> attempts = new ArrayList<>();
        for (int attempt = 1; attempt <= repeats; attempt++) {
            try (McpHttpClient mcp = new McpHttpClient(endpoint, Duration.ofSeconds(20))) {
                mcp.initialize();
                AgentRunner.Session session = new AgentRunner(agent, mcp).run(scenario);
                attempts.add(new ScenarioReport.Attempt(attempt,
                        session.trace().failures(scenario),
                        judge.score(scenario.verdict(), session.answer()),
                        session.overrun(),
                        session.answer()));
            } catch (RuntimeException e) {
                // The endpoint is not answering: the surface was never bound, or the stack is not
                // up. That is not the agent failing the scenario, and `mcp-probe` is the one-shot
                // that tells the two apart before a run is spent finding out.
                return ScenarioReport.skipped(scenario,
                        "the MCP endpoint at " + endpoint + " did not answer (" + e.getMessage()
                                + "). Is the stack up with compose/mcp.yml? Try: docker compose "
                                + "-f docker-compose.yml -f compose/mcp.yml --profile probe run "
                                + "--rm mcp-probe");
            }
        }
        return ScenarioReport.of(scenario, attempts);
    }

    private static boolean selected(AgentScenario scenario) {
        String only = System.getProperty("agent.eval.scenario");
        return only == null || only.isBlank() || only.equals(scenario.id());
    }

    private static String endpointUrl() {
        String configured = System.getenv("AGENT_EVAL_MCP_URL");
        return configured == null || configured.isBlank() ? "http://localhost:8080/mcp" : configured;
    }

    /**
     * {@code AGENT_EVAL_REPEAT}: how many times each scenario runs.
     *
     * <p>One by default, and more is how §7's "non-determinism is measured, not ignored" is done:
     * every attempt is reported and <b>all of them must pass</b>, so three out of five is a failure
     * that says three out of five rather than a green run somebody got lucky on.
     */
    private static int repeats() {
        String configured = System.getenv("AGENT_EVAL_REPEAT");
        try {
            return configured == null || configured.isBlank()
                    ? 1 : Math.max(1, Integer.parseInt(configured.trim()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
