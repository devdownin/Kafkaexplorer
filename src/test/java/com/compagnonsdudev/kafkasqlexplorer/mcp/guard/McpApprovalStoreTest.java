// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.guard;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpApprovalStoreTest {

    private static McpApprovalStore store(String... requiringApproval) {
        McpProperties properties = new McpProperties();
        properties.setApprovalRequiredTools(Set.of(requiringApproval));
        return new McpApprovalStore(properties);
    }

    @Test
    void a_tool_that_needs_no_approval_passes_without_a_token() {
        assertThatCode(() -> store("kex_produce_message").spend("kex_list_topics", null))
                .doesNotThrowAnyException();
    }

    @Test
    void a_tool_that_needs_approval_is_refused_without_one() {
        McpApprovalStore store = store("kex_preview_messages");

        assertThatThrownBy(() -> store.spend("kex_preview_messages", null))
                .isInstanceOf(McpToolException.class)
                .satisfies(e -> assertThat(((McpToolException) e).jsonRpcCode()).isEqualTo(-32042))
                .hasMessageContaining("approval-required-tools");
    }

    @Test
    void a_minted_token_lets_exactly_one_call_through() {
        // A token that survives its call is a standing permission with an approval's paperwork.
        McpApprovalStore store = store("kex_preview_messages");
        String token = store.mint("kex_preview_messages", "alice");

        assertThatCode(() -> store.spend("kex_preview_messages", token)).doesNotThrowAnyException();
        assertThatThrownBy(() -> store.spend("kex_preview_messages", token))
                .isInstanceOf(McpToolException.class);
    }

    @Test
    void a_token_minted_for_one_tool_does_not_approve_another() {
        // Otherwise approving a preview approves a produce, and the operator who clicked has
        // approved something they were never shown.
        McpApprovalStore store = store("kex_preview_messages", "kex_sql_query");
        String token = store.mint("kex_preview_messages", "alice");

        assertThatThrownBy(() -> store.spend("kex_sql_query", token))
                .isInstanceOf(McpToolException.class);
    }

    @Test
    void a_token_offered_for_the_wrong_tool_is_burned_rather_than_left_usable() {
        // It has been in the wrong hands or the wrong code path; either way it should not survive
        // to be offered again for the right one.
        McpApprovalStore store = store("kex_preview_messages", "kex_sql_query");
        String token = store.mint("kex_preview_messages", "alice");

        assertThatThrownBy(() -> store.spend("kex_sql_query", token)).isInstanceOf(McpToolException.class);
        assertThatThrownBy(() -> store.spend("kex_preview_messages", token))
                .isInstanceOf(McpToolException.class);
    }

    @Test
    void the_refusal_never_says_which_of_the_three_ways_it_failed() {
        // Distinguishing unknown from expired from wrong-tool tells a caller holding a stolen token
        // which part of it to change; a legitimate caller has the same thing to do in all three.
        McpApprovalStore store = store("kex_preview_messages");
        String minted = store.mint("kex_preview_messages", "alice");
        store.spend("kex_preview_messages", minted);

        String spent = catchMessage(store, minted);
        String invented = catchMessage(store, "not-a-real-token");

        assertThat(spent).isEqualTo(invented);
    }

    @Test
    void a_blank_token_is_refused_like_an_absent_one() {
        assertThatThrownBy(() -> store("kex_preview_messages").spend("kex_preview_messages", "  "))
                .isInstanceOf(McpToolException.class);
    }

    @Test
    void two_mints_never_produce_the_same_token() {
        McpApprovalStore store = store("kex_preview_messages");

        assertThat(store.mint("kex_preview_messages", "alice"))
                .isNotEqualTo(store.mint("kex_preview_messages", "alice"));
    }

    @Test
    void a_mint_loop_is_bounded_rather_than_a_memory_leak() {
        McpApprovalStore store = store("kex_preview_messages");

        for (int i = 0; i < McpApprovalStore.MAX_OUTSTANDING + 50; i++) {
            store.mint("kex_preview_messages", "alice");
        }

        assertThat(store.outstandingCount()).isEqualTo(McpApprovalStore.MAX_OUTSTANDING);
    }

    private static String catchMessage(McpApprovalStore store, String token) {
        try {
            store.spend("kex_preview_messages", token);
            throw new AssertionError("expected a refusal");
        } catch (McpToolException e) {
            return e.getMessage();
        }
    }
}
