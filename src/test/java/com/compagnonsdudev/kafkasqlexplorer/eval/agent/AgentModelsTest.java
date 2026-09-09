// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the harness does with an environment, and what it says when it cannot.
 *
 * <p>The skip sentence is the product here, not the boolean: a suite that goes red for want of an
 * API key is one people learn to ignore, and one that skips without naming the variable to set is
 * the same thing a day later. Every case below asserts the sentence.
 */
class AgentModelsTest {

    private static AgentModels with(String... pairs) {
        Map<String, String> environment = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            environment.put(pairs[i], pairs[i + 1]);
        }
        return new AgentModels(environment);
    }

    @Test
    @DisplayName("a key and a model are all it needs")
    void configured() {
        AgentModels models = with("OPENROUTER_API_KEY", "sk-test", "AGENT_EVAL_MODEL", "a/b");

        assertThat(models.unconfigured()).isEmpty();
        assertThat(models.agent().describe()).contains("a/b").contains("openrouter.ai");
    }

    @Test
    @DisplayName("no key names both variables, and which provider is configured")
    void refusesWithoutAKey() {
        assertThat(with("AGENT_EVAL_MODEL", "a/b").unconfigured())
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("OPENROUTER_API_KEY")
                        .contains("ANTHROPIC_API_KEY")
                        .contains("OPENROUTER"));
    }

    @Test
    @DisplayName("no model names the variable to set")
    void refusesWithoutAModel() {
        assertThat(with("OPENROUTER_API_KEY", "sk-test").unconfigured())
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("AGENT_EVAL_MODEL").contains("CLAUDE_MODEL"));
    }

    @Test
    @DisplayName("Ollama needs no key, because it has none")
    void ollamaNeedsNoKey() {
        assertThat(with("CLAUDE_PROVIDER", "OLLAMA", "AGENT_EVAL_MODEL", "qwen2.5").unconfigured())
                .isEmpty();
    }

    @Test
    @DisplayName("a provider with no tool-calling API is refused, not left to fail mid-run")
    void refusesAProviderThatCannotDriveTools() {
        // SPECTRA's query API has no tool notion at all. Saying so is the difference between
        // "fix your environment" and "this provider cannot run this suite".
        assertThat(with("CLAUDE_PROVIDER", "SPECTRA", "OPENROUTER_API_KEY", "k",
                "AGENT_EVAL_MODEL", "m").unconfigured())
                .hasValueSatisfying(reason -> assertThat(reason).contains("tool-calling"));
    }

    @Test
    @DisplayName("ANTHROPIC builds the Messages client, everything else the OpenAI-compatible one")
    void picksTheClientForTheProvider() {
        assertThat(with("CLAUDE_PROVIDER", "ANTHROPIC", "ANTHROPIC_API_KEY", "sk-ant",
                "AGENT_EVAL_MODEL", "claude-x").agent())
                .isInstanceOf(AnthropicToolCallingModel.class);
        assertThat(with("CLAUDE_PROVIDER", "OLLAMA", "AGENT_EVAL_MODEL", "qwen").agent())
                .isInstanceOf(OpenAiToolCallingModel.class);
    }

    @Test
    @DisplayName("both roles fall back to CLAUDE_MODEL, and the harness knows when they collide")
    void theJudgeDefaultsToTheAgentAndSaysSo() {
        // Judging with the model under evaluation is asking it whether it is pleased with itself.
        // Defaulting rather than refusing keeps the suite runnable; reporting the collision is what
        // stops it being silent.
        AgentModels shared = with("OPENROUTER_API_KEY", "k", "CLAUDE_MODEL", "one/model");
        assertThat(shared.judgeIsTheAgent()).isTrue();

        AgentModels split = with("OPENROUTER_API_KEY", "k",
                "AGENT_EVAL_MODEL", "big", "AGENT_EVAL_JUDGE_MODEL", "small");
        assertThat(split.judgeIsTheAgent()).isFalse();
        assertThat(split.judge().describe()).contains("small");
    }

    @Test
    @DisplayName("OPENROUTER_API_KEY wins over ANTHROPIC_API_KEY, as application.yml reads them")
    void keyPrecedenceMatchesTheApplication() {
        assertThat(with("OPENROUTER_API_KEY", "router", "ANTHROPIC_API_KEY", "ant",
                "AGENT_EVAL_MODEL", "m").unconfigured()).isEmpty();
    }

    @Test
    @DisplayName("a base URL override loses its trailing slash rather than producing a double one")
    void trimsTheBaseUrl() {
        assertThat(with("CLAUDE_BASE_URL", "http://gateway.internal/v1/",
                "OPENROUTER_API_KEY", "k", "AGENT_EVAL_MODEL", "m").agent().describe())
                .contains("gateway.internal");
    }
}
