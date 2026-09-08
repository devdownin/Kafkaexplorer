// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.domain.DataModelColumn;
import com.compagnonsdudev.kafkasqlexplorer.domain.DataModelEntity;
import com.compagnonsdudev.kafkasqlexplorer.domain.DataModelRelation;
import com.compagnonsdudev.kafkasqlexplorer.domain.DataModelResponse;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Coverage;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Measured;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.StopReason;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.ToolResult;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Warning;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpErrorCode;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpGuard;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpToolException;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.ToolCategory;
import com.compagnonsdudev.kafkasqlexplorer.service.DataModelService;
import com.compagnonsdudev.kafkasqlexplorer.service.DataModelSqlService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import java.util.ArrayList;
import java.util.List;

/**
 * "What is in this cluster, and how does it fit together?" — deduced, with the evidence attached.
 *
 * <p>An adapter over {@code DataModelService}, which infers each topic's schema and the links
 * between them, and over {@code DataModelSqlService}, which turns those links into a query.
 *
 * <p><b>Every relation carries its confidence and the sentence that produced it.</b> That pairing
 * is the whole value here: a model told only "orders relates to payments" writes a join on a
 * {@code MEDIUM} — names agree, nothing else does — exactly as readily as on a {@code HIGH}, and
 * the join it writes is a guess wearing a schema's authority.
 */
public class DataModelMcpTools implements ReadOnlyMcpTools {

    private final DataModelService dataModel;
    private final DataModelSqlService sql;
    private final ToolGuard guard;

    public DataModelMcpTools(DataModelService dataModel, DataModelSqlService sql, ToolGuard guard) {
        this.dataModel = dataModel;
        this.sql = sql;
        this.guard = guard;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.EXPLORATION;
    }

