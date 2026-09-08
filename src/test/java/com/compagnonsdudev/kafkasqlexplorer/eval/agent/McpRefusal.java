// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval.agent;

/**
 * The KIP-1318 refusal codes this harness reasons about.
 *
 * <p>Named here rather than read from {@code McpErrorCode} deliberately, and it is the one
 * duplication in this package. The harness is a third-party client: it sees the wire, and a code
 * that changed on the server should make a scenario fail loudly rather than follow the change and
 * keep passing. Importing the server's enum would make the two agree by construction, which is
 * exactly what a conformance harness must not do.
 *
 * <p>The numbers are KIP-1318's rather than this application's, which is what makes the
 * duplication safe to hold: they are a published contract both sides are conforming to, not a
 * private numbering one side owns.
 */
final class McpRefusal {

    /** The caller asked outside the topics or groups the deployment allows. */
    static final int OUT_OF_SCOPE = -32041;
    /** The tool needs an approval token this call did not carry. */
    static final int APPROVAL_REQUIRED = -32042;
    /** Something the tool depends on was unreachable — not a guard, and not the agent's to loosen. */
    static final int DEPENDENCY_UNAVAILABLE = -32043;
    /** Policy: the tool is denied, or an operator switched it off mid-session. */
    static final int POLICY_DENIED = -32044;
    /** Too many calls for this identity; the refusal names how long to wait. */
    static final int RATE_LIMITED = -32029;

    private McpRefusal() {
    }

    /**
     * The code and what it means, for a failure sentence.
     *
     * <p>A report that prints {@code -32041} alone makes the reader look the number up, which on a
     * red run is the moment they are least inclined to. An unknown code is reported as unknown
     * rather than guessed at — this harness reads a wire it does not own.
     */
    static String describe(int code) {
        return switch (code) {
            case OUT_OF_SCOPE -> code + " (outside the configured resource scope)";
            case APPROVAL_REQUIRED -> code + " (an approval token is required)";
            case DEPENDENCY_UNAVAILABLE -> code + " (a dependency is unavailable)";
            case POLICY_DENIED -> code + " (denied by policy, or switched off mid-session)";
            case RATE_LIMITED -> code + " (rate limit exceeded)";
            default -> code + " (a code this harness does not know)";
        };
    }
}
