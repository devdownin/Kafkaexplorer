// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.LlmResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.LlmUsage;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two things this pair of changes promises: the answer is <em>constrained</em> rather than
 * merely requested, and what the call cost is measured instead of guessed.
 *
 * <p>Driven against a real loopback HTTP server rather than a mock, because what matters here is
 * the bytes on the wire — whether {@code response_format} is present, and whether {@code usage} is
 * read back — and a mocked client would assert only that this test agrees with itself.
 */
class LlmStructuredOutputTest {

    private HttpServer server;
    private final List<String> requestBodies = new ArrayList<>();
    private final List<Map<String, List<String>>> requestHeaders = new ArrayList<>();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Starts a stub endpoint; each call answers with the next scripted response. */
    private ClaudeConfig startServer(List<StubResponse> script) throws IOException {
        AtomicInteger call = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestHeaders.add(Map.copyOf(exchange.getRequestHeaders()));
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            StubResponse stub = script.get(Math.min(call.getAndIncrement(), script.size() - 1));
            byte[] body = stub.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(stub.status(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        ClaudeConfig config = new ClaudeConfig();
        config.setProvider(ClaudeConfig.Provider.OLLAMA);
        config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        config.setModel("qwen3:4b");
        return config;
    }

    private record StubResponse(int status, String body) {
    }

    private static String okBody() {
        return "{\"choices\":[{\"message\":{\"content\":\"{}\"}}],"
            + "\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":340}}";
    }

    /** OpenRouter prices every response; this is the shape it answers with. */
    private static String pricedBody() {
        return "{\"choices\":[{\"message\":{\"content\":\"{}\"}}],"
            + "\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":340,\"cost\":0.00042}}";
    }

    private static LlmOutputSchema schema() {
        return new LlmOutputSchema("process_mining_result", LlmSchemas.processMiningResult());
    }

    @Test
    void sendsTheSchemaWhenTheProviderSupportsIt() throws Exception {
        ClaudeConfig config = startServer(List.of(new StubResponse(200, okBody())));

        new OpenAiCompatibleLlmClient(config).generateWithMeta("SYS", "USR", schema());

        JsonNode sent = new ObjectMapper().readTree(requestBodies.get(0));
        assertEquals("json_schema", sent.path("response_format").path("type").asText(),
            "the answer must be constrained, not merely requested in the prompt");
        assertEquals("process_mining_result",
            sent.path("response_format").path("json_schema").path("name").asText());
        assertTrue(sent.path("response_format").path("json_schema").path("schema")
                .path("properties").has("flowchart"),
            "the schema itself must travel, not just its name");
    }

    @Test
    void readsBackWhatTheCallCost() throws Exception {
        ClaudeConfig config = startServer(List.of(new StubResponse(200, okBody())));

        LlmResponse response = new OpenAiCompatibleLlmClient(config)
            .generateWithMeta("SYS", "USR", schema());

        LlmUsage usage = response.usage();
        assertNotNull(usage, "a call whose cost is not recorded cannot be tuned against");
        assertEquals(1200L, usage.inputTokens());
        assertEquals(340L, usage.outputTokens());
        assertEquals(1540L, usage.totalTokens());
        assertTrue(usage.durationMs() >= 0);
        assertEquals("qwen3:4b", usage.model());
    }

    /** A gateway that omits `usage` leaves the counts unknown — never zero. */
    @Test
    void reportsUnknownRatherThanZeroWhenTheProviderSaysNothing() throws Exception {
        ClaudeConfig config = startServer(List.of(new StubResponse(200,
            "{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}")));

        LlmUsage usage = new OpenAiCompatibleLlmClient(config)
            .generateWithMeta("SYS", "USR", schema()).usage();

        assertNull(usage.inputTokens(), "zero would claim the call was free");
        assertNull(usage.outputTokens());
        assertNull(usage.totalTokens(), "a half-known total is worse than none");
        assertTrue(usage.durationMs() >= 0, "the duration is measured here, so it is always real");
    }

