// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.mcp.security;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class McpOAuthFilterTest {
    private final McpProperties.OAuth oauth = new McpProperties.OAuth();
    {
        oauth.setIssuerUri("https://issuer.example.test");
        oauth.setResourceUri("https://kafka.example.test/mcp");
    }

    private McpHttpAuthFilter filter(String scopes) {
        JwtDecoder decoder = token -> {
            if (!"valid".equals(token)) throw new JwtException("invalid token");
            return Jwt.withTokenValue(token)
                    .header("alg", "RS256")
                    .issuer("https://issuer.example.test")
                    .subject("operator")
                    .audience(List.of("kafkaexplorer"))
                    .issuedAt(Instant.now().minusSeconds(10))
                    .expiresAt(Instant.now().plusSeconds(300))
                    .claim("scope", scopes)
                    .build();
        };
        return new McpHttpAuthFilter(decoder, oauth, false);
    }

    private static int call(McpHttpAuthFilter filter, String path, String method,
                            String token, AtomicBoolean reached) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (token != null) request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain() {
            @Override public void doFilter(ServletRequest req, ServletResponse res) {
                reached.set(true);
                assertEquals(McpHttpAuthFilter.identityOf("https://issuer.example.test:operator"),
                        McpCallerContext.identity());
                assertFalse(McpCallerContext.verifiedScopes().isEmpty());
            }
        });
        assertEquals("local", McpCallerContext.identity());
        assertTrue(McpCallerContext.verifiedScopes().isEmpty());
        return response.getStatus();
    }

    @Test void readScopeAllowsMcpAndRejectsAdmin() throws Exception {
        AtomicBoolean reached = new AtomicBoolean();
        McpHttpAuthFilter filter = filter("mcp.read");
        assertEquals(200, call(filter, "/mcp", "POST", "valid", reached));
        assertTrue(reached.get());
        reached.set(false);
        assertEquals(403, call(filter, "/api/mcp/try/kex_list_topics", "POST", "valid", reached));
        assertFalse(reached.get());
    }

    @Test void auditRequiresDedicatedScope() throws Exception {
        AtomicBoolean reached = new AtomicBoolean();
        assertEquals(403, call(filter("mcp.read mcp.admin"), "/api/mcp/calls/replay", "GET", "valid", reached));
        assertEquals(200, call(filter("mcp.audit"), "/api/mcp/calls/replay", "GET", "valid", reached));
    }

    @Test void missingAndInvalidTokensFailClosed() throws Exception {
        AtomicBoolean reached = new AtomicBoolean();
        assertEquals(401, call(filter("mcp.read"), "/mcp", "POST", null, reached));
        assertEquals(401, call(filter("mcp.read"), "/mcp", "POST", "invalid", reached));
        assertFalse(reached.get());
    }

    @Test void unauthorizedRequestsAdvertiseCanonicalProtectedResourceMetadata() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter("mcp.read").doFilter(request, response, new MockFilterChain());
        assertEquals(401, response.getStatus());
        assertEquals("Bearer realm=\"mcp\", resource_metadata=\"https://kafka.example.test/.well-known/oauth-protected-resource/mcp\"",
                response.getHeader("WWW-Authenticate"));
        assertTrue(McpCallerContext.verifiedScopes().isEmpty());
    }

    @Test void staticTokenCannotBypassOAuthMode() throws Exception {
        AtomicBoolean reached = new AtomicBoolean();
        assertEquals(401, call(filter("mcp.read"), "/mcp", "POST", "test-secret-token", reached));
        assertFalse(reached.get());
    }
}
