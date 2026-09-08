// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.domain.MetricSuggestion;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricSuggestionRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricSuggestions;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Coverage;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Measured;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.StopReason;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.ToolResult;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Warning;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpErrorCode;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpToolException;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.ToolCategory;
import com.compagnonsdudev.kafkasqlexplorer.service.MetricSuggestionService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import java.util.ArrayList;
import java.util.List;

/**
 * "What should I be measuring on this cluster?" — answered from what has been measured, or not
 * answered at all.
 *
 * <p>An adapter over {@code MetricSuggestionService}, which derives its proposals from the audit's
 * findings, from declared lineage and from a validated field mapping. What this class adds is the
 * envelope and one refusal it inherits and makes explicit: <b>no threshold is invented.</b>
 *
 * <p>That refusal is the reason this tool is worth having. "Suggest KPIs for my Kafka cluster" is
 * a question a language model answers fluently from nothing — p99 latency under 200 ms, lag under
 * 1 000, error rate under 1 % — and every number in that answer is a plausible invention about a
 * cluster it has never read. Here each proposal cites the run and the measurement behind it, and
 * where nothing supports a threshold, {@code thresholdBasis} is <em>unmeasured with a reason</em>
 * rather than filled with a number that would be indistinguishable from the measured ones.
 */
public class KpiMcpTools implements ReadOnlyMcpTools {

    private final MetricSuggestionService suggestions;
    private final ToolGuard guard;

    public KpiMcpTools(MetricSuggestionService suggestions, ToolGuard guard) {
        this.suggestions = suggestions;
        this.guard = guard;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.DIAGNOSTIC;
    }

    @McpTool(name = "kex_suggest_kpis", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = true),
            description = """
            Propose KPIs for this cluster, each citing the measurement and the audit run it derives
            from. Returns the Flink SQL each metric would run.

            NO THRESHOLD IS INVENTED. `thresholdBasis` arrives measured, naming the observation a
            threshold would rest on, or unmeasured with the reason none does — and in that second
            case there is no number to publish. Do not supply one: a threshold with no measurement
            behind it is indistinguishable, to whoever reads the alert, from one that has been
            earned.

            `auditRunId` names the run these rest on. Without an audit the list is thin and `notes`
            says so — read them, because a short list because nothing has been measured is a
            different answer from a short list because the cluster is simple. Run kex_run_audit
            first to get the proposals derived from topic volumes, findings and flows.

            `dataState` ABSENT or EMPTY means the metric would be created and never produce a
            value; `alreadyConfigured` names the metric that covers it today.""")
    public ToolResult<KpiView.Suggestions> suggestKpis() {

        long startedAt = System.currentTimeMillis();
        MetricSuggestions result;
        try {
            // No flow chains: those are traces an operator recorded in a browser session, and an
            // agent has none. Passing an empty request is what the service already expects, and
            // its own note says which proposals that leaves out.
            result = suggestions.suggest(new MetricSuggestionRequest(List.of()));
        } catch (Exception e) {
            throw new McpToolException(McpErrorCode.DEPENDENCY_UNAVAILABLE,
                    "KPI suggestions could not be built: " + rootMessage(e));
        }

        List<Warning> warnings = new ArrayList<>();
        result.notes().forEach(note -> warnings.add(Warning.info("EVIDENCE", guard.dlp().scrub(note))));
        if (!result.auditAvailable()) {
            warnings.add(Warning.warn("NO_AUDIT",
                    "no audit could be read, so nothing here rests on a scan of this cluster's "
                            + "topics. Run kex_run_audit and ask again."));
        }

        KpiView.Suggestions view = new KpiView.Suggestions(
                result.suggestions().stream().map(this::kpi).toList(),
                Measured.ofNullable(result.auditId(),
                        "no audit run could be read; these proposals rest on lineage and "
                                + "configuration alone"),
                result.auditTopics(),
                result.notes());

        // An audit that was not read is not zero topics scanned: the count describes the evidence,
        // and EXHAUSTED here would license reading a short list as "there is little to measure".
        Coverage coverage = new Coverage(
                result.auditTopics(), result.auditTopics(), List.of(), 0L,
                System.currentTimeMillis() - startedAt,
                result.auditAvailable() ? StopReason.EXHAUSTED : StopReason.PARTIAL_FAILURE,
                null, null, null);

        return new ToolResult<>(view, coverage, warnings, false);
    }

    private KpiView.Kpi kpi(MetricSuggestion suggestion) {
        return new KpiView.Kpi(
                suggestion.id(),
                suggestion.source() == null ? null : suggestion.source().name(),
                suggestion.title(),
                suggestion.rationale(),
                suggestion.evidence() == null ? List.of() : suggestion.evidence(),
                // The one field that must never be filled in by the reader.
                Measured.ofNullable(blankToNull(suggestion.thresholdBasis()),
                        "nothing measured on this cluster supports a threshold for this metric yet "
                                + "— create it without one, watch it, and set the threshold from "
                                + "what it reports"),
                suggestion.caveats() == null ? List.of() : suggestion.caveats(),
                suggestion.dataState() == null ? null : suggestion.dataState().name(),
                suggestion.alreadyConfigured(),
                suggestion.existingMetricName(),
                suggestion.metric() == null ? null : guard.dlp().scrub(suggestion.metric().sql()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
