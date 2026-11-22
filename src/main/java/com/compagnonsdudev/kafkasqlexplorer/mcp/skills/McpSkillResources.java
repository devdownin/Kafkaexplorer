// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.skills;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpErrorCode;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpGuard;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpToolException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.mcp.annotation.McpResource;

import java.nio.charset.StandardCharsets;

/**
 * Portable Skills over the currently supported MCP Resources primitive.
 *
 * <p>This is deliberately NOT an implementation of the SEP-2640 skills/list and skills/get
 * extension. Java SDK support and capability negotiation are still pending. Advertise the
 * template and catalogue as ordinary resources until both extension methods are available.
 */
public final class McpSkillResources {
    private final McpSkillCatalog skills;
    private final McpProperties properties;
    private final ObjectMapper json = new ObjectMapper();

    public McpSkillResources(McpSkillCatalog skills, McpProperties properties) {
        this.skills = skills;
        this.properties = properties;
    }

    @McpResource(uri = "kex://skills", name = "KafkaExplorer Agent Skills catalogue",
            description = "Immutable skill manifests, SHA-256 digests and skill:// URIs. "
                    + "The Skills extension is NOT negotiated on this Java transport.",
            mimeType = "application/json")
    public String catalogue() {
        try {
            return bounded(json.writeValueAsString(skills.entries()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize Agent Skills catalogue", e);
        }
    }

    @McpResource(uri = "skill://{name}/SKILL.md", name = "KafkaExplorer Agent Skill",
            description = "Read a versioned SKILL.md by its exact catalogue name. Reading a "
                    + "resource does not activate the skill: loading and consent belong to the host.",
            mimeType = "text/markdown")
    public String read(String name) {
        var detail = skills.find(name).orElseThrow(() ->
                new McpToolException(McpErrorCode.VALIDATION_FAILED, McpGuard.POLICY,
                        "Unknown Agent Skill. Read kex://skills for the published names."));
        // These bytes are code-reviewed, packaged static documentation, not cluster-derived data.
        // Do not apply DLP rewriting: resources/read bytes must match the published SHA-256 digest.
        return bounded(detail.content());
    }

    private String bounded(String value) {
        int size = value.getBytes(StandardCharsets.UTF_8).length;
        if (size > properties.getHardMaxOutputBytes()) {
            throw new McpToolException(McpErrorCode.POLICY_DENIED, McpGuard.POLICY,
                    "Skill resource exceeds the configured MCP output byte limit.");
        }
        return value;
    }
}
