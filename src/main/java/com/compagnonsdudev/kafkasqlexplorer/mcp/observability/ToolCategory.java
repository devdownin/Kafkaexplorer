// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.observability;

/**
 * How the console groups the tool table. Four rows of meaning rather than one alphabetical list:
 * the question "can this agent write?" has to be answerable at a glance, and it is the grouping
 * that makes {@link #WRITE} impossible to miss.
 */
public enum ToolCategory {
    EXPLORATION, CORRELATION, DIAGNOSTIC, WRITE
}
