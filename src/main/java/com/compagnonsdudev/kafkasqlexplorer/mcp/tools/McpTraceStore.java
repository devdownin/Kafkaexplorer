// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.domain.StreamFlowCoverage;
import com.compagnonsdudev.kafkasqlexplorer.domain.StreamFlowHit;
import com.compagnonsdudev.kafkasqlexplorer.domain.StreamFlowRequest;

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
 */
public class McpTraceStore {

    /** How long a token stays usable. An agent resumes within a turn or it has moved on. */
    static final Duration TTL = Duration.ofMinutes(15);

    /** How many paused traces are held. Past this the oldest goes, and its token says so. */
    static final int MAX_ENTRIES = 50;

    /**
     * A trace that stopped before it had read everything.
     *
     * @param request       the original request, so the criterion is reused verbatim rather than
     *                      rebuilt from a description of itself
     * @param remainingTopics what was never read, in the order it would have been
     * @param hits          what has been found so far, to be merged into the continued chain
     * @param coverage      what the earlier passes already covered, so the sums describe the whole
     *                      trace rather than only its last leg
     * @param createdAt     for the TTL
     */
    public record PausedTrace(
            StreamFlowRequest request,
            List<String> remainingTopics,
            List<StreamFlowHit> hits,
            StreamFlowCoverage coverage,
            Instant createdAt
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
                coverage, Instant.now()));
        return token;
    }

    /**
     * The paused trace for a token, or empty when it is unknown or has expired.
     *
     * <p>The two are deliberately one answer. Distinguishing them would tell a caller whether a
     * token it invented ever existed, and the caller has nothing to do differently either way: the
     * trace has to be run again from the start.
     */
    public synchronized Optional<PausedTrace> take(String token) {
        evictExpired();
        return Optional.ofNullable(paused.remove(token));
    }

    private void evictExpired() {
        Instant cutoff = Instant.now().minus(TTL);
        paused.entrySet().removeIf(entry -> entry.getValue().createdAt().isBefore(cutoff));
    }

    synchronized int size() {
        return paused.size();
    }
}
