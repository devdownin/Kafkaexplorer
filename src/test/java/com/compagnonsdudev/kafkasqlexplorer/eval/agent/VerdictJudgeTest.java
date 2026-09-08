// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the judge is shown, and what the harness does with what comes back.
 *
 * <p>Both halves matter and the first is the one nothing else would catch. The judge must see the
 * answer and the grid and <b>nothing else</b>: shown the trace it would grade the approach, which
 * the trace already asserts exactly, and two measurements of one thing do not make a better one —
 * they make one that can disagree with itself; shown the invariant it would be told the right
 * answer and asked whether the agent found it.
 *
 * <p>The second half is the harness's own honesty. A reply that does not parse, grades the wrong
 * number of requirements, or renumbers them is <b>not judged</b> — never partially believed.
 * Filling gaps with "met" passes a scenario nobody scored; filling them with "not met" fails an
 * agent for the judge's mistake. Both are the false verdict, in opposite directions.
 */
class VerdictJudgeTest {

    private static final AgentScenario.Verdict GRID = new AgentScenario.Verdict(
            List.of("ORD-101 reached delivery"),
            List.of("ORD-101 does not exist"),
            List.of("that the scan was partial"),
            List.of("a topic from coverage.topicsNotReached"));

    /** A judge that plays a fixed reply and records the prompt it was given. */
    private record ScriptedJudge(String reply, AtomicReference<String> seenSystem,
                                 AtomicReference<String> seenUser) implements AgentModel {

        @Override
        public Turn respond(String systemPrompt, List<Exchange> transcript,
                            List<McpHttpClient.ToolSpec> tools) {
            seenSystem.set(systemPrompt);
            seenUser.set(((Exchange.User) transcript.get(0)).text());
            return new Turn(reply, List.of());
        }

        @Override
        public String describe() {
            return "scripted-judge";
        }
    }

    private static ScriptedJudge judge(String reply) {
        return new ScriptedJudge(reply, new AtomicReference<>(), new AtomicReference<>());
    }

    private static String allMet(int count) {
        StringBuilder json = new StringBuilder("{\"findings\":[");
        for (int i = 0; i < count; i++) {
            json.append(i > 0 ? "," : "")
                    .append("{\"index\":").append(i).append(",\"met\":true,\"why\":\"it does\"}");
        }
        return json.append("]}").toString();
    }

    @Test
    @DisplayName("a grid the judge met entirely passes, with every reason kept")
    void passesAMetGrid() {
        JudgeVerdict verdict = new VerdictJudge(judge(allMet(4))).score(GRID, "the answer");

        assertThat(verdict.passed()).isTrue();
        assertThat(verdict.findings()).hasSize(4)
                .allSatisfy(finding -> assertThat(finding.why()).isNotBlank());
        assertThat(verdict.findings()).extracting(JudgeVerdict.Finding::requirement)
                .containsExactly("mustAssert", "mustNotAssert", "mustQualify", "mustCite");
    }

    @Test
    @DisplayName("an unmet requirement names which one and quotes what was asked")
    void reportsAnUnmetRequirement() {
        JudgeVerdict verdict = new VerdictJudge(judge("""
                {"findings":[
                  {"index":0,"met":true,"why":"stated"},
                  {"index":1,"met":true,"why":"absent"},
                  {"index":2,"met":false,"why":"no reservation anywhere in the answer"},
                  {"index":3,"met":true,"why":"names demo.orders.4"}]}""")).score(GRID, "answer");

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.failures()).singleElement().asString()
                .contains("mustQualify")
                .contains("that the scan was partial")
                .contains("no reservation anywhere");
    }

    @Test
    @DisplayName("the judge sees the answer and the grid, and nothing else")
    void showsTheJudgeNothingItShouldNotSee() {
        ScriptedJudge scripted = judge(allMet(4));
        new VerdictJudge(scripted).score(GRID, "ORD-101 arrived, though six topics went unread.");

        String shown = scripted.seenUser().get();
        assertThat(shown)
                .contains("mustQualify")
                .contains("ORD-101 arrived, though six topics went unread.");
        // Nothing of the trace, the scenario id, its title or its invariant reaches the judge.
        assertThat(shown).doesNotContain("kex_").doesNotContain("stopReason").doesNotContain("trace");
        assertThat(scripted.seenSystem().get()).contains("DECISIONS, never wording");
    }

    @Test
    @DisplayName("a grid with nothing in it is not a grid the judge is asked about")
    void skipsAnEmptyGrid() {
        AgentScenario.Verdict empty =
                new AgentScenario.Verdict(List.of(), List.of(), List.of(), List.of());
        ScriptedJudge scripted = judge("should not be called");

        assertThat(new VerdictJudge(scripted).score(empty, "answer").passed()).isTrue();
        assertThat(scripted.seenUser().get()).isNull();
    }

    @Test
    @DisplayName("an agent that said nothing is not judged, rather than passing every mustNotAssert")
    void refusesToGradeAnEmptyAnswer() {
        // Scoring silence as "made none of the forbidden claims" would pass every mustNotAssert for
        // an agent that produced no answer at all.
        JudgeVerdict verdict = new VerdictJudge(judge(allMet(4))).score(GRID, "   ");

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.notJudged()).contains("no final answer");
    }

    @Test
    @DisplayName("a reply that is not JSON is not judged, and quotes what came back")
    void refusesUnparseableJson() {
        JudgeVerdict verdict = new VerdictJudge(judge("I think it did fine, honestly."))
                .score(GRID, "answer");

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.notJudged()).contains("did not answer JSON").contains("honestly");
    }

    @Test
    @DisplayName("JSON wrapped in a fence or a sentence is still read")
    void toleratesAFencedReply() {
        JudgeVerdict verdict = new VerdictJudge(judge("Here you go:\n```json\n" + allMet(4) + "\n```"))
                .score(GRID, "answer");

        assertThat(verdict.passed()).isTrue();
    }

    @Test
    @DisplayName("a partial grading is not judged, rather than filled in")
    void refusesAPartialGrading() {
        JudgeVerdict verdict = new VerdictJudge(judge(allMet(2))).score(GRID, "answer");

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.notJudged()).contains("graded 2 requirement(s) where 4 were asked");
    }

    @Test
    @DisplayName("findings are attached by index, so a renumbered reply is refused")
    void refusesARenumberedReply() {
        // Attaching by arrival order would silently pin grades to the wrong requirements, which is
        // a wrong verdict that reads exactly like a right one.
        JudgeVerdict verdict = new VerdictJudge(judge("""
                {"findings":[
                  {"index":0,"met":true,"why":"a"},{"index":0,"met":true,"why":"b"},
                  {"index":2,"met":true,"why":"c"},{"index":3,"met":true,"why":"d"}]}"""))
                .score(GRID, "answer");

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.notJudged()).contains("same requirement twice");
    }

    @Test
    @DisplayName("a judge call that throws is not judged, and says so")
    void survivesAJudgeThatFails() {
        AgentModel broken = new AgentModel() {
            @Override
            public Turn respond(String s, List<Exchange> t, List<McpHttpClient.ToolSpec> tools) {
                throw new IllegalStateException("the model endpoint answered HTTP 500");
            }

            @Override
            public String describe() {
                return "broken";
            }
        };

        JudgeVerdict verdict = new VerdictJudge(broken).score(GRID, "answer");

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.notJudged()).contains("the judge call failed").contains("HTTP 500");
    }
}
