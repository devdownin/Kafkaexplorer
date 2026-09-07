// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.guard;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
    void mode_off_leaves_the_payload_alone() {
        McpProperties properties = new McpProperties();
        properties.getDlp().setMode(McpProperties.Dlp.Mode.OFF);

        assertThat(new DlpScrubber(properties).scrub("password=hunter2")).isEqualTo("password=hunter2");
    }
}
