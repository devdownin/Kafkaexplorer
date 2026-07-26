// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

import java.util.List;
import java.util.Map;

/**
 * @param totalMessages       sum of the per-topic counts actually reported, so the KPI and the
 *                            table column always agree (exact counts when that check ran, the
 *                            offset estimate otherwise)
 * @param criticalTopicsCount topics carrying at least one CRITICAL issue
 * @param warningTopicsCount  topics whose worst issue is a WARNING
 */
public record AuditReport(
    String auditId,
    AuditStatus status,
    long totalTopics,
    long totalMessages,
    int criticalTopicsCount,
    int warningTopicsCount,
    List<TopicAudit> topicAudits,
    List<FlowAudit> flowAudits,
    Map<String, Object> globalStats
) {}
