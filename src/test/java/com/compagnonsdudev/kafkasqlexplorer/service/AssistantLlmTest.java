// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.AssistantProperties;
import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The assistant's model: {@code claude.*} with its overrides, and a key that never changes hosts. */
class AssistantLlmTest {

    private ClaudeConfig claude;
    private AssistantProperties properties;

    @BeforeEach
    void setUp() {
        claude = new ClaudeConfig();
        claude.setProvider(ClaudeConfig.Provider.OPENROUTER);
        claude.setApiKey("sk-or-secret");
        claude.setModel("openai/gpt-4o-mini");
        properties = new AssistantProperties();
        properties.setEnabled(true);
    }

    @Test
    void inheritsEverythingWhenNothingIsOverridden() {
        ClaudeConfig effective = new AssistantLlm(claude, properties).effectiveConfig();

        assertEquals(ClaudeConfig.Provider.OPENROUTER, effective.getProvider());
        assertEquals("openai/gpt-4o-mini", effective.getModel());
        assertEquals("sk-or-secret", effective.getApiKey());
        assertEquals(60, effective.getRequestTimeoutSeconds(),
            "with no timeout of its own the assistant inherits claude.*'s: one budget for every call");
    }

    @Test
    void itsOwnTimeoutWinsWhenItSetsOne() {
        properties.setRequestTimeoutSeconds(120);

        assertEquals(120, new AssistantLlm(claude, properties).effectiveConfig().getRequestTimeoutSeconds());
    }

    @Test
    void theDeclaredWindowDoesNotFollowAnotherEndpoint() {
        claude.setContextWindowTokens(16_384);
        assertEquals(16_384, new AssistantLlm(claude, properties).effectiveConfig().getContextWindowTokens());

        properties.setBaseUrl("http://gpu-box:8080/v1");
        assertEquals(0, new AssistantLlm(claude, properties).effectiveConfig().getContextWindowTokens(),
            "the window belongs to claude.*'s server; another server's is unknown");
    }

    @Test
    void theKeyDoesNotFollowAnotherEndpoint() {
        properties.setProvider("llama-cpp");
        properties.setModel("gemma4:e2b");

        ClaudeConfig effective = new AssistantLlm(claude, properties).effectiveConfig();

        assertEquals(ClaudeConfig.Provider.LLAMA_CPP, effective.getProvider());
        assertEquals("http://localhost:8083/v1", effective.getResolvedBaseUrl(),
            "a provider switch with no address gets that provider's default, not the inherited one");
        assertEquals("", effective.getApiKey(), "the OpenRouter key must not be sent to another host");
        assertEquals("gemma4:e2b", effective.getModel());
        assertEquals("openai/gpt-4o-mini", claude.getModel(), "the shared configuration is untouched");
    }

    @Test
    void aBaseUrlOverrideAloneAlsoDropsTheKey() {
        properties.setBaseUrl("http://gpu-box:8080/v1");

        assertEquals("", new AssistantLlm(claude, properties).effectiveConfig().getApiKey());
    }

    @Test
    void problemsAreSentences() {
        AssistantLlm llm = new AssistantLlm(claude, properties);
        assertNull(llm.problem());

        properties.setEnabled(false);
        assertTrue(llm.problem().contains("explorer.assistant.enabled"), llm.problem());

        properties.setEnabled(true);
        properties.setProvider("gemini");
        assertTrue(llm.problem().contains("'gemini'"), llm.problem());

        properties.setProvider("openrouter");
        properties.setBaseUrl("https://gateway.example/v1");
        assertTrue(llm.problem().contains("API key"),
            "a hosted override without a key of its own says so: " + llm.problem());
    }

    @Test
    void theClientIsRebuiltWhenTheModelMovesAndTheOldOneClosed() {
        List<String> closed = new ArrayList<>();
        List<String> built = new ArrayList<>();
        AssistantLlm llm = new AssistantLlm(claude, properties, config -> {
            String model = config.getModel();
            built.add(model);
            return new LlmClient() {
                @Override
                public String generate(String systemPrompt, String userPrompt) {
                    return "{}";
                }

                @Override
                public void close() {
                    closed.add(model);
                }
            };
        });

        LlmClient first = llm.client(llm.effectiveConfig());
        assertSame(first, llm.client(llm.effectiveConfig()), "same configuration, same client");
        properties.setModel("other");
        llm.client(llm.effectiveConfig());

        assertEquals(List.of("openai/gpt-4o-mini", "other"), built);
        assertEquals(List.of("openai/gpt-4o-mini"), closed);
    }
}
