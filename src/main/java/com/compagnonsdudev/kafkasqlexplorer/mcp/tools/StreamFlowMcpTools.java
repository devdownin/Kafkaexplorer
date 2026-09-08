// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.domain.StreamFlowCoverage;
import com.compagnonsdudev.kafkasqlexplorer.domain.StreamFlowHit;
import com.compagnonsdudev.kafkasqlexplorer.domain.StreamFlowRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.StreamFlowResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.StreamFlowStats;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Coverage;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.StopReason;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.ToolResult;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Warning;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpErrorCode;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpGuard;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpToolException;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.ToolCategory;
import com.compagnonsdudev.kafkasqlexplorer.service.StreamFlowService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The tools this whole server exists to justify: follow one business key across the cluster.
 *
 * <p>Every other Kafka MCP server answers "where did ORD-1042 go?" with N {@code consume_messages}
 * calls and the model's own correlation — thousands of records through a context window, an answer
 * that costs more than the question, and nothing in the reply saying which topics were never looked
 * at. Here it is one call, and the envelope names what it did not reach.
 *
 * <p>An adapter, as always: {@code StreamFlowService} does the tracing, on the budgets and the
 * per-topic caps the UI already uses. What this class adds is the criterion mapping, the guard, and
 * the coverage envelope.
 */
public class StreamFlowMcpTools implements ReadOnlyMcpTools {

    private final StreamFlowService streamFlow;
    private final ToolGuard guard;
    private final McpTraceStore traces;

    public StreamFlowMcpTools(StreamFlowService streamFlow, ToolGuard guard, McpTraceStore traces) {
        this.streamFlow = streamFlow;
        this.guard = guard;
        this.traces = traces;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.CORRELATION;
    }

