// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.kafkasqlexplorer.domain.AuditDiff;
import com.yourcompany.kafkasqlexplorer.domain.AuditReport;
import com.yourcompany.kafkasqlexplorer.domain.HealthStatus;
import com.yourcompany.kafkasqlexplorer.domain.TopicDiff;
import com.yourcompany.kafkasqlexplorer.domain.TopicDiff.ChangeKind;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares two audit runs topic by topic.
 *
 * <p>The history screen answers "is the cluster improving?" with a health-score delta; this
 * answers the follow-up an operator actually acts on — <em>which</em> topics moved.
 *
 * <p>Reports are resolved from the in-memory runs first, then from the history topic, and are
 * diffed as JSON trees in both cases. That keeps one code path and sidesteps deserializing
 * records written by older versions.
 */
@Service
public class AuditDiffService {

    private final AuditService auditService;
    private final AuditHistoryService auditHistoryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuditDiffService(AuditService auditService, AuditHistoryService auditHistoryService) {
        this.auditService = auditService;
        this.auditHistoryService = auditHistoryService;
    }

    /** Why a comparison could not be produced. */
    public enum DiffError { FROM_NOT_FOUND, TO_NOT_FOUND, LEGACY_SHAPE }

    /** Either a diff or the reason there is none — never a half-empty diff passing for a result. */
    public record DiffResult(AuditDiff diff, DiffError error, String message) {
        static DiffResult of(AuditDiff diff) {
            return new DiffResult(diff, null, null);
        }
        static DiffResult failed(DiffError error, String message) {
            return new DiffResult(null, error, message);
        }
    }

    public DiffResult compare(String fromAuditId, String toAuditId) {
        JsonNode fromNode = resolve(fromAuditId);
        if (fromNode == null) {
            return DiffResult.failed(DiffError.FROM_NOT_FOUND,
                "Baseline run " + fromAuditId + " is not in memory nor within the history scan window.");
        }
        JsonNode toNode = resolve(toAuditId);
        if (toNode == null) {
            return DiffResult.failed(DiffError.TO_NOT_FOUND,
                "Run " + toAuditId + " is not in memory nor within the history scan window.");
        }

        Snapshot from = snapshot(fromAuditId, fromNode);
        Snapshot to = snapshot(toAuditId, toNode);
        if (from.legacy() || to.legacy()) {
            // The retired binary scale never separated warnings from criticals, so "improved" and
            // "regressed" cannot be decided against it. Refusing beats inventing a direction.
            return DiffResult.failed(DiffError.LEGACY_SHAPE,
                "One of the runs predates graded severity — its HEALTHY/UNHEALTHY scale cannot be "
                + "compared with the current one without guessing.");
        }

        List<TopicDiff> topics = new ArrayList<>();
        int unchanged = 0;
        Set<String> allNames = new TreeSet<>();
        allNames.addAll(from.topics().keySet());
        allNames.addAll(to.topics().keySet());

        for (String name : allNames) {
            TopicSnapshot before = from.topics().get(name);
            TopicSnapshot after = to.topics().get(name);
            TopicDiff diff = diffTopic(name, before, after);
            if (diff == null) unchanged++;
            else topics.add(diff);
        }

        // Worst first: a regression is what the reader is looking for.
        topics.sort(Comparator.comparingInt((TopicDiff d) -> switch (d.change()) {
            case REGRESSED -> 0;
            case ADDED -> 1;
            case ISSUES_CHANGED -> 2;
            case REMOVED -> 3;
            case IMPROVED -> 4;
        }).thenComparing(TopicDiff::name));

        Double scoreDelta = from.healthScore() != null && to.healthScore() != null
            ? to.healthScore() - from.healthScore()
            : null;

        List<String> warnings = new ArrayList<>();
        if (scoreDelta == null) {
            warnings.add("At least one run did not record a health score — no overall delta.");
        }
        if (!from.optionsSummary().equals(to.optionsSummary())) {
            // Comparing a schema-only run against a full one would read as mass improvement.
            warnings.add("The two runs did not enable the same checks (" + from.optionsSummary()
                + " vs " + to.optionsSummary() + ") — differences may come from the options, not the cluster.");
        }

        return DiffResult.of(new AuditDiff(
            fromAuditId, toAuditId, from.timestamp(), to.timestamp(),
            count(topics, ChangeKind.ADDED),
            count(topics, ChangeKind.REMOVED),
            count(topics, ChangeKind.REGRESSED),
            count(topics, ChangeKind.IMPROVED),
            count(topics, ChangeKind.ISSUES_CHANGED),
            unchanged,
            scoreDelta,
            topics,
            warnings));
    }

