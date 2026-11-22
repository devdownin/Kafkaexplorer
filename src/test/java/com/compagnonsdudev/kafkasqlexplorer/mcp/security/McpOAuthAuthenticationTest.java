// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.security;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpOAuthAuthenticationTest {
    private static final String ISSUER = "https://identity.example.test/realms/kafka";
    private static final String JWKS = ISSUER + "/protocol/openid-connect/certs";
    private static final String AUDIENCE = "https://explorer.example.test/mcp";

    private static McpProperties.OAuth settings() {
        var config = new McpProperties.OAuth();
        config.setEnabled(true);
        config.setIssuerUri(ISSUER);
        config.setJwkSetUri(JWKS);
        config.setResourceUri(AUDIENCE);
        return config;
    }

    private static Jwt token(String issuer, String audience, Instant expires, String scopes) {
        return Jwt.withTokenValue("not-a-real-token")
                .header("alg", "RS256")
                .claim("iss", issuer)
                .claim("sub", "service-account-A")
                .claim("aud", List.of(audience))
                .claim("exp", expires)
                .claim("scope", scopes)
                .build();
    }

    private static McpOAuthAuthentication withToken(Jwt jwt) {
        JwtDecoder decoder = value -> "valid".equals(value) ? jwt : invalid();
        return new McpOAuthAuthentication(settings(), decoder);
    }

    private static Jwt invalid() { throw new JwtException("Bad token signature"); }

    @AfterEach
    void cleanup() { McpCallerContext.clear(); }

    @Test
    void refuses_missing_configuration_and_insecure_jwks_at_startup() {
        var wrongJwks = settings();
        wrongJwks.setJwkSetUri("http://localhost:8080/certs");
        assertThatThrownBy(() -> McpOAuthAuthentication.validate(wrongJwks))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("jwk-set-uri");
        var wrongResource = settings();
        wrongResource.setResourceUri("https://explorer.example.test/other");
        assertThatThrownBy(() -> McpOAuthAuthentication.validate(wrongResource))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("/mcp");
        var invalidToolScope = settings();
        invalidToolScope.setToolScopes(Map.of("kex_run_audit", "bad scope"));
        assertThatThrownBy(() -> McpOAuthAuthentication.validate(invalidToolScope))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validates_exact_issuer_audience_subject_expiry_and_scopes() {
        var valid = withToken(token(ISSUER, AUDIENCE, Instant.now().plusSeconds(120), "mcp:read mcp:audit"));
        var first = valid.authenticate("valid");
        assertThat(first.scopes()).containsExactlyInAnyOrder("mcp:read", "mcp:audit");
        assertThat(first.identity()).startsWith("oauth:").doesNotContain("service-account-A");
        assertThat(valid.authenticate("valid").identity()).isEqualTo(first.identity());
        assertThatThrownBy(() -> valid.authenticate("forged")).isInstanceOf(JwtException.class);

        assertThatThrownBy(() -> withToken(token("https://evil.example", AUDIENCE,
                Instant.now().plusSeconds(60), "mcp:read")).authenticate("valid"))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> withToken(token(ISSUER, "https://another-resource.example/mcp",
                Instant.now().plusSeconds(60), "mcp:read")).authenticate("valid"))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> withToken(token(ISSUER, AUDIENCE,
                Instant.now().minusSeconds(60), "mcp:read")).authenticate("valid"))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void refuses_legacy_static_token_and_wrong_scopes_without_continuing_the_chain() throws Exception {
        var filter = new McpHttpAuthFilter("legacy-static-token", false,
                withToken(token(ISSUER, AUDIENCE, Instant.now().plusSeconds(120), "mcp:read")));

        var noToken = request("/mcp", "POST", null);
        var noTokenResponse = new MockHttpServletResponse();
        var noTokenChain = new RecordingChain();
        filter.doFilter(noToken, noTokenResponse, noTokenChain);
        assertThat(noTokenResponse.getStatus()).isEqualTo(401);
        assertThat(noTokenResponse.getHeader("WWW-Authenticate"))
                .contains("resource_metadata=\"https://explorer.example.test/.well-known/oauth-protected-resource/mcp\"");
        assertThat(noTokenChain.visited).isFalse();

        var legacy = request("/mcp", "POST", "legacy-static-token");
        var legacyResponse = new MockHttpServletResponse();
        filter.doFilter(legacy, legacyResponse, new RecordingChain());
        assertThat(legacyResponse.getStatus()).isEqualTo(401);

        var admin = request("/api/mcp/toggle/readonly", "POST", "valid");
        var adminResponse = new MockHttpServletResponse();
        var adminChain = new RecordingChain();
        filter.doFilter(admin, adminResponse, adminChain);
        assertThat(adminResponse.getStatus()).isEqualTo(403);
        assertThat(adminResponse.getHeader("WWW-Authenticate")).contains("insufficient_scope", "mcp:admin");
        assertThat(adminChain.visited).isFalse();

        var replay = request("/api/mcp/calls/replay", "GET", "valid");
        var replayResponse = new MockHttpServletResponse();
        filter.doFilter(replay, replayResponse, new RecordingChain());
        assertThat(replayResponse.getStatus()).isEqualTo(403);
    }

    @Test
    void authenticates_console_reads_and_clears_the_scope_context_after_success() throws Exception {
        var filter = new McpHttpAuthFilter("unused", false,
                withToken(token(ISSUER, AUDIENCE, Instant.now().plusSeconds(120), "mcp:read")));
        var req = request("/api/mcp/catalog", "GET", "valid");
        var res = new MockHttpServletResponse();
        var chain = new RecordingChain();
        filter.doFilter(req, res, chain);
        assertThat(chain.visited).isTrue();
        assertThat(chain.identity).startsWith("oauth:");
        assertThat(chain.readAllowed).isTrue();
        assertThat(chain.adminAllowed).isFalse();
        assertThat(McpCallerContext.authenticated()).isEmpty();
        assertThat(McpCallerContext.hasScope("mcp:read")).isFalse();
    }

    @Test
    void admin_token_can_mutate_console_without_being_implicitly_a_read_token() throws Exception {
        var filter = new McpHttpAuthFilter("", false,
                withToken(token(ISSUER, AUDIENCE, Instant.now().plusSeconds(120), "mcp:admin")));
        var req = request("/api/mcp/quarantine/client", "POST", "valid");
        var res = new MockHttpServletResponse();
        var chain = new RecordingChain();
        filter.doFilter(req, res, chain);
        assertThat(chain.visited).isTrue();
        assertThat(chain.adminAllowed).isTrue();
        var readResponse = new MockHttpServletResponse();
        filter.doFilter(request("/api/mcp/catalog", "GET", "valid"), readResponse, new RecordingChain());
        assertThat(readResponse.getStatus()).isEqualTo(403);
    }

    @Test
    void published_metadata_is_unambiguous_and_does_not_echo_host_headers() {
        var properties = new McpProperties();
        properties.setOauth(settings());
        var metadata = new McpOAuthResourceMetadataController(properties).metadata();
        assertThat(metadata.getStatusCode().value()).isEqualTo(200);
        assertThat(metadata.getBody()).containsEntry("resource", AUDIENCE)
                .containsEntry("authorization_servers", List.of(ISSUER));
        properties.getOauth().setEnabled(false);
        assertThat(new McpOAuthResourceMetadataController(properties).metadata().getStatusCode().value())
                .isEqualTo(404);
    }

    private static MockHttpServletRequest request(String path, String method, String bearer) {
        var request = new MockHttpServletRequest(method, path);
        if (bearer != null) request.addHeader("Authorization", "Bearer " + bearer);
        return request;
    }

    private static class RecordingChain extends MockFilterChain {
        boolean visited;
        String identity;
        boolean readAllowed;
        boolean adminAllowed;

        @Override public void doFilter(ServletRequest request, ServletResponse response) {
            visited = true;
            identity = McpCallerContext.identity();
            readAllowed = McpCallerContext.hasScope("mcp:read");
            adminAllowed = McpCallerContext.hasScope("mcp:admin");
        }
    }
}