    @McpTool(name = "kex_trace_key", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = true),
            description = """
            Follow one business key across the whole Kafka cluster and return the ordered hops with
            the latency between each. One call replaces reading every topic yourself.

            ALWAYS read `coverage`. An empty `hops` array with `stopReason` other than EXHAUSTED
            means "not found in the topics that were scanned", NOT "does not exist" —
            `coverage.topicsNotReached` names what was missed, and `coverage.resumeToken` continues
            the search with kex_resume_trace.

            `mode` picks how the value is matched:
              EXACT_KEY  the record key, exactly — the cheapest, and the only one that can skip
                         partitions instead of reading them all
              FIELD      one field of the payload; give its location in `path`. A dotted path
                         (order.customer.id), a JSONPath ($.items[0].sku, $..id) or an XPath
                         (/order/id) are all accepted — the shape decides, so there is no third
                         parameter to get wrong
              HEADER     a record header; give its name in `headerName`
              ANY        key, payload and headers — the widest and the slowest

            The hop marked `slowest` is where the time went. `clockSkew`, when set, means two
            brokers disagree about the time rather than that a message travelled backwards.""")
    public ToolResult<TraceView.Trace> traceKey(
            @McpToolParam(description = "The value to look for, e.g. ORD-1042") String value,
            @McpToolParam(required = false, description = "EXACT_KEY | FIELD | HEADER | ANY (default ANY)")
            String mode,
            @McpToolParam(required = false, description = "Header name, required when mode=HEADER")
            String headerName,
            @McpToolParam(required = false, description = "Where to look, required when mode=FIELD: order.customer.id, $.items[0].sku or /order/id")
            String path,
            @McpToolParam(required = false, description = "Restrict to these topics; empty searches the most recently active")
            List<String> topics,
            @McpToolParam(required = false, description = "Only look this many minutes back")
            Integer withinMinutes,
            @McpToolParam(required = false, description = "Records read per topic; clamped by the server ceiling")
            Integer maxRecordsPerTopic) {

        // Before any read: a scope check that runs afterwards has already disclosed what it refused.
        guard.checkTopicScope(topics);

        if (value == null || value.isBlank()) {
            throw new McpToolException(McpErrorCode.VALIDATION_FAILED, McpGuard.VALIDATION,
                    "a value to trace is required");
        }

        StreamFlowRequest request = request(value, mode, headerName, path, topics, withinMinutes,
                maxRecordsPerTopic, List.of(), StreamFlowCoverage.none());
        return run(request, describe(value, mode, headerName, path));
    }

    @McpTool(name = "kex_resume_trace", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = true),
            description = """
            Continue a trace that stopped before it had read everything, using the `resumeToken`
            from a previous kex_trace_key or kex_resume_trace response.

            The result is the WHOLE chain — earlier hops merged with the new ones — and its coverage
            counts every pass, not just this one. Keep resuming while `coverage.resumeToken` is
            present.

            A token is usable for about fifteen minutes. An unknown or expired one is reported as
            such rather than answered with an empty second pass, which would claim "nothing more
            found" about topics that were never read.""")
    public ToolResult<TraceView.Trace> resumeTrace(
            @McpToolParam(description = "The resumeToken from a previous trace") String resumeToken) {

        Optional<McpTraceStore.PausedTrace> paused = traces.take(resumeToken);
        if (paused.isEmpty()) {
            throw new McpToolException(McpErrorCode.VALIDATION_FAILED, McpGuard.VALIDATION,
                    ("resume token %s is unknown or has expired (tokens last about %d minutes). "
                            + "The trace has to be run again from the start with kex_trace_key — "
                            + "resuming from nothing would report \"nothing more found\" about "
                            + "topics that were never read.")
                            .formatted(resumeToken, McpTraceStore.TTL.toMinutes()));
        }

        McpTraceStore.PausedTrace trace = paused.get();
        StreamFlowRequest previous = trace.request();
        StreamFlowRequest request = new StreamFlowRequest(
                previous.messageKey(), previous.maxMessagesPerTopic(), previous.searchPath(),
                previous.timeLimitMinutes(), previous.useRegex(), previous.exactKey(),
                previous.caseSensitive(), previous.searchHeaders(),
                trace.remainingTopics(), trace.hits(), trace.coverage());

        return run(request, describe(previous.messageKey(), null, null, previous.searchPath()));
    }

    @McpTool(name = "kex_compare_traces", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = true),
            description = """
            Trace two keys and compare their paths: which topics both reached, which only one
            reached, and how the per-hop latency differs.

            Reports latency DIFFERENCES per hop, never absolute timestamps. Two keys produced at
            different moments have every timestamp different, so comparing those is noise that looks
            like signal; the question "which step got slower for this one" is answered by the delta.

            A topic in `onlyInA` or `onlyInB` is where the two flows diverged — usually the most
            interesting line in the answer. Read each trace's own `coverage`: a topic missing from
            one side because that pass ran out of budget is not a divergence.""")
    public ToolResult<TraceView.Comparison> compareTraces(
            @McpToolParam(description = "First value, e.g. ORD-1042") String valueA,
            @McpToolParam(description = "Second value, e.g. ORD-1043") String valueB,
            @McpToolParam(required = false, description = "EXACT_KEY | FIELD | HEADER | ANY (default ANY)")
            String mode,
            @McpToolParam(required = false, description = "Header name, required when mode=HEADER")
            String headerName,
            @McpToolParam(required = false, description = "Where to look, required when mode=FIELD: order.customer.id, $.items[0].sku or /order/id")
            String path,
            @McpToolParam(required = false, description = "Restrict to these topics; empty searches the most recently active")
            List<String> topics) {

        guard.checkTopicScope(topics);

        ToolResult<TraceView.Trace> a = run(
                request(valueA, mode, headerName, path, topics, null, null, List.of(), StreamFlowCoverage.none()),
                describe(valueA, mode, headerName, path));
        ToolResult<TraceView.Trace> b = run(
                request(valueB, mode, headerName, path, topics, null, null, List.of(), StreamFlowCoverage.none()),
                describe(valueB, mode, headerName, path));

        List<String> topicsA = a.data().hops().stream().map(TraceView.Hop::topic).toList();
        List<String> topicsB = b.data().hops().stream().map(TraceView.Hop::topic).toList();
        Set<String> inB = new LinkedHashSet<>(topicsB);
        List<String> common = topicsA.stream().filter(inB::contains).toList();

        Map<String, Long> latencyA = latencies(a.data());
        Map<String, Long> latencyB = latencies(b.data());
        List<TraceView.HopDelta> deltas = common.stream()
                .map(topic -> new TraceView.HopDelta(topic,
                        latencyA.get(topic) == null || latencyB.get(topic) == null
                                ? null
                                : latencyB.get(topic) - latencyA.get(topic)))
                .toList();

        List<Warning> warnings = new ArrayList<>(a.warnings());
        warnings.addAll(b.warnings());
        // The two coverages are merged rather than one of them picked: a divergence that is really
        // one side's spent budget must not read as a difference between the keys.
        if (!a.coverage().complete() || !b.coverage().complete()) {
            warnings.add(Warning.warn("PARTIAL_COMPARISON",
                    "at least one of the two traces did not finish, so a topic reached by only one "
                            + "key may simply not have been scanned for the other"));
        }

        TraceView.Comparison comparison = new TraceView.Comparison(
                common,
                topicsA.stream().filter(t -> !inB.contains(t)).toList(),
                topicsB.stream().filter(t -> !topicsA.contains(t)).toList(),
                deltas);

        Coverage merged = new Coverage(
                a.coverage().topicsRequested() + b.coverage().topicsRequested(),
                a.coverage().topicsScanned() + b.coverage().topicsScanned(),
                concat(a.coverage().topicsNotReached(), b.coverage().topicsNotReached()),
                a.coverage().recordsScanned() + b.coverage().recordsScanned(),
                a.coverage().elapsedMs() + b.coverage().elapsedMs(),
                a.coverage().complete() && b.coverage().complete()
                        ? StopReason.EXHAUSTED : StopReason.PARTIAL_FAILURE,
                null, null,
                // Deliberately no resume token: continuing one of two traces would leave the
                // comparison half-refreshed, and a caller cannot tell which half.
                null);

        return new ToolResult<>(comparison, merged, warnings, a.truncated() || b.truncated());
    }

    /** Runs one trace and turns the service's own stats into the module's envelope. */
    private ToolResult<TraceView.Trace> run(StreamFlowRequest request, String criterion) {
        StreamFlowResponse response;
        try {
            response = streamFlow.getStreamFlow(request);
        } catch (IllegalArgumentException e) {
            // A bad path or regex is the caller's, and its message names what is wrong with it.
            throw new McpToolException(McpErrorCode.VALIDATION_FAILED, McpGuard.VALIDATION,
                    e.getMessage());
        }

        StreamFlowStats stats = response.stats();
        List<StreamFlowHit> hits = response.hits() == null ? List.of() : response.hits();

        Long slowest = hits.stream().map(StreamFlowHit::latencyFromPreviousMs)
                .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);

        List<TraceView.Hop> hops = hits.stream()
                .map(hit -> new TraceView.Hop(
                        hit.topic(),
                        hit.occurrences(),
                        hit.firstTimestamp(),
                        hit.lastTimestamp(),
                        hit.firstPartition(),
                        hit.firstOffset(),
                        guard.dlp().scrub(hit.firstKey()),
                        guard.dlp().scrub(hit.preview()),
                        hit.latencyFromPreviousMs(),
                        slowest != null && slowest.equals(hit.latencyFromPreviousMs()),
                        hit.occurrencesCapped()))
                .toList();

        List<Warning> warnings = new ArrayList<>();
        if (response.warnings() != null) {
            response.warnings().forEach(w -> warnings.add(Warning.warn("TRACE", guard.dlp().scrub(w))));
        }

        // Skipped *and* failed: a topic whose read threw was not reached either, and leaving it out
        // of the coverage would let an empty trace look like a complete negative answer over it.
        // Both are resumable — a failure is often the broker being briefly busy.
        String resumeToken = null;
        List<String> notReached = concat(
                stats.skippedTopics() == null ? List.of() : stats.skippedTopics(),
                stats.failedTopics() == null ? List.of() : stats.failedTopics());
        if (!notReached.isEmpty()) {
            resumeToken = traces.remember(request, notReached, hits,
                    new StreamFlowCoverage(stats.topicsScanned(), stats.messagesScanned(),
                            stats.matches(), stats.durationMs()));
        }

        Coverage coverage = new Coverage(
                stats.topicsInScope(),
                stats.topicsScanned(),
                notReached,
                stats.messagesScanned(),
                stats.durationMs(),
                stopReason(stats),
                null, null,
                resumeToken);

        return new ToolResult<>(new TraceView.Trace(criterion, hops, clockSkew(hops)),
                coverage, warnings, stats.truncated());
    }

    /**
     * The service's stop reason, in this module's vocabulary.
     *
     * <p>{@code COMPLETE} with topics that failed is {@code PARTIAL_FAILURE}, not {@code EXHAUSTED}:
     * a scan that finished having been unable to read four topics has not covered the cluster, and
     * {@code EXHAUSTED} is the one value that licenses reading an empty result as a negative answer.
     */
    private static StopReason stopReason(StreamFlowStats stats) {
        if ("CANCELLED".equals(stats.stopReason())) {
            return StopReason.CANCELLED;
        }
        if ("TIME_BUDGET".equals(stats.stopReason())) {
            return StopReason.TIME_BUDGET;
        }
        if (stats.topicsFailed() > 0 || (stats.skippedTopics() != null && !stats.skippedTopics().isEmpty())) {
            return StopReason.PARTIAL_FAILURE;
        }
        return StopReason.EXHAUSTED;
    }

    /**
     * Names the case where a hop appears to precede the one before it.
     *
     * <p>Reported rather than hidden or "corrected": the message did not travel backwards, the two
     * brokers disagree about the time, and every latency across that boundary is suspect. A model
     * told only the numbers would reason about a negative delay.
     */
    private static String clockSkew(List<TraceView.Hop> hops) {
        for (int i = 1; i < hops.size(); i++) {
            if (hops.get(i).firstTimestampMs() < hops.get(i - 1).firstTimestampMs()) {
                return ("%s appears to precede %s. The record did not travel backwards: the brokers "
                        + "disagree about the time, so latencies across that boundary cannot be "
                        + "trusted.").formatted(hops.get(i).topic(), hops.get(i - 1).topic());
            }
        }
        return null;
    }

    private static Map<String, Long> latencies(TraceView.Trace trace) {
        Map<String, Long> byTopic = new java.util.LinkedHashMap<>();
        trace.hops().forEach(hop -> byTopic.put(hop.topic(), hop.latencyFromPreviousMs()));
        return byTopic;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    /**
     * Builds the service's request from the MCP criterion.
     *
     * <p>{@code StreamFlowService} encodes the match mode inside {@code searchPath} — a
     * {@code header:} prefix, a leading slash for XPath, a dotted path otherwise — and the mapping
     * lives here rather than being a second matcher. One matcher, in the service the UI uses, so an
     * agent and an operator asking the same question get the same hits.
     */
    private StreamFlowRequest request(String value, String mode, String headerName, String path,
                                      List<String> topics, Integer withinMinutes,
                                      Integer maxRecordsPerTopic, List<StreamFlowHit> priorHits,
                                      StreamFlowCoverage priorCoverage) {
        String resolved = mode == null || mode.isBlank() ? "ANY" : mode.trim().toUpperCase(Locale.ROOT);
        String searchPath = switch (resolved) {
            case "EXACT_KEY", "ANY" -> {
                rejectUnusedLocator(resolved, headerName, path);
                yield null;
            }
            case "HEADER" -> {
                if (headerName == null || headerName.isBlank()) {
                    throw new McpToolException(McpErrorCode.VALIDATION_FAILED, McpGuard.VALIDATION,
                            "mode=HEADER needs headerName — the header to look in");
                }
                if (path != null && !path.isBlank()) {
                    throw new McpToolException(McpErrorCode.VALIDATION_FAILED, McpGuard.VALIDATION,
                            "path applies to mode=FIELD; mode=HEADER is located by headerName");
                }
                yield "header:" + headerName.trim();
            }
            case "FIELD" -> {
                if (path == null || path.isBlank()) {
                    throw new McpToolException(McpErrorCode.VALIDATION_FAILED, McpGuard.VALIDATION,
                            "mode=FIELD needs path — where in the payload to look, e.g. "
                                    + "order.customer.id, $.items[0].sku or /order/id");
                }
                if (headerName != null && !headerName.isBlank()) {
                    throw new McpToolException(McpErrorCode.VALIDATION_FAILED, McpGuard.VALIDATION,
                            "headerName only applies to mode=HEADER");
                }
                // Refused rather than silently read as a header: `header:x` here would trace a
                // header while the response said FIELD, and no part of the answer would disagree.
                if (path.trim().toLowerCase(Locale.ROOT).startsWith("header:")) {
                    throw new McpToolException(McpErrorCode.VALIDATION_FAILED, McpGuard.VALIDATION,
                            "a header is reached with mode=HEADER and headerName, not with a "
                                    + "header: prefix in path");
                }
                yield path.trim();
            }
            default -> throw new McpToolException(McpErrorCode.VALIDATION_FAILED, McpGuard.VALIDATION,
                    ("unknown mode %s. Use EXACT_KEY, FIELD, HEADER or ANY.").formatted(mode));
        };

        return new StreamFlowRequest(
                value,
                guard.clampRecords(maxRecordsPerTopic),
                searchPath,
                withinMinutes,
                false,
                "EXACT_KEY".equals(resolved),
                false,
                !"EXACT_KEY".equals(resolved),
                topics,
                priorHits,
                priorCoverage);
    }

    /**
     * A locator given for a mode that has none is refused, not ignored: the whole cluster would be
     * scanned for the value anywhere while the caller believed one field was being read, and the
     * answer — plausible, wider than asked — carries nothing that contradicts them.
     */
    private static void rejectUnusedLocator(String mode, String headerName, String path) {
        if ((headerName != null && !headerName.isBlank()) || (path != null && !path.isBlank())) {
            throw new McpToolException(McpErrorCode.VALIDATION_FAILED, McpGuard.VALIDATION,
                    ("mode=%s matches the whole record, so it takes neither headerName nor path. "
                            + "Use mode=FIELD with path, or mode=HEADER with headerName.")
                            .formatted(mode));
        }
    }

    private static String describe(String value, String mode, String headerName, String path) {
        String resolved = mode == null || mode.isBlank() ? "ANY" : mode.trim().toUpperCase(Locale.ROOT);
        String locator = headerName != null && !headerName.isBlank() ? headerName : path;
        return locator == null || locator.isBlank()
                ? "%s (%s)".formatted(value, resolved)
                : "%s (%s %s)".formatted(value, resolved, locator);
    }
}
