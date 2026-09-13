// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.mcp.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpHttpAuthFilterTest {
    private static final String TOKEN = "test-secret-token";
    private final McpHttpAuthFilter filter = new McpHttpAuthFilter(TOKEN);
    @AfterEach void clearContext() { McpCallerContext.clear(); }

    @Test void mcpEndpointRejectsMissingBearerToken() throws Exception {
        MockHttpServletRequest request = request("/mcp", "POST");
        request.setSecure(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(401, response.getStatus());
    }

    @Test void productionFilterRejectsCleartextBeforeCredentialProcessing() throws Exception {
        MockHttpServletRequest request = request("/mcp", "POST");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingFilterChain chain = new RecordingFilterChain();
        filter.doFilter(request, response, chain);
        assertEquals(426, response.getStatus());
        assertFalse(chain.called);
    }

    @Test void developmentModeCanExplicitlyAcceptCleartext() throws Exception {
        MockHttpServletRequest request = request("/mcp", "POST");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingFilterChain chain = new RecordingFilterChain();
        new McpHttpAuthFilter(TOKEN, false).doFilter(request, response, chain);
        assertTrue(chain.called);
    }

    @Test void mcpEndpointAcceptsConfiguredBearerTokenOverTls() throws Exception {
        MockHttpServletRequest request = request("/mcp", "POST");
        request.setSecure(true);
        request.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingFilterChain chain = new RecordingFilterChain();
        filter.doFilter(request, response, chain);
        assertTrue(chain.called);
        assertEquals(McpHttpAuthFilter.identityOf(TOKEN), McpCallerContext.identity());
    }

    @Test void privilegedEndpointsAreProtected() throws Exception {
        assertProtected(request("/api/mcp/try/kex_list_topics", "POST"));
        assertProtected(request("/api/mcp/approve/kex_create_metric", "POST"));
        assertProtected(request("/api/mcp/calls/replay", "GET"));
    }

    private void assertProtected(MockHttpServletRequest request) throws Exception {
        request.setSecure(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingFilterChain chain = new RecordingFilterChain();
        filter.doFilter(request, response, chain);
        assertEquals(401, response.getStatus());
        assertFalse(chain.called);
    }
    private static MockHttpServletRequest request(String path, String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setContextPath("");
        return request;
    }
    private static final class RecordingFilterChain extends MockFilterChain {
        private boolean called;
        @Override public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) { called = true; }
    }
}
