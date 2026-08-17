// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * Shared HTTP plumbing for the plain-HTTP LLM clients (OpenAI-compatible, Ollama, SpectraLLM).
 *
 * <p>Centralises three concerns so each client does not reinvent them:
 * <ul>
 *   <li>a configurable connect timeout on the shared {@link HttpClient};</li>
 *   <li>a per-request timeout;</li>
 *   <li>a small retry-with-backoff loop that retries only transient failures
 *       (I/O errors, timeouts, HTTP 5xx and 429) and fails fast on 4xx, which
 *       almost always mean a misconfiguration (bad URL, model, or auth).</li>
 * </ul>
 */
final class LlmHttpSupport {

    private static final Logger log = LoggerFactory.getLogger(LlmHttpSupport.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 500L;
    /** Upper bound on the TCP/TLS handshake — see {@link #newClient}. */
    private static final int MAX_CONNECT_SECONDS = 10;

    private LlmHttpSupport() {
    }

    /**
     * Builds a shared client. The connect timeout is deliberately <em>not</em> the request timeout:
     * that one is sized for how long a model may take to generate (60s by default), and applying it
     * to the TCP/TLS handshake means a wrong port or an endpoint that is simply down takes a full
     * minute to say so — three times over, since a connect failure is retried. Opening a socket to a
     * local Ollama or a hosted API is a sub-second operation; anything past {@value #MAX_CONNECT_SECONDS}s
     * is an unreachable endpoint, not a slow one.
     */
    static HttpClient newClient(ClaudeConfig config) {
        long connectSeconds = Math.max(1, Math.min(MAX_CONNECT_SECONDS, config.getRequestTimeoutSeconds()));
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(connectSeconds))
            .build();
    }

    /** Applies the configured per-request timeout to a request builder. */
    static HttpRequest.Builder withTimeout(HttpRequest.Builder builder, ClaudeConfig config) {
        return builder.timeout(Duration.ofSeconds(config.getRequestTimeoutSeconds()));
    }

    /**
     * Sends the request, retrying transient failures. Returns a 2xx response, or throws a
     * {@link RuntimeException} whose message distinguishes a configuration error (4xx) from an
     * exhausted-retry transient failure.
     *
     * @param provider human-readable provider name used in error messages
     */
    static HttpResponse<String> sendWithRetry(HttpClient client, HttpRequest request, String provider) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return response;
                }
                if (status >= 400 && status < 500 && status != 429) {
                    // Client-side / configuration error — retrying will not help.
                    throw new RuntimeException(provider + " call failed with status " + status
                        + " (check base URL, model and API key): " + truncate(response.body()));
                }
                // 5xx or 429 → transient.
                lastError = new RuntimeException(provider + " call failed with status " + status
                    + ": " + truncate(response.body()));
                log.warn("{} transient failure (status {}), attempt {}/{}", provider, status, attempt, MAX_ATTEMPTS);
            } catch (HttpConnectTimeoutException e) {
                // Nothing was generated — the endpoint did not answer at all. Worth another try.
                lastError = new RuntimeException(provider + " connection timed out after "
                    + timeoutSeconds(request) + "s: " + e.getMessage(), e);
                log.warn("{} connect timeout, attempt {}/{}", provider, attempt, MAX_ATTEMPTS);
            } catch (HttpTimeoutException e) {
                // The request timed out *while generating*. Retrying replays the whole prompt on a
                // model that has already shown it needs longer than the budget: it triples the load
                // and the caller's wait for the same outcome, and on a live session the window it
                // was analysing is stale by then. Fail once, and name the setting that fixes it.
                throw new RuntimeException(provider + " did not answer within "
                    + timeoutSeconds(request) + "s. The model is slower than the budget — raise "
                    + "claude.request-timeout-seconds, lower claude.max-tokens, or use a smaller "
                    + "model.", e);
            } catch (IOException e) {
                // Connection resets and the like — transient.
                lastError = new RuntimeException(provider + " call failed: " + e.getMessage(), e);
                log.warn("{} transient I/O failure, attempt {}/{}: {}", provider, attempt, MAX_ATTEMPTS, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(provider + " call interrupted", e);
            }

            if (attempt < MAX_ATTEMPTS) {
                sleep(BASE_BACKOFF_MS * (1L << (attempt - 1)));
            }
        }
        throw lastError != null ? lastError : new RuntimeException(provider + " call failed");
    }

    private static String timeoutSeconds(HttpRequest request) {
        return request.timeout().map(d -> String.valueOf(d.toSeconds())).orElse("?");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) + "…" : body;
    }
}
