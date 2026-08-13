// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.web;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.StreamFlowProgress;
import com.compagnonsdudev.kafkasqlexplorer.domain.StreamFlowRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.StreamFlowResponse;
import com.compagnonsdudev.kafkasqlexplorer.service.StreamFlowService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stream-flow tracing API.
 *
 * <p>There is deliberately no {@code GET /stream-flow} mapping: {@code /stream-flow} is a
 * client-side route served by {@link SpaController}, and a controller mapping on it returned the
 * view name {@code "stream-flow"} to an application that has no template engine — refreshing the
 * page answered 500 (circular view path) instead of loading the SPA. Same rule as {@code /audit}.
 */
@RestController
@RequestMapping("/api/stream-flow")
public class StreamFlowController {

    private static final Logger log = LoggerFactory.getLogger(StreamFlowController.class);

    /** Head-room over the trace's own budget, so the emitter never times out before the scan ends. */
    private static final long EMITTER_GRACE_MS = 30_000;

    private final StreamFlowService streamFlowService;
    private final ExplorerConfig explorerConfig;
    /**
     * Drives the streamed traces. A dedicated pool, never the service's topic workers: a driver
     * waits on the topic tasks, so drivers occupying those threads would starve the very work they
     * are waiting for.
     */
    private final ExecutorService traceRunners;

    public StreamFlowController(StreamFlowService streamFlowService, ExplorerConfig explorerConfig) {
        this.streamFlowService = streamFlowService;
        this.explorerConfig = explorerConfig;
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "stream-flow-sse-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.traceRunners = Executors.newFixedThreadPool(4, factory);
    }

    @PreDestroy
    public void shutdown() {
        traceRunners.shutdownNow();
    }

    @PostMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<?> getStreamFlow(@RequestBody StreamFlowRequest request) {
        try {
            return ResponseEntity.ok(streamFlowService.getStreamFlow(request));
        } catch (IllegalArgumentException e) {
            // Invalid regex, malformed search path, missing key: the caller can fix it, so the
            // reason travels in the body. An explicit payload rather than a ResponseStatusException
            // because server.error.include-message defaults to "never" — the framework's own error
            // body would reach the UI as a bare "Bad Request".
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Same trace, streamed: {@code progress} after every topic, {@code flow} whenever a topic adds
     * to the graph, {@code result} at the end. A whole-cluster trace takes a minute, and a minute of
     * spinner is a minute during which the operator cannot see that the first three hops are already
     * there — nor decide that is enough and stop.
     *
     * <p>Hanging up is not just an HTTP concern here: the disconnect raises the cancel flag the
     * trace polls between topics, so the scan really stops instead of reading a cluster nobody is
     * watching any more.
     */
    @PostMapping(value = "/stream", consumes = "application/json")
    public ResponseEntity<?> stream(@RequestBody StreamFlowRequest request) {
        try {
            // Before the stream opens: an unusable criterion deserves a plain 400 with its reason,
            // not an event stream whose first act is to close itself.
            streamFlowService.validateCriterion(request);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }

        SseEmitter emitter = new SseEmitter(explorerConfig.getStreamFlowTimeoutMs() + EMITTER_GRACE_MS);
        AtomicBoolean cancelled = new AtomicBoolean();
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onError(error -> cancelled.set(true));
        emitter.onTimeout(() -> {
            cancelled.set(true);
            emitter.complete();
        });

        traceRunners.execute(() -> {
            try {
                StreamFlowResponse result = streamFlowService.trace(request,
                    new StreamFlowService.TraceListener() {
                        @Override
                        public void onProgress(StreamFlowProgress progress) {
                            send(emitter, cancelled, "progress", progress);
                        }

                        @Override
                        public void onPartialFlow(StreamFlowResponse flow) {
                            send(emitter, cancelled, "flow", flow);
                        }
                    },
                    cancelled::get);
                send(emitter, cancelled, "result", result);
                emitter.complete();
            } catch (IllegalArgumentException e) {
                // The criterion was validated above, so this is a late failure; the stream is
                // already open and a status code is no longer available.
                send(emitter, cancelled, "failed", Map.of("message", String.valueOf(e.getMessage())));
                emitter.complete();
            } catch (Exception e) {
                log.error("Streamed stream-flow trace failed", e);
                send(emitter, cancelled, "failed",
                    Map.of("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                emitter.complete();
            }
        });

        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(emitter);
    }

    /**
     * A send that fails means the client is gone — that is the normal end of a cancelled trace, so
     * it raises the cancel flag instead of being logged as an error.
     */
    private void send(SseEmitter emitter, AtomicBoolean cancelled, String event, Object payload) {
        if (cancelled.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(payload, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.debug("Stream-flow client disconnected during '{}': {}", event, e.getMessage());
            cancelled.set(true);
        }
    }

    /** Visible for tests: how long the runners are given to drain on shutdown. */
    boolean awaitRunners(long timeoutMs) throws InterruptedException {
        return traceRunners.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
    }
}
