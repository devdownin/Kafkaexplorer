// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.observability;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpToolFilter;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Coverage;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.ToolResult;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.MutatingMcpTools;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.ReadOnlyMcpTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class McpCatalogServiceTest {

    private static McpCatalogService catalogOf(McpProperties properties) {
        return new McpCatalogService(properties, new McpToolFilter(properties));
    }

    /** A stand-in read toolset — the introspection under test is reflective, so any bean will do. */
    static class FakeReadTools implements ReadOnlyMcpTools {
        @Override
        public ToolCategory category() {
            return ToolCategory.EXPLORATION;
        }

        @McpTool(name = "kex_fake_list", description = "List things. Always read coverage.")
        public ToolResult<List<String>> list() {
            return ToolResult.complete(List.of(), Coverage.exhausted(0L, 0L));
        }
    }

    static class FakeWriteTools implements MutatingMcpTools {
        @McpTool(name = "kex_produce_message", description = "Produce a message.")
        public ToolResult<String> produce() {
            return ToolResult.complete("", Coverage.exhausted(0L, 0L));
        }
    }

    @Test
    void a_tool_withheld_by_read_only_mode_stays_in_the_catalogue_with_its_reason() {
        // The row IS the answer to "why does my agent not see kex_produce_message?". Omitting it
        // leaves an operator reading YAML and guessing which of four settings did it.
        McpCatalogService catalog = catalogOf(new McpProperties());
        FakeReadTools read = new FakeReadTools();
        FakeWriteTools write = new FakeWriteTools();

        catalog.publish(List.of(read, write), List.of(read));

        assertThat(catalog.catalog()).hasSize(2);
        assertThat(catalog.catalog())
                .filteredOn(d -> d.name().equals("kex_produce_message"))
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.visibility().state()).isEqualTo(Visibility.State.HIDDEN);
                    assertThat(d.visibility().reason()).contains("read-only");
                    assertThat(d.category()).isEqualTo(ToolCategory.WRITE);
                });
        assertThat(catalog.exposed()).extracting(ToolDescriptor::name).containsExactly("kex_fake_list");
        assertThat(catalog.writeSurfaceOpen()).isFalse();
        // Withheld, but present — which is the sentence the banner needs and could not make.
        assertThat(catalog.writeSurfaceExists()).isTrue();
    }

    @Test
    void a_build_with_no_mutating_tool_says_so_rather_than_reporting_a_guard_with_nothing_to_guard() {
        // The state this application is actually in: no MutatingMcpTools implementation exists, so
        // read-only withholds an empty set. "READ-ONLY" over that is true and reads as "a write
        // surface is being held back" — a reassurance about a guard that has nothing to guard.
        McpCatalogService catalog = catalogOf(new McpProperties());
        FakeReadTools read = new FakeReadTools();

        catalog.publish(List.of(read), List.of(read));

        assertThat(catalog.writeSurfaceOpen()).isFalse();
        assertThat(catalog.writeSurfaceExists()).isFalse();
    }

    @Test
    void the_write_badge_lights_only_when_a_mutating_tool_is_actually_registered() {
        McpProperties properties = new McpProperties();
        properties.setReadonly(false);
        McpCatalogService catalog = new McpCatalogService(properties, new McpToolFilter(properties));
        FakeReadTools read = new FakeReadTools();
        FakeWriteTools write = new FakeWriteTools();

        catalog.publish(List.of(read, write), List.of(read, write));

        assertThat(catalog.writeSurfaceOpen()).isTrue();
        assertThat(catalog.writeSurfaceExists()).isTrue();
        assertThat(catalog.catalog())
                .filteredOn(d -> d.name().equals("kex_produce_message"))
                .singleElement()
                // It is in approval-required-tools by default, and the console must say so rather
                // than showing it as plainly exposed.
                .satisfies(d -> assertThat(d.visibility().state())
                        .isEqualTo(Visibility.State.EXPOSED_WITH_APPROVAL));
    }

    @Test
    void the_description_is_the_agents_own_never_a_paraphrase() {
        McpCatalogService catalog = catalogOf(new McpProperties());
        FakeReadTools read = new FakeReadTools();

        catalog.publish(List.of(read), List.of(read));

        assertThat(catalog.catalog()).singleElement()
                .satisfies(d -> assertThat(d.description()).isEqualTo("List things. Always read coverage."));
    }

    @Test
    void the_ceilings_shown_are_the_ones_the_guard_applies() {
        McpProperties properties = new McpProperties();
        properties.setHardMaxRows(250);
        McpCatalogService catalog = new McpCatalogService(properties, new McpToolFilter(properties));
        FakeReadTools read = new FakeReadTools();

        catalog.publish(List.of(read), List.of(read));

        assertThat(catalog.catalog()).singleElement()
                .satisfies(d -> assertThat(d.hardMaxRows()).isEqualTo(250));
    }
}
