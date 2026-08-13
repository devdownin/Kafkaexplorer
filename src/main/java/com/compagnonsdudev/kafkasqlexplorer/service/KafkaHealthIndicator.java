// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Broker reachability, contributed to the <em>readiness</em> group only.
 *
 * <p>The project uses {@code kafka-clients} directly rather than {@code spring-kafka}, so
 * Spring Boot auto-configures no Kafka health indicator: {@code /actuator/health} answered
 * UP as long as the context was up, whatever the broker was doing. A container reported
 * healthy while unable to reach any cluster is a healthcheck that tells you nothing.
 *
 * <p>Why readiness and not liveness. An unreachable broker does not mean this process
 * should be restarted or taken out of service — the UI still serves, and the Settings page
 * can repoint the application at another cluster, which is exactly what an operator needs
 * to be able to do at that moment. It means "not ready to answer queries", which is what
 * readiness is for. The container HEALTHCHECK therefore targets
 * {@code /actuator/health/liveness}, and an orchestrator that wants to gate traffic reads
 * {@code /actuator/health/readiness}.
 *
 * <p>The bean name gives the indicator its id ({@code kafka}), which is what
 * {@code management.endpoint.health.group.readiness.include} refers to.
 */
@Component("kafka")
public class KafkaHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(KafkaHealthIndicator.class);

    /**
     * Short on purpose. A readiness probe is polled on a schedule; making it wait out a
     * long client timeout would turn one unreachable broker into a pile of blocked
     * request threads.
     */
    static final long PROBE_TIMEOUT_MS = 2_000;

    private final KafkaAdminService kafkaAdminService;

    public KafkaHealthIndicator(KafkaAdminService kafkaAdminService) {
        this.kafkaAdminService = kafkaAdminService;
    }

    @Override
    public Health health() {
        try {
            String clusterId = kafkaAdminService.probeClusterId(PROBE_TIMEOUT_MS);
            return Health.up().withDetail("clusterId", clusterId).build();
        } catch (Exception e) {
            // The message, not the stack trace: this is polled, and a broker that is down
            // for a minute would otherwise write a stack trace per poll into the log.
            log.debug("Kafka readiness probe failed: {}", e.toString());
            return Health.down().withDetail("error", SqlErrorClassifier.explain(e)).build();
        }
    }
}
