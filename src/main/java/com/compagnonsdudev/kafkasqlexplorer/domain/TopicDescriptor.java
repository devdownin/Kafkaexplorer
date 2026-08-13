// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.Map;

public record TopicDescriptor(
    String name,
    int partitions,
    Map<Integer, Long> minOffsets,
    Map<Integer, Long> maxOffsets,
    MessageFormat detectedFormat,
    long estimatedSize
) {}
