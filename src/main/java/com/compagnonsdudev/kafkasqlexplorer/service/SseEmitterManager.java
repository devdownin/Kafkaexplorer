// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class SseEmitterManager {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterManager.class);

    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * How long one live session's stream may stay open. This is a <em>total</em> lifetime, not an
     * idle timeout — the 15 s heartbeat does not extend it — so the previous five minutes meant
     * every Process Mining live session died after five minutes whatever it was doing. Nothing
     * looked broken, because the browser's EventSource silently reconnected and the endpoint minted
     * a fresh session (new consumer, new group member, flowchart history lost). Long-running
     * monitoring is the feature; the timeout is only a backstop against an emitter whose client
     * vanished without the socket noticing, and the heartbeat already catches that within 15 s.
     */
    private static final long SESSION_TIMEOUT_MS = 12 * 60 * 60 * 1000L;

    public SseEmitter create(String sessionId) {
        SseEmitter emitter = new SseEmitter(SESSION_TIMEOUT_MS);

        emitter.onCompletion(() -> {
            log.debug("SSE emitter completed for session: {}", sessionId);
            emitters.remove(sessionId);
        });
        emitter.onTimeout(() -> {
            log.debug("SSE emitter timed out for session: {}", sessionId);
            emitters.remove(sessionId);
        });
        emitter.onError(ex -> {
            log.debug("SSE emitter error for session {}: {}", sessionId, ex.getMessage());
            emitters.remove(sessionId);
        });

        emitters.put(sessionId, emitter);
        return emitter;
    }

    public void send(String sessionId, String eventType, Object data) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter == null) {
            log.debug("No emitter found for session: {}", sessionId);
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(data);
            emitter.send(SseEmitter.event()
                .name(eventType)
                .data(json));
        } catch (Exception e) {
            log.warn("Failed to send SSE event {} to session {}: {}", eventType, sessionId, e.getMessage());
            emitters.remove(sessionId);
        }
    }

    public void sendHeartbeat(String sessionId) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event()
                .name("HEARTBEAT")
                .data("ping"));
        } catch (Exception e) {
            log.warn("Failed to send heartbeat to session {}: {}", sessionId, e.getMessage());
            emitters.remove(sessionId);
        }
    }

    public void complete(String sessionId) {
        SseEmitter emitter = emitters.remove(sessionId);
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Exception e) {
                log.warn("Error completing emitter for session {}: {}", sessionId, e.getMessage());
            }
        }
    }

    /**
     * How many live sessions are currently attached. Read by the settings endpoint, which refuses
     * to repoint the cluster out from under a running Process Mining session without being told to.
     */
    public int activeSessions() {
        return emitters.size();
    }

    public boolean exists(String sessionId) {
        return emitters.containsKey(sessionId);
    }
}
