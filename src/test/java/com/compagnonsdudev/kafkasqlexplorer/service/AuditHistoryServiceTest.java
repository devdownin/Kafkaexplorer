// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.AuditHistory;
import com.compagnonsdudev.kafkasqlexplorer.domain.AuditRunSummary;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditHistoryServiceTest {

    private static final String TOPIC = "internal.audit.history";
    private static final TopicPartition TP = new TopicPartition(TOPIC, 0);

    private MockConsumer<byte[], byte[]> consumer;
    private ExplorerConfig explorerConfig;
    private AuditHistoryService service;

    @BeforeEach
    void setUp() {
        KafkaConfig kafkaConfig = mock(KafkaConfig.class);
        when(kafkaConfig.getKafkaProperties()).thenReturn(Map.of("bootstrap.servers", "localhost:9092"));
        explorerConfig = new ExplorerConfig();
        consumer = new MockConsumer<>("earliest");
        consumer.updatePartitions(TOPIC, List.of(
                new PartitionInfo(TOPIC, 0, null, new org.apache.kafka.common.Node[0], new org.apache.kafka.common.Node[0])));

        service = new AuditHistoryService(kafkaConfig, explorerConfig) {
            @Override
            protected Consumer<byte[], byte[]> createConsumer(Properties props) {
                return consumer;
            }
        };
    }

    /** Seeds the mock topic with the given report JSON documents, one record each. */
    private void seed(String... reports) {
        // MockConsumer refuses addRecord for an unassigned partition, so assign here; the service
        // assigns the same partition again, which is a no-op for the queued records.
        consumer.assign(List.of(TP));
        consumer.updateBeginningOffsets(Map.of(TP, 0L));
        Map<TopicPartition, Long> end = new HashMap<>();
        end.put(TP, (long) reports.length);
        consumer.updateEndOffsets(end);
        for (int i = 0; i < reports.length; i++) {
            String auditId = "run-" + i;
            consumer.addRecord(new ConsumerRecord<>(TOPIC, 0, i, 1_000L + i,
                    org.apache.kafka.common.record.TimestampType.CREATE_TIME, -1, -1,
                    auditId.getBytes(StandardCharsets.UTF_8),
                    reports[i].getBytes(StandardCharsets.UTF_8),
                    new org.apache.kafka.common.header.internals.RecordHeaders(), java.util.Optional.empty()));
        }
    }

    private static String report(String auditId, String status, long timestamp,
                                 int critical, int warning, double healthScore) {
        return """
            {"auditId":"%s","status":"%s","totalTopics":12,"totalMessages":340,
             "criticalTopicsCount":%d,"warningTopicsCount":%d,
             "topicAudits":[],"flowAudits":[],
             "globalStats":{"timestamp":%d,"durationMs":4200,"healthScore":%s,
                            "options":{"topicPrefix":"orders."}}}
            """.formatted(auditId, status, critical, warning, timestamp, healthScore);
    }

    @Test
    void listsPastRunsNewestFirst() {
        seed(report("run-0", "COMPLETED", 3_000L, 1, 2, 0.8),
             report("run-1", "CANCELLED", 5_000L, 0, 1, 0.95),
             report("run-2", "COMPLETED", 4_000L, 3, 0, 0.5));

        AuditHistory history = service.listHistory();

        assertEquals(List.of("run-1", "run-2", "run-0"),
                history.runs().stream().map(AuditRunSummary::auditId).toList(),
                "newest first, by the report's own timestamp");
        assertEquals(3, history.recordsScanned());
        assertTrue(history.exhausted(), "the whole topic fits within the cap");
        assertTrue(history.warnings().isEmpty(), "nothing degraded: " + history.warnings());
    }

    @Test
    void carriesTheFiguresNeededToCompareRuns() {
        seed(report("run-0", "COMPLETED", 3_000L, 1, 2, 0.8));

        AuditRunSummary summary = service.listHistory().runs().get(0);

        assertEquals("COMPLETED", summary.status());
        assertEquals(3_000L, summary.timestamp());
        assertEquals(4_200L, summary.durationMs());
        assertEquals(12, summary.totalTopics());
        assertEquals(340, summary.totalMessages());
        assertEquals(1, summary.criticalTopicsCount());
        assertEquals(2, summary.warningTopicsCount());
        assertEquals(0.8, summary.healthScore(), 1e-9);
        assertEquals("orders.", summary.topicPrefix());
        assertFalse(summary.legacy());
    }

    @Test
    void readsRecordsWrittenBeforeGradedSeverityWithoutFailing() {
        // Shape written by the previous version: UNHEALTHY status, unhealthyTopicsCount, string
        // issues. Deserializing that into today's AuditReport is impossible — the list must still
        // show the run rather than hide it or blow up.
        seed("""
            {"auditId":"legacy-1","status":"COMPLETED","totalTopics":5,"totalMessages":100,
             "unhealthyTopicsCount":2,
             "topicAudits":[{"name":"a","messageCount":1,"format":"JSON","poisonMessageCount":0,
                             "duplicateCount":0,"healthStatus":"UNHEALTHY","issues":["something"]}],
             "flowAudits":[],"globalStats":{"timestamp":2000}}
            """);

        AuditRunSummary summary = service.listHistory().runs().get(0);

        assertEquals("legacy-1", summary.auditId());
        assertTrue(summary.legacy(), "the record predates graded severity and must say so");
        assertEquals(2, summary.criticalTopicsCount(), "the old unhealthy count lands here");
        assertEquals(0, summary.warningTopicsCount(), "the old scale recorded no warnings");
        assertNull(summary.healthScore(), "not recorded back then — null, not a made-up value");
    }

    @Test
    void oneUnreadableRecordDoesNotSinkTheList() {
        seed("not json at all", report("run-1", "COMPLETED", 4_000L, 0, 0, 1.0));

        AuditHistory history = service.listHistory();

        assertEquals(1, history.runs().size());
        assertEquals("run-1", history.runs().get(0).auditId());
        assertTrue(history.warnings().stream().anyMatch(w -> w.contains("unreadable")),
                "the skipped record must be reported: " + history.warnings());
    }

    @Test
    void saysWhenOlderRunsExistBeyondTheScan() {
        explorerConfig.setAuditHistoryMaxRecords(2);
        seed(report("run-0", "COMPLETED", 1_000L, 0, 0, 1.0),
             report("run-1", "COMPLETED", 2_000L, 0, 0, 1.0),
             report("run-2", "COMPLETED", 3_000L, 0, 0, 1.0),
             report("run-3", "COMPLETED", 4_000L, 0, 0, 1.0));

        AuditHistory history = service.listHistory();

        assertFalse(history.exhausted(),
                "older runs are stored but were not read — the response must not imply otherwise");
        assertTrue(history.runs().size() <= 2, "the cap is respected, got " + history.runs().size());
    }

    @Test
    void reportsAMissingTopicInsteadOfPretendingThereIsNoHistory() {
        // partitionsFor returns null for an unknown topic; a bare empty list would read as
        // "no audit ever ran" instead of "the topic does not exist".
        MockConsumer<byte[], byte[]> emptyConsumer = new MockConsumer<>("earliest");
        AuditHistoryService noTopic = new AuditHistoryService(mock(KafkaConfig.class), explorerConfig) {
            @Override
            protected Consumer<byte[], byte[]> createConsumer(Properties props) {
                return emptyConsumer;
            }
        };

        AuditHistory history = noTopic.listHistory();

        assertTrue(history.runs().isEmpty());
        assertTrue(history.warnings().stream().anyMatch(w -> w.contains("does not exist")),
                "the reason must be stated: " + history.warnings());
    }

    @Test
    void findsTheStoredReportForOneRun() {
        seed(report("run-0", "COMPLETED", 3_000L, 1, 2, 0.8));

        JsonNode found = service.findReport("run-0");

        assertNotNull(found);
        assertEquals("COMPLETED", found.path("status").asText());
        assertEquals(1, found.path("criticalTopicsCount").asInt());
        assertNull(service.findReport("never-stored"));
    }
}
