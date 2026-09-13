// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.mcp.security.McpCallerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpTraceStoreTest {

    private final McpTraceStore store = new McpTraceStore();

    @AfterEach
    void clearContext() {
        McpCallerContext.clear();
    }

    @Test
    void resumeTokenIsBoundToCreatingCaller() {
        McpCallerContext.set("bearer:alice");
        String token = store.remember(null, List.of("topic-a"), List.of(), null);

        McpCallerContext.set("bearer:bob");
        assertTrue(store.take(token).isEmpty(), "another principal must not redeem the token");

        McpCallerContext.set("bearer:alice");
        assertFalse(store.take(token).isEmpty(), "the creating principal may redeem the token");
        assertTrue(store.take(token).isEmpty(), "resume tokens remain single-use");
    }

    @Test
    void crossPrincipalFailureDoesNotConsumeTheOwnerToken() {
        McpCallerContext.set("bearer:alice");
        String token = store.remember(null, List.of("topic-a"), List.of(), null);

        McpCallerContext.set("bearer:bob");
        assertTrue(store.take(token).isEmpty());

        McpCallerContext.set("bearer:alice");
        assertEquals(List.of("topic-a"), store.take(token).orElseThrow().remainingTopics());
    }
}
