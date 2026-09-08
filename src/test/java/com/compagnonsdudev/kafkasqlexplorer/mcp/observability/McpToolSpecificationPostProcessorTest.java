// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.observability;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.DlpScrubber;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpApprovalStore;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpRateLimiter;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpRuntimeSwitches;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpToolFilter;
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
                new McpToolInterceptor(properties, new ToolGuard(properties, dlp), dlp, recorder,
                        new McpRuntimeSwitches(properties), new McpRateLimiter(properties),
                        new McpApprovalStore(properties));
        McpToolFilter filter = new McpToolFilter(properties);
        return new McpToolSpecificationPostProcessor(new ObjectProvider<>() {
            @Override
            public McpToolInterceptor getObject() {
                return interceptor;
            }
        }, new ObjectProvider<>() {
            @Override
            public McpToolFilter getObject() {
                return filter;
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

    @Test
    void a_denied_tool_is_absent_from_the_list_rather_than_present_and_refusing() {
        // The stronger guarantee, and the same one readonly already gives: a tool that is not in
        // tools/list is never described to the model, never chosen, never refused after a round
        // trip on a surface that advertises what it will not do.
        properties.getTools().setDenied("kex_sql_query");

        Object processed = postProcessor().postProcessAfterInitialization(
                List.of(spec("kex_list_topics"), spec("kex_sql_query")), "toolSpecs");

        assertThat(processed).isInstanceOf(List.class);
        assertThat((List<?>) processed).hasSize(1);
        assertThat(((SyncToolSpecification) ((List<?>) processed).getFirst()).tool().name())
                .isEqualTo("kex_list_topics");
    }

    @Test
    void an_allow_list_that_is_a_list_registers_only_what_it_names() {
        properties.getTools().setAllowed("kex_list_topics");

        Object processed = postProcessor().postProcessAfterInitialization(
                List.of(spec("kex_list_topics"), spec("kex_sql_query")), "toolSpecs");

        assertThat((List<?>) processed).hasSize(1);
    }

    @Test
    void the_names_kept_out_are_reported_so_the_console_can_say_why() {
        properties.getTools().setDenied("kex_sql_query");
        McpToolSpecificationPostProcessor processor = postProcessor();

        processor.postProcessAfterInitialization(
                List.of(spec("kex_list_topics"), spec("kex_sql_query")), "toolSpecs");

        assertThat(processor.removedTools()).containsExactly("kex_sql_query");
        assertThat(processor.wrappedCount()).isEqualTo(1);
    }
}
