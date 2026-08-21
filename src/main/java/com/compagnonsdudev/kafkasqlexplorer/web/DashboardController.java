// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.DashboardResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicActivityResponse;
import com.compagnonsdudev.kafkasqlexplorer.service.FlinkSqlService;
import com.compagnonsdudev.kafkasqlexplorer.service.KafkaAdminService;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private static final Logger logger = LoggerFactory.getLogger(DashboardController.class);

    private final KafkaAdminService kafkaAdminService;
    private final FlinkSqlService flinkSqlService;
    private final ExplorerConfig explorerConfig;
    private final KafkaConfig kafkaConfig;

    public DashboardController(KafkaAdminService kafkaAdminService, FlinkSqlService flinkSqlService,
                               ExplorerConfig explorerConfig, KafkaConfig kafkaConfig) {
        this.kafkaAdminService = kafkaAdminService;
        this.flinkSqlService = flinkSqlService;
        this.explorerConfig = explorerConfig;
        this.kafkaConfig = kafkaConfig;
    }

    @GetMapping
    public DashboardResponse getDashboardData() {
        List<String> topics;
        Map<String, Long> topicSizes;
        Map<String, Long> topicLastMessages;
        boolean health;

        try {
            topics = kafkaAdminService.listTopics();
            topicSizes = kafkaAdminService.getTopicsSize(topics);
            topicLastMessages = kafkaAdminService.getTopicsLastMessageTimestamps(topics);
            health = kafkaAdminService.ping();
        } catch (Exception e) {
            logger.warn("Failed to fetch Kafka metadata, returning empty defaults: {}", e.getMessage());
            topics = Collections.emptyList();
            topicSizes = Collections.emptyMap();
            topicLastMessages = Collections.emptyMap();
            health = false;
        }

        long totalMessages = topicSizes.values().stream().mapToLong(Long::longValue).sum();

        return new DashboardResponse(
                topics,
                topicSizes,
                totalMessages,
                flinkSqlService.listTables(),
                flinkSqlService.getActiveJobs(),
                health,
                explorerConfig.getClusterName(),
                kafkaConfig.getBootstrapServers(),
                topicLastMessages,
                explorerConfig.getResolvedInternalTopicPrefix()
        );
    }

    /**
     * The sparkline column of the topics table: what each of these topics produced, bucket by
     * bucket, over one shared window.
     *
     * <p>Separate from {@code GET /api/dashboard} on purpose, and not merged into it: the
     * dashboard polls every five seconds and lists the whole cluster, while this measures only the
     * rows on screen and is refreshed on the cache's own cadence. Folding the two together would
     * make every poll of a nine-hundred-topic cluster pay for a curve nobody is looking at.
     *
     * <p>Bounded on both axes and it says so rather than truncating in silence — the topic list is
     * cut to {@code explorer.activity-max-topics}, the work to
     * {@code explorer.activity-max-lookups}, and what was left out comes back in
     * {@code warnings}.
     *
     * <p>A broker that cannot answer yields {@code available: false} with the reason, never a row
     * of zeros: a flat curve is a claim about a quiet topic, which is the opposite of "we could
     * not tell".
     *
     * @param topics   comma-separated topic names (Kafka names cannot contain a comma), in the
     *                 order they are displayed — that order is what the lookup budget preserves
     * @param windowMs how far back to look; the configured default when absent
     * @param buckets  points in each series; the configured default when absent
     */
    @GetMapping("/activity")
    public TopicActivityResponse getTopicActivity(
            @RequestParam(name = "topics") List<String> topics,
            @RequestParam(name = "windowMs", required = false) Long windowMs,
            @RequestParam(name = "buckets", required = false) Integer buckets) {

        int maxTopics = Math.max(1, explorerConfig.getActivityMaxTopics());
        List<String> requested = topics.stream()
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .distinct()
                .toList();
        List<String> inScope = requested.size() > maxTopics ? requested.subList(0, maxTopics) : requested;

        TopicActivityResponse response = kafkaAdminService.getTopicActivity(
                inScope,
                windowMs == null ? explorerConfig.getActivityDefaultWindowMs() : windowMs,
                buckets == null ? explorerConfig.getActivityBuckets() : buckets,
                Math.max(1, explorerConfig.getActivityMaxLookups()));

        if (inScope.size() == requested.size()) {
            return response;
        }
        // The cut happens here rather than inside the service so the warning names the property an
        // operator would raise, and so a caller asking for three hundred topics is told which two
        // hundred it did not get instead of finding the rows empty.
        List<String> warnings = new ArrayList<>(response.warnings());
        warnings.add((requested.size() - maxTopics) + " topic(s) beyond the first " + maxTopics
                + " were not measured (explorer.activity-max-topics).");
        return new TopicActivityResponse(response.topics(), response.windowStartMs(), response.windowEndMs(),
                response.bucketMs(), response.buckets(), response.available(), warnings);
    }
}
