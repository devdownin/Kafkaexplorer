// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How a scenario's {@code serverConfig} reaches the container, and what happens when it cannot.
 *
 * <p>Docker is never run here: what is under test is the mapping and the refusal, and both are
 * decidable from {@code compose/mcp.yml} and a scenario. The refusal is the case that earns this
 * class — a setting the overlay does not publish leaves the container on its default while the
 * scenario believes it changed it, so the run measures the wrong world and reports about it with
 * confidence.
 */
class StackReconfigurerTest {

    private static final Path ROOT = Path.of(".").toAbsolutePath().normalize();

    private static AgentScenario scenarioWith(Map<String, String> serverConfig) {
        return new AgentScenario("s", "t", "i", serverConfig,
                new AgentScenario.Fixture("setup-demo.sh", List.of("demo.orders.1.received"), List.of()),
                "prompt", 4, 60_000,
                new AgentScenario.Trace(List.of(), List.of(), null, 0),
                new AgentScenario.Verdict(List.of(), List.of(), List.of(), List.of()), null, null);
    }

    @Test
    @DisplayName("a property becomes the one environment variable Spring binds it from")
    void mapsAPropertyToItsVariable() {
        assertThat(StackReconfigurer.variableFor("explorer.mcp.hard-max-topics"))
                .isEqualTo("EXPLORER_MCP_HARD_MAX_TOPICS");
        assertThat(StackReconfigurer.variableFor("explorer.mcp.rate-limit.calls-per-minute"))
                .isEqualTo("EXPLORER_MCP_RATE_LIMIT_CALLS_PER_MINUTE");
    }

    @Test
    @DisplayName("the settings the shipped scenarios move are published by the overlay")
    void resolvesTheShippedSettings() {
        StackReconfigurer stack = new StackReconfigurer(ROOT, false);

        assertThat(stack.resolve(scenarioWith(Map.of(
                "explorer.mcp.hard-max-topics", "2",
                "explorer.mcp.default-budget-ms", "2000"))))
                .containsEntry("EXPLORER_MCP_HARD_MAX_TOPICS", "2")
                .containsEntry("EXPLORER_MCP_DEFAULT_BUDGET_MS", "2000");
    }

    @Test
    @DisplayName("a setting the overlay does not publish is refused, naming the file to edit")
    void refusesAnUnpublishedSetting() {
        StackReconfigurer stack = new StackReconfigurer(ROOT, false);

        assertThatThrownBy(() -> stack.resolve(scenarioWith(
                Map.of("explorer.mcp.hard-max-rows", "5"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EXPLORER_MCP_HARD_MAX_ROWS")
                .hasMessageContaining("compose/mcp.yml")
                .hasMessageContaining("believing it had changed them");
    }

    @Test
    @DisplayName("every shipped scenario's serverConfig resolves against the overlay as it stands")
    void everyShippedScenarioResolves() {
        // The check that would have caught the two ceilings missing from the overlay, and that
        // catches the next one added to a scenario without being published.
        StackReconfigurer stack = new StackReconfigurer(ROOT, false);

        assertThat(ScenarioLoader.loadAll(Path.of("src/test/resources/eval/agent")))
                .allSatisfy(scenario -> assertThat(stack.resolve(scenario)).isNotNull());
    }

    @Test
    @DisplayName("the recreate command layers the overlay and touches only the explorer service")
    void buildsTheOperatorsOwnCommand() {
        // --no-deps, or recreating the app would take the broker with it and lose the seeded data
        // every scenario's fixture depends on.
        assertThat(new StackReconfigurer(ROOT, false).command())
                .containsSubsequence("docker", "compose", "-f", "docker-compose.yml",
                        "-f", "compose/mcp.yml", "up", "-d", "--force-recreate", "--no-deps", "explorer");
    }

    @Test
    @DisplayName("a scenario asking for what the stack already has does not recreate it")
    void reusesABootAcrossScenarios() {
        // What keeps the cost at ten seconds per distinct configuration rather than per scenario.
        StackReconfigurer stack = new StackReconfigurer(ROOT, false);
        AgentScenario first = scenarioWith(Map.of("explorer.mcp.hard-max-topics", "2"));
        AgentScenario same = scenarioWith(Map.of("explorer.mcp.hard-max-topics", "2"));
        AgentScenario other = scenarioWith(Map.of("explorer.mcp.hard-max-topics", "5"));

        assertThat(stack.applyTo(first)).isTrue();
        assertThat(stack.applyTo(same)).isFalse();
        assertThat(stack.applyTo(other)).isTrue();
    }

    @Test
    @DisplayName("a scenario with no serverConfig leaves the stack alone")
    void anEmptyConfigChangesNothing() {
        assertThat(new StackReconfigurer(ROOT, false).applyTo(scenarioWith(Map.of()))).isFalse();
    }
}
