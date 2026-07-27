// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

import java.util.List;

/**
 * A page of audit history, newest run first, together with what the scan covered.
 *
 * <p>The scan is bounded on purpose — the history topic is append-only and a report is large — so
 * the response states its own limits. A list that silently shows "the last 20 runs" when 500 exist
 * invites the reader to conclude the cluster was never audited before that.
 *
 * @param runs           summaries, newest first
 * @param recordsScanned Kafka records read to build this list
 * @param exhausted      true when the scan reached the beginning of the topic, so nothing older
 *                       exists; false means older runs are stored but were not read
 * @param warnings       anything that degraded the read (unreachable topic, unparseable record…)
 */
public record AuditHistory(
    List<AuditRunSummary> runs,
    int recordsScanned,
    boolean exhausted,
    List<String> warnings
) {
    public static AuditHistory empty(List<String> warnings) {
        return new AuditHistory(List.of(), 0, true, warnings);
    }
}
