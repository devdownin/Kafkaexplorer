// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How a rate limit is waited out.
 *
 * <p>A 429 used to be handled as a 5xx: 500 ms, then 1 s, then give up — three attempts inside a
 * second and a half, which is shorter than any rate limit worth the name. So the caller was told
 * "call failed with status 429" about a request that would have been accepted a few seconds later,
 * and the retry budget was spent being refused three times instead of once.
 *
 * <p>The parsing half is pure and takes its clock as a parameter, so most of this runs without a
 * server and without waiting for anything. Only the two behavioural cases — honouring a short
 * delay, refusing a long one — go through the real client.
 */
class LlmRateLimitTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ── the parsing, which is where the wrong answers would come from ──────────────────────────

    private static HttpResponse<String> withHeaders(Map<String, List<String>> headers) {
        return new HttpResponse<>() {
            @Override public int statusCode() {
                return 429;
            }

            @Override public HttpRequest request() {
                return null;
            }

            @Override public java.util.Optional<HttpResponse<String>> previousResponse() {
                return java.util.Optional.empty();
            }

            @Override public java.net.http.HttpHeaders headers() {
                return java.net.http.HttpHeaders.of(headers, (a, b) -> true);
            }

            @Override public String body() {
                return "";
            }

            @Override public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
                return java.util.Optional.empty();
            }

            @Override public URI uri() {
                return URI.create("http://example.invalid");
            }

            @Override public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }

    private static final long NOW = 1_700_000_000_000L;

    @Test
    void readsRetryAfterAsANumberOfSeconds() {
        assertEquals(4_000L, LlmHttpSupport.retryAfterMillis(
            withHeaders(Map.of("Retry-After", List.of("4"))), NOW));
    }

    /** RFC 9110 allows a date there too, and it is served in the wild. */
    @Test
    void readsRetryAfterAsAnHttpDate() {
        String at = DateTimeFormatter.RFC_1123_DATE_TIME.format(
            ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(NOW + 9_000L), ZoneOffset.UTC));

        long waitMs = LlmHttpSupport.retryAfterMillis(
            withHeaders(Map.of("Retry-After", List.of(at))), NOW);

        assertTrue(waitMs > 8_000L && waitMs <= 9_000L, "got " + waitMs);
    }

    /**
     * OpenRouter's convention: an instant rather than a delay, and in milliseconds. Consulted only
     * when {@code Retry-After} is absent, since that one is the standard and says what it means.
     */
    @Test
    void fallsBackToTheRateLimitResetInstant() {
        assertEquals(6_000L, LlmHttpSupport.retryAfterMillis(
            withHeaders(Map.of("X-RateLimit-Reset", List.of(String.valueOf(NOW + 6_000L)))), NOW));
    }

    /** The same header in seconds, which several gateways serve — see the epoch threshold. */
    @Test
    void readsAResetInstantExpressedInSeconds() {
        long resetSeconds = (NOW / 1000L) + 7L;

        assertEquals(7_000L, LlmHttpSupport.retryAfterMillis(
            withHeaders(Map.of("X-RateLimit-Reset", List.of(String.valueOf(resetSeconds)))), NOW));
    }

    @Test
    void retryAfterWinsOverTheResetInstant() {
        assertEquals(2_000L, LlmHttpSupport.retryAfterMillis(withHeaders(Map.of(
            "Retry-After", List.of("2"),
            "X-RateLimit-Reset", List.of(String.valueOf(NOW + 60_000L)))), NOW));
    }

    /**
     * Everything unusable answers -1, which is what selects the ordinary backoff. A guess would be
     * worse than the behaviour this replaces, not better.
     */
    @Test
    void anythingUnusableSaysSoRatherThanGuessing() {
        assertEquals(-1L, LlmHttpSupport.retryAfterMillis(withHeaders(Map.of()), NOW));
        assertEquals(-1L, LlmHttpSupport.retryAfterMillis(
            withHeaders(Map.of("Retry-After", List.of("soon"))), NOW));
        assertEquals(-1L, LlmHttpSupport.retryAfterMillis(
            withHeaders(Map.of("Retry-After", List.of("-5"))), NOW));
        assertEquals(-1L, LlmHttpSupport.retryAfterMillis(
            withHeaders(Map.of("X-RateLimit-Reset", List.of(String.valueOf(NOW - 60_000L)))), NOW),
            "a window that reopened in the past is a stale header, not an instruction to retry now");
    }

    // ── and what the loop does with it ────────────────────────────────────────────────────────

    /** Answers 429 with the given headers once, then 200. */
    private ClaudeConfig startServer(Map<String, String> rateLimitHeaders, AtomicInteger calls)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int call = calls.getAndIncrement();
            byte[] body = (call == 0
                ? "{\"error\":{\"message\":\"rate limited\"}}"
                : "{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}")
                .getBytes(StandardCharsets.UTF_8);
            if (call == 0) {
                rateLimitHeaders.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            }
            exchange.sendResponseHeaders(call == 0 ? 429 : 200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        ClaudeConfig config = new ClaudeConfig();
        config.setProvider(ClaudeConfig.Provider.OPENROUTER);
        config.setApiKey("test-key");
        config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        config.setModel("openai/gpt-4o-mini");
        return config;
    }

    /** A short delay is waited out, and the call then succeeds — one attempt, not three. */
    @Test
    void waitsTheStatedDelayAndSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ClaudeConfig config = startServer(Map.of("Retry-After", "1"), calls);

        long startedAt = System.currentTimeMillis();
        String answer = new OpenAiCompatibleLlmClient(config).generate("SYS", "USR");
        long elapsed = System.currentTimeMillis() - startedAt;

        assertEquals("{}", answer);
        assertEquals(2, calls.get());
        assertTrue(elapsed >= 1_000L,
            "the retry must not go out before the window reopens; waited " + elapsed + "ms");
    }

    /**
     * A delay past what this call can wait for is refused at once, naming it. Spending the
     * remaining attempts would arrive at the same refusal, slower.
     */
    @Test
    void refusesADelayLongerThanTheCallCanWait() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ClaudeConfig config = startServer(Map.of("Retry-After", "600"), calls);
        config.setRequestTimeoutSeconds(30);

        RuntimeException error = assertThrows(RuntimeException.class,
            () -> new OpenAiCompatibleLlmClient(config).generate("SYS", "USR"));

        assertTrue(error.getMessage().contains("600s"), error.getMessage());
        assertEquals(1, calls.get(), "nothing is gained by asking again before the window reopens");
    }

    /** With no header the behaviour is exactly what it was: the ordinary backoff. */
    @Test
    void fallsBackToTheBackoffWhenTheGatewaySaysNothing() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ClaudeConfig config = startServer(Map.of(), calls);

        assertEquals("{}", new OpenAiCompatibleLlmClient(config).generate("SYS", "USR"));
        assertEquals(2, calls.get());
    }

    /** Sanity: the cap is the call's own budget when that is the smaller of the two. */
    @Test
    void theCapFollowsTheCallsOwnTimeout() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ClaudeConfig config = startServer(Map.of("Retry-After", "5"), calls);
        config.setRequestTimeoutSeconds(2);   // shorter than the 5s the gateway asks for

        RuntimeException error = assertThrows(RuntimeException.class,
            () -> new OpenAiCompatibleLlmClient(config).generate("SYS", "USR"));

        assertTrue(error.getMessage().contains("5s"), error.getMessage());
        assertEquals(1, calls.get());
        assertTrue(Duration.ofSeconds(2).toMillis() < 5_000L);
    }
}
