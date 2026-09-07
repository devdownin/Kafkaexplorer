// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp;

import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Coverage;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.ToolResult;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpCallRecorder;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpToolInterceptor;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpToolSpecificationPostProcessor;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpCatalogService;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.ToolDescriptor;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.Visibility;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.MutatingMcpTools;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.SchemaMcpTools;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.SqlMcpTools;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.TopicMcpTools;
import com.compagnonsdudev.kafkasqlexplorer.service.DdlGeneratorService;
import com.compagnonsdudev.kafkasqlexplorer.service.FlinkSqlService;
import com.compagnonsdudev.kafkasqlexplorer.service.FlinkTableStore;
import com.compagnonsdudev.kafkasqlexplorer.service.KafkaAdminService;
import com.compagnonsdudev.kafkasqlexplorer.service.MessageFormatterService;
import com.compagnonsdudev.kafkasqlexplorer.service.SchemaInferenceService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * What the module does at startup — which is where its security posture actually lives.
 *
 * <p>An {@code ApplicationContextRunner} rather than {@code @SpringBootTest}: the assertions are
 * about conditional bean registration, and booting the whole application to reach them would drag
 * in a broker and a Flink runtime that have no bearing on the question.
 */
class McpServerConfigurationTest {

    /** A mutating toolset, so the read-only guard has something to withhold. */
    static class FakeWriteTools implements MutatingMcpTools {
        @McpTool(name = "kex_produce_message", description = "Produce a message.")
        public ToolResult<String> produce() {
            return ToolResult.complete("", Coverage.exhausted(0L, 0L));
        }
    }

    @Configuration
    static class Services {
        @Bean KafkaAdminService kafkaAdminService() { return mock(KafkaAdminService.class); }
        @Bean SchemaInferenceService schemaInferenceService() { return mock(SchemaInferenceService.class); }
        @Bean MessageFormatterService messageFormatterService() { return new MessageFormatterService(); }
        @Bean DdlGeneratorService ddlGeneratorService() { return mock(DdlGeneratorService.class); }
        @Bean FlinkSqlService flinkSqlService() { return mock(FlinkSqlService.class); }
        @Bean FlinkTableStore flinkTableStore() { return mock(FlinkTableStore.class); }
        @Bean MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
        @Bean FakeWriteTools fakeWriteTools() { return new FakeWriteTools(); }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Services.class)
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(McpServerConfiguration.class);

    @Test
    void nothing_of_the_module_is_built_when_the_server_is_disabled() {
        // The default. A feature that is off must not instantiate half of itself: that half can
        // still fail at startup, and a deployment that never asked for MCP would pay for it.
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ToolGuard.class);
            assertThat(context).doesNotHaveBean(McpCatalogService.class);
            assertThat(context).doesNotHaveBean(TopicMcpTools.class);
            assertThat(context).doesNotHaveBean(McpToolInterceptor.class);
        });
    }

    @Test
    void enabling_the_server_registers_the_read_tools_and_leaves_the_write_surface_closed() {
        runner.withPropertyValues("explorer.mcp.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(TopicMcpTools.class);
            assertThat(context).hasSingleBean(SchemaMcpTools.class);
            assertThat(context).hasSingleBean(SqlMcpTools.class);
            assertThat(context).hasSingleBean(McpCallRecorder.class);
            // Without these two the recorder is written and never called, the output ceiling is
            // advertised and never applied, and a guard's JSON-RPC code never reaches the agent.
            assertThat(context).hasSingleBean(McpToolInterceptor.class);
            assertThat(context).hasSingleBean(McpToolSpecificationPostProcessor.class);

            McpCatalogService catalog = context.getBean(McpCatalogService.class);
            assertThat(catalog.writeSurfaceOpen()).isFalse();
            assertThat(catalog.exposed()).extracting(ToolDescriptor::name)
                    .contains("kex_list_topics", "kex_describe_topic", "kex_preview_messages",
                            "kex_infer_schema", "kex_sql_query", "kex_list_tables")
                    .doesNotContain("kex_produce_message");

            // Withheld, not vanished: the catalogue keeps the row so the console can say why.
            assertThat(catalog.catalog()).filteredOn(d -> d.name().equals("kex_produce_message"))
                    .singleElement()
                    .satisfies(d -> assertThat(d.visibility().state()).isEqualTo(Visibility.State.HIDDEN));
        });
    }

    @Test
    void clearing_readonly_is_what_opens_the_write_surface() {
        runner.withPropertyValues("explorer.mcp.enabled=true", "explorer.mcp.readonly=false")
                .run(context -> {
                    McpCatalogService catalog = context.getBean(McpCatalogService.class);
                    assertThat(catalog.writeSurfaceOpen()).isTrue();
                    assertThat(catalog.exposed()).extracting(ToolDescriptor::name)
                            .contains("kex_produce_message");
                });
    }

    @Test
    void the_configured_scope_reaches_the_guard() {
        runner.withPropertyValues("explorer.mcp.enabled=true",
                        "explorer.mcp.allowed-topic-prefixes=demo.,sandbox.")
                .run(context -> assertThat(context.getBean(ToolGuard.class).properties()
                        .getAllowedTopicPrefixes()).containsExactly("demo.", "sandbox."));
    }
}
