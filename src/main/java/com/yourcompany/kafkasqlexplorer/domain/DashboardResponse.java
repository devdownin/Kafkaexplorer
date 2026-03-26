// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

import java.util.List;
import java.util.Map;

public record DashboardResponse(
    List<String> topics,
    Map<String, Long> topicSizes,
    long totalMessages,
    List<String> tables,
    List<FlinkJobSummary> jobs,
    boolean health,
    String clusterName,
    Map<String, Long> topicLastMessages
) {}