    /**
     * The degradation that makes AUTO safe to ship: an endpoint that rejects `response_format`
     * gets one unconstrained retry rather than an error blamed on the operator's configuration.
     */
    @Test
    void retriesWithoutTheSchemaWhenTheEndpointRefusesIt() throws Exception {
        ClaudeConfig config = startServer(List.of(
            new StubResponse(400, "{\"error\":{\"message\":\"unknown field response_format\"}}"),
            new StubResponse(200, okBody())));

        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(config);
        LlmResponse first = client.generateWithMeta("SYS", "USR", schema());

        assertEquals("{}", first.text(), "the retry's answer is what the caller gets");
        assertEquals(2, requestBodies.size());
        assertTrue(requestBodies.get(0).contains("response_format"));
        assertFalse(requestBodies.get(1).contains("response_format"),
            "the retry must drop the field the endpoint refused");

        // And it must not probe again: the observation is remembered for this client's lifetime.
        client.generateWithMeta("SYS", "USR", schema());
        assertEquals(3, requestBodies.size());
        assertFalse(requestBodies.get(2).contains("response_format"),
            "an endpoint that refused once must not be probed on every later call");
    }

    /** A 4xx that has nothing to do with the schema still surfaces — it is not swallowed by the retry. */
    @Test
    void stillReportsAGenuineClientError() throws Exception {
        ClaudeConfig config = startServer(List.of(
            new StubResponse(404, "{\"error\":{\"message\":\"model not found\"}}")));

        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(config);
        RuntimeException e = assertThrows(RuntimeException.class,
            () -> client.generateWithMeta("SYS", "USR", schema()));

        assertTrue(e.getMessage().contains("model not found"), e.getMessage());
    }

    /**
     * A 4xx that cannot mean "I do not implement {@code response_format}" must not disable
     * structured output.
     *
     * <p>The latch used to fire on any 4xx, and the two that matter are 401 and 404 — a wrong key
     * and a wrong model or path. Those are configuration mistakes an operator fixes within the
     * minute, but the latch outlives the fix: {@link LlmClientProvider} fingerprints provider, base
     * URL and key, and the <em>model</em> is in none of them, so correcting a mistyped model reuses
     * this very client. The deployment then ran unconstrained for ever, silently, because of a typo
     * that had already been corrected.
     */
    @Test
    void aWrongModelDoesNotDisableStructuredOutputForGood() throws Exception {
        ClaudeConfig config = startServer(List.of(
            new StubResponse(404, "{\"error\":{\"message\":\"model not found\"}}"),
            new StubResponse(200, okBody())));

        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(config);
        assertThrows(RuntimeException.class, () -> client.generateWithMeta("SYS", "USR", schema()));
        assertEquals(1, requestBodies.size(),
            "a 404 says nothing about response_format, so there is nothing to retry without it");

        // The operator corrects the model name; the client is the same one, and must still constrain.
        client.generateWithMeta("SYS", "USR", schema());
        assertEquals(2, requestBodies.size());
        assertTrue(requestBodies.get(1).contains("response_format"),
            "structured output must survive a failure that was never about the schema");
    }

    /**
     * The body OpenRouter answers with when the failure happened one hop further on: its own status
     * over the upstream provider's payload, under {@code error.metadata}.
     */
    private static String relayedFailureBody() {
        return "{\"error\":{\"message\":\"Provider returned error\",\"code\":400,"
            + "\"metadata\":{\"raw\":\"{\\\"code\\\":400,\\\"msg\\\":\\\"bad request\\\"}\","
            + "\"provider_name\":\"AtlasCloud\",\"is_byok\":false}}}";
    }

