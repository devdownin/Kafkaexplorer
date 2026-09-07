// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.domain.MessageFormat;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Coverage;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.StopReason;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.ToolResult;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Warning;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.ToolCategory;
import com.compagnonsdudev.kafkasqlexplorer.service.DdlGeneratorService;
import com.compagnonsdudev.kafkasqlexplorer.service.SchemaInferenceService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns untyped topics into something an agent can write SQL against.
 *
 * <p>This is the capability no other Kafka MCP server has, and it is why {@code kex_sql_query} is
 * usable at all: a Kafka topic carries bytes, so a model asked to query one has no columns to name
 * and will invent them. Sampling the payload and reporting the columns that were actually seen
 * replaces that invention with a measurement.
 *
 * <p>The inference is a <b>sample</b>, and the tool says so in every response. A field that appears
 * in one message per thousand will be missing from the schema; that is a property of sampling, not
 * a statement about the data, and the difference matters the moment a model concludes a field does
 * not exist.
 *
 * <p>The DDL is emitted with credentials redacted, through the same masker the UI paths use
 * ({@code DdlGeneratorService.maskSensitiveProperties}) — one masker, because a second one drifts,
 * and the drift is only ever noticed by a secret arriving somewhere it should not be.
 */
public class SchemaMcpTools implements ReadOnlyMcpTools {

    /** How many topics one call may infer. Each one samples the cluster; a hundred is a load test. */
    private static final int MAX_TOPICS_PER_CALL = 30;

    private final SchemaInferenceService schemas;
    private final DdlGeneratorService ddl;
    private final ToolGuard guard;

    public SchemaMcpTools(SchemaInferenceService schemas, DdlGeneratorService ddl, ToolGuard guard) {
        this.schemas = schemas;
        this.ddl = ddl;
        this.guard = guard;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.EXPLORATION;
    }

    @McpTool(name = "kex_infer_schema", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = true),
            description = """
            Infer the column names and Flink SQL types of one or more Kafka topics by sampling their
            records, and return a ready-to-run CREATE TABLE for each.

            The schema is inferred from a sample, so a rare field may be missing from it: absence
            here means "not seen in the sample", never "does not exist in the topic". `sampleSize`
            says how many records backed the inference, and topics that could not be inferred are
            named in `coverage.topicsNotReached` rather than dropped.

            Use the returned columns to write kex_sql_query statements; the DDL is only needed when
            you want to register the table yourself. Credentials in the DDL are redacted.""")
    public ToolResult<List<TopicView.InferredSchema>> inferSchema(
            @McpToolParam(description = "Topics to infer, at most 30 per call") List<String> topics,
            @McpToolParam(required = false, description = "JSON, XML, AVRO or AUTO (default: detect per topic)")
            String format) {

        long startedAt = System.currentTimeMillis();
        guard.checkTopicScope(topics);

        List<String> requested = topics == null ? List.of() : topics;
        List<String> selected = requested.stream().limit(MAX_TOPICS_PER_CALL).toList();
        List<Warning> warnings = new ArrayList<>();
        if (requested.size() > selected.size()) {
            warnings.add(Warning.warn("TOPIC_LIMIT",
                    "%d topics requested, %d inferred; one call samples every topic it is given"
                            .formatted(requested.size(), selected.size())));
        }

        MessageFormat requestedFormat = parseFormat(format, warnings);
        List<TopicView.InferredSchema> inferred = new ArrayList<>();
        List<String> notReached = new ArrayList<>(requested.stream().skip(selected.size()).toList());
        long recordsScanned = 0L;

        for (String topic : selected) {
            try {
                List<String> samples = schemas.getSampleMessages(topic);
                recordsScanned += samples.size();
                MessageFormat resolved = requestedFormat == MessageFormat.AUTO
                        ? schemas.detectFormat(topic, samples)
                        : requestedFormat;
                var columns = schemas.inferSchema(topic, resolved, samples);
                if (columns == null || columns.isEmpty()) {
                    // Not an error and not an empty schema: nothing in the sample was parseable,
                    // and a topic listed with zero columns would read as "this topic has no fields".
                    notReached.add(topic);
                    warnings.add(Warning.warn("SCHEMA_NOT_INFERRED",
                            "no schema could be inferred for %s from %d sampled records (format %s)"
                                    .formatted(topic, samples.size(), resolved)));
                    continue;
                }
                inferred.add(new TopicView.InferredSchema(topic, resolved.name(), columns, samples.size(),
                        guard.dlp().scrubDdl(ddl.generateDdl(topic, columns, resolved))));
            } catch (RuntimeException e) {
                notReached.add(topic);
                warnings.add(Warning.warn("SCHEMA_NOT_INFERRED",
                        "inference failed for " + topic + ": " + e.getMessage()));
            }
        }

        long elapsed = System.currentTimeMillis() - startedAt;
        Coverage coverage = notReached.isEmpty()
                ? Coverage.exhausted(selected.size(), recordsScanned, elapsed)
                : Coverage.partial(requested.size(), inferred.size(), notReached,
                        recordsScanned, elapsed, StopReason.PARTIAL_FAILURE, null);

        return new ToolResult<>(inferred, coverage, warnings, requested.size() > selected.size());
    }

    private static MessageFormat parseFormat(String format, List<Warning> warnings) {
        if (format == null || format.isBlank()) {
            return MessageFormat.AUTO;
        }
        try {
            return MessageFormat.valueOf(format.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // Falling back to AUTO rather than refusing: detection is what the caller wanted anyway,
            // and the warning tells it the argument was ignored instead of letting it believe
            // a format it named was honoured.
            warnings.add(Warning.warn("FORMAT_IGNORED",
                    "unknown format '%s'; detecting per topic instead. Known: JSON, XML, AVRO, AUTO."
                            .formatted(format)));
            return MessageFormat.AUTO;
        }
    }
}
