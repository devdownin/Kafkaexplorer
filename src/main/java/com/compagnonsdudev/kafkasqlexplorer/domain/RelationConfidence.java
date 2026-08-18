// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

/**
 * How much evidence backs a deduced relation. A relation is never observed — Kafka has no
 * foreign keys — so every edge of the data model is a claim, and the claim carries its grade:
 * {@code HIGH} when the referencing column name and the referenced entity's own key agree,
 * {@code MEDIUM} when only the entity name matched, {@code LOW} when the link rests on a
 * shared column name alone.
 */
public enum RelationConfidence {
    HIGH, MEDIUM, LOW
}
