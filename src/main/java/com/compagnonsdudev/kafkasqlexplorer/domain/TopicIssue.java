// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

/**
 * One audit finding on a topic, carrying its own severity.
 *
 * <p>Issues used to be bare strings, so the UI painted every one of them error-red and a topic's
 * status could not distinguish "one duplicate key" from "this topic could not be read at all".
 *
 * @param message   operator-facing description, including the scope the verdict is based on
 * @param severity  {@link HealthStatus#WARNING} or {@link HealthStatus#CRITICAL} — an issue is by
 *                  definition not HEALTHY
 */
public record TopicIssue(String message, HealthStatus severity) {

    public TopicIssue {
        if (severity == null || severity == HealthStatus.HEALTHY) {
            throw new IllegalArgumentException("An issue must be WARNING or CRITICAL, got " + severity);
        }
    }

    public static TopicIssue warning(String message) {
        return new TopicIssue(message, HealthStatus.WARNING);
    }

    public static TopicIssue critical(String message) {
        return new TopicIssue(message, HealthStatus.CRITICAL);
    }
}
