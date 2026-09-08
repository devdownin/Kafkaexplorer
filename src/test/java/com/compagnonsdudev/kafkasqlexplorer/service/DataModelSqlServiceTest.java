// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.domain.DataModelColumn;
import com.compagnonsdudev.kafkasqlexplorer.domain.DataModelEntity;
import com.compagnonsdudev.kafkasqlexplorer.domain.DataModelRelation;
import com.compagnonsdudev.kafkasqlexplorer.domain.DataModelResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.MessageFormat;
import com.compagnonsdudev.kafkasqlexplorer.domain.RelationConfidence;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same cases as {@code dataModelGraph.test.ts}, in Java.
 *
 * <p>Deliberately parallel: the page keeps its own copy so the join preview can keep up with the
 * pointer, and this suite is what makes a divergence between the two fail on one side. When a case
 * here changes, the TypeScript one changes with it — see {@link DataModelSqlService}'s note.
 */
class DataModelSqlServiceTest {

    private final DataModelSqlService service = new DataModelSqlService();

    private static DataModelColumn col(String name) {
        return new DataModelColumn(name, "STRING", false, null, null);
    }

    private static DataModelColumn key(String name) {
        return new DataModelColumn(name, "STRING", true, null, null);
    }

    private static DataModelColumn fk(String name, String references) {
        return new DataModelColumn(name, "STRING", false, references, null);
    }

    private static DataModelEntity entity(String id, String topic, String primaryKey,
                                          DataModelColumn... columns) {
        return new DataModelEntity(id, topic, MessageFormat.JSON, List.of(columns), primaryKey, 0L);
    }

    private static DataModelRelation rel(String from, String to, String fromColumn, String toColumn) {
        return new DataModelRelation(from, to, fromColumn, toColumn, RelationConfidence.HIGH,
                "%s names %s.".formatted(fromColumn, to));
    }

    private static DataModelResponse model(List<DataModelEntity> entities,
                                           List<DataModelRelation> relations) {
        return new DataModelResponse(entities, relations, List.of(), entities.size(),
                entities.size(), false);
    }

    private final DataModelEntity orders = entity("demo_orders", "demo.orders.received", "order_id",
            key("order_id"), col("status"));
    private final DataModelEntity payments = entity("demo_payments", "demo.payments.authorized",
            "payment_id", key("payment_id"), fk("order_id", "demo_orders"));
    private final DataModelEntity shipments = entity("demo_shipments", "demo.shipments.dispatched",
            "shipment_id", key("shipment_id"), fk("order_id", "demo_orders"));

    private final List<DataModelRelation> threeWay = List.of(
            rel("demo_payments", "demo_orders", "order_id", "order_id"),
            rel("demo_shipments", "demo_orders", "order_id", "order_id"));

    @Test
    void every_topic_gets_a_distinct_sql_legal_alias() {
        List<String> aliases = DataModelSqlService.joinAliasesFor(
                List.of(orders.topic(), payments.topic(), shipments.topic()));

        assertThat(new LinkedHashSet<>(aliases)).hasSize(3);
        assertThat(aliases).allMatch(alias -> alias.matches("^[A-Za-z_].*"));
    }

    @Test
    void homonyms_are_numbered_rather_than_emitted_twice() {
        // Two topics whose distinctive segment is a digit: no usable name. An alias starting with a
        // digit is not a SQL identifier, and the query would die at the parser.
        List<String> aliases = DataModelSqlService.joinAliasesFor(
                List.of("demo.orders.1", "demo.orders.2"));

        assertThat(new LinkedHashSet<>(aliases)).hasSize(2);
        assertThat(aliases).allMatch(alias -> alias.matches("^[A-Za-z_].*"));
    }

    @Test
    void three_entities_are_joined_along_their_deduced_relations() {
        DataModelSqlService.JoinSql join = service.buildJoin(
                List.of("demo_payments", "demo_orders", "demo_shipments"),
                model(List.of(orders, payments, shipments), threeWay));

        assertThat(join.problem()).isNull();
        assertThat(join.sql()).contains("FROM demo_payments AS");
        // Two JOINs for three tables: a spanning tree, not a cartesian product.
        assertThat(join.sql().lines().filter(line -> line.startsWith("JOIN "))).hasSize(2);
        assertThat(join.sql()).endsWith("LIMIT 50");
    }

