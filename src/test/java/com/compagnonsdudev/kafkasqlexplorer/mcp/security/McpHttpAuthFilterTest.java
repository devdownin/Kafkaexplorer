// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.mcp.security;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

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
        request.setSecure(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(401, response.getStatus());
    }

    @Test
    void productionFilterRejectsCleartextBeforeCredentialProcessing() throws Exception {
        MockHttpServletRequest request = request("/mcp", "POST");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingFilterChain chain = new RecordingFilterChain();
        filter.doFilter(request, response, chain);
        assertEquals(426, response.getStatus());
        assertFalse(chain.called);
    }

    @Test
    void developmentModeCanExplicitlyAcceptCleartext() throws Exception {
        MockHttpServletRequest request = request("/mcp", "POST");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingFilterChain chain = new RecordingFilterChain();
        new McpHttpAuthFilter(TOKEN, false).doFilter(request, response, chain);
        assertTrue(chain.called);
        assertEquals(McpHttpAuthFilter.identityOf(TOKEN), chain.identity);
        assertEquals("local", McpCallerContext.identity());
    }

    @Test
    void mcpEndpointAcceptsConfiguredBearerTokenOverTls() throws Exception {
        MockHttpServletRequest request = request("/mcp", "POST");
        request.setSecure(true);
        request.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingFilterChain chain = new RecordingFilterChain();
        filter.doFilter(request, response, chain);
        assertTrue(chain.called);
        assertEquals(McpHttpAuthFilter.identityOf(TOKEN), chain.identity);
        assertEquals("local", McpCallerContext.identity());
    }

    @Test
    void privilegedEndpointsAreProtected() throws Exception {
        assertProtected(request("/api/mcp/try/kex_list_topics", "POST"));
        assertProtected(request("/api/mcp/approve/kex_create_metric", "POST"));
        assertProtected(request("/api/mcp/calls/replay", "GET"));
        assertProtected(request("/api/mcp/toggle/readonly", "POST"));
        assertProtected(request("/api/mcp/quarantine/session%3Aabc", "POST"));
    }

    /**
     * Privilege is decided by exclusion, so an endpoint this filter has never heard of is covered
     * the moment it changes state — the failure mode a list of paths has is that nobody adds to it.
     */
    @Test
    void anEndpointAddedLaterIsProtectedByDefault() throws Exception {
        assertProtected(request("/api/mcp/some-future-switch", "POST"));
        assertProtected(request("/api/mcp/catalog", "DELETE"));
    }

    /**
     * The console's reads carry no token because the page cannot hold one: it is this application's
     * own screen, served to a browser, and protecting it here would only make the MCP screen answer
     * 401 to itself.
     */
    @Test
    void consoleReadsAreLeftToTheApplicationsOwnAccessControl() throws Exception {
        for (String path : new String[] {"/api/mcp/status", "/api/mcp/catalog", "/api/mcp/calls",
                "/api/mcp/stats", "/api/mcp/clients", "/api/mcp/overrides"}) {
            MockHttpServletRequest request = request(path, "GET");
            request.setSecure(true);
            MockHttpServletResponse response = new MockHttpServletResponse();
            RecordingFilterChain chain = new RecordingFilterChain();
            filter.doFilter(request, response, chain);
            assertTrue(chain.called, path + " must reach the controller without a bearer token");
            assertEquals(200, response.getStatus());
        }
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
        private String identity;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            called = true;
            identity = McpCallerContext.identity();
        }
    }
}
