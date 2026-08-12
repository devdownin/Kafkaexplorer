// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.config.ClaudeConfig;
import com.yourcompany.kafkasqlexplorer.config.KafkaConfig;
import com.yourcompany.kafkasqlexplorer.config.ProcessMiningConfig;
import com.yourcompany.kafkasqlexplorer.domain.AnomalyReport;
import com.yourcompany.kafkasqlexplorer.domain.FieldMapping;
import com.yourcompany.kafkasqlexplorer.domain.PayloadDigest;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
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
    /** Digesting a poll batch for longer than this means the tick is falling behind — worth a log line. */
    private static final long SLOW_DIGEST_WARN_MS = 1500L;

    private final LlmAnalysisService llmAnalysisService;
    private final SseEmitterManager sseEmitterManager;
    private final PayloadDigestService payloadDigestService;
    private final ClaudeConfig claudeConfig;
    private final KafkaConfig kafkaConfig;
    private final ProcessMiningConfig processMiningConfig;

    private final Map<String, LiveSession> sessions = new ConcurrentHashMap<>();
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
                              PayloadDigestService payloadDigestService,
                              ClaudeConfig claudeConfig,
                              KafkaConfig kafkaConfig,
                              ProcessMiningConfig processMiningConfig) {
        this.llmAnalysisService = llmAnalysisService;
        this.sseEmitterManager = sseEmitterManager;
        this.payloadDigestService = payloadDigestService;
        this.claudeConfig = claudeConfig;
        this.kafkaConfig = kafkaConfig;
        this.processMiningConfig = processMiningConfig;
    }

    public void startSession(String sessionId, List<String> topics, FieldMapping fieldMapping) {
        startSession(sessionId, topics, fieldMapping, null);
    }

    public void startSession(String sessionId, List<String> topics, FieldMapping fieldMapping,
                             String auditFocus) {
        log.info("Starting live session {} for topics: {}", sessionId, topics);

        // Defensive: a live session id is a fresh UUID per request, but never double-start one —
        // it would overwrite (and leak) the previous consumer and polling task.
        if (sessions.containsKey(sessionId)) {
            log.warn("Live session {} already active — ignoring duplicate start request", sessionId);
            return;
        }

        LiveSession session = new LiveSession(sessionId, topics, fieldMapping, auditFocus,
            createConsumer(buildConsumerProps(sessionId)));
        sessions.put(sessionId, session);

        // Main polling task — runs every 1 second. ALL consumer access happens here
        // (scheduleAtFixedRate never overlaps executions of the same task), including the
        // initial subscribe and the final close, so the consumer is never touched
        // concurrently from two threads.
        // Initial delay of 1s, not 0: the first tick must not run before pollingFuture is
        // assigned, or finishSession() would have nothing to cancel.
        session.pollingFuture = scheduler.scheduleAtFixedRate(
            () -> pollOnce(session), 1, 1, TimeUnit.SECONDS);

        // Heartbeat task — runs every 15 seconds. Keep the future so it can be cancelled;
        // otherwise every session leaks a periodic task that runs forever.
        session.heartbeatFuture = scheduler.scheduleAtFixedRate(() -> {
            if (!sseEmitterManager.exists(sessionId)) {
                stopSession(sessionId);
                return;
            }
            sseEmitterManager.sendHeartbeat(sessionId);
        }, 15, 15, TimeUnit.SECONDS);
    }

    /** One polling tick. Runs only on the scheduler thread that owns the session's consumer. */
    private void pollOnce(LiveSession session) {
        String sessionId = session.sessionId;
        try {
            if (session.finished) {
                // finishSession() ran before this task's future was assigned — cancel it now.
                session.cancelTasks();
                return;
            }
            if (session.stopRequested.get() || !sseEmitterManager.exists(sessionId)) {
                finishSession(sessionId);
                return;
            }

            if (!session.initialized) {
                // Skip the backlog: seeking on assignment (rather than after a fixed 500ms poll)
                // is what makes this reliable when the group takes longer to stabilize, and it
                // also skips whatever accumulated during a later rebalance — with MB-sized
                // payloads, replaying a backlog would flood the window instantly.
                session.consumer.subscribe(session.topics, new ConsumerRebalanceListener() {
                    @Override
                    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                        if (!partitions.isEmpty()) {
                            session.consumer.seekToEnd(partitions);
                        }
                    }

                    @Override
                    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                        // Nothing to commit: the live consumer never commits offsets.
                    }
                });
                session.initialized = true;
            }

            ConsumerRecords<String, byte[]> records = session.consumer.poll(Duration.ofMillis(500));
            session.consecutiveErrors = 0;   // a successful poll clears the error streak
            ingest(session, records);

            long now = System.currentTimeMillis();
            long windowTimeoutMs = claudeConfig.getSnapshotWindowTimeoutSeconds() * 1000L;
            boolean bufferFull = session.buffer.size() >= claudeConfig.getSnapshotWindowSize();
            boolean timedOut = (now - session.lastAnalysisAt) >= windowTimeoutMs && !session.buffer.isEmpty();

            // Single-flight per session: only start a new analysis when the previous one has
            // finished. This serializes lastFlowchart access, keeps SSE updates ordered, and
            // caps the analysis backlog at one in-flight task (the buffer keeps accumulating —
            // up to the cap — until the in-flight analysis frees the slot).
            if ((bufferFull || timedOut) && session.analysisInFlight.compareAndSet(false, true)) {
                List<PayloadDigest> window = new ArrayList<>(session.buffer);
                session.buffer.clear();
                session.bufferHeapBytes = 0;
                session.lastAnalysisAt = now;
                Map<String, Object> stats = session.cutWindowStats(window, now);

                // Emitted from the poll thread, before the LLM runs: the UI keeps showing
                // ingestion progress even when an analysis takes tens of seconds.
                sseEmitterManager.send(sessionId, "WINDOW_STATS", stats);

                // Run analysis on the dedicated pool: it must never block the poller
                // nor occupy the scheduler threads shared by all sessions
                analysisExecutor.submit(() -> runAnalysis(session, window));
            }

        } catch (WakeupException e) {
            // stopSession() called consumer.wakeup() while we were polling
            finishSession(sessionId);
        } catch (Exception e) {
            // A persistent failure (e.g. broker down) would otherwise log every second forever.
            if (++session.consecutiveErrors >= MAX_CONSECUTIVE_POLL_ERRORS) {
                log.error("Live session {}: {} consecutive polling errors — finishing session. Last error: {}",
                    sessionId, session.consecutiveErrors, e.getMessage(), e);
                finishSession(sessionId);
            } else {
                log.error("Error in live consumer polling for session {}: {}", sessionId, e.getMessage(), e);
            }
        }
    }

    /**
     * Digests every record of the batch and buffers the digests. The raw payload is dropped as
     * soon as its digest is built — a window of a hundred 1 MB documents costs a few hundred KB
     * of heap instead of hundreds of MB.
     */
    private void ingest(LiveSession session, ConsumerRecords<String, byte[]> records) {
        if (records.isEmpty()) {
            return;
        }
        long startedAt = System.currentTimeMillis();
        int digested = 0;

        for (var record : records) {
            byte[] value = record.value();
            int payloadBytes = value == null ? 0 : value.length;
            session.messagesReceived++;
            session.bytesReceived += payloadBytes;
            session.maxPayloadBytes = Math.max(session.maxPayloadBytes, payloadBytes);

            PayloadDigest digest = payloadDigestService.digest(
                record.topic(), record.partition(), record.offset(), record.timestamp(),
                record.key(), value, session.mappedPathsFor(record.topic()));
            if (digest.parseError() != null) {
                session.parseFailures++;
            }
            session.buffer.add(digest);
            session.bufferHeapBytes += digest.estimatedHeapBytes();
            digested++;
        }

        trimBuffer(session);

        long elapsed = System.currentTimeMillis() - startedAt;
        if (elapsed > SLOW_DIGEST_WARN_MS) {
            log.warn("Live session {}: digesting {} record(s) took {} ms — consider lowering "
                + "process-mining.max-poll-records or max-payload-bytes", session.sessionId, digested, elapsed);
        }
    }

    /** Bounds the window by digest count and by estimated heap, dropping the oldest digests. */
    private void trimBuffer(LiveSession session) {
        int windowSize = claudeConfig.getSnapshotWindowSize();
        int maxBuffer = Math.max(windowSize, windowSize * BUFFER_CAP_FACTOR);
        long maxHeap = processMiningConfig.getMaxWindowDigestBytes();

        int size = session.buffer.size();
        long heap = session.bufferHeapBytes;
        int drop = 0;
        while (drop < size && (size - drop > maxBuffer || (heap > maxHeap && size - drop > 1))) {
            heap -= session.buffer.get(drop).estimatedHeapBytes();
            drop++;
        }
        if (drop == 0) {
            return;
        }
        session.buffer.subList(0, drop).clear();
        session.bufferHeapBytes = Math.max(0, heap);
        session.droppedMessages += drop;
        log.warn("Live session {}: window cap exceeded, dropped {} oldest digest(s) — "
            + "the LLM analysis is slower than ingestion", session.sessionId, drop);
    }

    /** Runs on the analysis pool; never touches the consumer. */
    private void runAnalysis(LiveSession session, List<PayloadDigest> window) {
        String sessionId = session.sessionId;
        try {
            var result = llmAnalysisService.analyzeLiveDigests(
                window, session.fieldMapping, session.lastFlowchart, session.auditFocus);

            if (result.flowchart() != null && !result.flowchart().isBlank()
                    && !"NO_CHANGE".equals(result.flowchart())) {
                session.lastFlowchart = result.flowchart();
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

        } catch (Exception e) {
            log.error("Error during live analysis for session {}: {}", sessionId, e.getMessage(), e);
            sseEmitterManager.send(sessionId, "ANALYSIS_ERROR", Map.of(
                "message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        } finally {
            session.analysisInFlight.set(false);
        }
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

        LiveSession session = sessions.get(sessionId);
        if (session == null) {
            // Session unknown (already finished or never started) — just make sure the emitter is closed.
            sseEmitterManager.complete(sessionId);
            return;
        }
        session.stopRequested.set(true);
        session.consumer.wakeup();
    }

    /** Tears the session down from the polling thread: the only place the consumer is closed. */
    private void finishSession(String sessionId) {
        log.info("Stopping live session: {}", sessionId);

        LiveSession session = sessions.remove(sessionId);
        if (session != null) {
            session.finished = true;
            session.cancelTasks();
            try {
                session.consumer.close();
            } catch (Exception e) {
                log.warn("Error closing live consumer for session {}: {}", sessionId, e.getMessage());
            }
        }

        sseEmitterManager.complete(sessionId);
    }

    /** Seam for tests: overridden to inject a MockConsumer instead of a real one. */
    protected Consumer<String, byte[]> createConsumer(Properties props) {
        return new KafkaConsumer<>(props);
    }

    @PreDestroy
    public void shutdown() {
        // Signal every session, then stop the pools. Once the scheduler has terminated no
        // polling task can touch a consumer anymore, so closing the leftovers from this
        // thread is safe (their finishSession tick may never have had a chance to run).
        sessions.keySet().forEach(this::stopSession);
        ShutdownBudget.shutdown(scheduler);
        ShutdownBudget.shutdown(analysisExecutor);
        sessions.forEach((sessionId, session) -> {
            session.cancelTasks();
            try {
                session.consumer.close(Duration.ofSeconds(5));
            } catch (Exception e) {
                log.warn("Error closing live consumer for session {} at shutdown: {}", sessionId, e.getMessage());
            }
            // finishSession() is what normally completes the emitter, and these are exactly
            // the sessions whose polling task never got to run it. Without this the browser
            // is left holding an SSE stream that simply dies with the socket, which the page
            // reads as a network glitch rather than as the server going down.
            sseEmitterManager.complete(sessionId);
        });
        sessions.clear();
    }


    private Properties buildConsumerProps(String sessionId) {
        Properties props = new Properties();
        kafkaConfig.getKafkaProperties().forEach(props::put);
        ExplorerConsumerGroups.configureForSession(props, "live", sessionId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        // Values stay as bytes: the digest is built straight from the wire representation, so a
        // 1 MB document never becomes a ~2 MB Java String.
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,
            String.valueOf(processMiningConfig.getMaxPollRecords()));
        // Defaults (1 MB per partition) are exactly the size of the payloads we target: a record
        // at or above the limit is then fetched alone, one round trip per message. Raise both so
        // large records batch normally.
        props.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG,
            String.valueOf(processMiningConfig.getMaxPartitionFetchBytes()));
        props.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG,
            String.valueOf(processMiningConfig.getFetchMaxBytes()));
        // KIP-848 incremental rebalance protocol, opt-in via kafka.consumer-group-protocol
        // (requires a Kafka 4.x broker; the classic default keeps 3.x broker compatibility)
        if ("consumer".equalsIgnoreCase(kafkaConfig.getConsumerGroupProtocol())) {
            props.put(ConsumerConfig.GROUP_PROTOCOL_CONFIG, "consumer");
        }
        return props;
    }

    /**
     * Per-session state. Every mutable field except {@link #stopRequested}, {@link #analysisInFlight}
     * and {@link #lastFlowchart} is confined to the polling thread; {@code lastFlowchart} is handed
     * between the polling thread and the analysis pool under the single-flight guarantee.
     */
    private final class LiveSession {
        private final String sessionId;
        private final List<String> topics;
        private final FieldMapping fieldMapping;
        private final String auditFocus;
        private final Consumer<String, byte[]> consumer;
        private final Map<String, Set<String>> mappedPathsByTopic = new LinkedHashMap<>();
        private final List<PayloadDigest> buffer = new ArrayList<>();

        private final AtomicBoolean stopRequested = new AtomicBoolean(false);
        private final AtomicBoolean analysisInFlight = new AtomicBoolean(false);

        private volatile ScheduledFuture<?> pollingFuture;
        private volatile ScheduledFuture<?> heartbeatFuture;
        private volatile String lastFlowchart;

        private volatile boolean finished;
        private boolean initialized;
        private int consecutiveErrors;
        private long lastAnalysisAt = System.currentTimeMillis();
        private long bufferHeapBytes;

        // Ingestion counters, reset on every window cut
        private long messagesReceived;
        private long bytesReceived;
        private int maxPayloadBytes;
        private int droppedMessages;
        private int parseFailures;

        LiveSession(String sessionId, List<String> topics, FieldMapping fieldMapping,
                    String auditFocus, Consumer<String, byte[]> consumer) {
            this.sessionId = sessionId;
            this.topics = List.copyOf(topics);
            this.fieldMapping = fieldMapping;
            this.auditFocus = auditFocus;
            this.consumer = consumer;
        }

        Set<String> mappedPathsFor(String topic) {
            return mappedPathsByTopic.computeIfAbsent(topic,
                t -> payloadDigestService.mappedPaths(fieldMapping, t));
        }

        /** Snapshots the ingestion counters for the window being analysed, then resets them. */
        Map<String, Object> cutWindowStats(List<PayloadDigest> window, long now) {
            Set<String> shapes = new LinkedHashSet<>();
            for (PayloadDigest digest : window) {
                if (digest.shapeId() != null) {
                    shapes.add(digest.shapeId());
                }
            }

            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("windowSize", window.size());
            stats.put("timestamp", now);
            stats.put("messagesReceived", messagesReceived);
            stats.put("bytesReceived", bytesReceived);
            stats.put("maxPayloadBytes", maxPayloadBytes);
            stats.put("droppedMessages", droppedMessages);
            stats.put("parseFailures", parseFailures);
            stats.put("distinctShapes", shapes.size());

            messagesReceived = 0;
            bytesReceived = 0;
            maxPayloadBytes = 0;
            droppedMessages = 0;
            parseFailures = 0;
            return stats;
        }

        void cancelTasks() {
            if (pollingFuture != null) {
                pollingFuture.cancel(false);
            }
            if (heartbeatFuture != null) {
                heartbeatFuture.cancel(false);
            }
        }
    }
}
