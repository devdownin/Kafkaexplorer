// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
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

    @AfterEach
    void clearContext() {
        McpCallerContext.clear();
    }

    @Test
    void mcpEndpointRejectsMissingBearerToken() throws Exception {
        MockHttpServletRequest request = request("/mcp", "POST");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertEquals("Bearer realm=\"mcp\"", response.getHeader("WWW-Authenticate"));
    }

    @Test
    void mcpEndpointAcceptsConfiguredBearerToken() throws Exception {
        MockHttpServletRequest request = request("/mcp", "POST");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingFilterChain chain = new RecordingFilterChain();

        filter.doFilter(request, response, chain);

        assertTrue(chain.called);
        assertEquals(200, response.getStatus());
        assertEquals(McpHttpAuthFilter.identityOf(TOKEN),
                request.getAttribute(McpHttpAuthFilter.IDENTITY_ATTRIBUTE));
        assertEquals(McpHttpAuthFilter.identityOf(TOKEN), McpCallerContext.identity());
    }

    @Test
    void tryItAndApprovalEndpointsAreProtectedButReadOnlyCatalogIsNot() throws Exception {
        MockHttpServletRequest tryIt = request("/api/mcp/try/kex_list_topics", "POST");
        assertProtected(tryIt);

        MockHttpServletRequest approve = request("/api/mcp/approve/kex_create_metric", "POST");
        assertProtected(approve);

        MockHttpServletRequest catalog = request("/api/mcp/catalog", "GET");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingFilterChain chain = new RecordingFilterChain();
        filter.doFilter(catalog, response, chain);
        assertTrue(chain.called);
        assertFalse(response.getStatus() == 401);
    }

    @Test
    void auditReplayIsProtected() throws Exception {
        assertProtected(request("/api/mcp/calls/replay", "GET"));
    }

    private void assertProtected(MockHttpServletRequest request) throws Exception {
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

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request,
                              jakarta.servlet.ServletResponse response) {
            called = true;
        }
    }
}
