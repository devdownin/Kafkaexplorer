// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.config.ExplorerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Removes the consumer groups this application left on a cluster — opt-in, and the only thing
 * here that writes to it.
 *
 * <p>Older builds committed offsets under a fresh random group id on every metadata read (see
 * {@link ExplorerConsumerGroups}), so a cluster that has been running this app for a while can
 * carry thousands of empty groups, each retained for {@code offsets.retention.minutes}. They stop
 * being created, but nothing removes the ones already there: they crowd the group list, and until
 * they expire they are indistinguishable from real consumers to anything that does not know the
 * naming schemes.
 *
 * <p>Three guards, because deleting things on someone else's cluster deserves them:
 *
 * <ul>
 *   <li><b>Off by default.</b> {@code explorer.cleanup-own-groups} must be set. An app documented
 *       as read-only does not start writing because an upgrade landed.</li>
 *   <li><b>Ours, and dormant.</b> A group is deleted only if it carries one of our names
 *       <em>and</em> the broker reports it EMPTY or DEAD. A live Process Mining session holds
 *       members, so it is never a candidate — nor is any group that is not ours, whatever its
 *       state.</li>
 *   <li><b>Bounded and logged.</b> At most {@code explorer.cleanup-own-groups-max} per pass, and
 *       every deleted id is logged. A cleanup nobody can audit afterwards is worse than none.</li>
 * </ul>
 *
 * <p>It runs once, after the context is ready, on the caller's thread of the event — never during
 * bean construction, so a slow or unreachable broker cannot hold up the application's start.
 */
@Service
public class ExplorerGroupCleanupService {

    private static final Logger log = LoggerFactory.getLogger(ExplorerGroupCleanupService.class);

    private final KafkaAdminService kafkaAdminService;
    private final ExplorerConfig explorerConfig;

    public ExplorerGroupCleanupService(KafkaAdminService kafkaAdminService, ExplorerConfig explorerConfig) {
        this.kafkaAdminService = kafkaAdminService;
        this.explorerConfig = explorerConfig;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void cleanUpOnStartup() {
        if (!explorerConfig.isCleanupOwnGroups()) {
            return;
        }
        try {
            run();
        } catch (Exception e) {
            // Never fatal: this is housekeeping on someone else's cluster, and failing to tidy up
            // is not a reason to refuse to start.
            log.warn("Cleanup of the explorer's own consumer groups failed: {}", e.toString());
        }
    }

    /** Visible for testing; returns the ids it deleted. */
    List<String> run() {
        int max = Math.max(0, explorerConfig.getCleanupOwnGroupsMax());
        if (max == 0) return List.of();

        List<String> candidates = kafkaAdminService.listDeletableExplorerGroups(max);
        if (candidates.isEmpty()) {
            log.info("No leftover explorer consumer group to delete.");
            return List.of();
        }

        List<String> deleted = kafkaAdminService.deleteConsumerGroups(candidates);
        // Named, one line: an operator must be able to see afterwards exactly what was removed.
        deleted.forEach(id -> log.info("Deleted leftover explorer consumer group '{}'", id));
        if (deleted.size() < candidates.size()) {
            log.warn("{} of {} leftover explorer group(s) could not be deleted; they will be retried "
                + "on the next start.", candidates.size() - deleted.size(), candidates.size());
        }
        if (candidates.size() == max) {
            log.info("The cleanup cap of {} was reached — run again to remove the rest.", max);
        }
        return deleted;
    }
}
