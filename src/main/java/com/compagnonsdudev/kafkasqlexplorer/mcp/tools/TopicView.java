// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Measured;

import java.util.List;
import java.util.Map;

/**
 * The shapes the topic tools return.
 *
 * <p>Separate from the application's own {@code TopicDescriptor} and friends on purpose: those are
 * shaped for a React page that can render a dash, while these are read by a model that cannot tell
 * a dash from a zero. Every field that a broker may decline to answer is {@link Measured}, so the
 * decline arrives as a decline.
 */
public final class TopicView {

    private TopicView() {
    }

    /**
     * One row of {@code kex_list_topics}.
     *
     * @param name           topic name
     * @param partitions     partition count
     * @param records        readable records ({@code end - beginning}, retention already applied),
     *                       unmeasured when the broker did not answer for this topic
     * @param lastActivityMs epoch millis of the newest record, unmeasured on an empty or unread topic
     * @param deadLetter     true when the name matches this cluster's dead-letter convention
     */
    public record TopicSummary(
            String name,
            int partitions,
            Measured<Long> records,
            Measured<Long> lastActivityMs,
            boolean deadLetter
    ) {}

    /**
     * {@code kex_describe_topic}.
     *
     * @param name            topic name
     * @param partitions      partition count
     * @param earliestOffsets first readable offset per partition
     * @param latestOffsets   log end offset per partition
     * @param records         readable records, unmeasured when the offsets did not come back
     * @param detectedFormat  JSON, XML, AVRO — inferred from a sample, not declared by the broker
     * @param consumerGroups  groups seen on this topic
     * @param deadLetter      true when the name matches the dead-letter convention
     */
    public record TopicDetail(
            String name,
            int partitions,
            Map<Integer, Long> earliestOffsets,
            Map<Integer, Long> latestOffsets,
            Measured<Long> records,
            String detectedFormat,
            List<String> consumerGroups,
            boolean deadLetter
    ) {}

    /**
     * One sampled record.
     *
     * @param partition   partition it came from
     * @param offset      its offset — with the partition, what makes the sample reproducible
     * @param timestampMs broker timestamp
     * @param key         record key, DLP-scrubbed
     * @param value       record value, pretty-printed and DLP-scrubbed
     */
    public record MessagePreview(
            int partition,
            long offset,
            long timestampMs,
            String key,
            String value
    ) {}

    /**
     * {@code kex_infer_schema} for one topic.
     *
     * @param topic       topic the schema was inferred from
     * @param format      format detected in the sample
     * @param columns     column name to Flink SQL type, in inference order
     * @param sampleSize  how many records the inference actually read — the confidence lives here
     * @param flinkDdl    a {@code CREATE TABLE} ready to run, credentials redacted
     */
    public record InferredSchema(
            String topic,
            String format,
            Map<String, String> columns,
            int sampleSize,
            String flinkDdl
    ) {}
}
