// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.contract;

import java.util.List;

/**
 * What every MCP tool returns: the answer, and the account of how it was obtained.
 *
 * <p>{@code coverage} is not optional and has no empty form. A tool that cannot say what it read
 * has no business answering an agent — that is the whole contract of this module, and making the
 * field mandatory is what keeps it from being skipped under deadline.
 *
 * @param data      the tool's own payload
 * @param coverage  what was read and what was not — see {@link Coverage}
 * @param warnings  caveats that degraded the result without emptying it
 * @param truncated true when a server ceiling cut the payload; the ceiling is named in a warning
 */
public record ToolResult<T>(T data, Coverage coverage, List<Warning> warnings, boolean truncated) {

    public ToolResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static <T> ToolResult<T> complete(T data, Coverage coverage) {
        return new ToolResult<>(data, coverage, List.of(), false);
    }

    public static <T> ToolResult<T> of(T data, Coverage coverage, List<Warning> warnings) {
        return new ToolResult<>(data, coverage, warnings, false);
    }
}
