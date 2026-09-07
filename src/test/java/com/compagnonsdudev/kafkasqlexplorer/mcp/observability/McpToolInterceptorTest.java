// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.observability;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.StopReason;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.DlpScrubber;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpErrorCode;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpGuard;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpScopeViolationException;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpToolException;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The layer that makes three of this module's claims true. Each test below pins one of them.
 */
class McpToolInterceptorTest {

    private McpProperties properties;
    private McpCallRecorder recorder;
    private McpToolInterceptor interceptor;
    private final MeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        properties = new McpProperties();
        DlpScrubber dlp = new DlpScrubber(properties);
        recorder = new McpCallRecorder(properties, null, meters);
        interceptor = new McpToolInterceptor(properties, new ToolGuard(properties, dlp), dlp, recorder);
    }

    /** A tool that answers with the structured content Spring AI actually produces: a Map. */
    private CallToolResult structured(long recordsScanned, StopReason stopReason, boolean truncated) {
        return CallToolResult.builder()
                .structuredContent(Map.of(
                        "data", List.of("a", "b"),
                        "coverage", Map.of("recordsScanned", recordsScanned,
                                "stopReason", stopReason.name()),
                        "truncated", truncated))
                .build();
    }

    private CallToolResult invoke(BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler,
                                  Map<String, Object> arguments) {
        SyncToolSpecification wrapped = interceptor.wrap(
                new SyncToolSpecification(Tool.builder("kex_list_topics").build(), handler));
        return wrapped.callHandler().apply(null, CallToolRequest.builder().name("kex_list_topics").arguments(arguments).build());
    }

    @Test
    void a_successful_call_is_recorded_with_the_coverage_the_tool_reported() {
        // The envelope arrives as a Map, not as our ToolResult — Spring AI serialises the return
        // value and parses it back before this layer sees it. Reading it as a ToolResult would
        // record every call as "does not count records" on a server doing real work.
        invoke((exchange, request) -> structured(8_400L, StopReason.TIME_BUDGET, false), Map.of());

        assertThat(recorder.recent(McpCallFilter.all(), 10)).singleElement().satisfies(call -> {
            assertThat(call.tool()).isEqualTo("kex_list_topics");
            assertThat(call.outcome()).isEqualTo(McpCallRecord.Outcome.OK);
            assertThat(call.recordsScanned().value()).isEqualTo(8_400L);
            assertThat(call.stopReason()).isEqualTo(StopReason.TIME_BUDGET);
            assertThat(call.partialCoverage()).isTrue();
            assertThat(call.outputBytes().measured()).isTrue();
        });
        assertThat(meters.counter("explorer_mcp_calls_total",
                "tool", "kex_list_topics", "outcome", "OK", "origin", "AGENT").count()).isEqualTo(1.0);
    }

    @Test
    void a_tool_with_no_envelope_reports_an_unmeasured_record_count_not_zero() {
        invoke((exchange, request) -> CallToolResult.builder().addTextContent("done").build(), Map.of());

        assertThat(recorder.recent(McpCallFilter.all(), 10)).singleElement()
                .satisfies(call -> assertThat(call.recordsScanned().measured()).isFalse());
    }

    @Test
    void a_refusal_reaches_the_agent_as_its_own_json_rpc_code() {
        // The claim that motivated this layer. Without it the SDK flattens the refusal into a
        // generic tool error, and -32041 — the whole reason for adopting KIP-1318's numbering —
        // never reaches a client that knows how to read it.
        assertThatThrownBy(() -> invoke((exchange, request) -> {
            throw new McpScopeViolationException("topics", List.of("prod.payments"), List.of("demo."));
        }, Map.of("topics", List.of("prod.payments"))))
                .isInstanceOf(McpError.class)
                .satisfies(e -> assertThat(((McpError) e).getJsonRpcError().code()).isEqualTo(-32041));

        assertThat(recorder.recent(McpCallFilter.all(), 10)).singleElement().satisfies(call -> {
            assertThat(call.outcome()).isEqualTo(McpCallRecord.Outcome.DENIED);
            assertThat(call.jsonRpcErrorCode()).isEqualTo(-32041);
            assertThat(call.deniedByGuard()).isEqualTo(McpGuard.SCOPE);
        });
        assertThat(meters.counter("explorer_mcp_denied_total", "guard", "SCOPE", "code", "-32041")
                .count()).isEqualTo(1.0);
    }

    @Test
    void a_user_sql_error_comes_back_as_tool_output_so_the_model_can_correct_it() {
        // The SDK documents isError as "the tool EXECUTION failed and the content contains error
        // information" — that result reaches the model. A JSON-RPC error is a failure of the call
        // itself, which a client may surface as a transport fault without showing the model
        // anything. Sent as a hard error, the planner's line and column — the whole reason for
        // preserving its message — could never be read by the thing that has to act on it.
        CallToolResult result = invoke((exchange, request) -> {
            throw new McpToolException(McpErrorCode.VALIDATION_FAILED, McpGuard.VALIDATION,
                    "SQL parse failed. Encountered \"FROM\" at line 1, column 12.");
        }, Map.of("sql", "SELECT id, FROM orders"));

        assertThat(result.isError()).isTrue();
        assertThat(result.content().getFirst().toString())
                .contains("line 1, column 12").contains("-32046");
        assertThat(recorder.recent(McpCallFilter.all(), 10)).singleElement().satisfies(call -> {
            assertThat(call.jsonRpcErrorCode()).isEqualTo(-32046);
            assertThat(call.outputBytes().measured()).isTrue();
        });
    }

    @Test
    void an_unreachable_dependency_is_an_error_not_a_denial_and_reaches_the_model() {
        // Filing a broker outage under "denied" sends an operator to loosen a guard that was never
        // involved; and the model should read "the broker is away" rather than be told the call
        // itself was malformed.
        CallToolResult result = invoke((exchange, request) -> {
            throw new McpToolException(McpErrorCode.DEPENDENCY_UNAVAILABLE, "Kafka was unreachable");
        }, Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(recorder.recent(McpCallFilter.all(), 10)).singleElement()
                .satisfies(call -> assertThat(call.outcome()).isEqualTo(McpCallRecord.Outcome.ERROR));
    }

    @Test
    void a_scope_refusal_stays_a_hard_json_rpc_error_the_model_cannot_talk_around() {
        // The other side of the same split: a permission refusal must not arrive as readable tool
        // output inviting the model to try a variation. An agent probing around a scope refusal is
        // exactly what the guard exists to stop.
        assertThat(McpErrorCode.OUT_OF_SCOPE.reportedToTheModel()).isFalse();
        assertThat(McpErrorCode.QUARANTINED.reportedToTheModel()).isFalse();
        assertThat(McpErrorCode.EXFILTRATION_BLOCKED.reportedToTheModel()).isFalse();
        assertThat(McpErrorCode.VALIDATION_FAILED.reportedToTheModel()).isTrue();
        assertThat(McpErrorCode.DEPENDENCY_UNAVAILABLE.reportedToTheModel()).isTrue();
    }

    @Test
    void a_payload_over_the_ceiling_is_refused_and_says_what_to_narrow_rather_than_being_cut() {
        // Cutting a serialised result yields malformed JSON at best, and at worst a shorter answer
        // a model cannot distinguish from a complete one — the exact failure this module exists to
        // prevent. The reply names the size, the ceiling and the way out.
        properties.setHardMaxOutputBytes(200);

        CallToolResult result = invoke((exchange, request) -> CallToolResult.builder()
                .structuredContent(Map.of("data", "x".repeat(5_000)))
                .build(), Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).isNotEmpty();
        assertThat(result.content().getFirst().toString())
                .contains("kex_list_topics").contains("200").contains("NOT truncated");
        assertThat(recorder.recent(McpCallFilter.all(), 10)).singleElement()
                .satisfies(call -> assertThat(call.truncated()).isTrue());
        assertThat(meters.counter("explorer_mcp_output_truncated_total", "tool", "kex_list_topics")
                .count()).isEqualTo(1.0);
    }

    @Test
    void a_payload_under_the_ceiling_passes_through_untouched() {
        CallToolResult original = structured(3L, StopReason.EXHAUSTED, false);

        assertThat(invoke((exchange, request) -> original, Map.of())).isSameAs(original);
    }

    @Test
    void recorded_parameters_are_redacted_before_they_are_kept() {
        // Before, not at display: the ring buffer and the audit topic both outlive the call.
        invoke((exchange, request) -> structured(1L, StopReason.EXHAUSTED, false),
                Map.of("sql", "SELECT * FROM t", "apiKey", "correct-horse"));

        assertThat(recorder.recent(McpCallFilter.all(), 10)).singleElement().satisfies(call -> {
            assertThat(call.redactedParams()).containsEntry("apiKey", "******");
            assertThat(call.redactedParams()).containsEntry("sql", "SELECT * FROM t");
        });
    }

    @Test
    void a_quarantined_identity_is_stopped_before_the_tool_runs() {
        // Quarantine is about the caller, and the caller is only visible at this layer — a tool
        // cannot see who invoked it.
        boolean[] ran = {false};
        interceptor = new McpToolInterceptor(properties, quarantineEverything(),
                new DlpScrubber(properties), recorder);

        assertThatThrownBy(() -> invoke((exchange, request) -> {
            ran[0] = true;
            return structured(1L, StopReason.EXHAUSTED, false);
        }, Map.of()))
                .isInstanceOf(McpError.class)
                .satisfies(e -> assertThat(((McpError) e).getJsonRpcError().code()).isEqualTo(-32047));

        assertThat(ran[0]).isFalse();
    }

    private ToolGuard quarantineEverything() {
        ToolGuard guard = new ToolGuard(properties, new DlpScrubber(properties));
        guard.quarantine(McpToolInterceptor.LOCAL_IDENTITY);
        return guard;
    }
}
