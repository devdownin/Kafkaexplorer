// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

import java.util.List;
import java.util.Map;

public record AuditReport(
    String auditId,
    AuditStatus status,
    long totalTopics,
    long totalMessages,
    int unhealthyTopicsCount,
    List<TopicAudit> topicAudits,
    List<FlowAudit> flowAudits,
    Map<String, Object> globalStats
) {}
