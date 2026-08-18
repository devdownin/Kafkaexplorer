// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;

/**
 * The deduced data model, with the bounds of what was actually read. {@code topicsRequested}
 * vs {@code topicsAnalyzed} plus {@code warnings} is what keeps an incomplete model from
 * passing for a complete one: a topic that yielded no schema, a topic past the cap or a topic
 * whose read failed is named, never silently absent from the graph.
 *
 * @param entities        one per topic that yielded a schema
 * @param relations       deduced relations, deterministically ordered
 * @param warnings        everything that degraded the model, in the words of the check that hit it
 * @param topicsRequested how many topics the request named
 * @param topicsAnalyzed  how many were actually read
 * @param truncated       true when the request exceeded the per-request topic cap
 */
public record DataModelResponse(
        List<DataModelEntity> entities,
        List<DataModelRelation> relations,
        List<String> warnings,
        int topicsRequested,
        int topicsAnalyzed,
        boolean truncated
) {
}
