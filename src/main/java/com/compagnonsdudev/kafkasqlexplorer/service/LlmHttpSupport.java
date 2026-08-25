// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import com.compagnonsdudev.kafkasqlexplorer.util.LogSafe;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

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
 *
 * <p>A 429 is retried on the <em>server's</em> schedule rather than on the backoff, because it is
 * the one transient status that says when it stops being true — see {@link #retryAfterMillis}.
 */
final class LlmHttpSupport {

    private static final Logger log = LoggerFactory.getLogger(LlmHttpSupport.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 500L;
    /**
     * The longest rate-limit wait worth sitting through, whatever the header says.
     *
     * <p>Bounded twice over — by this and by the caller's own per-request timeout, whichever is
     * smaller. A rate limit that reopens in four seconds is worth waiting for; one that reopens in
     * ten minutes is not something to hold an HTTP thread on, and the honest answer there is a
     * refusal that says when it reopens.
     */
    private static final long MAX_RATE_LIMIT_WAIT_MS = 30_000L;
    /**
     * Below this, an epoch stamp is in seconds; at or above it, in milliseconds. The same heuristic
     * {@code setup-demo.sh} relies on for event times, and it is safe for the same reason: 10^10
     * seconds is the year 2286, and 10^10 milliseconds is 1970.
     */
    private static final long EPOCH_MILLIS_THRESHOLD = 10_000_000_000L;
    /** Upper bound on the TCP/TLS handshake — see {@link #newClient}. */
    private static final int MAX_CONNECT_SECONDS = 10;
    /** Reads an error body only — see {@link #upstreamProviderOf}. */
    private static final ObjectMapper ERROR_MAPPER = new ObjectMapper();

    private LlmHttpSupport() {
    }

    /** A 4xx other than 429: the request itself is wrong, so it is never retried as-is. */
    static final class ClientErrorException extends RuntimeException {
        private final int status;
        private final String upstreamProvider;

        ClientErrorException(int status, String message) {
            this(status, message, null);
        }

        ClientErrorException(int status, String message, String upstreamProvider) {
            super(message);
            this.status = status;
            this.upstreamProvider = upstreamProvider;
        }

        int status() {
            return status;
        }

        /**
         * The upstream provider a routing gateway blamed, or {@code null} when the status is the
         * gateway's own verdict on this request.
         *
         * <p>The distinction is not cosmetic: on OpenRouter one model is served by several upstream
         * providers, the routing is opaque and it varies from one call to the next — so a relayed
         * failure is a fact about <em>that</em> provider at <em>that</em> moment, not about the
         * model, the request or the configuration. Observed here: {@code liquid/lfm-2.5-2.6b:free}
         * answered a 400 "Provider returned error" through AtlasCloud while the byte-identical body
         * succeeded through Liquid minutes later.
         */
        String upstreamProvider() {
            return upstreamProvider;
        }
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

    /**
     * Joins a path onto an OpenAI-style base URL, tolerating whether the operator wrote the
     * {@code /v1} segment or not.
     *
     * <p>Shared rather than restated because there are two callers now — the chat endpoint and
     * OpenRouter's model catalogue — and the whole content of this rule is a judgement about a
     * string somebody typed into a settings field. Two copies of that judgement is how one of them
     * comes to accept a trailing slash the other does not.
     *
     * @param path the path below {@code /v1}, with no leading slash (e.g. {@code chat/completions})
     */
    static String v1Url(ClaudeConfig config, String path) {
        String baseUrl = config.getResolvedBaseUrl();
        if (baseUrl.endsWith("/v1")) {
            return baseUrl + "/" + path;
        }
        if (baseUrl.endsWith("/v1/")) {
            return baseUrl + path;
        }
        return baseUrl + "/v1/" + path;
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
                    // Client-side / configuration error — retrying will not help. Typed, because
                    // one caller can actually act on it: an endpoint that rejects a request field
                    // it does not implement answers this way, and the client can retry without it.
                    String upstream = upstreamProviderOf(response.body());
                    throw new ClientErrorException(status, provider + " call failed with status "
                        + status + " — " + remedyFor(status, upstream) + ": "
                        + truncate(response.body()), upstream);
                }
                // 5xx or 429 → transient.
                lastError = new RuntimeException(provider + " call failed with status " + status
                    + ": " + truncate(response.body()));
                log.warn("{} transient failure (status {}), attempt {}/{}", provider, status, attempt, MAX_ATTEMPTS);

                // A 429 is not a 5xx, and treating them alike is what made the retry budget
                // useless against a rate limit. A 5xx may well pass half a second later; a rate
                // limit states when it reopens, and every attempt made before that instant is an
                // attempt spent being refused again. The default schedule — 500 ms then 1 s —
                // exhausts all three inside a second and a half, which is shorter than any rate
                // limit worth the name, so the caller was told "status 429" about a request that
                // would have gone through a few seconds later.
                if (status == 429) {
                    long waitMs = retryAfterMillis(response, System.currentTimeMillis());
                    long cap = rateLimitWaitCap(request);
                    if (waitMs > cap) {
                        // Naming the delay beats spending the remaining attempts to arrive at the
                        // same refusal: nothing this application can do shortens it.
                        throw new RuntimeException(provider + " is rate-limiting this key and says "
                            + "it will not accept another request for " + (waitMs / 1000)
                            + "s, which is longer than this call can wait. Try again later, or use "
                            + "a model or a tier with a higher limit: " + truncate(response.body()));
                    }
                    if (waitMs >= 0 && attempt < MAX_ATTEMPTS) {
                        log.warn("{} rate-limited; waiting {}ms as instructed before attempt {}/{}",
                            provider, waitMs, attempt + 1, MAX_ATTEMPTS);
                        sleep(waitMs);
                        continue;   // the header replaces the backoff rather than adding to it
                    }
                }
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

    /**
     * How long a rate-limited response says to wait, in milliseconds, or {@code -1} when it does
     * not say.
     *
     * <p>Two headers, in the order of how much they promise. {@code Retry-After} is the standard
     * one and is authoritative: RFC 9110 allows either a number of seconds or an HTTP date, and both
     * are accepted here because both are served in the wild. {@code X-RateLimit-Reset} is the
     * convention OpenRouter and several others follow — an instant rather than a delay — and is
     * consulted only when the first is absent.
     *
     * <p>Everything unusable answers {@code -1} rather than a guess: a header that cannot be parsed,
     * a negative delay, an instant already past. The caller then falls back to the ordinary backoff,
     * which is exactly the behaviour this replaces — so a gateway that says nothing is no worse off
     * than before.
     *
     * <p>Pure, and takes the clock as a parameter, so the parsing can be tested without a server and
     * without waiting for anything.
     */
    static long retryAfterMillis(HttpResponse<?> response, long nowMillis) {
        long fromRetryAfter = parseRetryAfter(header(response, "Retry-After"), nowMillis);
        if (fromRetryAfter >= 0) {
            return fromRetryAfter;
        }
        return parseResetInstant(header(response, "X-RateLimit-Reset"), nowMillis);
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse(null);
    }

    /** {@code Retry-After}: delta-seconds, or an HTTP date. */
    private static long parseRetryAfter(String value, long nowMillis) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        String trimmed = value.strip();
        try {
            long seconds = Long.parseLong(trimmed);
            return seconds >= 0 ? seconds * 1000L : -1;
        } catch (NumberFormatException notANumber) {
            // Fall through to the date form rather than giving up: both are legal here.
        }
        try {
            long at = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli();
            return Math.max(0, at - nowMillis);
        } catch (DateTimeParseException e) {
            return -1;
        }
    }

    /** {@code X-RateLimit-Reset}: the instant the window reopens, in seconds or in milliseconds. */
    private static long parseResetInstant(String value, long nowMillis) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        try {
            long stamp = Long.parseLong(value.strip());
            if (stamp <= 0) {
                return -1;
            }
            long atMillis = stamp < EPOCH_MILLIS_THRESHOLD ? stamp * 1000L : stamp;
            long waitMs = atMillis - nowMillis;
            // A reset already in the past is a stale header, not an instruction to retry now: the
            // ordinary backoff is the better answer, and it is what returning -1 selects.
            return waitMs > 0 ? waitMs : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The longest wait this particular call may honour — see {@link #MAX_RATE_LIMIT_WAIT_MS}. */
    private static long rateLimitWaitCap(HttpRequest request) {
        long requestBudgetMs = request.timeout().map(Duration::toMillis).orElse(MAX_RATE_LIMIT_WAIT_MS);
        return Math.min(MAX_RATE_LIMIT_WAIT_MS, requestBudgetMs);
    }

    /**
     * What a client error most often means, in words that name the thing to go and change.
     *
     * <p>Every 4xx used to read "check base URL, model and API key" — three things that are all
     * fine on the two statuses a hosted, metered gateway actually returns. A 402 is an account out
     * of credit or past its spending cap, and sending its owner to re-read their base URL is worse
     * than saying nothing; a 403 is a moderation or permission refusal, which no amount of checking
     * the model name resolves. The provider's own body still follows, because it is the half that
     * says <em>which</em> guardrail or which cap.
     *
     * <p>Deliberately phrased as what the status usually means rather than as a verdict: this is
     * shared by every plain-HTTP provider here, and a corporate gateway is free to use these codes
     * its own way.
     */
    private static String remedyFor(int status, String upstreamProvider) {
        if (upstreamProvider != null) {
            // Said before anything else, because every other sentence here would name the wrong
            // thing to go and change. The gateway accepted the request and passed it on; what
            // failed is one provider behind it, chosen by a routing decision this application does
            // not make and cannot repeat. "The endpoint rejected the request body" sent an operator
            // to audit a body that was provably fine — the same bytes succeeded through another
            // provider for the same model, minutes apart.
            return "the upstream provider '" + upstreamProvider + "' failed and the gateway relayed "
                + "its answer, so this is that provider's doing rather than a wrong request or a "
                + "wrong setting here — trying again may route elsewhere, and a model served by a "
                + "single provider is the one worth replacing";
        }
        return switch (status) {
            case 400, 422 -> "the endpoint rejected the request body (a field it does not implement, "
                + "or a malformed one)";
            case 401 -> "the API key was refused (check claude.api-key, or the variable it is bound "
                + "to)";
            case 402 -> "payment required: the account is out of credit or past a spending cap — "
                + "topping it up is the fix, not the configuration";
            case 403 -> "refused as a permission, guardrail or moderation matter, not a "
                + "configuration one";
            case 404 -> "no such model or endpoint (check claude.model and claude.base-url)";
            case 413 -> "the request was too large — lower process-mining.prompt-char-budget or "
                + "claude.max-tokens";
            default -> "check base URL, model and API key";
        };
    }

    /**
     * The provider a routing gateway is blaming, or {@code null} when the error is its own.
     *
     * <p>OpenRouter relays an upstream failure verbatim under
     * {@code error.metadata.provider_name}, with that provider's own payload beside it in
     * {@code metadata.raw} — the status then describes what happened somewhere else. Read
     * defensively: this runs on an error body, which is exactly where nothing about the shape is
     * guaranteed, and a body it cannot parse must leave the diagnosis as it was rather than replace
     * one wrong answer with another.
     */
    static String upstreamProviderOf(String body) {
        if (body == null || !body.contains("provider_name")) {
            return null;
        }
        try {
            JsonNode name = ERROR_MAPPER.readTree(body).path("error").path("metadata")
                .path("provider_name");
            if (!name.isTextual() || name.asText().isBlank()) {
                return null;
            }
            // Neutralised here rather than at each place it comes back out: it lands in an
            // exception message that is logged, and it is a string a remote host chose.
            return LogSafe.slug(name.asText().strip());
        } catch (Exception e) {
            return null;
        }
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
