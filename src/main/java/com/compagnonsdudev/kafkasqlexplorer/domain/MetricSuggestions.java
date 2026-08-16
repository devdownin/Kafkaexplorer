// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;

/**
 * The suggested KPIs, together with the state of the evidence they were derived from.
 *
 * <p>An empty list has two very different meanings — "nothing has been measured yet" and "what was
 * measured suggests nothing" — and a panel that cannot tell them apart sends the operator looking
 * for a bug. The evidence fields are what separates them: with no audit and no trace, the panel
 * says the feature is waiting on a run rather than claiming the cluster needs no KPI.
 *
 * @param suggestions         proposals, most specific evidence first
 * @param auditAvailable      whether an audit report could be read at all
 * @param auditId             the run the audit-derived proposals come from, null when none
 * @param auditTimestamp      when that run was produced (epoch millis), null when not recorded —
 *                            a proposal derived from a three-week-old scan is worth knowing about
 * @param auditSource         {@code CURRENT_RUN} (this process) or {@code HISTORY}
 *                            ({@code internal.audit.history}), null when no report was read
 * @param auditTopics         topics that run actually audited, so the reader can see the scope the
 *                            proposals rest on
 * @param flowChainsSubmitted Stream Flow traces the caller carried back from the browser
 * @param notes               what was found but not turned into a metric, and what would unlock
 *                            more — never silence
 */
public record MetricSuggestions(
    List<MetricSuggestion> suggestions,
    boolean auditAvailable,
    String auditId,
    Long auditTimestamp,
    String auditSource,
    int auditTopics,
    int flowChainsSubmitted,
    List<String> notes
) {}
