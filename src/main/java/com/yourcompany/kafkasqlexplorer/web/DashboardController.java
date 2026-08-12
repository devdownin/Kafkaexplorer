// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.web;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yourcompany.kafkasqlexplorer.config.ExplorerConfig;
import com.yourcompany.kafkasqlexplorer.config.KafkaConfig;
import com.yourcompany.kafkasqlexplorer.domain.DashboardResponse;
import com.yourcompany.kafkasqlexplorer.service.FlinkSqlService;
import com.yourcompany.kafkasqlexplorer.service.KafkaAdminService;

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
                topicLastMessages
        );
    }
}
