// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.security;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** RFC 9728 metadata for the root-path /mcp protected resource.
 * No Host/X-Forwarded-Host data is trusted; the public HTTPS URL is operator-configured.
 * This discovery endpoint must remain public for conforming OAuth clients.
 */
@RestController
@ConditionalOnProperty(prefix = "explorer.mcp", name = "enabled", havingValue = "true")
public final class McpOAuthResourceMetadataController {
    private final McpProperties properties;

    public McpOAuthResourceMetadataController(McpProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/.well-known/oauth-protected-resource/mcp")
    public ResponseEntity<Map<String, Object>> metadata() {
        if (!properties.getOauth().isEnabled()) return ResponseEntity.notFound().build();
        McpProperties.OAuth oauth = properties.getOauth();
        // The constructor enforces root /mcp and HTTPS, even if the bearer filter is not used.
        McpOAuthAuthentication.validate(oauth);
        var scopes = new java.util.TreeSet<String>();
        scopes.add(oauth.getReadScope());
        scopes.add(oauth.getWriteScope());
        scopes.add(oauth.getAdminScope());
        scopes.addAll(oauth.getToolScopes().values());
        return ResponseEntity.ok()
                .header("Cache-Control", "public, max-age=300")
                .body(Map.of(
                        "resource", oauth.getResourceUri(),
                        "authorization_servers", List.of(oauth.getIssuerUri()),
                        "bearer_methods_supported", List.of("header"),
                        "scopes_supported", List.copyOf(scopes)));
    }
}
