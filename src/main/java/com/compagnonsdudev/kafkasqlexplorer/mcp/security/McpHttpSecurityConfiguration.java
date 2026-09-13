// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.security;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Security boundary for the network MCP transport and privileged console actions. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "explorer.mcp", name = "enabled", havingValue = "true")
public class McpHttpSecurityConfiguration {

    @Bean
    FilterRegistrationBean<McpHttpAuthFilter> mcpHttpAuthFilter(McpProperties properties) {
        FilterRegistrationBean<McpHttpAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new McpHttpAuthFilter(properties.getAuthToken()));
        registration.addUrlPatterns("/mcp", "/mcp/*", "/api/mcp/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("mcpHttpAuthFilter");
        return registration;
    }
}
