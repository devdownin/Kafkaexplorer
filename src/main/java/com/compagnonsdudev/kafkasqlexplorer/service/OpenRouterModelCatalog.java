// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.ProcessMiningConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.LlmModelCheck;
import com.compagnonsdudev.kafkasqlexplorer.domain.LlmModelOption;
import com.compagnonsdudev.kafkasqlexplorer.domain.LlmModelShortlist;
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
import java.util.ArrayList;
import java.util.List;

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

    /**
     * A shortlist is meant to be read, not scrolled. The gateway sorts server-side, so asking for
     * more would only move the choosing back into the browser — which is the problem this replaces.
     */
    private static final int MAX_SHORTLIST = 50;

    /**
     * What may address the catalogue path. Two or more segments, each beginning with a letter or a
     * digit — which is the part that matters, since it is what a `.` or `..` segment cannot satisfy.
     * Colons are allowed inside a segment: OpenRouter carries variants that way (`…:free`).
     */
    private static final java.util.regex.Pattern SLUG =
        java.util.regex.Pattern.compile("[A-Za-z0-9][\\w.:-]*(/[A-Za-z0-9][\\w.:-]*)+");

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
        return isSupported(claudeConfig);
    }

    /** Same question about a candidate configuration the operator has not committed to. */
    public boolean isSupported(ClaudeConfig config) {
        return config.isOpenRouterEndpoint();
    }

    /**
     * Looks the configured model up, or explains why it could not.
     *
     * @return never {@code null}; an unusable answer comes back as
     *         {@link LlmModelCheck#unavailable}
     */
    public LlmModelCheck describeConfiguredModel() {
        return describeModel(claudeConfig);
    }

    /**
     * The same read against a candidate configuration — a model typed into the Settings form and
     * not yet applied. The budget comparison still uses <em>this deployment's</em> prompt budget
     * and {@code max-tokens}, because those are what an analysis would actually send; only the
     * endpoint and the slug are the candidate's.
     */
    public LlmModelCheck describeModel(ClaudeConfig config) {
        if (!isSupported(config)) {
            return LlmModelCheck.unavailable(
                "Model capabilities are published by OpenRouter; this endpoint is "
                    + config.getResolvedBaseUrl() + ".");
        }
        String slug = config.getModel() == null ? "" : config.getModel().strip();
        if (slug.isEmpty()) {
            return LlmModelCheck.unavailable("No model is configured.");
        }
        // OpenRouter names models vendor/model, and the catalogue path takes the two halves
        // separately. A slug with no slash cannot address it — that is the shape of an Ollama model
        // name left behind by a provider switch, and saying so beats a 404 the operator would read
        // as a routing refusal.
        //
        // The shape is *validated*, not merely split, because this string becomes a URL path and a
        // probe may name it: every segment has to start alphanumeric, which is what rules out `.`
        // and `..` reaching the path a client or a proxy might then normalise. Percent-encoding
        // alone does not — `URLEncoder` leaves a dot untouched.
        if (!SLUG.matcher(slug).matches()) {
            return LlmModelCheck.unavailable(
                "\"" + slug + "\" is not an OpenRouter slug — they are named vendor/model, "
                    + "for example openai/gpt-4o-mini.");
        }
        int slash = slug.indexOf('/');
        String author = slug.substring(0, slash);
        // Everything after the first slash: a slug may carry a variant suffix of its own.
        String name = slug.substring(slash + 1);

        try {
            String url = LlmHttpSupport.v1Url(config, "model/" + encode(author) + "/" + encode(name));
            HttpResponse<String> response = get(config, url);
            if (response.statusCode() / 100 != 2) {
                return LlmModelCheck.unavailable("OpenRouter answered HTTP " + response.statusCode()
                    + " for \"" + slug + "\".");
            }
            return parse(objectMapper.readTree(response.body()), slug);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return LlmModelCheck.unavailable("Interrupted while reading the model catalogue.");
        } catch (Exception e) {
            log.debug("Model catalogue lookup failed for {}", FlinkSqlService.sanitizeForLog(slug), e);
            return LlmModelCheck.unavailable("Could not read the model catalogue: "
                + SqlErrorClassifier.explain(e));
        }
    }

    /**
     * The models that can do this application's job, cheapest first.
     *
     * <p>One request, not a download of the catalogue: OpenRouter filters and sorts server-side, so
     * what comes back is already the shortlist. Every filter is a fact this application knows about
     * itself — it needs a model that emits text, that constrains its answers to a schema, and whose
     * window holds {@link #estimatedPromptTokens()} — which is what turns choosing a model from
     * recalling a slug into recognising one.
     *
     * @param includeUnconstrained widen the list to models without schema support. Off by default
     *        because a constrained answer is what the pipeline is built on, and on by request
     *        because the client degrades gracefully without one — that is the operator's call, not
     *        this method's.
     */
    public LlmModelShortlist shortlist(ClaudeConfig config, boolean includeUnconstrained, int limit) {
        if (!isSupported(config)) {
            return LlmModelShortlist.unavailable(
                "OpenRouter is the only configured provider that publishes a model catalogue; "
                    + "this endpoint is " + config.getResolvedBaseUrl() + ".");
        }
        long needed = estimatedPromptTokens();
        int bounded = Math.max(1, Math.min(MAX_SHORTLIST, limit));

        List<String> criteria = new ArrayList<>();
        criteria.add("emits text");
        if (!includeUnconstrained) {
            criteria.add("supports structured outputs");
        }
        criteria.add("context of at least " + needed + " tokens (this deployment's prompt budget "
            + "plus its answer, on the same optimistic estimate — a floor)");
        criteria.add("cheapest first");

        StringBuilder query = new StringBuilder("models?output_modalities=text")
            .append("&context=").append(needed)
            .append("&sort=pricing-low-to-high")
            .append("&limit=").append(bounded);
        if (!includeUnconstrained) {
            query.append("&supported_parameters=structured_outputs");
        }

        try {
            HttpResponse<String> response = get(config, LlmHttpSupport.v1Url(config, query.toString()));
            if (response.statusCode() / 100 != 2) {
                return LlmModelShortlist.unavailable(
                    "OpenRouter answered HTTP " + response.statusCode() + " for the model list.");
            }
            return parseShortlist(objectMapper.readTree(response.body()), criteria);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return LlmModelShortlist.unavailable("Interrupted while reading the model catalogue.");
        } catch (Exception e) {
            log.debug("Model shortlist lookup failed", e);
            return LlmModelShortlist.unavailable("Could not read the model catalogue: "
                + SqlErrorClassifier.explain(e));
        }
    }

    /** Package-private for the same reason {@link #parse} is: the wire shape is the risky half. */
    LlmModelShortlist parseShortlist(JsonNode root, List<String> criteria) {
        JsonNode data = root.has("data") ? root.path("data") : root;
        if (!data.isArray()) {
            return LlmModelShortlist.unavailable("OpenRouter returned no model list.");
        }
        List<LlmModelOption> models = new ArrayList<>();
        for (JsonNode model : data) {
            LlmModelOption option = toOption(model);
            if (option != null) {
                models.add(option);
            }
        }
        return new LlmModelShortlist(true, List.copyOf(models), List.copyOf(criteria), null);
    }

    /** {@code null} for an entry with no usable id — a row nothing could be selected from. */
    private LlmModelOption toOption(JsonNode model) {
        String id = text(model, "id", null);
        if (id == null) {
            return null;
        }
        JsonNode pricing = model.path("pricing");
        // Published as USD *per token*, as decimal strings — so they are tiny, and rendering them
        // per million is the only form anybody reads. Parsed leniently: a price this application
        // cannot read is an absent price, never a free model.
        Double promptPerToken = price(pricing, "prompt");
        Double completionPerToken = price(pricing, "completion");

        JsonNode reasoning = model.path("reasoning");
        Boolean reasoningMandatory = reasoning.isObject() && reasoning.path("mandatory").isBoolean()
            ? reasoning.path("mandatory").asBoolean()
            : null;

        return new LlmModelOption(
            id,
            text(model, "name", null),
            positiveLong(model.path("context_length")),
            gradeSchemaSupport(model.path("supported_parameters")),
            reasoningMandatory,
            perMillion(promptPerToken),
            perMillion(completionPerToken),
            projectedCost(promptPerToken, completionPerToken));
    }

    /**
     * What one Process Mining window would cost on this model.
     *
     * <p>A <strong>projection</strong>, and the word is carried all the way to the screen. Every
     * other money figure in this application is <em>read</em> from the provider's own accounting
     * precisely because no price table lives here; this one is published prices multiplied by an
     * estimate, on the same optimistic token floor as everything else, so it can understate. It
     * earns its place because the alternative at pick time is no idea at all — not because it is
     * the same kind of number as {@code LlmUsage.costUsd}.
     *
     * <p>{@code null} as soon as either half is unpublished: half a price is not a cheaper model.
     */
    private Double projectedCost(Double promptPerToken, Double completionPerToken) {
        if (promptPerToken == null || completionPerToken == null) {
            return null;
        }
        long answerTokens = Math.max(0, claudeConfig.getMaxTokens());
        long promptTokens = Math.max(0, estimatedPromptTokens() - answerTokens);
        return promptTokens * promptPerToken + answerTokens * completionPerToken;
    }

    private static Double perMillion(Double perToken) {
        return perToken == null ? null : perToken * 1_000_000d;
    }

    /** Prices arrive as decimal strings; a negative or unparseable one is an absent price. */
    private static Double price(JsonNode pricing, String field) {
        JsonNode value = pricing.path(field);
        try {
            if (value.isNumber()) {
                return value.asDouble() >= 0 ? value.asDouble() : null;
            }
            if (value.isTextual() && !value.asText().isBlank()) {
                double parsed = Double.parseDouble(value.asText().strip());
                return parsed >= 0 ? parsed : null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    /** One GET against the catalogue, carrying the key when there is one. */
    private HttpResponse<String> get(ClaudeConfig config, String url)
            throws java.io.IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(LOOKUP_TIMEOUT_SECONDS))
            .GET();
        if (config.isApiKeyConfigured()) {
            request.header("Authorization", "Bearer " + config.getApiKey());
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
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
