// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.guard;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpRuntimeSwitchesTest {

    private static McpRuntimeSwitches switches(boolean readonly, boolean togglesAllowed) {
        McpProperties properties = new McpProperties();
        properties.setReadonly(readonly);
        properties.getConsole().setAllowRuntimeToggle(togglesAllowed);
        return new McpRuntimeSwitches(properties);
    }

    @Test
    void locking_read_only_takes_effect_at_once_and_records_who_and_why() {
        // The banner exists to get a derogation lifted, and it cannot without a name and a reason.
        McpRuntimeSwitches runtime = switches(false, true);

        McpRuntimeSwitches.Override lock = runtime.lockReadonly("alice", "agent looping on prod");

        assertThat(runtime.readonly()).isTrue();
        assertThat(lock.actor()).isEqualTo("alice");
        assertThat(lock.reason()).contains("looping");
        assertThat(runtime.active()).containsExactly(lock);
    }

    @Test
    void locking_twice_keeps_the_first_reason() {
        // During an incident the same switch gets thrown twice, and the second throw overwriting
        // the first would lose the one sentence explaining why.
        McpRuntimeSwitches runtime = switches(false, true);
        runtime.lockReadonly("alice", "agent looping on prod");

        McpRuntimeSwitches.Override second = runtime.lockReadonly("bob", "");

        assertThat(second.actor()).isEqualTo("alice");
        assertThat(second.reason()).contains("looping");
    }

    @Test
    void lifting_the_lock_cannot_open_a_surface_the_configuration_keeps_closed() {
        // The write surface is decided at bean registration, so a toggle could not open it — and a
        // control that appears to and does not is worse than none.
        McpRuntimeSwitches runtime = switches(true, true);

        runtime.unlockReadonly();

        assertThat(runtime.readonly()).isTrue();
        assertThat(runtime.readonlyIsConfigured()).isTrue();
    }

    @Test
    void lifting_a_lock_that_was_never_set_says_so_rather_than_reporting_success() {
        assertThat(switches(false, true).unlockReadonly()).isFalse();
    }

    @Test
    void a_disabled_tool_carries_the_operator_and_the_reason_for_the_refusal_message() {
        McpRuntimeSwitches runtime = switches(false, true);

        runtime.disableTool("kex_sql_query", "alice", "a runaway query");

        assertThat(runtime.toolDisabled("kex_sql_query")).hasValueSatisfying(override -> {
            assertThat(override.actor()).isEqualTo("alice");
            assertThat(override.reason()).contains("runaway");
        });
        assertThat(runtime.toolDisabled("kex_list_topics")).isEmpty();
    }

    @Test
    void re_enabling_a_tool_that_was_not_disabled_reports_no_change() {
        McpRuntimeSwitches runtime = switches(false, true);

        assertThat(runtime.enableTool("kex_sql_query")).isFalse();
        runtime.disableTool("kex_sql_query", "alice", "x");
        assertThat(runtime.enableTool("kex_sql_query")).isTrue();
        assertThat(runtime.toolDisabled("kex_sql_query")).isEmpty();
    }

    @Test
    void a_quarantined_identity_is_held_until_it_is_released() {
        McpRuntimeSwitches runtime = switches(false, true);

        runtime.quarantine("agent-7", "alice", "exfiltration attempt");

        assertThat(runtime.quarantineOf("agent-7")).isPresent();
        assertThat(runtime.quarantineOf("agent-8")).isEmpty();
        assertThat(runtime.releaseQuarantine("agent-7")).isTrue();
        assertThat(runtime.quarantineOf("agent-7")).isEmpty();
    }

    @Test
    void a_null_identity_is_not_quarantined_by_accident() {
        assertThat(switches(false, true).quarantineOf(null)).isEmpty();
    }

    @Test
    void every_switch_is_refused_when_the_deployment_turned_the_toggles_off() {
        McpRuntimeSwitches runtime = switches(false, false);

        assertThatThrownBy(() -> runtime.lockReadonly("alice", "x"))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("allow-runtime-toggle");
        assertThatThrownBy(() -> runtime.disableTool("kex_sql_query", "alice", "x"))
                .isInstanceOf(McpToolException.class);
        assertThatThrownBy(() -> runtime.quarantine("agent-7", "alice", "x"))
                .isInstanceOf(McpToolException.class);
    }

    @Test
    void the_banner_lists_the_oldest_override_first() {
        // The one in force longest is the one most likely to have outlived its incident, and it
        // belongs at the top rather than scrolled past.
        McpRuntimeSwitches runtime = switches(false, true);
        runtime.disableTool("kex_sql_query", "alice", "first");
        runtime.quarantine("agent-7", "bob", "second");

        assertThat(runtime.active()).extracting(McpRuntimeSwitches.Override::reason)
                .containsExactly("first", "second");
    }
}
