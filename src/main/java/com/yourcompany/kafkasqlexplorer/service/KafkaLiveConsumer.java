// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.config.ClaudeConfig;
import com.yourcompany.kafkasqlexplorer.config.KafkaConfig;
import com.yourcompany.kafkasqlexplorer.domain.AnomalyReport;
import com.yourcompany.kafkasqlexplorer.domain.FieldMapping;
import com.yourcompany.kafkasqlexplorer.domain.KafkaMessage;
import com.yourcompany.kafkasqlexplorer.domain.ProcessMiningResult;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
public class KafkaLiveConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaLiveConsumer.class);

    private final LlmAnalysisService llmAnalysisService;
    private final SseEmitterManager sseEmitterManager;
    private final ClaudeConfig claudeConfig;
    private final KafkaConfig kafkaConfig;

    private final Map<String, ScheduledFuture<?>> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, KafkaConsumer<String, String>> activeConsumers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    public KafkaLiveConsumer(LlmAnalysisService llmAnalysisService,
                              SseEmitterManager sseEmitterManager,
                              ClaudeConfig claudeConfig,
                              KafkaConfig kafkaConfig) {
        this.llmAnalysisService = llmAnalysisService;
        this.sseEmitterManager = sseEmitterManager;
        this.claudeConfig = claudeConfig;
        this.kafkaConfig = kafkaConfig;
    }

    public void startSession(String sessionId, List<String> topics, FieldMapping fieldMapping) {
        log.info("Starting live session {} for topics: {}", sessionId, topics);

        int windowSize = claudeConfig.getSnapshotWindowSize();
        long windowTimeoutMs = claudeConfig.getSnapshotWindowTimeoutSeconds() * 1000L;

        Properties props = buildConsumerProps(sessionId);
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        activeConsumers.put(sessionId, consumer);

        // Subscribe and seek to latest
        consumer.subscribe(topics);
        // Poll once to trigger partition assignment, then seek to end
        consumer.poll(Duration.ofMillis(500));
        consumer.seekToEnd(consumer.assignment());

        List<KafkaMessage> buffer = new ArrayList<>();
        final String[] lastFlowchart = {null};
        final long[] lastAnalysisTime = {System.currentTimeMillis()};

        // Main polling task — runs every 1 second
        ScheduledFuture<?> pollingFuture = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (!sseEmitterManager.exists(sessionId)) {
                    stopSession(sessionId);
                    return;
                }

                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (var record : records) {
                    buffer.add(new KafkaMessage(
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        record.timestamp(),
                        record.key(),
                        record.value()
                    ));
                }

                long now = System.currentTimeMillis();
                boolean bufferFull = buffer.size() >= windowSize;
                boolean timedOut = (now - lastAnalysisTime[0]) >= windowTimeoutMs && !buffer.isEmpty();

                if (bufferFull || timedOut) {
                    List<KafkaMessage> snapshot = new ArrayList<>(buffer);
                    buffer.clear();
                    lastAnalysisTime[0] = now;

                    // Run analysis in background to avoid blocking poller
                    scheduler.submit(() -> {
                        try {
                            ProcessMiningResult result = llmAnalysisService.analyzeLive(
                                snapshot, fieldMapping, lastFlowchart[0]);

                            if (result.flowchart() != null && !result.flowchart().isBlank()
                                    && !"NO_CHANGE".equals(result.flowchart())) {
                                lastFlowchart[0] = result.flowchart();
                                sseEmitterManager.send(sessionId, "FLOWCHART_UPDATE", result.flowchart());
                            }

                            if (result.comments() != null && !result.comments().isBlank()) {
                                sseEmitterManager.send(sessionId, "ANALYSIS_COMMENTS", result.comments());
                            }

                            if (result.anomalies() != null) {
                                for (AnomalyReport anomaly : result.anomalies()) {
                                    sseEmitterManager.send(sessionId, "ANOMALY_DETECTED", anomaly);
                                }
                            }

                            // Send window stats
                            sseEmitterManager.send(sessionId, "WINDOW_STATS", Map.of(
                                "windowSize", snapshot.size(),
                                "timestamp", now
                            ));

                        } catch (Exception e) {
                            log.error("Error during live analysis for session {}: {}", sessionId, e.getMessage(), e);
                        }
                    });
                }

            } catch (Exception e) {
                log.error("Error in live consumer polling for session {}: {}", sessionId, e.getMessage(), e);
            }
        }, 0, 1, TimeUnit.SECONDS);

        activeSessions.put(sessionId, pollingFuture);

        // Heartbeat task — runs every 15 seconds
        scheduler.scheduleAtFixedRate(() -> {
            if (!sseEmitterManager.exists(sessionId)) {
                stopSession(sessionId);
                return;
            }
            sseEmitterManager.sendHeartbeat(sessionId);
        }, 15, 15, TimeUnit.SECONDS);
    }

    public void stopSession(String sessionId) {
        log.info("Stopping live session: {}", sessionId);

        ScheduledFuture<?> future = activeSessions.remove(sessionId);
        if (future != null) {
            future.cancel(false);
        }

        KafkaConsumer<String, String> consumer = activeConsumers.remove(sessionId);
        if (consumer != null) {
            try {
                consumer.close();
            } catch (Exception e) {
                log.warn("Error closing live consumer for session {}: {}", sessionId, e.getMessage());
            }
        }

        sseEmitterManager.complete(sessionId);
    }

    private Properties buildConsumerProps(String sessionId) {
        Properties props = new Properties();
        kafkaConfig.getKafkaProperties().forEach(props::put);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "live-consumer-" + sessionId);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1000");
        return props;
    }
}
