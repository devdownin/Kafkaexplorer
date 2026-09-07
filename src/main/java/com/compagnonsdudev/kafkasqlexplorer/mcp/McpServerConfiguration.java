// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp;

import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.DlpScrubber;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpAuditSink;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpCallRecorder;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpCatalogService;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpToolInterceptor;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpToolSpecificationPostProcessor;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.McpToolset;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.MutatingMcpTools;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.ReadOnlyMcpTools;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Registers the MCP surface — and, just as importantly, decides what is <em>not</em> registered.
 *
 * <p><b>The read-only guard is at registration, not invocation, and Spring AI forces that anyway.</b>
 * The framework scans {@code @McpTool} methods on every bean in the context
 * ({@code McpServerAnnotationScannerAutoConfiguration}), so a tool that exists as a bean is listed
 * by {@code tools/list} no matter what its body then refuses. The only way to make a tool genuinely
 * absent is for its bean not to exist — hence the tool objects are declared here, conditionally,
 * rather than annotated {@code @Component} and left to component scanning.
 *
 * <p>That is why {@link McpCatalogService#publish} is told about the hidden ones too: the console
 * has to be able to say <em>why</em> a tool is missing, and by then the bean it would describe was
 * never created. The descriptors are introspected from the same objects the protocol serves, so
 * the two cannot drift.
 *
 * <p>The whole configuration is conditional on {@code explorer.mcp.enabled}, which ships false.
 * Nothing here loads, no endpoint binds, and the console reports the server as disabled rather than
 * as idle.
 */
@Configuration
@EnableConfigurationProperties(McpProperties.class)
@ConditionalOnProperty(prefix = "explorer.mcp", name = "enabled", havingValue = "true")
public class McpServerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(McpServerConfiguration.class);

    /**
     * The guard and its redactor. Beans here rather than {@code @Component}s so that a deployment
     * with {@code explorer.mcp.enabled=false} builds none of this module — a disabled feature that
     * still instantiates half of itself is a disabled feature that can still fail at startup.
     */
    @Bean
    DlpScrubber dlpScrubber(McpProperties properties) {
        return new DlpScrubber(properties);
    }

    @Bean
    ToolGuard toolGuard(McpProperties properties, DlpScrubber dlp) {
        return new ToolGuard(properties, dlp);
    }

    @Bean
    McpCatalogService mcpCatalogService(McpProperties properties) {
        return new McpCatalogService(properties);
    }

    @Bean
    McpCallRecorder mcpCallRecorder(McpProperties properties, MeterRegistry meters,
                                    ObjectProvider<McpAuditSink> auditSink) {
        // No sink until phase 5 wires the append-only topic. The recorder is built against the
        // interface from the start so its failure counter, and the console field that surfaces it,
        // exist before there is anything to lose — but nothing pretends to persist in the meantime.
        return new McpCallRecorder(properties, auditSink.getIfAvailable(), meters);
    }

    /**
     * The layer every tool call passes through. Without it three of this module's claims are not
     * true: the metrics stay at zero on a serving deployment, {@code hard-max-output-bytes} is a
     * ceiling the catalogue advertises and nothing enforces, and a guard's KIP-1318 code never
     * reaches the agent. See {@link McpToolInterceptor}.
     */
    @Bean
    McpToolInterceptor mcpToolInterceptor(McpProperties properties, ToolGuard guard,
                                          DlpScrubber dlp, McpCallRecorder recorder) {
        return new McpToolInterceptor(properties, guard, dlp, recorder);
    }

    /**
     * Static, and it has to be: a {@code BeanPostProcessor} declared by an instance method makes
     * its whole configuration class instantiate before the container is ready to configure it,
     * which Spring reports as a wall of "is not eligible for post-processing" warnings and which
     * would, here, build the properties bean before its binding is available.
     */
    @Bean
    static McpToolSpecificationPostProcessor mcpToolSpecificationPostProcessor(
            ObjectProvider<McpToolInterceptor> interceptor) {
        return new McpToolSpecificationPostProcessor(interceptor);
    }

    @Bean
    TopicMcpTools topicMcpTools(KafkaAdminService kafka, SchemaInferenceService schemas,
                                MessageFormatterService formatter, ToolGuard guard) {
        return new TopicMcpTools(kafka, schemas, formatter, guard);
    }

    @Bean
    SchemaMcpTools schemaMcpTools(SchemaInferenceService schemas, DdlGeneratorService ddl, ToolGuard guard) {
        return new SchemaMcpTools(schemas, ddl, guard);
    }

    @Bean
    SqlMcpTools sqlMcpTools(FlinkSqlService flink, FlinkTableStore tableStore, ToolGuard guard) {
        return new SqlMcpTools(flink, tableStore, guard);
    }

    /**
     * Publishes the catalogue from the toolsets that exist and the ones that were withheld.
     *
     * <p>{@code ObjectProvider} rather than {@code List} injection so an empty write surface — the
     * default — is an empty stream instead of a missing-bean failure at startup.
     */
    @Bean
    McpCatalogPublisher mcpCatalogPublisher(McpProperties properties,
                                            McpCatalogService catalog,
                                            ObjectProvider<ReadOnlyMcpTools> readTools,
                                            ObjectProvider<MutatingMcpTools> writeTools) {
        List<McpToolset> all = new ArrayList<>(readTools.stream().toList());
        List<McpToolset> exposed = new ArrayList<>(all);

        List<MutatingMcpTools> mutating = writeTools.stream().toList();
        all.addAll(mutating);
        if (!properties.isReadonly()) {
            exposed.addAll(mutating);
        }

        Set<String> denied = names(properties.getTools().getDenied());
        Set<String> allowed = names(properties.getTools().getAllowed());
        catalog.publish(all, exposed);

        log.info("MCP server enabled: {} tool(s) exposed, {} withheld, readonly={}, "
                        + "allowed-topic-prefixes={}",
                catalog.exposed().size(),
                catalog.catalog().size() - catalog.exposed().size(),
                properties.isReadonly(),
                properties.getAllowedTopicPrefixes());
        if (!denied.isEmpty() || !allowed.contains(McpProperties.ANY)) {
            // Phase 1 registers whole toolsets; per-name allow/deny lands with the console's
            // runtime toggle in phase 2. Saying so is better than a setting that quietly does
            // nothing — a deny-list an operator believes is in force is worse than none.
            log.warn("explorer.mcp.tools.allowed/.denied are configured but per-tool filtering is "
                    + "not active yet; all registered tools are exposed. Use explorer.mcp.readonly "
                    + "to withhold the write surface.");
        }
        return new McpCatalogPublisher();
    }

    private static Set<String> names(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(Arrays.stream(csv.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).toList());
    }

    /** A marker bean: publishing happens in the factory method, this is what makes it eager. */
    public static final class McpCatalogPublisher {
    }
}
