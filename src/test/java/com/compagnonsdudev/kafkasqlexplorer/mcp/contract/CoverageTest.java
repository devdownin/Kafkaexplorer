// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.contract;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoverageTest {

    @Test
    void only_an_exhausted_pass_is_complete() {
        assertThat(Coverage.exhausted(3, 120L, 40L).complete()).isTrue();
        assertThat(Coverage.partial(40, 12, List.of("demo.sc.13.out"), 8_400L, 20_000L,
                StopReason.TIME_BUDGET, "rt-9f3c").complete()).isFalse();
    }

    @Test
    void what_was_not_reached_is_named_not_counted() {
        Coverage coverage = Coverage.partial(40, 12, List.of("demo.sc.13.out", "demo.payments.in"),
                8_400L, 20_000L, StopReason.TIME_BUDGET, "rt-9f3c");

        assertThat(coverage.topicsNotReached()).containsExactly("demo.sc.13.out", "demo.payments.in");
        assertThat(coverage.resumeToken()).isEqualTo("rt-9f3c");
    }

    @Test
    void the_not_reached_list_cannot_be_mutated_after_the_fact() {
        List<String> mutable = new ArrayList<>(List.of("demo.a"));
        Coverage coverage = Coverage.partial(2, 1, mutable, 0L, 1L, StopReason.TOPIC_LIMIT, null);

        mutable.add("demo.b");

        assertThat(coverage.topicsNotReached()).containsExactly("demo.a");
        assertThatThrownBy(() -> coverage.topicsNotReached().add("demo.c"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
