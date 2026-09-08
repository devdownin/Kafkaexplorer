// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.domain.AuditOptions;
import com.compagnonsdudev.kafkasqlexplorer.domain.AuditReport;
import com.compagnonsdudev.kafkasqlexplorer.domain.AuditStatus;
import com.compagnonsdudev.kafkasqlexplorer.domain.FlowAudit;
import com.compagnonsdudev.kafkasqlexplorer.domain.HealthStatus;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicAudit;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicIssue;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Coverage;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Measured;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.StopReason;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.ToolResult;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Warning;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpErrorCode;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpGuard;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpToolException;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.ToolCategory;
import com.compagnonsdudev.kafkasqlexplorer.service.AuditService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The cluster audit, started and read as two calls.
 *
 * <p><b>Two calls rather than one, and that is the honest shape.</b> A full audit reads every
 * topic, samples payloads, counts duplicates and traces flows: minutes, not seconds. A tool that
 * blocked on it would hit the caller's own timeout and return nothing at all, having spent the
 * whole scan — so {@code kex_run_audit} hands back an id and {@code kex_get_audit} answers with
 * whatever the run has so far, saying which of the two it is.
 *
 * <p>An adapter over {@code AuditService}: the same run an operator starts from the Audit page,
 * with the same checks and the same scope rules. An agent and an operator therefore see one run
 * and one verdict rather than two scans disagreeing about the same cluster.
 */
public class AuditMcpTools implements ReadOnlyMcpTools {

    /** Worst first, and healthy topics are not listed at all — a report is its findings. */
    private static final Comparator<TopicAudit> WORST_FIRST =
            Comparator.comparingInt((TopicAudit audit) -> audit.healthStatus().ordinal()).reversed()
                    .thenComparing(TopicAudit::name);

    private final AuditService audit;
    private final ToolGuard guard;

    public AuditMcpTools(AuditService audit, ToolGuard guard) {
        this.audit = audit;
        this.guard = guard;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.DIAGNOSTIC;
    }

