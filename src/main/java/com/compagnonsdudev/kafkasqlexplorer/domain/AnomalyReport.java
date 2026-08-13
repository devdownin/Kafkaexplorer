// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;

public record AnomalyReport(
    String id,
    String topic,
    String type,      // SEQUENCE|TEMPORAL|STRUCTURAL|CARDINALITY|BUSINESS
    String severity,  // CRITICAL|MAJOR|MINOR
    List<String> fields,
    String description,
    String probableCause,
    String ksqlSuggestion
) {}
