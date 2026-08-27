// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /**
     * The test that decides whether a stored credential may follow a configuration change. The
     * host and nothing else: a changed port or path is the same endpoint, a changed hostname is
     * not — and a URL that will not parse is treated as different, since the safe answer to "may
     * this key follow?" is no.
     */
    @Test
    void sameEndpointHostComparesTheHostAndNothingElse() {
        assertTrue(ClaudeConfig.sameEndpointHost(
            "https://openrouter.ai/api/v1", "https://openrouter.ai:8443/other/v1"));
        assertTrue(ClaudeConfig.sameEndpointHost(
            "https://OpenRouter.AI/api/v1", "https://openrouter.ai/api/v1"));

        assertFalse(ClaudeConfig.sameEndpointHost(
            "https://openrouter.ai/api/v1", "https://attacker.example/v1"));
        // A subdomain is a different host, which is the conservative reading and the right one
        // here: nothing guarantees it is operated by the same people.
        assertFalse(ClaudeConfig.sameEndpointHost(
            "https://openrouter.ai/api/v1", "https://evil.openrouter.ai.attacker.example/v1"));

        assertFalse(ClaudeConfig.sameEndpointHost("not a url", "not a url"));
        assertFalse(ClaudeConfig.sameEndpointHost("", "https://openrouter.ai/api/v1"));
        assertFalse(ClaudeConfig.sameEndpointHost(null, null));
    }
    /**
     * The two things that make a call impossible before it is made, and only the first was asked
     * about.
     *
     * <p>A missing endpoint was checked nowhere, and {@code defaultBaseUrl(OPENAI_COMPATIBLE)} is
     * {@code ""} by design — so such a deployment passed every guard, read its topics, built its
     * prompt and died inside {@code HttpRequest.newBuilder().uri(…)} with "URI with undefined
     * scheme", a message naming neither the setting nor the page that sets it.
     */
    @Test
    void aMissingEndpointIsAsFinalAsAMissingKey() {
        ClaudeConfig noEndpoint = at(ClaudeConfig.Provider.OPENAI_COMPATIBLE, "");
        String problem = noEndpoint.configurationProblem();
        assertNotNull(problem, "a provider with no default and no base URL cannot call anything");
        assertTrue(problem.contains("claude.base-url"), problem);

        ClaudeConfig noKey = at(ClaudeConfig.Provider.OPENROUTER, "");
        assertNotNull(noKey.configurationProblem());
        assertTrue(noKey.configurationProblem().contains("claude.api-key"),
            noKey.configurationProblem());
    }

    /** A host with no scheme is how one writes an internal gateway, and it cannot be sent to. */
    @Test
    void anAddressThatIsNotAnAddressIsReported() {
        ClaudeConfig config = at(ClaudeConfig.Provider.OPENAI_COMPATIBLE, "gpu-box:11434/v1");
        String problem = config.configurationProblem();
        assertNotNull(problem);
        assertTrue(problem.contains("absolute http(s) URL"), problem);

        assertFalse(ClaudeConfig.isAbsoluteHttpUrl("gpu-box:11434/v1"),
            "java.net.URI parses this as a scheme of its own — the HTTP client is what refuses it");
        assertFalse(ClaudeConfig.isAbsoluteHttpUrl("/v1/chat/completions"));
        assertFalse(ClaudeConfig.isAbsoluteHttpUrl("ftp://gpu-box/v1"));
        assertFalse(ClaudeConfig.isAbsoluteHttpUrl(""));
        assertFalse(ClaudeConfig.isAbsoluteHttpUrl(null));
        assertTrue(ClaudeConfig.isAbsoluteHttpUrl("http://gpu-box:11434/v1"));
        assertTrue(ClaudeConfig.isAbsoluteHttpUrl("https://openrouter.ai/api/v1"));
    }

    /** Nothing is reported about a configuration that can be tried — the endpoint answers for itself. */
    @Test
    void aConfigurationThatCanBeTriedReportsNothing() {
        assertNull(at(ClaudeConfig.Provider.OLLAMA, "").configurationProblem(),
            "the provider's own default fills in, so there is an address");
        assertNull(at(ClaudeConfig.Provider.SPECTRA, "http://spectra-api:8080").configurationProblem());

        ClaudeConfig withKey = at(ClaudeConfig.Provider.OPENROUTER, "");
        withKey.setApiKey("sk-or-v1-something");
        assertNull(withKey.configurationProblem());
    }
}
