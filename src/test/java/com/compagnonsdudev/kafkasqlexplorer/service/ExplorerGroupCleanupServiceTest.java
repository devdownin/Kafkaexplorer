// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one place this application writes to a cluster, so the guards are what the tests are about.
 *
 * <p>Off by default, restricted to groups that are ours *and* dormant, bounded per pass, and never
 * able to stop the app from starting.
 */
class ExplorerGroupCleanupServiceTest {

    private KafkaAdminService admin;
    private ExplorerConfig config;
    private ExplorerGroupCleanupService service;

    @BeforeEach
    void setUp() {
        admin = Mockito.mock(KafkaAdminService.class);
        config = new ExplorerConfig();
        service = new ExplorerGroupCleanupService(admin, config);
    }

    @Test
    void doesNothingUnlessItIsTurnedOn() {
        assertTrue(config.isCleanupOwnGroups() == false, "the shipped default must not write to a cluster");

        service.cleanUpOnStartup();

        verify(admin, never()).listDeletableExplorerGroups(anyInt());
        verify(admin, never()).deleteConsumerGroups(anyList());
    }

    @Test
    void deletesWhatTheAdminServiceSelected() {
        config.setCleanupOwnGroups(true);
        when(admin.listDeletableExplorerGroups(anyInt()))
                .thenReturn(List.of("kafka-explorer-metadata-1", "kafka-explorer-metadata-2"));
        when(admin.deleteConsumerGroups(anyList()))
                .thenReturn(List.of("kafka-explorer-metadata-1", "kafka-explorer-metadata-2"));

        assertEquals(2, service.run().size());
        verify(admin).deleteConsumerGroups(List.of("kafka-explorer-metadata-1", "kafka-explorer-metadata-2"));
    }

    /** The cap is passed down, so one pass cannot turn a start into a batch job. */
    @Test
    void asksForNoMoreThanTheConfiguredCap() {
        config.setCleanupOwnGroups(true);
        config.setCleanupOwnGroupsMax(50);
        when(admin.listDeletableExplorerGroups(50)).thenReturn(List.of());

        service.run();

        verify(admin).listDeletableExplorerGroups(50);
    }

    /**
     * Once at startup was the wrong cadence for what is being cleaned: every bundled stack runs
     * this application with {@code restart: unless-stopped}, and a live Process Mining session
     * leaves an empty group behind each time one ends — so a deployment that stays up for months
     * tidied up once, months ago.
     */
    @Test
    void itRunsAgainOnTheConfiguredInterval() {
        config.setCleanupOwnGroups(true);
        config.setCleanupOwnGroupsIntervalMs(50);
        when(admin.listDeletableExplorerGroups(anyInt())).thenReturn(List.of());

        try {
            service.cleanUpOnStartup();
            verify(admin, timeout(5_000).atLeast(3)).listDeletableExplorerGroups(anyInt());
        } finally {
            service.stop();
        }
    }

    /**
     * {@code scheduleWithFixedDelay} cancels the whole schedule on the first exception its task
     * lets out, so one unreachable broker would otherwise end the cleanup for the life of the
     * process — silently, which is how this became a startup-only job in the first place.
     */
    @Test
    void oneFailedPassDoesNotEndTheSchedule() {
        config.setCleanupOwnGroups(true);
        config.setCleanupOwnGroupsIntervalMs(50);
        when(admin.listDeletableExplorerGroups(anyInt()))
                .thenThrow(new IllegalStateException("broker unreachable"))
                .thenReturn(List.of());

        try {
            service.cleanUpOnStartup();
            verify(admin, timeout(5_000).atLeast(3)).listDeletableExplorerGroups(anyInt());
        } finally {
            service.stop();
        }
    }

    /** Zero keeps the old behaviour exactly: one pass, at startup, and never again. */
    @Test
    void anIntervalOfZeroRunsOnlyAtStartup() {
        config.setCleanupOwnGroups(true);
        config.setCleanupOwnGroupsIntervalMs(0);
        when(admin.listDeletableExplorerGroups(anyInt())).thenReturn(List.of());

        try {
            service.cleanUpOnStartup();
            verify(admin, timeout(500).times(1)).listDeletableExplorerGroups(anyInt());
            verify(admin, after(300).times(1)).listDeletableExplorerGroups(anyInt());
        } finally {
            service.stop();
        }
    }

    @Test
    void aCapOfZeroDeletesNothingAtAll() {
        config.setCleanupOwnGroups(true);
        config.setCleanupOwnGroupsMax(0);

        assertEquals(List.of(), service.run());
        verify(admin, never()).listDeletableExplorerGroups(anyInt());
    }

    @Test
    void deletesNothingWhenThereIsNothingToDelete() {
        config.setCleanupOwnGroups(true);
        when(admin.listDeletableExplorerGroups(anyInt())).thenReturn(List.of());

        assertEquals(List.of(), service.run());
        verify(admin, never()).deleteConsumerGroups(anyList());
    }

    /*
     * Housekeeping on someone else's cluster must never be a reason to refuse to start — and the
     * partial outcome is reported rather than swallowed as a success.
     */
    @Test
    void aFailureNeverStopsTheApplicationFromStarting() {
        config.setCleanupOwnGroups(true);
        when(admin.listDeletableExplorerGroups(anyInt())).thenThrow(new IllegalStateException("broker gone"));

        service.cleanUpOnStartup();   // must not throw
    }

    @Test
    void reportsOnlyWhatWasActuallyDeleted() {
        config.setCleanupOwnGroups(true);
        when(admin.listDeletableExplorerGroups(anyInt()))
                .thenReturn(List.of("kafka-explorer-a", "kafka-explorer-b"));
        // The second one became active between the listing and the delete: it stays.
        when(admin.deleteConsumerGroups(anyList())).thenReturn(List.of("kafka-explorer-a"));

        assertEquals(List.of("kafka-explorer-a"), service.run());
    }
}
