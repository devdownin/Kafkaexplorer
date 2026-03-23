// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

import java.util.List;

public record ProcessMiningResult(
    String flowchart,
    String comments,
    List<String> hypotheses,
    List<String> blindSpots,
    List<AnomalyReport> anomalies
) {}
