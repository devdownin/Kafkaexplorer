// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

/**
 * {@code POST /api/assistant/metric}.
 *
 * @param description what the metric should measure, in natural language
 * @param topic       the topic or table it reads, when the user named one
 */
public record AssistantMetricRequest(
    String description,
    String topic
) {
}
