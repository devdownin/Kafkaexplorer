// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.domain;

import java.util.List;

/** Measured produced offsets per time bucket for one Kafka partition, not a sample of records. */
public record PartitionActivity(int partition, List<Long> counts, Long coveredFromMs,
                                boolean available, String note) {
    public PartitionActivity {
        counts = counts == null ? List.of() : List.copyOf(counts);
    }
}
