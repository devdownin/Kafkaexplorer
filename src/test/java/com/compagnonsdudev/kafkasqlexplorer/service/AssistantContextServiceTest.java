// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.AssistantProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Which tables the model is shown. Deterministic and ordered: explicit scope, the statement's own
 * sources, the names in the question, then a lexical match — and what is left out is named.
 */
class AssistantContextServiceTest {

    private FlinkSqlService flink;
    private KafkaAdminService kafka;
    private AssistantProperties properties;
    private AssistantContextService service;

    @BeforeEach
    void setUp() throws Exception {
        flink = mock(FlinkSqlService.class);
        kafka = mock(KafkaAdminService.class);
        properties = new AssistantProperties();
        when(flink.withTopicTableNames(anyString())).thenAnswer(i -> i.getArgument(0));
        when(flink.listTables()).thenReturn(List.of("hand_written"));
        when(kafka.listTopics()).thenReturn(List.of("demo.orders", "demo.payments", "_schemas", "inventory-v1"));
        when(flink.sourceSchema(anyString())).thenReturn(Map.of("id", "STRING"));
        service = new AssistantContextService(flink, kafka, properties);
    }

    @Test
    void explicitScopeComesFirstThenTheStatementThenTheQuestion() {
        AssistantContextService.Context context = service.build(
            "join with demo.payments", "SELECT * FROM inventory_v1", List.of("hand_written"));

        assertEquals(List.of("hand_written", "inventory_v1", "demo_payments"), context.tableNames());
    }

    @Test
    void aTopicNamedInTheQuestionIsDescribedThroughItsTopic() {
        service.build("how many rows in demo.orders?", null, List.of());

        verify(flink).sourceSchema("demo.orders");
    }

    @Test
    void fallsBackToALexicalMatchWhenNothingIsNamed() {
        AssistantContextService.Context context = service.build("total amount of payments per customer", null, null);

        assertEquals(List.of("demo_payments"), context.tableNames());
    }

    @Test
    void anInternalTopicIsNeverACandidate() {
        AssistantContextService.Context context = service.build("what is in _schemas", null, null);

        assertTrue(context.tableNames().isEmpty(), context.toString());
    }

    @Test
    void whatIsLeftOutIsNamed() {
        properties.setMaxTables(1);

        AssistantContextService.Context context =
            service.build(null, null, List.of("demo.orders", "demo.payments"));

        assertEquals(List.of("demo_orders"), context.tableNames());
        assertEquals(List.of("demo_payments"), context.omitted());
    }

    @Test
    void aSourceWithNoSchemaIsOmittedRatherThanDescribedEmpty() {
        when(flink.sourceSchema("demo.orders")).thenReturn(Map.of());

        AssistantContextService.Context context = service.build(null, null, List.of("demo.orders"));

        assertTrue(context.tables().isEmpty());
        assertEquals(List.of("demo_orders"), context.omitted());
    }

    @Test
    void columnsAreCappedAndTheRestCounted() {
        Map<String, String> wide = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) wide.put("c" + i, "INT");
        when(flink.sourceSchema("demo.orders")).thenReturn(wide);
        properties.setMaxColumnsPerTable(4);

        AssistantContextService.TableContext table =
            service.build(null, null, List.of("demo.orders")).tables().get(0);

        assertEquals(List.of("c0", "c1", "c2", "c3"), List.copyOf(table.columns().keySet()));
        assertEquals(6, table.columnsOmitted());
    }

    @Test
    void aStatementThatDoesNotParseStillNamesItsTable() {
        // The commonest statement to fix: the grammar refuses it, so it yields no source.
        AssistantContextService.Context context = service.build(null, "SELECT id, FROM demo_orders", List.of());

        assertEquals(List.of("demo_orders"), context.tableNames());
    }

    @Test
    void aMisspelledTableIsMatchedToTheClosestOne() {
        AssistantContextService.Context context =
            service.build(null, "SELECT * FROM demo_payment WHERE amount > 1", List.of());

        assertEquals(List.of("demo_payments"), context.tableNames());
    }

    @Test
    void anUnreachableBrokerLeavesTheRegisteredTables() throws Exception {
        when(kafka.listTopics()).thenThrow(new java.util.concurrent.TimeoutException("broker down"));

        AssistantContextService.Context context = service.build("rows of hand_written", null, null);

        assertEquals(List.of("hand_written"), context.tableNames());
    }
}
