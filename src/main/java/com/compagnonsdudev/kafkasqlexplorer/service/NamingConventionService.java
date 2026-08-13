// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.domain.FlowAudit;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicAudit;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class NamingConventionService {

    /**
     * Identifies a potential primary key field from a schema based on naming conventions.
     * Returns {@code null} when no id-like field exists: falling back to an arbitrary
     * field would make duplicate detection group by a non-key column (e.g. a status)
     * and report massive false positives.
     */
    public String findKeyField(Map<String, String> schema) {
        if (schema == null || schema.isEmpty()) {
            return null;
        }

        // Priority: exact "id", then case-insensitive "id"/"order_id", then any *_id / *Id suffix
        return schema.keySet().stream()
                .filter(k -> k.equals("id"))
                .findFirst()
                .orElseGet(() -> schema.keySet().stream()
                        .filter(k -> k.equalsIgnoreCase("id") || k.equalsIgnoreCase("order_id"))
                        .findFirst()
                        .orElseGet(() -> schema.keySet().stream()
                                .filter(k -> k.toLowerCase().endsWith("_id") || k.endsWith("Id"))
                                .findFirst()
                                .orElse(null)));
    }

    /**
     * Heuristically groups topics into logical business processes (Flows)
     * based on their naming convention (e.g., 'prefix.domain.step').
     */
    public List<FlowAudit> identifyFlows(List<TopicAudit> topicAudits) {
        // Group topics by their first two naming components (e.g., demo.orders)
        Map<String, List<TopicAudit>> grouped = topicAudits.stream()
            .filter(t -> t.name().contains("."))
            .collect(Collectors.groupingBy(t -> {
                String[] parts = t.name().split("\\.");
                if (parts.length >= 2) return parts[0] + "." + parts[1];
                return parts[0];
            }));

        List<FlowAudit> flows = new ArrayList<>();
        for (Map.Entry<String, List<TopicAudit>> entry : grouped.entrySet()) {
            if (entry.getValue().size() < 2) continue;

            List<TopicAudit> sortedTopics = entry.getValue().stream()
                .sorted(Comparator.comparing(TopicAudit::name))
                .toList();

            List<FlowAudit.StepInfo> steps = new ArrayList<>();
            long firstStepCount = sortedTopics.get(0).messageCount();

            for (int i = 0; i < sortedTopics.size(); i++) {
                TopicAudit topic = sortedTopics.get(i);
                double throughput = firstStepCount == 0 ? 100.0 : (double) topic.messageCount() / firstStepCount * 100.0;
                steps.add(new FlowAudit.StepInfo(topic.name(), topic.messageCount(), throughput, null)); // Latency is calculated later
            }

            // Score is a 0..1 ratio, NOT a percentage: how much of the first step's volume still
            // reaches the last one. It used to be stored as the raw 0..100 throughput percentage,
            // which the UI then multiplied by 100 again and rendered as "10000%".
            // A fan-out (last step larger than the first) is clamped to 1.0 rather than >100%.
            double lastThroughput = steps.get(steps.size() - 1).throughputPercentage();
            double healthScore = Math.min(1.0, Math.max(0.0, lastThroughput / 100.0));
            flows.add(new FlowAudit(entry.getKey(), steps, healthScore));
        }

        return flows;
    }
}