    @McpTool(name = "kex_run_audit", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = true),
            description = """
            Start a cluster audit and return its runId. Read the result with kex_get_audit — this
            call does NOT wait, because a full audit reads every topic and takes minutes, and a
            tool that blocked on it would time out having spent the whole scan.

            CHECK `started`. When it is false an audit was ALREADY RUNNING and this call attached
            to it: the runId is that run's, and `scope` is the scope IT chose, not the one asked
            for here. Reading its findings as an answer to a different question is the mistake this
            field exists to prevent.

            `topicPrefix` restricts the run. Leave it empty to audit the whole cluster, which is
            what costs the minutes.""")
    public ToolResult<AuditView.Run> runAudit(
            @McpToolParam(required = false, description = "Only audit topics whose name starts with this")
            String topicPrefix,
            @McpToolParam(required = false, description = "Sample payloads for parse failures. Default true.")
            Boolean checkPoisonMessages,
            @McpToolParam(required = false, description = "Look for duplicate keys. Default true.")
            Boolean checkDuplicates,
            @McpToolParam(required = false, description = "Trace flows between topics. Default true.")
            Boolean checkFlows,
            @McpToolParam(required = false, description = "Count records exactly rather than from offsets. Slower. Default true.")
            Boolean checkExactCount,
            @McpToolParam(required = false, description = "Read each topic's consumer groups. Several round trips per topic. Default true.")
            Boolean checkConsumerLag) {

        // Before anything is started: a prefix outside the scope must not launch a wider run.
        String prefix = topicPrefix == null || topicPrefix.isBlank() ? null : topicPrefix.trim();
        guard.checkTopicScope(prefix);

        // An unscoped run on a scoped deployment would audit exactly what the prefixes withhold,
        // so the scope is applied rather than the call refused: the agent gets its slice.
        List<String> allowed = guard.properties().getAllowedTopicPrefixes();
        List<Warning> warnings = new ArrayList<>();
        if (prefix == null && !com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties.unrestricted(allowed)) {
            if (allowed.size() > 1) {
                throw new McpToolException(McpErrorCode.OUT_OF_SCOPE, McpGuard.SCOPE,
                        ("this deployment allows %d topic prefixes (%s) and an audit run takes one. "
                                + "Name the prefix to audit in topicPrefix.")
                                .formatted(allowed.size(), String.join(", ", allowed)));
            }
            prefix = allowed.get(0);
            warnings.add(Warning.info("SCOPED_RUN",
                    "the run was restricted to '%s', this deployment's only allowed topic prefix"
                            .formatted(prefix)));
        }

        long startedAt = System.currentTimeMillis();
        AuditOptions options = new AuditOptions(
                true,
                !Boolean.FALSE.equals(checkPoisonMessages),
                !Boolean.FALSE.equals(checkDuplicates),
                !Boolean.FALSE.equals(checkFlows),
                !Boolean.FALSE.equals(checkExactCount),
                !Boolean.FALSE.equals(checkConsumerLag),
                prefix);

        AuditService.AuditStart start;
        try {
            start = audit.startAudit(options);
        } catch (Exception e) {
            throw new McpToolException(McpErrorCode.DEPENDENCY_UNAVAILABLE,
                    "the audit could not be started: " + rootMessage(e));
        }

        String note;
        if (start.started()) {
            note = "the run has started; poll kex_get_audit with this runId. A whole-cluster audit "
                    + "takes minutes, and its partial results are readable throughout.";
        } else {
            note = "an audit was already running, so this call attached to it. Its scope is the "
                    + "one that run chose, which may not be the one asked for here — check `scope` "
                    + "on the report before reading its findings as an answer to this question.";
            warnings.add(Warning.warn("ATTACHED_TO_RUNNING_AUDIT", note));
        }

        String scope = start.started() ? prefix : scopeOf(audit.getAuditReport(start.auditId()));
        return new ToolResult<>(
                new AuditView.Run(start.auditId(), start.started(), scope, note),
                // Nothing has been read yet: the run is asynchronous, and claiming a scanned
                // count here would describe a scan that has not happened.
                new Coverage(0, 0, List.of(), 0L, System.currentTimeMillis() - startedAt,
                        StopReason.EXHAUSTED, null, null, null),
                warnings, false);
    }

    @McpTool(name = "kex_get_audit", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = true),
            description = """
            Read an audit run's findings, by the runId kex_run_audit returned. Omit runId for the
            most recent run.

            READ `status` FIRST:
              RUNNING    partial results from a scan still in progress — an absent finding is not
                         a finding of absence; poll again
              COMPLETED  the scan finished over its scope
              CANCELLED  stopped early; the topics reached before the stop are still reported
              FAILED     the run did not produce a verdict

            `scope` is the topic prefix the run covered. A clean report over `demo.` says nothing
            about the rest of the cluster, and `coverage.stopReason` carries the same fact.

            Findings are worst first and healthy topics are NOT listed — the count of topics
            audited is in `topicsAudited`, so an empty `findings` on a COMPLETED run over many
            topics is genuinely good news, and on a RUNNING one is not news at all.""")
    public ToolResult<AuditView.Report> getAudit(
            @McpToolParam(required = false, description = "The runId; omit for the most recent run")
            String runId) {

        long startedAt = System.currentTimeMillis();
        AuditReport report = runId == null || runId.isBlank()
                ? audit.getLastAuditReport()
                : audit.getAuditReport(runId.trim());

        if (report == null) {
            throw new McpToolException(McpErrorCode.VALIDATION_FAILED, McpGuard.VALIDATION,
                    runId == null || runId.isBlank()
                            ? "no audit has been run in this process. Start one with kex_run_audit."
                            : ("no audit run called %s is known. Runs are held in memory, so one "
                                    + "from before a restart is gone — start a new one with "
                                    + "kex_run_audit.").formatted(runId));
        }

        List<Warning> warnings = new ArrayList<>();
        List<TopicAudit> findings = report.topicAudits().stream()
                .filter(topicAudit -> topicAudit.healthStatus() != HealthStatus.HEALTHY)
                .sorted(WORST_FIRST)
                .toList();

        if (report.status() == AuditStatus.RUNNING) {
            warnings.add(Warning.warn("AUDIT_RUNNING",
                    ("this run is still scanning: %d topic(s) have a verdict so far, and a topic "
                            + "with no finding here may simply not have been reached yet")
                            .formatted(report.topicAudits().size())));
        }
        if (report.status() == AuditStatus.CANCELLED) {
            warnings.add(Warning.warn("AUDIT_CANCELLED",
                    "this run was stopped before it finished; what it reports covers the topics "
                            + "reached before the stop, not the scope it was given"));
        }

        String scope = scopeOf(report);
        if (scope != null) {
            warnings.add(Warning.info("SCOPED_AUDIT",
                    ("this run covered topics starting with '%s' only — it says nothing about the "
                            + "rest of the cluster").formatted(scope)));
        }

        AuditView.Report view = new AuditView.Report(
                report.auditId(),
                report.status().name(),
                healthScore(report),
                report.topicAudits().size(),
                // Zero on a run that counted nothing is not zero records: the count is a check of
                // its own (checkExactCount) and it can have been turned off or have failed.
                Measured.ofNullable(report.totalMessages() > 0 ? report.totalMessages() : null,
                        "no exact count ran, or none of the topics reported one"),
                report.criticalTopicsCount(),
                report.warningTopicsCount(),
                scope,
                findings.stream().map(AuditMcpTools::finding).toList(),
                report.flowAudits().stream().map(AuditMcpTools::flow).toList());

        Coverage coverage = new Coverage(
                topicsInScope(report),
                report.topicAudits().size(),
                List.of(),
                0L,
                System.currentTimeMillis() - startedAt,
                switch (report.status()) {
                    case RUNNING -> StopReason.TIME_BUDGET;
                    case CANCELLED -> StopReason.CANCELLED;
                    case FAILED -> StopReason.PARTIAL_FAILURE;
                    case COMPLETED -> StopReason.EXHAUSTED;
                },
                null, null,
                // The runId continues the read, which is what a resume token is for here.
                report.status() == AuditStatus.RUNNING ? report.auditId() : null);

        return new ToolResult<>(view, coverage, warnings,
                report.status() != AuditStatus.COMPLETED);
    }

    /** The run's own score, or unmeasured — never a zero, which reads as "everything is broken". */
    private static Measured<Double> healthScore(AuditReport report) {
        Object score = stat(report, "healthScore");
        if (score instanceof Number number) {
            return Measured.of(number.doubleValue());
        }
        return Measured.unmeasured(report.status() == AuditStatus.RUNNING
                ? "the run has not computed a score yet"
                : "this run produced no score");
    }

    private static String scopeOf(AuditReport report) {
        if (report == null) {
            return null;
        }
        Object options = stat(report, "options");
        if (options instanceof Map<?, ?> map && map.get("topicPrefix") instanceof String prefix
                && !prefix.isBlank()) {
            return prefix;
        }
        return null;
    }

    private static int topicsInScope(AuditReport report) {
        Object inScope = stat(report, "topicsInScope");
        if (inScope instanceof Number number) {
            return number.intValue();
        }
        return (int) Math.max(report.totalTopics(), report.topicAudits().size());
    }

    private static Object stat(AuditReport report, String key) {
        Map<String, Object> stats = report.globalStats();
        return stats == null ? null : stats.get(key);
    }

    private static AuditView.TopicFinding finding(TopicAudit topicAudit) {
        return new AuditView.TopicFinding(
                topicAudit.name(),
                topicAudit.healthStatus().name(),
                Measured.ofNullable(topicAudit.messageCount() > 0 ? topicAudit.messageCount() : null,
                        "this topic reported no count: the exact count was off, or it is empty"),
                topicAudit.poisonMessageCount(),
                topicAudit.duplicateCount(),
                topicAudit.issues().stream()
                        .sorted(Comparator.comparingInt(
                                (TopicIssue issue) -> issue.severity().ordinal()).reversed())
                        .map(TopicIssue::message).toList());
    }

    private static AuditView.FlowFinding flow(FlowAudit flowAudit) {
        return new AuditView.FlowFinding(
                flowAudit.flowName(),
                flowAudit.overallHealthScore(),
                flowAudit.steps().stream()
                        .map(step -> new AuditView.Step(
                                step.topicName(),
                                step.count(),
                                step.throughputPercentage(),
                                Measured.ofNullable(step.averageLatencyMs(),
                                        "no pair of records could be timed across this step")))
                        .toList());
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
