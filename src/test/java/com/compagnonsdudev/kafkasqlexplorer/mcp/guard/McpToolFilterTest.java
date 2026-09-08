// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.guard;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolFilterTest {

    private static McpToolFilter filter(String allowed, String denied) {
        McpProperties properties = new McpProperties();
        properties.getTools().setAllowed(allowed);
        properties.getTools().setDenied(denied);
        return new McpToolFilter(properties);
    }

    @Test
    void the_shipped_default_restricts_nothing() {
        McpToolFilter filter = filter("*", "");

        assertThat(filter.unrestricted()).isTrue();
        assertThat(filter.permits("kex_list_topics")).isTrue();
        assertThat(filter.refusalReason("kex_list_topics")).isNull();
    }

    @Test
    void the_deny_list_wins_over_the_allow_list() {
        // The two are written by different people at different times — one in a base config, one
        // in an environment overlay — and the safe resolution of a disagreement is the restrictive
        // one. A deny an allow-list can override is decoration.
        McpToolFilter filter = filter("kex_sql_query,kex_list_topics", "kex_sql_query");

        assertThat(filter.permits("kex_sql_query")).isFalse();
        assertThat(filter.refusalReason("kex_sql_query")).contains("denied");
        assertThat(filter.permits("kex_list_topics")).isTrue();
    }

    @Test
    void an_allow_list_that_is_a_list_withholds_everything_it_does_not_name() {
        McpToolFilter filter = filter("kex_list_topics", "");

        assertThat(filter.permits("kex_list_topics")).isTrue();
        assertThat(filter.permits("kex_sql_query")).isFalse();
        assertThat(filter.refusalReason("kex_sql_query")).contains("not named in");
    }

    @Test
    void the_two_refusals_are_worded_apart_because_they_need_different_edits() {
        // "Excluded by allowed / denied" makes an operator open both to find out which.
        McpToolFilter filter = filter("kex_list_topics", "kex_sql_query");

        assertThat(filter.refusalReason("kex_sql_query")).contains("explorer.mcp.tools.denied");
        assertThat(filter.refusalReason("kex_infer_schema")).contains("explorer.mcp.tools.allowed");
    }

    @Test
    void a_name_in_either_list_that_no_tool_carries_is_reported_as_the_typo_it_is() {
        // A deny-list with a typo silences nothing while reading as though it did.
        McpToolFilter filter = filter("kex_list_topics,kex_typo", "kex_produce_mesage");

        Set<String> registered = Set.of("kex_list_topics", "kex_produce_message");
        assertThat(filter.deniedButUnknown(registered)).containsExactly("kex_produce_mesage");
        assertThat(filter.allowedButUnknown(registered)).containsExactly("kex_typo");
    }

    @Test
    void a_wildcard_allow_list_reports_no_unknown_names_since_it_names_none() {
        assertThat(filter("*", "").allowedButUnknown(Set.of("kex_list_topics"))).isEmpty();
    }

    @Test
    void whitespace_around_a_name_does_not_make_it_a_different_tool() {
        // A YAML list written across lines picks up spaces, and a deny-list that silently stopped
        // matching because of one would be the worst kind of not-working.
        assertThat(filter("*", " kex_sql_query , kex_list_topics ").permits("kex_sql_query"))
                .isFalse();
    }

    @Test
    void a_null_tool_name_is_refused_rather_than_treated_as_permitted() {
        assertThat(filter("*", "").permits(null)).isFalse();
    }
}
