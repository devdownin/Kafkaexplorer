// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The one line that says what this process is pointed at.
 *
 * <p>Asserted against the log because the log <em>is</em> the product: a boot with nothing
 * listening on the bootstrap address was measured at 103 775 lines, six of them from this
 * application's own package, and not one naming the address that had not answered.
 */
class StartupSummaryTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void captureLog() {
        logger = (Logger) LoggerFactory.getLogger(StartupSummary.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseLog() {
        logger.detachAppender(appender);
    }

    private String messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
            .collect(Collectors.joining("\n"));
    }

    private static StartupSummary summary(KafkaAdminService admin) {
        KafkaConfig kafkaConfig = new KafkaConfig();
        kafkaConfig.setBootstrapServers("broker.example:9092");
        return new StartupSummary(kafkaConfig, new ClaudeConfig(), admin);
    }

    /**
     * The address <em>and</em> the reason. "Disconnected" tells an operator nothing: a broker that
     * is down, a bootstrap address pointing nowhere and an authorisation failure are three
     * different problems with three different fixes.
     */
    @Test
    void anUnreachableBrokerIsNamedWithItsReason() {
        KafkaAdminService admin = mock(KafkaAdminService.class);
        when(admin.pingDetail()).thenReturn(
            new KafkaAdminService.PingResult(false, "No answer within 2000 ms"));

        summary(admin).report();

        String logged = messages();
        assertTrue(logged.contains("broker.example:9092"), logged);
        assertTrue(logged.contains("No answer within 2000 ms"), logged);
        List<ILoggingEvent> warnings = appender.list.stream()
            .filter(e -> e.getLevel() == Level.WARN).toList();
        assertTrue(warnings.size() == 1,
            "a degraded start is a WARN, not an ERROR: the UI serves and Settings can repoint it");
    }

    @Test
    void aReachableBrokerIsStatedOnce() {
        KafkaAdminService admin = mock(KafkaAdminService.class);
        when(admin.pingDetail()).thenReturn(new KafkaAdminService.PingResult(true, null));

        summary(admin).report();

        String logged = messages();
        assertTrue(logged.contains("broker.example:9092"), logged);
        assertTrue(appender.list.stream().noneMatch(e -> e.getLevel() == Level.WARN), logged);
    }

    /** One log line may never be able to affect an application that has already started. */
    @Test
    void aSummaryThatCannotBeProducedIsHarmless() {
        KafkaAdminService admin = mock(KafkaAdminService.class);
        when(admin.pingDetail()).thenThrow(new IllegalStateException("The AdminClient is closed"));

        assertDoesNotThrow(() -> summary(admin).report());
    }
}
