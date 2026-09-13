// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.guard;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpRateLimiterBoundTest {
    @Test
    void identityCardinalityIsBoundedAgainstMemoryExhaustion() {
        McpProperties properties = new McpProperties();
        properties.getRateLimit().setCallsPerMinute(60);
        properties.getRateLimit().setBurst(1);
        McpRateLimiter limiter = new McpRateLimiter(properties);

        for (int i = 0; i < 10_500; i++) {
            try {
                limiter.check("identity-" + i);
            } catch (McpToolException ignored) {
                // The first call normally consumes the burst; cardinality is what this test checks.
            }
        }

        assertTrue(limiter.identityCount() <= McpRateLimiter.MAX_IDENTITIES);
    }

    @Test
    void resetRemovesAnIdentityBucket() {
        McpProperties properties = new McpProperties();
        properties.getRateLimit().setCallsPerMinute(60);
        McpRateLimiter limiter = new McpRateLimiter(properties);
        assertDoesNotThrow(() -> limiter.check("identity"));
        assertTrue(limiter.identityCount() > 0);
        limiter.reset("identity");
        assertTrue(limiter.identityCount() == 0);
    }
}
