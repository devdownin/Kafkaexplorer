// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code SPECAGENT.md} §7, made checkable.
 *
 * <p>Every case here is one way a harness can report a number that is false under a label that is
 * true — which is the failure this repository hunts everywhere else and would be at its most
 * embarrassing in the harness written to hunt it.
 */
class ScenarioReportTest {

    private static final AgentScenario SCENARIO = new AgentScenario(
            "trace-partial-coverage", "A partial scan does not read as it does not exist",
            "stopReason != EXHAUSTED means not found in what was read", Map.of(),
            new AgentScenario.Fixture("setup-demo.sh", List.of("demo.orders.1.received"), List.of()),
            "prompt", 6, 60_000,
            new AgentScenario.Trace(List.of(), List.of(), null, 0),
            new AgentScenario.Verdict(List.of(), List.of(), List.of(), List.of()),
            null);

    private static ScenarioReport.Attempt passing(int number) {
        return new ScenarioReport.Attempt(number, List.of(), JudgeVerdict.nothingToJudge(),
                null, "ORD-101 arrived.");
    }

    private static ScenarioReport.Attempt failing(int number) {
        return new ScenarioReport.Attempt(number,
                List.of("it never called kex_trace_key — it called nothing at all"),
                new JudgeVerdict(List.of(new JudgeVerdict.Finding(
                        "mustQualify", "that the scan was partial", false, "no reservation")), null),
                null, "ORD-101 never arrived.");
    }

    @Test
    @DisplayName("a skipped scenario is its own outcome, never a passing one")
    void aSkipIsNotAPass() {
        // "18/18" over twelve that never ran is the false number under the true label.
        ScenarioReport report = ScenarioReport.skipped(SCENARIO, "no API key: set OPENROUTER_API_KEY");

        assertThat(report.outcome()).isEqualTo(ScenarioReport.Outcome.SKIPPED);
        assertThat(report.render()).startsWith("SKIPPED").contains("OPENROUTER_API_KEY");
    }

    @Test
    @DisplayName("a scenario with no attempt at all is not a pass either")
    void noAttemptIsNotAPass() {
        assertThat(ScenarioReport.of(SCENARIO, List.of()).outcome())
                .isEqualTo(ScenarioReport.Outcome.FAILED);
    }

    @Test
    @DisplayName("every attempt has to pass — three out of five is not green")
    void everyAttemptCounts() {
        // Hiding a rate behind a lucky last run is the lie the whole module fights.
        ScenarioReport report = ScenarioReport.of(SCENARIO,
                List.of(passing(1), passing(2), failing(3), passing(4), passing(5)));

        assertThat(report.outcome()).isEqualTo(ScenarioReport.Outcome.FAILED);
        assertThat(report.passes()).isEqualTo(4);
        assertThat(report.render()).contains("4/5 attempts passed");
    }

    @Test
    @DisplayName("a failure leads with the invariant it broke")
    void afailureNamesThePromise() {
        // "Assertion failed" does not answer the question a red run has to answer.
        String rendered = ScenarioReport.of(SCENARIO, List.of(failing(1))).render();

        assertThat(rendered).startsWith("FAILED")
                .contains("invariant: stopReason != EXHAUSTED")
                .contains("it never called kex_trace_key")
                .contains("mustQualify")
                .contains("the agent answered: ORD-101 never arrived.");
    }

    @Test
    @DisplayName("an overrun is reported as the bound it hit, beside the answer it cut short")
    void reportsAnOverrun() {
        ScenarioReport report = ScenarioReport.of(SCENARIO, List.of(new ScenarioReport.Attempt(
                1, List.of(), JudgeVerdict.nothingToJudge(),
                "it asked for more than the 6 tool calls this scenario allows", "")));

        assertThat(report.outcome()).isEqualTo(ScenarioReport.Outcome.FAILED);
        assertThat(report.render())
                .contains("more than the 6 tool calls")
                .contains("the agent answered: (nothing)");
    }

    @Test
    @DisplayName("a grid that could not be scored fails, and says it was not scored")
    void anUnscoredGridFails() {
        ScenarioReport report = ScenarioReport.of(SCENARIO, List.of(new ScenarioReport.Attempt(
                1, List.of(), JudgeVerdict.notJudged("the judge did not answer JSON"),
                null, "an answer")));

        assertThat(report.outcome()).isEqualTo(ScenarioReport.Outcome.FAILED);
        assertThat(report.render()).contains("the grid could not be scored");
    }

    @Test
    @DisplayName("a green scenario is one line, and says how many attempts it survived")
    void aPassIsOneLine() {
        assertThat(ScenarioReport.of(SCENARIO, List.of(passing(1))).render())
                .isEqualTo("PASSED   trace-partial-coverage");
        assertThat(ScenarioReport.of(SCENARIO, List.of(passing(1), passing(2), passing(3))).render())
                .contains("(3/3 attempts)");
    }

    @Test
    @DisplayName("the suite total counts the three outcomes apart and says a skip is not a pass")
    void theTotalNeverRoundsUp() {
        String total = ScenarioReport.renderSuite(List.of(
                ScenarioReport.of(SCENARIO, List.of(passing(1))),
                ScenarioReport.of(SCENARIO, List.of(failing(1))),
                ScenarioReport.skipped(SCENARIO, "no stack")));

        assertThat(total).isEqualTo(
                "1 passed, 1 failed, 1 skipped — a skipped scenario is not a passing one");
    }

    @Test
    @DisplayName("with nothing skipped the total stops explaining itself")
    void theCaveatOnlyAppearsWhenItApplies() {
        assertThat(ScenarioReport.renderSuite(List.of(ScenarioReport.of(SCENARIO, List.of(passing(1))))))
                .isEqualTo("1 passed, 0 failed, 0 skipped");
    }
}
