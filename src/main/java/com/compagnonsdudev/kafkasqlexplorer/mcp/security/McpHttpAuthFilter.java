// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Fail-closed bearer-token and transport-security boundary for the HTTP MCP surface.
 * Production MCP requires TLS; local compose explicitly opts out for loopback development.
 */
public final class McpHttpAuthFilter extends OncePerRequestFilter {
    public static final String IDENTITY_ATTRIBUTE = McpHttpAuthFilter.class.getName() + ".identity";
    private final String configuredToken;
    private final boolean requireTls;

    public McpHttpAuthFilter(String configuredToken) { this(configuredToken, true); }

    public McpHttpAuthFilter(String configuredToken, boolean requireTls) {
        this.configuredToken = configuredToken == null ? "" : configuredToken.trim();
        this.requireTls = requireTls;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) path = path.substring(context.length());
        if ("/mcp".equals(path) || path.startsWith("/mcp/")) return false;
        if (!path.startsWith("/api/mcp/")) return true;
        if ("GET".equalsIgnoreCase(request.getMethod())) return "/api/mcp/calls/replay".equals(path);
        return path.startsWith("/api/mcp/try/") || path.startsWith("/api/mcp/toggle/")
                || path.startsWith("/api/mcp/quarantine/") || path.startsWith("/api/mcp/approve/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                FilterChain filterChain) throws ServletException, IOException {
        if (requireTls && !request.isSecure()) {
            response.setStatus(HttpServletResponse.SC_UPGRADE_REQUIRED);
            response.setHeader("Upgrade", "TLS/1.2, TLS/1.3");
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"mcp_tls_required\",\"message\":\"the MCP HTTP endpoint requires TLS\"}");
            return;
        }
        if (configuredToken.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setHeader("WWW-Authenticate", "Bearer");
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"mcp_auth_not_configured\",\"message\":\"EXPLORER_MCP_AUTH_TOKEN must be configured before the HTTP MCP surface is enabled.\"}");
            return;
        }
        String supplied = bearerToken(request);
        if (supplied == null || !constantTimeEquals(configuredToken, supplied)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", "Bearer realm=\"mcp\"");
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"mcp_unauthorized\",\"message\":\"a valid MCP bearer token is required\"}");
            return;
        }
        String identity = identityOf(supplied);
        request.setAttribute(IDENTITY_ATTRIBUTE, identity);
        McpCallerContext.set(identity);
        try { filterChain.doFilter(request, response); }
        finally { McpCallerContext.clear(); request.removeAttribute(IDENTITY_ATTRIBUTE); }
    }

    static String bearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null) return null;
        int separator = authorization.indexOf(' ');
        if (separator <= 0 || !"Bearer".equalsIgnoreCase(authorization.substring(0, separator))) return null;
        String token = authorization.substring(separator + 1).trim();
        return token.isEmpty() ? null : token;
    }

    static String identityOf(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return "bearer:" + hex;
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("JDK SHA-256 is required", e); }
    }

    private static boolean constantTimeEquals(String expected, String supplied) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
}