    private static int count(List<TopicDiff> topics, ChangeKind kind) {
        return (int) topics.stream().filter(t -> t.change() == kind).count();
    }

    /** @return null when nothing worth reporting changed for this topic */
    private TopicDiff diffTopic(String name, TopicSnapshot before, TopicSnapshot after) {
        if (before == null) {
            return new TopicDiff(name, ChangeKind.ADDED, null, after.status(),
                0, after.messageCount(), 0, after.duplicateCount(), 0, after.poisonCount(),
                List.copyOf(after.issues()), List.of());
        }
        if (after == null) {
            return new TopicDiff(name, ChangeKind.REMOVED, before.status(), null,
                before.messageCount(), 0, before.duplicateCount(), 0, before.poisonCount(), 0,
                List.of(), List.copyOf(before.issues()));
        }

        List<String> newIssues = difference(after.issues(), before.issues());
        List<String> resolvedIssues = difference(before.issues(), after.issues());
        int severityDelta = after.status().compareTo(before.status());
        if (severityDelta == 0 && newIssues.isEmpty() && resolvedIssues.isEmpty()) {
            return null; // health identical — volume alone is not a finding
        }

        ChangeKind change = severityDelta > 0 ? ChangeKind.REGRESSED
            : severityDelta < 0 ? ChangeKind.IMPROVED
            : ChangeKind.ISSUES_CHANGED;

        return new TopicDiff(name, change, before.status(), after.status(),
            before.messageCount(), after.messageCount(),
            before.duplicateCount(), after.duplicateCount(),
            before.poisonCount(), after.poisonCount(),
            newIssues, resolvedIssues);
    }

    private static List<String> difference(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.removeAll(b);
        return List.copyOf(out);
    }

    /** In-memory runs win: they are the freshest and avoid a Kafka read. */
    private JsonNode resolve(String auditId) {
        AuditReport inMemory = auditService.getAuditReport(auditId);
        if (inMemory != null) return objectMapper.valueToTree(inMemory);
        return auditHistoryService.findReport(auditId);
    }

    private record TopicSnapshot(String name, HealthStatus status, long messageCount,
                                 long duplicateCount, int poisonCount, Set<String> issues) {}

    private record Snapshot(String auditId, long timestamp, Double healthScore, boolean legacy,
                            String optionsSummary, Map<String, TopicSnapshot> topics) {}

    private Snapshot snapshot(String auditId, JsonNode root) {
        JsonNode stats = root.path("globalStats");
        boolean legacy = !root.has("criticalTopicsCount");

        Map<String, TopicSnapshot> topics = new LinkedHashMap<>();
        for (JsonNode topic : root.path("topicAudits")) {
            String name = topic.path("name").asText(null);
            if (name == null) continue;
            HealthStatus status = parseStatus(topic.path("healthStatus").asText(""));
            Set<String> issues = new LinkedHashSet<>();
            for (JsonNode issue : topic.path("issues")) {
                // Current shape is {message, severity}; the retired one was a bare string.
                issues.add(issue.isObject() ? issue.path("message").asText("") : issue.asText(""));
            }
            topics.put(name, new TopicSnapshot(name, status,
                topic.path("messageCount").asLong(0),
                topic.path("duplicateCount").asLong(0),
                topic.path("poisonMessageCount").asInt(0),
                issues));
        }

        return new Snapshot(auditId,
            stats.path("timestamp").asLong(0),
            stats.path("healthScore").isNumber() ? stats.path("healthScore").asDouble() : null,
            legacy,
            describeOptions(stats.path("options")),
            topics);
    }

    private static HealthStatus parseStatus(String raw) {
        try {
            return HealthStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return HealthStatus.HEALTHY; // includes the retired UNHEALTHY; legacy runs are refused above
        }
    }

    /** Compact "schema+count+poison" style key so two runs' scopes can be compared at a glance. */
    private static String describeOptions(JsonNode options) {
        if (options.isMissingNode() || !options.isObject()) return "unknown";
        List<String> enabled = new ArrayList<>();
        options.fields().forEachRemaining(entry -> {
            if (entry.getValue().isBoolean() && entry.getValue().asBoolean()) {
                enabled.add(entry.getKey().replaceFirst("^check", "").toLowerCase());
            }
        });
        return enabled.isEmpty() ? "none" : String.join("+", enabled);
    }
}
