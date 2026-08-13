// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import org.apache.kafka.clients.consumer.ConsumerConfig;

import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * The consumer groups this application creates for itself, named in one place and — above all —
 * configured never to commit.
 *
 * <p>Every reader here assigns its partitions and seeks explicitly; not one of them ever reads a
 * committed offset. Seven of them nonetheless left {@code enable.auto.commit} at its default, which
 * is <strong>true</strong>. So each metadata read, each timestamp scan, each sample fetch committed
 * offsets under a fresh random group id, and left a consumer group behind on the cluster —
 * retained for {@code offsets.retention.minutes} (a week, by default).
 *
 * <p>That is not a tidiness problem. The Dashboard polls {@code /api/dashboard} every 30 seconds,
 * and that call alone drives two of those readers: an idle browser tab manufactured a few thousand
 * phantom groups a day. Each one then has committed offsets, no members, and a topic that keeps
 * receiving records — which is precisely the shape {@code ConsumerGroupLag.health()} grades as
 * {@code STALLED}, and which the cluster audit reports as a <em>critical</em> finding. The
 * application was inventing critical findings about its own leftovers, crowding out the real
 * consumer groups from a panel that is capped at {@code explorer.consumer-group-max-groups}.
 *
 * <p>Hence one entry point: {@link #configure(Properties, String)} sets the name and kills the
 * commits together, so the two can no longer drift apart.
 */
public final class ExplorerConsumerGroups {

    /** Everything this application creates for its own reads. */
    public static final String PREFIX = "kafka-explorer-";

    /**
     * Group-id shapes used before the prefix existed, kept so that groups already lingering on a
     * cluster — and instances still running an older build — are still recognised as ours. A
     * cluster upgraded yesterday would otherwise show a week of phantom groups as third-party
     * consumers, which is the exact confusion this class removes.
     */
    private static final List<String> LEGACY_PREFIXES = List.of(
        // Not legacy: the group id `DdlGeneratorService` writes into every generated Flink table
        // (`'properties.group.id' = 'flink_table_<table>'`). It is a group this application asks
        // Flink to use on the user's cluster, so it belongs on this list — the Kafka connector
        // commits only on a checkpoint, which a bounded local SELECT never takes, but a group that
        // does gain committed offsets with no member is precisely the phantom-STALLED shape above.
        // The DDL pins `enable.auto.commit=false` besides; recognising the name is the second lock.
        "flink_table_",
        "kafka-sql-explorer-",
        "explorer-earliest-",
        "explorer-metrics-restorer-",
        "audit-history-reader-",
        "topic-search-",
        "flow-records-",
        "live-consumer-",
        "snapshot-reader-"
    );

    private ExplorerConsumerGroups() {
    }

    /** A one-shot reader: {@code kafka-explorer-<purpose>-<uuid>}. */
    public static String transientGroup(String purpose) {
        return PREFIX + purpose + "-" + UUID.randomUUID();
    }

    /** A reader tied to something the user started, so the id is traceable back to it. */
    public static String forSession(String purpose, String sessionId) {
        return PREFIX + purpose + "-" + sessionId;
    }

    /**
     * Names an internal consumer and stops it committing.
     *
     * <p>Both, always: a group id without {@code enable.auto.commit=false} is exactly the
     * combination that produced the phantom groups.
     */
    public static void configure(Properties props, String purpose) {
        props.put(ConsumerConfig.GROUP_ID_CONFIG, transientGroup(purpose));
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
    }

    /** Same, for a consumer whose id must stay stable for the life of a session. */
    public static void configureForSession(Properties props, String purpose, String sessionId) {
        props.put(ConsumerConfig.GROUP_ID_CONFIG, forSession(purpose, sessionId));
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
    }

    /**
     * True when a group id belongs to this application rather than to a real consumer.
     *
     * <p>Used to keep the app out of its own answers to "who reads this topic". It matches other
     * instances of the explorer too, which is intended: another explorer pointed at the same
     * cluster is no more a consumer of your pipeline than this one is.
     */
    public static boolean isExplorerGroup(String groupId) {
        if (groupId == null) return false;
        if (groupId.startsWith(PREFIX)) return true;
        return LEGACY_PREFIXES.stream().anyMatch(groupId::startsWith);
    }
}
