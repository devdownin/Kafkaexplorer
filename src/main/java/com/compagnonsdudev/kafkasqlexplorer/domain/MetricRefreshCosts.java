// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.Map;

/**
 * The refresh loop's tick and what each metric's last refresh cost. The loop is single threaded by
 * design, so a slow metric delays every other one; this names it, and the remedy — a
 * {@code refreshIntervalMs} of its own — is per metric too. A metric not refreshed yet is absent.
 */
public record MetricRefreshCosts(long tickMs, Map<String, Cost> metrics) {
    public MetricRefreshCosts {
        metrics = Map.copyOf(metrics);
    }

    /** One metric's last refresh duration, and how often it is collected. */
    public record Cost(long lastDurationMs, long collectionIntervalMs) {}
}
