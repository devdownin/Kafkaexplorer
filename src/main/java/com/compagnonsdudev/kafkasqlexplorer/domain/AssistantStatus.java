// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

/**
 * Whether the SQL assistant can be offered, and if not, why — {@code GET /api/assistant/status}.
 *
 * <p>{@code localDeployment} is {@code ClaudeConfig.isLocalDeployment()} read off the assistant's
 * <em>effective</em> endpoint: a claim about where table and column names go is made from the
 * address they are sent to, never from the provider's name.
 *
 * @param enabled         {@code explorer.assistant.enabled}
 * @param problem         why the assistant cannot answer, or {@code null} when it can
 * @param provider        the provider label the assistant will call
 * @param model           the model it will ask for
 * @param localDeployment whether that endpoint is a loopback address
 */
public record AssistantStatus(
    boolean enabled,
    String problem,
    String provider,
    String model,
    boolean localDeployment
) {
}
