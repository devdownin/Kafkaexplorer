// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates the three topics this application keeps its own state in, with the retention policy each
 * one actually needs.
 *
 * <p>Nothing here ever created them: they were auto-created by the broker on the first write, and
 * therefore with its defaults. That is wrong for all three, in two different ways.
 *
 * <ul>
 *   <li><b>{@code internal.metrics.config} and {@code internal.field.mappings} are keyed stores.</b>
 *       Each is replayed by key at startup and only the last record per key matters, which is the
 *       definition of a compacted topic — and both got {@code cleanup.policy=delete} with the
 *       broker's retention. A metric configured eight days ago and not edited since was deleted by
 *       the broker, and the next restart came up without it, silently. {@code FieldMappingStore}
 *       says as much in its own warning ("The topic is meant to be compacted — check that it is"):
 *       the requirement was documented and enforced nowhere.</li>
 *   <li><b>{@code internal.audit.history} is append-only and nothing ever trimmed it.</b> What
 *       bounded it was whichever retention the broker happened to apply — unbounded growth on one
 *       cluster, a "Past runs" list that stops a week back on another. Neither is a decision
 *       anybody took; {@code explorer.audit-history-retention-ms} is.</li>
 * </ul>
 *
 * <p>And it removes a dependency the stacks had to work around: {@code KAFKA_AUTO_CREATE_TOPICS_ENABLE}
 * is set to {@code "true"} in the bundled compose files with a comment naming these very topics.
 * Most production brokers turn auto-creation off, and there the three writes simply failed.
 *
 * <p>Four guards, the same ones {@link ExplorerGroupCleanupService} states for the other write this
 * application makes to a cluster:
 *
 * <ul>
 *   <li><b>Only ours.</b> The names come from {@link ExplorerConfig}, so a configured
 *       {@code explorer.internal-topic-prefix} is followed, and no topic of the user's pipelines
 *       can be named here.</li>
 *   <li><b>Create, don't alter.</b> An existing topic whose policy differs is reported, not
 *       corrected — it may have been configured deliberately. {@code explorer.internal-topic-reconcile}
 *       is the opt-in that fixes it, and it is what an upgraded deployment wants once, since those
 *       topics carry the auto-created defaults.</li>
 *   <li><b>The broker sizes the topic.</b> Partitions and replication are left to its defaults; a
 *       hard-coded replication factor is wrong on every cluster that is not the one it was written
 *       on.</li>
 *   <li><b>Never fatal, and never in front of readiness.</b> It runs after
 *       {@code ApplicationReadyEvent}: the stores fall back to auto-creation exactly as before if
 *       this cannot run, and housekeeping on someone else's cluster must not keep the app down.</li>
 * </ul>
 */
@Service
public class InternalTopicProvisioner {

    private static final Logger log = LoggerFactory.getLogger(InternalTopicProvisioner.class);

    static final String CLEANUP_POLICY = "cleanup.policy";
    static final String RETENTION_MS = "retention.ms";
    static final String COMPACT = "compact";
    static final String DELETE = "delete";

    private final KafkaAdminService kafkaAdminService;
    private final ExplorerConfig explorerConfig;

    public InternalTopicProvisioner(KafkaAdminService kafkaAdminService, ExplorerConfig explorerConfig) {
        this.kafkaAdminService = kafkaAdminService;
        this.explorerConfig = explorerConfig;
    }

    /**
     * One of this application's own topics and the configuration it needs.
     *
     * @param name    the topic, prefix applied
     * @param purpose what it holds, for the log line an operator reads
     * @param configs the entries this application asserts — never the whole configuration
     */
    record InternalTopic(String name, String purpose, Map<String, String> configs) {
    }

    @EventListener(ApplicationReadyEvent.class)
    public void provisionOnStartup() {
        try {
            provision();
        } catch (Exception e) {
            // The stores still write, and the broker still auto-creates if it is allowed to:
            // this is the improvement, not the mechanism.
            log.warn("The internal topics could not be provisioned: {}", SqlErrorClassifier.explain(e));
        }
    }

