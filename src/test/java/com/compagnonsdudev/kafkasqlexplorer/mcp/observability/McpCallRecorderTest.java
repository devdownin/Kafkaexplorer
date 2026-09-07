// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.observability;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Measured;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.StopReason;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpGuard;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;

@ExtendWith(MockitoExtension.class)
class McpCallRecorderTest {

    @Mock McpAuditSink auditSink;

    MeterRegistry meters = new SimpleMeterRegistry();
    McpCallRecorder recorder;

    @BeforeEach
    void setUp() {
        McpProperties properties = new McpProperties();
        properties.getConsole().setRingBufferSize(3);
        recorder = new McpCallRecorder(properties, auditSink, meters);
    }

    @Test
    void a_failed_audit_append_never_fails_the_call_but_is_counted() {
        // The bookkeeping must not be able to take down the thing it books. It must also not be
        // able to lie about it: a console reporting a confident total over a log that lost writes
        // is this module's own honesty rule, one level up.
        willThrow(new RuntimeException("topic unavailable")).given(auditSink).append(any());

        assertThatNoException().isThrownBy(() -> recorder.record(ok("kex_list_topics")));

        assertThat(recorder.auditWriteErrors()).isEqualTo(1);
        assertThat(recorder.recent(McpCallFilter.all(), 10)).hasSize(1);
    }

    @Test
    void the_ring_buffer_stays_bounded_and_says_what_it_dropped() {
        for (int i = 0; i < 10; i++) {
            recorder.record(ok("kex_list_topics"));
        }

        assertThat(recorder.recent(McpCallFilter.all(), 100)).hasSize(3);
        assertThat(recorder.droppedFromRing()).isEqualTo(7);
    }

    @Test
    void denials_are_recorded_with_the_guard_that_blocked_them() {
        recorder.record(denied("kex_produce_message", -32041, McpGuard.SCOPE));

        assertThat(meters.counter("explorer_mcp_denied_total", "guard", "SCOPE", "code", "-32041").count())
                .isEqualTo(1.0);
        assertThat(meters.counter("explorer_mcp_calls_total",
                "tool", "kex_produce_message", "outcome", "DENIED", "origin", "AGENT").count())
                .isEqualTo(1.0);
    }

    @Test
    void an_unmeasured_record_count_is_not_added_to_the_scanned_total() {
        // Adding a zero for "did not count" would make the induced-load card read low for tools
        // that never report, which is the one number an operator uses to answer "is the agent
        // hurting the cluster?".
        recorder.record(new McpCallRecord("c1", Instant.now(), 5L, McpCallRecord.Origin.AGENT,
                "svc@corp", "claude-code/1.4.2", "kex_list_tables", Map.of(),
                McpCallRecord.Outcome.OK, null, null,
                Measured.unmeasured("this tool reads no records"), null, false, 120L));

        assertThat(meters.find("explorer_mcp_records_scanned_total")
                .tag("tool", "kex_list_tables").counter()).isNull();
    }

    @Test
    void with_no_sink_configured_the_call_is_still_recorded_and_the_console_is_told() {
        // The state this ships in. It must not throw, and it must not let the console read a
        // bounded live feed as the history it is not.
        McpProperties properties = new McpProperties();
        McpCallRecorder noSink = new McpCallRecorder(properties, null, meters);

        assertThatNoException().isThrownBy(() -> noSink.record(ok("kex_list_topics")));

        assertThat(noSink.recent(McpCallFilter.all(), 10)).hasSize(1);
        assertThat(noSink.auditPersisted()).isFalse();
        assertThat(noSink.auditWriteErrors()).isZero();
    }

    @Test
    void a_configured_sink_is_reported_as_persisting() {
        assertThat(recorder.auditPersisted()).isTrue();
    }

    @Test
    void the_feed_filters_and_returns_the_most_recent_first() {
        recorder.record(ok("kex_list_topics"));
        recorder.record(denied("kex_sql_query", -32046, McpGuard.VALIDATION));

        assertThat(recorder.recent(new McpCallFilter(null, "kex_sql_query", null, null, null), 10))
                .singleElement()
                .satisfies(call -> assertThat(call.tool()).isEqualTo("kex_sql_query"));
    }

    private static McpCallRecord ok(String tool) {
        return new McpCallRecord("c-" + tool, Instant.now(), 12L, McpCallRecord.Origin.AGENT,
                "svc@corp", "claude-code/1.4.2", tool, Map.of(), McpCallRecord.Outcome.OK,
                null, null, Measured.of(42L), StopReason.EXHAUSTED, false, 512L);
    }

    private static McpCallRecord denied(String tool, int code, McpGuard guard) {
        return new McpCallRecord("d-" + tool, Instant.now(), 2L, McpCallRecord.Origin.AGENT,
                "svc@corp", "claude-code/1.4.2", tool, Map.of(), McpCallRecord.Outcome.DENIED,
                code, guard, Measured.unmeasured("refused before reading"), null, false, 0L);
    }
}
