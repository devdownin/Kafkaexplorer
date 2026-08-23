// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.ProcessMiningConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.LlmModelCheck;
import com.compagnonsdudev.kafkasqlexplorer.domain.SchemaSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Asks OpenRouter what the configured model can do, so the Test button can answer the question an
 * operator actually has.
 *
 * <p>Everywhere else in this application the model's capabilities are discovered by <em>provoking a
 * failure</em>: the schema latch learns that a model refuses {@code response_format} from the 400 it
 * returns, and a slug that cannot emit text is found out on the first analysed window. OpenRouter
 * publishes all of it, per model, at {@code GET /v1/model/{author}/{slug}} — one small request,
 * about the one slug configured, rather than the several-hundred-model catalogue.
 *
 * <p>Four rules keep it from becoming a liability:
 *
 * <ul>
 *   <li><strong>Only against OpenRouter's own host.</strong> Same test as the attribution headers
 *       ({@link ClaudeConfig#isOpenRouterEndpoint()}) and the same reasoning: the provider enum
 *       says which dialect to speak, not who answers, and posting a vendor-specific path at
 *       somebody's corporate egress proxy is not this application's business. The asymmetry with
 *       the routing policy is deliberate and is documented there — that one carries a guarantee
 *       and must survive a proxy; this one is a convenience and can be withheld on a guess.
 *   <li><strong>Best-effort, always.</strong> Every failure becomes
 *       {@link LlmModelCheck#unavailable}, never an exception and never a changed verdict about
 *       reachability. "We asked and the answer is no" and "we could not ask" are different
 *       answers.
 *   <li><strong>No retry, short deadline.</strong> This is a side read on an interactive gesture.
 *       Retrying it would make the Test button slower to say something it was never obliged to
 *       say.
 *   <li><strong>Never on the analysis path.</strong> It runs from {@code test-llm} and nowhere
 *       else. A capability report is worth one deliberate button press, not a request per window.
 * </ul>
 */
@Service
public class OpenRouterModelCatalog {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterModelCatalog.class);

    /**
     * The same deliberately optimistic ratio {@code docs/check-compose.py} applies to the prompt
     * budget. Optimistic on purpose: it makes the resulting verdict a <em>floor</em>, so a budget
     * that fails this comparison certainly does not fit, while one that passes it may still not.
     * A stricter ratio would look like a calibration, which nothing here is in a position to
     * offer — tokenisation is the model's.
     */
    private static final int CHARS_PER_TOKEN = 4;

    /** A catalogue read is metadata. Past this it is not slow, it is unreachable. */
    private static final int LOOKUP_TIMEOUT_SECONDS = 10;

    private final ClaudeConfig claudeConfig;
    private final ProcessMiningConfig processMiningConfig;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenRouterModelCatalog(ClaudeConfig claudeConfig, ProcessMiningConfig processMiningConfig) {
        this.claudeConfig = claudeConfig;
        this.processMiningConfig = processMiningConfig;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(LOOKUP_TIMEOUT_SECONDS))
            .build();
    }

    /** Whether a lookup is possible at all — false for every provider but OpenRouter's own host. */
    public boolean isSupported() {
        return claudeConfig.isOpenRouterEndpoint();
    }

    /**
     * Looks the configured model up, or explains why it could not.
     *
     * @return never {@code null}; an unusable answer comes back as
     *         {@link LlmModelCheck#unavailable}
     */
    public LlmModelCheck describeConfiguredModel() {
        if (!isSupported()) {
            return LlmModelCheck.unavailable(
                "Model capabilities are published by OpenRouter; this endpoint is "
                    + claudeConfig.getResolvedBaseUrl() + ".");
        }
        String slug = claudeConfig.getModel() == null ? "" : claudeConfig.getModel().strip();
        if (slug.isEmpty()) {
            return LlmModelCheck.unavailable("No model is configured.");
        }
        // OpenRouter names models vendor/model, and the catalogue path takes the two halves
        // separately. A slug with no slash cannot address it — that is the shape of an Ollama model
        // name left behind by a provider switch, and saying so beats a 404 the operator would read
        // as a routing refusal.
        int slash = slug.indexOf('/');
        if (slash <= 0 || slash == slug.length() - 1) {
            return LlmModelCheck.unavailable(
                "\"" + slug + "\" is not an OpenRouter slug — they are named vendor/model, "
                    + "for example openai/gpt-4o-mini.");
        }
        String author = slug.substring(0, slash);
        // Everything after the first slash: a slug may carry a variant suffix of its own.
        String name = slug.substring(slash + 1);

        try {
            String url = LlmHttpSupport.v1Url(claudeConfig, "model/" + encode(author) + "/" + encode(name));
            HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(LOOKUP_TIMEOUT_SECONDS))
                .GET();
            if (claudeConfig.isApiKeyConfigured()) {
                request.header("Authorization", "Bearer " + claudeConfig.getApiKey());
            }

            HttpResponse<String> response =
                httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return LlmModelCheck.unavailable("OpenRouter answered HTTP " + response.statusCode()
                    + " for \"" + slug + "\".");
            }
            return parse(objectMapper.readTree(response.body()), slug);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return LlmModelCheck.unavailable("Interrupted while reading the model catalogue.");
        } catch (Exception e) {
            log.debug("Model catalogue lookup failed for {}", slug, e);
            return LlmModelCheck.unavailable("Could not read the model catalogue: "
                + SqlErrorClassifier.explain(e));
        }
    }

    /**
     * Turns the catalogue entry into the verdicts the Settings page renders.
     *
     * <p>Package-private so the parsing can be exercised against recorded bodies: the wire shape
     * comes from OpenRouter's published schema, and the field this application would most easily
     * get wrong is one whose absence is indistinguishable from a negative answer.
     */
    LlmModelCheck parse(JsonNode root, String requestedSlug) {
        // The endpoint wraps its payload in `data`; tolerate both shapes rather than depending on
        // an envelope the schema is free to change around a stable object.
        JsonNode model = root.has("data") ? root.path("data") : root;
        if (!model.isObject()) {
            return LlmModelCheck.unavailable("OpenRouter returned no model entry for \""
                + requestedSlug + "\".");
        }

        String id = text(model, "id", requestedSlug);
        String name = text(model, "name", null);
        Long contextLength = positiveLong(model.path("context_length"));

        JsonNode outputModalities = model.path("architecture").path("output_modalities");
        // Absent or empty means the catalogue did not say. It must not read as "this model emits
        // nothing", which is the same claim as "this model cannot be used" — a refusal invented
        // out of a missing field.
        Boolean emitsText = outputModalities.isArray() && !outputModalities.isEmpty()
            ? contains(outputModalities, "text")
            : null;

        JsonNode parameters = model.path("supported_parameters");
        SchemaSupport schemaSupport = gradeSchemaSupport(parameters);

        JsonNode reasoning = model.path("reasoning");
        // Omitted for a model that does not reason at all, which is the common case and is not the
        // same statement as "reasoning is optional here".
        Boolean reasoningMandatory = reasoning.isObject() && reasoning.path("mandatory").isBoolean()
            ? reasoning.path("mandatory").asBoolean()
            : null;

        long promptBudgetTokens = estimatedPromptTokens();
        Boolean fits = contextLength == null ? null : promptBudgetTokens <= contextLength;

        return new LlmModelCheck(id, name, contextLength, emitsText, schemaSupport,
            reasoningMandatory, promptBudgetTokens, fits, null);
    }

    /** See {@link SchemaSupport} — the third value is the whole reason this is not a boolean. */
    private static SchemaSupport gradeSchemaSupport(JsonNode parameters) {
        if (!parameters.isArray() || parameters.isEmpty()) {
            return SchemaSupport.UNKNOWN;
        }
        boolean responseFormat = contains(parameters, "response_format");
        boolean structuredOutputs = contains(parameters, "structured_outputs");
        if (structuredOutputs) {
            return SchemaSupport.CONSTRAINED;
        }
        return responseFormat ? SchemaSupport.ACCEPTED_UNCONSTRAINED : SchemaSupport.UNSUPPORTED;
    }

    /**
     * What one Process Mining prompt claims of the window, as a floor.
     *
     * <p>Both halves count, and forgetting the second is the easy mistake: the answer is generated
     * into the same window as the prompt, so a budget that fits exactly leaves the model no room to
     * reply. Ollama's response to an over-long prompt is to drop the oldest messages and log it at
     * debug — nothing on screen, nothing in a default log — which is why this is worth stating
     * before a call rather than diagnosing after one.
     */
    long estimatedPromptTokens() {
        long promptTokens = (long) Math.ceil(
            processMiningConfig.getPromptCharBudget() / (double) CHARS_PER_TOKEN);
        return promptTokens + Math.max(0, claudeConfig.getMaxTokens());
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode element : array) {
            if (element.isTextual() && element.asText().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : fallback;
    }

    /** {@code context_length} is nullable in the schema, and 0 would be a window, not an absence. */
    private static Long positiveLong(JsonNode node) {
        return node.isNumber() && node.asLong() > 0 ? node.asLong() : null;
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
