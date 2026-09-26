// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LagSampleStoreTest {
    @TempDir Path directory;

    @Test
    void reads_the_previous_complete_sample_across_instances_and_restart() throws Exception {
        var first = LagSampleStore.shared(directory);
        var second = LagSampleStore.shared(directory);
        var initial = new LagSampleStore.Sample(20, 1_000, Map.of(0, 100L), Map.of(0, 80L));
        var later = new LagSampleStore.Sample(27, 2_000, Map.of(0, 110L), Map.of(0, 83L));
        assertThat(first.swap("demo.orders\u0000orders", initial, 30_000)).isNull();
        assertThat(second.swap("demo.orders\u0000orders", later, 30_000)).isEqualTo(initial);
        assertThat(LagSampleStore.shared(directory).swap("demo.orders\u0000orders",
                new LagSampleStore.Sample(30, 40_000, Map.of(0, 120L), Map.of(0, 90L)), 30_000))
                .isNull();
    }
}
