// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.mcp.security;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpOAuthResourceMetadataTest {
    private static McpProperties properties() {
        McpProperties props = new McpProperties();
        McpProperties.OAuth oauth = props.getOauth();
        oauth.setEnabled(true);
        oauth.setIssuerUri("https://idp.example.test/realm");
        oauth.setResourceUri("https://api.example.test:9443/kafka/mcp");
        oauth.setToolScopes(Map.of("kex_describe_topic", "kafka.topic.inspect"));
        return props;
    }

    @Test void pathSpecificAndRootDiscoveryContainOnlyConfiguredValues() {
        McpOAuthResourceMetadataController controller =
                new McpOAuthResourceMetadataController(properties());
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/.well-known/oauth-protected-resource/kafka/mcp");
        request.addHeader("Host", "attacker.example.test");
        request.addHeader("X-Forwarded-Host", "attacker.example.test");

        ResponseEntity<Map<String, Object>> response = controller.resourceMetadata(request);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("https://api.example.test:9443/kafka/mcp", response.getBody().get("resource"));
        assertEquals(List.of("https://idp.example.test/realm"),
                response.getBody().get("authorization_servers"));
        assertTrue(((List<?>) response.getBody().get("scopes_supported")).contains("kafka.topic.inspect"));
        assertEquals("no-store", response.getHeaders().getCacheControl());

        MockHttpServletRequest root = new MockHttpServletRequest("GET",
                "/.well-known/oauth-protected-resource");
        assertEquals(200, controller.resourceMetadata(root).getStatusCode().value());
        MockHttpServletRequest bad = new MockHttpServletRequest("GET",
                "/.well-known/oauth-protected-resource/other/mcp");
        assertEquals(404, controller.resourceMetadata(bad).getStatusCode().value());
    }

    @Test void invalidProtectedResourceUrlsAreRejected() {
        for (String resource : List.of(
                "http://api.example.test/mcp",
                "https://attacker@api.example.test/mcp",
                "https://api.example.test/mcp?token=secret",
                "https://api.example.test/mcp#frag",
                "https://api.example.test/not-mcp",
                "https://api.example.test")) {
            McpProperties props = properties();
            props.getOauth().setResourceUri(resource);
            assertThrows(IllegalStateException.class,
                    () -> new McpOAuthProtectedResourceMetadata(props.getOauth()), resource);
        }
    }

    @Test void challengeUrlIsCanonicalAndMatchesDiscoveredPath() {
        McpOAuthProtectedResourceMetadata metadata =
                new McpOAuthProtectedResourceMetadata(properties().getOauth());
        assertEquals("https://api.example.test:9443/.well-known/oauth-protected-resource/kafka/mcp",
                metadata.metadataUrl());
        assertTrue(metadata.matchesPath("/.well-known/oauth-protected-resource/kafka/mcp"));
        assertFalse(metadata.matchesPath("/.well-known/oauth-protected-resource/else"));
    }
}
