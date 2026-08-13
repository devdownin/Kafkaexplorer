// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;
import java.util.Map;

public record MetricPreviewResult(
    Double value,
    List<Map<String, Object>> rows,
    String error,
    Map<String, Object> summary
) {}
