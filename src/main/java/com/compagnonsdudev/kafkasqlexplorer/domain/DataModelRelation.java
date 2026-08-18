// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

/**
 * A deduced relation between two entities: {@code from}'s column looks like a reference to
 * {@code to}. Every relation states the evidence it rests on ({@code reason}) and how strong
 * that evidence is ({@code confidence}) — an edge nobody can trace back to an observation is
 * a guess drawn as a fact.
 *
 * @param from       id of the referencing entity
 * @param to         id of the referenced entity
 * @param fromColumn the column in {@code from} that carries the reference
 * @param toColumn   the column in {@code to} it resolves to, or {@code null} when the target
 *                   entity has no matching key column
 * @param confidence grade of the evidence
 * @param reason     human-readable statement of what matched
 */
public record DataModelRelation(
        String from,
        String to,
        String fromColumn,
        String toColumn,
        RelationConfidence confidence,
        String reason
) {
}