    /** Visible for testing; returns the names it created. */
    List<String> provision() {
        List<InternalTopic> wanted = desiredTopics();
        if (!explorerConfig.isInternalTopicProvisioning()) {
            log.info("Internal topic provisioning is off (explorer.internal-topic-provisioning): {} "
                + "will be used as they are, or auto-created by the broker.",
                wanted.stream().map(InternalTopic::name).toList());
            return List.of();
        }

        List<String> created = new ArrayList<>();
        for (InternalTopic topic : wanted) {
            try {
                if (kafkaAdminService.createTopicIfAbsent(topic.name(), topic.configs())) {
                    created.add(topic.name());
                    log.info("Created '{}' ({}) with {}", LogSafe.name(topic.name()), topic.purpose(),
                        topic.configs());
                } else {
                    verify(topic);
                }
            } catch (Exception e) {
                // One topic costs itself: the other two are separate stores, and a failure here
                // leaves the previous behaviour (auto-creation on first write) intact.
                log.warn("Could not provision '{}' ({}): {}", LogSafe.name(topic.name()), topic.purpose(),
                    SqlErrorClassifier.explain(e));
            }
        }
        return created;
    }

    /**
     * The three topics and what each needs.
     *
     * <p>Retention is asserted on the history topic alone: the two keyed stores are compacted, and
     * naming a retention beside it would delete by age the records compaction exists to keep.
     */
    List<InternalTopic> desiredTopics() {
        List<InternalTopic> topics = new ArrayList<>();
        topics.add(new InternalTopic(explorerConfig.getMetricsConfigTopic(),
            "metric configurations", Map.of(CLEANUP_POLICY, COMPACT)));
        topics.add(new InternalTopic(explorerConfig.getFieldMappingTopic(),
            "Process Mining field mappings", Map.of(CLEANUP_POLICY, COMPACT)));

        Map<String, String> history = new LinkedHashMap<>();
        history.put(CLEANUP_POLICY, DELETE);
        long retention = explorerConfig.getAuditHistoryRetentionMs();
        // 0 is "leave the broker's retention alone", which is a third answer and not a zero-length
        // one: writing retention.ms=0 would delete every report as it lands.
        if (retention != 0) history.put(RETENTION_MS, Long.toString(retention));
        topics.add(new InternalTopic(explorerConfig.getAuditHistoryTopic(), "audit history", history));
        return topics;
    }

    /**
     * Reports an existing topic whose configuration is not the one this store needs — and fixes it
     * only when told to.
     *
     * <p>The comparison is per entry, and only over the entries this application asserts:
     * {@code cleanup.policy} is a list, so a topic set to {@code compact,delete} <em>does</em>
     * compact and is not a finding.
     */
    private void verify(InternalTopic topic) throws Exception {
        Map<String, String> actual = kafkaAdminService.getTopicConfigs(topic.name());
        if (actual.isEmpty()) return;   // could not be read: that is not a finding about the topic

        Map<String, String> wrong = new LinkedHashMap<>();
        topic.configs().forEach((key, wantedValue) -> {
            String found = actual.get(key);
            if (found == null || !satisfies(key, found, wantedValue)) wrong.put(key, wantedValue);
        });
        if (wrong.isEmpty()) return;

        if (!explorerConfig.isInternalTopicReconcile()) {
            wrong.forEach((key, wantedValue) -> log.warn(
                "Topic '{}' ({}) has {}={} where this store needs {}. Metric configurations and "
                + "field mappings are keyed stores that only work on a compacted topic, and the "
                + "audit history is bounded by its retention. Set "
                + "explorer.internal-topic-reconcile=true to have this fixed at the next start, or "
                + "run: kafka-configs.sh --alter --topic {} --add-config {}={}",
                LogSafe.name(topic.name()), topic.purpose(), key, actual.get(key), wantedValue,
                LogSafe.name(topic.name()), key, wantedValue));
            return;
        }
        kafkaAdminService.alterTopicConfigs(topic.name(), wrong);
        log.info("Reconciled '{}' ({}): set {}", LogSafe.name(topic.name()), topic.purpose(), wrong);
    }

    /**
     * Whether a value the broker reports already satisfies what is wanted.
     *
     * <p>{@code cleanup.policy} is a comma-separated list, so {@code compact,delete} satisfies
     * {@code compact}: the topic is compacted, which is the property the store depends on, and
     * whether it also deletes by age is the operator's business. Every other entry is compared
     * exactly.
     */
    static boolean satisfies(String key, String actual, String wanted) {
        if (!CLEANUP_POLICY.equals(key)) return wanted.equals(actual);
        for (String policy : actual.split(",")) {
            if (policy.trim().equalsIgnoreCase(wanted)) return true;
        }
        return false;
    }
}
