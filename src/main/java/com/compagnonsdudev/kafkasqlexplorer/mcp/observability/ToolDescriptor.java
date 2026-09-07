// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.observability;

/**
 * One tool as the console shows it — including the tools no agent can see.
 *
 * <p>{@code description} is the agent's description <b>verbatim</b>, not a paraphrase for humans.
 * The operator's question is "what has the model been told this does?", and an editorialised copy
 * answers a different one; it is also the copy that drifts.
 *
 * @param name         the tool name, as an agent would call it
 * @param category     how the console groups it
 * @param description  exactly what the agent is told
 * @param visibility   exposed, exposed-with-approval, or hidden and why
 * @param defaultBudgetMs default time budget, null for a tool with no budget
 * @param hardMaxRecords records ceiling, null when the tool reads none
 * @param hardMaxRows    row ceiling, null when the tool returns none
 * @param hardMaxBytes   output ceiling, always set
 */
public record ToolDescriptor(
        String name,
        ToolCategory category,
        String description,
        Visibility visibility,
        Long defaultBudgetMs,
        Integer hardMaxRecords,
        Integer hardMaxRows,
        int hardMaxBytes
) {

    public ToolDescriptor withVisibility(Visibility visibility) {
        return new ToolDescriptor(name, category, description, visibility,
                defaultBudgetMs, hardMaxRecords, hardMaxRows, hardMaxBytes);
    }
}
