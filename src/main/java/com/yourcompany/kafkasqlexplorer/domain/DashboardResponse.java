package com.yourcompany.kafkasqlexplorer.domain;

import java.util.List;
import java.util.Map;

public record DashboardResponse(
    List<String> topics,
    Map<String, Long> topicSizes,
    long totalMessages,
    List<String> tables,
    Map<String, String> jobs,
    boolean health
) {}
