// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the loader refuses, which is most of what it is for.
 *
 * <p>A scenario file is the whole contract of a case: the harness holds nothing in code. So a file
 * that loads while meaning something other than its author wrote produces a run that reports green
 * having asserted less than it claims — the false green {@code SPECAGENT.md} §7 calls worse than no
 * harness at all. Every case below is one shape of that.
 */
class ScenarioLoaderTest {

    /** A complete, valid scenario. Cases below mutate one line of it at a time. */
    private static final String VALID = """
            id: sample
            title: "A sample"
            invariant: "an empty result means not found in what was read"
            serverConfig:
              explorer.mcp.hard-max-topics: 2
            fixture:
              seeder: setup-demo.sh
              requires:
                topics: ["demo.orders.1.received"]
                keys: ["ORD-101"]
            prompt: |
              Did ORD-101 get delivered?
            maxToolCalls: 6
            budgetMs: 60000
            trace:
              mustCall: ["kex_trace_key"]
              mustNotCall: ["kex_preview_messages"]
              onResumeTokenReturned: mustCall(kex_resume_trace)
              maxCalls: 4
            verdict:
              mustNotAssert:
                - "ORD-101 did not arrive"
              mustCite:
                - "a topic from coverage.topicsNotReached"
            """;

    private static AgentScenario parse(String yaml) {
        return ScenarioLoader.parse(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "sample.yaml");
    }

    @Test
    @DisplayName("a valid scenario loads with every field where the format puts it")
    void loadsAValidScenario() {
        AgentScenario scenario = parse(VALID);

        assertThat(scenario.id()).isEqualTo("sample");
        assertThat(scenario.serverConfig()).containsExactly(
                org.assertj.core.api.Assertions.entry("explorer.mcp.hard-max-topics", "2"));
        assertThat(scenario.fixture().topics()).containsExactly("demo.orders.1.received");
        assertThat(scenario.fixture().keys()).containsExactly("ORD-101");
        assertThat(scenario.prompt()).contains("ORD-101");
        assertThat(scenario.trace().mustCall()).containsExactly("kex_trace_key");
        assertThat(scenario.trace().onResumeTokenReturned()).isEqualTo("kex_resume_trace");
        assertThat(scenario.verdict().mustNotAssert()).hasSize(1);
        assertThat(scenario.verdict().mustAssert()).isEmpty();
        assertThat(scenario.expectRefusal()).isNull();
    }

    @Test
    @DisplayName("trace.maxCalls is the ceiling that applies when it is set")
    void theTighterCeilingWins() {
        assertThat(parse(VALID).effectiveMaxCalls()).isEqualTo(4);
        assertThat(parse(VALID.replace("  maxCalls: 4\n", "")).effectiveMaxCalls()).isEqualTo(6);
    }

    @Test
    @DisplayName("an unknown key is refused, not ignored")
    void refusesAnUnknownKey() {
        // The case this exists for: `mustNotCite` is not a key this harness has ever had, and a
        // file carrying it would otherwise load, run, and assert nothing under a name its author
        // believes asserts something.
        assertThatThrownBy(() -> parse(VALID.replace(
                "  mustCite:\n    - \"a topic from coverage.topicsNotReached\"",
                "  mustNotCite:\n    - \"a topic from coverage.topicsNotReached\"")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mustNotCite");
    }

    @Test
    @DisplayName("a duplicated key is refused rather than silently keeping the last one")
    void refusesADuplicatedKey() {
        assertThatThrownBy(() -> parse(VALID + "\nmaxToolCalls: 99\n"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("the id has to match the file name")
    void refusesAnIdThatDoesNotMatchItsFile() {
        assertThatThrownBy(() -> parse(VALID.replace("id: sample", "id: something-else")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match the file name");
    }

    @Test
    @DisplayName("serverConfig accepts explorer.mcp.* and nothing else")
    void refusesASettingOutsideTheModule() {
        assertThatThrownBy(() -> parse(VALID.replace(
                "explorer.mcp.hard-max-topics: 2", "explorer.audit-history-max-records: 2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("explorer.mcp.*");
    }

    @Test
    @DisplayName("onResumeTokenReturned takes the format's one spelling")
    void refusesAnUnparseableResumeRule() {
        assertThatThrownBy(() -> parse(VALID.replace(
                "onResumeTokenReturned: mustCall(kex_resume_trace)",
                "onResumeTokenReturned: kex_resume_trace")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mustCall(<tool>)");
    }

    @Test
    @DisplayName("a scenario that asserts nothing is refused")
    void refusesAScenarioThatMeasuresNothing() {
        String hollow = VALID
                .replace("  mustCall: [\"kex_trace_key\"]\n", "")
                .replaceAll("(?s)verdict:.*", "");
        assertThatThrownBy(() -> parse(hollow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("asserts nothing");
    }

    @Test
    @DisplayName("a tool in both mustCall and mustNotCall is refused")
    void refusesAContradiction() {
        assertThatThrownBy(() -> parse(VALID.replace(
                "mustNotCall: [\"kex_preview_messages\"]", "mustNotCall: [\"kex_trace_key\"]")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("both mustCall and mustNotCall");
    }

    @Test
    @DisplayName("a trace ceiling above maxToolCalls is refused as unreachable")
    void refusesACeilingThatCannotBind() {
        assertThatThrownBy(() -> parse(VALID.replace("maxCalls: 4", "maxCalls: 40")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds maxToolCalls");
    }

    @Test
    @DisplayName("a fixture that names neither a topic nor a key is refused")
    void refusesAnUnresolvableFixture() {
        String empty = VALID.replace(
                "  requires:\n    topics: [\"demo.orders.1.received\"]\n    keys: [\"ORD-101\"]\n",
                "  requires: {}\n");
        assertThatThrownBy(() -> parse(empty))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("neither a topic nor a key");
    }

    @Test
    @DisplayName("the shipped scenarios all load")
    void everyShippedScenarioLoads() {
        // Not a formality: these files are the harness's only content, and a broken one would
        // otherwise be found by the eval run that needs an API key — which is to say, rarely.
        List<AgentScenario> scenarios =
                ScenarioLoader.loadAll(Path.of("src/test/resources/eval/agent"));

        assertThat(scenarios).isNotEmpty();
        assertThat(scenarios).extracting(AgentScenario::id).doesNotHaveDuplicates();
        assertThat(scenarios).allSatisfy(scenario -> {
            assertThat(scenario.invariant()).isNotBlank();
            assertThat(scenario.prompt()).isNotBlank();
            assertThat(scenario.maxToolCalls()).isPositive();
        });
    }

    @Test
    @DisplayName("a directory with no scenarios is empty, not an error")
    void anAbsentDirectoryIsEmpty() {
        assertThat(ScenarioLoader.loadAll(Path.of("src/test/resources/eval/agent/nowhere")))
                .isEmpty();
    }
}
