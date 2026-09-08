// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.console;

import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Measured;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.ToolCategory;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.Visibility;

/**
 * One row of the catalogue table — including the tools no agent can see.
 *
 * <p>{@code p95Ms} is {@link Measured} rather than a number, and that is the rule this module
 * applies to itself: a percentile over three calls is not a percentile, and printing {@code 0 ms}
 * for a tool nobody has called yet says it is instantaneous. The reason names which of the two it
 * is — never called, or called too few times to rank.
 *
 * @param name          the tool name, as an agent would call it
 * @param category      Exploration · Correlation · Diagnostic · Write
 * @param description   exactly what the agent is told, never a paraphrase
 * @param visibility    exposed, exposed-with-approval, or hidden and why
 * @param defaultBudgetMs default time budget, null for a tool with no budget
 * @param hardMaxRecords records ceiling, null when the tool reads none
 * @param hardMaxRows    row ceiling, null when the tool returns none
 * @param hardMaxBytes   output ceiling, always set and now actually enforced
 * @param calls         calls seen in the observed window — see {@link ObservedWindow}
 * @param denied        how many of those were refused, so the row shows the ratio
 * @param p95Ms         95th percentile latency, unmeasured under {@link #MIN_CALLS_FOR_P95} calls
 */
public record McpToolRow(
        String name,
        ToolCategory category,
        String description,
        Visibility visibility,
        Long defaultBudgetMs,
        Integer hardMaxRecords,
        Integer hardMaxRows,
        int hardMaxBytes,
        long calls,
        long denied,
        Measured<Long> p95Ms
) {

    /**
     * Below this, a percentile is arithmetic on noise.
     *
     * <p>Twenty rather than five: at five calls the "95th percentile" is simply the slowest one,
     * which is the number most likely to be a cold start — and an operator who reads it as typical
     * chases a problem that does not exist.
     */
    public static final int MIN_CALLS_FOR_P95 = 20;
}