    /**
     * A relayed upstream failure must not be reported as "the endpoint rejected the request body".
     *
     * <p>It is the one 4xx here that says nothing about the request: the gateway accepted it and
     * passed it on. Measured on a live account — {@code liquid/lfm-2.5-2.6b:free} answered 400
     * through AtlasCloud while the byte-identical body succeeded through Liquid — so the old
     * sentence sent an operator to audit a body that was provably fine, and named neither the
     * provider that failed nor the fact that another attempt may route elsewhere.
     */
    @Test
    void namesTheUpstreamProviderRatherThanBlamingTheRequest() throws Exception {
        ClaudeConfig config = startServer(List.of(new StubResponse(400, relayedFailureBody())));
        config.setProvider(ClaudeConfig.Provider.OPENROUTER);
        config.setStructuredOutput(ClaudeConfig.StructuredOutput.OFF);

        RuntimeException e = assertThrows(RuntimeException.class,
            () -> new OpenAiCompatibleLlmClient(config).generateWithMeta("SYS", "USR", schema()));

        assertTrue(e.getMessage().contains("AtlasCloud"),
            "the provider that actually failed has to be named: " + e.getMessage());
        assertFalse(e.getMessage().contains("rejected the request body"),
            "the request was accepted by the gateway and forwarded — blaming it is a false cause: "
                + e.getMessage());
        assertEquals(1, requestBodies.size(), "an upstream failure is not retried by the transport");
    }

    /**
     * ...and it must not be remembered as "this model cannot be constrained".
     *
     * <p>The latch keys on the model, which is right for a refusal the endpoint issued and wrong
     * for one it relayed: a routing gateway picks a different upstream provider whenever it likes,
     * so a provider's bad afternoon would otherwise disable structured output for that model for
     * the client's whole lifetime — silently, and long after the provider recovered. The retry
     * still happens (an upstream genuinely lacking {@code response_format} is a real case, and one
     * extra request is cheap); only the durable conclusion is withheld.
     */
    @Test
    void aRelayedUpstreamFailureIsNotRememberedAgainstTheModel() throws Exception {
        ClaudeConfig config = startServer(List.of(
            new StubResponse(400, relayedFailureBody()),
            new StubResponse(200, okBody())));
        config.setProvider(ClaudeConfig.Provider.OPENROUTER);
        config.setModel("liquid/lfm-2.5-2.6b:free");

        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(config);
        client.generateWithMeta("SYS", "USR", schema());

        assertEquals(2, requestBodies.size());
        assertTrue(requestBodies.get(0).contains("response_format"));
        assertFalse(requestBodies.get(1).contains("response_format"),
            "one unconstrained retry is still worth it — the upstream may genuinely lack the field");

        // The same model again: the schema must be sent, because nothing was established about it.
        client.generateWithMeta("SYS", "USR", schema());
        assertEquals(3, requestBodies.size());
        assertTrue(requestBodies.get(2).contains("response_format"),
            "one provider's failure is not evidence that the model cannot be constrained");
    }

    /** The detector only fires on the shape it was written for; anything else stays a plain 4xx. */
    @Test
    void readsTheRelayedProviderOnlyWhereTheGatewayReportsOne() {
        assertEquals("AtlasCloud", LlmHttpSupport.upstreamProviderOf(relayedFailureBody()));
        assertNull(LlmHttpSupport.upstreamProviderOf(
            "{\"error\":{\"message\":\"response_format is not supported\"}}"));
        assertNull(LlmHttpSupport.upstreamProviderOf("not json at all provider_name"),
            "an unparseable body must leave the diagnosis where it was");
        assertNull(LlmHttpSupport.upstreamProviderOf(null));
    }

    /**
     * An answer that never arrived because the model spent its budget thinking says so, and names
     * the setting that fixes it.
     *
     * <p>The observed shape on a small reasoning model: a 200, a well-formed body, no content, and
     * `finish_reason: "length"`. The bare "carried no message content" sent an operator to check an
     * endpoint, a model name and a key that were all correct.
     */
    @Test
    void namesTheOutputLimitWhenTheModelRanOutBeforeAnswering() throws Exception {
        ClaudeConfig config = startServer(List.of(new StubResponse(200,
            "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"\"}}],"
                + "\"usage\":{\"prompt_tokens\":900,\"completion_tokens\":8192,"
                + "\"completion_tokens_details\":{\"reasoning_tokens\":8192}}}")));

        RuntimeException e = assertThrows(RuntimeException.class,
            () -> new OpenAiCompatibleLlmClient(config).generateWithMeta("SYS", "USR", null));

        assertTrue(e.getMessage().contains("claude.max-tokens"), e.getMessage());
        assertTrue(e.getMessage().contains("8192"), "the budget it actually spent is the evidence");
        assertTrue(e.getMessage().contains("reasoning"), e.getMessage());
    }

