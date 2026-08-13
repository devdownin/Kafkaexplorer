// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.AuditHistory;
import com.compagnonsdudev.kafkasqlexplorer.domain.AuditRunSummary;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Reads past audit reports back out of {@code internal.audit.history}.
 *
 * <p>{@link AuditService} has always written every report to that topic, and until now nothing
 * ever read it: the reports were write-only, and a restart lost the history. This service turns
 * them into a browsable list so the question that actually matters — "is the cluster improving?" —
 * can be answered.
 *
 * <p><strong>Bounded by design.</strong> The topic is append-only and a report holds one entry per
 * topic, so the scan reads at most {@code explorer.audit-history-max-records} records from the end
 * and says so in {@link AuditHistory#exhausted()}. Summaries are extracted from the JSON tree
 * rather than deserialized into {@code AuditReport}: it is far cheaper, and it survives records
 * written by older versions whose shape no longer matches the current record.
 */
@Service
public class AuditHistoryService {

    private static final Logger log = LoggerFactory.getLogger(AuditHistoryService.class);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(500);
    /** Consecutive empty polls tolerated before concluding the scan is done. */
    private static final int EMPTY_POLLS_BEFORE_STOP = 2;

    private final KafkaConfig kafkaConfig;
    private final ExplorerConfig explorerConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuditHistoryService(KafkaConfig kafkaConfig, ExplorerConfig explorerConfig) {
        this.kafkaConfig = kafkaConfig;
        this.explorerConfig = explorerConfig;
    }

    /**
     * History, newest first. Cached (same TTL as the other Kafka metadata caches) because the
     * Audit page asks for it on every mount and the answer only changes when a run finishes.
     */
    @Cacheable("auditHistory")
    public AuditHistory listHistory() {
        List<String> warnings = new ArrayList<>();
        ScanResult scan = scan(warnings);
        if (scan == null) return AuditHistory.empty(warnings);

        // One run can appear twice if a report was ever republished under the same id; the latest
        // record wins, which is why insertion order is by ascending offset here.
        Map<String, AuditRunSummary> byId = new LinkedHashMap<>();
        for (ConsumerRecord<byte[], byte[]> record : scan.records()) {
            AuditRunSummary summary = toSummary(record, warnings);
            if (summary != null) byId.put(summary.auditId(), summary);
        }

        List<AuditRunSummary> runs = new ArrayList<>(byId.values());
        runs.sort(Comparator.comparingLong(AuditRunSummary::timestamp).reversed());
        return new AuditHistory(runs, scan.recordsScanned(), scan.exhausted(), warnings);
    }

    /**
     * The stored report for one run, as JSON.
     *
     * <p>Deliberately returns the raw tree rather than an {@code AuditReport}: a record written
     * before graded severity carries {@code "UNHEALTHY"} and string issues, which today's record
     * cannot deserialize. Handing back what was stored loses nothing and lets the caller decide.
     *
     * @return the report node, or {@code null} when no record with that id is within scan range
     */
    public JsonNode findReport(String auditId) {
        List<String> warnings = new ArrayList<>();
        ScanResult scan = scan(warnings);
        if (scan == null) return null;
        // Newest wins: walk backwards.
        List<ConsumerRecord<byte[], byte[]>> records = scan.records();
        for (int i = records.size() - 1; i >= 0; i--) {
            ConsumerRecord<byte[], byte[]> record = records.get(i);
            if (auditId.equals(keyOf(record))) {
                try {
                    return objectMapper.readTree(record.value());
                } catch (Exception e) {
                    log.warn("Audit history record for {} is not readable JSON: {}", auditId, e.getMessage());
                    return null;
                }
            }
        }
        return null;
    }

    private record ScanResult(List<ConsumerRecord<byte[], byte[]>> records,
                              int recordsScanned,
                              boolean exhausted) {}

    /** @return null when the topic could not be read at all (never created, broker down) */
    private ScanResult scan(List<String> warnings) {
        String topic = explorerConfig.getAuditHistoryTopic();
        int maxRecords = Math.max(1, explorerConfig.getAuditHistoryMaxRecords());
        Properties props = new Properties();
        props.putAll(kafkaConfig.getKafkaProperties());
        // Fresh group, no commits: reading history must not move anyone's offsets.
        ExplorerConsumerGroups.configure(props, "audit-history");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());

        try (Consumer<byte[], byte[]> consumer = createConsumer(props)) {
            List<PartitionInfo> infos = consumer.partitionsFor(topic);
            if (infos == null || infos.isEmpty()) {
                warnings.add("Topic " + topic + " does not exist yet — no audit has been persisted.");
                return null;
            }
            List<TopicPartition> partitions = infos.stream()
                .map(info -> new TopicPartition(topic, info.partition()))
                .toList();
            consumer.assign(partitions);

            Map<TopicPartition, Long> beginning = consumer.beginningOffsets(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            int perPartition = Math.max(1, maxRecords / partitions.size());
            boolean exhausted = true;
            long remaining = 0;
            for (TopicPartition tp : partitions) {
                long from = beginning.getOrDefault(tp, 0L);
                long to = end.getOrDefault(tp, 0L);
                // Clamp to the partition's beginning: seeking below it resets to auto.offset.reset
                // and would silently return nothing on a retention-trimmed topic.
                long start = Math.max(from, to - perPartition);
                if (start > from) exhausted = false; // older records exist that we are not reading
                consumer.seek(tp, start);
                remaining += to - start;
            }
            if (remaining == 0) return new ScanResult(List.of(), 0, exhausted);

            List<ConsumerRecord<byte[], byte[]>> collected = new ArrayList<>();
            int emptyPolls = 0;
            // An empty poll does NOT mean exhausted — metadata may not be resolved yet — so a
            // couple of them are tolerated before giving up. Same lesson as the metrics restore.
            while (collected.size() < remaining && emptyPolls < EMPTY_POLLS_BEFORE_STOP) {
                ConsumerRecords<byte[], byte[]> polled = consumer.poll(POLL_TIMEOUT);
                if (polled.isEmpty()) {
                    emptyPolls++;
                    continue;
                }
                emptyPolls = 0;
                polled.forEach(collected::add);
            }
            collected.sort(Comparator.comparingLong(ConsumerRecord::timestamp));
            return new ScanResult(collected, collected.size(), exhausted);

        } catch (Exception e) {
            log.warn("Could not read audit history from {}: {}", topic, e.getMessage());
            warnings.add("Could not read " + topic + ": " + e.getMessage());
            return null;
        }
    }

    /** Test seam. */
    protected Consumer<byte[], byte[]> createConsumer(Properties props) {
        return new KafkaConsumer<>(props);
    }

    private static String keyOf(ConsumerRecord<byte[], byte[]> record) {
        return record.key() == null ? null : new String(record.key(), StandardCharsets.UTF_8);
    }

    /** @return null when the record is not a readable report — one bad record must not fail the list */
    private AuditRunSummary toSummary(ConsumerRecord<byte[], byte[]> record, List<String> warnings) {
        try {
            JsonNode root = objectMapper.readTree(record.value());
            String auditId = root.path("auditId").asText(keyOf(record));
            if (auditId == null || auditId.isBlank()) return null;

            JsonNode stats = root.path("globalStats");
            long timestamp = stats.path("timestamp").isNumber()
                ? stats.path("timestamp").asLong()
                : record.timestamp();

            // Pre-severity records only have unhealthyTopicsCount; the old scale did not separate
            // warnings from criticals, so the count lands under critical and `legacy` says why.
            boolean legacy = !root.has("criticalTopicsCount");
            int critical = legacy
                ? root.path("unhealthyTopicsCount").asInt(0)
                : root.path("criticalTopicsCount").asInt(0);
            int warning = legacy ? 0 : root.path("warningTopicsCount").asInt(0);

            return new AuditRunSummary(
                auditId,
                root.path("status").asText("UNKNOWN"),
                timestamp,
                stats.path("durationMs").isNumber() ? stats.path("durationMs").asLong() : null,
                root.path("totalTopics").asLong(0),
                root.path("totalMessages").asLong(0),
                critical,
                warning,
                stats.path("healthScore").isNumber() ? stats.path("healthScore").asDouble() : null,
                stats.path("options").path("topicPrefix").isTextual()
                    ? stats.path("options").path("topicPrefix").asText() : null,
                legacy
            );
        } catch (Exception e) {
            warnings.add("Skipped an unreadable history record at offset " + record.offset() + ".");
            return null;
        }
    }
}
