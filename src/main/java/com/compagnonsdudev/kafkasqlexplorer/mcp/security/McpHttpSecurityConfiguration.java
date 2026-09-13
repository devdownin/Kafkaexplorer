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
 @Bean FilterRegistrationBean<McpHttpAuthFilter> mcpHttpAuthFilter(McpProperties p){
  FilterRegistrationBean<McpHttpAuthFilter> r=new FilterRegistrationBean<>();
  r.setFilter(new McpHttpAuthFilter(p.getAuthToken(),p.isRequireTls())); r.addUrlPatterns("/mcp","/mcp/*","/api/mcp/*");
  r.setOrder(Ordered.HIGHEST_PRECEDENCE); r.setName("mcpHttpAuthFilter"); return r;
 }
}
