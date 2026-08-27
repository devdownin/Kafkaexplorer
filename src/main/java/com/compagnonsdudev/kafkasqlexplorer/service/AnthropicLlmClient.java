// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.Timeout;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextDelta;
import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.LlmResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.LlmUsage;
import com.compagnonsdudev.kafkasqlexplorer.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class AnthropicLlmClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(AnthropicLlmClient.class);
    private final ClaudeConfig config;
    private final AnthropicClient client;

    /** Upper bound on the TCP/TLS handshake — see {@link #connectSeconds}. */
    private static final int MAX_CONNECT_SECONDS = 10;

    /**
     * Which models this endpoint has refused a schema for.
     *
     * <p>This path had no fallback at all, and {@code structured-output: AUTO} turns schemas
     * <em>on</em> for ANTHROPIC — so a model, a gateway in front, or an account not enabled for the
     * feature that refuses {@code output_config} failed the analysis outright, where the same
     * refusal on the OpenAI-compatible path costs one retry and the run succeeds. The provider whose
     * schema support this application asserts most confidently was the only one with nothing behind
     * the assertion.
     */
    private final SchemaRefusalMemory schemaRefusals = new SchemaRefusalMemory();

    public AnthropicLlmClient(ClaudeConfig config) {
        this.config = config;
        var builder = AnthropicOkHttpClient.builder()
            .apiKey(config.getApiKey())
            // claude.request-timeout-seconds applied at last. It was documented as governing "HTTP
            // LLM providers (OpenAI-compatible, Ollama, SpectraLLM)" and this path silently took
            // the SDK's own default instead — while the Settings form renders the field whatever
            // the provider in force, so a number on screen did nothing on the one provider whose
            // section it sits in. Split the way LlmHttpSupport splits it, and for the same reason:
            // the request budget is sized for how long a model may take to *generate*, and applying
            // it to a TCP handshake means a wrong port takes a full minute to say so.
            .timeout(Timeout.builder()
                .connect(Duration.ofSeconds(connectSeconds(config)))
                .request(Duration.ofSeconds(config.getRequestTimeoutSeconds()))
                .read(Duration.ofSeconds(config.getRequestTimeoutSeconds()))
                .write(Duration.ofSeconds(config.getRequestTimeoutSeconds()))
                .build());

        if (config.getResolvedBaseUrl() != null && !config.getResolvedBaseUrl().isBlank()) {
            builder.baseUrl(config.getResolvedBaseUrl());
        }

        this.client = builder.build();
    }

    /** Same rule and same ceiling as {@link LlmHttpSupport#newClient}: past this it is unreachable. */
    private static long connectSeconds(ClaudeConfig config) {
        return Math.max(1, Math.min(MAX_CONNECT_SECONDS, config.getRequestTimeoutSeconds()));
    }

    @Override
    public String generate(String systemPrompt, String userPrompt) {
        return generateWithMeta(systemPrompt, userPrompt, null).text();
    }

    @Override
    public LlmResponse generateWithMeta(String systemPrompt, String userPrompt) {
        return generateWithMeta(systemPrompt, userPrompt, null);
    }

    @Override
    public LlmResponse generateWithMeta(String systemPrompt, String userPrompt,
                                        LlmOutputSchema schema) {
        String model = SchemaRefusalMemory.modelKey(config.getModel());
        boolean constrain = schema != null
            && config.isStructuredOutputEnabled()
            && !schemaRefusals.refuses(model);

        try {
            try {
                return call(systemPrompt, userPrompt, constrain ? schema : null);
            } catch (AnthropicServiceException e) {
                if (!constrain || !SchemaRefusalMemory.looksLikeRefusal(e.statusCode())) {
                    throw e;
                }
                // The same one-retry degrade the OpenAI-compatible path has had all along, and the
                // same order: the unconstrained call is the experiment, so it runs before the
                // verdict is written down. A 400 says the request body was not understood and not
                // which field of it, so a failure that survives dropping the schema was never about
                // the schema — it leaves no durable conclusion and the caller gets that second
                // failure, which is the honest one.
                log.warn("Anthropic refused a schema-constrained request for model '{}' (status {});"
                        + " retrying without the constraint. Set claude.structured-output=OFF to "
                        + "skip this probe.",
                    LogSafe.slug(model), e.statusCode());
                LlmResponse unconstrained = call(systemPrompt, userPrompt, null);
                schemaRefusals.remember(model);
                log.warn("Anthropic answered model '{}' without the schema, so the schema was the "
                        + "refusal's cause; no schema will be sent for that model again by this "
                        + "client.", LogSafe.slug(model));
                return unconstrained;
            }
        } catch (AnthropicServiceException e) {
            // Named the way every other provider's client errors are named. This path reported the
            // SDK's own text and nothing else, so a 402 (out of credit) and a 403 (a permission or
            // moderation refusal) reached an operator with no indication that neither is a
            // configuration matter — the wording LlmHttpSupport.remedyFor exists to give, shared
            // rather than written a second time.
            String message = "Anthropic API call failed with status " + e.statusCode() + " — "
                + LlmHttpSupport.remedyFor(e.statusCode(), null) + ": "
                + SqlErrorClassifier.explain(e);
            log.error("Error calling Anthropic API: {}", LogSafe.text(message));
            throw new RuntimeException(message, e);
        } catch (Exception e) {
            log.error("Error calling Anthropic API: {}", LogSafe.text(SqlErrorClassifier.explain(e)), e);
            // Keep the real cause in the message: callers surface it to the UI, and a bare
            // "LLM call failed" hides timeouts, auth and model errors. Through `explain`, which is
            // never blank — getMessage() is null for a NullPointerException.
            throw new RuntimeException("Anthropic API call failed: " + SqlErrorClassifier.explain(e), e);
        }
    }

    /** One attempt, with the schema when one is being sent. Lets the SDK's own exceptions through. */
    private LlmResponse call(String systemPrompt, String userPrompt, LlmOutputSchema schema) {
        long startedAt = System.currentTimeMillis();
        MessageCreateParams.Builder params = MessageCreateParams.builder()
            .model(config.getModel())
            .maxTokens((long) config.getMaxTokens())
            // Pinned like every other provider here (0.0 on the OpenAI-compatible and SpectraLLM
            // paths). It was left unset, so this one call ran at the vendor default while the rest
            // of the pipeline ran deterministically — on two calls whose answers are parsed as JSON.
            .temperature(0.0)
            .system(systemPrompt)
            .addMessage(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(userPrompt)
                .build());

        if (schema != null) {
            params.outputConfig(OutputConfig.builder()
                .format(JsonOutputFormat.builder()
                    .schema(toSchema(schema.schema()))
                    .build())
                .build());
        }

        // Usage arrives split across the stream: input tokens on message_start, output tokens
        // on message_delta. Collecting both here is what makes the cost of an analysis a
        // measured number rather than a guess.
        AtomicReference<Long> inputTokens = new AtomicReference<>();
        AtomicLong outputTokens = new AtomicLong(-1);

        String text;
        try (var stream = client.messages().createStreaming(params.build())) {
            text = stream.stream()
                .peek(event -> {
                    event.messageStart().ifPresent(start ->
                        inputTokens.set(start.message().usage().inputTokens()));
                    event.messageDelta().ifPresent(delta ->
                        outputTokens.set(delta.usage().outputTokens()));
                })
                .flatMap(e -> e.contentBlockDelta().stream())
                .flatMap(d -> d.delta().text().stream())
                .map(TextDelta::text)
                .collect(Collectors.joining());
        }

        LlmUsage usage = new LlmUsage(
            inputTokens.get(),
            outputTokens.get() < 0 ? null : outputTokens.get(),
            // The Anthropic API prices nothing in its response, and this application keeps no
            // price table — a cost shown here is one a provider stood behind, or none.
            null,
            // Cache accounting is not read on this path: nothing here sets a cache breakpoint,
            // so a figure would only ever be zero, and a zero nobody can act on is noise.
            null,
            // Extended thinking is not requested here either, so there is no deliberation to
            // account for. Null rather than 0: nobody counted, which is not the same as none.
            null,
            System.currentTimeMillis() - startedAt,
            config.getProviderLabel(),
            config.getModel());
        log.debug("Anthropic call complete — {}", usage.summary());

        return new LlmResponse(text, List.of(), usage);
    }

    /** The SDK's schema object is a freeform property bag, so the map goes in key by key. */
    private static JsonOutputFormat.Schema toSchema(Map<String, Object> schema) {
        JsonOutputFormat.Schema.Builder builder = JsonOutputFormat.Schema.builder();
        schema.forEach((key, value) -> builder.putAdditionalProperty(key, JsonValue.from(value)));
        return builder.build();
    }
}
