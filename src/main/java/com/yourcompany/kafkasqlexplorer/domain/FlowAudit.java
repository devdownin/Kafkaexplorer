package com.yourcompany.kafkasqlexplorer.domain;

import java.util.List;
import java.util.Map;

public record FlowAudit(
    String flowName,
    List<StepInfo> steps,
    double overallHealthScore
) {
    public record StepInfo(
        String topicName,
        long count,
        double throughputPercentage, // compared to previous step or first step
        Long averageLatencyMs
        double throughputPercentage // compared to previous step or first step
    ) {}
}
