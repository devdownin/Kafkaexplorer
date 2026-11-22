// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.security;
import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix="explorer.mcp",name="enabled",havingValue="true")
public class McpHttpSecurityConfiguration {
    @Bean
    FilterRegistrationBean<McpHttpAuthFilter> mcpHttpAuthFilter(McpProperties properties) {
        FilterRegistrationBean<McpHttpAuthFilter> registration = new FilterRegistrationBean<>();
        McpHttpAuthFilter filter;
        if ("oauth2".equalsIgnoreCase(properties.getAuthMode())) {
            var oauth = properties.getOauth2();
            if (oauth.getIssuerUri() == null || oauth.getIssuerUri().isBlank()
                    || oauth.getJwkSetUri() == null || oauth.getJwkSetUri().isBlank()
                    || oauth.getAudience() == null || oauth.getAudience().isBlank()) {
                throw new IllegalStateException("OAuth2 MCP requires issuer-uri, jwk-set-uri and audience");
            }
            var decoder = org.springframework.security.oauth2.jwt.NimbusJwtDecoder
                    .withJwkSetUri(oauth.getJwkSetUri()).build();
            decoder.setJwtValidator(org.springframework.security.oauth2.jwt.JwtValidators
                    .createDefaultWithIssuer(oauth.getIssuerUri()));
            filter = new McpHttpAuthFilter(decoder, oauth.getAudience(), properties.isRequireTls());
        } else if ("bearer".equalsIgnoreCase(properties.getAuthMode())) {
            filter = new McpHttpAuthFilter(properties.getAuthToken(), properties.isRequireTls());
        } else {
            throw new IllegalStateException("Unsupported explorer.mcp.auth-mode; expected bearer or oauth2");
        }
        registration.setFilter(filter);
        registration.addUrlPatterns("/mcp", "/mcp/*", "/api/mcp/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("mcpHttpAuthFilter");
        return registration;
    }
}
