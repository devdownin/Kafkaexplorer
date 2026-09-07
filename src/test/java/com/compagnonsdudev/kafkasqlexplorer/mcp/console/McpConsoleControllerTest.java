// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.console;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The controller with the MCP module absent — which is the shipped default, not a degraded case.
 *
 * <p>Every endpoint has to answer something an operator can read. A 404 would leave the screen
 * unable to tell "the server is off" from "this build is too old" from "the endpoint moved", and
 * the empty state that explains itself is the reason the screen exists.
 */
class McpConsoleControllerTest {

    private static <T> ObjectProvider<T> absent() {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                throw new IllegalStateException("no such bean");
            }

            @Override
            public T getIfAvailable() {
                return null;
            }
        };
    }

    private final McpConsoleController controller =
            new McpConsoleController(absent(), absent(), new MockEnvironment());

    @Test
    void with_the_server_disabled_the_status_says_so_rather_than_failing() {
        McpStatusView status = controller.status();

        assertThat(status.enabled()).isFalse();
        assertThat(status.endpoint()).isNull();
        assertThat(status.transports()).isEmpty();
    }

    @Test
    void the_other_endpoints_answer_empty_rather_than_erroring() {
        assertThat(controller.catalog(null).tools()).isEmpty();
        assertThat(controller.calls(null, null, null, null, null, null)).isEmpty();
        assertThat(controller.clients(null)).isEmpty();

        McpStatsView stats = controller.stats(null);
        assertThat(stats.calls()).isZero();
        // Zero calls is a fact here; the latency is not, and it says why rather than reading 0 ms.
        assertThat(stats.p95Ms().measured()).isFalse();
        assertThat(stats.p95Ms().reason()).contains("disabled");
    }

    @Test
    void the_client_config_snippet_never_contains_a_credential() {
        // A generated snippet is pasted into a dotfile, a chat, a ticket. Putting a token in it
        // would make this console the thing that leaked one.
        for (String client : List.of("claude-code", "claude-desktop", "generic")) {
            McpClientConfig config = controller.clientConfig(client);
            assertThat(config.snippet().toLowerCase(java.util.Locale.ROOT))
                    .doesNotContain("bearer ").doesNotContain("secret").doesNotContain("api-key")
                    .doesNotContain("token");
            assertThat(config.snippet()).contains("/mcp");
        }
    }

    @Test
    void an_unknown_client_falls_back_to_the_generic_snippet() {
        assertThat(controller.clientConfig("some-editor").client()).isEqualTo("generic");
        assertThat(controller.clientConfig(null).client()).isEqualTo("generic");
    }

    @Test
    void trying_a_tool_with_the_server_off_explains_instead_of_pretending() {
        var response = controller.tryTool("kex_list_topics", Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().invoked()).isFalse();
        assertThat(response.getBody().message()).contains("explorer.mcp.enabled=false");
    }

    @Test
    void try_it_can_be_turned_off_and_the_refusal_names_the_setting() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("explorer.mcp.console.allow-try-it", "false");
        var response = new McpConsoleController(absent(), absent(), env).tryTool("kex_list_topics", Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("allow-try-it");
    }

    @Test
    void replay_says_the_history_does_not_exist_yet_rather_than_404ing() {
        // The console offers the button the moment the ring evicts anything, so whoever presses it
        // must learn that nothing is persisted — not go hunting for a broken route.
        var response = controller.replay();

        assertThat(response.getStatusCode().value()).isEqualTo(501);
        assertThat(response.getBody()).contains("audit-topic");
    }

    @Test
    void a_window_that_cannot_be_parsed_falls_back_instead_of_rejecting_the_request() {
        // The value comes from a URL an operator may have edited by hand, and the response says
        // what it actually covered anyway.
        assertThat(McpConsoleController.parseWindow("24h")).isEqualTo(Duration.ofHours(24));
        assertThat(McpConsoleController.parseWindow("7d")).isEqualTo(Duration.ofDays(7));
        assertThat(McpConsoleController.parseWindow("90m")).isEqualTo(Duration.ofMinutes(90));
        assertThat(McpConsoleController.parseWindow("banana")).isEqualTo(Duration.ofHours(24));
        assertThat(McpConsoleController.parseWindow(null)).isEqualTo(Duration.ofHours(24));
    }
}
