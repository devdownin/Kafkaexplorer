// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.mcp.security;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpOAuth2AuthFilterTest {
    private static final String ISSUER = "https://id.example.test";
    private static final String AUDIENCE = "kafkaexplorer-mcp";

    private static Jwt jwt(String scope, String audience) {
        return Jwt.withTokenValue("signed-token").header("alg", "RS256")
                .issuer(ISSUER).subject("agent-123")
                .issuedAt(Instant.now().minusSeconds(10))
                .expiresAt(Instant.now().plusSeconds(600))
                .audience(List.of(audience))
                .claim("scope", scope).build();
    }

    private static MockHttpServletRequest request(String path, String method) {
        var req = new MockHttpServletRequest(method, path);
        req.setSecure(true);
        req.addHeader("Authorization", "Bearer signed-token");
        return req;
    }

    private static final class Recorder extends MockFilterChain {
        boolean called;
        String identity;
        @Override public void doFilter(ServletRequest req, ServletResponse res) {
            called = true;
            identity = McpCallerContext.identity();
        }
    }

    @Test void requiresInvocationScopeForMcpTransport() throws Exception {
        JwtDecoder decoder = token -> jwt("mcp:invoke", AUDIENCE);
        var filter = new McpHttpAuthFilter(decoder, AUDIENCE, true);
        var response = new MockHttpServletResponse();
        var chain = new Recorder();
        filter.doFilter(request("/mcp", "POST"), response, chain);
        assertTrue(chain.called);
        assertEquals("local", McpCallerContext.identity());
        assertTrue(chain.identity.startsWith("oauth:"));
        assertFalse(chain.identity.contains("agent-123"));
    }

    @Test void deniesMissingScopeAndWrongAudience() throws Exception {
        for (Jwt bad : List.of(jwt("mcp:admin", AUDIENCE), jwt("mcp:invoke", "other-resource"))) {
            var filter = new McpHttpAuthFilter(token -> bad, AUDIENCE, true);
            var response = new MockHttpServletResponse();
            var chain = new Recorder();
            filter.doFilter(request("/mcp", "POST"), response, chain);
            assertFalse(chain.called);
            assertTrue(response.getStatus() == 401 || response.getStatus() == 403);
        }
    }

    @Test void privilegedConsoleOperationsRequireAdminScope() throws Exception {
        var req = request("/api/mcp/toggle/readonly", "POST");
        var filter = new McpHttpAuthFilter(token -> jwt("mcp:invoke", AUDIENCE), AUDIENCE, true);
        var response = new MockHttpServletResponse();
        var chain = new Recorder();
        filter.doFilter(req, response, chain);
        assertEquals(403, response.getStatus());
        assertFalse(chain.called);

        filter = new McpHttpAuthFilter(token -> jwt("mcp:admin", AUDIENCE), AUDIENCE, true);
        response = new MockHttpServletResponse();
        chain = new Recorder();
        filter.doFilter(req, response, chain);
        assertTrue(chain.called);
    }

    @Test void rejectedJwtNeverFallsBackToLegacyBearer() throws Exception {
        JwtDecoder decoder = token -> { throw new JwtException("invalid signature"); };
        var filter = new McpHttpAuthFilter(decoder, AUDIENCE, true);
        var response = new MockHttpServletResponse();
        var chain = new Recorder();
        filter.doFilter(request("/mcp", "POST"), response, chain);
        assertEquals(401, response.getStatus());
        assertFalse(chain.called);
    }
}
