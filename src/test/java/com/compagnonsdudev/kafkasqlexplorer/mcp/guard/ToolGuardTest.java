// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.guard;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

class ToolGuardTest {

    private static ToolGuard guardWith(List<String> topicPrefixes) {
        McpProperties properties = new McpProperties();
        properties.setAllowedTopicPrefixes(topicPrefixes);
        return new ToolGuard(properties, new DlpScrubber(properties));
    }

    @Test
    void the_wildcard_places_no_restriction() {
        assertThatNoException().isThrownBy(
                () -> guardWith(List.of("*")).checkTopicScope(List.of("prod.payments")));
    }

    @Test
    void a_topic_outside_the_scope_is_refused_and_named() {
        ToolGuard guard = guardWith(List.of("demo.", "sandbox."));

        assertThatThrownBy(() -> guard.checkTopicScope(List.of("demo.orders", "prod.payments")))
                .isInstanceOf(McpScopeViolationException.class)
                // The offending name is echoed on purpose: it came from the caller, so repeating it
                // discloses nothing, and withholding it leaves an agent guessing at the fix.
                .hasMessageContaining("prod.payments")
                .hasMessageContaining("demo.");
    }

    @Test
    void a_scope_violation_carries_the_kip1318_code_and_the_guard_that_blocked_it() {
        ToolGuard guard = guardWith(List.of("demo."));

        McpScopeViolationException thrown = null;
        try {
            guard.checkTopicScope("prod.payments");
        } catch (McpScopeViolationException e) {
            thrown = e;
        }

        assertThat(thrown).isNotNull();
        assertThat(thrown.jsonRpcCode()).isEqualTo(-32041);
        assertThat(thrown.guard()).isEqualTo(McpGuard.SCOPE);
        assertThat(thrown.offending()).containsExactly("prod.payments");
    }

    @Test
    void a_budget_above_the_ceiling_is_clamped_rather_than_refused() {
        // Clamping, not refusing: a model guessing at a limit nobody told it is not an attack, and
        // a refusal costs a round trip while teaching it nothing.
        McpProperties properties = new McpProperties();
        ToolGuard guard = new ToolGuard(properties, new DlpScrubber(properties));

        assertThat(guard.clampBudget(999_999)).isEqualTo(properties.getHardMaxBudgetMs());
        assertThat(guard.clampBudget(null)).isEqualTo(properties.getDefaultBudgetMs());
        assertThat(guard.clampBudget(5_000)).isEqualTo(5_000L);
    }

    @Test
    void a_clamp_is_reported_so_the_caller_learns_the_real_ceiling() {
        ToolGuard guard = guardWith(List.of("*"));

        assertThat(guard.clampWarnings("maxRows", 50_000, 1_000))
                .singleElement()
                .satisfies(warning -> {
                    assertThat(warning.code()).isEqualTo("BUDGET_CLAMPED");
                    assertThat(warning.message()).contains("50000").contains("1000");
                });
        assertThat(guard.clampWarnings("maxRows", 10, 1_000)).isEmpty();
    }

    @Test
    void a_quarantined_identity_is_refused_before_anything_runs() {
        ToolGuard guard = guardWith(List.of("*"));
        guard.quarantine("svc-sre@corp");

        assertThatThrownBy(() -> guard.checkNotQuarantined("svc-sre@corp"))
                .isInstanceOf(McpToolException.class)
                .satisfies(e -> assertThat(((McpToolException) e).jsonRpcCode()).isEqualTo(-32047));

        guard.releaseQuarantine("svc-sre@corp");
        assertThatNoException().isThrownBy(() -> guard.checkNotQuarantined("svc-sre@corp"));
    }
}
