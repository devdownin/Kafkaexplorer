// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "claude")
public class ClaudeConfig {

    public enum Provider { ANTHROPIC, OPENAI_COMPATIBLE, OLLAMA, OPENROUTER, SPECTRA }

    /**
     * Whether to constrain the model's answer to a JSON Schema rather than merely asking for JSON
     * in the prompt.
     *
     * <p>{@link #AUTO} is deliberately not "on everywhere". {@code OPENAI_COMPATIBLE} points at an
     * endpoint we know nothing about — llama.cpp, vLLM, LM Studio, a corporate gateway — and an
     * unrecognised {@code response_format} is answered with a 400 by some of them. Turning a
     * working deployment into a failing one to gain a guarantee it may not need is the wrong
     * default, so AUTO enables it where support is known ({@code ANTHROPIC}, {@code OLLAMA},
     * {@code OPENROUTER}) and {@link #ON} is there for an operator who knows their gateway supports
     * it. Either way the client degrades on its own if the endpoint refuses the field.
     *
     * <p>{@code OPENROUTER} is in the AUTO set for a reason worth stating, because it is the one
     * provider here whose answer to "do you support schemas" is <em>per model</em> rather than per
     * endpoint: OpenRouter routes to hundreds of models behind one base URL and one key, and only
     * some of them (and only some of the upstream providers serving them) implement
     * {@code response_format}. That is safe only because
     * {@link com.compagnonsdudev.kafkasqlexplorer.service.OpenAiCompatibleLlmClient} remembers a
     * refusal <em>against the model that refused</em>: a schema-less model costs one extra request,
     * once, and does not disable constrained decoding for the next model chosen in Settings.
     */
    public enum StructuredOutput { AUTO, ON, OFF }

    /**
     * Whether OpenRouter may route to upstream providers that retain or train on what is sent.
     *
     * <p>This is the one setting here that turns a privacy warning into a privacy property. The
     * Settings page can say a deployment is "remote" by reading the address, and no further: what
     * the upstream vendor then does with a Kafka message digest is outside anything this
     * application can observe. OpenRouter can enforce it at the routing layer, so {@link #DENY}
     * asks for exactly that.
     *
     * <p>It is the default, and the failure mode is accepted on purpose: a model served only by
     * data-collecting providers stops being routable, and an error naming the policy is the right
     * outcome when the alternative is message content silently becoming training data. Set
     * {@link #ALLOW} to widen the choice of models back.
     */
    public enum DataCollection { ALLOW, DENY }

    /**
     * The provider a deployment that configures nothing gets.
     *
     * <p>{@code OPENROUTER}, and it used to be {@code OLLAMA} pointed at
     * {@code http://localhost:11434/v1}. That default only ever worked in one situation — a
     * developer running this application outside a container with Ollama installed on the same
     * machine — because inside every image published here {@code localhost} is the container, where
     * no Ollama runs, so the shipped default answered a connection refused to itself. A default is
     * what the largest number of people meet first, and OpenRouter is reachable from anywhere with
     * a key and one line of configuration.
     *
     * <p>It is a hosted gateway, so what it costs is stated rather than implied: message digests
     * leave the host, and both the Settings banner and the Process Mining page read that off the
     * resolved address (see {@link #isLocalDeployment()}) rather than off this constant. A
     * deployment that must keep everything in-house sets {@code claude.provider} to {@code OLLAMA}
     * or {@code SPECTRA} — {@code docker-compose-llm.yml} and the SpectraLLM stacks name their
     * provider explicitly and are untouched by this.
     */
    private Provider provider = Provider.OPENROUTER;
    private String apiKey = "";
    private String baseUrl = "";
    /** An OpenRouter slug, matching the default provider — cheap, current, and it supports schemas. */
    private String model = "openai/gpt-4o-mini";
    private int maxTokens = 4096;
    private int snapshotWindowSize = 100;
    private int snapshotWindowTimeoutSeconds = 30;
    /**
     * Per-call timeout for HTTP-based LLM providers (OpenAI-compatible, Ollama, SpectraLLM),
     * applied as both connect and request timeout. Local audit models can be slow, so this
     * defaults high; lower it to fail fast against a hosted endpoint.
     */
    private int requestTimeoutSeconds = 60;
    /**
     * Only used by the {@link Provider#SPECTRA} provider: when {@code true} the audit
     * question is answered with SpectraLLM's hybrid RAG retrieval over its ingested
     * corpus. Defaults to {@code false} because the Kafka audit prompt already carries
     * all the message context inline, so plain LLM generation is what we want.
     */
    private boolean useRag = false;
    /**
     * SPECTRA provider only: the SpectraLLM ChromaDB collection to retrieve from when
     * {@link #useRag} is enabled. Blank = SpectraLLM's default collection.
     */
    private String collection = "";
    private StructuredOutput structuredOutput = StructuredOutput.AUTO;
    /** OPENROUTER only — see {@link DataCollection}. Ignored by every other provider. */
    private DataCollection openrouterDataCollection = DataCollection.DENY;
    /**
     * OPENROUTER only: route only to providers implementing every parameter sent, which makes
     * structured output a routing guarantee instead of something discovered by a refusal.
     *
     * <p>Off by default, unlike its sibling above, and for the reason that governs
     * {@link StructuredOutput#AUTO}: a model whose providers do not support schemas becomes
     * <em>unroutable</em> rather than degrading, and the per-model latch cannot rescue it — the
     * refusal arrives as "no endpoints found", not as the 400 or 422 that latch keys on. Turning a
     * working deployment into a failing one to gain a guarantee it may not need is the wrong
     * default; an operator who knows their model is served with schema support can say so here.
     */
    private boolean openrouterRequireParameters = false;

