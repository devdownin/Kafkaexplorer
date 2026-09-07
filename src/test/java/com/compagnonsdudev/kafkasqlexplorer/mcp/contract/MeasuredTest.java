// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one contract this module cannot get wrong: an unmeasured value must not be readable as zero.
 */
class MeasuredTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void an_unmeasured_value_serialises_with_its_reason_and_no_value() throws Exception {
        String out = json.writeValueAsString(Measured.unmeasured("partition 3 unreadable: 8s timeout"));

        assertThat(json.readTree(out).get("measured").asBoolean()).isFalse();
        assertThat(json.readTree(out).get("reason").asText()).contains("partition 3");
        assertThat(json.readTree(out).get("value").isNull()).isTrue();
    }

    @Test
    void a_measured_zero_is_still_a_zero_and_says_so() throws Exception {
        // The point of the type: "caught up" and "could not tell" must not share a wire shape.
        String out = json.writeValueAsString(Measured.of(0L));

        assertThat(json.readTree(out).get("value").asLong()).isZero();
        assertThat(json.readTree(out).get("measured").asBoolean()).isTrue();
    }

    @Test
    void the_wire_shape_does_not_depend_on_a_registered_serializer() throws Exception {
        // A plain mapper, no module registered: the MCP transport runs on a different Jackson from
        // the application's REST layer, so a shape that needs registration is a shape that is
        // present on one surface and absent on the other.
        String out = new ObjectMapper().writeValueAsString(Measured.unmeasured("no commit"));

        assertThat(out).contains("\"measured\":false").contains("\"reason\":\"no commit\"");
    }

    @Test
    void an_unmeasured_value_must_say_why() {
        assertThatThrownBy(() -> new Measured<Long>(null, false, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_measured_value_carries_no_reason() {
        assertThatThrownBy(() -> new Measured<>(3L, true, "unread"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void of_nullable_picks_the_right_side() {
        assertThat(Measured.ofNullable(7L, "unread").measured()).isTrue();
        assertThat(Measured.ofNullable(null, "unread").reason()).isEqualTo("unread");
    }
}