    /**
     * ...and a gateway that reports no reason is not paraphrased into one. The counts are stated,
     * the cause is offered as the usual one, and the limit is not asserted.
     */
    @Test
    void doesNotAssertALimitTheProviderDidNotReport() throws Exception {
        ClaudeConfig config = startServer(List.of(new StubResponse(200,
            "{\"choices\":[{\"message\":{\"content\":\"   \"}}],"
                + "\"usage\":{\"prompt_tokens\":900,\"completion_tokens\":1200}}")));

        RuntimeException e = assertThrows(RuntimeException.class,
            () -> new OpenAiCompatibleLlmClient(config).generateWithMeta("SYS", "USR", null));

        assertTrue(e.getMessage().contains("1200 output token(s)"), e.getMessage());
        assertTrue(e.getMessage().contains("did not say why it stopped"), e.getMessage());
        assertFalse(e.getMessage().contains("stopped at its output limit"),
            "the provider did not report a limit, so this must not claim one");
    }

    /** 422 is the other way a gateway says "I do not understand this field". */
    @Test
    void treatsUnprocessableEntityAsASchemaRefusalToo() throws Exception {
        ClaudeConfig config = startServer(List.of(
            new StubResponse(422, "{\"error\":{\"message\":\"response_format not supported\"}}"),
            new StubResponse(200, okBody())));

        new OpenAiCompatibleLlmClient(config).generateWithMeta("SYS", "USR", schema());

        assertEquals(2, requestBodies.size());
        assertFalse(requestBodies.get(1).contains("response_format"));
    }

    @Test
    void doesNotConstrainWhenTheSettingIsOff() throws Exception {
        ClaudeConfig config = startServer(List.of(new StubResponse(200, okBody())));
        config.setStructuredOutput(ClaudeConfig.StructuredOutput.OFF);

        new OpenAiCompatibleLlmClient(config).generateWithMeta("SYS", "USR", schema());

        assertFalse(requestBodies.get(0).contains("response_format"));
    }

    /**
     * AUTO is not "on everywhere": an arbitrary OpenAI-compatible gateway is left alone, because
     * turning a working deployment into a failing one is the wrong default.
     */
    @Test
    void autoLeavesAnUnknownGatewayAlone() throws Exception {
        ClaudeConfig config = startServer(List.of(new StubResponse(200, okBody())));
        config.setProvider(ClaudeConfig.Provider.OPENAI_COMPATIBLE);

        assertFalse(config.isStructuredOutputEnabled());
        new OpenAiCompatibleLlmClient(config).generateWithMeta("SYS", "USR", schema());
        assertFalse(requestBodies.get(0).contains("response_format"));

        config.setStructuredOutput(ClaudeConfig.StructuredOutput.ON);
        assertTrue(config.isStructuredOutputEnabled(), "ON is how an operator opts a known gateway in");
    }

    /**
     * OpenRouter is in the AUTO set, and it is the one provider whose schema support is a property
     * of the <em>model</em> — one base URL and one key route to hundreds of them, only some of
     * which implement {@code response_format}.
     */
    @Test
    void autoConstrainsOnOpenRouter() throws Exception {
        ClaudeConfig config = startServer(List.of(new StubResponse(200, okBody())));
        config.setProvider(ClaudeConfig.Provider.OPENROUTER);
        config.setModel("openai/gpt-4o-mini");

        assertTrue(config.isStructuredOutputEnabled());
        new OpenAiCompatibleLlmClient(config).generateWithMeta("SYS", "USR", schema());

        assertTrue(requestBodies.get(0).contains("response_format"));
    }

