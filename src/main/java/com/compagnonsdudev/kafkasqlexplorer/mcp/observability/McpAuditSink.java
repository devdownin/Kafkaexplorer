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
}
