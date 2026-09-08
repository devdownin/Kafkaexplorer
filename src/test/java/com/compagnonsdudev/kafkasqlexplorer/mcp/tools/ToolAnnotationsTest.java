// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every read-only tool must say so in its MCP annotations.
 *
 * <p>The hints are not decoration: a client uses them to decide whether a call needs a human in
 * the loop. And the defaults are the wrong way round for us — {@code readOnlyHint} defaults to
 * <b>false</b> and {@code destructiveHint} to <b>true</b>, so a tool that omits them advertises
 * itself as potentially destructive. Six tools that only read were making that claim, which is not
 * a missing hint but a false one: it costs an approval prompt per call on the tools an agent uses
 * to explore, which is where the friction is least deserved.
 */
class ToolAnnotationsTest {

    private static final List<Class<?>> READ_ONLY_TOOLSETS =
            List.of(TopicMcpTools.class, SchemaMcpTools.class, SqlMcpTools.class,
                    StreamFlowMcpTools.class, ConsumerLagMcpTools.class);

    @Test
    void every_read_only_tool_declares_itself_read_only_and_non_destructive() {
        List<Method> tools = READ_ONLY_TOOLSETS.stream()
                .flatMap(type -> Arrays.stream(type.getMethods()))
                .filter(method -> method.isAnnotationPresent(McpTool.class))
                .toList();

        assertThat(tools).hasSize(10);
        assertThat(tools).allSatisfy(method -> {
            McpTool tool = method.getAnnotation(McpTool.class);
            assertThat(tool.annotations().readOnlyHint())
                    .describedAs("%s must declare readOnlyHint", tool.name()).isTrue();
            assertThat(tool.annotations().destructiveHint())
                    .describedAs("%s must not inherit the destructive default", tool.name()).isFalse();
            // The cluster is somebody else's and changes underneath us, so the world is open.
            assertThat(tool.annotations().openWorldHint()).isTrue();
        });
    }

    @Test
    void every_tool_is_named_and_described() {
        READ_ONLY_TOOLSETS.stream()
                .flatMap(type -> Arrays.stream(type.getMethods()))
                .filter(method -> method.isAnnotationPresent(McpTool.class))
                .map(method -> method.getAnnotation(McpTool.class))
                .forEach(tool -> {
                    assertThat(tool.name()).startsWith("kex_");
                    // The description is what an agent reads before the payload; a tool without one
                    // is a tool the model guesses at.
                    assertThat(tool.description()).isNotBlank();
                });
    }
}
