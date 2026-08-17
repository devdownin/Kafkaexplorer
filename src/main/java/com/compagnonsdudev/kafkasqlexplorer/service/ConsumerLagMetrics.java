// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.ConsumerGroupLag;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicConsumers;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicTimeLag;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.stream.Collectors;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exposes consumer-group lag as Prometheus gauges on {@code /actuator/prometheus}.
 *
 * <p>The Consumers tab answers the question once, for whoever thinks to open it. A gauge is what
 * makes a backlog <em>trigger</em> something instead of being noticed afterwards:
 *
 * <ul>
 *   <li>{@code kafka_consumer_group_lag{group,topic}} — messages the group is behind on this
 *       topic, summed over the partitions it has actually committed on</li>
 *   <li>{@code kafka_consumer_group_partitions_without_commit{group,topic}} — partitions it has
 *       never committed on, whose backlog the lag above does <em>not</em> count. Alerting on lag
 *       alone would miss a group reading three partitions of twelve</li>
 *   <li>{@code kafka_consumer_group_assigned_members{group,topic}} — members holding a partition
 *       of this topic. Zero with a non-zero lag is the stalled case: nothing will drain it, and
 *       the two series together express that far better than a threshold on lag ever could</li>
 *   <li>{@code kafka_consumer_group_lag_last_success_timestamp_seconds{group,topic}} — when the
 *       three above were last actually measured. They keep their last value through a failed
 *       read, so this is what separates a real backlog from one nobody is measuring any more;
 *       see {@link #LAST_SUCCESS}</li>
 * </ul>
 *
 * <h2>Why this is opt-in and topic-scoped</h2>
 *
 * <p>Cardinality, and cost. A series per group × topic × partition is how a metrics backend gets
 * killed — a cluster with two hundred groups over four hundred topics is eighty thousand series
 * before partitions are counted. So: partitions are aggregated away (the per-partition detail
 * stays in the UI, where it is asked for one topic at a time), the topics polled are named
 * explicitly in {@code explorer.lag-metrics-topics} rather than discovered, nothing is registered
 * when that list is empty, and {@code explorer.lag-metrics-max-series} caps what a surprising
 * cluster can produce — the cap is logged when it bites, since a silently truncated metric is
 * worse than an absent one.
 *
 * <p>Each topic costs several coordinator round trips, so the poll interval is deliberately no
 * faster than the 30 s cache behind {@link KafkaAdminService#getTopicConsumers}: polling more
 * often would return the same numbers at full price.
 */
@Component
public class ConsumerLagMetrics {

    private static final Logger log = LoggerFactory.getLogger(ConsumerLagMetrics.class);
    static final long REFRESH_PERIOD_SECONDS = 30;
    /**
     * Unix time of the last measurement that actually succeeded for a group×topic.
     *
     * <p>The three gauges below deliberately keep their last value when a read fails — falling back
     * to zero would read as "the backlog was resolved". The cost of that choice was that a frozen
     * value is indistinguishable from a fresh one: an alert on {@code lag > 10000} fires the same
     * way whether the backlog is real and stuck or simply no longer being measured, and the
     * operator it wakes has no way to tell. This is the standard Prometheus answer — date the
     * value and let the alert decide what it will trust:
     *
     * <pre>kafka_consumer_group_lag &gt; 10000
     *   and time() - kafka_consumer_group_lag_last_success_timestamp_seconds &lt; 120</pre>
     *
     * <p>A timestamp rather than a boolean: same cardinality, and it carries <em>how</em> stale,
     * which is the part a threshold needs. The cost is one more series per group×topic (a third
     * more than the three below) — paid because a number nobody can date is a number nobody should
     * act on, which defeats the point of exporting it. The cap in {@link #admits} still counts
     * group×topic pairs, so what it bounds is unchanged.
     */
    private static final String LAST_SUCCESS = "kafka.consumer.group.lag.last.success.timestamp.seconds";

    /**
     * The same backlog in time — the age of the oldest message the group has not read.
     *
     * <p>Opt-in (`explorer.lag-metrics-time`) and off by default, because it is the only series
     * here that costs a <em>record</em> read: one per lagging partition, per group, per refresh,
     * where every other gauge is metadata. A group committed at the end of every partition is
     * published as 0 without reading anything — caught up is a measurement.
     *
     * <p>In seconds, not milliseconds, because that is the Prometheus convention and the suffix
     * has to mean what it says. The {@code CONSUMER_TIME_LAG} metric template reports the same
     * measurement in milliseconds, matching its sibling templates: one measurement, two units,
     * each following the convention of where it lands.
     *
     * <p><b>This one is removed rather than frozen when a refresh cannot measure it</b>, which is
     * the opposite of the rule for the three gauges above, and deliberately so. Their last value
     * stays because a count that stopped being read is still roughly the count. An <em>age</em>
     * that stopped being read is not still roughly the age: a backlog ages by construction, so a
     * frozen "3 min" while the real one has been growing for hours is not a stale number, it is a
     * false one. Absent, an alert can see it with {@code absent()}; frozen, nobody can see it at
     * all.
     */
    private static final String TIME_LAG = "kafka.consumer.group.lag.seconds";

    private static final List<String> GAUGE_NAMES = List.of(
            "kafka.consumer.group.lag",
            "kafka.consumer.group.partitions.without.commit",
            "kafka.consumer.group.assigned.members",
            TIME_LAG,
            LAST_SUCCESS);

    private final KafkaAdminService kafkaAdminService;
    private final ExplorerConfig explorerConfig;
    private final MeterRegistry meterRegistry;
    /** One holder per gauge identity (name + tags); Micrometer reads it on scrape. */
    private final Map<String, AtomicLong> gaugeValues = new ConcurrentHashMap<>();
    /** Series seen so far, to enforce the cap across refreshes rather than within one. */
    private final Set<String> series = ConcurrentHashMap.newKeySet();
    /** Groups last seen on each watched topic, so a group that disappears stops being exported. */
    private final Map<String, Set<String>> groupsPerTopic = new ConcurrentHashMap<>();
    private volatile boolean capReported = false;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "consumer-lag-metrics");
        t.setDaemon(true);
        return t;
    });

    public ConsumerLagMetrics(KafkaAdminService kafkaAdminService, ExplorerConfig explorerConfig,
                              MeterRegistry meterRegistry) {
        this.kafkaAdminService = kafkaAdminService;
        this.explorerConfig = explorerConfig;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void start() {
        if (watchedTopics().isEmpty()) {
            // Nothing to watch: not a degraded state, the feature is simply not configured.
            log.debug("Consumer lag metrics disabled (explorer.lag-metrics-topics is empty)");
            return;
        }
        scheduler.scheduleAtFixedRate(this::refresh, 10, REFRESH_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }

    /** One poll of every watched topic → gauge updates. Package-private for tests. */
    void refresh() {
        for (String topic : watchedTopics()) {
            try {
                TopicConsumers consumers = kafkaAdminService.getTopicConsumers(
                        topic, explorerConfig.getConsumerGroupMaxGroups());
                if (!consumers.available()) {
                    // The read failed: an empty group list here means "we could not ask", and
                    // acting on it would publish exactly the wrong answer — see the catch below.
                    log.debug("Consumer lag metrics: {} could not be read ({})", topic,
                            consumers.warnings().isEmpty() ? "no reason given" : consumers.warnings().get(0));
                    continue;
                }
                // Retire *avant* d'exporter : la place libérée par un groupe disparu doit être
                // disponible pour un nouveau dès ce tour, sinon le plafond continue de compter des
                // groupes qui n'existent plus et le remplaçant n'est jamais publié.
                Set<String> live = consumers.groups().stream()
                        .map(ConsumerGroupLag::groupId)
                        .collect(Collectors.toCollection(HashSet::new));
                dropVanished(topic, live);
                for (ConsumerGroupLag group : consumers.groups()) {
                    if (group.error() != null) {
                        // This one group's offsets could not be read, so it arrives with a lag of
                        // zero — which as a gauge reads "caught up" and silences the very alert
                        // that should fire. Keep its last value, like a whole failed refresh.
                        continue;
                    }
                    Tags tags = Tags.of("group", group.groupId(), "topic", topic);
                    if (!admits("kafka.consumer.group.lag" + tags)) continue;
                    setGauge("kafka.consumer.group.lag", tags, group.totalLag());
                    setGauge("kafka.consumer.group.partitions.without.commit", tags,
                            group.partitionsWithoutCommit());
                    setGauge("kafka.consumer.group.assigned.members", tags, group.assignedMembers());
                    // Set here and nowhere else: only a row that was actually measured dates the
                    // three gauges above. Everything that skips this line — an error on the group,
                    // an unreadable topic, a throwing refresh — leaves the previous timestamp
                    // standing, which is what makes the freeze visible.
                    setGauge(LAST_SUCCESS, tags, System.currentTimeMillis() / 1000);
                    exportTimeLag(topic, group, tags);
                }
            } catch (Exception e) {
                // Registered gauges keep their last value: a transient admin failure must not
                // read as "the lag dropped to zero", which is the one wrong answer here.
                log.debug("Consumer lag metrics refresh failed for topic {}", topic, e);
            }
        }
    }

    /**
     * The same group's backlog in seconds, when {@code explorer.lag-metrics-time} is on.
     *
     * <p>A group caught up on every partition is published as 0 without any read — that is what
     * {@code totalLag == 0} means and it costs nothing to say. Anything else opens a consumer, so
     * this stays opt-in.
     *
     * <p>A measurement that fails <b>removes</b> the series instead of freezing it. That is the
     * opposite of what the record-lag gauges do, and the reason is the unit: a backlog ages by
     * itself, so a frozen age is not a stale reading but a wrong one, and it gets more wrong every
     * minute. Absent, {@code absent(kafka_consumer_group_lag_seconds{...})} sees it.
     */
    private void exportTimeLag(String topic, ConsumerGroupLag group, Tags tags) {
        if (!explorerConfig.isLagMetricsTime()) return;
        if (group.totalLag() <= 0) {
            setGauge(TIME_LAG, tags, 0);
            return;
        }
        try {
            TopicTimeLag delay = kafkaAdminService.getConsumerTimeLag(topic, group.groupId());
            if (delay.available() && delay.maxLagMs() != null) {
                setGauge(TIME_LAG, tags, delay.maxLagMs() / 1000);
            } else {
                dropTimeLag(tags);
            }
        } catch (Exception e) {
            log.debug("Time lag of {} on {} could not be measured: {}", group.groupId(), topic, e.toString());
            dropTimeLag(tags);
        }
    }

    private void dropTimeLag(Tags tags) {
        meterRegistry.find(TIME_LAG).tags(tags).meters().forEach(meterRegistry::remove);
        gaugeValues.remove(TIME_LAG + tags);
    }

    /**
     * Removes the series of groups that a <em>successful</em> read no longer returns.
     *
     * <p>Keeping the last value is right for a failed read and wrong for a group that is simply
     * gone: a decommissioned consumer went on exporting the backlog it had on its last day, for
     * the life of the process, and an alert on it could never clear. The distinction is exactly
     * {@code available} plus the per-group error above — everything that got this far was measured.
     */
    private void dropVanished(String topic, Set<String> live) {
        Set<String> known = groupsPerTopic.computeIfAbsent(topic, t -> ConcurrentHashMap.newKeySet());
        for (String gone : Set.copyOf(known)) {
            if (live.contains(gone)) continue;
            Tags tags = Tags.of("group", gone, "topic", topic);
            for (String name : GAUGE_NAMES) {
                meterRegistry.find(name).tags(tags).meters().forEach(meterRegistry::remove);
                gaugeValues.remove(name + tags);
                series.remove(name + tags);
            }
            // `admits` keys on the lag series alone; free that slot too or the cap would count
            // groups that no longer exist against the ones that do.
            series.remove("kafka.consumer.group.lag" + tags);
            known.remove(gone);
        }
        known.addAll(live);
    }

    /**
     * Whether a new series may be created. Existing ones always pass, so the cap freezes the set
     * instead of making membership depend on the order groups happen to be returned in.
     */
    private boolean admits(String key) {
        if (series.contains(key)) return true;
        if (series.size() >= explorerConfig.getLagMetricsMaxSeries()) {
            if (!capReported) {
                capReported = true;
                log.warn("Consumer lag metrics capped at {} series (explorer.lag-metrics-max-series); "
                        + "further groups are not exported. Narrow explorer.lag-metrics-topics or "
                        + "raise the cap.", explorerConfig.getLagMetricsMaxSeries());
            }
            return false;
        }
        series.add(key);
        return true;
    }

    /** De-duplicated, blank-free view of the configured topic list. */
    private List<String> watchedTopics() {
        List<String> configured = explorerConfig.getLagMetricsTopics();
        if (configured == null) return List.of();
        return List.copyOf(new LinkedHashSet<>(configured.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::trim)
                .toList()));
    }

    private void setGauge(String name, Tags tags, long value) {
        gaugeValues.computeIfAbsent(name + tags, k -> {
            AtomicLong holder = new AtomicLong();
            Gauge.builder(name, holder, AtomicLong::doubleValue)
                    .tags(tags)
                    .register(meterRegistry);
            return holder;
        }).set(value);
    }
}
