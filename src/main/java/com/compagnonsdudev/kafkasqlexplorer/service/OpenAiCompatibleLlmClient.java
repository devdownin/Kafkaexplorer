// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.LlmResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.LlmUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class OpenAiCompatibleLlmClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);
    private final ClaudeConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * The models this endpoint has refused a schema-constrained request for, so the next call does
     * not repeat the mistake. Instance state rather than config: the client is rebuilt whenever the
     * provider, base URL or key changes, which is exactly the lifetime this observation is valid
     * for.
     *
     * <p>Keyed by <em>model</em> rather than being one flag for the client, because on a gateway
     * that routes — OpenRouter above all — schema support is a property of the model and of the
     * upstream provider serving it, not of the endpoint. One flag meant a model that cannot be
     * constrained disabled constrained decoding for every model chosen afterwards, silently and for
     * the client's whole lifetime: {@link LlmClientProvider} fingerprints provider, base URL and
     * key, and the model is in none of them, so changing the model in Settings reuses this very
     * client. A bounded map, since the set of models one deployment tries is small and this lives
     * on a long-lived bean.
     */
    private final Set<String> modelsRefusingSchema = ConcurrentHashMap.newKeySet();

    /** Upper bound on {@link #modelsRefusingSchema} — a guard against an unbounded field, not a policy. */
    private static final int MAX_REMEMBERED_MODELS = 64;

    /** Sent only to OpenRouter — see the header block in {@link #call}. */
    private static final String OPENROUTER_APP_URL = "https://github.com/devdownin/Kafkaexplorer";
    private static final String OPENROUTER_APP_TITLE = "Kafka SQL Explorer";

    public OpenAiCompatibleLlmClient(ClaudeConfig config) {
        this.config = config;
        this.httpClient = LlmHttpSupport.newClient(config);
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
        String model = modelKey();
        boolean constrain = schema != null
            && config.isStructuredOutputEnabled()
            && !modelsRefusingSchema.contains(model);

        try {
            return call(systemPrompt, userPrompt, constrain ? schema : null);
        } catch (LlmHttpSupport.ClientErrorException e) {
            if (!constrain || !looksLikeSchemaRefusal(e.status())) {
                throw e;
            }
            // The endpoint rejected the request and a schema was the only unusual thing in it.
            // Retrying unconstrained is worth one attempt: the alternative is telling an operator
            // their gateway is broken when it merely does not implement response_format. If the
            // second call fails too, that error is the honest one to report.
            log.warn("{} refused a schema-constrained request for model '{}' (status {}); retrying "
                    + "without the constraint and not sending one again for that model. Set "
                    + "claude.structured-output=OFF to skip this probe.",
                config.getProviderLabel(), model, e.status());
            rememberSchemaRefusal(model);
            return call(systemPrompt, userPrompt, null);
        }
    }

    /**
     * OpenRouter's {@code provider} routing object, empty for every other provider.
     *
     * <p>A routing gateway is the one place where "where does my data go" has an answer better than
     * a warning. {@code data_collection: "deny"} restricts routing to upstream providers that do
     * not retain or train on what is sent — so the Settings page can state a property instead of a
     * risk, which is the whole reason this application reads its privacy claims off the resolved
     * address rather than off a provider's name. It defaults to deny: a model served only by
     * data-collecting providers then fails to route, and an error naming the policy is the right
     * outcome when the alternative is Kafka message digests silently becoming training data.
     *
     * <p>{@code require_parameters} is the other half and is deliberately <em>off</em> by default.
     * It routes only to providers implementing every parameter sent, which would make structured
     * output a routing guarantee rather than something discovered by a 400 — but a model whose
     * providers do not support schemas then becomes unroutable instead of degrading, and the
     * per-model latch cannot catch that: a refusal arrives as "no endpoints found", not as the
     * 400/422 the latch keys on. Turning a working deployment into a failing one to gain a
     * guarantee it may not need is the same trade {@code structured-output: AUTO} already refuses.
     */
    private Map<String, Object> routingPolicy() {
        if (config.getProvider() != ClaudeConfig.Provider.OPENROUTER) {
            return Map.of();
        }
        Map<String, Object> routing = new LinkedHashMap<>();
        if (config.getOpenrouterDataCollection() == ClaudeConfig.DataCollection.DENY) {
            routing.put("data_collection", "deny");
        }
        if (config.isOpenrouterRequireParameters()) {
            routing.put("require_parameters", true);
        }
        return routing;
    }

    /**
     * Names the routing policy when a "not found" is plausibly its doing.
     *
     * <p>Restricting routing turns "this model exists" into "this model exists <em>and</em> some
     * provider serving it satisfies your policy", and the gateway answers the second question with
     * the same 404 it uses for a mistyped slug. Left alone, an operator reads "model not found",
     * checks the spelling — which is correct — and has no way to reach the real cause. The
     * exception keeps its status, because {@link #looksLikeSchemaRefusal} reads it and a 404 must
     * go on meaning "do not retry without the schema".
     */
    private LlmHttpSupport.ClientErrorException explainRoutingRefusal(
            LlmHttpSupport.ClientErrorException e) {
        Map<String, Object> routing = routingPolicy();
        if (e.status() != 404 || routing.isEmpty()) {
            return e;
        }
        return new LlmHttpSupport.ClientErrorException(e.status(), e.getMessage()
            + " — note that provider routing is restricted (" + routing
            + "), so a model whose providers do not satisfy it is reported exactly like an unknown "
            + "one. Relax claude.openrouter-data-collection or claude.openrouter-require-parameters "
            + "to tell the two apart.");
    }

    /** The configured model, normalised so a null or blank one still keys the map. */
    private String modelKey() {
        String model = config.getModel();
        return model == null || model.isBlank() ? "" : model.strip();
    }

    private void rememberSchemaRefusal(String model) {
        if (modelsRefusingSchema.size() >= MAX_REMEMBERED_MODELS) {
            // Nothing here is worth an eviction policy: forget the lot and re-probe. Sixty-four
            // models on one client means the operator has been switching all afternoon, and one
            // extra request per model is cheaper than a field that grows without bound.
            modelsRefusingSchema.clear();
        }
        modelsRefusingSchema.add(model);
    }

    /**
     * Whether a 4xx can plausibly mean "I do not implement {@code response_format}".
     *
     * <p>Only a rejected <em>request body</em> can: 400 and 422 are what a gateway answers to a field
     * it does not understand. The latch used to fire on any 4xx, and the two that matter are 401 and
     * 404 — a wrong key and a wrong model or path. Those disable structured output permanently for
     * the client's lifetime, and that lifetime is longer than it looks: {@link LlmClientProvider}
     * fingerprints provider, base URL and key, so correcting a mistyped <em>model</em> in Settings
     * reuses the same client. The deployment then runs unconstrained for ever, silently, because of
     * a typo that was fixed minutes later — the failure mode a fallback exists to prevent, arrived at
     * through the fallback itself.
     */
    private static boolean looksLikeSchemaRefusal(int status) {
        return status == 400 || status == 422;
    }

    private LlmResponse call(String systemPrompt, String userPrompt, LlmOutputSchema schema) {
        long startedAt = System.currentTimeMillis();
        try {
            String url = resolveChatCompletionsUrl();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", config.getModel());
            body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
            ));
            body.put("max_tokens", config.getMaxTokens());
            body.put("temperature", 0.0);
            body.put("stream", false);
            if (schema != null) {
                body.put("response_format", Map.of(
                    "type", "json_schema",
                    "json_schema", Map.of(
                        "name", schema.name(),
                        "strict", true,
                        "schema", schema.schema())));
            }
            Map<String, Object> routing = routingPolicy();
            if (!routing.isEmpty()) {
                body.put("provider", routing);
            }

            String requestBody = objectMapper.writeValueAsString(body);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));
            LlmHttpSupport.withTimeout(requestBuilder, config);

            if (config.isApiKeyConfigured()) {
                requestBuilder.header("Authorization", "Bearer " + config.getApiKey());
            }
            if (config.getProvider() == ClaudeConfig.Provider.OPENROUTER) {
                // OpenRouter's two optional attribution headers, which is how a request is credited
                // to an application on its public leaderboard. They carry this project's identity
                // and nothing about the deployment or the messages — no host name, no topic, no
                // payload — so they say who wrote the client, not who is running it.
                requestBuilder.header("HTTP-Referer", OPENROUTER_APP_URL);
                requestBuilder.header("X-Title", OPENROUTER_APP_TITLE);
            }

            HttpResponse<String> response =
                LlmHttpSupport.sendWithRetry(httpClient, requestBuilder.build(), config.getProviderLabel());

            JsonNode root = objectMapper.readTree(response.body());
            String text = firstChoiceContent(root, response.body());
            LlmUsage usage = usageOf(root, System.currentTimeMillis() - startedAt);
            log.debug("{} call complete — {}", config.getProviderLabel(), usage.summary());
            return new LlmResponse(text, List.of(), usage);

        } catch (LlmHttpSupport.ClientErrorException e) {
            LlmHttpSupport.ClientErrorException reported = explainRoutingRefusal(e);
            log.error("Error calling OpenAI-compatible API: {}", reported.getMessage());
            throw reported;
        } catch (RuntimeException e) {
            log.error("Error calling OpenAI-compatible API: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Error calling OpenAI-compatible API: {}", e.getMessage(), e);
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Reads the {@code usage} object. Both OpenAI and Ollama's compatibility layer report
     * {@code prompt_tokens} / {@code completion_tokens}, but a leaner gateway may omit the object
     * entirely — in which case the counts stay null rather than becoming a zero that would read as
     * "this call was free".
     */
    private LlmUsage usageOf(JsonNode root, long durationMs) {
        JsonNode usage = root.path("usage");
        return new LlmUsage(
            longOrNull(usage, "prompt_tokens"),
            longOrNull(usage, "completion_tokens"),
            // OpenRouter prices every response, so the money is already on the wire and used to be
            // thrown away — on the provider this application now ships pointed at, which bills per
            // token. Read, never computed: no price table lives here, so a figure on screen is one
            // the provider stood behind. Absent on OpenAI and Ollama, and null there rather than 0.
            doubleOrNull(usage, "cost"),
            durationMs,
            config.getProviderLabel(),
            config.getModel());
    }

    private static Long longOrNull(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        return value.isNumber() ? value.asLong() : null;
    }

    private static Double doubleOrNull(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        return value.isNumber() ? value.asDouble() : null;
    }

    private String resolveChatCompletionsUrl() {
        String baseUrl = config.getResolvedBaseUrl();
        if (baseUrl.endsWith("/v1")) {
            return baseUrl + "/chat/completions";
        }
        if (baseUrl.endsWith("/v1/")) {
            return baseUrl + "chat/completions";
        }
        return baseUrl + "/v1/chat/completions";
    }

    /**
     * Reads {@code choices[0].message.content}, refusing an answerless body rather than crashing on
     * it. {@code path("choices").get(0)} returns {@code null} when the array is absent or empty —
     * which is precisely what a 200-with-an-error body looks like on Ollama and on several
     * OpenAI-compatible gateways — so the NPE that followed reached the user as
     * "LLM call failed: null", the one message that names neither cause nor remedy. The provider's
     * own {@code error.message} is the useful half of such a body, so it is what gets reported.
     */
    static String firstChoiceContent(JsonNode root, String rawBody) {
        JsonNode error = root.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            String detail = error.isTextual() ? error.asText() : error.path("message").asText("");
            throw new RuntimeException("LLM provider returned an error: "
                + (detail.isBlank() ? truncate(rawBody) : detail));
        }

        JsonNode choice = root.path("choices").path(0);
        if (choice.isMissingNode()) {
            throw new RuntimeException("LLM response contained no choices "
                + "(check the model name and that the endpoint is an OpenAI-compatible "
                + "/chat/completions API): " + truncate(rawBody));
        }

        JsonNode content = choice.path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new RuntimeException("LLM response choice carried no message content: "
                + truncate(rawBody));
        }
        return content.asText();
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) + "…" : body;
    }
}
