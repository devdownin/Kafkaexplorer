// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.security;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * OAuth 2.1 *protected resource*, not an authorization server. JWT signatures are verified against
 * the trusted issuer's explicitly configured HTTPS JWKS endpoint, with issuer, timestamp and
 * exact resource audience validation. No request header can select a key source or an issuer.
 *
 * <p>Legacy static bearer mode and OAuth mode are mutually exclusive; an OAuth installation
 * must never fall back to EXPLORER_MCP_AUTH_TOKEN after a JWT validation failure.
 */
public final class McpOAuthAuthentication {
    public record Caller(String identity, Set<String> scopes) {}

    private final McpProperties.OAuth settings;
    private final JwtDecoder decoder;
    private final String metadataUrl;

    public McpOAuthAuthentication(McpProperties.OAuth settings) {
        this(settings, newDecoder(settings));
    }

    /** Decoder injection is deliberately package-private for deterministic negative tests. */
    McpOAuthAuthentication(McpProperties.OAuth settings, JwtDecoder decoder) {
        validate(settings);
        this.settings = settings;
        this.decoder = decoder;
        URI resource = URI.create(settings.getResourceUri());
        this.metadataUrl = resource.getScheme() + "://" + resource.getRawAuthority()
                + "/.well-known/oauth-protected-resource/mcp";
    }

    private static JwtDecoder newDecoder(McpProperties.OAuth oauth) {
        validate(oauth);
        // RS256 is explicit; unsigned JWTs and algorithm substitutions are not accepted.
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(oauth.getJwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(oauth.getIssuerUri()),
                jwt -> jwt.getAudience() != null && jwt.getAudience().contains(oauth.getResourceUri())
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                                "invalid_token", "Wrong MCP resource audience", null))));
        return decoder;
    }

    private static URI requireHttps(String value, String property) {
        try {
            URI uri = URI.create(value == null ? "" : value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPort() == 0) {
                throw new IllegalArgumentException(property + " must be an absolute HTTPS URI without query, fragment or credentials");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid explorer.mcp.oauth." + property + ": " + e.getMessage(), e);
        }
    }

    private static void requireScope(String scope, String key) {
        if (scope == null || !scope.matches("[A-Za-z0-9:_-]+")) {
            throw new IllegalArgumentException("explorer.mcp.oauth." + key + " must be one nonempty scope token");
        }
    }

    static void validate(McpProperties.OAuth oauth) {
        if (oauth == null) throw new IllegalArgumentException("Missing explorer.mcp.oauth configuration");
        requireHttps(oauth.getIssuerUri(), "issuer-uri");
        requireHttps(oauth.getJwkSetUri(), "jwk-set-uri");
        URI resource = requireHttps(oauth.getResourceUri(), "resource-uri");
        if (!"/mcp".equals(resource.getRawPath())) {
            throw new IllegalArgumentException("explorer.mcp.oauth.resource-uri must identify the public /mcp endpoint");
        }
        requireScope(oauth.getReadScope(), "read-scope");
        requireScope(oauth.getWriteScope(), "write-scope");
        requireScope(oauth.getAdminScope(), "admin-scope");
        for (var entry : oauth.getToolScopes().entrySet()) {
            if (entry.getKey() == null || !entry.getKey().matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException("Invalid configured MCP tool name in tool-scopes");
            }
            requireScope(entry.getValue(), "tool-scopes." + entry.getKey());
        }
    }

    /**
     * Rejects a missing exp, missing sub or wrong issuer/audience even when a custom decoder
     * has been supplied. A production Nimbus decoder ALSO verifies JWS, nbf and expiry.
     */
    public Caller authenticate(String accessToken) {
        Jwt jwt = decoder.decode(accessToken);
        Instant expires = jwt.getExpiresAt();
        String subject = jwt.getSubject();
        if (expires == null || !expires.isAfter(Instant.now())
                || jwt.getIssuer() == null || !jwt.getIssuer().toString().equals(settings.getIssuerUri())
                || jwt.getAudience() == null || !jwt.getAudience().contains(settings.getResourceUri())
                || subject == null || subject.isBlank()) {
            throw new JwtException("Invalid OAuth token claims");
        }
        Set<String> scopes = new LinkedHashSet<>();
        Object scope = jwt.getClaims().get("scope");
        if (scope instanceof String string) {
            Arrays.stream(string.split("\\s+")).filter(s -> !s.isBlank()).forEach(scopes::add);
        }
        Object scp = jwt.getClaims().get("scp");
        if (scp instanceof Collection<?> collection) {
            collection.stream().filter(String.class::isInstance).map(String.class::cast)
                    .filter(s -> !s.isBlank()).forEach(scopes::add);
        }
        // Do not expose issuer, user id or token to the ring, audit topic or frontend.
        return new Caller("oauth:" + sha256(settings.getIssuerUri() + "\u0000" + subject),
                Set.copyOf(scopes));
    }

    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK SHA-256 unavailable", e);
        }
    }

    public String metadataUrl() { return metadataUrl; }
    public String resourceUri() { return settings.getResourceUri(); }
    public String issuerUri() { return settings.getIssuerUri(); }
    public String readScope() { return settings.getReadScope(); }
    public String writeScope() { return settings.getWriteScope(); }
    public String adminScope() { return settings.getAdminScope(); }
    public Set<String> advertisedScopes() {
        Set<String> scopes = new LinkedHashSet<>(Set.of(readScope(), writeScope(), adminScope()));
        scopes.addAll(settings.getToolScopes().values());
        return Set.copyOf(scopes);
    }
    public Map<String, String> perToolScopes() { return settings.getToolScopes(); }
}