    /**
     * The precondition for the line above: a model that cannot be constrained must cost one extra
     * request for <em>itself</em>, not disable constrained decoding for every model chosen
     * afterwards. The latch used to be one flag per client, and a client outlives a model change —
     * {@link LlmClientProvider} fingerprints provider, base URL and key, and the model is in none
     * of them, so on a routing gateway one schema-less model silently degraded all the others.
     */
    @Test
    void aModelThatRefusesTheSchemaDoesNotDisableItForTheNextOne() throws Exception {
        ClaudeConfig config = startServer(List.of(
            new StubResponse(400, "{\"error\":{\"message\":\"response_format is not supported\"}}"),
            new StubResponse(200, okBody())));
        config.setProvider(ClaudeConfig.Provider.OPENROUTER);
        config.setModel("some-vendor/no-schemas-here");

        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(config);
        client.generateWithMeta("SYS", "USR", schema());
        assertEquals(2, requestBodies.size());
        assertFalse(requestBodies.get(1).contains("response_format"), "the retry drops the field");

        // Same model again: remembered, so no second probe.
        client.generateWithMeta("SYS", "USR", schema());
        assertFalse(requestBodies.get(2).contains("response_format"));

        // A different model on the same gateway, through the same client: constrained again.
        config.setModel("openai/gpt-4o-mini");
        client.generateWithMeta("SYS", "USR", schema());
        assertTrue(requestBodies.get(3).contains("response_format"),
            "one model's refusal says nothing about another model's capabilities");
    }

    /**
     * OpenRouter's attribution headers name this project and nothing about the deployment. They go
     * only to OpenRouter: sending an unsolicited {@code X-Title} to somebody's corporate gateway is
     * not this application's business.
     */
    @Test
    void sendsTheKeyButNotTheAttributionHeadersToAnAddressThatIsNotOpenRouters() throws Exception {
        ClaudeConfig config = startServer(List.of(new StubResponse(200, okBody())));
        config.setProvider(ClaudeConfig.Provider.OPENROUTER);
        config.setApiKey("sk-or-v1-test");

        new OpenAiCompatibleLlmClient(config).generateWithMeta("SYS", "USR", null);

        Map<String, List<String>> sent = requestHeaders.get(0);
        assertEquals(List.of("Bearer sk-or-v1-test"), sent.get("Authorization"));
        // Header names come back capitalised by com.sun.net.httpserver.
        assertNull(sent.get("X-title"), "naming this project at somebody's own gateway is not our business");
        assertNull(sent.get("Http-referer"));
    }

    /** The address decides the courtesy headers; the shipped default is the real gateway. */
    @Test
    void recognisesOpenRoutersOwnAddressForAttribution() {
        ClaudeConfig config = new ClaudeConfig();
        config.setProvider(ClaudeConfig.Provider.OPENROUTER);
        config.setBaseUrl("");
        assertTrue(config.isOpenRouterEndpoint());

        config.setBaseUrl("https://gateway.corp.example/openrouter/v1");
        assertFalse(config.isOpenRouterEndpoint());

        config.setProvider(ClaudeConfig.Provider.OPENAI_COMPATIBLE);
        config.setBaseUrl("https://openrouter.ai/api/v1");
        assertFalse(config.isOpenRouterEndpoint(),
            "the address alone is not enough — the provider chooses the dialect");
    }

    /**
     * The money is on the wire and used to be dropped. It is read, never derived — no price table
     * lives in this application, so a figure shown is one the provider stood behind.
     */
    @Test
    void readsBackWhatTheCallCostInMoney() throws Exception {
        ClaudeConfig config = startServer(List.of(new StubResponse(200, pricedBody())));
        config.setProvider(ClaudeConfig.Provider.OPENROUTER);

        LlmUsage usage = new OpenAiCompatibleLlmClient(config)
            .generateWithMeta("SYS", "USR", null).usage();

        assertEquals(0.00042, usage.costUsd(), 1e-9);
        assertTrue(usage.summary().contains("$0.000420"), usage.summary());
    }

