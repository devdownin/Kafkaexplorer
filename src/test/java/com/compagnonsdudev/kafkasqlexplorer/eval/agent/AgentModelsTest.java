// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval.agent;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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
        // Saying so is the difference between "fix your environment" and "this provider cannot run
        // this suite".
        assertThat(with("CLAUDE_PROVIDER", "BEDROCK", "OPENROUTER_API_KEY", "k",
                "AGENT_EVAL_MODEL", "m").unconfigured())
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("CLAUDE_PROVIDER=BEDROCK").contains("tool-calling"));
    }

    @Test
    @DisplayName("SPECTRA needs no key and takes the alias llm-chat serves as its model")
    void spectraNeedsNoKeyAndDefaultsToTheServedAlias() {
        AgentModels models = with("CLAUDE_PROVIDER", "spectra", "LLM_CHAT_MODEL_NAME", "qwen2.5-7b-instruct",
                "CLAUDE_MODEL", "ignored-by-spectra");

        assertThat(models.unconfigured()).isEmpty();
        assertThat(models.agent()).isInstanceOf(OpenAiToolCallingModel.class);
        assertThat(models.agent().describe()).isEqualTo("qwen2.5-7b-instruct via localhost");
    }

    @Test
    @DisplayName("SPECTRA without a model names the alias variable, not CLAUDE_MODEL")
    void spectraWithoutAModelNamesTheAlias() {
        // CLAUDE_MODEL is ignored by the application under SPECTRA; pointing at it would send the
        // operator to a variable that changes nothing.
        assertThat(with("CLAUDE_PROVIDER", "SPECTRA").unconfigured())
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("AGENT_EVAL_MODEL").contains("LLM_CHAT_MODEL_NAME")
                        .doesNotContain("CLAUDE_MODEL"));
    }

    @Test
    @DisplayName("SPECTRA posts to llm-chat on AGENT_EVAL_LLM_PORT, without the key and ignoring CLAUDE_BASE_URL")
    void spectraReachesLlmChatWithoutACredential() throws IOException {
        // CLAUDE_BASE_URL names Spectra's /api/query under this provider, and llama-server has no
        // authentication: the request must go to the overlay's port and carry nothing.
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"done\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            AgentModels models = with("CLAUDE_PROVIDER", "SPECTRA", "LLM_CHAT_MODEL_NAME", "qwen",
                    "AGENT_EVAL_LLM_PORT", String.valueOf(server.getAddress().getPort()),
                    "CLAUDE_BASE_URL", "http://spectra-api.invalid:8080",
                    "OPENROUTER_API_KEY", "sk-must-not-leak");

            AgentModel.Turn turn = models.agent()
                    .respond("system", List.of(new AgentModel.Exchange.User("go")), List.of());

            assertThat(turn.text()).isEqualTo("done");
            assertThat(path.get()).isEqualTo("/v1/chat/completions");
            assertThat(authorization.get()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("a local agent can be graded by a hosted judge, and then no caveat is printed")
    void theJudgeCanLiveOnAnotherProvider() {
        AgentModels models = with("CLAUDE_PROVIDER", "SPECTRA", "LLM_CHAT_MODEL_NAME", "qwen",
                "AGENT_EVAL_JUDGE_PROVIDER", "ANTHROPIC", "ANTHROPIC_API_KEY", "sk-ant",
                "AGENT_EVAL_JUDGE_MODEL", "claude-x");

        assertThat(models.unconfigured()).isEmpty();
        assertThat(models.agent()).isInstanceOf(OpenAiToolCallingModel.class);
        assertThat(models.judge()).isInstanceOf(AnthropicToolCallingModel.class);
        assertThat(models.judge().describe()).contains("claude-x").contains("api.anthropic.com");
        assertThat(models.judgeCaveat()).isEmpty();
    }

    @Test
    @DisplayName("an OpenRouter judge grades a SPECTRA agent through its own endpoint and key")
    void theJudgeCanBeOnOpenRouter() {
        // What agent-eval.yml runs with judge_provider=OPENROUTER: the agent's model is llm-chat's,
        // the judge's is OpenRouter's, and neither borrows the other's address.
        AgentModels models = with("CLAUDE_PROVIDER", "SPECTRA", "LLM_CHAT_MODEL_NAME", "qwen",
                "AGENT_EVAL_JUDGE_PROVIDER", "OPENROUTER", "OPENROUTER_API_KEY", "sk-or",
                "AGENT_EVAL_JUDGE_MODEL", "anthropic/some-model");

        assertThat(models.unconfigured()).isEmpty();
        assertThat(models.agent().describe()).isEqualTo("qwen via localhost");
        assertThat(models.judge()).isInstanceOf(OpenAiToolCallingModel.class);
        assertThat(models.judge().describe()).isEqualTo("anthropic/some-model via openrouter.ai");
        assertThat(models.judgeCaveat()).isEmpty();
    }

    @Test
    @DisplayName("a hosted judge with no key is reported as the judge's problem")
    void aJudgeWithoutAKeyIsNamedAsSuch() {
        assertThat(with("CLAUDE_PROVIDER", "SPECTRA", "LLM_CHAT_MODEL_NAME", "qwen",
                "AGENT_EVAL_JUDGE_PROVIDER", "ANTHROPIC", "AGENT_EVAL_JUDGE_MODEL", "claude-x").unconfigured())
                .hasValueSatisfying(reason -> assertThat(reason)
                        .startsWith("judge: ").contains("AGENT_EVAL_JUDGE_PROVIDER is ANTHROPIC"));
    }

    @Test
    @DisplayName("on SPECTRA two names are still one model, so the caveat stands")
    void twoNamesOnLlmChatAreOneModel() {
        // llama-server ignores the model field: distinct names would pass for distinct models.
        AgentModels models = with("CLAUDE_PROVIDER", "SPECTRA",
                "AGENT_EVAL_MODEL", "qwen", "AGENT_EVAL_JUDGE_MODEL", "other-name");

        assertThat(models.judgeIsTheAgent()).isTrue();
        assertThat(models.judgeCaveat()).hasValueSatisfying(caveat -> assertThat(caveat)
                .contains("AGENT_EVAL_JUDGE_PROVIDER"));
    }

    @Test
    @DisplayName("CLAUDE_BASE_URL is the agent's gateway, not the judge's on another provider")
    void theBaseUrlBelongsToClaudeProvider() {
        AgentModels models = with("CLAUDE_PROVIDER", "OLLAMA", "CLAUDE_BASE_URL", "http://gpu-box:11434/v1",
                "AGENT_EVAL_MODEL", "qwen", "AGENT_EVAL_JUDGE_PROVIDER", "OPENROUTER",
                "OPENROUTER_API_KEY", "k", "AGENT_EVAL_JUDGE_MODEL", "a/b");

        assertThat(models.agent().describe()).contains("gpu-box");
        assertThat(models.judge().describe()).contains("openrouter.ai");
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
