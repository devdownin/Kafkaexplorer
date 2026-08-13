// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

    private LlmHttpSupport() {
    }

    /** Builds a shared client with the configured connect timeout. */
    static HttpClient newClient(ClaudeConfig config) {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(config.getRequestTimeoutSeconds()))
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
            } catch (IOException e) {
                // Includes HttpTimeoutException and connection resets — transient.
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
