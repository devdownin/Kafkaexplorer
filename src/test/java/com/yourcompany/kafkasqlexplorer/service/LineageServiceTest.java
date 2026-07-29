package com.yourcompany.kafkasqlexplorer.service;

import org.apache.flink.api.common.JobID;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LineageServiceTest {

    private StreamTableEnvironment tableEnv;
    private FlinkSqlService flinkSqlService;
    private KafkaAdminService kafkaAdminService;
    private LineageService lineageService;

    @BeforeEach
    void setUp() throws Exception {
        tableEnv = mock(StreamTableEnvironment.class);
        flinkSqlService = mock(FlinkSqlService.class);
        kafkaAdminService = mock(KafkaAdminService.class);
        when(kafkaAdminService.listTopics()).thenReturn(Collections.emptyList());
        lineageService = new LineageService(tableEnv, new FlinkRuntimeCoordinator(tableEnv), flinkSqlService, kafkaAdminService);
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

    // ── Helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void mockDdl(String statement, String ddl) {
        TableResult result = mock(TableResult.class);
        CloseableIterator<Row> it = mock(CloseableIterator.class);
        when(result.collect()).thenReturn(it);
        when(it.hasNext()).thenReturn(true, false);
        when(it.next()).thenReturn(Row.of(ddl));
        when(tableEnv.executeSql(statement)).thenReturn(result);
    }

    private void noActiveJobs() {
        when(flinkSqlService.getActiveJobsDetails()).thenReturn(Collections.emptyMap());
    }

    private void activeInsertJob(String queryId, String sql) {
        JobClient client = mock(JobClient.class);
        when(client.getJobID()).thenReturn(new JobID());
        FlinkSqlService.JobInfo info =
            new FlinkSqlService.JobInfo(queryId, sql, "INSERT", "streaming", client, 0L);
        when(flinkSqlService.getActiveJobsDetails()).thenReturn(Map.of(queryId, info));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> nodesOf(Map<String, Object> lineage) {
        return (List<Map<String, Object>>) lineage.get("nodes");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> edgesOf(Map<String, Object> lineage) {
        return (List<Map<String, String>>) lineage.get("edges");
    }

    @SuppressWarnings("unchecked")
    private List<String> warningsOf(Map<String, Object> lineage) {
        return (List<String>) lineage.getOrDefault("warnings", List.of());
    }

    /** Comme {@code mockDdl}, mais sur un environnement donné plutôt que sur le champ partagé. */
    @SuppressWarnings("unchecked")
    private void mockDdlOn(org.apache.flink.table.api.TableEnvironment env, String statement, String ddl) {
        TableResult result = mock(TableResult.class);
        CloseableIterator<Row> it = mock(CloseableIterator.class);
        when(result.collect()).thenReturn(it);
        when(it.hasNext()).thenReturn(true, false);
        when(it.next()).thenReturn(Row.of(ddl));
        when(env.executeSql(statement)).thenReturn(result);
    }

    // ── Regression tests ─────────────────────────────────────────────────────

    /**
     * Flink's listTables() returns tables AND views. A view showing up in that list must still
     * be typed "view", and must not trigger a SHOW CREATE TABLE that can only fail.
     */
    @Test
    void viewsReturnedByListTablesAreStillTypedAsViews() {
        when(tableEnv.listTables()).thenReturn(new String[]{"table1", "view1"});
        when(tableEnv.listViews()).thenReturn(new String[]{"view1"});
        mockDdl("SHOW CREATE TABLE table1", "CREATE TABLE table1 (...) WITH ('topic' = 'topic1')");
        mockDdl("SHOW CREATE VIEW view1", "CREATE VIEW view1 AS SELECT * FROM table1");
        noActiveJobs();

        List<Map<String, Object>> nodes = nodesOf(lineageService.getLineage());

        Map<String, Object> view = nodes.stream()
            .filter(n -> "view1".equals(n.get("id"))).findFirst().orElseThrow();
        assertEquals("view", view.get("type"));
        assertEquals(1, nodes.stream().filter(n -> "view1".equals(n.get("id"))).count());
        verify(tableEnv, never()).executeSql("SHOW CREATE TABLE view1");
    }

    /** Two tables backed by the same topic must share a single topic node. */
    @Test
    void topicBackingTwoTablesProducesOneNode() {
        when(tableEnv.listTables()).thenReturn(new String[]{"table1", "table2"});
        when(tableEnv.listViews()).thenReturn(new String[]{});
        mockDdl("SHOW CREATE TABLE table1", "CREATE TABLE table1 (...) WITH ('topic' = 'shared')");
        mockDdl("SHOW CREATE TABLE table2", "CREATE TABLE table2 (...) WITH ('topic' = 'shared')");
        noActiveJobs();

        Map<String, Object> lineage = lineageService.getLineage();

        assertEquals(1, nodesOf(lineage).stream().filter(n -> "topic_shared".equals(n.get("id"))).count());
        assertEquals(2, edgesOf(lineage).stream().filter(e -> "topic_shared".equals(e.get("from"))).count());
    }

    /** A commented-out FROM must not create a dependency, even on a real table. */
    @Test
    void commentedOutFromIsNotADependency() {
        when(tableEnv.listTables()).thenReturn(new String[]{"table1", "secret_table"});
        when(tableEnv.listViews()).thenReturn(new String[]{"view1"});
        mockDdl("SHOW CREATE TABLE table1", "CREATE TABLE table1 (...)");
        mockDdl("SHOW CREATE TABLE secret_table", "CREATE TABLE secret_table (...)");
        mockDdl("SHOW CREATE VIEW view1",
            "CREATE VIEW view1 AS SELECT * FROM table1 -- FROM secret_table\n");
        noActiveJobs();

        List<Map<String, String>> edges = edgesOf(lineageService.getLineage());

        assertTrue(edges.stream().anyMatch(e -> "table1".equals(e.get("from")) && "view1".equals(e.get("to"))));
        assertFalse(edges.stream().anyMatch(e -> "secret_table".equals(e.get("from"))));
    }

    /** A table name inside a string literal is data, not a dependency. */
    @Test
    void tableNameInsideStringLiteralIsNotADependency() {
        when(tableEnv.listTables()).thenReturn(new String[]{"table1", "secret_table"});
        when(tableEnv.listViews()).thenReturn(new String[]{"view1"});
        mockDdl("SHOW CREATE TABLE table1", "CREATE TABLE table1 (...)");
        mockDdl("SHOW CREATE TABLE secret_table", "CREATE TABLE secret_table (...)");
        mockDdl("SHOW CREATE VIEW view1",
            "CREATE VIEW view1 AS SELECT * FROM table1 WHERE note = 'FROM secret_table'");
        noActiveJobs();

        List<Map<String, String>> edges = edgesOf(lineageService.getLineage());

        assertFalse(edges.stream().anyMatch(e -> "secret_table".equals(e.get("from"))));
    }

    /** Sources that are not real nodes (CTE names, aliases) must not leave dangling edges. */
    @Test
    void edgesPointingAtUnknownObjectsAreDropped() {
        when(tableEnv.listTables()).thenReturn(new String[]{});
        when(tableEnv.listViews()).thenReturn(new String[]{});
        activeInsertJob("job-1", "INSERT INTO ghost_target SELECT * FROM ghost_source");

        Map<String, Object> lineage = lineageService.getLineage();

        assertTrue(nodesOf(lineage).stream().anyMatch(n -> "query".equals(n.get("type"))));
        assertTrue(edgesOf(lineage).isEmpty(), "no edge may reference a node that does not exist");
    }

    /**
     * Stubs one wired topic (backing table1) and one orphan topic.
     * Kept to a single getLineage() call per test: the DDL iterator mock is one-shot.
     */
    private void stubWiredAndOrphanTopics() throws Exception {
        when(tableEnv.listTables()).thenReturn(new String[]{"table1"});
        when(tableEnv.listViews()).thenReturn(new String[]{});
        mockDdl("SHOW CREATE TABLE table1", "CREATE TABLE table1 (...) WITH ('topic' = 'wired')");
        when(kafkaAdminService.listTopics()).thenReturn(List.of("wired", "orphan"));
        when(kafkaAdminService.getTopicsSize(anyList())).thenReturn(Map.of("wired", 5L, "orphan", 7L));
        noActiveJobs();
    }

    @Test
    void unfilteredGraphKeepsOrphanTopics() throws Exception {
        stubWiredAndOrphanTopics();

        List<Map<String, Object>> nodes = nodesOf(lineageService.getLineage(false));

        assertTrue(nodes.stream().anyMatch(n -> "topic_orphan".equals(n.get("id"))));
        assertTrue(nodes.stream().anyMatch(n -> "topic_wired".equals(n.get("id"))));
        assertEquals(7L, nodes.stream()
            .filter(n -> "topic_orphan".equals(n.get("id"))).findFirst().orElseThrow().get("messageCount"));
    }

    @Test
    void connectedOnlyDropsUnwiredNodes() throws Exception {
        stubWiredAndOrphanTopics();

        List<Map<String, Object>> nodes = nodesOf(lineageService.getLineage(true));

        assertFalse(nodes.stream().anyMatch(n -> "topic_orphan".equals(n.get("id"))));
        assertTrue(nodes.stream().anyMatch(n -> "topic_wired".equals(n.get("id"))));
        assertTrue(nodes.stream().anyMatch(n -> "table1".equals(n.get("id"))));
    }

    /** The DDL endpoint takes its name from the URL — unknown names must never be executed. */
    @Test
    void getDdlForNodeRejectsUnknownOrMalformedNames() {
        when(tableEnv.listTables()).thenReturn(new String[]{"table1"});
        when(tableEnv.listViews()).thenReturn(new String[]{});

        assertTrue(lineageService.getDdlForNode("topic_orders").startsWith("-- DDL not available"));
        assertTrue(lineageService.getDdlForNode("table1; DROP").startsWith("-- DDL not available"));
        verify(tableEnv, never()).executeSql("SHOW CREATE TABLE topic_orders");
        verify(tableEnv, never()).executeSql("SHOW CREATE TABLE table1; DROP");
    }

    /** A known table still resolves, and its credentials are masked. */
    @Test
    void getDdlForNodeReturnsMaskedDdlForKnownTable() {
        when(tableEnv.listTables()).thenReturn(new String[]{"table1"});
        when(tableEnv.listViews()).thenReturn(new String[]{});
        mockDdl("SHOW CREATE TABLE table1",
            "CREATE TABLE table1 (...) WITH ('properties.ssl.truststore.password' = 'hunter2')");

        String ddl = lineageService.getDdlForNode("table1");

        assertTrue(ddl.contains("CREATE TABLE table1"));
        assertFalse(ddl.contains("hunter2"), "credentials must be masked before reaching the UI");
    }

    // ── Dependency resolution through Flink's parser ─────────────────────────

    /** Un environnement complet : le parseur est disponible et fait autorité. */
    private LineageService withParser(org.apache.flink.table.delegation.Parser parser, String... tables) {
        org.apache.flink.table.api.internal.TableEnvironmentImpl impl =
            mock(org.apache.flink.table.api.internal.TableEnvironmentImpl.class);
        when(impl.getParser()).thenReturn(parser);
        when(impl.listTables()).thenReturn(tables);
        when(impl.listViews()).thenReturn(new String[]{});
        for (String table : tables) {
            mockDdlOn(impl, "SHOW CREATE TABLE " + table, "CREATE TABLE " + table + " (...)");
        }
        return new LineageService(impl, new FlinkRuntimeCoordinator(impl), flinkSqlService, kafkaAdminService);
    }

    private static org.apache.flink.table.operations.SourceQueryOperation sourceOp(String name) {
        org.apache.flink.table.operations.SourceQueryOperation op =
            mock(org.apache.flink.table.operations.SourceQueryOperation.class);
        org.apache.flink.table.catalog.ContextResolvedTable resolved =
            mock(org.apache.flink.table.catalog.ContextResolvedTable.class);
        when(resolved.getIdentifier())
            .thenReturn(org.apache.flink.table.catalog.ObjectIdentifier.of("cat", "db", name));
        when(op.getContextResolvedTable()).thenReturn(resolved);
        when(op.getChildren()).thenReturn(List.of());
        return op;
    }

    /**
     * The regex scan reads {@code FROM TABLE(TUMBLE(TABLE orders, …))} as the keyword TABLE and
     * loses the topic entirely — a windowed INSERT lost its source edge. The planner does not.
     */
    @Test
    void windowedInsertKeepsItsSourceThroughTheParser() {
        String sql = "INSERT INTO enriched SELECT * FROM TABLE(TUMBLE(TABLE orders, "
            + "DESCRIPTOR(event_time), INTERVAL '5' MINUTE))";
        org.apache.flink.table.operations.SinkModifyOperation sink =
            mock(org.apache.flink.table.operations.SinkModifyOperation.class);
        org.apache.flink.table.catalog.ContextResolvedTable target =
            mock(org.apache.flink.table.catalog.ContextResolvedTable.class);
        when(target.getIdentifier())
            .thenReturn(org.apache.flink.table.catalog.ObjectIdentifier.of("cat", "db", "enriched"));
        when(sink.getContextResolvedTable()).thenReturn(target);
        org.apache.flink.table.operations.SourceQueryOperation ordersSource = sourceOp("orders");
        when(sink.getChild()).thenReturn(ordersSource);

        org.apache.flink.table.delegation.Parser parser = mock(org.apache.flink.table.delegation.Parser.class);
        when(parser.parse(sql)).thenReturn(List.of(sink));
        LineageService service = withParser(parser, "orders", "enriched");
        activeInsertJob("q1", sql);

        Map<String, Object> lineage = service.getLineage();

        assertTrue(edgesOf(lineage).stream()
                .anyMatch(e -> "orders".equals(e.get("from")) && "query_q1".equals(e.get("to"))),
            "the planner resolves the windowed source, got " + edgesOf(lineage));
        assertTrue(edgesOf(lineage).stream()
            .anyMatch(e -> "query_q1".equals(e.get("from")) && "enriched".equals(e.get("to"))));
        assertTrue(warningsOf(lineage).isEmpty(), "nothing was guessed, so nothing to warn about");
    }

    /** A view's sources come from the query the CREATE VIEW wraps. */
    @Test
    void viewSourcesAreResolvedThroughTheWrappedQuery() {
        String ddl = "CREATE VIEW view1 AS SELECT * FROM table1";
        String inner = "SELECT * FROM table1";
        org.apache.flink.table.catalog.CatalogView catalogView =
            mock(org.apache.flink.table.catalog.CatalogView.class);
        when(catalogView.getOriginalQuery()).thenReturn(inner);
        org.apache.flink.table.operations.ddl.CreateViewOperation createView =
            mock(org.apache.flink.table.operations.ddl.CreateViewOperation.class);
        when(createView.getCatalogView()).thenReturn(catalogView);

        org.apache.flink.table.delegation.Parser parser = mock(org.apache.flink.table.delegation.Parser.class);
        org.apache.flink.table.operations.SourceQueryOperation table1Source = sourceOp("table1");
        when(parser.parse(ddl)).thenReturn(List.of(createView));
        when(parser.parse(inner)).thenReturn(List.of(table1Source));

        org.apache.flink.table.api.internal.TableEnvironmentImpl impl =
            mock(org.apache.flink.table.api.internal.TableEnvironmentImpl.class);
        when(impl.getParser()).thenReturn(parser);
        when(impl.listTables()).thenReturn(new String[]{"table1", "view1"});
        when(impl.listViews()).thenReturn(new String[]{"view1"});
        mockDdlOn(impl, "SHOW CREATE TABLE table1", "CREATE TABLE table1 (...) WITH ('topic' = 'topic1')");
        mockDdlOn(impl, "SHOW CREATE VIEW view1", ddl);
        noActiveJobs();

        Map<String, Object> lineage = new LineageService(
            impl, new FlinkRuntimeCoordinator(impl), flinkSqlService, kafkaAdminService).getLineage();

        assertTrue(edgesOf(lineage).stream()
                .anyMatch(e -> "table1".equals(e.get("from")) && "view1".equals(e.get("to"))),
            "expected the view to depend on its source, got " + edgesOf(lineage));
    }

    /** A statement the parser refuses still yields a graph — and the graph says it was guessed. */
    @Test
    void aStatementTheParserRefusesFallsBackAndIsReported() {
        String sql = "INSERT INTO enriched SELECT * FROM orders";
        org.apache.flink.table.delegation.Parser parser = mock(org.apache.flink.table.delegation.Parser.class);
        when(parser.parse(sql)).thenThrow(new IllegalStateException("Object 'orders' not found"));
        LineageService service = withParser(parser, "orders", "enriched");
        activeInsertJob("q1", sql);

        Map<String, Object> lineage = service.getLineage();

        assertTrue(edgesOf(lineage).stream()
                .anyMatch(e -> "query_q1".equals(e.get("from")) && "enriched".equals(e.get("to"))),
            "the regex fallback still wires what it can, got " + edgesOf(lineage));
        assertTrue(warningsOf(lineage).stream().anyMatch(w -> w.contains("q1")),
            "a guessed statement must be named, got " + warningsOf(lineage));
    }

    /** No parser at all (a plain TableEnvironment): same fallback, same honesty. */
    @Test
    void withoutAParserTheGraphSaysItWasGuessed() {
        when(tableEnv.listTables()).thenReturn(new String[]{});
        when(tableEnv.listViews()).thenReturn(new String[]{});
        activeInsertJob("q9", "INSERT INTO enriched SELECT * FROM orders");

        Map<String, Object> lineage = lineageService.getLineage();

        assertTrue(warningsOf(lineage).stream().anyMatch(w -> w.contains("q9")),
            "got " + warningsOf(lineage));
    }
}
