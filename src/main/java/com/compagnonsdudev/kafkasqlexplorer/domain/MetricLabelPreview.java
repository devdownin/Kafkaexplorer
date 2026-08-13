// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.Map;

public record MetricLabelPreview(
    String topic,
    Long timestamp,
    String message,
    Map<String, String> fields
) {}
