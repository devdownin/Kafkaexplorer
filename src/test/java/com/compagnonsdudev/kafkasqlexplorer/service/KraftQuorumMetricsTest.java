package com.compagnonsdudev.kafkasqlexplorer.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KraftQuorumMetricsTest {

    private KafkaAdminService kafkaAdminService;
    private SimpleMeterRegistry registry;
    private KraftQuorumMetrics metrics;

    @BeforeEach
    void setUp() {
        kafkaAdminService = mock(KafkaAdminService.class);
        registry = new SimpleMeterRegistry();
        metrics = new KraftQuorumMetrics(kafkaAdminService, registry);
    }

    private static Map<String, Object> quorumSnapshot(long highWatermark, long voterLag, long observerLag) {
        Map<String, Object> quorum = new LinkedHashMap<>();
        quorum.put("leaderId", 1);
        quorum.put("leaderEpoch", 5L);
        quorum.put("highWatermark", highWatermark);
        quorum.put("voters", List.of(new LinkedHashMap<String, Object>(Map.of(
                "replicaId", 1, "isLeader", true, "logEndOffset", highWatermark, "lag", voterLag))));
        quorum.put("observers", List.of(new LinkedHashMap<String, Object>(Map.of(
                "replicaId", 1, "isLeader", false, "logEndOffset", highWatermark - observerLag, "lag", observerLag))));
        return quorum;
    }

    @Test
    void refreshRegistersLeaderAndLagGauges() {
        when(kafkaAdminService.getQuorumSnapshot()).thenReturn(quorumSnapshot(100L, 0L, 3L));

        metrics.refresh();

        assertEquals(1.0, registry.get("kafka.quorum.leader.id").gauge().value());
        assertEquals(5.0, registry.get("kafka.quorum.leader.epoch").gauge().value());
        assertEquals(100.0, registry.get("kafka.quorum.high.watermark").gauge().value());
        assertEquals(0.0, registry.get("kafka.quorum.replica.lag")
                .tags("role", "voter", "replicaId", "1").gauge().value());
        assertEquals(3.0, registry.get("kafka.quorum.replica.lag")
                .tags("role", "observer", "replicaId", "1").gauge().value());
    }

    @Test
    void refreshUpdatesExistingGaugesInPlace() {
        when(kafkaAdminService.getQuorumSnapshot())
                .thenReturn(quorumSnapshot(100L, 0L, 3L))
                .thenReturn(quorumSnapshot(250L, 7L, 0L));

        metrics.refresh();
        metrics.refresh();

        assertEquals(250.0, registry.get("kafka.quorum.high.watermark").gauge().value());
        assertEquals(7.0, registry.get("kafka.quorum.replica.lag")
                .tags("role", "voter", "replicaId", "1").gauge().value());
        assertEquals(1, registry.get("kafka.quorum.high.watermark").gauges().size(),
                "second refresh must reuse the gauge, not register a duplicate");
    }

    @Test
    void nullSnapshotRegistersNothing() {
        when(kafkaAdminService.getQuorumSnapshot()).thenReturn(null);

        metrics.refresh();

        assertTrue(registry.getMeters().isEmpty(), "Zookeeper-mode clusters must not expose quorum gauges");
    }
}
