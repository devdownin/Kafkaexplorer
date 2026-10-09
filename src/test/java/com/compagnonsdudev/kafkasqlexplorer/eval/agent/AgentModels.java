// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval.agent;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Builds the agent and the judge from the environment — <b>no new setting</b> but the roles.
 *
 * <p>{@code SPECAGENT.md} §5.3: the provider, the key and the model come from the variables this
 * application already documents ({@code CLAUDE_PROVIDER}, {@code OPENROUTER_API_KEY} /
 * {@code ANTHROPIC_API_KEY}, {@code CLAUDE_MODEL}). The ones that are new name the two roles, and
 * they exist because judging with the model under evaluation is asking it whether it is pleased
 * with itself: {@code AGENT_EVAL_MODEL} (falling back to {@code CLAUDE_MODEL}) and
 * {@code AGENT_EVAL_JUDGE_MODEL} (falling back to the agent's model), and
 * {@code AGENT_EVAL_JUDGE_PROVIDER} (falling back to {@code CLAUDE_PROVIDER}) — which is what lets
 * a local agent be graded by a hosted judge.
 *
 * <p><b>{@code SPECTRA} means SpectraLLM's llama.cpp chat server, not its query API.</b>
 * {@code POST /api/query} is single-turn and has no notion of a tool, so the harness drives the
 * {@code llm-chat} service behind it, which speaks OpenAI's {@code /chat/completions} with tools
 * once started with {@code --jinja} — what {@code compose/spectra-hub.agent-eval.yml} adds, along
 * with the loopback port. {@code CLAUDE_BASE_URL} is <em>not</em> read for it: under that provider
 * it names Spectra's API, and posting a chat completion there answers 404 about a server that is
 * fine. The port is {@code AGENT_EVAL_LLM_PORT}, the variable the overlay publishes, so one export
 * configures both; the model defaults to {@code LLM_CHAT_MODEL_NAME}, the alias the stack serves.
 *
 * <p><b>An absent configuration produces a reason, never a client that fails later.</b>
 * {@link #unconfigured()} is what {@code McpAgentEvalTest} turns into a skip, and the sentence names
 * the variable to set: a suite that goes red for want of an API key is one people learn to ignore,
 * and one that says "skipped" without saying why is the same thing a day later.
 */
final class AgentModels {

    private static final String SPECTRA = "SPECTRA";
    private static final String DEFAULT_LLM_PORT = "8090";

    /** Read through a map so the resolution is testable without touching the process environment. */
    private final Map<String, String> environment;

    AgentModels(Map<String, String> environment) {
        this.environment = environment;
    }

    static AgentModels fromEnvironment() {
        return new AgentModels(System.getenv());
    }

    /** Why no model can be built, or empty when one can. */
    Optional<String> unconfigured() {
        return problem(agentRole(), "CLAUDE_PROVIDER", "AGENT_EVAL_MODEL")
                .or(() -> problem(judgeRole(), "AGENT_EVAL_JUDGE_PROVIDER", "AGENT_EVAL_JUDGE_MODEL")
                        .map(reason -> "judge: " + reason));
    }

    AgentModel agent() {
        return build(agentRole());
    }

    /**
     * The judge, which defaults to the same model as the agent and should not be left there.
     *
     * <p>Defaulting rather than refusing is deliberate: a suite that cannot run at all without a
     * second model named would be one nobody tries. {@link #judgeCaveat()} reports the state so
     * the report can carry the caveat instead of hiding it.
     */
    AgentModel judge() {
        return build(judgeRole());
    }

    /**
     * Whether the verdicts are graded by the model that produced the answers.
     *
     * <p>On {@code SPECTRA} the names cannot tell: llama-server serves one model and ignores the
     * {@code model} field, so two different names on the same server are still one model, and
     * reporting them as distinct would be the false reassurance this caveat exists to prevent.
     */
    boolean judgeIsTheAgent() {
        Role agent = agentRole();
        Role judge = judgeRole();
        return agent.provider().equals(judge.provider())
                && (SPECTRA.equals(agent.provider()) || agent.model().equals(judge.model()));
    }

    Optional<String> judgeCaveat() {
        if (!judgeIsTheAgent()) {
            return Optional.empty();
        }
        String remedy = SPECTRA.equals(agentRole().provider())
                ? "llm-chat serves one model whatever name is asked, so set AGENT_EVAL_JUDGE_PROVIDER "
                        + "(and its key) to grade with another"
                : "set AGENT_EVAL_JUDGE_MODEL";
        return Optional.of("the judge is the model under evaluation — " + remedy + ". "
                + "Judging with the model being graded is asking it whether it is pleased with itself.");
    }

    private record Role(String provider, String model) {}

    private Role agentRole() {
        String provider = provider("CLAUDE_PROVIDER", "OPENROUTER");
        return new Role(provider, model("AGENT_EVAL_MODEL", provider));
    }

    private Role judgeRole() {
        Role agent = agentRole();
        String provider = provider("AGENT_EVAL_JUDGE_PROVIDER", agent.provider());
        String named = get("AGENT_EVAL_JUDGE_MODEL", "");
        if (!named.isBlank()) {
            return new Role(provider, named);
        }
        // On the agent's provider the agent's model is the default; on another, that name means nothing.
        return new Role(provider, provider.equals(agent.provider()) ? agent.model() : model("", provider));
    }

    private Optional<String> problem(Role role, String providerVariable, String modelVariable) {
        String provider = role.provider();
        return switch (provider) {
            case "ANTHROPIC", "OPENROUTER", "OPENAI_COMPATIBLE" -> key().isBlank()
                    ? Optional.of("no API key: set OPENROUTER_API_KEY or ANTHROPIC_API_KEY ("
                            + providerVariable + " is " + provider + ")")
                    : missingModel(role, modelVariable + " or CLAUDE_MODEL");
            case "OLLAMA" -> missingModel(role, modelVariable + " or CLAUDE_MODEL");
            case SPECTRA -> missingModel(role, modelVariable + " or LLM_CHAT_MODEL_NAME");
            default -> Optional.of(providerVariable + "=" + provider
                    + " has no tool-calling API this harness can drive");
        };
    }

    private static Optional<String> missingModel(Role role, String variables) {
        return role.model().isBlank() ? Optional.of("no model: set " + variables) : Optional.empty();
    }

    private AgentModel build(Role role) {
        Duration timeout = Duration.ofSeconds(30);
        if ("ANTHROPIC".equals(role.provider())) {
            return new AnthropicToolCallingModel(
                    URI.create(baseUrl(role.provider()) + "/messages"), key(), role.model(), 4096, timeout);
        }
        // No key for llm-chat: llama-server has no authentication, and sending it the OpenRouter
        // key would hand a credential to a process that has no use for it.
        String key = SPECTRA.equals(role.provider()) ? "" : key();
        return new OpenAiToolCallingModel(
                URI.create(baseUrl(role.provider()) + "/chat/completions"), key, role.model(), timeout);
    }

    private String provider(String variable, String fallback) {
        return get(variable, fallback).toUpperCase(Locale.ROOT);
    }

    /** Both historical names, in the order `application.yml` reads them. */
    private String key() {
        String openRouter = get("OPENROUTER_API_KEY", "");
        return openRouter.isBlank() ? get("ANTHROPIC_API_KEY", "") : openRouter;
    }

    private String model(String variable, String provider) {
        String named = variable.isEmpty() ? "" : get(variable, "");
        if (!named.isBlank()) {
            return named;
        }
        // CLAUDE_MODEL is ignored by the application under SPECTRA, so it names nothing served here.
        return SPECTRA.equals(provider) ? get("LLM_CHAT_MODEL_NAME", "") : get("CLAUDE_MODEL", "");
    }

    /**
     * {@code CLAUDE_BASE_URL} belongs to {@code CLAUDE_PROVIDER}: a judge on another provider
     * posting to the agent's gateway would reach the wrong server with the right key.
     */
    private String baseUrl(String provider) {
        if (SPECTRA.equals(provider)) {
            return "http://localhost:" + get("AGENT_EVAL_LLM_PORT", DEFAULT_LLM_PORT) + "/v1";
        }
        String explicit = get("CLAUDE_BASE_URL", "");
        if (!explicit.isBlank() && provider.equals(provider("CLAUDE_PROVIDER", "OPENROUTER"))) {
            return trimTrailingSlash(explicit);
        }
        return switch (provider) {
            case "ANTHROPIC" -> "https://api.anthropic.com/v1";
            case "OLLAMA" -> "http://localhost:11434/v1";
            default -> "https://openrouter.ai/api/v1";
        };
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String get(String name, String fallback) {
        String value = environment.get(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
