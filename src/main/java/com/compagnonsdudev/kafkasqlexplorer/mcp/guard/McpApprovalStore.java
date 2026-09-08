// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.guard;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Approval tokens: a human said yes to <em>this</em> tool, once — KIP-1318's step 7,
 * {@code -32042}.
 *
 * <p>{@code explorer.mcp.approval-required-tools} shipped for three phases naming three tools and
 * enforced by nothing; the catalogue said so in as many words, which was honest and still left the
 * setting unable to stop anything. This makes it real, and on any tool an operator lists rather
 * than only the mutating ones — a read can be the sensitive gesture on a cluster whose payloads are
 * regulated, and hard-coding the list to the write surface would deny that operator the control.
 *
 * <p>Four properties, each of which is the answer to a way an approval can be defeated:
 *
 * <ul>
 *   <li><b>Single use.</b> A token that survives its call is a standing permission with an
 *       approval's paperwork. Taken means spent, whether the call then succeeded or failed.</li>
 *   <li><b>Bound to one tool.</b> Otherwise approving a preview approves a produce, and the
 *       operator who clicked has approved something they were never shown.</li>
 *   <li><b>Short-lived.</b> Fifteen minutes: long enough for a human to read what they are
 *       approving, short enough that a token found in a log later is worthless.</li>
 *   <li><b>Unguessable and compared in constant time.</b> A token is a bearer credential; an
 *       equality that returns early leaks its prefix to anyone who can time it.</li>
 * </ul>
 *
 * <p>In memory and per process, like every other control here: a restart voids outstanding
 * approvals, which is the safe direction.
 */
public class McpApprovalStore {

    /** How long a minted token stays usable. */
    public static final Duration TTL = Duration.ofMinutes(15);

    /** Past this many outstanding tokens the oldest is dropped — a mint loop is not a memory leak. */
    static final int MAX_OUTSTANDING = 100;

    private record Grant(String tool, String actor, Instant mintedAt) {}

    private final McpProperties properties;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Grant> outstanding = new LinkedHashMap<>();

    public McpApprovalStore(McpProperties properties) {
        this.properties = properties;
    }

    /** True when this tool may not be called without a token. */
    public boolean requiresApproval(String tool) {
        return tool != null && properties.getApprovalRequiredTools().contains(tool);
    }

    /** Mints a token for one tool. The caller — the console — has already shown a human what for. */
    public synchronized String mint(String tool, String actor) {
        evictExpired();
        while (outstanding.size() >= MAX_OUTSTANDING) {
            outstanding.remove(outstanding.keySet().iterator().next());
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        outstanding.put(token, new Grant(tool, actor, Instant.now()));
        return token;
    }

    /**
     * Spends a token for a tool, or refuses.
     *
     * <p>The refusal never says which of the three ways it failed — unknown, expired, or minted for
     * another tool. Distinguishing them tells a caller holding a stolen token which part of it to
     * change, and the caller who holds a legitimate one has the same thing to do in all three
     * cases: ask a human again.
     */
    public synchronized void spend(String tool, String presented) {
        if (!requiresApproval(tool)) {
            return;
        }
        evictExpired();
        if (presented == null || presented.isBlank()) {
            throw refusal(tool, "no approval token was presented");
        }
        String matched = findConstantTime(presented);
        if (matched == null || !outstanding.get(matched).tool().equals(tool)) {
            // Spent anyway when it matched: a token offered for the wrong tool has been in the
            // wrong hands or the wrong code path, and either way it should not survive to be
            // offered again for the right one.
            if (matched != null) {
                outstanding.remove(matched);
            }
            throw refusal(tool, "this approval token is not usable");
        }
        outstanding.remove(matched);
    }

    /** How many approvals are outstanding — the console shows it beside the tool list. */
    public synchronized int outstandingCount() {
        evictExpired();
        return outstanding.size();
    }

    private McpToolException refusal(String tool, String what) {
        return new McpToolException(McpErrorCode.APPROVAL_REQUIRED, McpGuard.APPROVAL,
                ("%s: %s. This tool is listed in explorer.mcp.approval-required-tools, so a human "
                        + "has to approve each call from the MCP console, which mints a token good "
                        + "for one call of this tool within %d minutes.")
                        .formatted(tool, what, TTL.toMinutes()));
    }

    /**
     * Looks a token up without letting the comparison's duration say how close a guess was.
     *
     * <p>A map lookup would answer in a time that depends on the hash, and an equality that returns
     * on the first differing byte leaks the prefix. Every outstanding token is compared, all the
     * way through, and the match is remembered rather than returned early.
     */
    private String findConstantTime(String presented) {
        String matched = null;
        for (String candidate : outstanding.keySet()) {
            if (java.security.MessageDigest.isEqual(
                    candidate.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    presented.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                matched = candidate;
            }
        }
        return matched;
    }

    private void evictExpired() {
        Instant cutoff = Instant.now().minus(TTL);
        outstanding.entrySet().removeIf(entry -> entry.getValue().mintedAt().isBefore(cutoff));
    }
}