    @Test
    void every_join_predicate_cites_a_table_already_introduced() {
        // A JOIN whose predicate names a table not yet in the query does not parse.
        DataModelSqlService.JoinSql join = service.buildJoin(
                List.of("demo_payments", "demo_orders", "demo_shipments"),
                model(List.of(orders, payments, shipments), threeWay));

        Set<String> introduced = new LinkedHashSet<>();
        Pattern source = Pattern.compile("^(?:FROM|JOIN) \\S+ AS (\\S+)");
        Pattern on = Pattern.compile("^ {2}ON (\\S+?)\\.\\S+ = (\\S+?)\\.");
        for (String line : join.sql().split("\n")) {
            Matcher declared = source.matcher(line);
            if (declared.find()) {
                introduced.add(declared.group(1));
                continue;
            }
            Matcher predicate = on.matcher(line);
            if (predicate.find()) {
                assertThat(introduced).contains(predicate.group(1), predicate.group(2));
            }
        }
    }

    @Test
    void a_set_the_relations_do_not_connect_is_refused_and_named() {
        // The rule this whole class exists for: inventing an ON clause would assert an equality
        // nothing supports, which is the one thing the diagram avoids everywhere else.
        DataModelEntity lonely = entity("demo_iot", "demo.iot.sensors", null, col("reading"));

        DataModelSqlService.JoinSql join = service.buildJoin(
                List.of("demo_payments", "demo_orders", "demo_iot"),
                model(List.of(orders, payments, lonely),
                        List.of(rel("demo_payments", "demo_orders", "order_id", "order_id"))));

        assertThat(join.sql()).isNull();
        assertThat(join.problem()).contains("demo_iot");
    }

    @Test
    void fewer_than_two_entities_is_refused() {
        DataModelSqlService.JoinSql join = service.buildJoin(List.of("demo_orders"),
                model(List.of(orders), List.of()));

        assertThat(join.sql()).isNull();
        assertThat(join.problem()).contains("at least two");
    }

    @Test
    void an_entity_the_model_does_not_hold_is_named_rather_than_dropped() {
        // Dropping it would silently join two of the three tables asked for and answer as if that
        // were the question.
        DataModelSqlService.JoinSql join = service.buildJoin(
                List.of("demo_payments", "demo_orders", "demo_ghost"),
                model(List.of(orders, payments),
                        List.of(rel("demo_payments", "demo_orders", "order_id", "order_id"))));

        assertThat(join.sql()).isNull();
        assertThat(join.problem()).contains("demo_ghost");
    }

    @Test
    void a_relation_naming_no_column_falls_back_to_the_targets_detected_key() {
        DataModelSqlService.JoinSql join = service.buildJoin(
                List.of("demo_payments", "demo_orders"),
                model(List.of(orders, payments),
                        List.of(rel("demo_payments", "demo_orders", "order_id", null))));

        assertThat(join.sql()).contains(".order_id");
        assertThat(join.problem()).isNull();
    }

    @Test
    void an_edge_whose_target_has_neither_a_named_column_nor_a_key_is_refused() {
        DataModelEntity keyless = entity("demo_orders", "demo.orders.received", null, col("status"));

        DataModelSqlService.JoinSql join = service.buildJoin(
                List.of("demo_payments", "demo_orders"),
                model(List.of(keyless, payments),
                        List.of(rel("demo_payments", "demo_orders", "order_id", null))));

        assertThat(join.sql()).isNull();
        assertThat(join.problem()).contains("demo_orders");
    }

