// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.resources;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpErrorCode;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpGuard;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpRateLimiter;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpRuntimeSwitches;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpToolException;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpCatalogService;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.ToolDescriptor;
import com.compagnonsdudev.kafkasqlexplorer.mcp.security.McpCallerContext;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.TopicMcpTools;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.mcp.annotation.McpResource;

import java.nio.charset.StandardCharsets;

/**
 * Read-only MCP resources backed by the same scoped and measured tool adapters as the UI.
 *
 * <p>Resources do not pass through McpToolInterceptor. Deliberately repeat its admission guards
 * here: otherwise resources/read would bypass the tool allow-list, runtime kill switch, rate
 * budget, output ceiling and DLP. Never read KafkaAdminService directly from this class.
 */
public final class KafkaMcpResources {
    private final TopicMcpTools topics;
    private final McpCatalogService catalog;
    private final McpRuntimeSwitches switches;
    private final McpRateLimiter limiter;
    private final ToolGuard guard;
    private final ObjectMapper json = new ObjectMapper();

    public KafkaMcpResources(TopicMcpTools topics, McpCatalogService catalog,
                             McpRuntimeSwitches switches, McpRateLimiter limiter, ToolGuard guard) {
        this.topics = topics;
        this.catalog = catalog;
        this.switches = switches;
        this.limiter = limiter;
        this.guard = guard;
    }

    @McpResource(uri = "kex://contract", name = "KafkaExplorer measurement contract",
            description = "How to interpret measured values, coverage, partial reads and refusals.",
            mimeType = "text/plain")
    public String contract() {
        return """
                KafkaExplorer MCP read contract
                A missing or failed measurement is not zero. Inspect measured and reason.
                Inspect coverage.stopReason and topicsNotReached before making absence claims.
                EXHAUSTED means the requested bounded scan finished, not that the topic has never
                contained other records. A capped preview is a sample, never an exhaustive search.
                Use tools for scoped diagnostics, trace resume, audits and forecasting.
                Resources are snapshots and must not be used as a substitute for fresh measurements.
                Do not claim an incident is resolved without a new comparable measurement.
                """;
    }

    @McpResource(uri = "kafka://topics", name = "Bounded Kafka topic inventory",
            description = "First 20 in-scope non-DLQ topics, with Measured counts and Coverage. "
                    + "Use kex_list_topics for prefix filtering and pagination.",
            mimeType = "application/json")
    public String topicInventory() {
        admission("kex_list_topics");
        return encode(topics.listTopics(null, false, 20));
    }

    @McpResource(uri = "kafka://topics/{topic}", name = "Kafka topic details",
            description = "Describe one in-scope topic with offsets, partitions, format and measured coverage.",
            mimeType = "application/json")
    public String topic(String topic) {
        admission("kex_describe_topic");
        guard.checkTopicScope(topic);
        return encode(topics.describeTopic(topic));
    }

    private void admission(String tool) {
        boolean exposed = catalog.exposed().stream().map(ToolDescriptor::name).anyMatch(tool::equals);
        if (!exposed || switches.toolDisabled(tool).isPresent()) {
            throw new McpToolException(McpErrorCode.POLICY_DENIED, McpGuard.POLICY,
                    "The backing tool " + tool + " is not available to this caller.");
        }
        String identity = McpCallerContext.identity();
        if (switches.quarantineOf(identity).isPresent()) {
            throw new McpToolException(McpErrorCode.POLICY_DENIED, McpGuard.POLICY,
                    "This MCP caller is quarantined.");
        }
        limiter.check(identity);
    }

    private String encode(Object value) {
        try {
            String encoded = guard.dlp().scrub(json.writeValueAsString(value));
            int bytes = encoded.getBytes(StandardCharsets.UTF_8).length;
            int maximum = guard.properties().getHardMaxOutputBytes();
            if (bytes > maximum) {
                throw new McpToolException(McpErrorCode.POLICY_DENIED, McpGuard.POLICY,
                        "The resource snapshot exceeds the MCP output limit (" + bytes
                                + " > " + maximum + " bytes). Use a narrower tool query.");
            }
            return encoded;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize the measured MCP resource", e);
        }
    }
}
