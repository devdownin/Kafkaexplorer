// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.guard;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DlpScrubberTest {

    private final DlpScrubber scrubber = new DlpScrubber(new McpProperties());

    @Test
    void a_credential_in_a_payload_is_redacted() {
        String scrubbed = scrubber.scrub("{\"user\":\"ana\",\"password\":\"hunter2\"}");

        assertThat(scrubbed).doesNotContain("hunter2").contains("******").contains("ana");
    }

    @Test
    void a_bearer_token_is_redacted_wherever_it_appears() {
        // The regression that made this a test: the credential-key rule matched `Authorization:`
        // first and, with a value pattern that stopped at whitespace, masked the word "Bearer" and
        // left the token in place — a redaction that reads as if it worked.
        assertThat(scrubber.scrub("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.abc"))
                .doesNotContain("eyJhbGciOiJIUzI1NiJ9");
    }

    @Test
    void a_bearer_token_is_redacted_even_under_a_key_that_names_nothing_sensitive() {
        assertThat(scrubber.scrub("{\"header\":\"Bearer eyJhbGciOiJIUzI1NiJ9.abc\"}"))
                .doesNotContain("eyJhbGciOiJIUzI1NiJ9")
                .contains("header");
    }

    @Test
    void a_ddl_goes_through_the_same_masker_the_ui_uses() {
        String ddl = "CREATE TABLE t (a INT) WITH ('properties.ssl.key.password' = 's3cr3t')";

        assertThat(scrubber.scrubDdl(ddl)).doesNotContain("s3cr3t").contains("******");
    }

    @Test
    void recorded_parameters_are_scrubbed_by_key_as_well_as_by_value() {
        // By key too: a secret that happens to be a plain word is invisible to the value patterns,
        // and the parameters are persisted — a redaction applied only at display leaves it in the
        // ring buffer and on the audit topic.
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("apiKey", "correct-horse");
        params.put("topic", "demo.orders");

        Map<String, Object> scrubbed = scrubber.scrubParams(params);

        assertThat(scrubbed.get("apiKey")).isEqualTo("******");
        assertThat(scrubbed.get("topic")).isEqualTo("demo.orders");
    }

    @Test
    void block_mode_refuses_the_payload_instead_of_masking_it() {
        // It used to be accepted and behave exactly like redact: an operator who set `block`,
        // believing a payload carrying a secret would not leave, got the same masked payload.
        // A security setting that reads stricter than it behaves is worse than not offering it.
        McpProperties properties = new McpProperties();
        properties.getDlp().setMode(McpProperties.Dlp.Mode.BLOCK);
        DlpScrubber blocking = new DlpScrubber(properties);

        assertThatThrownBy(() -> blocking.scrub("{\"password\":\"hunter2\"}"))
                .isInstanceOf(McpToolException.class)
                .satisfies(e -> {
                    assertThat(((McpToolException) e).jsonRpcCode()).isEqualTo(-32045);
                    // Protocol-level: it must not come back as tool output the model reads, on a
                    // control whose point is that nothing leaves.
                    assertThat(((McpToolException) e).errorCode().reportedToTheModel()).isFalse();
                });
    }

    @Test
    void block_mode_passes_a_payload_that_carries_nothing_sensitive() {
        McpProperties properties = new McpProperties();
        properties.getDlp().setMode(McpProperties.Dlp.Mode.BLOCK);

        assertThat(new DlpScrubber(properties).scrub("{\"id\":\"ORD-1042\"}"))
                .isEqualTo("{\"id\":\"ORD-1042\"}");
    }

    @Test
    void block_mode_refuses_a_ddl_that_carries_a_credential() {
        McpProperties properties = new McpProperties();
        properties.getDlp().setMode(McpProperties.Dlp.Mode.BLOCK);

        assertThatThrownBy(() -> new DlpScrubber(properties).scrubDdl(
                "CREATE TABLE t (a INT) WITH ('properties.ssl.key.password' = 's3cr3t')"))
                .isInstanceOf(McpToolException.class);
    }

    @Test
    void block_mode_still_only_redacts_a_recorded_parameter() {
        // Block is about what leaves the cluster. An argument came FROM the caller, so refusing
        // the call protects nobody and loses the record of it — what matters is that the secret
        // does not settle into the ring buffer and the audit topic.
        McpProperties properties = new McpProperties();
        properties.getDlp().setMode(McpProperties.Dlp.Mode.BLOCK);

        assertThat(new DlpScrubber(properties).scrubParams(Map.of("apiKey", "correct-horse")))
                .containsEntry("apiKey", "******");
    }

    @Test
    void mode_off_leaves_the_payload_alone() {
        McpProperties properties = new McpProperties();
        properties.getDlp().setMode(McpProperties.Dlp.Mode.OFF);

        assertThat(new DlpScrubber(properties).scrub("password=hunter2")).isEqualTo("password=hunter2");
    }
}
