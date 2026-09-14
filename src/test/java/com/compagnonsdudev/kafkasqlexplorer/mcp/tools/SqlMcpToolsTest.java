// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.domain.QueryRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryResult;
import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.mcp.contract.StopReason;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.DlpScrubber;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpToolException;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard;
import com.compagnonsdudev.kafkasqlexplorer.service.FlinkSqlService;
import com.compagnonsdudev.kafkasqlexplorer.service.FlinkTableStore;
import com.compagnonsdudev.kafkasqlexplorer.service.KafkaAdminService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SqlMcpToolsTest {

    @Mock FlinkSqlService flink;
    @Mock FlinkTableStore tableStore;
    @Mock KafkaAdminService kafka;

    private final McpProperties properties = new McpProperties();

    private SqlMcpTools tools() {
        return new SqlMcpTools(flink, tableStore, kafka,
                new ToolGuard(properties, new DlpScrubber(properties)));
    }

    @Test
    void an_engine_error_comes_back_as_the_planners_own_message_not_as_an_empty_result() {
        // An empty result set is the one shape a model reads as "no matching rows". Returning it
        // for a misspelled column makes the model conclude the data is absent and stop looking —
        // while the planner's sentence names the column and the position and gets it right next go.
        given(flink.executeSync(any())).willReturn(new QueryResult(List.of(), List.of(), 4L,
                "SQL parse failed. Encountered \"FROM\" at line 1, column 12."));

        assertThatThrownBy(() -> tools().sqlQuery("SELECT id, FROM orders", null, null, null))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("line 1, column 12")
                .satisfies(e -> assertThat(((McpToolException) e).jsonRpcCode()).isEqualTo(-32046));
    }

    @Test
    void the_engine_that_answered_is_reported_along_with_the_predicates_it_dropped() {
        // KAFKA_DIRECT applies only the predicates it understands. A result that looks filtered but
        // is not is worse than a refusal, so the caveat travels with the rows.
        given(flink.executeSync(any())).willReturn(new QueryResult(
                List.of("id"), List.of(Map.of("id", "ORD-1")), 12L, null, true, "KAFKA_DIRECT",
                List.of("predicate on upper(state) was not applied")));

        var result = tools().sqlQuery("SELECT id FROM orders WHERE upper(state)='X'", null, null, null);

        assertThat(result.data().engine()).isEqualTo("KAFKA_DIRECT");
        assertThat(result.warnings()).anySatisfy(w ->
                assertThat(w.message()).contains("was not applied"));
    }

    @Test
    void a_result_that_filled_the_row_cap_says_more_rows_exist() {
        properties.setHardMaxRows(2);
        given(flink.executeSync(any())).willReturn(new QueryResult(
                List.of("id"), List.of(Map.of("id", "a"), Map.of("id", "b")), 8L, null, true, "FLINK"));

        var result = tools().sqlQuery("SELECT id FROM orders", null, null, null);

        assertThat(result.truncated()).isTrue();
        assertThat(result.coverage().stopReason()).isEqualTo(StopReason.RECORD_LIMIT);
    }

    @Test
    void a_client_supplied_row_cap_and_budget_are_clamped_to_the_server_ceilings() {
        given(flink.executeSync(any())).willReturn(new QueryResult(List.of(), List.of(), 1L, null));

        tools().sqlQuery("SELECT 1", null, 999_999, 999_999);

        ArgumentCaptor<QueryRequest> request = ArgumentCaptor.forClass(QueryRequest.class);
        verify(flink).executeSync(request.capture());
        assertThat(request.getValue().maxRows()).isEqualTo(properties.getHardMaxRows());
        assertThat(request.getValue().timeout()).isEqualTo(properties.getHardMaxBudgetMs());
    }

    @Test
    void a_blank_statement_is_a_validation_failure_not_a_call_to_the_engine() {
        assertThatThrownBy(() -> tools().sqlQuery("  ", null, null, null))
                .isInstanceOf(McpToolException.class)
                .satisfies(e -> assertThat(((McpToolException) e).jsonRpcCode()).isEqualTo(-32046));
    }

    @Test
    void a_listed_tables_ddl_is_redacted() {
        given(flink.listTables()).willReturn(List.of("orders"));
        given(flink.getTableSchema("orders")).willReturn(Map.of("id", "STRING"));
        given(tableStore.isEnabled()).willReturn(true);
        given(tableStore.all()).willReturn(List.of(new FlinkTableStore.StoredTable("orders",
                "CREATE TABLE orders (id STRING) WITH ('properties.sasl.jaas.config' = 'pw=s3cr3t')", 0L)));

        var result = tools().listTables();

        assertThat(result.data()).singleElement().satisfies(table -> {
            assertThat(table.ddl()).doesNotContain("s3cr3t");
            assertThat(table.columns()).containsEntry("id", "STRING");
        });
    }

    @Test
    void a_table_whose_columns_cannot_be_read_is_still_listed_with_a_warning() {
        // Dropping the row would say the table does not exist; an empty column map with no caveat
        // would say it has no columns. Neither is what happened.
        given(flink.listTables()).willReturn(List.of("orders"));
        given(flink.getTableSchema("orders")).willThrow(new RuntimeException("runtime busy"));

        var result = tools().listTables();

        assertThat(result.data()).singleElement()
                .satisfies(table -> assertThat(table.columns()).isEmpty());
        assertThat(result.warnings()).anySatisfy(w -> assertThat(w.code()).isEqualTo("SCHEMA_UNREAD"));
    }

    // ---------------------------------------------------------------------------------------
    // Scope. This tool took a statement and had no scope check of any kind, so a deployment that
    // refused `internal.mcp.audit` through kex_preview_messages served it through one SELECT.
    // ---------------------------------------------------------------------------------------

    private void restrictTo(String... prefixes) throws Exception {
        properties.setAllowedTopicPrefixes(List.of(prefixes));
        given(kafka.listTopics()).willReturn(List.of("demo.orders", "internal.mcp.audit"));
    }

    @Test
    void a_source_outside_the_scope_is_refused_before_the_engine_is_asked() throws Exception {
        restrictTo("demo.");

        assertThatThrownBy(() -> tools().sqlQuery("SELECT * FROM internal.mcp.audit", null, null, null))
                .isInstanceOf(McpToolException.class)
                .satisfies(e -> assertThat(((McpToolException) e).jsonRpcCode()).isEqualTo(-32041))
                .hasMessageContaining("internal.mcp.audit");

        // The refusal has to precede the read, or it has disclosed what it refused.
        verify(flink, org.mockito.Mockito.never()).executeSync(any());
    }

    @Test
    void the_underscored_form_of_an_out_of_scope_topic_is_the_same_topic() throws Exception {
        // A prefix is written in topic terms and a statement names Flink tables, where a dot has
        // become an underscore. Comparing the written name against the prefix would let the
        // identifier form of the very same topic through.
        restrictTo("demo.");

        assertThatThrownBy(() -> tools().sqlQuery("SELECT * FROM internal_mcp_audit", null, null, null))
                .isInstanceOf(McpToolException.class)
                .satisfies(e -> assertThat(((McpToolException) e).jsonRpcCode()).isEqualTo(-32041));
    }

    @Test
    void a_join_is_checked_on_every_side_not_only_the_first() throws Exception {
        restrictTo("demo.");

        assertThatThrownBy(() -> tools().sqlQuery(
                "SELECT o.id FROM demo.orders o JOIN internal.mcp.audit a ON a.id = o.id",
                null, null, null))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("internal.mcp.audit");
    }

    @Test
    void a_source_that_matches_no_topic_cannot_be_shown_to_be_in_scope_so_it_is_refused() throws Exception {
        // A table written by hand carries its own `topic` option, which is the one way left to read
        // outside the prefixes once every reference is resolved against the cluster.
        restrictTo("demo.");

        assertThatThrownBy(() -> tools().sqlQuery("SELECT * FROM hand_written", null, null, null))
                .isInstanceOf(McpToolException.class)
                .satisfies(e -> assertThat(((McpToolException) e).jsonRpcCode()).isEqualTo(-32041))
                .hasMessageContaining("hand_written");
    }

    @Test
    void a_create_table_naming_an_out_of_scope_topic_is_refused_without_listing_anything() throws Exception {
        properties.setAllowedTopicPrefixes(List.of("demo."));

        assertThatThrownBy(() -> tools().sqlQuery(
                "CREATE TABLE leak (id STRING) WITH ('connector' = 'kafka', "
                        + "'topic' = 'internal.mcp.audit')", null, null, null))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("internal.mcp.audit");

        // The statement said which topic it wanted, so nothing had to be resolved to refuse it.
        verify(kafka, org.mockito.Mockito.never()).listTopics();
    }

    @Test
    void a_describe_is_a_read_of_the_topic_and_is_scoped_like_one() throws Exception {
        // DESCRIBE registers the table, which samples the topic's records to infer its schema.
        restrictTo("demo.");

        assertThatThrownBy(() -> tools().sqlQuery("DESCRIBE internal.mcp.audit", null, null, null))
                .isInstanceOf(McpToolException.class)
                .satisfies(e -> assertThat(((McpToolException) e).jsonRpcCode()).isEqualTo(-32041));
    }

    @Test
    void a_cte_body_is_a_source_like_any_other() throws Exception {
        // `withoutLeadingCte` hides the CTE from the parser, so without reading the bodies back the
        // only visible source is the CTE's own name — and the topic it reads is never checked.
        restrictTo("demo.");

        assertThatThrownBy(() -> tools().sqlQuery(
                "WITH x AS (SELECT * FROM internal.mcp.audit) SELECT * FROM x", null, null, null))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("internal.mcp.audit");
    }

    @Test
    void a_cte_reading_an_in_scope_topic_is_not_refused_for_being_a_cte() throws Exception {
        // The converse, and the reason the bodies are parsed rather than the whole statement being
        // refused: a guard that refuses every CTE is a guard operators widen their prefixes around.
        restrictTo("demo.");
        given(flink.executeSync(any())).willReturn(new QueryResult(
                List.of("id"), List.of(Map.of("id", "1")), 3L, null, true, "FLINK"));

        var result = tools().sqlQuery(
                "WITH x AS (SELECT id FROM demo.orders) SELECT id FROM x", null, null, null);

        assertThat(result.data().rows()).hasSize(1);
    }

    @Test
    void a_set_operation_is_checked_rather_than_skipped_for_not_starting_with_select() throws Exception {
        // The catalogue is shared with the UI, which registers a table for any topic it is pointed
        // at — so a shape this guard does not look at reads whatever the UI has already registered.
        restrictTo("demo.");

        assertThatThrownBy(() -> tools().sqlQuery(
                "(SELECT id FROM demo.orders) UNION (SELECT id FROM internal.mcp.audit)",
                null, null, null))
                .isInstanceOf(McpToolException.class)
                .satisfies(e -> assertThat(((McpToolException) e).jsonRpcCode()).isEqualTo(-32041));
    }

    @Test
    void a_show_reads_no_record_so_it_is_not_resolved() throws Exception {
        properties.setAllowedTopicPrefixes(List.of("demo."));
        given(flink.executeSync(any())).willReturn(new QueryResult(
                List.of("table name"), List.of(Map.of("table name", "demo_orders")), 1L, null, true, "FLINK"));

        var result = tools().sqlQuery("SHOW TABLES", null, null, null);

        assertThat(result.data().rows()).hasSize(1);
        verify(kafka, org.mockito.Mockito.never()).listTopics();
    }

    @Test
    void describe_catalog_names_no_table_so_it_is_left_alone() throws Exception {
        // DESCRIBE <table> is a read and is scoped above; DESCRIBE CATALOG names nothing that
        // resolves to a topic, and refusing it would be the guard answering a question nobody asked.
        properties.setAllowedTopicPrefixes(List.of("demo."));
        given(flink.executeSync(any())).willReturn(new QueryResult(
                List.of("info"), List.of(Map.of("info", "default_catalog")), 1L, null, true, "FLINK"));

        var result = tools().sqlQuery("DESCRIBE CATALOG default_catalog", null, null, null);

        assertThat(result.data().rows()).hasSize(1);
        verify(kafka, org.mockito.Mockito.never()).listTopics();
    }

    @Test
    void a_source_inside_the_scope_runs_untouched() throws Exception {
        restrictTo("demo.");
        given(flink.executeSync(any())).willReturn(new QueryResult(
                List.of("id"), List.of(Map.of("id", "1")), 3L, null, true, "FLINK"));

        var result = tools().sqlQuery("SELECT id FROM demo.orders", null, null, null);

        assertThat(result.data().rows()).hasSize(1);
    }

    @Test
    void an_unrestricted_deployment_pays_neither_the_parse_nor_the_topic_listing() throws Exception {
        // The shipped default is "*", and a guard that costs a broker round trip on every query
        // when it has nothing to enforce would be paid by every deployment that never scoped.
        given(flink.executeSync(any())).willReturn(new QueryResult(
                List.of("id"), List.of(Map.of("id", "1")), 3L, null, true, "FLINK"));

        tools().sqlQuery("SELECT id FROM anything_at_all", null, null, null);

        verify(kafka, org.mockito.Mockito.never()).listTopics();
    }

    @Test
    void a_statement_whose_sources_cannot_be_read_is_refused_while_the_scope_is_restricted() throws Exception {
        // Otherwise an unparseable statement is the way around the guard.
        restrictTo("demo.");

        assertThatThrownBy(() -> tools().sqlQuery("SELECT * FROM (((", null, null, null))
                .isInstanceOf(McpToolException.class)
                .satisfies(e -> assertThat(((McpToolException) e).jsonRpcCode()).isEqualTo(-32041));
    }

    @Test
    void a_broker_that_cannot_be_listed_fails_the_scope_check_closed() throws Exception {
        properties.setAllowedTopicPrefixes(List.of("demo."));
        given(kafka.listTopics()).willThrow(new RuntimeException("no broker"));

        assertThatThrownBy(() -> tools().sqlQuery("SELECT * FROM demo.orders", null, null, null))
                .isInstanceOf(McpToolException.class)
                .satisfies(e -> assertThat(((McpToolException) e).jsonRpcCode()).isEqualTo(-32043));
    }

    @Test
    void a_registered_table_outside_the_scope_is_withheld_and_the_count_says_so() throws Exception {
        restrictTo("demo.");
        given(flink.listTables()).willReturn(List.of("demo_orders", "internal_mcp_audit"));
        given(flink.getTableSchema("demo_orders")).willReturn(Map.of("id", "STRING"));

        var result = tools().listTables();

        assertThat(result.data()).singleElement()
                .satisfies(table -> assertThat(table.name()).isEqualTo("demo_orders"));
        assertThat(result.warnings()).anySatisfy(w -> {
            assertThat(w.code()).isEqualTo("SCOPE");
            // Counted, not named: the names are what the scope withholds.
            assertThat(w.message()).doesNotContain("internal_mcp_audit").contains("1 registered");
        });
    }

    // ---------------------------------------------------------------------------------------
    // DLP. It reached this tool's warnings and not its rows, so the setting held on the readers
    // that return a handful of records and lapsed on the one that can return a whole topic.
    // ---------------------------------------------------------------------------------------

    @Test
    void a_credential_in_a_row_is_redacted_like_one_in_a_previewed_record() {
        given(flink.executeSync(any())).willReturn(new QueryResult(
                List.of("id", "note", "amount"),
                List.of(new java.util.LinkedHashMap<>(Map.of(
                        "id", "1",
                        "note", "Authorization: Bearer abcdef0123456789",
                        "amount", 42))),
                3L, null, true, "FLINK"));

        var result = tools().sqlQuery("SELECT id, note, amount FROM demo.orders", null, null, null);

        assertThat(result.data().rows()).singleElement().satisfies(row -> {
            assertThat(String.valueOf(row.get("note"))).doesNotContain("abcdef0123456789");
            // A number is left alone: it carries nothing to redact, and three regexes per cell
            // across a thousand rows is a cost paid on every query.
            assertThat(row.get("amount")).isEqualTo(42);
        });
    }

    @Test
    void block_mode_refuses_a_row_rather_than_returning_it_masked() {
        properties.getDlp().setMode(McpProperties.Dlp.Mode.BLOCK);
        given(flink.executeSync(any())).willReturn(new QueryResult(
                List.of("email"), List.of(Map.of("email", "someone@example.com")),
                3L, null, true, "FLINK"));

        assertThatThrownBy(() -> tools().sqlQuery("SELECT email FROM demo.orders", null, null, null))
                .isInstanceOf(McpToolException.class)
                .satisfies(e -> assertThat(((McpToolException) e).jsonRpcCode()).isEqualTo(-32045));
    }
}
