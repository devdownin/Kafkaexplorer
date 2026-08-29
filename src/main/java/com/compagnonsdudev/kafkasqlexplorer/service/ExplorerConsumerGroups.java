// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;

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

    private static final Logger log = LoggerFactory.getLogger(ExplorerConsumerGroups.class);

    /**
     * What every internal reader is named with when nothing says otherwise.
     *
     * <p>It is also permanently recognised as ours, whatever {@code explorer.consumer-group-prefix}
     * is set to: a deployment that adopts a custom prefix does not thereby disown the groups it
     * left on the cluster yesterday, and failing to recognise them is not a cosmetic slip — it is
     * how this application starts reporting critical audit findings about its own leftovers.
     */
    public static final String DEFAULT_PREFIX = "kafka-explorer-";

    /**
     * Characters a group id may carry. Kafka itself is permissive here, which is the problem: a
     * prefix with a space or a slash produces ids that are legal, unreadable, and awkward in every
     * tool that later has to name them (JMX, metrics labels, {@code kafka-consumer-groups.sh}).
     */
    private static final Pattern VALID_PREFIX = Pattern.compile("[A-Za-z0-9._:-]+");

    /** Separators a prefix may already end with — anything else gets a {@code -} appended. */
    private static final String SEPARATORS = "-_.:";

    /**
     * The prefix in force. Written once at startup from {@link
     * com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig}, read by every naming call
     * afterwards; {@code volatile} because those calls happen on the request and poll threads
     * rather than on the one that wrote it.
     */
    private static volatile String prefix = DEFAULT_PREFIX;

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

    /**
     * The prefix a configured value actually yields.
     *
     * <p>Absent, blank or whitespace-only falls back to {@link #DEFAULT_PREFIX} — an empty prefix
     * is not a shorter name, it is internal readers that no longer look like ours, which the
     * audit then reports as third-party consumers stalled on a backlog.
     *
     * <p>A value that is not a plausible group id falls back too, with a WARN naming the property:
     * the alternative is every internal read carrying an id nothing downstream can handle, failing
     * far from the mistake.
     *
     * <p>A trailing separator is added when the value has none, because {@code myapp} would
     * otherwise produce {@code myappmetadata-<uuid>} — a prefix is meant to be legible where it is
     * joined, and asking every operator to remember the dash is asking to be forgotten.
     */
    public static String resolvePrefix(String configured) {
        if (configured == null || configured.isBlank()) return DEFAULT_PREFIX;
        String trimmed = configured.trim();
        if (!VALID_PREFIX.matcher(trimmed).matches()) {
            log.warn("explorer.consumer-group-prefix '{}' is not a usable group-id prefix "
                + "(letters, digits, dot, underscore, colon and dash only); "
                + "falling back to '{}'.", trimmed, DEFAULT_PREFIX);
            return DEFAULT_PREFIX;
        }
        return SEPARATORS.indexOf(trimmed.charAt(trimmed.length() - 1)) >= 0 ? trimmed : trimmed + "-";
    }

    /**
     * Applies the configured prefix, once, at startup. Called from {@code ExplorerConfig} rather
     * than from a listener of its own: the two readers that restore state at boot
     * ({@code MetricService}, {@code FieldMappingStore}) inject that bean, so it is constructed
     * before them and no consumer can be named while this is still unset.
     */
    public static void usePrefix(String configured) {
        String resolved = resolvePrefix(configured);
        prefix = resolved;
        if (!DEFAULT_PREFIX.equals(resolved)) {
            log.info("Internal consumer groups are named '{}<purpose>-<id>'. "
                + "Groups left under '{}' are still recognised as this application's.",
                resolved, DEFAULT_PREFIX);
        }
    }

    /** The prefix in force — what an internal reader is currently named with. */
    public static String prefix() {
        return prefix;
    }

    /** A one-shot reader: {@code <prefix><purpose>-<uuid>}. */
    public static String transientGroup(String purpose) {
        return prefix + purpose + "-" + UUID.randomUUID();
    }

    /** A reader tied to something the user started, so the id is traceable back to it. */
    public static String forSession(String purpose, String sessionId) {
        return prefix + purpose + "-" + sessionId;
    }

    /**
     * Names an internal consumer, stops it committing, and stops it creating topics.
     *
     * <p>All three, always: a group id without {@code enable.auto.commit=false} is exactly the
     * combination that produced the phantom groups.
     *
     * <p>The third is the same rule one level down. {@code allow.auto.create.topics} defaults to
     * <strong>true</strong>, so asking a consumer about a topic that does not exist — a
     * {@code partitionsFor} on a name typed into the search box, a subscribe to a topic deleted
     * since the catalogue was cached — <em>creates</em> it, with one partition and the broker's
     * defaults, on the cluster under exploration. This application makes exactly two deliberate
     * writes to a user's cluster ({@code InternalTopicProvisioner}, {@code
     * ExplorerGroupCleanupService}), both opt-out and both loud; a read inventing a topic is
     * neither. It is also what makes {@code KafkaConsumer.partitionsFor} usable as the metadata
     * read the record fetchers now take instead of a separate {@code describeTopics} round trip.
     */
    public static void configure(Properties props, String purpose) {
        props.put(ConsumerConfig.GROUP_ID_CONFIG, transientGroup(purpose));
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, "false");
    }

    /** Same, for a consumer whose id must stay stable for the life of a session. */
    public static void configureForSession(Properties props, String purpose, String sessionId) {
        props.put(ConsumerConfig.GROUP_ID_CONFIG, forSession(purpose, sessionId));
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, "false");
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
        if (groupId.startsWith(prefix)) return true;
        // The shipped default is recognised whatever the configured prefix is. Adopting a custom
        // one must not orphan the groups the previous configuration left on the cluster — an
        // unrecognised leftover of ours is exactly what the audit grades STALLED and reports as
        // critical, which is the defect this class exists to keep fixed.
        if (groupId.startsWith(DEFAULT_PREFIX)) return true;
        return LEGACY_PREFIXES.stream().anyMatch(groupId::startsWith);
    }

    /**
     * True when a group id carries one of the naming schemes used <em>before</em> the prefix
     * existed — that is, a leftover no build running today can produce.
     *
     * <p>Used by the cleanup to decide what a capped pass removes first. Every candidate is ours
     * and empty, so there is no wrong answer here; what makes this the better order is that these
     * are the accumulated backlog the cleanup exists for — thousands of them on a cluster that ran
     * an older build — while a current-prefix group is at most one live session that has just
     * ended. Cutting alphabetically instead ordered them by a UUID, which is to say at random.
     */
    public static boolean isLegacyGroup(String groupId) {
        return groupId != null && LEGACY_PREFIXES.stream().anyMatch(groupId::startsWith);
    }
}
