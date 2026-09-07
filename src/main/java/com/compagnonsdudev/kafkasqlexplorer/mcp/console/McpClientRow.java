// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.console;

import java.time.Instant;

/**
 * One caller seen in the window.
 *
 * <p><b>Not a session.</b> The HTTP transport is stateless by design, so there is no server-side
 * session to report; this row is a reconstruction from the identity and the {@code client_info}
 * declared at {@code initialize}, grouped by the console. The page says so in a tooltip rather than
 * letting an operator believe in a session tracker that does not exist — the same rule as every
 * other number here, applied to the one that would be easiest to fake.
 *
 * @param identity   OAuth subject once phase 5 lands; until then the transport session, prefixed
 *                   so it cannot be mistaken for an authenticated principal
 * @param clientInfo what the client called itself, e.g. {@code claude-code/1.4.2}
 * @param calls      calls in the window
 * @param denied     of which refused
 * @param lastSeenAt most recent call
 */
public record McpClientRow(
        String identity,
        String clientInfo,
        long calls,
        long denied,
        Instant lastSeenAt
) {}
