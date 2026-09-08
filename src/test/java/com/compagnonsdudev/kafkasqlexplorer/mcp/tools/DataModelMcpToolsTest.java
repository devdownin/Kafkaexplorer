// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.domain.DataModelColumn;
import com.compagnonsdudev.kafkasqlexplorer.domain.DataModelEntity;
import com.compagnonsdudev.kafkasqlexplorer.domain.DataModelRelation;
import com.compagnonsdudev.kafkasqlexplorer.domain.DataModelResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.MessageFormat;
import com.compagnonsdudev.kafkasqlexplorer.domain.RelationConfidence;
import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.StopReason;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.ToolResult;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.DlpScrubber;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpScopeViolationException;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpToolException;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard;
import com.compagnonsdudev.kafkasqlexplorer.service.DataModelService;
import com.compagnonsdudev.kafkasqlexplorer.service.DataModelSqlService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataModelMcpToolsTest {

    @Mock DataModelService dataModel;

    private DataModelMcpTools toolsScopedTo(String... prefixes) {
        McpProperties properties = new McpProperties();
        properties.setAllowedTopicPrefixes(List.of(prefixes));
        return new DataModelMcpTools(dataModel, new DataModelSqlService(),
                new ToolGuard(properties, new DlpScrubber(properties)));
    }

    private DataModelMcpTools tools() {
        return toolsScopedTo("*");
    }

    private static DataModelEntity entity(String id, String topic, String primaryKey,
                                          DataModelColumn... columns) {
        return new DataModelEntity(id, topic, MessageFormat.JSON, List.of(columns), primaryKey, 42L);
    }

    private static DataModelColumn column(String name, boolean primaryKey, String references,
                                          String keyBase) {
        return new DataModelColumn(name, "STRING", primaryKey, references, keyBase);
    }

    private final DataModelEntity orders = entity("demo.orders", "demo.orders", "order_id",
            column("order_id", true, null, null), column("status", false, null, null));
    private final DataModelEntity payments = entity("demo.payments", "demo.payments", "payment_id",
            column("payment_id", true, null, null),
            column("order_id", false, "demo.orders", "order"));

    private void answer(DataModelResponse response) {
        given(dataModel.buildModel(any(), anyInt())).willReturn(response);
    }

    private static DataModelResponse response(List<DataModelEntity> entities,
                                              List<DataModelRelation> relations,
                                              List<String> warnings, int requested, boolean truncated) {
        return new DataModelResponse(entities, relations, warnings, requested, entities.size(),
                truncated);
    }

    private static DataModelRelation relation(RelationConfidence confidence) {
        return new DataModelRelation("demo.payments", "demo.orders", "order_id", "order_id",
                confidence, "Key columns agree.");
    }

    @Test
    void a_topic_outside_the_scope_is_refused_before_the_service_is_touched() {
        assertThatThrownBy(() -> toolsScopedTo("demo.")
                .deduceDataModel(List.of("prod.payments"), null))
                .isInstanceOf(McpScopeViolationException.class);

        verifyNoInteractions(dataModel);
    }

    @Test
    void every_relation_carries_its_confidence_and_the_sentence_behind_it() {
        // A model told only "there is a relation" writes a join on a MEDIUM as readily as on a
        // HIGH, and the join it writes is a guess wearing a schema's authority.
        answer(response(List.of(orders, payments), List.of(relation(RelationConfidence.MEDIUM)),
                List.of(), 2, false));

        ModelView.Model model = tools().deduceDataModel(List.of("demo.orders", "demo.payments"), null)
                .data();

        assertThat(model.relations()).singleElement().satisfies(relation -> {
            assertThat(relation.confidence()).isEqualTo("MEDIUM");
            assertThat(relation.reason()).isEqualTo("Key columns agree.");
        });
    }

    @Test
    void a_column_named_like_a_foreign_key_that_resolves_to_nothing_is_flagged_not_claimed() {
        // "Points at orders" and "is named like something that would point somewhere, and points
        // nowhere we can see" are different facts, and only the first is a relation.
        DataModelEntity dangling = entity("demo.invoices", "demo.invoices", null,
                column("customer_id", false, null, "customer"));
        answer(response(List.of(dangling), List.of(), List.of(), 1, false));

        ModelView.Column column = tools().deduceDataModel(List.of("demo.invoices"), null)
                .data().entities().get(0).columns().get(0);

        assertThat(column.references()).isNull();
        assertThat(column.referencesUnresolved()).isTrue();
    }

    @Test
    void a_resolved_foreign_key_is_not_flagged_unresolved() {
        answer(response(List.of(payments), List.of(), List.of(), 1, false));

        List<ModelView.Column> columns = tools().deduceDataModel(List.of("demo.payments"), null)
                .data().entities().get(0).columns();

        assertThat(columns.get(1).references()).isEqualTo("demo.orders");
        assertThat(columns.get(1).referencesUnresolved()).isFalse();
    }

    @Test
    void a_topic_that_produced_no_entity_is_named_rather_than_silently_missing() {
        // Otherwise a model over three topics that could read one reads as a model of the cluster.
        answer(response(List.of(orders), List.of(), List.of(), 3, false));

        ToolResult<ModelView.Model> result = tools()
                .deduceDataModel(List.of("demo.orders", "demo.empty", "demo.binary"), null);

        assertThat(result.coverage().topicsNotReached()).containsExactly("demo.empty", "demo.binary");
        assertThat(result.coverage().stopReason()).isEqualTo(StopReason.PARTIAL_FAILURE);
        assertThat(result.warnings()).extracting(w -> w.code()).contains("NO_SCHEMA_INFERRED");
    }

    @Test
    void a_run_that_read_every_topic_is_exhausted() {
        answer(response(List.of(orders, payments), List.of(relation(RelationConfidence.HIGH)),
                List.of(), 2, false));

        ToolResult<ModelView.Model> result = tools()
                .deduceDataModel(List.of("demo.orders", "demo.payments"), null);

        assertThat(result.coverage().stopReason()).isEqualTo(StopReason.EXHAUSTED);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void a_truncated_run_says_the_ceiling_stopped_it() {
        answer(response(List.of(orders), List.of(), List.of("Only the first 1 of 2 were analyzed."),
                2, true));

        ToolResult<ModelView.Model> result = tools()
                .deduceDataModel(List.of("demo.orders", "demo.payments"), null);

        assertThat(result.coverage().stopReason()).isEqualTo(StopReason.TOPIC_LIMIT);
        assertThat(result.truncated()).isTrue();
        assertThat(result.warnings()).extracting(w -> w.code()).contains("DATA_MODEL");
    }

    @Test
    void the_mermaid_caption_says_the_columns_were_inferred_from_a_sample() {
        // A diagram detached from the application cannot be interrogated: if it does not carry its
        // limits it reads as a complete model.
        answer(response(List.of(orders, payments), List.of(relation(RelationConfidence.HIGH)),
                List.of(), 2, false));

        String mermaid = tools().deduceDataModel(List.of("demo.orders", "demo.payments"), null)
                .data().mermaid();

        assertThat(mermaid).contains("erDiagram").contains("inferred from a bounded sample");
    }

    @Test
    void an_empty_topic_list_is_refused_and_says_which_tool_discovers_them() {
        assertThatThrownBy(() -> tools().deduceDataModel(List.of(), null))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("kex_list_topics");

        verifyNoInteractions(dataModel);
    }

    @Test
    void a_join_over_connected_entities_is_written_with_its_caveats() {
        answer(response(List.of(orders, payments), List.of(relation(RelationConfidence.HIGH)),
                List.of(), 2, false));

        ToolResult<ModelView.Join> result = tools().buildJoin(
                List.of("demo.orders", "demo.payments"), List.of("demo.payments", "demo.orders"));

        assertThat(result.data().sql()).contains("JOIN demo.orders AS");
        assertThat(result.data().problem()).isNull();
    }

    @Test
    void a_selection_the_relations_do_not_connect_is_refused_and_the_entity_named() {
        // Inventing an ON clause would assert an equality nothing supports — the one thing this
        // whole surface avoids.
        DataModelEntity lonely = entity("demo.iot", "demo.iot", null,
                column("reading", false, null, null));
        answer(response(List.of(orders, payments, lonely), List.of(relation(RelationConfidence.HIGH)),
                List.of(), 3, false));

        ToolResult<ModelView.Join> result = tools().buildJoin(
                List.of("demo.orders", "demo.payments", "demo.iot"),
                List.of("demo.payments", "demo.orders", "demo.iot"));

        assertThat(result.data().sql()).isNull();
        assertThat(result.data().problem()).contains("demo.iot");
        // A refusal is a valid answer, not an error: the caller asked whether these tables join.
        assertThat(result.warnings()).extracting(w -> w.code()).contains("NO_JOIN");
    }

    @Test
    void a_join_of_one_entity_is_refused_before_any_topic_is_read() {
        assertThatThrownBy(() -> tools().buildJoin(List.of("demo.orders"), List.of("demo.orders")))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("at least two entities");

        verifyNoInteractions(dataModel);
    }

    @Test
    void a_join_without_the_topics_the_model_covers_is_refused() {
        // The join is written from the deduced relations, which need the topics read.
        assertThatThrownBy(() -> tools().buildJoin(List.of(), List.of("a", "b")))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("topics the model covers");

        verifyNoInteractions(dataModel);
    }

    @Test
    void a_join_outside_the_topic_scope_is_refused_before_the_service_is_touched() {
        assertThatThrownBy(() -> toolsScopedTo("demo.")
                .buildJoin(List.of("prod.orders", "prod.payments"), List.of("a", "b")))
                .isInstanceOf(McpScopeViolationException.class);

        verifyNoInteractions(dataModel);
    }
}
