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

    /** The caller presenting the token. One identity is enough until a case needs two. */
    private static final String AGENT = "bearer:aaaa";

    private static McpApprovalStore store(String... requiringApproval) {
        McpProperties properties = new McpProperties();
        properties.setApprovalRequiredTools(Set.of(requiringApproval));
        return new McpApprovalStore(properties);
    }

    @Test
    void a_tool_that_needs_no_approval_passes_without_a_token() {
        assertThatCode(() -> store("kex_produce_message").spend("kex_list_topics", null, AGENT))
                .doesNotThrowAnyException();
    }

    @Test
    void a_tool_that_needs_approval_is_refused_without_one() {
        McpApprovalStore store = store("kex_preview_messages");

        assertThatThrownBy(() -> store.spend("kex_preview_messages", null, AGENT))
                .isInstanceOf(McpToolException.class)
                .satisfies(e -> assertThat(((McpToolException) e).jsonRpcCode()).isEqualTo(-32042))
                .hasMessageContaining("approval-required-tools");
    }

    @Test
    void a_minted_token_lets_exactly_one_call_through() {
        // A token that survives its call is a standing permission with an approval's paperwork.
        McpApprovalStore store = store("kex_preview_messages");
        String token = store.mint("kex_preview_messages", "alice", null);

        assertThatCode(() -> store.spend("kex_preview_messages", token, AGENT)).doesNotThrowAnyException();
        assertThatThrownBy(() -> store.spend("kex_preview_messages", token, AGENT))
                .isInstanceOf(McpToolException.class);
    }

    @Test
    void a_token_minted_for_one_tool_does_not_approve_another() {
        // Otherwise approving a preview approves a produce, and the operator who clicked has
        // approved something they were never shown.
        McpApprovalStore store = store("kex_preview_messages", "kex_sql_query");
        String token = store.mint("kex_preview_messages", "alice", null);

        assertThatThrownBy(() -> store.spend("kex_sql_query", token, AGENT))
                .isInstanceOf(McpToolException.class);
    }

    @Test
    void a_token_offered_for_the_wrong_tool_is_burned_rather_than_left_usable() {
        // It has been in the wrong hands or the wrong code path; either way it should not survive
        // to be offered again for the right one.
        McpApprovalStore store = store("kex_preview_messages", "kex_sql_query");
        String token = store.mint("kex_preview_messages", "alice", null);

        assertThatThrownBy(() -> store.spend("kex_sql_query", token, AGENT)).isInstanceOf(McpToolException.class);
        assertThatThrownBy(() -> store.spend("kex_preview_messages", token, AGENT))
                .isInstanceOf(McpToolException.class);
    }

    @Test
    void the_refusal_never_says_which_of_the_four_ways_it_failed() {
        // Distinguishing unknown from expired from wrong-tool from wrong-caller tells a caller
        // holding a stolen token which part of it to change; a legitimate caller has the same thing
        // to do in every one of them.
        McpApprovalStore store = store("kex_preview_messages");
        String minted = store.mint("kex_preview_messages", "alice", null);
        store.spend("kex_preview_messages", minted, AGENT);

        String spent = catchMessage(store, minted);
        String invented = catchMessage(store, "not-a-real-token");

        assertThat(spent).isEqualTo(invented);
    }

    @Test
    void a_blank_token_is_refused_like_an_absent_one() {
        assertThatThrownBy(() -> store("kex_preview_messages").spend("kex_preview_messages", "  ", AGENT))
                .isInstanceOf(McpToolException.class);
    }

    @Test
    void two_mints_never_produce_the_same_token() {
        McpApprovalStore store = store("kex_preview_messages");

        assertThat(store.mint("kex_preview_messages", "alice", null))
                .isNotEqualTo(store.mint("kex_preview_messages", "alice", null));
    }

    @Test
    void a_mint_loop_is_bounded_rather_than_a_memory_leak() {
        McpApprovalStore store = store("kex_preview_messages");

        for (int i = 0; i < McpApprovalStore.MAX_OUTSTANDING + 50; i++) {
            store.mint("kex_preview_messages", "alice", null);
        }

        assertThat(store.outstandingCount()).isEqualTo(McpApprovalStore.MAX_OUTSTANDING);
    }

    @Test
    void a_token_minted_for_one_caller_is_not_spendable_by_another() {
        // The hole this closes: on the deployment this store exists for — more people reach the
        // application than may approve — an approval granted to a named agent was spendable by
        // whoever asked first.
        McpApprovalStore store = store("kex_preview_messages");
        String token = store.mint("kex_preview_messages", "alice", AGENT);

        assertThatThrownBy(() -> store.spend("kex_preview_messages", token, "bearer:bbbb"))
                .isInstanceOf(McpToolException.class)
                .satisfies(e -> assertThat(((McpToolException) e).jsonRpcCode()).isEqualTo(-32042));
    }

    @Test
    void a_token_minted_for_one_caller_is_spendable_by_that_caller() {
        McpApprovalStore store = store("kex_preview_messages");
        String token = store.mint("kex_preview_messages", "alice", AGENT);

        assertThatCode(() -> store.spend("kex_preview_messages", token, AGENT))
                .doesNotThrowAnyException();
    }

    @Test
    void an_unbound_token_admits_any_caller_because_that_is_what_minting_one_means() {
        // Optional rather than required: an operator may be approving for a client that has not
        // called yet and has no identity to name. The answer says which of the two it minted.
        McpApprovalStore store = store("kex_preview_messages");
        String token = store.mint("kex_preview_messages", "alice", null);

        assertThatCode(() -> store.spend("kex_preview_messages", token, "bearer:whoever"))
                .doesNotThrowAnyException();
    }

    @Test
    void a_token_offered_by_the_wrong_caller_is_burned_like_one_offered_for_the_wrong_tool() {
        // Same reasoning: it has been in the wrong hands or the wrong code path.
        McpApprovalStore store = store("kex_preview_messages");
        String token = store.mint("kex_preview_messages", "alice", AGENT);

        assertThatThrownBy(() -> store.spend("kex_preview_messages", token, "bearer:bbbb"))
                .isInstanceOf(McpToolException.class);
        assertThatThrownBy(() -> store.spend("kex_preview_messages", token, AGENT))
                .isInstanceOf(McpToolException.class);
    }

    @Test
    void the_wrong_caller_reads_exactly_like_an_invented_token() {
        McpApprovalStore store = store("kex_preview_messages");
        String bound = store.mint("kex_preview_messages", "alice", AGENT);

        String wrongCaller = catchMessage(store, bound, "bearer:bbbb");
        String invented = catchMessage(store, "not-a-real-token", AGENT);

        assertThat(wrongCaller).isEqualTo(invented);
    }

    private static String catchMessage(McpApprovalStore store, String token) {
        return catchMessage(store, token, AGENT);
    }

    private static String catchMessage(McpApprovalStore store, String token, String caller) {
        try {
            store.spend("kex_preview_messages", token, caller);
            throw new AssertionError("expected a refusal");
        } catch (McpToolException e) {
            return e.getMessage();
        }
    }
}
