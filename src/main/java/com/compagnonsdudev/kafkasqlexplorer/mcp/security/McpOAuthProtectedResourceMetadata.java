// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.security;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** RFC 9728 metadata derived only from operator-configured public URLs, never request Host. */
public final class McpOAuthProtectedResourceMetadata {
    public static final String WELL_KNOWN = "/.well-known/oauth-protected-resource";

    private final String resourceUri;
    private final String metadataUrl;
    private final String resourcePath;
    private final Map<String, Object> document;

    public McpOAuthProtectedResourceMetadata(McpProperties.OAuth oauth) {
        if (oauth.getResourceUri() == null || oauth.getResourceUri().isBlank()) {
            throw new IllegalStateException("MCP OAuth requires explorer.mcp.oauth.resource-uri");
        }
        URI uri;
        try {
            uri = URI.create(oauth.getResourceUri());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("MCP OAuth resource-uri must be a canonical HTTPS URL", exception);
        }
        String path = uri.getRawPath();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || path == null || !path.endsWith("/mcp")) {
            throw new IllegalStateException("MCP OAuth resource-uri must be a public HTTPS endpoint ending in /mcp");
        }
        this.resourceUri = uri.toString();
        this.resourcePath = path;
        this.metadataUrl = uri.getScheme() + "://" + uri.getRawAuthority() + WELL_KNOWN + path;

        TreeSet<String> scopes = new TreeSet<>();
        scopes.add(oauth.getReadScope());
        scopes.add(oauth.getAdminScope());
        scopes.add(oauth.getAuditScope());
        scopes.addAll(oauth.getToolScopes().values());
        this.document = Map.of(
                "resource", this.resourceUri,
                "authorization_servers", List.of(oauth.getIssuerUri()),
                "scopes_supported", List.copyOf(scopes),
                "bearer_methods_supported", List.of("header"));
    }

    public String resourceUri() { return resourceUri; }
    public String metadataUrl() { return metadataUrl; }
    public String resourcePath() { return resourcePath; }
    public Map<String, Object> document() { return document; }

    /** Root and path-specific well-known URLs are served for client compatibility. */
    public boolean matchesPath(String requestPath) {
        return WELL_KNOWN.equals(requestPath) || (WELL_KNOWN + resourcePath).equals(requestPath);
    }
}
