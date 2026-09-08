// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.console;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Measured;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.DlpScrubber;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpApprovalStore;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpRateLimiter;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpRuntimeSwitches;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpToolFilter;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpErrorCode;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpGuard;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpScopeViolationException;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpToolException;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpCallFilter;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpCallRecord;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpCallRecorder;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpToolInterceptor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console's "Try it", which runs the real specification rather than a parallel route.
 */
class McpToolInvokerTest {

    private final McpProperties properties = new McpProperties();
    private final McpCallRecorder recorder =
            new McpCallRecorder(properties, null, new SimpleMeterRegistry());

    private McpToolInvoker invokerFor(List<SyncToolSpecification> specs) {
        return new McpToolInvoker(new ObjectProvider<>() {
            @Override
            public List<SyncToolSpecification> getObject() {
                return specs;
            }

            @Override
            public List<SyncToolSpecification> getIfAvailable() {
                return specs;
            }
        });
    }

    private SyncToolSpecification instrumented(String name,
                                               java.util.function.Supplier<CallToolResult> body) {
        DlpScrubber dlp = new DlpScrubber(properties);
        McpToolInterceptor interceptor =
                new McpToolInterceptor(properties, new ToolGuard(properties, dlp), dlp, recorder,
                        new McpRuntimeSwitches(properties), new McpRateLimiter(properties),
                        new McpApprovalStore(properties));
        return interceptor.wrap(new SyncToolSpecification(Tool.builder(name).build(),
                (exchange, request) -> body.get()));
    }

    @Test
    void an_unknown_tool_names_the_tool_in_its_refusal() {
        // The regression this test exists for: `.formatted(tool)` was written after the last
        // literal of a concatenation, so it bound to a fragment with no placeholder and the panel
        // read "no tool named %s is registered" — on the one sentence whose job is to name it.
        // Every other test passed, because none of them read the message.
        McpTryResult result = invokerFor(List.of()).invoke("kex_nope", Map.of());

        assertThat(result.invoked()).isFalse();
        assertThat(result.message()).contains("kex_nope").doesNotContain("%s");
    }

    @Test
    void a_console_call_runs_the_real_specification_and_is_recorded_as_console() {
        // Counted in the load it really causes, and kept out of the answer to "what has my agent
        // been doing?".
        SyncToolSpecification spec = instrumented("kex_list_topics",
                () -> CallToolResult.builder().structuredContent(Map.of("data", List.of("a"))).build());

        McpTryResult result = invokerFor(List.of(spec)).invoke("kex_list_topics", Map.of());

        assertThat(result.invoked()).isTrue();
        assertThat(result.isError()).isFalse();
        assertThat(recorder.recent(McpCallFilter.all(), 10)).singleElement()
                .satisfies(call -> assertThat(call.origin()).isEqualTo(McpCallRecord.Origin.CONSOLE));
    }

    @Test
    void the_origin_returns_to_agent_after_the_console_call() {
        // The ThreadLocal restores rather than clears, so a later agent call on the same thread is
        // not silently attributed to the console.
        SyncToolSpecification spec = instrumented("kex_list_topics",
                () -> CallToolResult.builder().addTextContent("ok").build());
        invokerFor(List.of(spec)).invoke("kex_list_topics", Map.of());

        spec.callHandler().apply(null,
                io.modelcontextprotocol.spec.McpSchema.CallToolRequest.builder("kex_list_topics").arguments(Map.of()).build());

        assertThat(recorder.recent(McpCallFilter.all(), 10)).extracting(McpCallRecord::origin)
                .containsExactly(McpCallRecord.Origin.AGENT, McpCallRecord.Origin.CONSOLE);
    }

    @Test
    void a_guard_refusal_is_shown_in_the_panel_rather_than_thrown_at_the_operator() {
        // It is what the agent would receive, which is the whole point of the button; letting the
        // McpError escape would turn an informative refusal into a 500 on the screen.
        SyncToolSpecification spec = instrumented("kex_list_topics", () -> {
            throw new McpScopeViolationException("topics", List.of("prod.payments"), List.of("demo."));
        });

        McpTryResult result = invokerFor(List.of(spec)).invoke("kex_list_topics", Map.of());

        assertThat(result.invoked()).isTrue();
        assertThat(result.isError()).isTrue();
        assertThat(result.jsonRpcErrorCode()).isEqualTo(-32041);
        assertThat(result.message()).contains("prod.payments");
    }

    @Test
    void an_execution_failure_comes_back_as_the_tools_own_message() {
        SyncToolSpecification spec = instrumented("kex_sql_query", () -> {
            throw new McpToolException(McpErrorCode.VALIDATION_FAILED, McpGuard.VALIDATION,
                    "SQL parse failed at line 1, column 12.");
        });

        McpTryResult result = invokerFor(List.of(spec)).invoke("kex_sql_query", Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(result.jsonRpcErrorCode()).isNull();
        assertThat(result.message()).contains("line 1, column 12");
    }

    @Test
    void only_registered_tools_are_invocable() {
        SyncToolSpecification spec = instrumented("kex_list_topics",
                () -> CallToolResult.builder().addTextContent("ok").build());

        assertThat(invokerFor(List.of(spec)).invocableTools()).containsExactly("kex_list_topics");
        assertThat(invokerFor(List.of()).invocableTools()).isEmpty();
        // Sanity on the recorder's own Measured field, so this test fails loudly if the shape moves.
        assertThat(Measured.of(1L).measured()).isTrue();
    }
}
