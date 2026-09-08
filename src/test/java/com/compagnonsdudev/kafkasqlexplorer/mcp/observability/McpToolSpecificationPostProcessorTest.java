// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.observability;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.DlpScrubber;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolSpecificationPostProcessorTest {

    private final McpProperties properties = new McpProperties();
    private final McpCallRecorder recorder =
            new McpCallRecorder(properties, null, new SimpleMeterRegistry());

    private McpToolSpecificationPostProcessor postProcessor() {
        DlpScrubber dlp = new DlpScrubber(properties);
        McpToolInterceptor interceptor =
                new McpToolInterceptor(properties, new ToolGuard(properties, dlp), dlp, recorder);
        return new McpToolSpecificationPostProcessor(new ObjectProvider<>() {
            @Override
            public McpToolInterceptor getObject() {
                return interceptor;
            }
        });
    }

    private static SyncToolSpecification spec(String name) {
        return new SyncToolSpecification(Tool.builder(name).build(),
                (exchange, request) -> CallToolResult.builder().addTextContent("ok").build());
    }

    @Test
    void the_tool_list_comes_back_instrumented_and_the_call_is_recorded() {
        Object processed = postProcessor()
                .postProcessAfterInitialization(List.of(spec("kex_list_topics")), "toolSpecs");

        assertThat(processed).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<SyncToolSpecification> specs = (List<SyncToolSpecification>) processed;
        assertThat(specs).singleElement()
                .satisfies(s -> assertThat(s.tool().name()).isEqualTo("kex_list_topics"));

        specs.getFirst().callHandler().apply(null, CallToolRequest.builder("kex_list_topics").arguments(Map.of()).build());

        assertThat(recorder.recent(McpCallFilter.all(), 10)).singleElement()
                .satisfies(call -> assertThat(call.tool()).isEqualTo("kex_list_topics"));
    }

    @Test
    void beans_that_are_not_a_tool_specification_list_are_returned_untouched() {
        // It runs against every bean in the context, so being uninterested has to be cheap and
        // total — a post-processor that mangles an unrelated bean is a startup failure nobody
        // will attribute to the MCP module.
        McpToolSpecificationPostProcessor processor = postProcessor();

        Object other = List.of("not a tool");
        assertThat(processor.postProcessAfterInitialization(other, "somethingElse")).isSameAs(other);
        assertThat(processor.postProcessAfterInitialization(List.of(), "empty")).isEqualTo(List.of());
        assertThat(processor.postProcessAfterInitialization("a string", "name")).isEqualTo("a string");
        assertThat(processor.wrappedCount()).isZero();
    }
}
