package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.domain.FlowAudit;
import com.yourcompany.kafkasqlexplorer.domain.TopicAudit;
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
     */
    public String findKeyField(Map<String, String> schema) {
        if (schema == null || schema.isEmpty()) {
            return null;
        }

        // Priority: exact "id", then case-insensitive "id", then case-insensitive "order_id"
        return schema.keySet().stream()
                .filter(k -> k.equals("id"))
                .findFirst()
                .orElseGet(() -> schema.keySet().stream()
                        .filter(k -> k.equalsIgnoreCase("id") || k.equalsIgnoreCase("order_id"))
                        .findFirst()
                        .orElseGet(() -> schema.keySet().stream().findFirst().orElse(null)));
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

            double healthScore = steps.get(steps.size() - 1).throughputPercentage();
            flows.add(new FlowAudit(entry.getKey(), steps, healthScore));
        }

        return flows;
    }
}
