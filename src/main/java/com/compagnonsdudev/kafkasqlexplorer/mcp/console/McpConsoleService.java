// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.console;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Measured;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpCallFilter;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpCallRecord;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpCallRecorder;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpCatalogService;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.ToolCategory;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.ToolDescriptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Everything {@code /api/mcp/**} answers, computed from the live registry and the call ring.
 *
 * <p><b>Every number here carries the window it was computed over.</b> The ring is bounded — it is
 * a live feed, not a history store — so a card headed "24 h" over a ring that reaches back forty
 * minutes is a wrong number wearing a right label, and wrong in the direction that reassures. Each
 * response therefore carries an {@link ObservedWindow} saying how far back the data really goes,
 * how much was evicted, and whether anything is persisted beyond it at all. That is principle P1
 * turned on the supervision screen, which is itself a measurement.
 *
 * <p>Nothing is read from a constant. The tool table comes from {@link McpCatalogService}, which
 * introspects the same objects the protocol serves; the counters come from the recorder the
 * interceptor feeds. A number typed into this class would be a third source of truth about a
 * surface that already has one.
 */
public class McpConsoleService {

    private final McpProperties properties;
    private final McpCatalogService catalog;
    private final McpCallRecorder recorder;
    private final McpEndpointResolver endpoints;

    public McpConsoleService(McpProperties properties, McpCatalogService catalog,
                             McpCallRecorder recorder, McpEndpointResolver endpoints) {
        this.properties = properties;
        this.catalog = catalog;
        this.recorder = recorder;
        this.endpoints = endpoints;
    }

    public McpStatusView status() {
        if (!properties.isEnabled()) {
            return McpStatusView.disabled();
        }
        List<String> mutating = catalog.catalog().stream()
                .filter(tool -> tool.category() == ToolCategory.WRITE)
                .filter(tool -> tool.visibility().visibleToAgents())
                .map(ToolDescriptor::name)
                .toList();

        return new McpStatusView(
                true,
                List.of("HTTP (streamable)"),
                endpoints.endpoint(),
                properties.isReadonly(),
                !mutating.isEmpty(),
                mutating,
                // Named as the absence it is. Phase 5 brings OAuth 2.1; until then a deployment
                // that exposes this endpoint beyond a trusted network has no caller authentication
                // at all, and the banner is where an operator would find that out.
                "none — anything that can reach the endpoint can call it (OAuth 2.1 is phase 5)",
                properties.getAllowedTopicPrefixes(),
                properties.getAllowedGroupPrefixes());
    }

    public McpCatalogView catalog(Duration window) {
        List<McpCallRecord> calls = within(window);
        Map<String, List<McpCallRecord>> byTool = calls.stream()
                .collect(Collectors.groupingBy(McpCallRecord::tool));

        List<McpToolRow> rows = catalog.catalog().stream()
                .map(tool -> row(tool, byTool.getOrDefault(tool.name(), List.of())))
                .toList();
        return new McpCatalogView(rows, observedWindow(window));
    }

    private static McpToolRow row(ToolDescriptor tool, List<McpCallRecord> calls) {
        long denied = calls.stream().filter(c -> c.outcome() == McpCallRecord.Outcome.DENIED).count();
        return new McpToolRow(
                tool.name(),
                tool.category(),
                tool.description(),
                tool.visibility(),
                tool.defaultBudgetMs(),
                tool.hardMaxRecords(),
                tool.hardMaxRows(),
                tool.hardMaxBytes(),
                calls.size(),
                denied,
                percentile(calls, 95, McpToolRow.MIN_CALLS_FOR_P95));
    }

    public List<McpCallView> calls(McpCallFilter filter, int limit) {
        return recorder.recent(filter, limit).stream().map(McpCallView::from).toList();
    }

