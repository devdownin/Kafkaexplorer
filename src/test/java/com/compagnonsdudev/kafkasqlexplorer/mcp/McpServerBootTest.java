// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp;

import com.compagnonsdudev.kafkasqlexplorer.ExplorerContextTest;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpCatalogService;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpToolSpecificationPostProcessor;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.ToolDescriptor;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MCP server, actually started.
 *
 * <p><b>Every other test in this module builds its tool specifications by hand.</b>
 * {@code McpServerConfigurationTest} runs an {@code ApplicationContextRunner} with
 * {@code AutoConfigurations.of()} — empty — so Spring AI's own scanner has never run in a test, the
 * {@code toolSpecs} bean the post-processor exists to post-process has never been built by the
 * framework, and nothing has ever asserted what {@code tools/list} would answer. Two hundred unit
 * tests, and the server had never been started.
 *
 * <p>What that hides is a whole class of failure this suite could not see: Spring AI derives a JSON
 * schema for every {@code @McpTool} method from its signature, and a parameter or return type it
 * cannot express is a startup failure or a missing tool — in production, on the first agent that
 * connects. This module's tools take {@code List<String>} parameters and return a generic
 * {@code ToolResult<T>} over records that nest other records and a generic {@code Measured<T>};
 * none of that was ever put in front of the scanner.
 *
 * <p>{@code webEnvironment} stays {@code MOCK} (the default): the assertions are about the tools the
 * framework registered, and binding a port would add a listener without adding a fact.
 */
@ExplorerContextTest
@TestPropertySource(properties = {
    "explorer.mcp.enabled=true",
    // Kept at the shipped default so this test asserts the posture a deployment actually gets.
    "explorer.mcp.readonly=true",
})
class McpServerBootTest {

    /** Every tool this server is expected to publish. A new one lands here or the test says so. */
    private static final List<String> EXPECTED = List.of(
        "kex_list_topics", "kex_describe_topic", "kex_preview_messages",
        "kex_infer_schema",
        "kex_sql_query", "kex_list_tables",
        "kex_trace_key", "kex_resume_trace", "kex_compare_traces",
        "kex_consumer_lag",
        "kex_deduce_data_model", "kex_build_join",
        "kex_run_audit", "kex_get_audit", "kex_suggest_kpis");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("kafka.bootstrap-servers", () -> "localhost:9092");
    }

    @Autowired List<SyncToolSpecification> toolSpecs;
    @Autowired McpCatalogService catalog;
    @Autowired McpToolSpecificationPostProcessor postProcessor;

    @Test
    void spring_ai_registers_every_tool_this_server_declares() {
        // The assertion the module could not make before: the framework's scanner accepted every
        // @McpTool signature — the List<String> parameters, the generic ToolResult<T> return, the
        // nested records — and produced a specification for each.
        assertThat(toolSpecs).extracting(spec -> spec.tool().name())
                .containsExactlyInAnyOrderElementsOf(EXPECTED);
    }

    @Test
    void every_registered_tool_carries_a_schema_and_a_description() {
        // A tool whose schema the scanner could not derive is a tool a client cannot call, and a
        // tool with no description is one the model guesses at.
        assertThat(toolSpecs).allSatisfy(spec -> {
            Tool tool = spec.tool();
            assertThat(tool.description())
                    .describedAs("%s must carry the description written for the model", tool.name())
                    .isNotBlank();
            assertThat(tool.inputSchema())
                    .describedAs("%s must carry an input schema", tool.name())
                    .isNotNull();
        });
    }

    @Test
    void the_interceptor_wrapped_every_tool_the_framework_registered() {
        // Without this the recorder counts nothing, the output ceiling is unenforced and a guard's
        // JSON-RPC code never reaches the agent — silently, on a serving deployment.
        assertThat(postProcessor.wrappedCount()).isEqualTo(EXPECTED.size());
        assertThat(postProcessor.removedTools()).isEmpty();
    }

    @Test
    void the_shipped_posture_publishes_no_mutating_tool() {
        // readonly=true withholds the write surface at bean registration. Today that surface is
        // empty — no MutatingMcpTools exists — so this pins the fact rather than the mechanism:
        // when the first write tool lands, this test is what says whether it stayed withheld.
        assertThat(catalog.writeSurfaceOpen()).isFalse();
        assertThat(catalog.exposed()).extracting(ToolDescriptor::name)
                .containsExactlyInAnyOrderElementsOf(EXPECTED);
    }

    @Test
    void the_catalogue_describes_exactly_what_the_transport_serves() {
        // The console reads the catalogue and the agent reads tools/list. Two lists that can drift
        // are two answers to "what does this server offer", and the console's whole purpose is to
        // be the one an operator can trust.
        assertThat(catalog.exposed()).extracting(ToolDescriptor::name)
                .containsExactlyInAnyOrderElementsOf(
                        toolSpecs.stream().map(spec -> spec.tool().name()).toList());
    }
}