    /**
     * A gateway that does not price its answers — the OpenAI API, Ollama — leaves the cost unknown.
     * Zero would say the call was free, on a page whose whole point is what a configuration costs.
     */
    @Test
    void reportsAnUnknownCostRatherThanAFreeOne() throws Exception {
        ClaudeConfig config = startServer(List.of(new StubResponse(200, okBody())));

        LlmUsage usage = new OpenAiCompatibleLlmClient(config)
            .generateWithMeta("SYS", "USR", null).usage();

        assertNull(usage.costUsd(), "an unpriced call is not a free one");
        assertFalse(usage.summary().contains("$"), usage.summary());
    }

    /**
     * The privacy control: OpenRouter can enforce at the routing layer what the Settings banner can
     * otherwise only warn about.
     */
    @Test
    void asksOpenRouterToKeepMessagesAwayFromProvidersThatRetainThem() throws Exception {
        ClaudeConfig config = startServer(List.of(new StubResponse(200, okBody())));
        config.setProvider(ClaudeConfig.Provider.OPENROUTER);

        new OpenAiCompatibleLlmClient(config).generateWithMeta("SYS", "USR", null);

        JsonNode sent = new ObjectMapper().readTree(requestBodies.get(0));
        assertEquals("deny", sent.path("provider").path("data_collection").asText());
        assertTrue(sent.path("provider").path("require_parameters").isMissingNode(),
            "require_parameters is opt-in: it makes a schema-less model unroutable rather than "
                + "degrading, which the per-model latch cannot rescue");
        assertTrue(config.isDataRetentionRefused());
    }

    @Test
    void sendsNoRoutingPolicyToAGatewayThatIsNotOpenRouter() throws Exception {
        ClaudeConfig config = startServer(List.of(new StubResponse(200, okBody())));
        config.setProvider(ClaudeConfig.Provider.OPENAI_COMPATIBLE);

        new OpenAiCompatibleLlmClient(config).generateWithMeta("SYS", "USR", null);

        JsonNode sent = new ObjectMapper().readTree(requestBodies.get(0));
        assertTrue(sent.path("provider").isMissingNode(),
            "an arbitrary gateway has no notion of OpenRouter's routing object");
        assertFalse(config.isDataRetentionRefused(),
            "the claim is only true where it can be enforced");
    }

    @Test
    void routingCanBeWidenedAndTightenedFromTheConfiguration() throws Exception {
        ClaudeConfig config = startServer(List.of(new StubResponse(200, okBody())));
        config.setProvider(ClaudeConfig.Provider.OPENROUTER);
        config.setOpenrouterDataCollection(ClaudeConfig.DataCollection.ALLOW);
        config.setOpenrouterRequireParameters(true);

        new OpenAiCompatibleLlmClient(config).generateWithMeta("SYS", "USR", null);

        JsonNode routing = new ObjectMapper().readTree(requestBodies.get(0)).path("provider");
        assertTrue(routing.path("data_collection").isMissingNode());
        assertTrue(routing.path("require_parameters").asBoolean());
    }

    /**
     * A 402 is an account out of credit. Telling its owner to check their base URL, model and API
     * key — which are all correct — is worse than saying nothing.
     */
    @Test
    void namesTheRealRemedyOnAMeteredGatewaysErrors() throws Exception {
        ClaudeConfig config = startServer(List.of(
            new StubResponse(402, "{\"error\":{\"message\":\"Insufficient credits\"}}")));
        config.setProvider(ClaudeConfig.Provider.OPENROUTER);

        RuntimeException e = assertThrows(RuntimeException.class,
            () -> new OpenAiCompatibleLlmClient(config).generateWithMeta("SYS", "USR", null));

        assertTrue(e.getMessage().contains("out of credit"), e.getMessage());
        assertTrue(e.getMessage().contains("Insufficient credits"),
            "the provider's own words are the half that says which cap");
        assertFalse(e.getMessage().contains("check base URL, model and API key"), e.getMessage());
    }

