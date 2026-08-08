// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KafkaHealthIndicatorTest {

    @Test
    void reportsUpWithTheClusterIdWhenTheBrokerAnswers() throws Exception {
        KafkaAdminService admin = mock(KafkaAdminService.class);
        when(admin.probeClusterId(anyLong())).thenReturn("MkU2OhlMTT69sPFvS1n16g");

        Health health = new KafkaHealthIndicator(admin).health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("MkU2OhlMTT69sPFvS1n16g", health.getDetails().get("clusterId"));
    }

    @Test
    void reportsDownWithAReadableReasonWhenTheBrokerDoesNotAnswer() throws Exception {
        KafkaAdminService admin = mock(KafkaAdminService.class);
        when(admin.probeClusterId(anyLong())).thenThrow(new TimeoutException("no broker"));

        Health health = new KafkaHealthIndicator(admin).health();

        assertEquals(Status.DOWN, health.getStatus());
        Object reason = health.getDetails().get("error");
        assertNotNull(reason, "a DOWN readiness must say why");
        assertTrue(reason.toString().contains("no broker"), "actual: " + reason);
    }

    @Test
    void neverThrows() throws Exception {
        // A health indicator that propagates turns the readiness endpoint into a 500,
        // which an orchestrator reads as "the whole app is broken" rather than "not ready".
        KafkaAdminService admin = mock(KafkaAdminService.class);
        when(admin.probeClusterId(anyLong())).thenThrow(new RuntimeException());

        assertEquals(Status.DOWN, new KafkaHealthIndicator(admin).health().getStatus());
    }
}
