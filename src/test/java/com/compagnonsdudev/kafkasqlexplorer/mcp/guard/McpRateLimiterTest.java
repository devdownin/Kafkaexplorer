// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.guard;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpRateLimiterTest {

    private static McpRateLimiter limiter(int perMinute, int burst) {
        McpProperties properties = new McpProperties();
        properties.getRateLimit().setCallsPerMinute(perMinute);
        properties.getRateLimit().setBurst(burst);
        return new McpRateLimiter(properties);
    }

    @Test
    void the_burst_passes_and_the_call_past_it_is_refused() {
        McpRateLimiter limiter = limiter(60, 3);

        for (int i = 0; i < 3; i++) {
            assertThatCode(() -> limiter.check("agent-7")).doesNotThrowAnyException();
        }

        assertThatThrownBy(() -> limiter.check("agent-7"))
                .isInstanceOf(McpToolException.class)
                .satisfies(e -> assertThat(((McpToolException) e).jsonRpcCode()).isEqualTo(-32029));
    }

    @Test
    void the_refusal_names_the_wait_so_retrying_at_once_is_not_the_obvious_move() {
        // A bare "rate limited" teaches a model to retry immediately, which is the behaviour the
        // limit exists to stop.
        McpRateLimiter limiter = limiter(60, 1);
        limiter.check("agent-7");

        assertThatThrownBy(() -> limiter.check("agent-7"))
                .hasMessageContaining("Wait about")
                .hasMessageContaining("calls per minute");
    }

    @Test
    void one_identity_spending_its_allowance_does_not_refuse_another() {
        McpRateLimiter limiter = limiter(60, 1);
        limiter.check("agent-7");

        assertThatCode(() -> limiter.check("agent-8")).doesNotThrowAnyException();
    }

    @Test
    void zero_turns_the_limiter_off_rather_than_refusing_everything() {
        // A misread setting that locked out every agent is a far worse failure than one that let
        // them through, and "0 means unlimited" is how explorer.max-concurrent-jobs already reads.
        McpRateLimiter limiter = limiter(0, 1);

        for (int i = 0; i < 50; i++) {
            assertThatCode(() -> limiter.check("agent-7")).doesNotThrowAnyException();
        }
    }

    @Test
    void a_burst_below_one_still_lets_a_first_call_through() {
        // A misconfigured burst must not make every call impossible.
        assertThatCode(() -> limiter(60, 0).check("agent-7")).doesNotThrowAnyException();
    }

    @Test
    void an_unnamed_caller_shares_one_bucket_rather_than_escaping_the_limit() {
        McpRateLimiter limiter = limiter(60, 1);
        limiter.check(null);

        assertThatThrownBy(() -> limiter.check(null)).isInstanceOf(McpToolException.class);
    }

    @Test
    void resetting_an_identity_gives_it_its_allowance_back() {
        McpRateLimiter limiter = limiter(60, 1);
        limiter.check("agent-7");
        limiter.reset("agent-7");

        assertThatCode(() -> limiter.check("agent-7")).doesNotThrowAnyException();
    }

    @Test
    void the_bucket_refills_continuously_rather_than_at_a_window_boundary() throws Exception {
        // A fixed window lets a caller spend the whole allowance in the last second of one minute
        // and the whole allowance again in the first second of the next.
        McpRateLimiter limiter = limiter(6000, 1);
        limiter.check("agent-7");

        Thread.sleep(20);

        assertThatCode(() -> limiter.check("agent-7")).doesNotThrowAnyException();
    }
}
