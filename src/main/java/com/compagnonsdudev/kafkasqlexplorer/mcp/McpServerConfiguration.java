// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp;

import com.compagnonsdudev.kafkasqlexplorer.mcp.console.McpAuditReplayService;
import com.compagnonsdudev.kafkasqlexplorer.mcp.console.McpConsoleService;
import com.compagnonsdudev.kafkasqlexplorer.mcp.console.McpEndpointResolver;
import com.compagnonsdudev.kafkasqlexplorer.mcp.console.McpToolInvoker;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.DlpScrubber;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpApprovalStore;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpRateLimiter;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpRuntimeSwitches;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpToolFilter;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpAuditSink;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.KafkaMcpAuditSink;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpCallRecorder;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpCatalogService;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpToolInterceptor;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpToolSpecificationPostProcessor;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.AuditMcpTools;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.ConsumerLagMcpTools;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.DataModelMcpTools;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.KpiMcpTools;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.McpToolset;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.McpTraceStore;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.MutatingMcpTools;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.ReadOnlyMcpTools;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.SchemaMcpTools;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.SqlMcpTools;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.StreamFlowMcpTools;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.TopicMcpTools;
import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.service.AuditService;
import com.compagnonsdudev.kafkasqlexplorer.service.DataModelService;
import com.compagnonsdudev.kafkasqlexplorer.service.DataModelSqlService;
import com.compagnonsdudev.kafkasqlexplorer.service.DdlGeneratorService;
import com.compagnonsdudev.kafkasqlexplorer.service.FlinkSqlService;
import com.compagnonsdudev.kafkasqlexplorer.service.FlinkTableStore;
import com.compagnonsdudev.kafkasqlexplorer.service.KafkaAdminService;
import com.compagnonsdudev.kafkasqlexplorer.service.MessageFormatterService;
import com.compagnonsdudev.kafkasqlexplorer.service.MetricSuggestionService;
import com.compagnonsdudev.kafkasqlexplorer.service.SchemaInferenceService;
import com.compagnonsdudev.kafkasqlexplorer.service.StreamFlowService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
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
    McpToolFilter mcpToolFilter(McpProperties properties) {
        return new McpToolFilter(properties);
    }

    /**
     * The three controls a call passes through that only the interception layer can apply: who is
     * calling, how often, and what an operator switched off a second ago.
     */
    @Bean
    McpRuntimeSwitches mcpRuntimeSwitches(McpProperties properties) {
        return new McpRuntimeSwitches(properties);
    }

    @Bean
    McpRateLimiter mcpRateLimiter(McpProperties properties) {
        return new McpRateLimiter(properties);
    }

    @Bean
    McpApprovalStore mcpApprovalStore(McpProperties properties) {
        return new McpApprovalStore(properties);
    }

    @Bean
    McpCatalogService mcpCatalogService(McpProperties properties, McpToolFilter filter) {
        return new McpCatalogService(properties, filter);
    }

    /**
     * The append-only trail. A bean rather than a component so a deployment with the server off
     * builds no producer, and so the sink is absent — not a no-op — when there is nothing to record.
     */
    @Bean
    McpAuditSink mcpAuditSink(KafkaConfig kafkaConfig, McpProperties properties) {
        return new KafkaMcpAuditSink(kafkaConfig, properties);
    }

    @Bean
    McpAuditReplayService mcpAuditReplayService(KafkaConfig kafkaConfig, McpProperties properties) {
        return new McpAuditReplayService(kafkaConfig, properties);
    }

    @Bean
    McpCallRecorder mcpCallRecorder(McpProperties properties, MeterRegistry meters,
                                    ObjectProvider<McpAuditSink> auditSink) {
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
                                          DlpScrubber dlp, McpCallRecorder recorder,
                                          McpRuntimeSwitches switches, McpRateLimiter rateLimiter,
                                          McpApprovalStore approvals) {
        return new McpToolInterceptor(properties, guard, dlp, recorder, switches, rateLimiter,
                approvals);
    }

    /**
     * Static, and it has to be: a {@code BeanPostProcessor} declared by an instance method makes
     * its whole configuration class instantiate before the container is ready to configure it,
     * which Spring reports as a wall of "is not eligible for post-processing" warnings and which
     * would, here, build the properties bean before its binding is available.
     */
    @Bean
    static McpToolSpecificationPostProcessor mcpToolSpecificationPostProcessor(
            ObjectProvider<McpToolInterceptor> interceptor, ObjectProvider<McpToolFilter> filter) {
        return new McpToolSpecificationPostProcessor(interceptor, filter);
    }

    /**
     * The console's read model. Beans here rather than components, so a deployment with the server
     * off builds none of it — the controller answers "disabled" from an absent provider instead.
     */
    @Bean
    McpEndpointResolver mcpEndpointResolver(org.springframework.core.env.Environment environment) {
        return new McpEndpointResolver(environment);
    }

    @Bean
    McpConsoleService mcpConsoleService(McpProperties properties, McpCatalogService catalog,
                                        McpCallRecorder recorder, McpEndpointResolver endpoints) {
        return new McpConsoleService(properties, catalog, recorder, endpoints);
    }

    @Bean
    McpToolInvoker mcpToolInvoker(
            ObjectProvider<java.util.List<io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification>> toolSpecs) {
        return new McpToolInvoker(toolSpecs);
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

    @Bean
    DataModelMcpTools dataModelMcpTools(DataModelService dataModel, DataModelSqlService sql,
                                        ToolGuard guard) {
        return new DataModelMcpTools(dataModel, sql, guard);
    }

    /**
     * The audit pair. {@code AuditService} holds one run at a time for the whole process, which is
     * why {@code kex_run_audit} reports having <em>attached</em> to a run rather than started one:
     * an agent and an operator share that runtime, and a second caller silently reading the first
     * one's scope is the failure this module exists to prevent.
     */
    @Bean
    AuditMcpTools auditMcpTools(AuditService audit, ToolGuard guard) {
        return new AuditMcpTools(audit, guard);
    }

    @Bean
    KpiMcpTools kpiMcpTools(MetricSuggestionService suggestions, ToolGuard guard) {
        return new KpiMcpTools(suggestions, guard);
    }

    /**
     * One store per deployment, holding what a paused trace needs to be continued.
     *
     * <p>The module prefers stateless tools, and this is the deliberate exception: a resume token
     * that carried the hits it had already found would be a payload of hundreds of records passing
     * through the model's context twice, costing more than re-running the trace. Bounded and
     * expiring — see {@link McpTraceStore}.
     */
    @Bean
    McpTraceStore mcpTraceStore() {
        return new McpTraceStore();
    }

    @Bean
    StreamFlowMcpTools streamFlowMcpTools(StreamFlowService streamFlow, ToolGuard guard,
                                          McpTraceStore traces) {
        return new StreamFlowMcpTools(streamFlow, guard, traces);
    }

    @Bean
    ConsumerLagMcpTools consumerLagMcpTools(KafkaAdminService kafka, ToolGuard guard) {
        return new ConsumerLagMcpTools(kafka, guard);
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
                                            McpToolFilter filter,
                                            ObjectProvider<ReadOnlyMcpTools> readTools,
                                            ObjectProvider<MutatingMcpTools> writeTools) {
        List<McpToolset> all = new ArrayList<>(readTools.stream().toList());
        List<McpToolset> exposed = new ArrayList<>(all);

        List<MutatingMcpTools> mutating = writeTools.stream().toList();
        all.addAll(mutating);
        if (!properties.isReadonly()) {
            exposed.addAll(mutating);
        }

        catalog.publish(all, exposed);

        log.info("MCP server enabled: {} tool(s) exposed, {} withheld, readonly={}, "
                        + "allowed-topic-prefixes={}",
                catalog.exposed().size(),
                catalog.catalog().size() - catalog.exposed().size(),
                properties.isReadonly(),
                properties.getAllowedTopicPrefixes());
        // A name in either list that no tool carries is a typo, and a deny-list with a typo in it
        // silences nothing while reading as though it did — the exact failure this setting was
        // added to prevent.
        Set<String> registered = catalog.catalog().stream()
                .map(com.compagnonsdudev.kafkasqlexplorer.mcp.observability.ToolDescriptor::name)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> unknownDenied = filter.deniedButUnknown(registered);
        Set<String> unknownAllowed = filter.allowedButUnknown(registered);
        if (!unknownDenied.isEmpty()) {
            log.warn("explorer.mcp.tools.denied names {} tool(s) this server does not have, so they "
                    + "deny nothing: {}. Registered tools: {}", unknownDenied.size(), unknownDenied,
                    registered);
        }
        if (!unknownAllowed.isEmpty()) {
            log.warn("explorer.mcp.tools.allowed names {} tool(s) this server does not have: {}",
                    unknownAllowed.size(), unknownAllowed);
        }
        return new McpCatalogPublisher();
    }

    /** A marker bean: publishing happens in the factory method, this is what makes it eager. */
    public static final class McpCatalogPublisher {
    }
}
