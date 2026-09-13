// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.domain.StreamFlowCoverage;
import com.compagnonsdudev.kafkasqlexplorer.domain.StreamFlowHit;
import com.compagnonsdudev.kafkasqlexplorer.domain.StreamFlowRequest;
import com.compagnonsdudev.kafkasqlexplorer.mcp.security.McpCallerContext;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Traces a caller may continue, held between two calls.
 *
 * <p><b>Server-side, and that is a considered reversal of this module's stateless preference.</b>
 * The alternative was to encode the paused trace into the token itself, as the UI does — it hands
 * its own prior hits back in the next request, precisely so the server keeps no session. That works
 * for a browser and fails for an agent: the hits of a wide trace are hundreds of records, and
 * pushing them through a model's context to get them back costs the caller more than the trace did,
 * with a truncated resume as the failure mode. A bounded map is smaller, and its one weakness —
 * that a token can expire — is one the caller can be told about.
 *
 * <p>So it is told. An unknown token is answered as unknown, never as an empty second pass: a
 * resume that silently starts over would report "nothing more found" about topics it never read,
 * which is the sentence this whole module exists to prevent.
 *
 * <p>Bounded by count and by age, on the same reasoning as the call ring. A paused trace nobody
 * resumed is not worth a megabyte an hour later, and an agent that abandons one abandons it within
 * seconds.
 *
 * <p>Every paused trace is also bound to the authenticated MCP caller that created it. The bearer
 * token itself never enters the token or the audit record; only its one-way identity fingerprint is
 * retained. A different principal therefore cannot redeem a valid-looking token from another
 * session.
 */
public class McpTraceStore {

    /** How long a token stays usable. An agent resumes within a turn or it has moved on. */
    static final Duration TTL = Duration.ofMinutes(15);

    /** How many paused traces are held. Past this the oldest goes, and its token says so. */
    static final int MAX_ENTRIES = 50;

    /**
     * A trace that stopped before it had read everything.
     *
     * @param request         the original request
     * @param remainingTopics what was never read, in the order it would have been
     * @param hits            what has been found so far
     * @param coverage        what the earlier passes already covered
     * @param createdAt       for the TTL
     * @param ownerIdentity   one-way identity of the caller that created the token
     */
    public record PausedTrace(
            StreamFlowRequest request,
            List<String> remainingTopics,
            List<StreamFlowHit> hits,
            StreamFlowCoverage coverage,
            Instant createdAt,
            String ownerIdentity
    ) {}

    private final Map<String, PausedTrace> paused = new LinkedHashMap<>();

    /** Keeps a paused trace and returns the token that continues it. */
    public synchronized String remember(StreamFlowRequest request, List<String> remainingTopics,
                                        List<StreamFlowHit> hits, StreamFlowCoverage coverage) {
        evictExpired();
        while (paused.size() >= MAX_ENTRIES) {
            paused.remove(paused.keySet().iterator().next());
        }
        String token = "rt-" + UUID.randomUUID().toString().substring(0, 8);
        paused.put(token, new PausedTrace(request, List.copyOf(remainingTopics), List.copyOf(hits),
                coverage, Instant.now(), McpCallerContext.identity()));
        return token;
    }

    /**
     * Returns and consumes a token only for the caller that created it.
     *
     * <p>Unknown, expired and cross-principal tokens intentionally have the same externally visible
     * result. A failed ownership check must not reveal whether an attacker guessed a token that
     * belongs to somebody else.
     */
    public synchronized Optional<PausedTrace> take(String token) {
        evictExpired();
        PausedTrace trace = paused.get(token);
        if (trace == null || !trace.ownerIdentity().equals(McpCallerContext.identity())) {
            return Optional.empty();
        }
        paused.remove(token);
        return Optional.of(trace);
    }

    private void evictExpired() {
        Instant cutoff = Instant.now().minus(TTL);
        paused.entrySet().removeIf(entry -> entry.getValue().createdAt().isBefore(cutoff));
    }

    synchronized int size() {
        return paused.size();
    }
}
