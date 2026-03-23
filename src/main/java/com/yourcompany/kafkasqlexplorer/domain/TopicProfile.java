// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

import java.util.List;

public record TopicProfile(
    String name,
    String format,
    List<FieldInfo> fields,
    List<String> candidateCorrelationKeys,
    List<String> candidateTimestamps,
    List<String> candidateStatuses
) {}
