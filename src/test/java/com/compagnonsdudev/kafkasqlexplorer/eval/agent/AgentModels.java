// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval.agent;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Builds the agent and the judge from the environment — <b>no new setting</b>.
 *
 * <p>{@code SPECAGENT.md} §5.3: the provider, the key and the model come from the variables this
 * application already documents ({@code CLAUDE_PROVIDER}, {@code OPENROUTER_API_KEY} /
 * {@code ANTHROPIC_API_KEY}, {@code CLAUDE_MODEL}). The two that are new name the two roles, and
 * they exist because judging with the model under evaluation is asking it whether it is pleased
 * with itself: {@code AGENT_EVAL_MODEL} and {@code AGENT_EVAL_JUDGE_MODEL}, each falling back to
 * {@code CLAUDE_MODEL}.
 *
 * <p><b>An absent configuration produces a reason, never a client that fails later.</b>
 * {@link #unconfigured()} is what {@code McpAgentEvalTest} turns into a skip, and the sentence names
 * the variable to set: a suite that goes red for want of an API key is one people learn to ignore,
 * and one that says "skipped" without saying why is the same thing a day later.
 */
final class AgentModels {

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
        String provider = provider();
        if (key().isBlank() && !"OLLAMA".equals(provider)) {
            return Optional.of("no API key: set OPENROUTER_API_KEY or ANTHROPIC_API_KEY "
                    + "(CLAUDE_PROVIDER is " + provider + ")");
        }
        if (model("AGENT_EVAL_MODEL").isBlank()) {
            return Optional.of("no model: set AGENT_EVAL_MODEL or CLAUDE_MODEL");
        }
        return switch (provider) {
            case "ANTHROPIC", "OPENROUTER", "OPENAI_COMPATIBLE", "OLLAMA" -> Optional.empty();
            // SPECTRA's query API has no tool-calling notion at all, so this is a refusal rather
            // than a failure to configure — and saying which is the difference between "fix your
            // environment" and "this provider cannot run this suite".
            default -> Optional.of("CLAUDE_PROVIDER=" + provider
                    + " has no tool-calling API this harness can drive");
        };
    }

    AgentModel agent() {
        return build(model("AGENT_EVAL_MODEL"));
    }

    /**
     * The judge, which defaults to the same model as the agent and should not be left there.
     *
     * <p>Defaulting rather than refusing is deliberate: a suite that cannot run at all without a
     * second model named would be one nobody tries. {@link #judgeIsTheAgent()} reports the state so
     * the report can carry the caveat instead of hiding it.
     */
    AgentModel judge() {
        return build(model("AGENT_EVAL_JUDGE_MODEL"));
    }

    boolean judgeIsTheAgent() {
        return model("AGENT_EVAL_MODEL").equals(model("AGENT_EVAL_JUDGE_MODEL"));
    }

    private AgentModel build(String model) {
        Duration timeout = Duration.ofSeconds(30);
        if ("ANTHROPIC".equals(provider())) {
            return new AnthropicToolCallingModel(
                    URI.create(baseUrl() + "/messages"), key(), model, 4096, timeout);
        }
        return new OpenAiToolCallingModel(
                URI.create(baseUrl() + "/chat/completions"), key(), model, timeout);
    }

    private String provider() {
        return get("CLAUDE_PROVIDER", "OPENROUTER").toUpperCase(Locale.ROOT);
    }

    /** Both historical names, in the order `application.yml` reads them. */
    private String key() {
        String openRouter = get("OPENROUTER_API_KEY", "");
        return openRouter.isBlank() ? get("ANTHROPIC_API_KEY", "") : openRouter;
    }

    private String model(String variable) {
        String named = get(variable, "");
        return named.isBlank() ? get("CLAUDE_MODEL", "") : named;
    }

    private String baseUrl() {
        String explicit = get("CLAUDE_BASE_URL", "");
        if (!explicit.isBlank()) {
            return trimTrailingSlash(explicit);
        }
        return switch (provider()) {
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