    /**
     * OPENROUTER only: the most this deployment will pay a provider, in USD per million tokens.
     * {@code 0} — the default — means no ceiling.
     *
     * <p>This is the <em>enforceable</em> half of {@code session-cost-limit-usd}. That one is an
     * accumulator: it adds up what has already been spent and stops the session afterwards, which
     * is the right shape for a running total and the wrong one for a surprise — a model routed to
     * an expensive provider has already been paid for by the time the cap notices. A price ceiling
     * is refused at the routing layer, before the tokens exist, in the same way and for the same
     * reason {@code data_collection} turns a privacy warning into a property. The two are
     * complementary rather than alternatives: one bounds the rate, the other the total.
     *
     * <p>The ceiling applies to <em>both</em> published prices rather than taking a pair. Completion
     * is almost always the dearer of the two, so that is what it binds on in practice, and a single
     * number is a setting an operator can reason about — where a prompt ceiling and a completion
     * ceiling invite one to be set and the other forgotten.
     *
     * <p>Off by default on the rule its two siblings follow: a model whose providers all charge
     * more becomes <em>unroutable</em>, and that arrives as the same "no endpoints found" a
     * restrictive data policy produces. Turning a working deployment into a failing one to gain a
     * bound it may not need is not a default this application chooses on an operator's behalf.
     */
    private double openrouterMaxPriceUsdPerMillion = 0;

    /**
     * Upper bound, in USD, on what one live Process Mining session may spend before it stops
     * itself. {@code 0} — the default — means no bound.
     *
     * <p>A live session calls the model every time a window is cut, on a timer, and its SSE
     * emitter lives twelve hours: a tab left open overnight is on the order of a thousand
     * analyses. That was free while the shipped provider was a local Ollama and is a real bill
     * now that it bills per token, and nothing anywhere bounded it — the only limits in the
     * pipeline are on prompt size and window count, neither of which is money.
     *
     * <p>Off by default, deliberately. Any figure picked here would be arbitrary — the same reason
     * the audit refuses a threshold on consumer lag — and a session that stops on its own is a
     * surprise of its own kind. What makes off defensible is that the running total is now on
     * screen, so an operator can see what a configuration costs before deciding what to cap it at.
     *
     * <p>It bounds the <em>analyses</em> of one session, not the profiling call that precedes it:
     * profiling is a separate request the server does not attribute to a session. And it can only
     * be enforced where cost is reported — on a provider that prices nothing (Ollama, SpectraLLM,
     * the OpenAI API) the session says the bound cannot apply rather than counting calls it cannot
     * price, which would be a budget in name only.
     */
    private double sessionCostLimitUsd = 0.0;

