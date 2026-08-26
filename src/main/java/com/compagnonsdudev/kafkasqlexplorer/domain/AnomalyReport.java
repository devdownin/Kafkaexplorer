// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;

/**
 * One anomaly the model reported.
 *
 * <p>{@code probableCause} and {@code sqlSuggestion} are nullable, and that is the correction: the
 * schema marked every field required under strict decoding, so the model could not omit either —
 * it had to invent a cause and a statement for every anomaly, in the two fields an operator is most
 * likely to act on.
 *
 * <p>The field is {@code sqlSuggestion} rather than {@code ksqlSuggestion} because this application
 * runs <strong>Flink SQL</strong>: {@code FlinkSqlService.executeSql} whitelists SELECT, EXPLAIN and
 * CREATE TABLE, so the {@code CREATE STREAM} the prompt used to teach by example was a statement the
 * engine refuses — rendered in a monospace block that invited pasting it into the editor. Same
 * defect the Help page was rewritten for, one screen over.
 */
public record AnomalyReport(
    String id,
    String topic,
    String type,      // SEQUENCE|TEMPORAL|STRUCTURAL|CARDINALITY|BUSINESS
    String severity,  // CRITICAL|MAJOR|MINOR
    List<String> fields,
    String description,
    String probableCause,
    String sqlSuggestion
) {}
