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
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import jakarta.annotation.PreDestroy;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class KafkaLiveConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaLiveConsumer.class);

    /** Buffer is capped at windowSize × this factor so a slow analysis backlog can't grow unbounded. */
    private static final int BUFFER_CAP_FACTOR = 10;
    /** Finish a session after this many consecutive failing poll ticks (~seconds) to stop error spam. */
    private static final int MAX_CONSECUTIVE_POLL_ERRORS = 30;

    private final LlmAnalysisService llmAnalysisService;
    private final SseEmitterManager sseEmitterManager;
    private final ClaudeConfig claudeConfig;
    private final KafkaConfig kafkaConfig;

    private final Map<String, ScheduledFuture<?>> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> heartbeatTasks = new ConcurrentHashMap<>();
    private final Map<String, KafkaConsumer<String, String>> activeConsumers = new ConcurrentHashMap<>();
    /**
     * Per-session stop signal. KafkaConsumer is not thread-safe: only the polling task may
     * touch the consumer, so stopSession() just raises this flag (plus wakeup(), the one
     * thread-safe consumer method) and the polling task performs the actual close.
     */
    private final Map<String, AtomicBoolean> stopFlags = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    /**
     * LLM analyses run on their own pool: a single analysis can take up to the configured
     * request timeout (60s by default), and running them on the 4-thread scheduler starved
     * the polling and heartbeat tasks of every other live session.
     */
    private final ExecutorService analysisExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r);
        t.setName("live-analysis-" + t.getId());
        t.setDaemon(true);
        return t;
    });

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
        startSession(sessionId, topics, fieldMapping, null);
    }

    public void startSession(String sessionId, List<String> topics, FieldMapping fieldMapping,
                             String auditFocus) {
        log.info("Starting live session {} for topics: {}", sessionId, topics);

        // Defensive: a live session id is a fresh UUID per request, but never double-start one —
        // it would overwrite (and leak) the previous consumer and polling task.
        if (activeSessions.containsKey(sessionId)) {
            log.warn("Live session {} already active — ignoring duplicate start request", sessionId);
            return;
        }

        int windowSize = claudeConfig.getSnapshotWindowSize();
        long windowTimeoutMs = claudeConfig.getSnapshotWindowTimeoutSeconds() * 1000L;

        Properties props = buildConsumerProps(sessionId);
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        AtomicBoolean stopRequested = new AtomicBoolean(false);
        activeConsumers.put(sessionId, consumer);
        stopFlags.put(sessionId, stopRequested);

        List<KafkaMessage> buffer = new ArrayList<>();
        final String[] lastFlowchart = {null};
        final long[] lastAnalysisTime = {System.currentTimeMillis()};
        final boolean[] initialized = {false};
        // At most one analysis in flight per session: serializes lastFlowchart access, keeps SSE
        // updates ordered, and bounds the analysis backlog. Cleared in the analysis task's finally.
        final AtomicBoolean analysisInFlight = new AtomicBoolean(false);
        final int[] consecutiveErrors = {0};
        final int maxBuffer = Math.max(windowSize, windowSize * BUFFER_CAP_FACTOR);

        // Main polling task — runs every 1 second. ALL consumer access happens here
        // (scheduleAtFixedRate never overlaps executions of the same task), including the
        // initial subscribe/seek and the final close, so the consumer is never touched
        // concurrently from two threads.
        ScheduledFuture<?> pollingFuture = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (stopRequested.get() || !sseEmitterManager.exists(sessionId)) {
                    finishSession(sessionId);
                    return;
                }

                if (!initialized[0]) {
                    // Subscribe, poll once to trigger partition assignment, then seek to end
                    consumer.subscribe(topics);
                    consumer.poll(Duration.ofMillis(500));
                    consumer.seekToEnd(consumer.assignment());
                    initialized[0] = true;
                    return;
                }

                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                consecutiveErrors[0] = 0;   // a successful poll clears the error streak
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
                // Bound memory: if analyses fall behind ingestion, keep only the most recent messages.
                if (buffer.size() > maxBuffer) {
                    int drop = buffer.size() - maxBuffer;
                    buffer.subList(0, drop).clear();
                    log.warn("Live session {}: buffer cap {} exceeded, dropped {} oldest message(s)",
                        sessionId, maxBuffer, drop);
                }

                long now = System.currentTimeMillis();
                boolean bufferFull = buffer.size() >= windowSize;
                boolean timedOut = (now - lastAnalysisTime[0]) >= windowTimeoutMs && !buffer.isEmpty();

                // Single-flight per session: only start a new analysis when the previous one has
                // finished. This serializes lastFlowchart access, keeps SSE updates ordered, and
                // caps the analysis backlog at one in-flight task (the buffer keeps accumulating —
                // up to maxBuffer — until the in-flight analysis frees the slot).
                if ((bufferFull || timedOut) && analysisInFlight.compareAndSet(false, true)) {
                    List<KafkaMessage> snapshot = new ArrayList<>(buffer);
                    buffer.clear();
                    lastAnalysisTime[0] = now;

                    // Run analysis on the dedicated pool: it must never block the poller
                    // nor occupy the scheduler threads shared by all sessions
                    analysisExecutor.submit(() -> {
                        try {
                            ProcessMiningResult result = llmAnalysisService.analyzeLive(
                                snapshot, fieldMapping, lastFlowchart[0], auditFocus);

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

                            if (result.ragSources() != null && !result.ragSources().isEmpty()) {
                                sseEmitterManager.send(sessionId, "RAG_SOURCES", result.ragSources());
                            }

                            // Send window stats
                            sseEmitterManager.send(sessionId, "WINDOW_STATS", Map.of(
                                "windowSize", snapshot.size(),
                                "timestamp", now
                            ));

                        } catch (Exception e) {
                            log.error("Error during live analysis for session {}: {}", sessionId, e.getMessage(), e);
                            sseEmitterManager.send(sessionId, "ANALYSIS_ERROR", Map.of(
                                "message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                        } finally {
                            analysisInFlight.set(false);
                        }
                    });
                }

            } catch (WakeupException e) {
                // stopSession() called consumer.wakeup() while we were polling
                finishSession(sessionId);
            } catch (Exception e) {
                // A persistent failure (e.g. broker down) would otherwise log every second forever.
                if (++consecutiveErrors[0] >= MAX_CONSECUTIVE_POLL_ERRORS) {
                    log.error("Live session {}: {} consecutive polling errors — finishing session. Last error: {}",
                        sessionId, consecutiveErrors[0], e.getMessage(), e);
                    finishSession(sessionId);
                } else {
                    log.error("Error in live consumer polling for session {}: {}", sessionId, e.getMessage(), e);
                }
            }
        }, 0, 1, TimeUnit.SECONDS);

        activeSessions.put(sessionId, pollingFuture);

        // Heartbeat task — runs every 15 seconds. Keep the future so it can be cancelled;
        // otherwise every session leaks a periodic task that runs forever.
        ScheduledFuture<?> heartbeatFuture = scheduler.scheduleAtFixedRate(() -> {
            if (!sseEmitterManager.exists(sessionId)) {
                stopSession(sessionId);
                return;
            }
            sseEmitterManager.sendHeartbeat(sessionId);
        }, 15, 15, TimeUnit.SECONDS);
        heartbeatTasks.put(sessionId, heartbeatFuture);
    }

    /**
     * Requests the session to stop. The consumer itself is closed by the polling task
     * (see {@link #finishSession}) because KafkaConsumer is not thread-safe — calling
     * close() from an HTTP thread while a poll is in flight throws
     * ConcurrentModificationException. wakeup() is the only thread-safe consumer method
     * and aborts an in-flight poll immediately.
     */
    public void stopSession(String sessionId) {
        log.info("Stop requested for live session: {}", sessionId);

        AtomicBoolean stopRequested = stopFlags.get(sessionId);
        if (stopRequested == null) {
            // Session unknown (already finished or never started) — just make sure the emitter is closed.
            sseEmitterManager.complete(sessionId);
            return;
        }
        stopRequested.set(true);
        KafkaConsumer<String, String> consumer = activeConsumers.get(sessionId);
        if (consumer != null) {
            consumer.wakeup();
        }
    }

    /** Tears the session down from the polling thread: the only place the consumer is closed. */
    private void finishSession(String sessionId) {
        log.info("Stopping live session: {}", sessionId);

        ScheduledFuture<?> future = activeSessions.remove(sessionId);
        if (future != null) {
            future.cancel(false);
        }

        ScheduledFuture<?> heartbeat = heartbeatTasks.remove(sessionId);
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }

        stopFlags.remove(sessionId);
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

    @PreDestroy
    public void shutdown() {
        // Signal every session, then stop the pools. Once the scheduler has terminated no
        // polling task can touch a consumer anymore, so closing the leftovers from this
        // thread is safe (their finishSession tick may never have had a chance to run).
        activeSessions.keySet().forEach(this::stopSession);
        shutdownExecutor(scheduler);
        shutdownExecutor(analysisExecutor);
        activeConsumers.forEach((sessionId, consumer) -> {
            try {
                consumer.close();
            } catch (Exception e) {
                log.warn("Error closing live consumer for session {} at shutdown: {}", sessionId, e.getMessage());
            }
        });
        activeConsumers.clear();
        activeSessions.clear();
        heartbeatTasks.clear();
        stopFlags.clear();
    }

    private void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
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
