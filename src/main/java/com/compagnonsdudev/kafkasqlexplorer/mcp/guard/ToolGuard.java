// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.guard;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Warning;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The checks every tool runs before it touches a cluster, in KIP-1318's order and fail-closed.
 *
 * <p><b>Scope is checked before any I/O, and that placement is the point.</b> A scope check that
 * runs after the read has already disclosed what it was refusing — the topic existed, it had
 * records, it answered — so the refusal protects nothing. {@code StreamFlowMcpToolsTest} asserts
 * {@code verifyNoInteractions} on the service for exactly this, and the same rule holds for every
 * tool added later.
 *
 * <p><b>A ceiling clamps, it does not refuse.</b> A model asking for a hundred thousand rows is
 * not attacking anything; it is guessing at a limit nobody told it. Refusing costs a round trip
 * and teaches nothing, so the value is clamped and the clamp is <em>said</em> — the caller learns
 * the real ceiling from the warning and asks correctly next time. Silence would be worse than
 * either: a result cut to a thousand rows that claims to be the answer is the honesty failure this
 * whole module exists to prevent.
 */
public class ToolGuard {

    private final McpProperties properties;
    private final DlpScrubber dlp;

    /**
     * Identities under {@code -32047}. In memory and per-process: a kill switch has to take effect
     * now, and a quarantine that outlives the incident it answered is a trap of its own — the
     * console names every live override for the same reason.
     */
    private final Set<String> quarantined = ConcurrentHashMap.newKeySet();

    public ToolGuard(McpProperties properties, DlpScrubber dlp) {
        this.properties = properties;
        this.dlp = dlp;
    }

    /** Refuses topics outside {@code explorer.mcp.allowed-topic-prefixes}. Call before any read. */
    public void checkTopicScope(List<String> topics) {
        checkScope("topics", topics, properties.getAllowedTopicPrefixes());
    }

    /** Refuses a single topic, for the tools that take one. */
    public void checkTopicScope(String topic) {
        checkTopicScope(topic == null ? null : List.of(topic));
    }

    /** Refuses consumer groups outside {@code explorer.mcp.allowed-group-prefixes}. */
    public void checkGroupScope(List<String> groups) {
        checkScope("consumer groups", groups, properties.getAllowedGroupPrefixes());
    }

    private void checkScope(String kind, List<String> names, List<String> allowedPrefixes) {
        if (names == null || names.isEmpty() || McpProperties.unrestricted(allowedPrefixes)) {
            return;
        }
        List<String> offending = names.stream()
                .filter(name -> allowedPrefixes.stream().noneMatch(name::startsWith))
                .toList();
        if (!offending.isEmpty()) {
            throw new McpScopeViolationException(kind, offending, allowedPrefixes);
        }
    }

    /** Refuses a quarantined identity before anything else runs. */
    public void checkNotQuarantined(String identity) {
        if (identity != null && quarantined.contains(identity)) {
            throw new McpToolException(McpErrorCode.QUARANTINED, McpGuard.QUARANTINE,
                    "identity %s is quarantined; an operator lifted it from the MCP console"
                            .formatted(identity));
        }
    }

    public void quarantine(String identity) {
        quarantined.add(identity);
    }

    public void releaseQuarantine(String identity) {
        quarantined.remove(identity);
    }

    public Set<String> quarantinedIdentities() {
        return Set.copyOf(quarantined);
    }

    /** The budget the tool will actually use: the caller's, the default, or the ceiling. */
    public long clampBudget(Integer requestedMs) {
        long requested = requestedMs == null ? properties.getDefaultBudgetMs() : requestedMs;
        return Math.max(1L, Math.min(requested, properties.getHardMaxBudgetMs()));
    }

    public int clampRows(Integer requested) {
        return clamp(requested, properties.getHardMaxRows());
    }

    public int clampRecords(Integer requested) {
        return clamp(requested, properties.getHardMaxRecords());
    }

    public int clampTopics(Integer requested) {
        return clamp(requested, properties.getHardMaxTopics());
    }

    /**
     * A ceiling of its own rather than the topic one: a group costs an offsets read per partition
     * where a topic costs one listing, so the number that is generous for topics is expensive here.
     */
    public int clampGroups(Integer requested) {
        return clamp(requested, properties.getHardMaxGroups());
    }

    private static int clamp(Integer requested, int ceiling) {
        return requested == null ? ceiling : Math.max(1, Math.min(requested, ceiling));
    }

    /**
     * Adds one warning per value the ceilings cut down.
     *
     * <p>Collected into the caller's list rather than thrown, because these are notes on a result
     * that is otherwise valid — and because a tool that silently honours less than it was asked
     * hands a model a truncated answer with a complete answer's shape.
     */
    public List<Warning> clampWarnings(String what, Integer requested, long applied) {
        List<Warning> warnings = new ArrayList<>();
        if (requested != null && requested > applied) {
            warnings.add(Warning.info("BUDGET_CLAMPED",
                    "%s was clamped from %d to the server ceiling of %d".formatted(what, requested, applied)));
        }
        return warnings;
    }

    public DlpScrubber dlp() {
        return dlp;
    }

    public McpProperties properties() {
        return properties;
    }
}
