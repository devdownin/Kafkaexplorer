// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

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
        "kafka-sql-explorer-",
        "explorer-earliest-",
        "explorer-metrics-restorer-",
        "audit-history-reader-",
        "topic-search-",
        "flow-records-",
        "live-consumer-",
        "snapshot-reader-"
    );

    /**
     * The group id {@code DdlGeneratorService} writes into every generated Flink table
     * ({@code 'properties.group.id' = 'flink_table_<table>'}).
     *
     * <p>Ours by origin — this application is what puts that name on the user's cluster — but
     * <strong>not ours to delete</strong>, which is why it is kept apart from the prefixes above
     * rather than listed among them. The generated DDL exists to be copied: three endpoints serve
     * it, the Topic page shows it with a copy button, and pasting it into a production Flink job
     * is the intended use. That job then runs under this very id, so the same name may belong to
     * something this application has never launched and knows nothing about.
     *
     * <p>Hence the split below. For "is this a consumer of the user's pipeline?" the answer is no
     * and the group is excluded — a Flink table registered for a SELECT is not a stakeholder in
     * anyone's backlog. For "may this application delete it?" the answer must be no as well, and
     * for the opposite reason: it might not be ours at all.
     */
    static final String FLINK_TABLE_PREFIX = "flink_table_";

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
     * True when a group id is this application's doing rather than a real consumer's.
     *
     * <p>Used to keep the app out of its own answers to "who reads this topic", and to mark those
     * rows on the Cluster page. It matches other instances of the explorer too, which is intended:
     * another explorer pointed at the same cluster is no more a consumer of your pipeline than
     * this one is. It also matches {@link #FLINK_TABLE_PREFIX} — a table this application
     * registered is not a stakeholder in anyone's backlog either.
     *
     * <p>This answers <em>origin</em>, not ownership. For "may we delete it?" use
     * {@link #isOwnReaderGroup(String)}, which is deliberately narrower.
     */
    public static boolean isExplorerGroup(String groupId) {
        if (groupId == null) return false;
        return isOwnReaderGroup(groupId) || groupId.startsWith(FLINK_TABLE_PREFIX);
    }

    /**
     * True when a group id was created by a consumer <em>this application runs itself</em>.
     *
     * <p>The predicate for destructive housekeeping, and the reason it is not
     * {@link #isExplorerGroup(String)}: every id it matches is minted here, by a
     * {@code KafkaConsumer} inside this JVM (or an older build of it), so nothing else can be
     * using it. A {@code flink_table_*} id fails that test — it is a name this application
     * <em>suggests</em>, in DDL meant to be copied into the user's own Flink jobs, so an idle one
     * may be a stopped production job rather than our leftover. Deleting it would break the rule
     * the cleanup states for itself: never touch a group that is not ours, whatever its state.
     */
    public static boolean isOwnReaderGroup(String groupId) {
        if (groupId == null) return false;
        if (groupId.startsWith(PREFIX)) return true;
        return LEGACY_PREFIXES.stream().anyMatch(groupId::startsWith);
    }
}
