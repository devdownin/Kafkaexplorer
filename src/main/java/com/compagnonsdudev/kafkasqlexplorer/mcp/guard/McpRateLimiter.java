// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.guard;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How many calls one identity may make per minute — KIP-1318's step 8, {@code -32029}.
 *
 * <p>A token bucket per identity, refilled continuously rather than reset on a boundary. A fixed
 * window lets a caller spend the whole allowance in the last second of one minute and the whole
 * allowance again in the first second of the next, which is twice the configured rate at exactly
 * the moment the cluster is least able to take it.
 *
 * <p><b>Why an agent needs this and a browser does not.</b> An operator clicks; a model loops. The
 * failure this bounds is not malice but a retry that reads its own error as something to try
 * differently — a tool that answers "not found in what was scanned" invites another pass, and a
 * model with a budget and no rate limit will spend it on the cluster the UI shares. The ceilings in
 * {@code ToolGuard} bound one call; this bounds the sequence.
 *
 * <p><b>The refusal says when to come back.</b> A bare "rate limited" teaches a model to retry
 * immediately, which is the behaviour the limit exists to stop; naming the wait in the message
 * makes the correct action the obvious one.
 *
 * <p>In memory and per process, like every other switch here. A deployment behind a load balancer
 * therefore limits per instance — stated rather than papered over, because a distributed limiter
 * needs a store this application does not have, and one that silently allowed N times the rate
 * would be the claim-without-code this module keeps removing.
 */
public class McpRateLimiter {

    /** One caller's allowance. Guarded by its own monitor: contention is per identity, not global. */
    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos;

        Bucket(double tokens) {
            this.tokens = tokens;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized long tryConsume(double capacity, double perSecond) {
            long now = System.nanoTime();
            tokens = Math.min(capacity, tokens + (now - lastRefillNanos) / 1_000_000_000.0 * perSecond);
            lastRefillNanos = now;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return 0L;
            }
            // How long until one token exists, so the refusal can name it.
            return (long) Math.ceil((1.0 - tokens) / perSecond * 1000.0);
        }
    }

    private final McpProperties properties;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public McpRateLimiter(McpProperties properties) {
        this.properties = properties;
    }

    /**
     * Takes one call's allowance, or refuses with the wait.
     *
     * <p>Zero or a negative {@code calls-per-minute} turns the limiter off rather than refusing
     * everything: a misread setting that locked out every agent would be a far worse failure than
     * one that let them through, and "0 means unlimited" is how the neighbouring
     * {@code explorer.max-concurrent-jobs} already reads.
     */
    public void check(String identity) {
        int perMinute = properties.getRateLimit().getCallsPerMinute();
        if (perMinute <= 0) {
            return;
        }
        double perSecond = perMinute / 60.0;
        double capacity = Math.max(1.0, properties.getRateLimit().getBurst());
        String key = identity == null ? "unknown" : identity;

        long waitMs = buckets.computeIfAbsent(key, k -> new Bucket(capacity))
                .tryConsume(capacity, perSecond);
        if (waitMs > 0) {
            throw new McpToolException(McpErrorCode.RATE_LIMITED, McpGuard.RATE_LIMIT,
                    ("%s has used its allowance of %d calls per minute (burst %d). Wait about %d ms "
                            + "before the next call — retrying immediately is what this limit "
                            + "exists to stop. The limit is per server instance "
                            + "(explorer.mcp.rate-limit.calls-per-minute).")
                            .formatted(key, perMinute, (int) capacity, waitMs));
        }
    }

    /** Forgets one identity's bucket — used when an operator lifts a quarantine. */
    public void reset(String identity) {
        buckets.remove(identity);
    }
}
