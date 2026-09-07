// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.observability;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps every MCP call: in a bounded ring for the live console, in metrics for alerting, and on
 * the audit sink for history.
 *
 * <p><b>A failed audit append never fails the call.</b> An MCP call that worked, refused by the
 * bookkeeping behind it, would be a self-inflicted outage — so the append is caught, counted
 * ({@code explorer_mcp_audit_write_errors_total}) and shown on the console. The counter is not
 * decoration: a call that was not recorded is not a call that did not happen, and a console
 * reporting a confident total over a log that lost writes is the same lie this module was built to
 * stop, one level up.
 *
 * <p><b>The ring is an {@link ArrayDeque} under a lock, not a {@code ConcurrentLinkedQueue}.</b>
 * The lock-free queue looks like the right tool until the trim loop needs {@code size()}, which it
 * computes by walking the whole queue — an O(n) traversal on <em>every</em> recorded call, on the
 * hot path, growing with the buffer the operator configured. The deque gives O(1) size and O(1)
 * eviction; the lock is held for a push and a poll, which is shorter than the traversal it
 * replaces.
 */
public class McpCallRecorder {

    private static final Logger log = LoggerFactory.getLogger(McpCallRecorder.class);

    private final Deque<McpCallRecord> ring = new ArrayDeque<>();
    private final Object ringLock = new Object();
    private final int capacity;

    /** Null until phase 5 configures one — see {@link McpAuditSink} for why that is not a no-op. */
    private final McpAuditSink auditSink;
    private final MeterRegistry meters;
    private final AtomicLong auditWriteErrors = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    public McpCallRecorder(McpProperties properties, McpAuditSink auditSink, MeterRegistry meters) {
        // auditSink may be null: no sink configured is a state the console reports, not a failure.
        this.capacity = Math.max(1, properties.getConsole().getRingBufferSize());
        this.auditSink = auditSink;
        this.meters = meters;
        Gauge.builder("explorer_mcp_audit_write_errors_total", auditWriteErrors, AtomicLong::get)
                .description("MCP calls whose audit append failed; the call itself still succeeded")
                .register(meters);
    }

    public void record(McpCallRecord call) {
        synchronized (ringLock) {
            ring.addLast(call);
            while (ring.size() > capacity) {
                ring.pollFirst();
                dropped.incrementAndGet();
            }
        }

        meters.counter("explorer_mcp_calls_total",
                "tool", call.tool(),
                "outcome", call.outcome().name(),
                "origin", call.origin().name()).increment();
        meters.timer("explorer_mcp_call_duration", "tool", call.tool())
                .record(call.durationMs(), TimeUnit.MILLISECONDS);
        if (call.outcome() == McpCallRecord.Outcome.DENIED) {
            meters.counter("explorer_mcp_denied_total",
                    "guard", call.deniedByGuard() == null ? "UNKNOWN" : call.deniedByGuard().name(),
                    "code", String.valueOf(call.jsonRpcErrorCode())).increment();
        }
        if (call.truncated()) {
            meters.counter("explorer_mcp_output_truncated_total", "tool", call.tool()).increment();
        }
        if (call.recordsScanned() != null && call.recordsScanned().measured()) {
            meters.counter("explorer_mcp_records_scanned_total", "tool", call.tool())
                    .increment(call.recordsScanned().value());
        }
        if (call.outputBytes() != null && call.outputBytes().measured()) {
            meters.counter("explorer_mcp_output_bytes_total", "tool", call.tool())
                    .increment(call.outputBytes().value());
        }

        if (auditSink == null) {
            return;
        }
        try {
            auditSink.append(call);
        } catch (RuntimeException e) {
            auditWriteErrors.incrementAndGet();
            log.warn("MCP audit append failed for correlationId={}", call.correlationId(), e);
        }
    }

    /**
     * Everything the ring currently holds, oldest first.
     *
     * <p>A copy taken under the lock rather than a live view: the console aggregates over it
     * several times per request (counts, percentiles, per-tool and per-identity groupings) and
     * iterating the live deque would either hold the lock across all of that or read a ring that
     * changes underneath the sums, so two cards on the same screen would disagree.
     */
    public List<McpCallRecord> snapshot() {
        synchronized (ringLock) {
            return List.copyOf(ring);
        }
    }

    /** Most recent first, so the console's first page is the one an operator wants. */
    public List<McpCallRecord> recent(McpCallFilter filter, int limit) {
        List<McpCallRecord> snapshot;
        synchronized (ringLock) {
            snapshot = new ArrayList<>(ring);
        }
        return snapshot.stream()
                .filter(filter)
                .sorted(Comparator.comparing(McpCallRecord::startedAt).reversed())
                .limit(Math.max(0, limit))
                .toList();
    }

    public long auditWriteErrors() {
        return auditWriteErrors.get();
    }

    /**
     * Calls evicted from the ring since startup.
     *
     * <p>The console needs it to say "earlier history: replay from the audit topic" rather than
     * showing a list that is short for reasons it does not mention.
     */
    public long droppedFromRing() {
        return dropped.get();
    }

    /**
     * Whether anything outlives the ring. False makes the console say so, rather than letting an
     * operator read a bounded live feed as the history it is not.
     */
    public boolean auditPersisted() {
        return auditSink != null;
    }

    public int ringCapacity() {
        return capacity;
    }
}