    @McpTool(name = "kex_deduce_data_model", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = true),
            description = """
            Read several topics as tables and deduce the relations between them: entities with
            their columns and detected key, plus the links, each with the evidence that produced it.
            Also returns the model as a Mermaid erDiagram.

            READ `confidence` BEFORE WRITING A JOIN ON A RELATION:
              HIGH    the key columns agree — this is a join predicate
              MEDIUM  the names match and nothing else does
              LOW     a shared key column, no more
            `reason` is the sentence behind the grade. A MEDIUM written into a query is a guess
            wearing a schema's authority.

            A column with `referencesUnresolved: true` is named like a foreign key and points at
            nothing this model found — that is NOT a relation, and it is the honest form of the
            "?" a diagram would draw.

            `coverage` says how many topics were actually analysed. Kafka has no schema catalogue,
            so every column here is inferred from a bounded sample: a field absent from the sample
            is absent from the model, not from the topic.""")
    public ToolResult<ModelView.Model> deduceDataModel(
            @McpToolParam(description = "The topics to read as tables") List<String> topics,
            @McpToolParam(required = false, description = "How many of them to analyse; clamped by the server ceiling")
            Integer maxTopics) {

        // Before any read: a scope check that runs afterwards has already disclosed what it refused.
        guard.checkTopicScope(topics);

        if (topics == null || topics.isEmpty()) {
            throw new McpToolException(McpErrorCode.VALIDATION_FAILED, McpGuard.VALIDATION,
                    "at least one topic is required — this tool reads topics as tables, it does "
                            + "not discover them; use kex_list_topics first");
        }

        long startedAt = System.currentTimeMillis();
        int cap = guard.clampTopics(maxTopics);
        List<Warning> warnings = new ArrayList<>(guard.clampWarnings("maxTopics", maxTopics, cap));

        DataModelResponse model;
        try {
            model = dataModel.buildModel(topics, cap);
        } catch (IllegalArgumentException e) {
            throw new McpToolException(McpErrorCode.VALIDATION_FAILED, McpGuard.VALIDATION,
                    e.getMessage());
        } catch (Exception e) {
            throw new McpToolException(McpErrorCode.DEPENDENCY_UNAVAILABLE,
                    "the data model could not be built: " + rootMessage(e));
        }

        model.warnings().forEach(w -> warnings.add(Warning.warn("DATA_MODEL", guard.dlp().scrub(w))));

        List<String> notReached = model.entities().size() < topics.size()
                ? topics.stream().filter(topic -> model.entities().stream()
                        .noneMatch(entity -> topic.equals(entity.topic()))).toList()
                : List.of();
        if (!notReached.isEmpty()) {
            warnings.add(Warning.warn("NO_SCHEMA_INFERRED",
                    ("%d topic(s) produced no entity: nothing readable was sampled, or the payloads "
                            + "are in a format inference does not cover. They are named in "
                            + "coverage.topicsNotReached.").formatted(notReached.size())));
        }

        ModelView.Model view = new ModelView.Model(
                model.entities().stream().map(DataModelMcpTools::entity).toList(),
                model.relations().stream().map(DataModelMcpTools::relation).toList(),
                sql.toMermaidEr(model, caption(model)));

        Coverage coverage = new Coverage(
                model.topicsRequested(),
                model.topicsAnalyzed(),
                notReached,
                0L,
                System.currentTimeMillis() - startedAt,
                model.truncated() ? StopReason.TOPIC_LIMIT
                        : notReached.isEmpty() ? StopReason.EXHAUSTED : StopReason.PARTIAL_FAILURE,
                null, null, null);

        return new ToolResult<>(view, coverage, warnings, model.truncated());
    }

    @McpTool(name = "kex_build_join", annotations = @McpTool.McpAnnotations(
            readOnlyHint = true, destructiveHint = false, openWorldHint = true),
            description = """
            Write the SQL that joins several entities along the relations deduced between them,
            from a model kex_deduce_data_model produced. Pass the same topics, plus the entity ids
            to join — an entity id is the registered table name, which is the topic's name.

            IT REFUSES RATHER THAN INVENTING A PREDICATE. A selection the deduced relations do not
            connect comes back with `sql: null` and a `problem` naming the unreachable entity;
            manufacturing an ON clause would assert an equality nothing supports, which is exactly
            the mistake a model makes when handed a list of tables and asked to join them.

            READ `caveats` BEFORE RUNNING THE QUERY. They name what it assumes: a join column that
            is a nested path, columns left out of the projection, and the fact that a regular join
            keeps every side in Flink's state — fine on a demo topic, not on a real one.""")
    public ToolResult<ModelView.Join> buildJoin(
            @McpToolParam(description = "The topics the model covers") List<String> topics,
            @McpToolParam(description = "Entity ids to join, two or more — the topic names")
            List<String> entities) {

        guard.checkTopicScope(topics);

        if (entities == null || entities.size() < 2) {
            throw new McpToolException(McpErrorCode.VALIDATION_FAILED, McpGuard.VALIDATION,
                    "at least two entities are required — a join of one table is the table");
        }
        if (topics == null || topics.isEmpty()) {
            throw new McpToolException(McpErrorCode.VALIDATION_FAILED, McpGuard.VALIDATION,
                    "the topics the model covers are required: the join is written from the "
                            + "relations deduced over them, not from the entity names alone");
        }

        long startedAt = System.currentTimeMillis();
        DataModelResponse model;
        try {
            model = dataModel.buildModel(topics, guard.clampTopics(null));
        } catch (IllegalArgumentException e) {
            throw new McpToolException(McpErrorCode.VALIDATION_FAILED, McpGuard.VALIDATION,
                    e.getMessage());
        } catch (Exception e) {
            throw new McpToolException(McpErrorCode.DEPENDENCY_UNAVAILABLE,
                    "the data model could not be built: " + rootMessage(e));
        }

        DataModelSqlService.JoinSql join = sql.buildJoin(entities, model);

        List<Warning> warnings = new ArrayList<>();
        model.warnings().forEach(w -> warnings.add(Warning.warn("DATA_MODEL", guard.dlp().scrub(w))));
        join.caveats().forEach(caveat -> warnings.add(Warning.warn("JOIN_CAVEAT", caveat)));
        // A refusal is a valid answer, not an error: the caller asked whether these tables join,
        // and "they do not, and here is which one is unreachable" answers it exactly.
        if (join.problem() != null) {
            warnings.add(Warning.warn("NO_JOIN", join.problem()));
        }

        Coverage coverage = new Coverage(
                model.topicsRequested(), model.topicsAnalyzed(), List.of(), 0L,
                System.currentTimeMillis() - startedAt,
                model.truncated() ? StopReason.TOPIC_LIMIT : StopReason.EXHAUSTED,
                null, null, null);

        return new ToolResult<>(
                new ModelView.Join(join.sql(), join.caveats(), join.problem()),
                coverage, warnings, model.truncated());
    }

    /** The Mermaid header: a diagram detached from the app has to carry its own limits. */
    private static List<String> caption(DataModelResponse model) {
        List<String> caption = new ArrayList<>();
        caption.add("Deduced data model — %d entities, %d relations"
                .formatted(model.entities().size(), model.relations().size()));
        caption.add("%d of %d topics analysed%s".formatted(model.topicsAnalyzed(),
                model.topicsRequested(), model.truncated() ? ", stopped at the ceiling" : ""));
        caption.add("Columns are inferred from a bounded sample, not read from a catalogue.");
        return caption;
    }

    private static ModelView.Entity entity(DataModelEntity entity) {
        return new ModelView.Entity(
                entity.id(),
                entity.topic(),
                entity.format() == null ? null : entity.format().name(),
                entity.columns().stream().map(DataModelMcpTools::column).toList(),
                entity.primaryKey(),
                Measured.ofNullable(entity.messageCount(),
                        "the broker did not return offsets for this topic"));
    }

    private static ModelView.Column column(DataModelColumn column) {
        return new ModelView.Column(
                column.name(),
                column.type(),
                column.primaryKey(),
                column.references(),
                // The spec's "?": named like a foreign key, resolving to nothing this model found.
                column.keyBase() != null && column.references() == null);
    }

    private static ModelView.Relation relation(DataModelRelation relation) {
        return new ModelView.Relation(
                relation.from(), relation.to(), relation.fromColumn(), relation.toColumn(),
                relation.confidence() == null ? null : relation.confidence().name(),
                relation.reason());
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
