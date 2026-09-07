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

    private final McpProperties properties = new McpProperties();

    private SqlMcpTools tools() {
        return new SqlMcpTools(flink, tableStore, new ToolGuard(properties, new DlpScrubber(properties)));
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
}
