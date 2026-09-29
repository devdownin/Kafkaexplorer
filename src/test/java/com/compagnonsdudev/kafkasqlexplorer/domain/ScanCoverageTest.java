// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScanCoverageTest {
    @Test
    void reportsObservedPartitionOffsetsWithoutClaimingACompleteTopic() {
        var records = List.of(
            new ConsumerRecord<String, String>("orders", 1, 9, null, "a"),
            new ConsumerRecord<String, String>("orders", 0, 4, null, "b"),
            new ConsumerRecord<String, String>("orders", 1, 10, null, "c"));
        var shortScan = ScanCoverage.observed("orders", records, 10);
        assertEquals("UNVERIFIED", shortScan.status());
        assertEquals(List.of(new ScanCoverage.PartitionRange(0, 4, 4),
            new ScanCoverage.PartitionRange(1, 9, 10)), shortScan.partitions());
        assertEquals("PARTIAL", ScanCoverage.observed("orders", records, 3).status());
    }
}
