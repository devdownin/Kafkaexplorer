// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.mcp.security;

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
        return new McpHttpAuthFilter(decoder, "kafkaexplorer", false);
    }

    private static int call(McpHttpAuthFilter filter, String path, String method,
                            String token, AtomicBoolean reached) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (token != null) request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain() {
            @Override public void doFilter(ServletRequest req, ServletResponse res) {
                reached.set(true);
                assertTrue(McpCallerContext.identity().startsWith("oauth:"));
                assertFalse(McpCallerContext.identity().contains("operator"));
            }
        });
        assertEquals("local", McpCallerContext.identity());
        return response.getStatus();
    }

    @Test void readScopeAllowsMcpAndRejectsAdmin() throws Exception {
        AtomicBoolean reached = new AtomicBoolean();
        McpHttpAuthFilter filter = filter("mcp:invoke");
        assertEquals(200, call(filter, "/mcp", "POST", "valid", reached));
        assertTrue(reached.get());
        reached.set(false);
        assertEquals(403, call(filter, "/api/mcp/try/kex_list_topics", "POST", "valid", reached));
        assertFalse(reached.get());
    }

    @Test void auditReplayRequiresAdminScope() throws Exception {
        AtomicBoolean reached = new AtomicBoolean();
        assertEquals(403, call(filter("mcp:invoke"), "/api/mcp/calls/replay", "GET", "valid", reached));
        assertEquals(200, call(filter("mcp:admin"), "/api/mcp/calls/replay", "GET", "valid", reached));
    }

    @Test void missingAndInvalidTokensFailClosed() throws Exception {
        AtomicBoolean reached = new AtomicBoolean();
        assertEquals(401, call(filter("mcp:invoke"), "/mcp", "POST", null, reached));
        assertEquals(401, call(filter("mcp:invoke"), "/mcp", "POST", "invalid", reached));
        assertFalse(reached.get());
    }

    @Test void unauthorizedRequestsAdvertiseBearerChallenge() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter("mcp:invoke").doFilter(request, response, new MockFilterChain());
        assertEquals(401, response.getStatus());
        assertEquals("Bearer realm=\"mcp\"",
                response.getHeader("WWW-Authenticate"));
        assertTrue(McpCallerContext.verifiedScopes().isEmpty());
    }

    @Test void staticTokenCannotBypassOAuthMode() throws Exception {
        AtomicBoolean reached = new AtomicBoolean();
        assertEquals(401, call(filter("mcp:invoke"), "/mcp", "POST", "test-secret-token", reached));
        assertFalse(reached.get());
    }
}
