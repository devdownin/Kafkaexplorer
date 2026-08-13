// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

/**
 * Severity of an audit finding, and by extension of a topic — a topic takes the worst severity
 * among its issues.
 *
 * <p>This used to be a binary HEALTHY / UNHEALTHY. On a real cluster that made the verdict
 * useless: a topic with one duplicate key out of ten thousand messages and a topic whose audit
 * could not run at all both came back UNHEALTHY, so the "unhealthy topics" count and the health
 * score were noise. Declared in increasing order of severity — {@link #max} relies on it.
 */
public enum HealthStatus {
    /** No finding. */
    HEALTHY,
    /** Worth looking at: duplicate keys, a check that could not run, a degraded metric. */
    WARNING,
    /** Bad data, or no verdict at all: malformed payloads, a topic that failed to audit. */
    CRITICAL;

    /** The worse of the two severities. */
    public static HealthStatus max(HealthStatus a, HealthStatus b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    public boolean atLeast(HealthStatus other) {
        return ordinal() >= other.ordinal();
    }
}
