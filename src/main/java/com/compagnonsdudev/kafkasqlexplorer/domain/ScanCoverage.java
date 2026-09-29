// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/** The offsets actually fetched, not an assertion about unread records in the topic. */
public record ScanCoverage(String topic, int recordsFetched, int scanCeiling,
                           String status, List<PartitionRange> partitions) {
    public record PartitionRange(int partition, long firstOffset, long lastOffset) { }

    public static ScanCoverage observed(String topic, List<ConsumerRecord<String, String>> records, int ceiling) {
        Map<Integer, long[]> ranges = new TreeMap<>();
        for (ConsumerRecord<String, String> record : records) {
            long[] bounds = ranges.computeIfAbsent(record.partition(), ignored -> new long[] {
                record.offset(), record.offset() });
            bounds[0] = Math.min(bounds[0], record.offset());
            bounds[1] = Math.max(bounds[1], record.offset());
        }
        List<PartitionRange> partitions = ranges.entrySet().stream()
            .map(e -> new PartitionRange(e.getKey(), e.getValue()[0], e.getValue()[1]))
            .toList();
        // A short read is not proof of completeness: the consumer may stop after its poll budget.
        return new ScanCoverage(topic, records.size(), ceiling,
            records.size() >= ceiling ? "PARTIAL" : "UNVERIFIED", partitions);
    }
}
