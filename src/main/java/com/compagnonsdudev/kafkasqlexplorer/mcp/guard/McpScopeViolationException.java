// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.guard;

import java.util.List;

/**
 * A call naming a resource outside {@code explorer.mcp.allowed-*-prefixes}.
 *
 * <p>The offending names are echoed back, and that is a deliberate call rather than an oversight:
 * they came from the caller, so repeating them discloses nothing it did not already have, and
 * withholding them turns a fixable mistake ("prod.payments is not in scope") into a bare -32041
 * that an agent can only respond to by guessing.
 */
public class McpScopeViolationException extends McpToolException {

    private final List<String> offending;

    public McpScopeViolationException(String kind, List<String> offending, List<String> allowedPrefixes) {
        super(McpErrorCode.OUT_OF_SCOPE, McpGuard.SCOPE,
                "%s outside the configured scope: %s. Allowed prefixes: %s."
                        .formatted(kind, String.join(", ", offending), String.join(", ", allowedPrefixes)));
        this.offending = List.copyOf(offending);
    }

    public List<String> offending() { return offending; }
}
