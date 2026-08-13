// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;
import java.util.Map;

public record DashboardResponse(
    List<String> topics,
    Map<String, Long> topicSizes,
    long totalMessages,
    List<String> tables,
    List<FlinkJobSummary> jobs,
    boolean health,
    /** Display label from {@code explorer.cluster-name} — chosen by whoever deployed the app. */
    String clusterName,
    /**
     * The bootstrap address the app is actually using, including after a runtime repoint through
     * {@code POST /api/config}. The label above says whatever it was configured to say; this is
     * the part a reader can check, which is why the header carries both.
     */
    String bootstrapServers,
    Map<String, Long> topicLastMessages
) {}
