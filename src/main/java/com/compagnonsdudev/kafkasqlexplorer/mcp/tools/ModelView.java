// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Measured;

import java.util.List;

/** What {@code kex_deduce_data_model} and {@code kex_build_join} return. */
public final class ModelView {

    private ModelView() {
    }

    /**
     * One column of one entity.
     *
     * <p>{@code references} is the FK-like target when one was found. A column that <em>looks</em>
     * like a foreign key and resolves to nothing keeps {@code referencesUnresolved} — the spec's
     * {@code ?} — because "points at orders" and "is named like something that would point
     * somewhere, and points nowhere we can see" are different facts, and only the first is a
     * relation. Dropping the distinction would let a model claim a link it never found.
     */
    public record Column(
            String name,
            String type,
            boolean primaryKey,
            String references,
            boolean referencesUnresolved
    ) {
    }

    /**
     * One topic, read as a table.
     *
     * @param id           the registered table name — the topic's name, which is what a join cites
     * @param topic        the Kafka topic behind it
     * @param format       JSON / XML / AVRO, as inference resolved it
     * @param columns      inferred columns, in the order inference produced them
     * @param primaryKey   the detected key, {@code null} when none was
     * @param messageCount records in the topic, unmeasured when the broker did not answer
     */
    public record Entity(
            String id,
            String topic,
            String format,
            List<Column> columns,
            String primaryKey,
            Measured<Long> messageCount
    ) {
    }

    /**
     * A deduced link, with the evidence that deduced it.
     *
     * <p>{@code confidence} and {@code reason} travel together on purpose: {@code MEDIUM} means the
     * names agree and nothing else does, and a model told only "there is a relation" will write a
     * join on it as readily as on a {@code HIGH}. The reason is the sentence that stops it.
     */
    public record Relation(
            String from,
            String to,
            String fromColumn,
            String toColumn,
            String confidence,
            String reason
    ) {
    }

    /**
     * The deduced model.
     *
     * @param entities  one per topic analysed
     * @param relations the links between them, strongest first as the service ordered them
     * @param mermaid   the same model as a Mermaid {@code erDiagram} — the form that can be diffed
     */
    public record Model(
            List<Entity> entities,
            List<Relation> relations,
            String mermaid
    ) {
    }

    /**
     * A join over several entities, or the reason there is none.
     *
     * <p>{@code sql} is {@code null} exactly when {@code problem} is set. The refusal is the point:
     * a set of entities the deduced relations do not connect has no join, and inventing a predicate
     * would assert an equality nothing supports.
     *
     * @param sql      the query, {@code null} when none could be written
     * @param caveats  what the query assumes and a caller has to check before running it
     * @param problem  why there is no query, {@code null} when there is one
     */
    public record Join(String sql, List<String> caveats, String problem) {
    }
}
