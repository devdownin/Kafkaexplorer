// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.oauth2.jwt.JwtException;

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
    private final McpOAuthAuthentication oauth;

    public McpHttpAuthFilter(String configuredToken) { this(configuredToken, true); }

    public McpHttpAuthFilter(String configuredToken, boolean requireTls) {
        this(configuredToken, requireTls, null);
    }

    /** A configured OAuth verifier replaces (never supplements) the legacy static token. */
    public McpHttpAuthFilter(String configuredToken, boolean requireTls, McpOAuthAuthentication oauth) {
        this.configuredToken = configuredToken == null ? "" : configuredToken.trim();
        this.requireTls = requireTls;
        this.oauth = oauth;
    }

    /**
     * Which requests this boundary covers. {@code true} means <em>skip the filter</em>, and reading
     * that the other way round is what shipped: the {@code /api/mcp/**} branch returned {@code true}
     * — no token — for {@code try}, {@code toggle}, {@code quarantine} and {@code approve}, the four
     * endpoints that change what this server does, while every ordinary console read answered 401 to
     * a page that sends no header. {@code McpHttpAuthFilterTest} asserted the opposite from the
     * first commit and failed on every build since.
     *
     * <p>So: {@code /mcp} is always covered. Under {@code /api/mcp/**} a privileged request is
     * covered and an ordinary console read is not — the console is this application's own screen and
     * answers to whatever protects the application, while a browser has no bearer token to offer.
     *
     * <p><b>Privilege is decided by exclusion, not by a list of paths.</b> Any non-GET under
     * {@code /api/mcp/**} changes state, so an endpoint added later is covered by default rather
     * than left open by an omission nobody notices. The one privileged read is named instead:
     * {@code /calls/replay} reads the audit topic — every call and every refusal, not the live ring
     * the screen already shows.
     */
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) path = path.substring(context.length());
        if ("/mcp".equals(path) || path.startsWith("/mcp/")) return false;
        if (!path.startsWith("/api/mcp/")) return true;
        // In enterprise OAuth mode, catalogue and usage data are private too: the browser must
        // present an access token. Legacy mode preserves the existing public console reads.
        if (oauth != null) return false;
        if (!"GET".equalsIgnoreCase(request.getMethod())) return false;
        return !"/api/mcp/calls/replay".equals(path);
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
        if (oauth != null) {
            filterOAuth(request, response, filterChain);
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

    private void filterOAuth(HttpServletRequest request, HttpServletResponse response,
                             FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        boolean admin = path.startsWith("/api/mcp/")
                && (!"GET".equalsIgnoreCase(request.getMethod()) || "/api/mcp/calls/replay".equals(path));
        String neededScope = admin ? oauth.adminScope() : oauth.readScope();
        String bearer = bearerToken(request);
        if (bearer == null) {
            oauthRefusal(response, HttpServletResponse.SC_UNAUTHORIZED, "invalid_token",
                    "mcp_unauthorized", neededScope);
            return;
        }
        McpOAuthAuthentication.Caller caller;
        try {
            caller = oauth.authenticate(bearer);
        } catch (JwtException | IllegalArgumentException e) {
            oauthRefusal(response, HttpServletResponse.SC_UNAUTHORIZED, "invalid_token",
                    "mcp_unauthorized", neededScope);
            return;
        }
        if (!caller.scopes().contains(neededScope)) {
            oauthRefusal(response, HttpServletResponse.SC_FORBIDDEN, "insufficient_scope",
                    "mcp_insufficient_scope", neededScope);
            return;
        }
        request.setAttribute(IDENTITY_ATTRIBUTE, caller.identity());
        McpCallerContext.set(caller.identity(), caller.scopes());
        try { chain.doFilter(request, response); }
        finally { McpCallerContext.clear(); request.removeAttribute(IDENTITY_ATTRIBUTE); }
    }

    private void oauthRefusal(HttpServletResponse response, int status, String code, String message,
                              String neededScope) throws IOException {
        response.setStatus(status);
        String challenge = "Bearer realm=" + '"' + "mcp" + '"'
                + ", resource_metadata=" + '"' + oauth.metadataUrl() + '"'
                + ", error=" + '"' + code + '"'
                + ", scope=" + '"' + neededScope + '"';
        response.setHeader("WWW-Authenticate", challenge);
        response.setContentType("application/json");
        response.getWriter().write("{" + '"' + "error" + '"' + ":" + '"' + message + '"' + "}");
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