    @Test
    void nested_fields_are_left_out_of_the_projection_and_the_count_is_reported() {
        // A dotted path behind a table alias does not mean in Flink SQL what it means on the
        // diagram, and a query that fails on its first run is worse than a shorter one.
        DataModelEntity nested = entity("demo_payments", "demo.payments.authorized", "payment_id",
                key("payment_id"), fk("order_id", "demo_orders"), col("card.last4"));

        DataModelSqlService.JoinSql join = service.buildJoin(
                List.of("demo_payments", "demo_orders"),
                model(List.of(orders, nested),
                        List.of(rel("demo_payments", "demo_orders", "order_id", "order_id"))));

        assertThat(join.sql()).doesNotContain("card.last4");
        assertThat(join.caveats()).anyMatch(caveat -> caveat.contains("1 nested field"));
    }

    @Test
    void the_projection_is_capped_and_keeps_the_join_column_whatever_the_cap() {
        DataModelColumn[] wide = new DataModelColumn[20];
        wide[0] = fk("order_id", "demo_orders");
        for (int i = 1; i < wide.length; i++) {
            wide[i] = col("f_" + i);
        }
        DataModelEntity fat = entity("demo_payments", "demo.payments.authorized", null, wide);

        DataModelSqlService.JoinSql join = service.buildJoin(
                List.of("demo_payments", "demo_orders"),
                model(List.of(orders, fat),
                        List.of(rel("demo_payments", "demo_orders", "order_id", "order_id"))));

        List<String> projected = Arrays.stream(join.sql().split("\n"))
                .filter(line -> line.startsWith("    payments.")).toList();
        assertThat(projected).hasSize(5);
        // Without the join column the query reads as though the tables were unrelated.
        assertThat(projected.get(0)).matches("^ {4}payments\\.order_id,?$");
    }

    @Test
    void the_query_names_its_columns_rather_than_selecting_star() {
        // Both sides carry order_id, so the star produces two homonymous columns.
        DataModelSqlService.JoinSql join = service.buildJoin(
                List.of("demo_payments", "demo_orders"),
                model(List.of(orders, payments),
                        List.of(rel("demo_payments", "demo_orders", "order_id", "order_id"))));

        assertThat(join.sql()).doesNotContain("SELECT *");
        assertThat(join.sql()).contains("    payments.order_id").contains("    orders.order_id");
    }

    @Test
    void the_evidence_and_the_unbounded_join_caveat_travel_in_the_query_itself() {
        // Not only in a tooltip nobody exports: the SQL is what gets pasted into the editor.
        DataModelSqlService.JoinSql join = service.buildJoin(
                List.of("demo_payments", "demo_orders"),
                model(List.of(orders, payments),
                        List.of(rel("demo_payments", "demo_orders", "order_id", "order_id"))));

        assertThat(join.sql()).contains("-- order_id names demo_orders.");
        assertThat(join.sql()).contains("high");
        assertThat(join.sql()).contains("Flink keeps every side in state");
    }

    @Test
    void the_mermaid_diagram_carries_the_caption_and_marks_keys() {
        String mermaid = service.toMermaidEr(
                model(List.of(orders, payments),
                        List.of(rel("demo_payments", "demo_orders", "order_id", "order_id"))),
                List.of("Deduced data model", "2 of 2 topics analysed"));

        assertThat(mermaid).startsWith("%% Deduced data model\n%% 2 of 2 topics analysed\nerDiagram");
        assertThat(mermaid).contains("demo_orders ||--o{ demo_payments : \"order_id (high)\"");
        assertThat(mermaid).contains("STRING order_id PK");
        assertThat(mermaid).contains("STRING order_id FK");
    }

    @Test
    void a_mermaid_identifier_is_sanitised_and_the_original_name_kept_as_a_comment() {
        // Sanitising must not cost information: the dotted name is what an operator recognises.
        DataModelEntity nested = entity("demo_payments", "demo.payments.authorized", null,
                col("card.last4"));

        String mermaid = service.toMermaidEr(model(List.of(nested), List.of()), List.of());

        assertThat(mermaid).contains("STRING card_last4 \"card.last4\"");
    }

    @Test
    void a_relation_pointing_outside_the_drawn_entities_is_not_drawn() {
        String mermaid = service.toMermaidEr(
                model(List.of(orders), List.of(rel("demo_ghost", "demo_orders", "order_id", "order_id"))),
                List.of());

        assertThat(mermaid).doesNotContain("demo_ghost");
    }
}
