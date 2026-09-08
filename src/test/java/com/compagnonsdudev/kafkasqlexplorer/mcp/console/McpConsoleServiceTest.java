// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.console;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpToolFilter;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Coverage;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Measured;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.StopReason;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.ToolResult;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpGuard;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpCallFilter;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpCallRecord;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpCallRecorder;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpCatalogService;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.ToolCategory;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.Visibility;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.MutatingMcpTools;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.ReadOnlyMcpTools;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpConsoleServiceTest {

    static class ReadTools implements ReadOnlyMcpTools {
        @Override public ToolCategory category() { return ToolCategory.EXPLORATION; }

        @McpTool(name = "kex_list_topics", description = "List topics. Always read coverage.")
        public ToolResult<List<String>> list() {
            return ToolResult.complete(List.of(), Coverage.exhausted(0L, 0L));
        }
    }

    static class WriteTools implements MutatingMcpTools {
        @McpTool(name = "kex_produce_message", description = "Produce a message.")
        public ToolResult<String> produce() {
            return ToolResult.complete("", Coverage.exhausted(0L, 0L));
        }
    }

    private McpProperties properties;
    private McpCatalogService catalog;
    private McpCallRecorder recorder;
    private McpConsoleService console;

    @BeforeEach
    void setUp() {
        properties = new McpProperties();
        properties.setEnabled(true);
        catalog = new McpCatalogService(properties, new McpToolFilter(properties));
        ReadTools read = new ReadTools();
        catalog.publish(List.of(read, new WriteTools()), List.of(read));
        recorder = new McpCallRecorder(properties, null, new SimpleMeterRegistry());
        console = new McpConsoleService(properties, catalog, recorder, new McpEndpointResolver(new MockEnvironment()));
    }

    @Test
    void the_status_banner_says_the_write_surface_is_closed_and_names_no_endpoint_before_binding() {
        McpStatusView status = console.status();

        assertThat(status.enabled()).isTrue();
        assertThat(status.readonly()).isTrue();
        assertThat(status.writeSurfaceOpen()).isFalse();
        assertThat(status.mutatingToolsExposed()).isEmpty();
        // Nothing is bound in a unit test, and null is the honest answer: falling back to the
        // configured port would print an address that may well not answer.
        assertThat(status.endpoint()).isNull();
        // Phase 5 brings OAuth. Until then the banner has to say there is no caller authentication.
        assertThat(status.authentication()).containsIgnoringCase("none");
        // And the page has to know the button would be refused, so it can hide it rather than
        // offer one that answers 403.
        assertThat(status.tryItEnabled()).isFalse();
    }

    @Test
    void the_banner_reports_try_it_once_it_is_turned_on() {
        properties.getConsole().setAllowTryIt(true);

        assertThat(console.status().tryItEnabled()).isTrue();
    }

    @Test
    void the_catalogue_keeps_the_withheld_tool_with_its_reason() {
        McpCatalogView view = console.catalog(Duration.ofHours(24));

        assertThat(view.tools()).extracting(McpToolRow::name)
                .containsExactlyInAnyOrder("kex_list_topics", "kex_produce_message");
        assertThat(view.tools()).filteredOn(t -> t.name().equals("kex_produce_message"))
                .singleElement().satisfies(row -> {
                    assertThat(row.visibility().state()).isEqualTo(Visibility.State.HIDDEN);
                    assertThat(row.visibility().reason()).contains("read-only");
                });
    }

    @Test
    void a_p95_over_too_few_calls_is_unmeasured_never_zero() {
        // A percentile over three calls is the slowest of three, most likely a cold start. Printing
        // it as p95 sends an operator after a problem that does not exist; printing 0 for a tool
        // nobody called says it is instantaneous.
        record(3, "kex_list_topics", 10);

        assertThat(console.catalog(Duration.ofHours(24)).tools())
                .filteredOn(t -> t.name().equals("kex_list_topics"))
                .singleElement().satisfies(row -> {
                    assertThat(row.calls()).isEqualTo(3);
                    assertThat(row.p95Ms().measured()).isFalse();
                    assertThat(row.p95Ms().reason()).contains("3 call");
                });

        assertThat(console.catalog(Duration.ofHours(24)).tools())
                .filteredOn(t -> t.name().equals("kex_produce_message"))
                .singleElement().satisfies(row ->
                        assertThat(row.p95Ms().reason()).isEqualTo("not called in this window"));
    }

    @Test
    void a_p95_is_measured_once_the_sample_is_large_enough() {
        record(McpToolRow.MIN_CALLS_FOR_P95, "kex_list_topics", 40);

        assertThat(console.catalog(Duration.ofHours(24)).tools())
                .filteredOn(t -> t.name().equals("kex_list_topics"))
                .singleElement().satisfies(row -> assertThat(row.p95Ms().measured()).isTrue());
    }

    @Test
    void the_window_says_when_the_ring_evicted_history_rather_than_showing_a_short_list() {
        // A card headed "24 h" over a ring that dropped calls is a wrong number wearing a right
        // label — and wrong in the direction that reassures.
        properties.getConsole().setRingBufferSize(3);
        recorder = new McpCallRecorder(properties, null, new SimpleMeterRegistry());
        console = new McpConsoleService(properties, catalog, recorder, new McpEndpointResolver(new MockEnvironment()));
        record(10, "kex_list_topics", 5);

        ObservedWindow window = console.stats(Duration.ofHours(24)).observedWindow();

        assertThat(window.callsHeld()).isEqualTo(3);
        assertThat(window.droppedFromRing()).isEqualTo(7);
        assertThat(window.coversRequestedWindow()).isFalse();
        assertThat(window.auditPersisted()).isFalse();
    }

    @Test
    void a_full_ring_that_never_evicted_covers_its_window() {
        record(5, "kex_list_topics", 5);

        assertThat(console.stats(Duration.ofHours(24)).observedWindow().coversRequestedWindow()).isTrue();
    }

    @Test
    void refusals_are_counted_beside_successes_and_grouped_by_code_and_guard() {
        // On the same card, deliberately: a control refusing two hundred times an hour is a
        // configuration to revisit, and relegating it to a panel of its own is how it stays
        // invisible for a month.
        record(2, "kex_list_topics", 5);
        recorder.record(denied("kex_list_topics", -32041, McpGuard.SCOPE));
        recorder.record(denied("kex_list_topics", -32041, McpGuard.SCOPE));
        recorder.record(denied("kex_sql_query", -32046, McpGuard.VALIDATION));

        McpStatsView stats = console.stats(Duration.ofHours(24));

        assertThat(stats.calls()).isEqualTo(5);
        assertThat(stats.denied()).isEqualTo(3);
        assertThat(stats.denialsByCode()).hasSize(2);
        assertThat(stats.denialsByCode()).anySatisfy(d -> {
            assertThat(d.jsonRpcErrorCode()).isEqualTo(-32041);
            assertThat(d.guard()).isEqualTo("SCOPE");
            assertThat(d.count()).isEqualTo(2);
        });
    }

    @Test
    void induced_load_stays_unmeasured_when_no_call_in_the_window_counted_anything() {
        // Summing only the measured entries and returning 0 would say "the agent read no records"
        // about a window in which nothing counted them.
        recorder.record(new McpCallRecord("c", Instant.now(), 4L, McpCallRecord.Origin.AGENT,
                "svc@corp", "claude-code/1.4.2", "kex_list_tables", Map.of(),
                McpCallRecord.Outcome.OK, null, null,
                Measured.unmeasured("this tool reads no records"), StopReason.EXHAUSTED, false,
                Measured.of(120L)));

        McpStatsView stats = console.stats(Duration.ofHours(24));

        assertThat(stats.recordsScanned().measured()).isFalse();
        assertThat(stats.outputBytes().value()).isEqualTo(120L);
    }

    @Test
    void calls_outside_the_window_are_excluded_from_the_aggregates() {
        recorder.record(okAt("kex_list_topics", Instant.now().minus(Duration.ofHours(48))));
        record(1, "kex_list_topics", 5);

        assertThat(console.stats(Duration.ofHours(24)).calls()).isEqualTo(1);
    }

    @Test
    void clients_are_grouped_by_identity_with_what_they_declared() {
        record(2, "kex_list_topics", 5);

        assertThat(console.clients(Duration.ofHours(24))).singleElement().satisfies(row -> {
            assertThat(row.identity()).isEqualTo("session:abc");
            assertThat(row.clientInfo()).isEqualTo("claude-code/1.4.2");
            assertThat(row.calls()).isEqualTo(2);
        });
    }

    @Test
    void the_feed_is_a_view_that_carries_the_partial_coverage_flag_precomputed() {
        recorder.record(new McpCallRecord("c", Instant.now(), 8L, McpCallRecord.Origin.AGENT,
                "svc@corp", "claude-code/1.4.2", "kex_trace_key", Map.of(),
                McpCallRecord.Outcome.OK, null, null, Measured.of(8_400L),
                StopReason.TIME_BUDGET, false, Measured.of(900L)));

        assertThat(console.calls(McpCallFilter.all(), 10)).singleElement()
                .satisfies(call -> assertThat(call.partialCoverage()).isTrue());
    }

    private void record(int count, String tool, long durationMs) {
        for (int i = 0; i < count; i++) {
            recorder.record(new McpCallRecord("c" + i, Instant.now(), durationMs + i,
                    McpCallRecord.Origin.AGENT, "session:abc", "claude-code/1.4.2", tool, Map.of(),
                    McpCallRecord.Outcome.OK, null, null, Measured.of(10L), StopReason.EXHAUSTED,
                    false, Measured.of(100L)));
        }
    }

    private static McpCallRecord okAt(String tool, Instant at) {
        return new McpCallRecord("old", at, 5L, McpCallRecord.Origin.AGENT, "session:abc",
                "claude-code/1.4.2", tool, Map.of(), McpCallRecord.Outcome.OK, null, null,
                Measured.of(1L), StopReason.EXHAUSTED, false, Measured.of(10L));
    }

    private static McpCallRecord denied(String tool, int code, McpGuard guard) {
        return new McpCallRecord("d" + code + guard, Instant.now(), 2L, McpCallRecord.Origin.AGENT,
                "session:abc", "claude-code/1.4.2", tool, Map.of(), McpCallRecord.Outcome.DENIED,
                code, guard, Measured.unmeasured("refused before reading"), null, false,
                Measured.of(0L));
    }
}
