// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The liveness / readiness split is configuration, and configuration that only the
 * container healthcheck exercises is configuration nobody notices breaking: a typo in
 * `management.endpoint.health.group.readiness.include` leaves the group silently absent
 * and `/actuator/health/liveness` answering 404, which the HEALTHCHECK reads as a dead
 * container. Both groups are asserted to exist here, where a rename fails the build.
 */
@SpringBootTest
class HealthProbesTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("kafka.bootstrap-servers", () -> "localhost:9092");
    }

    @Autowired
    private HealthEndpointGroups groups;

    @Test
    void livenessAndReadinessAreBothExposed() {
        // The Dockerfile HEALTHCHECK targets liveness; an orchestrator gates on readiness.
        assertTrue(groups.getNames().contains("liveness"), "groups: " + groups.getNames());
        assertTrue(groups.getNames().contains("readiness"), "groups: " + groups.getNames());
    }

    @Test
    void readinessCarriesTheKafkaIndicatorAndLivenessDoesNot() {
        // The whole point of the split: a broker outage must not make the container look
        // dead, since the app still serves and can be repointed from the Settings page.
        assertTrue(groups.get("readiness").isMember("kafka"),
            "readiness must include the Kafka indicator");
        assertTrue(!groups.get("liveness").isMember("kafka"),
            "liveness must not depend on the broker");
    }
}
