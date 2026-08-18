// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;

/**
 * One node of the data model: a Kafka topic read as a table. The id is the sanitized table
 * name (what Flink SQL would call it), the topic keeps its real name, and the columns are the
 * schema inferred from a bounded sample of messages.
 *
 * @param id           stable node id — the Flink table name derived from the topic
 * @param topic        the Kafka topic the entity was inferred from
 * @param format       the payload format the sample exhibited
 * @param columns      inferred columns in deterministic order
 * @param primaryKey   name of the detected key column, or {@code null} when none was found —
 *                     never an invented one
 * @param messageCount estimated record count of the topic, or {@code null} when it could not
 *                     be read (a zero would claim an empty topic)
 */
public record DataModelEntity(
        String id,
        String topic,
        MessageFormat format,
        List<DataModelColumn> columns,
        String primaryKey,
        Long messageCount
) {
}
