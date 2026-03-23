// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

public record SnapshotConfig(
    String mode,           // EARLIEST | LATEST_N | TIMESTAMP
    int maxMessages,       // default 500
    Integer durationMinutes,
    Long fromTimestamp
) {
    public static SnapshotConfig latestN(int n) {
        return new SnapshotConfig("LATEST_N", n, null, null);
    }

    public static SnapshotConfig earliest(int max) {
        return new SnapshotConfig("EARLIEST", max, null, null);
    }
}
