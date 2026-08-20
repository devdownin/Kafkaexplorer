// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The budget the two startup restores share, and the verdict they pass to each other.
 *
 * <p>Measured with nothing listening on the bootstrap address: the field-mapping restore and the
 * metric-configuration restore were 10.1 s of a 14.5 s boot, each waiting out the Kafka client's
 * 5 s default API timeout to establish the same fact. The second one now reads the first one's
 * answer.
 */
class StartupRestoreTest {

    private static StartupRestore withTimeout(long ms) {
        ExplorerConfig config = new ExplorerConfig();
        config.setStartupRestoreTimeoutMs(ms);
        return new StartupRestore(config);
    }

    @Test
    void nothingIsKnownBeforeARestoreHasRun() {
        assertNull(withTimeout(3000).brokerUnreachableReason());
    }

    @Test
    void theVerdictIsWhatTheFirstRestoreSaw() {
        StartupRestore restore = withTimeout(3000);

        restore.brokerDidNotAnswer("metric configurations", "Timed out waiting for a node assignment");

        assertEquals("Timed out waiting for a node assignment", restore.brokerUnreachableReason());
    }

    /**
     * The first account wins. Both restores time out against the same broker within a second of
     * each other, and the second one's identical message must not overwrite what actually happened
     * first — that is the one the operator has to read.
     */
    @Test
    void aSecondFailureDoesNotRewriteTheFirstAccount() {
        StartupRestore restore = withTimeout(3000);

        restore.brokerDidNotAnswer("metric configurations", "Connection refused");
        restore.brokerDidNotAnswer("Process Mining field mappings", "Timed out");

        assertEquals("Connection refused", restore.brokerUnreachableReason());
    }

    @Test
    void theConfiguredBudgetIsWhatTheConsumersUse() {
        StartupRestore restore = withTimeout(4000);

        assertEquals(4000, restore.discoveryTimeoutMs());
        assertTrue(restore.requestTimeoutMs() < restore.discoveryTimeoutMs(),
            "the request timeout has to sit under the API timeout, or it never bites");
    }

    /**
     * A budget below the floor is not a stricter policy, it is a restore that can only fail —
     * and losing an operator's hand-corrected field mappings to a mistyped property is worse than
     * a slow boot.
     */
    @Test
    void anUnusableBudgetIsFlooredRatherThanObeyed() {
        assertEquals(500, withTimeout(0).discoveryTimeoutMs());
        assertEquals(500, withTimeout(-1).discoveryTimeoutMs());
        assertTrue(withTimeout(0).requestTimeoutMs() > 0);
    }
}