    public Provider getProvider() {
        return provider;
    }

    public void setProvider(Provider provider) {
        this.provider = provider;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getResolvedBaseUrl() {
        if (baseUrl != null && !baseUrl.isBlank()) {
            return baseUrl;
        }
        return defaultBaseUrl(provider);
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public int getSnapshotWindowSize() {
        return snapshotWindowSize;
    }

    public void setSnapshotWindowSize(int snapshotWindowSize) {
        this.snapshotWindowSize = snapshotWindowSize;
    }

    public int getSnapshotWindowTimeoutSeconds() {
        return snapshotWindowTimeoutSeconds;
    }

    public void setSnapshotWindowTimeoutSeconds(int snapshotWindowTimeoutSeconds) {
        this.snapshotWindowTimeoutSeconds = snapshotWindowTimeoutSeconds;
    }

    public boolean isUseRag() {
        return useRag;
    }

    public void setUseRag(boolean useRag) {
        this.useRag = useRag;
    }

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    public StructuredOutput getStructuredOutput() {
        return structuredOutput;
    }

    public void setStructuredOutput(StructuredOutput structuredOutput) {
        this.structuredOutput = structuredOutput;
    }

    public DataCollection getOpenrouterDataCollection() {
        return openrouterDataCollection;
    }

    public void setOpenrouterDataCollection(DataCollection openrouterDataCollection) {
        this.openrouterDataCollection = openrouterDataCollection;
    }

    public double getSessionCostLimitUsd() {
        return sessionCostLimitUsd;
    }

    public void setSessionCostLimitUsd(double sessionCostLimitUsd) {
        this.sessionCostLimitUsd = sessionCostLimitUsd;
    }

    /** Whether a live session should stop itself once it has spent that much. */
    public boolean hasSessionCostLimit() {
        return sessionCostLimitUsd > 0;
    }

    public boolean isOpenrouterRequireParameters() {
        return openrouterRequireParameters;
    }

    public void setOpenrouterRequireParameters(boolean openrouterRequireParameters) {
        this.openrouterRequireParameters = openrouterRequireParameters;
    }

    public double getOpenrouterMaxPriceUsdPerMillion() {
        return openrouterMaxPriceUsdPerMillion;
    }

    public void setOpenrouterMaxPriceUsdPerMillion(double openrouterMaxPriceUsdPerMillion) {
        this.openrouterMaxPriceUsdPerMillion = openrouterMaxPriceUsdPerMillion;
    }

    /** Whether a price ceiling is in force — a non-positive value is no ceiling, not a free one. */
    public boolean hasOpenrouterPriceCeiling() {
        return openrouterMaxPriceUsdPerMillion > 0;
    }

    /**
     * Whether the configured endpoint really is OpenRouter's, and not merely named after it.
     *
     * <p>Used for the <em>attribution headers</em> and nothing else. The provider enum says which
     * dialect to speak; it does not say who answers, and {@code claude.base-url} is free text —
     * pointing the OPENROUTER provider at a corporate egress proxy is an ordinary thing to do.
     * Naming this project to somebody's internal gateway is not this application's business, and a
     * header is a courtesy that can be withheld on a guess with nothing lost.
     *
     * <p>The routing policy deliberately does <em>not</em> use this, and the asymmetry is the
     * point: that one carries a privacy restriction the operator configured, and a proxy in front
     * of OpenRouter still forwards it. Withholding it because the hostname is unfamiliar would
     * silently drop the guarantee — the failure this whole setting exists to prevent.
     */
    public boolean isOpenRouterEndpoint() {
        if (provider != Provider.OPENROUTER) {
            return false;
        }
        String host;
        try {
            host = java.net.URI.create(getResolvedBaseUrl().strip()).getHost();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (host == null) {
            return false;
        }
        host = host.toLowerCase(java.util.Locale.ROOT);
        return host.equals("openrouter.ai") || host.endsWith(".openrouter.ai");
    }

    /**
     * Whether this configuration asks the gateway to keep message content away from providers that
     * would retain it. False for every provider but OpenRouter, where the question has no answer
     * this application can enforce — the Settings banner uses it to qualify its "remote" sentence
     * rather than to replace it.
     */
    public boolean isDataRetentionRefused() {
        // Follows what the request actually carries, which is keyed on the provider — see
        // OpenAiCompatibleLlmClient.routingPolicy(). The UI must claim the restriction exactly
        // where it was sent: no more (it would be inventing one) and no less (it would hide one
        // the operator configured).
        return provider == Provider.OPENROUTER && openrouterDataCollection == DataCollection.DENY;
    }

    /** Whether this configuration should send a schema with the request — see {@link StructuredOutput}. */
    public boolean isStructuredOutputEnabled() {
        return switch (structuredOutput) {
            case ON -> true;
            case OFF -> false;
            case AUTO -> provider == Provider.ANTHROPIC
                || provider == Provider.OLLAMA
                || provider == Provider.OPENROUTER;
        };
    }

    /**
     * Whether a call can be made at all without a key. OpenRouter is a hosted gateway that answers
     * 401 to an anonymous request, so an empty key there is not "optional credentials" as it is on
     * a local Ollama — it is a deployment that cannot analyse anything, and the Process Mining page
     * says so up front rather than after the first failed window.
     */
    public boolean isApiKeyRequired() {
        return provider == Provider.ANTHROPIC || provider == Provider.OPENROUTER;
    }

    public boolean isApiKeyConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Whether the configured endpoint is on this host — and therefore whether nothing sent to the
     * model leaves the machine.
     *
     * <p>This is a privacy claim, not a convenience flag: the Process Mining page renders it as
     * "It is a loopback address, so no message content leaves this host." It used to be true by
     * <em>provider</em> — {@code provider == OLLAMA} short-circuited the check — so pointing Ollama
     * at a GPU box (a thoroughly ordinary setup: {@code CLAUDE_BASE_URL=http://gpu-box:11434/v1})
     * kept the reassurance on screen while every message digest went over the network. A claim about
     * where data goes has to be read off the address the data is actually sent to.
     */
    public boolean isLocalDeployment() {
        return isLoopbackUrl(getResolvedBaseUrl());
    }

    public String getProviderLabel() {
        return switch (provider) {
            case ANTHROPIC -> "Anthropic";
            case OPENAI_COMPATIBLE -> "OpenAI-compatible";
            case OLLAMA -> "Ollama";
            case OPENROUTER -> "OpenRouter";
            case SPECTRA -> "SpectraLLM";
        };
    }

    public static String defaultBaseUrl(Provider provider) {
        return switch (provider) {
            case ANTHROPIC -> "https://api.anthropic.com";
            case OPENAI_COMPATIBLE -> "";
            case OLLAMA -> "http://localhost:11434/v1";
            case OPENROUTER -> "https://openrouter.ai/api/v1";
            case SPECTRA -> "http://localhost:8080";
        };
    }

    /**
     * The model a deployment gets when it picks this provider and names none.
     *
     * <p>Served to the Settings page rather than restated there. The browser carried
     * {@code openai/gpt-4o-mini} twice — as initial form state and in the provider-switch
     * fallback — beside a table mirroring {@link #defaultBaseUrl}, which is the mirror-drift
     * pattern this codebase keeps removing: the day a shipped default moves, the form offers one
     * model while the engine runs another.
     *
     * <p>Empty for {@link Provider#OPENAI_COMPATIBLE}, whose endpoint we know nothing about, and
     * for {@link Provider#SPECTRA}, which serves whichever model it is configured to run and
     * ignores the field entirely. Empty means "we have nothing to propose", not "no model".
     */
    public static String defaultModel(Provider provider) {
        return switch (provider) {
            case ANTHROPIC -> "claude-3-5-sonnet-20241022";
            case OPENAI_COMPATIBLE -> "";
            case OLLAMA -> "qwen3:4b";
            case OPENROUTER -> "openai/gpt-4o-mini";
            case SPECTRA -> "";
        };
    }

    /**
     * A throw-away copy of this configuration naming a different model, for probing a candidate
     * the operator has not committed to.
     *
     * <p>{@code POST /api/config/test-llm} used to require the form to be <em>applied</em> first,
     * so trying a model repointed the running deployment and — with
     * {@code explorer.settings-persistence} on — wrote it into the settings store. Exploring and
     * committing were the same gesture, which is why nobody compared two models. A copy has no
     * such cost: nothing here is a Spring bean, nothing is published to another thread, and the
     * running configuration is untouched.
     *
     * <p><strong>Only the model is overridable, and that restriction is the security boundary.</strong>
     * An earlier draft let a probe carry its own provider, base URL and key. That turned an
     * unauthenticated request into a server-side request forgery with the response reflected back
     * to the caller — and worse, a blank key fell back to the <em>stored</em> one, so pointing a
     * probe at any host exfiltrated the operator's API key in a single call that changed no state
     * and left nothing in the settings file. Changing where this deployment sends its data is what
     * {@code POST /api/config} is for; it is a deliberate, persisted act. Trying another model
     * against the endpoint already configured is not, and it is the whole of what the Settings
     * page needs.
     *
     * <p>A blank or null model keeps this configuration's, so a probe that names nothing tests
     * exactly what is running.
     */
    public ClaudeConfig probeCopy(String overrideModel) {
        ClaudeConfig copy = new ClaudeConfig();
        copy.model = blankToNull(overrideModel) != null ? overrideModel.strip() : model;
        // Everything else is this deployment's, deliberately — the endpoint, the credential, and
        // the budgets and routing policy that describe how it calls a model rather than which one.
        // A probe that quietly used different ones would be testing something other than what an
        // analysis will do.
        copy.provider = provider;
        copy.baseUrl = baseUrl;
        copy.apiKey = apiKey;
        copy.maxTokens = maxTokens;
        copy.snapshotWindowSize = snapshotWindowSize;
        copy.snapshotWindowTimeoutSeconds = snapshotWindowTimeoutSeconds;
        copy.requestTimeoutSeconds = requestTimeoutSeconds;
        copy.useRag = useRag;
        copy.collection = collection;
        copy.structuredOutput = structuredOutput;
        copy.openrouterDataCollection = openrouterDataCollection;
        copy.openrouterRequireParameters = openrouterRequireParameters;
        copy.openrouterMaxPriceUsdPerMillion = openrouterMaxPriceUsdPerMillion;
        copy.sessionCostLimitUsd = sessionCostLimitUsd;
        return copy;
    }

    /**
     * Whether this copy names a model other than the one running — the one thing the answer of a
     * probe must state, since "the endpoint is reachable" means a different thing about a
     * candidate than about the deployment.
     */
    public boolean differsFrom(ClaudeConfig other) {
        return !java.util.Objects.equals(model, other.model);
    }

    /**
     * Whether two endpoint URLs name the same host — the test that decides whether a stored
     * credential may follow a configuration change.
     *
     * <p>The <em>host</em> is compared and nothing else, so a changed port or path is not a
     * different endpoint while a changed hostname is. A URL that will not parse counts as a
     * different host: the safe answer to "may this key follow?" is no.
     */
    public static boolean sameEndpointHost(String a, String b) {
        String hostA = hostOf(a);
        String hostB = hostOf(b);
        return hostA != null && hostA.equals(hostB);
    }

    private static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String host = java.net.URI.create(url.strip()).getHost();
            return host == null ? null : host.toLowerCase(java.util.Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Whether a URL names this host. The <em>host</em> is compared, not the URL text: a substring
     * scan answered yes for {@code https://localhost.example.com/} and for any path or query that
     * happened to contain the word — on the one flag that tells an operator their data stays put.
     * A URL that cannot be parsed is not local, since the safe answer to "am I sure this is on this
     * machine" is no.
     */
    private boolean isLoopbackUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String host;
        try {
            host = java.net.URI.create(value.strip()).getHost();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (host == null) {
            return false;
        }
        // Brackets survive getHost() for an IPv6 literal.
        host = host.toLowerCase(java.util.Locale.ROOT).replace("[", "").replace("]", "");
        return host.equals("localhost")
            || host.endsWith(".localhost")
            || host.startsWith("127.")
            || host.equals("0.0.0.0")
            || host.equals("::1")
            || host.equals("0:0:0:0:0:0:0:1");
    }
}
