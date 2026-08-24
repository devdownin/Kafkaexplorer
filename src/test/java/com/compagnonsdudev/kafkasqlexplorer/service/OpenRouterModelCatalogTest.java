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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The catalogue read cannot be exercised end to end here — it reaches openrouter.ai — so what these
 * tests pin is the half that would go quietly wrong: the parse. Every field this record carries can
 * be absent from a real answer, and the failure mode throughout is the same one this codebase keeps
 * removing: an absent fact rendered as a negative one.
 */
class OpenRouterModelCatalogTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ClaudeConfig claudeConfig = new ClaudeConfig();
    private final ProcessMiningConfig processMiningConfig = new ProcessMiningConfig();
    private final OpenRouterModelCatalog catalog =
        new OpenRouterModelCatalog(claudeConfig, processMiningConfig);

    private JsonNode json(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void readsTheCapabilitiesOfAFullyDescribedModel() {
        LlmModelCheck check = catalog.parse(json("""
            {"data": {
               "id": "openai/gpt-4o-mini",
               "name": "GPT-4o mini",
               "context_length": 128000,
               "architecture": {"input_modalities": ["text"], "output_modalities": ["text"]},
               "supported_parameters": ["max_tokens", "response_format", "structured_outputs"]
            }}
            """), "openai/gpt-4o-mini");

        assertNull(check.error());
        assertEquals("openai/gpt-4o-mini", check.id());
        assertEquals("GPT-4o mini", check.name());
        assertEquals(128000L, check.contextLength());
        assertEquals(Boolean.TRUE, check.emitsText());
        assertEquals(SchemaSupport.CONSTRAINED, check.schemaSupport());
    }

    /** The envelope is not part of the contract worth depending on; the object inside is. */
    @Test
    void readsAnEntryThatArrivesWithoutTheDataEnvelope() {
        LlmModelCheck check = catalog.parse(json("""
            {"id": "openai/gpt-4o-mini", "context_length": 128000}
            """), "openai/gpt-4o-mini");

        assertEquals(128000L, check.contextLength());
    }

    /**
     * The whole reason {@link SchemaSupport} is not a boolean. A model listing
     * {@code response_format} without {@code structured_outputs} accepts the field and ignores the
     * schema — no 4xx, so the client's per-model latch never fires and the deployment believes
     * decoding is constrained when it is not.
     */
    @Test
    void tellsAnAcceptedButUnconstrainedSchemaFromASupportedOne() {
        LlmModelCheck accepted = catalog.parse(json("""
            {"id": "x/y", "supported_parameters": ["response_format", "temperature"]}
            """), "x/y");
        assertEquals(SchemaSupport.ACCEPTED_UNCONSTRAINED, accepted.schemaSupport());

        LlmModelCheck unsupported = catalog.parse(json("""
            {"id": "x/y", "supported_parameters": ["temperature"]}
            """), "x/y");
        assertEquals(SchemaSupport.UNSUPPORTED, unsupported.schemaSupport());
    }

    /** An unreported parameter list is not a model that supports nothing. */
    @Test
    void anAbsentParameterListIsUnknownRatherThanUnsupported() {
        assertEquals(SchemaSupport.UNKNOWN,
            catalog.parse(json("{\"id\": \"x/y\"}"), "x/y").schemaSupport());
        assertEquals(SchemaSupport.UNKNOWN,
            catalog.parse(json("{\"id\": \"x/y\", \"supported_parameters\": []}"), "x/y")
                .schemaSupport());
    }

    /**
     * The finding that makes this worth asking before a call: a slug pointing at an embeddings,
     * rerank or speech model cannot answer a Process Mining prompt, and the gateway reports that
     * with a status that sends the operator to check the model name instead.
     */
    @Test
    void reportsAModelThatDoesNotEmitText() {
        LlmModelCheck check = catalog.parse(json("""
            {"id": "openai/text-embedding-3-small",
             "architecture": {"output_modalities": ["embeddings"]}}
            """), "openai/text-embedding-3-small");

        assertEquals(Boolean.FALSE, check.emitsText());
    }

    /** Absent modalities must not read as "this model emits nothing", which is a refusal. */
    @Test
    void unreportedModalitiesAreNullRatherThanFalse() {
        assertNull(catalog.parse(json("{\"id\": \"x/y\"}"), "x/y").emitsText());
        assertNull(catalog.parse(
            json("{\"id\": \"x/y\", \"architecture\": {\"output_modalities\": []}}"), "x/y")
            .emitsText());
    }

    /**
     * Mandatory reasoning eats {@code claude.max-tokens} on every call by construction. A model
     * that publishes no reasoning block is the ordinary case and is not the same statement.
     */
    @Test
    void tellsMandatoryReasoningFromAModelThatDoesNotReason() {
        assertEquals(Boolean.TRUE, catalog.parse(json("""
            {"id": "x/y", "reasoning": {"mandatory": true}}
            """), "x/y").reasoningMandatory());
        assertEquals(Boolean.FALSE, catalog.parse(json("""
            {"id": "x/y", "reasoning": {"mandatory": false}}
            """), "x/y").reasoningMandatory());
        assertNull(catalog.parse(json("{\"id\": \"x/y\"}"), "x/y").reasoningMandatory());
    }

    /**
     * The comparison the whole third suggestion is about — and the half that is easy to forget is
     * that the answer is generated into the same window, so {@code max-tokens} counts too.
     */
    @Test
    void comparesThePromptBudgetAgainstTheWindowIncludingTheAnswer() {
        processMiningConfig.setPromptCharBudget(120_000);
        claudeConfig.setMaxTokens(4096);
        // 120 000 / 4 = 30 000 prompt tokens, plus 4 096 for the answer.
        assertEquals(34_096L, catalog.estimatedPromptTokens());

        LlmModelCheck roomy = catalog.parse(json("""
            {"id": "x/y", "context_length": 128000}
            """), "x/y");
        assertEquals(Boolean.TRUE, roomy.promptBudgetFits());
        assertEquals(34_096L, roomy.promptBudgetTokens());

        LlmModelCheck cramped = catalog.parse(json("""
            {"id": "x/y", "context_length": 8192}
            """), "x/y");
        assertEquals(Boolean.FALSE, cramped.promptBudgetFits());
    }

    /** An unknown window yields no verdict — a floor cannot be computed against nothing. */
    @Test
    void anUnknownWindowGivesNoBudgetVerdict() {
        LlmModelCheck check = catalog.parse(json("{\"id\": \"x/y\"}"), "x/y");
        assertNull(check.contextLength());
        assertNull(check.promptBudgetFits());
        assertNotNull(check.promptBudgetTokens(),
            "what we claim is known even when what it is compared against is not");
    }

    /** {@code context_length: null} is in the published schema, and 0 is not a window. */
    @Test
    void aNullOrZeroWindowIsAnAbsentOne() {
        assertNull(catalog.parse(json("{\"id\": \"x/y\", \"context_length\": null}"), "x/y")
            .contextLength());
        assertNull(catalog.parse(json("{\"id\": \"x/y\", \"context_length\": 0}"), "x/y")
            .contextLength());
    }

    /** A body that is not a model entry is an unavailable answer, never an empty verdict. */
    @Test
    void aBodyThatIsNotAModelEntryIsReportedAsUnavailable() {
        LlmModelCheck check = catalog.parse(json("{\"data\": null}"), "x/y");
        assertNotNull(check.error());
        assertEquals(SchemaSupport.UNKNOWN, check.schemaSupport());
    }

    /**
     * A slug with no slash is the shape an Ollama model name leaves behind after a provider
     * switch. It cannot address the catalogue path, and the gateway answers it with the same 404
     * it uses for a routing refusal — so it is named here instead.
     */
    @Test
    void refusesASlugThatIsNotVendorSlashModel() {
        claudeConfig.setProvider(ClaudeConfig.Provider.OPENROUTER);
        claudeConfig.setModel("qwen3:4b");

        LlmModelCheck check = catalog.describeConfiguredModel();
        assertNotNull(check.error());
        assertTrue(check.error().contains("vendor/model"),
            "the message has to name the shape that would work: " + check.error());
    }

    /**
     * The slug becomes a URL path and a probe may name it, so its shape is validated rather than
     * merely split. Percent-encoding is not enough on its own: {@code URLEncoder} leaves a dot
     * untouched, so a segment of {@code ..} would survive into a path something downstream may
     * normalise.
     */
    @Test
    void refusesASlugThatCouldTraverseTheCataloguePath() {
        claudeConfig.setProvider(ClaudeConfig.Provider.OPENROUTER);

        for (String hostile : new String[] {"../admin/keys", "openai/../../admin", "./x/y", "a//b"}) {
            claudeConfig.setModel(hostile);
            LlmModelCheck check = catalog.describeConfiguredModel();
            assertNotNull(check.error(), "should have been refused: " + hostile);
            assertTrue(check.error().contains("vendor/model"), check.error());
        }
    }

    /**
     * The per-key list disambiguates a refusal, and it is only allowed to do so when the answer is
     * conclusive. A truncated page that does not contain the slug is a page we did not finish
     * reading, not a model the key lacks — reporting that as a restriction would manufacture the
     * false negative this whole integration is written against.
     */
    @Test
    void aModelMissingFromATruncatedEntitlementListIsNotReportedAsRestricted() {
        // Exercised through parse-level reasoning rather than the network: what is pinned is that
        // the record can carry the three states at all, and that the default is the unasked one.
        LlmModelCheck unasked = catalog.parse(json("{\"id\": \"a/b\"}"), "a/b");
        assertNull(unasked.availableToKey(),
            "the question is not asked on a successful lookup");

        assertNull(LlmModelCheck.unavailable("nope").availableToKey());
        assertEquals(Boolean.FALSE,
            LlmModelCheck.unavailable("nope").withAvailability(false).availableToKey());
        assertNull(LlmModelCheck.unavailable("nope").withAvailability(null).availableToKey(),
            "could-not-establish stays null rather than collapsing to false");
    }

    /** Attaching an availability verdict must not disturb anything else the check carries. */
    @Test
    void attachingAvailabilityKeepsTheRestOfTheCheck() {
        LlmModelCheck check = catalog.parse(json("""
            {"id": "a/b", "name": "A B", "context_length": 8000,
             "architecture": {"output_modalities": ["text"]},
             "supported_parameters": ["structured_outputs"]}
            """), "a/b").withAvailability(true);

        assertEquals("a/b", check.id());
        assertEquals("A B", check.name());
        assertEquals(8000L, check.contextLength());
        assertEquals(Boolean.TRUE, check.emitsText());
        assertEquals(SchemaSupport.CONSTRAINED, check.schemaSupport());
        assertEquals(Boolean.TRUE, check.availableToKey());
    }

    /** The shapes OpenRouter really publishes have to keep working, variants included. */
    @Test
    void acceptsTheSlugShapesOpenRouterActuallyUses() {
        for (String slug : new String[] {
                "openai/gpt-4o-mini", "meta-llama/llama-3.1-8b-instruct:free",
                "qwen/qwen3-4b", "google/gemini-2.0-flash-001"}) {
            claudeConfig.setProvider(ClaudeConfig.Provider.OPENROUTER);
            claudeConfig.setModel(slug);
            LlmModelCheck check = catalog.describeConfiguredModel();
            // It cannot reach the network here, so what is asserted is that it got *past* the shape
            // check — the refusal below is the one this test is about, and it must not fire.
            assertFalse(check.error() != null && check.error().contains("vendor/model"),
                "wrongly refused a real slug: " + slug + " — " + check.error());
        }
    }

    /**
     * The lookup is a vendor-specific path, so it is only made against the vendor's own host —
     * the same rule as the attribution headers, and deliberately not the rule the routing policy
     * follows, which must survive a proxy because it carries a guarantee.
     */
    @Test
    void isNotAttemptedAgainstAnEndpointThatIsMerelyNamedOpenRouter() {
        claudeConfig.setProvider(ClaudeConfig.Provider.OPENROUTER);
        claudeConfig.setBaseUrl("https://egress.internal.example.com/openrouter/v1");
        assertFalse(catalog.isSupported());

        LlmModelCheck check = catalog.describeConfiguredModel();
        assertNotNull(check.error());
        assertNull(check.emitsText(), "an unasked question has no answer");
    }

    // ─── The shortlist ──────────────────────────────────────────────────────────────────────

    @Test
    void readsAShortlistRowWithItsPriceExpressedPerMillionTokens() {
        LlmModelShortlist list = catalog.parseShortlist(json("""
            {"data": [
              {"id": "openai/gpt-4o-mini",
               "name": "GPT-4o mini",
               "context_length": 128000,
               "supported_parameters": ["response_format", "structured_outputs"],
               "pricing": {"prompt": "0.00000015", "completion": "0.0000006"}}
            ]}
            """), List.of("cheapest first"));

        assertTrue(list.available());
        assertEquals(1, list.models().size());
        LlmModelOption option = list.models().get(0);
        assertEquals("openai/gpt-4o-mini", option.id());
        assertEquals(SchemaSupport.CONSTRAINED, option.schemaSupport());
        // Published per token; nobody reads 0.00000015, so it travels per million.
        assertEquals(0.15d, option.promptPriceUsdPerMillion(), 1e-9);
        assertEquals(0.60d, option.completionPriceUsdPerMillion(), 1e-9);
    }

    /**
     * The projection, and the arithmetic behind it: the prompt half of the budget at the prompt
     * price, plus the whole answer allowance at the completion price. It is a projection and not a
     * measurement — every other money figure here is read from the provider — which is why the
     * record and the UI both say so.
     */
    @Test
    void projectsWhatOneWindowWouldCost() {
        processMiningConfig.setPromptCharBudget(120_000);
        claudeConfig.setMaxTokens(4096);
        // 30 000 prompt tokens at $0.15/M, 4 096 answer tokens at $0.60/M.
        double expected = 30_000 * 0.00000015d + 4_096 * 0.0000006d;

        LlmModelShortlist list = catalog.parseShortlist(json("""
            {"data": [{"id": "a/b", "pricing": {"prompt": "0.00000015", "completion": "0.0000006"}}]}
            """), List.of());

        assertEquals(expected, list.models().get(0).projectedCostUsd(), 1e-12);
    }

    /** Half a published price is not a cheaper model — it is an unpriced one. */
    @Test
    void refusesToProjectFromHalfAPrice() {
        LlmModelShortlist list = catalog.parseShortlist(json("""
            {"data": [
              {"id": "a/b", "pricing": {"prompt": "0.000001"}},
              {"id": "c/d"}
            ]}
            """), List.of());

        assertNull(list.models().get(0).projectedCostUsd());
        assertNull(list.models().get(0).completionPriceUsdPerMillion());
        assertNull(list.models().get(1).projectedCostUsd());
    }

    /** A free model prices at 0, which is a measurement — it must not read as "unpriced". */
    @Test
    void aFreeModelIsPricedAtZeroRatherThanUnpriced() {
        LlmModelShortlist list = catalog.parseShortlist(json("""
            {"data": [{"id": "a/b:free", "pricing": {"prompt": "0", "completion": "0"}}]}
            """), List.of());

        assertEquals(0.0d, list.models().get(0).projectedCostUsd());
        assertEquals(0.0d, list.models().get(0).promptPriceUsdPerMillion());
    }

    /** A price this application cannot read is an absent price, never a free model. */
    @Test
    void anUnreadablePriceIsAbsentRatherThanFree() {
        LlmModelShortlist list = catalog.parseShortlist(json("""
            {"data": [{"id": "a/b", "pricing": {"prompt": "n/a", "completion": "0.000001"}}]}
            """), List.of());

        assertNull(list.models().get(0).promptPriceUsdPerMillion());
        assertNull(list.models().get(0).projectedCostUsd());
    }

    /** A row nothing could be selected from is dropped rather than rendered as a blank choice. */
    @Test
    void skipsAnEntryWithNoSlug() {
        LlmModelShortlist list = catalog.parseShortlist(json("""
            {"data": [{"name": "nameless"}, {"id": "a/b"}]}
            """), List.of());

        assertEquals(1, list.models().size());
        assertEquals("a/b", list.models().get(0).id());
    }

    /**
     * "We could not ask" and "nothing matches" are different answers, and only the second says
     * anything about the catalogue — the same rule the single-model check follows.
     */
    @Test
    void tellsAnEmptyCatalogueFromAnUnreadableOne() {
        LlmModelShortlist empty = catalog.parseShortlist(json("{\"data\": []}"), List.of());
        assertTrue(empty.available());
        assertTrue(empty.models().isEmpty());
        assertNull(empty.error());

        LlmModelShortlist broken = catalog.parseShortlist(json("{\"data\": {}}"), List.of());
        assertFalse(broken.available());
        assertNotNull(broken.error());
    }

    /** A filtered view presented as "the models" is the same lie as a silently truncated list. */
    @Test
    void carriesTheCriteriaItWasFilteredBy() {
        LlmModelShortlist list = catalog.parseShortlist(json("{\"data\": []}"),
            List.of("emits text", "supports structured outputs"));
        assertEquals(List.of("emits text", "supports structured outputs"), list.criteria());
    }

    @Test
    void doesNotOfferAShortlistForAProviderWithNoCatalogue() {
        claudeConfig.setProvider(ClaudeConfig.Provider.OLLAMA);
        LlmModelShortlist list = catalog.shortlist(claudeConfig, false, 20);
        assertFalse(list.available());
        assertNotNull(list.error());
    }

    @Test
    void isNotAttemptedForAnyOtherProvider() {
        claudeConfig.setProvider(ClaudeConfig.Provider.OLLAMA);
        assertFalse(catalog.isSupported());
    }
}