    /**
     * Restricting routing makes "no provider satisfies your policy" arrive as the same 404 a
     * mistyped slug does. Unqualified, an operator checks a model name that is already correct.
     */
    @Test
    void aRoutingRefusalIsNotReportedAsAnUnknownModel() throws Exception {
        ClaudeConfig config = startServer(List.of(
            new StubResponse(404, "{\"error\":{\"message\":\"No endpoints found\"}}")));
        config.setProvider(ClaudeConfig.Provider.OPENROUTER);

        RuntimeException e = assertThrows(RuntimeException.class,
            () -> new OpenAiCompatibleLlmClient(config).generateWithMeta("SYS", "USR", null));

        assertTrue(e.getMessage().contains("routing is restricted"), e.getMessage());
        assertTrue(e.getMessage().contains("claude.openrouter-data-collection"), e.getMessage());
    }

    /**
     * The provider enum says which dialect to speak; it does not say who answers. Sending
     * OpenRouter's routing object and attribution headers to a corporate proxy is both none of this
     * application's business and one more way to be answered 400 — which the schema latch would
     * then blame on the schema.
     */
    /**
     * The asymmetry, pinned: behind a proxy the privacy restriction still travels while the
     * courtesy header does not. Dropping {@code data_collection} because the hostname is
     * unfamiliar would silently remove a guarantee the operator configured — the exact failure
     * that setting exists to prevent — whereas a withheld header costs nothing.
     */
    @Test
    void keepsThePrivacyRestrictionBehindAProxyWhileWithholdingTheCourtesyHeader() throws Exception {
        ClaudeConfig config = startServer(List.of(new StubResponse(200, okBody())));
        // Provider OPENROUTER, base URL the local stub — as a corporate egress proxy would be.
        config.setProvider(ClaudeConfig.Provider.OPENROUTER);

        new OpenAiCompatibleLlmClient(config).generateWithMeta("SYS", "USR", null);

        assertEquals("deny", new ObjectMapper().readTree(requestBodies.get(0))
            .path("provider").path("data_collection").asText());
        assertNull(requestHeaders.get(0).get("X-title"));
        assertFalse(config.isOpenRouterEndpoint());
        assertTrue(config.isDataRetentionRefused(),
            "the UI states the restriction because the request really carried it");
    }

    /** Cache accounting is a measurement, so a miss and an unreported figure must not look alike. */
    @Test
    void readsBackHowMuchOfThePromptWasCached() throws Exception {
        ClaudeConfig config = startServer(List.of(new StubResponse(200,
            "{\"choices\":[{\"message\":{\"content\":\"{}\"}}],"
                + "\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":340,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":900}}}")));

        LlmUsage usage = new OpenAiCompatibleLlmClient(config)
            .generateWithMeta("SYS", "USR", null).usage();

        assertEquals(900L, usage.cachedInputTokens());
    }

    @Test
    void leavesCacheAccountingUnknownWhenTheProviderCountsNone() throws Exception {
        ClaudeConfig config = startServer(List.of(new StubResponse(200, okBody())));

        LlmUsage usage = new OpenAiCompatibleLlmClient(config)
            .generateWithMeta("SYS", "USR", null).usage();

        assertNull(usage.cachedInputTokens(),
            "zero would say the cache was consulted and missed, which nobody measured");
    }

    /**
     * The symmetric breakdown to the cache figure, on the output side. It explains a cost rather
     * than adding to it, so the totals it breaks down must not move.
     */
    @Test
    void readsBackHowMuchOfTheAnswerWasDeliberation() throws Exception {
        ClaudeConfig config = startServer(List.of(new StubResponse(200,
            "{\"choices\":[{\"message\":{\"content\":\"{}\"}}],"
                + "\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":340,"
                + "\"completion_tokens_details\":{\"reasoning_tokens\":260}}}")));

        LlmUsage usage = new OpenAiCompatibleLlmClient(config)
            .generateWithMeta("SYS", "USR", null).usage();

        assertEquals(260L, usage.reasoningTokens());
        assertEquals(340L, usage.outputTokens(),
            "reasoning is already inside the completion tokens — a breakdown, not an addition");
        assertEquals(1540L, usage.totalTokens());
    }

