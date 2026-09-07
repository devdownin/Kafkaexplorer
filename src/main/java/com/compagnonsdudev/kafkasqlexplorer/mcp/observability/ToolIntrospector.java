// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.observability;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.McpToolset;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Reads the tool surface out of the beans that implement it.
 *
 * <p>P8 of the specification, made mechanical: the console's tool table, the ceilings it prints and
 * the descriptions it shows are all taken from the same {@code @McpTool} annotations the MCP
 * protocol serves to agents. A second list — in a constant here, or in the React page — is a second
 * source of truth, and the two diverge the first time a tool is renamed. What the operator reads
 * then is a catalogue describing a server that no longer exists.
 *
 * <p>{@code getUserClass} unwraps CGLIB proxies: a bean the container has advised reports its
 * generated subclass, whose methods carry no annotations, and the toolset would silently describe
 * itself as empty.
 */
public final class ToolIntrospector {

    private ToolIntrospector() {
    }

    /** Every {@code @McpTool} method on {@code toolset}, ordered by name so the table is stable. */
    public static List<ToolDescriptor> describe(McpToolset toolset, McpProperties properties) {
        Class<?> type = ClassUtils.getUserClass(toolset);
        return Arrays.stream(type.getMethods())
                .map(method -> describeMethod(toolset, method, properties))
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(ToolDescriptor::name))
                .toList();
    }

    private static ToolDescriptor describeMethod(McpToolset toolset, Method method, McpProperties properties) {
        McpTool tool = AnnotationUtils.findAnnotation(method, McpTool.class);
        if (tool == null) {
            return null;
        }
        String name = tool.name().isBlank() ? method.getName() : tool.name();
        return new ToolDescriptor(
                name,
                toolset.category(),
                tool.description(),
                Visibility.exposed(),
                properties.getDefaultBudgetMs(),
                properties.getHardMaxRecords(),
                properties.getHardMaxRows(),
                properties.getHardMaxOutputBytes());
    }
}
