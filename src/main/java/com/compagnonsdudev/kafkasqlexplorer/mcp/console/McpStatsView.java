// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.console;

import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Measured;

import java.util.List;

/**
 * The four head cards of the supervision tab, and the aggregates behind them.
 *
 * <p><b>Refusals sit on the same card as successes.</b> Relegating them to a panel of their own
 * makes a control that blocks two hundred times an hour invisible until someone goes looking, and
 * nobody goes looking for a control that is working — that is how a rate limit set too tight lives
 * for a month as "the agent is a bit useless".
 *
 * @param calls            total in the observed window
 * @param denied           of which refused
 * @param errors           of which failed for a reason no guard chose
 * @param p50Ms            median latency, unmeasured under a usable sample
 * @param p95Ms            95th percentile, same rule
 * @param maxMs            slowest call, unmeasured when there were none
 * @param slowestTool      which tool that was, null when nothing was measured
 * @param recordsScanned   induced load: Kafka records read through MCP, summed over the tools that
 *                         count them — unmeasured when none of the calls in the window reported any
 * @param outputBytes      bytes served back to agents, same rule
 * @param activeIdentities distinct callers seen
 * @param topTools         busiest tools, most calls first
 * @param denialsByCode    refusals grouped by the code and the guard that produced them
 * @param observedWindow   what all of the above actually rests on
 */
public record McpStatsView(
        long calls,
        long denied,
        long errors,
        Measured<Long> p50Ms,
        Measured<Long> p95Ms,
        Measured<Long> maxMs,
        String slowestTool,
        Measured<Long> recordsScanned,
        Measured<Long> outputBytes,
        int activeIdentities,
        List<ToolCount> topTools,
        List<DenialCount> denialsByCode,
        ObservedWindow observedWindow
) {

    public record ToolCount(String tool, long calls) {}

    /**
     * @param guard which control refused — several codes share a guard and several guards share a
     *              code, so the console groups by both or it sends an operator to the wrong setting
     */
    public record DenialCount(Integer jsonRpcErrorCode, String guard, long count) {}

    public McpStatsView {
        topTools = topTools == null ? List.of() : List.copyOf(topTools);
        denialsByCode = denialsByCode == null ? List.of() : List.copyOf(denialsByCode);
    }
}
