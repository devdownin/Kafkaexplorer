// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;

public record FieldInfo(
    String path,
    List<String> sampleValues,
    String inferredType,   // STRING|NUMBER|BOOLEAN|DATE|UUID|ENUM
    String semanticRole,   // CORRELATION_ID|TIMESTAMP|STATUS|AMOUNT|ACTOR|UNKNOWN
    double confidence,
    String reasoning
) {}
