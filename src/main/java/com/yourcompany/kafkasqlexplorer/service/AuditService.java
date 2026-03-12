package com.yourcompany.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.kafkasqlexplorer.domain.*;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final String AUDIT_HISTORY_TOPIC = "internal.audit.history";

    private final KafkaAdminService kafkaAdminService;
    private final FlinkSqlService flinkSqlService;
    private final SchemaInferenceService schemaInferenceService;
    private final DdlGeneratorService ddlGeneratorService;
    private final com.yourcompany.kafkasqlexplorer.config.KafkaConfig kafkaConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, AuditReport> auditRuns = new ConcurrentHashMap<>();
    private String lastAuditId = null;

    public AuditService(KafkaAdminService kafkaAdminService,
                        FlinkSqlService flinkSqlService,
                        SchemaInferenceService schemaInferenceService,
                        DdlGeneratorService ddlGeneratorService,
                        com.yourcompany.kafkasqlexplorer.config.KafkaConfig kafkaConfig) {
        this.kafkaAdminService = kafkaAdminService;
        this.flinkSqlService = flinkSqlService;
        this.schemaInferenceService = schemaInferenceService;
        this.ddlGeneratorService = ddlGeneratorService;
        this.kafkaConfig = kafkaConfig;
    }

    public String startAudit() {
        String auditId = UUID.randomUUID().toString();
        lastAuditId = auditId;
        AuditReport initialReport = new AuditReport(auditId, "RUNNING", 0, 0, 0, Collections.emptyList(), Collections.emptyList(), Collections.emptyMap());
        auditRuns.put(auditId, initialReport);

        runAuditAsync(auditId);
        return auditId;
    }

    public AuditReport getAuditReport(String auditId) {
        return auditRuns.get(auditId);
    }

    public AuditReport getLastAuditReport() {
        return lastAuditId != null ? auditRuns.get(lastAuditId) : null;
    }

    @Async
    protected void runAuditAsync(String auditId) {
        try {
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

            AuditReport finalReport = new AuditReport(
                auditId,
                "COMPLETED",
                topics.size(),
                totalMessages,
                unhealthyCount,
                topicAudits,
                flowAudits,
                Map.of("timestamp", System.currentTimeMillis())
            );

            auditRuns.put(auditId, finalReport);
            persistAuditHistory(finalReport);

        } catch (Exception e) {
            log.error("Audit failed for id {}", auditId, e);
            auditRuns.put(auditId, new AuditReport(auditId, "FAILED", 0, 0, 0, Collections.emptyList(), Collections.emptyList(), Map.of("error", e.getMessage())));
        }
    }

    private TopicAudit auditTopic(String topicName, long approximateCount) {
        MessageFormat format = schemaInferenceService.detectFormat(topicName);
        Map<String, String> schema = schemaInferenceService.inferSchema(topicName, format);

        registerTableIfNeeded(topicName, schema, format);

        // Exact count
        long exactCount = getExactCount(topicName, approximateCount);

        // Duplicate detection
        long duplicates = detectDuplicates(topicName, schema);

        // Poison message detection
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
        if (duplicates > 0) issues.add("Detected " + duplicates + " potential duplicate records.");

        String status = issues.isEmpty() ? "HEALTHY" : "UNHEALTHY";

        return new TopicAudit(topicName, exactCount, format, poisonCount, duplicates, status, issues);
    }

    private void registerTableIfNeeded(String topicName, Map<String, String> schema, MessageFormat format) {
        if (!flinkSqlService.listTables().contains(topicName)) {
            String ddl = ddlGeneratorService.generateDdl(topicName, schema, format);
            try {
                flinkSqlService.executeSql(new QueryRequest(ddl, null, null, null, null));
            } catch (Exception e) {
                log.warn("Could not register table for audit: {}", topicName);
            }
        }
    }

    private long getExactCount(String topicName, long approximateCount) {
        QueryResult countResult = flinkSqlService.executeSql(new QueryRequest("SELECT COUNT(*) FROM \"" + topicName + "\"", null, 1, 5000L, null));
        if (countResult.error() == null && !countResult.rows().isEmpty()) {
            Object val = countResult.rows().get(0).get("EXPR$0");
            if (val instanceof Long) return (Long) val;
            if (val instanceof Integer) return ((Integer) val).longValue();
        }
        return approximateCount;
    }

    private long detectDuplicates(String topicName, Map<String, String> schema) {
        String keyField = schema.keySet().stream()
                .filter(k -> k.equalsIgnoreCase("id") || k.equalsIgnoreCase("order_id"))
                .findFirst().orElse(null);

        if (keyField == null) return 0;

        String sql = "SELECT COUNT(*) FROM (SELECT \"" + keyField + "\" FROM \"" + topicName + "\" GROUP BY \"" + keyField + "\" HAVING COUNT(*) > 1)";
        QueryResult dupResult = flinkSqlService.executeSql(new QueryRequest(sql, null, 1, 5000L, null));
        if (dupResult.error() == null && !dupResult.rows().isEmpty()) {
            Object val = dupResult.rows().get(0).get("EXPR$0");
            if (val instanceof Long) return (Long) val;
            if (val instanceof Integer) return ((Integer) val).longValue();
        }
        return 0;
    }

    private List<FlowAudit> identifyAndAuditFlows(List<TopicAudit> topicAudits) {
        List<FlowAudit> flows = new ArrayList<>();

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

            for (int i = 0; i < sortedTopics.size(); i++) {
                TopicAudit topic = sortedTopics.get(i);
                double throughput = firstStepCount == 0 ? 100.0 : (double) topic.messageCount() / firstStepCount * 100.0;

                Long latency = null;
                if (i > 0) {
                    latency = calculateLatency(sortedTopics.get(i-1).name(), topic.name());
                }

                steps.add(new FlowAudit.StepInfo(topic.name(), topic.messageCount(), throughput, latency));
            }

            double healthScore = steps.get(steps.size() - 1).throughputPercentage();
            flows.add(new FlowAudit(entry.getKey(), steps, healthScore));
        }

        return flows;
    }

    private Long calculateLatency(String sourceTopic, String targetTopic) {
        // Simple heuristic for latency: average of (target.event_time - source.event_time) joined by ID
        // Note: This requires both topics to have a common ID field.
        // For demo purposes, we look for 'id' or 'order_id'
        String sql = "SELECT AVG(CAST(t2.event_time AS LONG) - CAST(t1.event_time AS LONG)) " +
                     "FROM \"" + sourceTopic + "\" t1 JOIN \"" + targetTopic + "\" t2 ON t1.id = t2.id " +
                     "WHERE t2.event_time > t1.event_time";

        // We need to check if 'id' exists in both. This is complex to do perfectly here, so we wrap in try/catch or rely on QueryResult.error()
        QueryResult result = flinkSqlService.executeSql(new QueryRequest(sql, null, 1, 5000L, null));
        if (result.error() == null && !result.rows().isEmpty()) {
            Object val = result.rows().get(0).values().iterator().next();
            if (val instanceof Number) return ((Number) val).longValue();
        }
        return null;
    }

    private void persistAuditHistory(AuditReport report) {
        Properties props = new Properties();
        props.putAll(kafkaConfig.getKafkaProperties());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            String value = objectMapper.writeValueAsString(report);
            producer.send(new ProducerRecord<>(AUDIT_HISTORY_TOPIC, report.auditId(), value)).get();
            log.info("Persisted audit {} to history topic", report.auditId());
        } catch (Exception e) {
            log.warn("Failed to persist audit history: {}", e.getMessage());
        }
    }
}