    public McpStatsView stats(Duration window) {
        List<McpCallRecord> calls = within(window);

        long denied = calls.stream().filter(c -> c.outcome() == McpCallRecord.Outcome.DENIED).count();
        long errors = calls.stream().filter(c -> c.outcome() == McpCallRecord.Outcome.ERROR).count();

        McpCallRecord slowest = calls.stream().max(Comparator.comparingLong(McpCallRecord::durationMs))
                .orElse(null);

        List<McpStatsView.ToolCount> topTools = calls.stream()
                .collect(Collectors.groupingBy(McpCallRecord::tool, Collectors.counting()))
                .entrySet().stream()
                .map(e -> new McpStatsView.ToolCount(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(McpStatsView.ToolCount::calls).reversed()
                        .thenComparing(McpStatsView.ToolCount::tool))
                .toList();

        // Grouped by code AND guard: several guards answer with one code and one guard can answer
        // with several, so collapsing either way sends an operator to a setting that was not
        // involved.
        Map<String, McpStatsView.DenialCount> denials = new LinkedHashMap<>();
        calls.stream().filter(c -> c.outcome() == McpCallRecord.Outcome.DENIED).forEach(call -> {
            String guard = call.deniedByGuard() == null ? "UNKNOWN" : call.deniedByGuard().name();
            String key = call.jsonRpcErrorCode() + "/" + guard;
            McpStatsView.DenialCount existing = denials.get(key);
            denials.put(key, new McpStatsView.DenialCount(call.jsonRpcErrorCode(), guard,
                    existing == null ? 1 : existing.count() + 1));
        });

        Set<String> identities = calls.stream().map(McpCallRecord::identity)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());

        return new McpStatsView(
                calls.size(),
                denied,
                errors,
                percentile(calls, 50, 1),
                percentile(calls, 95, McpToolRow.MIN_CALLS_FOR_P95),
                slowest == null
                        ? Measured.unmeasured("no call in this window")
                        : Measured.of(slowest.durationMs()),
                slowest == null ? null : slowest.tool(),
                sumMeasured(calls, McpCallRecord::recordsScanned,
                        "no call in this window reported a record count"),
                sumMeasured(calls, McpCallRecord::outputBytes,
                        "no call in this window reported an output size"),
                identities.size(),
                topTools.stream().limit(10).toList(),
                List.copyOf(denials.values()),
                observedWindow(window));
    }

    public List<McpClientRow> clients(Duration window) {
        Map<String, List<McpCallRecord>> byIdentity = within(window).stream()
                .filter(call -> call.identity() != null)
                .collect(Collectors.groupingBy(McpCallRecord::identity));

        List<McpClientRow> rows = new ArrayList<>();
        byIdentity.forEach((identity, calls) -> rows.add(new McpClientRow(
                identity,
                calls.stream().map(McpCallRecord::clientInfo).filter(java.util.Objects::nonNull)
                        .findFirst().orElse(null),
                calls.size(),
                calls.stream().filter(c -> c.outcome() == McpCallRecord.Outcome.DENIED).count(),
                calls.stream().map(McpCallRecord::startedAt).max(Comparator.naturalOrder())
                        .orElse(null))));
        rows.sort(Comparator.comparing(McpClientRow::lastSeenAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return rows;
    }

    /**
     * Sums a {@link Measured} field, staying unmeasured when nothing in the window reported one.
     *
     * <p>A sum that skips the unmeasured entries and returns {@code 0} would say "the agent read no
     * records" about a window in which nothing counted them. Zero is only reported when at least
     * one call actually measured a zero.
     */
    private static Measured<Long> sumMeasured(List<McpCallRecord> calls,
                                              java.util.function.Function<McpCallRecord, Measured<Long>> field,
                                              String whyIfNone) {
        long total = 0;
        boolean any = false;
        for (McpCallRecord call : calls) {
            Measured<Long> value = field.apply(call);
            if (value != null && value.measured()) {
                total += value.value();
                any = true;
            }
        }
        return any ? Measured.of(total) : Measured.unmeasured(whyIfNone);
    }

    /**
     * The nearest-rank percentile of the calls' durations, or why it was not taken.
     *
     * <p>Below {@code minCalls} it is refused rather than computed: at five calls a "95th
     * percentile" is just the slowest one, and an operator who reads that as typical goes looking
     * for a problem that is a cold start. Returning {@code 0} would be worse still — it says the
     * tool is instantaneous.
     */
    private static Measured<Long> percentile(List<McpCallRecord> calls, int percentile, int minCalls) {
        if (calls.size() < minCalls) {
            return Measured.unmeasured(calls.isEmpty()
                    ? "not called in this window"
                    : "%d call(s) in this window; %d are needed before a p%d means anything"
                            .formatted(calls.size(), minCalls, percentile));
        }
        List<Long> sorted = calls.stream().map(McpCallRecord::durationMs).sorted().toList();
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return Measured.of(sorted.get(Math.max(0, Math.min(rank, sorted.size() - 1))));
    }

    private List<McpCallRecord> within(Duration window) {
        Instant since = Instant.now().minus(window);
        return recorder.snapshot().stream()
                .filter(call -> !call.startedAt().isBefore(since))
                .toList();
    }

    private ObservedWindow observedWindow(Duration window) {
        List<McpCallRecord> held = recorder.snapshot();
        return new ObservedWindow(
                window.toMillis(),
                held.stream().map(McpCallRecord::startedAt).min(Comparator.naturalOrder()).orElse(null),
                held.size(),
                recorder.ringCapacity(),
                recorder.droppedFromRing(),
                recorder.auditPersisted());
    }
}
