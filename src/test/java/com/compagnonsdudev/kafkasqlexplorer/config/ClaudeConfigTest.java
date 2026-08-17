// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code isLocalDeployment()} is not a convenience flag: the Process Mining page turns it into
 * "It is a loopback address, so no message content leaves this host." A privacy claim has to be
 * read off the address the data is actually sent to.
 */
class ClaudeConfigTest {

    private static ClaudeConfig at(ClaudeConfig.Provider provider, String baseUrl) {
        ClaudeConfig config = new ClaudeConfig();
        config.setProvider(provider);
        config.setBaseUrl(baseUrl);
        return config;
    }

    @Test
    void loopbackAddressesAreLocal() {
        for (String url : new String[]{
            "http://localhost:11434/v1",
            "http://127.0.0.1:11434/v1",
            "http://127.1.2.3:8080",
            "http://[::1]:11434/v1",
            "https://ollama.localhost/v1",
        }) {
            assertTrue(at(ClaudeConfig.Provider.OLLAMA, url).isLocalDeployment(), url);
        }
    }

    /**
     * The bug this replaced: {@code provider == OLLAMA} short-circuited the check, so pointing
     * Ollama at a GPU box — an entirely ordinary setup — kept "nothing leaves this host" on screen
     * while every message digest went over the network.
     */
    @Test
    void remoteOllamaIsNotLocal() {
        assertFalse(at(ClaudeConfig.Provider.OLLAMA, "http://gpu-box:11434/v1").isLocalDeployment());
        assertFalse(at(ClaudeConfig.Provider.OLLAMA, "http://10.0.0.5:11434/v1").isLocalDeployment());
        // The address the bundled LLM compose stack uses: another container, not this host.
        assertFalse(at(ClaudeConfig.Provider.OLLAMA, "http://ollama:11434/v1").isLocalDeployment());
    }

    /**
     * The host is compared, not the URL text. A substring scan answered yes for a hostname that
     * merely ends in something familiar — on the one flag that tells an operator their data stays
     * put, which is the worst place for a near-miss.
     */
    @Test
    void aHostnameThatMerelyContainsLocalhostIsNotLocal() {
        assertFalse(at(ClaudeConfig.Provider.OPENAI_COMPATIBLE,
            "https://localhost.example.com/v1").isLocalDeployment());
        assertFalse(at(ClaudeConfig.Provider.OPENAI_COMPATIBLE,
            "https://api.example.com/localhost/v1").isLocalDeployment());
        assertFalse(at(ClaudeConfig.Provider.OPENAI_COMPATIBLE,
            "https://api.example.com/v1?host=127.0.0.1").isLocalDeployment());
    }

    @Test
    void anUnusableUrlIsNotClaimedLocal() {
        // The safe answer to "am I sure this stays on this machine" is no.
        assertFalse(at(ClaudeConfig.Provider.OPENAI_COMPATIBLE, "not a url").isLocalDeployment());
        assertFalse(at(ClaudeConfig.Provider.OPENAI_COMPATIBLE, "").isLocalDeployment());
    }

    /** Anthropic's default endpoint is hosted, and blank base URLs fall back to it. */
    @Test
    void anthropicDefaultIsNotLocal() {
        assertFalse(at(ClaudeConfig.Provider.ANTHROPIC, "").isLocalDeployment());
    }

    /** A blank base URL under OLLAMA still resolves to the loopback default. */
    @Test
    void ollamaDefaultIsLocal() {
        assertTrue(at(ClaudeConfig.Provider.OLLAMA, "").isLocalDeployment());
    }
}
