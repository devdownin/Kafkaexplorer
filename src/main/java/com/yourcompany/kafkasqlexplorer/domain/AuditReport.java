package com.yourcompany.kafkasqlexplorer.domain;

import java.util.List;
import java.util.Map;

public record AuditReport(
    long totalTopics,
    long totalMessages,
    int unhealthyTopicsCount,
    List<TopicAudit> topicAudits,
    List<FlowAudit> flowAudits,
    Map<String, Object> globalStats
) {}