    /**
     * Nullability reads the other way round from the cache figure: {@code 0} is the ordinary case
     * — a model that did not deliberate — so only a genuinely absent field may be null.
     */
    @Test
    void tellsAModelThatDidNotDeliberateFromOneNobodyCounted() throws Exception {
        ClaudeConfig config = startServer(List.of(
            new StubResponse(200, "{\"choices\":[{\"message\":{\"content\":\"{}\"}}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,"
                + "\"completion_tokens_details\":{\"reasoning_tokens\":0}}}"),
            new StubResponse(200, okBody())));

        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(config);
        assertEquals(0L, client.generateWithMeta("SYS", "USR", null).usage().reasoningTokens(),
            "zero is a measurement: this model answered without thinking first");
        assertNull(client.generateWithMeta("SYS", "USR", null).usage().reasoningTokens(),
            "a provider that reports no breakdown said nothing, which is not zero");
    }

    @Test
    void schemasAreValidJsonSchemaObjects() {
        for (Map<String, Object> schema : List.of(
                LlmSchemas.processMiningResult(), LlmSchemas.fieldProfileResult())) {
            assertEquals("object", schema.get("type"));
            assertEquals(Boolean.FALSE, schema.get("additionalProperties"),
                "strict mode requires additionalProperties:false on the root");
            assertNotNull(schema.get("properties"));
            assertNotNull(schema.get("required"));
        }
    }

    /**
     * The two fields an operator is most likely to act on must be declinable.
     *
     * <p>Every property was required, and under strict decoding required means the model
     * <em>cannot</em> omit it — so the schema compelled a small model to invent a probable cause and
     * a SQL statement for every anomaly it reported. Nullable rather than dropped from
     * {@code required}: strict mode refuses a schema that declares a property and does not require
     * it, and that refusal is the 400 the per-model latch reads as "no response_format here",
     * which would leave the deployment decoding unconstrained for good.
     */
    @Test
    @SuppressWarnings("unchecked")
    void anAnomalyMayDeclineACauseAndAQueryWithoutLeavingStrictMode() {
        Map<String, Object> anomaly = (Map<String, Object>) ((Map<String, Object>)
            ((Map<String, Object>) LlmSchemas.processMiningResult().get("properties"))
                .get("anomalies")).get("items");
        Map<String, Object> properties = (Map<String, Object>) anomaly.get("properties");
        List<String> required = (List<String>) anomaly.get("required");

        for (String field : List.of("probableCause", "sqlSuggestion")) {
            assertTrue(required.contains(field),
                field + " stays required — strict mode refuses a declared-but-optional property");
            Object type = ((Map<String, Object>) properties.get(field)).get("type");
            assertEquals(List.of("string", "null"), type,
                field + " must be nullable, so the model can decline rather than invent");
        }
        assertFalse(properties.containsKey("ksqlSuggestion"),
            "the engine is Flink SQL; ksqlDB is a dialect it refuses");
    }

    /** The prompt must not teach a statement this application's own whitelist rejects. */
    @Test
    void theSchemaNamesFlinkSqlAndNotKsqlDb() {
        Map<String, Object> anomaly = (Map<String, Object>) ((Map<String, Object>)
            ((Map<String, Object>) LlmSchemas.processMiningResult().get("properties"))
                .get("anomalies")).get("items");
        Map<String, Object> properties = (Map<String, Object>) anomaly.get("properties");
        String description = String.valueOf(
            ((Map<String, Object>) properties.get("sqlSuggestion")).get("description"));

        assertTrue(description.contains("Flink SQL"), description);
        assertTrue(description.contains("CREATE STREAM"),
            "naming the syntax that does NOT run here is what stops it being emitted");
    }
}
