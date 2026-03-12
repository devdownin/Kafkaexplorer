package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final KafkaAdminService kafkaAdminService;
    private final FlinkSqlService flinkSqlService;
    private final SchemaInferenceService schemaInferenceService;
    private final DdlGeneratorService ddlGeneratorService;

    public AuditService(KafkaAdminService kafkaAdminService,
                        FlinkSqlService flinkSqlService,
                        SchemaInferenceService schemaInferenceService,
                        DdlGeneratorService ddlGeneratorService) {
        this.kafkaAdminService = kafkaAdminService;
        this.flinkSqlService = flinkSqlService;
        this.schemaInferenceService = schemaInferenceService;
        this.ddlGeneratorService = ddlGeneratorService;
    }

    public AuditReport generateAuditReport() throws ExecutionException, InterruptedException {
        List<String> topics = kafkaAdminService.listTopics();
        Map<String, Long> topicSizes = kafkaAdminService.getTopicsSize(topics);

        List<TopicAudit> topicAudits = new ArrayList<>();
        int unhealthyCount = 0;

        for (String topic : topics) {
            TopicAudit audit = auditTopic(topic, topicSizes.getOrDefault(topic, 0L));
            topicAudits.add(audit);
            if ("UNHEALTHY".equals(audit.healthStatus())) {
                unhealthyCount++;
            }
        }

        List<FlowAudit> flowAudits = identifyAndAuditFlows(topicAudits);

        long totalMessages = topicSizes.values().stream().mapToLong(Long::longValue).sum();

        return new AuditReport(
            topics.size(),
            totalMessages,
            unhealthyCount,
            topicAudits,
            flowAudits,
            Map.of("timestamp", System.currentTimeMillis())
        );
    }

    private TopicAudit auditTopic(String topicName, long approximateCount) {
        MessageFormat format = schemaInferenceService.detectFormat(topicName);
        Map<String, String> schema = schemaInferenceService.inferSchema(topicName, format);

        // Register table if not exists
        if (!flinkSqlService.listTables().contains(topicName)) {
            String ddl = ddlGeneratorService.generateDdl(topicName, schema, format);
            try {
                flinkSqlService.executeSql(new QueryRequest(ddl, null, null, null, null));
            } catch (Exception e) {
                log.warn("Could not register table for audit: {}", topicName);
            }
        }

        // Execute automated query to get exact count
        long exactCount = approximateCount;
        QueryResult countResult = flinkSqlService.executeSql(new QueryRequest("SELECT COUNT(*) FROM \"" + topicName + "\"", null, 1, 5000L, null));
        if (countResult.error() == null && !countResult.rows().isEmpty()) {
            Object val = countResult.rows().get(0).get("EXPR$0");
            if (val instanceof Long) exactCount = (Long) val;
            else if (val instanceof Integer) exactCount = ((Integer) val).longValue();
        }

        // Poison message detection (simple heuristic)
        int poisonCount = 0;
        List<String> issues = new ArrayList<>();
        List<String> samples = kafkaAdminService.getSampleMessages(topicName, 10);
        for (String sample : samples) {
            if (format == MessageFormat.JSON && !(sample.trim().startsWith("{") || sample.trim().startsWith("["))) {
                poisonCount++;
            } else if (format == MessageFormat.XML && !sample.trim().startsWith("<")) {
                poisonCount++;
            }
        }

        if (poisonCount > 0) issues.add("Detected " + poisonCount + " malformed messages in sample.");
        if (approximateCount > 0 && exactCount == 0) issues.add("Flink SQL returned 0 rows despite Kafka having messages.");

        String status = issues.isEmpty() ? "HEALTHY" : "UNHEALTHY";

        return new TopicAudit(topicName, exactCount, format, poisonCount, status, issues);
    }

    private List<FlowAudit> identifyAndAuditFlows(List<TopicAudit> topicAudits) {
        List<FlowAudit> flows = new ArrayList<>();

        // Group by prefix (e.g., demo.orders, demo.sc)
        Map<String, List<TopicAudit>> grouped = topicAudits.stream()
            .filter(t -> t.name().contains("."))
            .collect(Collectors.groupingBy(t -> {
                String[] parts = t.name().split("\\.");
                if (parts.length >= 2) return parts[0] + "." + parts[1];
                return parts[0];
            }));

        for (Map.Entry<String, List<TopicAudit>> entry : grouped.entrySet()) {
            if (entry.getValue().size() < 2) continue;

            List<TopicAudit> sortedTopics = entry.getValue().stream()
                .sorted(Comparator.comparing(TopicAudit::name))
                .toList();

            List<FlowAudit.StepInfo> steps = new ArrayList<>();
            long firstStepCount = sortedTopics.get(0).messageCount();

            for (TopicAudit topic : sortedTopics) {
                double throughput = firstStepCount == 0 ? 100.0 : (double) topic.messageCount() / firstStepCount * 100.0;
                steps.add(new FlowAudit.StepInfo(topic.name(), topic.messageCount(), throughput));
            }

            double healthScore = steps.get(steps.size() - 1).throughputPercentage();
            flows.add(new FlowAudit(entry.getKey(), steps, healthScore));
        }

        return flows;
    }
}
