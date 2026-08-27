// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two things that used to fail silently: an LLM client frozen at startup while the settings page
 * repointed it, and an answerless provider body crashing on a null dereference.
 */
class LlmClientResolutionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ClaudeConfig ollamaConfig() {
        ClaudeConfig config = new ClaudeConfig();
        config.setProvider(ClaudeConfig.Provider.OLLAMA);
        config.setBaseUrl("http://localhost:11434/v1");
        return config;
    }

    @Test
    void sameConfigurationReusesTheSameClient() {
        LlmClientProvider provider = new LlmClientProvider(ollamaConfig());
        assertSame(provider.get(), provider.get(),
            "an unchanged configuration must not reallocate the HTTP client per call");
    }

    @Test
    void changingProviderRebuildsTheClient() {
        ClaudeConfig config = ollamaConfig();
        LlmClientProvider provider = new LlmClientProvider(config);
        LlmClient before = provider.get();

        config.setProvider(ClaudeConfig.Provider.SPECTRA);
        config.setBaseUrl("http://localhost:8080");
        LlmClient after = provider.get();

        assertNotSame(before, after);
        assertInstanceOf(SpectraLlmClient.class, after,
            "a provider changed through POST /api/config must be the one the analyses use");
    }

    /**
     * The request timeout is not read purely per call: {@code LlmHttpSupport.newClient} bakes it
     * into the client as the <em>connect</em> timeout. Leaving it out of the fingerprint moved one
     * half of the setting and left the other at the old value until the client happened to be
     * rebuilt for an unrelated reason — the exact defect this class exists to prevent, in the one
     * field added to it after the class was written.
     */
    @Test
    void changingTheRequestTimeoutRebuildsTheClient() {
        ClaudeConfig config = ollamaConfig();
        LlmClientProvider provider = new LlmClientProvider(config);
        LlmClient before = provider.get();

        config.setRequestTimeoutSeconds(config.getRequestTimeoutSeconds() + 60);

        assertNotSame(before, provider.get(),
            "the connect timeout is baked into the client, so raising the setting means a new one");
    }

    /**
     * A replaced client is closed. Two of the three hold an {@link java.net.http.HttpClient},
     * which keeps a selector thread and a connection pool alive until it is collected; every
     * Settings save that moved the endpoint leaked one.
     */
    @Test
    void aReplacedClientIsClosed() {
        ClaudeConfig config = ollamaConfig();
        LlmClientProvider provider = new LlmClientProvider(config);
        RecordingClient first = new RecordingClient();
        provider.setClientForTest(first, config);

        config.setProvider(ClaudeConfig.Provider.SPECTRA);
        config.setBaseUrl("http://localhost:8080");
        provider.get();

        assertTrue(first.closed, "the outgoing client must be released, not dropped");
    }

    /** And the last one built is released at shutdown, or it is the one that leaks. */
    @Test
    void theLastClientIsClosedOnShutdown() {
        ClaudeConfig config = ollamaConfig();
        LlmClientProvider provider = new LlmClientProvider(config);
        RecordingClient held = new RecordingClient();
        provider.setClientForTest(held, config);

        provider.shutdown();

        assertTrue(held.closed);
    }

    /** Retiring a client must never be able to fail the save that retired it. */
    @Test
    void aClientThatRefusesToCloseDoesNotFailTheSave() {
        ClaudeConfig config = ollamaConfig();
        LlmClientProvider provider = new LlmClientProvider(config);
        provider.setClientForTest(new RecordingClient() {
            @Override
            public void close() {
                throw new IllegalStateException("pool already gone");
            }
        }, config);

        config.setProvider(ClaudeConfig.Provider.SPECTRA);
        config.setBaseUrl("http://localhost:8080");

        assertInstanceOf(SpectraLlmClient.class, provider.get(),
            "a client that will not close must not stop the replacement being handed out");
    }

    private static class RecordingClient implements LlmClient {
        boolean closed;

        @Override
        public String generate(String systemPrompt, String userPrompt) {
            return "";
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    void changingApiKeyRebuildsTheClient() {
        ClaudeConfig config = ollamaConfig();
        LlmClientProvider provider = new LlmClientProvider(config);
        LlmClient before = provider.get();

        config.setApiKey("rotated-key");

        assertNotSame(before, provider.get(),
            "the API key is baked into the client, so a new key means a new client");
    }

    @Test
    void changingBaseUrlRebuildsTheClient() {
        ClaudeConfig config = ollamaConfig();
        LlmClientProvider provider = new LlmClientProvider(config);
        LlmClient before = provider.get();

        config.setBaseUrl("http://gpu-box:11434/v1");

        assertNotSame(before, provider.get());
    }

    /**
     * OpenRouter speaks the OpenAI API, so it deliberately has no client of its own — what has to
     * hold is that the factory says so, and that a blank base URL resolves to the gateway rather
     * than to whatever the previous provider was pointed at.
     */
    @Test
    void openRouterUsesTheOpenAiCompatibleClientAndItsOwnDefaultEndpoint() {
        ClaudeConfig config = new ClaudeConfig();
        config.setProvider(ClaudeConfig.Provider.OPENROUTER);
        config.setBaseUrl("");

        assertEquals("https://openrouter.ai/api/v1", config.getResolvedBaseUrl());
        assertInstanceOf(OpenAiCompatibleLlmClient.class, LlmClientFactory.create(config));
        assertTrue(config.isApiKeyRequired(),
            "an anonymous OpenRouter request is a 401, so a blank key is a broken deployment, "
                + "not optional credentials");
        assertFalse(config.isLocalDeployment());
    }

    @Test
    void firstChoiceContentReadsTheAnswer() throws Exception {
        String body = "{\"choices\":[{\"message\":{\"content\":\"hello\"}}]}";
        assertEquals("hello", OpenAiCompatibleLlmClient.firstChoiceContent(MAPPER.readTree(body), body));
    }

    /** A 200 carrying an error object — Ollama's shape for an unknown model. */
    @Test
    void firstChoiceContentReportsAProviderError() throws Exception {
        String body = "{\"error\":{\"message\":\"model 'nope' not found\"}}";
        RuntimeException e = assertThrows(RuntimeException.class,
            () -> OpenAiCompatibleLlmClient.firstChoiceContent(MAPPER.readTree(body), body));
        assertTrue(e.getMessage().contains("model 'nope' not found"), e.getMessage());
    }

    /**
     * An empty or absent choices array used to reach {@code .get(0)} and NPE, so the user was told
     * "LLM call failed: null" — the one message that names neither cause nor remedy.
     */
    @Test
    void firstChoiceContentRefusesAnAnswerlessBody() throws Exception {
        for (String body : new String[]{"{\"choices\":[]}", "{}", "{\"choices\":[{}]}"}) {
            RuntimeException e = assertThrows(RuntimeException.class,
                () -> OpenAiCompatibleLlmClient.firstChoiceContent(MAPPER.readTree(body), body),
                "body should be refused: " + body);
            assertNotNull(e.getMessage());
            assertFalse(e.getMessage().contains("null"), e.getMessage());
        }
    }
}
