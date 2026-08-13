// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exposes KRaft controller-quorum health (KIP-595) as Prometheus gauges on
 * {@code /actuator/prometheus}, so a lagging voter or a leadership change can be alerted on
 * instead of being discovered on the Cluster page:
 *
 * <ul>
 *   <li>{@code kafka_quorum_leader_id} / {@code kafka_quorum_leader_epoch} — current metadata
 *       log leader and its epoch (an epoch counter increase means a controller failover)</li>
 *   <li>{@code kafka_quorum_high_watermark} — committed offset of the metadata log</li>
 *   <li>{@code kafka_quorum_replica_lag{replicaId,role}} — per voter/observer distance between
 *       the quorum high watermark and the replica's log end offset</li>
 * </ul>
 *
 * The quorum is polled on a dedicated daemon thread every {@value #REFRESH_PERIOD_SECONDS}s
 * ({@link KafkaAdminService#getQuorumSnapshot()} is a single cheap admin call). On brokers
 * without a metadata quorum (Zookeeper mode) the snapshot is null and no gauge is ever
 * registered; once registered, a gauge keeps its last value if a refresh fails transiently.
 */
@Component
public class KraftQuorumMetrics {

    private static final Logger log = LoggerFactory.getLogger(KraftQuorumMetrics.class);
    static final long REFRESH_PERIOD_SECONDS = 30;

    private final KafkaAdminService kafkaAdminService;
    private final MeterRegistry meterRegistry;
    /** One holder per gauge identity (name + tags); Micrometer reads it on scrape. */
    private final Map<String, AtomicLong> gaugeValues = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "kraft-quorum-metrics");
        t.setDaemon(true);
        return t;
    });

    public KraftQuorumMetrics(KafkaAdminService kafkaAdminService, MeterRegistry meterRegistry) {
        this.kafkaAdminService = kafkaAdminService;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void start() {
        scheduler.scheduleAtFixedRate(this::refresh, 10, REFRESH_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }

    /** One poll of the quorum → gauge updates. Package-private for tests. */
    void refresh() {
        try {
            Map<String, Object> quorum = kafkaAdminService.getQuorumSnapshot();
            if (quorum == null) {
                return; // Zookeeper mode or transient failure — keep last known values
            }
            setGauge("kafka.quorum.leader.id", Tags.empty(), ((Number) quorum.get("leaderId")).longValue());
            setGauge("kafka.quorum.leader.epoch", Tags.empty(), ((Number) quorum.get("leaderEpoch")).longValue());
            setGauge("kafka.quorum.high.watermark", Tags.empty(), ((Number) quorum.get("highWatermark")).longValue());
            registerReplicaLags(quorum, "voters", "voter");
            registerReplicaLags(quorum, "observers", "observer");
        } catch (Exception e) {
            log.debug("KRaft quorum metrics refresh failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void registerReplicaLags(Map<String, Object> quorum, String key, String role) {
        List<Map<String, Object>> replicas = (List<Map<String, Object>>) quorum.get(key);
        if (replicas == null) {
            return;
        }
        for (Map<String, Object> replica : replicas) {
            Tags tags = Tags.of(
                    "replicaId", String.valueOf(replica.get("replicaId")),
                    "role", role);
            setGauge("kafka.quorum.replica.lag", tags, ((Number) replica.get("lag")).longValue());
        }
    }

    private void setGauge(String name, Tags tags, long value) {
        gaugeValues.computeIfAbsent(name + tags, k -> {
            AtomicLong holder = new AtomicLong();
            Gauge.builder(name, holder, AtomicLong::doubleValue)
                    .tags(tags)
                    .register(meterRegistry);
            return holder;
        }).set(value);
    }
}
