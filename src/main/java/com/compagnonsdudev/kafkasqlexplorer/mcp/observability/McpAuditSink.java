// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.observability;

/**
 * Where an MCP call is kept beyond the console's memory ring.
 *
 * <p>An interface with no implementation yet, because the two halves land in different phases: the
 * ring buffer and the metrics are phase 1, the append-only Kafka topic is phase 5. The recorder is
 * written against it from the start so the counter that tracks failed appends — and the console
 * field that surfaces it — exist before there is anything to fail.
 *
 * <p><b>There is deliberately no no-op implementation.</b> The first draft had one, reporting
 * itself inactive through an {@code active()} method; CodeQL flagged its {@code append} for an
 * unused parameter and was right — a Null Object whose whole body is "do nothing with this" is
 * weight, and the {@code active()} method existed only so callers could ask whether the object
 * meant anything. A deployment that persists nothing now simply has <b>no sink bean</b>, and
 * {@link McpCallRecorder#auditPersisted()} answers from its absence. "There is nowhere to write
 * this" is also the truer sentence: a sink that accepts and discards claims a destination that
 * does not exist.
 */
public interface McpAuditSink {

    void append(McpCallRecord call);

    /**
     * Appends this sink accepted and then failed to deliver, asynchronously.
     *
     * <p>It exists because the counter that is supposed to say "the trail has holes" could not see
     * the hole it is most likely to have. {@link McpCallRecorder} counts what {@code append} throws
     * — a record that will not serialise, a {@code send} that refused on the spot — and a Kafka
     * producer reports the ordinary failure, the broker not taking the record, on its own callback
     * thread, long after {@code append} returned. That failure was logged and counted nowhere, so
     * {@code explorer_mcp_audit_write_errors_total} read zero through exactly the outage it is
     * watched for.
     *
     * <p>Read rather than pushed: a sink that called back into the recorder would be a cycle
     * between two beans, one of which is constructed with the other.
     */
    default long asyncWriteErrors() {
        return 0L;
    }
}
