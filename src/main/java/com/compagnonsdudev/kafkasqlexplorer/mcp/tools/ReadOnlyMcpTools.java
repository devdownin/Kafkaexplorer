// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

/**
 * A toolset that only reads. Registered whatever {@code explorer.mcp.readonly} says.
 *
 * <p>"Only reads" is about the user's cluster, and it is meant literally: a tool here may not
 * produce, alter a topic, register a table or change a setting. Sampling records and running a
 * whitelisted {@code SELECT} are reads even though they cost the cluster something; the ceilings
 * in {@code ToolGuard} are what bound that cost.
 */
public interface ReadOnlyMcpTools extends McpToolset {
}
