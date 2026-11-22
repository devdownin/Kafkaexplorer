// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.security;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * RFC 9728 protected-resource discovery.
 *
 * <p>Unauthenticated by design. The response uses configured canonical HTTPS URLs only;
 * neither Host nor forwarded proxy headers influence the metadata or OAuth challenge.
 */
@RestController
@ConditionalOnProperty(prefix = "explorer.mcp", name = {"enabled", "oauth.enabled"}, havingValue = "true")
public final class McpOAuthResourceMetadataController {
    private final McpOAuthProtectedResourceMetadata metadata;

    public McpOAuthResourceMetadataController(McpProperties properties) {
        this.metadata = new McpOAuthProtectedResourceMetadata(properties.getOauth());
    }

    @GetMapping(path = {
            "/.well-known/oauth-protected-resource",
            "/.well-known/oauth-protected-resource/**"
    }, produces = "application/json")
    public ResponseEntity<Map<String, Object>> resourceMetadata(HttpServletRequest request) {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        if (!metadata.matchesPath(path)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(metadata.document());
    }
}
