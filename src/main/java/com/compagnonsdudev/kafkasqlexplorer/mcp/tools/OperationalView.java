// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Measured;

import java.util.List;

/** Wire contracts for the operational diagnostic tools consumed by supervising agents. */
public final class OperationalView {

    private OperationalView() {
    }

    public record TopicActivity(
            String topic,
            long windowStartMs,
            long windowEndMs,
            long bucketMs,
            List<Long> counts,
            long offsetsProduced,
            Measured<Long> lastMessageAt,
            int silentBuckets,
            int partitionsMeasured,
            int partitionsTotal,
            boolean complete,
            String note
    ) {
    }

    /**
     * One ordered stage of an integration process.
     *
     * @param name stable stage label used in evidence; optional for callers
     * @param topic Kafka topic observed at this stage
     * @param consumerGroupId group that consumes this stage, when the stage has one
     */
    public record ProcessStage(
            String name,
            String topic,
            String consumerGroupId
    ) {
    }

    public record ConsumerDiagnosis(
            String topic,
            String groupId,
            String verdict,
            String diagnosis,
            String state,
            Measured<Long> recordLag,
            Measured<Long> lagMs,
            Measured<Integer> assignedMembers,
            int partitionsWithoutCommit
    ) {
    }

    public record ProcessHealth(
            String process,
            String status,
            String explanation,
            List<String> topics,
            List<TopicActivity> activity,
            List<ConsumerDiagnosis> consumers,
            Measured<Long> lastActivityAt,
            Measured<Long> offsetsProduced,
            Measured<Long> worstRecordLag
    ) {
    }

    public record FlowStage(
            String topic,
            long offsetsProduced,
            Measured<Long> lastMessageAt,
            boolean complete
    ) {
    }

    public record FlowHealth(
            String status,
            String bottleneck,
            List<FlowStage> stages,
            Measured<Double> largestDropRate,
            String explanation
    ) {
    }

    public record ProcessComparison(
            String process,
            String verdict,
            String beforeStatus,
            String afterStatus,
            Measured<Long> beforeRecordLag,
            Measured<Long> afterRecordLag,
            Measured<Long> beforeOffsetsProduced,
            Measured<Long> afterOffsetsProduced,
            String explanation
    ) {
    }

    public record IncidentEvidence(
            String process,
            long observedAt,
            ProcessHealth health,
            List<TopicActivity> topicActivity,
            List<ConsumerDiagnosis> consumerDiagnostics,
            List<String> facts
    ) {
    }
}
