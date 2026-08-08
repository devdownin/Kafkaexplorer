// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.config.ExplorerConfig;
import com.yourcompany.kafkasqlexplorer.domain.ConsumerGroupLag;
import com.yourcompany.kafkasqlexplorer.domain.TopicConsumers;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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

    private final KafkaAdminService kafkaAdminService;
    private final ExplorerConfig explorerConfig;
    private final MeterRegistry meterRegistry;
    /** One holder per gauge identity (name + tags); Micrometer reads it on scrape. */
    private final Map<String, AtomicLong> gaugeValues = new ConcurrentHashMap<>();
    /** Series seen so far, to enforce the cap across refreshes rather than within one. */
    private final Set<String> series = ConcurrentHashMap.newKeySet();
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
                for (ConsumerGroupLag group : consumers.groups()) {
                    Tags tags = Tags.of("group", group.groupId(), "topic", topic);
                    if (!admits("kafka.consumer.group.lag" + tags)) continue;
                    setGauge("kafka.consumer.group.lag", tags, group.totalLag());
                    setGauge("kafka.consumer.group.partitions.without.commit", tags,
                            group.partitionsWithoutCommit());
                    setGauge("kafka.consumer.group.assigned.members", tags, group.assignedMembers());
                }
            } catch (Exception e) {
                // Registered gauges keep their last value: a transient admin failure must not
                // read as "the lag dropped to zero", which is the one wrong answer here.
                log.debug("Consumer lag metrics refresh failed for topic {}", topic, e);
            }
        }
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
