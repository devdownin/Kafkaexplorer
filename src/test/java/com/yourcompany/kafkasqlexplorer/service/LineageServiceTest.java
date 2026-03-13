package com.yourcompany.kafkasqlexplorer.service;

import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LineageServiceTest {

    private StreamTableEnvironment tableEnv;
    private FlinkSqlService flinkSqlService;
    private LineageService lineageService;

    @BeforeEach
    void setUp() {
        tableEnv = mock(StreamTableEnvironment.class);
        flinkSqlService = mock(FlinkSqlService.class);
        lineageService = new LineageService(tableEnv, flinkSqlService);
    }

    @Test
    void testGetLineageWithTablesAndTopics() {
        // Setup tables
        when(tableEnv.listTables()).thenReturn(new String[]{"table1"});
        when(tableEnv.listViews()).thenReturn(new String[]{});

        // Setup DDL for table1 to include a topic
        TableResult tableDdlResult = mock(TableResult.class);
        CloseableIterator<Row> tableDdlIt = mock(CloseableIterator.class);
        when(tableDdlResult.collect()).thenReturn(tableDdlIt);
        when(tableDdlIt.hasNext()).thenReturn(true, false);
        when(tableDdlIt.next()).thenReturn(Row.of("CREATE TABLE table1 (...) WITH ('topic' = 'topic1')"));
        when(tableEnv.executeSql("SHOW CREATE TABLE table1")).thenReturn(tableDdlResult);

        when(flinkSqlService.getActiveJobsDetails()).thenReturn(Collections.emptyMap());

        Map<String, Object> lineage = lineageService.getLineage();

        List<Map<String, String>> nodes = (List<Map<String, String>>) lineage.get("nodes");
        List<Map<String, String>> edges = (List<Map<String, String>>) lineage.get("edges");

        assertTrue(nodes.stream().anyMatch(n -> "table1".equals(n.get("id"))));
        assertTrue(nodes.stream().anyMatch(n -> "topic_topic1".equals(n.get("id"))));
        assertTrue(edges.stream().anyMatch(e -> "topic_topic1".equals(e.get("from")) && "table1".equals(e.get("to"))));
    }

    @Test
    void testGetLineageWithViews() {
        when(tableEnv.listTables()).thenReturn(new String[]{"table1"});
        when(tableEnv.listViews()).thenReturn(new String[]{"view1"});

        // Table DDL
        TableResult tableDdlResult = mock(TableResult.class);
        CloseableIterator<Row> tableDdlIt = mock(CloseableIterator.class);
        when(tableDdlResult.collect()).thenReturn(tableDdlIt);
        when(tableDdlIt.hasNext()).thenReturn(true, false);
        when(tableDdlIt.next()).thenReturn(Row.of("CREATE TABLE table1 (...) WITH ('topic' = 'topic1')"));
        when(tableEnv.executeSql("SHOW CREATE TABLE table1")).thenReturn(tableDdlResult);

        // View DDL
        TableResult viewDdlResult = mock(TableResult.class);
        CloseableIterator<Row> viewDdlIt = mock(CloseableIterator.class);
        when(viewDdlResult.collect()).thenReturn(viewDdlIt);
        when(viewDdlIt.hasNext()).thenReturn(true, false);
        when(viewDdlIt.next()).thenReturn(Row.of("CREATE VIEW view1 AS SELECT * FROM table1"));
        when(tableEnv.executeSql("SHOW CREATE VIEW view1")).thenReturn(viewDdlResult);

        when(flinkSqlService.getActiveJobsDetails()).thenReturn(Collections.emptyMap());

        Map<String, Object> lineage = lineageService.getLineage();

        List<Map<String, String>> nodes = (List<Map<String, String>>) lineage.get("nodes");
        List<Map<String, String>> edges = (List<Map<String, String>>) lineage.get("edges");

        assertTrue(nodes.stream().anyMatch(n -> "view1".equals(n.get("id"))));
        assertTrue(edges.stream().anyMatch(e -> "table1".equals(e.get("from")) && "view1".equals(e.get("to")) && "depends".equals(e.get("label"))));
    }
}
