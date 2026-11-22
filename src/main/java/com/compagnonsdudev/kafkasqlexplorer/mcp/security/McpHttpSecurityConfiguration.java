// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.security;
import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import java.net.URI;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix="explorer.mcp",name="enabled",havingValue="true")
public class McpHttpSecurityConfiguration {
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void requireHttps(String value, String name) {
        if (blank(value)) throw new IllegalStateException("MCP OAuth requires " + name);
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalStateException("MCP OAuth " + name + " must be an HTTPS URL");
        }
    }

    @Bean
    FilterRegistrationBean<McpHttpAuthFilter> mcpHttpAuthFilter(McpProperties properties) {
        FilterRegistrationBean<McpHttpAuthFilter> registration = new FilterRegistrationBean<>();
        McpProperties.OAuth oauth = properties.getOauth();
        if (oauth != null && oauth.isEnabled()) {
            // MCP tool calls share /mcp: HTTP scopes cannot distinguish mutating tool names.
            // Do not offer OAuth mode with write-capable tool beans until per-tool RBAC exists.
            if (!properties.isReadonly()) {
                throw new IllegalStateException("MCP OAuth currently requires explorer.mcp.readonly=true");
            }
            requireHttps(oauth.getIssuerUri(), "issuer-uri");
            requireHttps(oauth.getJwkSetUri(), "jwk-set-uri");
            if (blank(oauth.getAudience()) || blank(oauth.getReadScope())
                    || blank(oauth.getAdminScope()) || blank(oauth.getAuditScope())) {
                throw new IllegalStateException("MCP OAuth requires audience and all three scope names");
            }
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(oauth.getJwkSetUri()).build();
            OAuth2TokenValidator<Jwt> issuer = JwtValidators.createDefaultWithIssuer(oauth.getIssuerUri());
            decoder.setJwtValidator(jwt -> {
                OAuth2TokenValidatorResult result = issuer.validate(jwt);
                if (result.hasErrors()) return result;
                return jwt.getAudience().contains(oauth.getAudience())
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid MCP audience", null));
            });
            registration.setFilter(new McpHttpAuthFilter(decoder, oauth, properties.isRequireTls()));
        } else {
            registration.setFilter(new McpHttpAuthFilter(properties.getAuthToken(), properties.isRequireTls()));
        }
        registration.addUrlPatterns("/mcp", "/mcp/*", "/api/mcp/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("mcpHttpAuthFilter");
        return registration;
    }
}
