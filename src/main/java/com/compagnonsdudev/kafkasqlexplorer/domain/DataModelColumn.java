// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

/**
 * One column of a data-model entity: an inferred field with its Flink SQL type. Nested paths
 * keep their dot notation ({@code customer.address.city}) — flattening them would hide where
 * a field actually lives.
 *
 * @param name       the field path as inferred from the payloads
 * @param type       the merged Flink SQL type ({@code STRING}, {@code BIGINT}, …)
 * @param primaryKey true when this column was detected as the entity's identifying key
 * @param references id of the entity this column points at, when a relation was deduced from
 *                   it; {@code null} for an ordinary column
 */
public record DataModelColumn(
        String name,
        String type,
        boolean primaryKey,
        String references
) {
}
