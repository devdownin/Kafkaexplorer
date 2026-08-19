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
 * @param keyBase    the entity word this column's name points at ({@code order_id} →
 *                   {@code order}), or {@code null} when the name does not point at another
 *                   entity — either it does not read as an identifier at all ({@code amount},
 *                   and {@code paid} or {@code valid}, which merely end in "id"), or it names
 *                   <em>this</em> entity ({@code id}, or {@code order_id} on an orders topic),
 *                   which is identity rather than reference.
 *                   <p>
 *                   This is the same rule {@link
 *                   com.compagnonsdudev.kafkasqlexplorer.service.DataModelService#deduceRelations}
 *                   applies before looking for a target, exposed so the UI can mark a column
 *                   that reads as a foreign key but produced no relation. It is carried rather
 *                   than re-derived in the browser: the heuristic has three parts that must
 *                   agree, and a second copy of it drifts.
 *                   <p>
 *                   A non-null {@code keyBase} with a null {@code references} therefore means
 *                   exactly one thing — no selected topic carries that name — because a target
 *                   present in the model always yields a relation.
 */
public record DataModelColumn(
        String name,
        String type,
        boolean primaryKey,
        String references,
        String keyBase
) {
}
