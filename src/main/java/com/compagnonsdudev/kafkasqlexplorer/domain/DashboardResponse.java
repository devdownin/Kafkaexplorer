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
    boolean health,
    /** Display label from {@code explorer.cluster-name} — chosen by whoever deployed the app. */
    String clusterName,
    /**
     * The bootstrap address the app is actually using, including after a runtime repoint through
     * {@code POST /api/config}. The label above says whatever it was configured to say; this is
     * the part a reader can check, which is why the header carries both.
     */
    String bootstrapServers,
    Map<String, Long> topicLastMessages,
    /**
     * The resolved prefix of the topics this application writes for its own state
     * ({@code explorer.internal-topic-prefix}), empty on a deployment that sets none.
     *
     * <p>It travels because the browser asks the same question the server does — "is this one of
     * ours?" — in four places: the data model's select-all, the SQL editor's starter queries and
     * its sink-table pick. Each answered it with a literal {@code "internal."} written on the
     * spot, which was right while the prefix was a constant and stopped being the marker the day
     * it became a setting. This is the one response every page already receives, through
     * {@code Layout}'s poll, so nothing extra is requested for it.
     */
    String internalTopicPrefix
) {}
