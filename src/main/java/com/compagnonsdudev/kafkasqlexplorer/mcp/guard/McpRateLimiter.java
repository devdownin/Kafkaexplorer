// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.guard;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;

/** Per-caller token bucket with bounded identity cardinality and idle expiry. */
public class McpRateLimiter {
    static final long MAX_IDENTITIES = 10_000L;
    static final Duration IDLE_EXPIRY = Duration.ofMinutes(15);
    private static final class Bucket {
        private double tokens; private long lastRefillNanos;
        Bucket(double tokens) { this.tokens = tokens; this.lastRefillNanos = System.nanoTime(); }
        synchronized long tryConsume(double capacity, double perSecond) {
            long now = System.nanoTime();
            tokens = Math.min(capacity, tokens + (now - lastRefillNanos) / 1_000_000_000.0 * perSecond);
            lastRefillNanos = now;
            if (tokens >= 1.0) { tokens -= 1.0; return 0L; }
            return (long) Math.ceil((1.0 - tokens) / perSecond * 1000.0);
        }
    }
    private final McpProperties properties;
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder().maximumSize(MAX_IDENTITIES)
            .expireAfterAccess(IDLE_EXPIRY).build();
    public McpRateLimiter(McpProperties properties) { this.properties = properties; }
    public void check(String identity) {
        int perMinute = properties.getRateLimit().getCallsPerMinute();
        if (perMinute <= 0) return;
        double perSecond = perMinute / 60.0;
        double capacity = Math.max(1.0, properties.getRateLimit().getBurst());
        String key = identity == null ? "unknown" : identity;
        long waitMs = buckets.get(key, k -> new Bucket(capacity)).tryConsume(capacity, perSecond);
        if (waitMs > 0) {
            throw new McpToolException(McpErrorCode.RATE_LIMITED, McpGuard.RATE_LIMIT,
                    ("%s has used its allowance of %d calls per minute (burst %d). Wait about %d ms "
                            + "before the next call — retrying immediately is what this limit exists "
                            + "to stop. The limit is per server instance "
                            + "(explorer.mcp.rate-limit.calls-per-minute).")
                            .formatted(key, perMinute, (int) capacity, waitMs));
        }
    }
    public void reset(String identity) { buckets.invalidate(identity); }
    int identityCount() { return (int) buckets.estimatedSize(); }
}
