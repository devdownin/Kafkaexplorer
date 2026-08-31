// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.MessageFormat;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryResult;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.types.Row;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link FlinkSqlService} covering both execution engines:
 *
 * <h3>FLINK (restored planner)</h3>
 * {@code SELECT} runs through the real Flink planner ({@code engine=FLINK}); {@code EXPLAIN}
 * and {@code CREATE TABLE} go through the embedded {@link StreamTableEnvironment} too.
 * In-memory Flink views registered with {@code createTemporaryView()} are resolved by the
 * planner. The historical {@code FlinkRelMetadataQuery} NPE that once forced every SELECT
 * through the direct reader is fixed (THREAD_PROVIDERS pre-seed, see FlinkRuntimeCoordinator).
 *
 * <h3>KAFKA_DIRECT (fallback)</h3>
 * {@code kafkaDirectSelect()} reads directly from Kafka via {@link KafkaAdminService} and is
 * used only when the planner path is disabled or fails. Tests that exercise it mock
 * {@code listTopics()} and {@code getEarliestRecords()} / {@code getRecentRecords()}.
 *
 * <p>The tests that were once annotated {@code @Disabled("KAFKA_DIRECT")} — written against
 * the old bypass, when SELECT could not resolve in-memory views — are enabled again now that
 * the Flink SELECT path is restored.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlinkSqlServiceTest {

    private StreamTableEnvironment tableEnv;
    private FlinkSqlService service;
    private KafkaAdminService kafkaAdminService;
    private SchemaInferenceService schemaInferenceService;
    private DdlGeneratorService ddlGeneratorService;

    @BeforeAll
    void setup() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment();
        tableEnv = StreamTableEnvironment.create(env,
                EnvironmentSettings.newInstance().inStreamingMode().build());

        ExplorerConfig config = new ExplorerConfig();
        config.setDefaultMaxRows(50);
        config.setDefaultQueryTimeoutMs(10_000);

        kafkaAdminService = mock(KafkaAdminService.class);
        schemaInferenceService = mock(SchemaInferenceService.class);
        ddlGeneratorService = mock(DdlGeneratorService.class);

        FlinkRuntimeCoordinator runtimeCoordinator = new FlinkRuntimeCoordinator(tableEnv);
        SqlQueryValidator validator = new SqlQueryValidator(config, tableEnv, runtimeCoordinator);
        // A temporary path, so a CREATE TABLE run by a test does not write into the checkout's
        // data/ directory — and so two runs of the suite cannot see each other's tables.
        config.setFlinkTableStorePath(
            Files.createTempFile("flink-tables-", ".json").toString());
        service = new FlinkSqlService(tableEnv, runtimeCoordinator, config, validator,
                kafkaAdminService, schemaInferenceService, ddlGeneratorService,
                new FlinkTableStore(config));

        // ── In-memory test data (registered once, reused by all tests) ───────
        tableEnv.createTemporaryView("orders",
                tableEnv.fromValues(
                        DataTypes.ROW(
                                DataTypes.FIELD("order_id", DataTypes.STRING()),
                                DataTypes.FIELD("amount", DataTypes.DOUBLE()),
                                DataTypes.FIELD("state", DataTypes.STRING()),
                                DataTypes.FIELD("customer_id", DataTypes.STRING())
                        ),
                        Row.of("ORD-001", 599.99, "RECEIVED", "C-001"),
                        Row.of("ORD-002", 0.00,   "REJECTED", "C-002"),
                        Row.of("ORD-003", 1200.00, "SHIPPED",  "C-003"),
                        Row.of("ORD-004", 450.00,  "DELIVERED","C-003")
                ));

        tableEnv.createTemporaryView("customers",
                tableEnv.fromValues(
                        DataTypes.ROW(
                                DataTypes.FIELD("customer_id", DataTypes.STRING()),
                                DataTypes.FIELD("name", DataTypes.STRING()),
                                DataTypes.FIELD("segment", DataTypes.STRING())
                        ),
                        Row.of("C-001", "Alice",   "VIP"),
                        Row.of("C-002", "Bob",     "REGULAR"),
                        Row.of("C-003", "Charlie", "REGULAR")
                ));

        tableEnv.createTemporaryView("xml_messages",
                tableEnv.fromValues(
                        DataTypes.ROW(DataTypes.FIELD("raw_value", DataTypes.STRING())),
                        Row.of("<Order><Customer>Alice</Customer><Amount>150.00</Amount></Order>"),
                        Row.of("<Order><Customer>Bob</Customer><Amount>42.00</Amount></Order>")
                ));

        // Kafka DDL table for latest-offset hint test (Flink defers connector init to SELECT time)
        tableEnv.executeSql(
                "CREATE TABLE IF NOT EXISTS kafka_for_hint_test (" +
                "  id STRING" +
                ") WITH (" +
                "  'connector'='kafka'," +
                "  'topic'='hint_test'," +
                "  'properties.bootstrap.servers'='localhost:9092'," +
                "  'value.format'='json'," +
                "  'scan.startup.mode'='earliest-offset'" +
                ")");

        // Infinite datagen source used only by the timeout test
        tableEnv.executeSql(
                "CREATE TABLE IF NOT EXISTS infinite_source (id BIGINT) " +
                "WITH ('connector'='datagen')");

        // Une table horodatée *sans* watermark : sa colonne temporelle n'est donc pas un attribut
        // temporel, et toute fenêtre posée dessus est refusée par le planner. C'est la forme de
        // toutes les tables générées avant que `DdlGeneratorService` ne déclare un watermark, et
        // c'est encore celle d'un `event_time` venu du payload.
        tableEnv.executeSql(
                "CREATE TABLE IF NOT EXISTS win_no_watermark (id STRING, event_time TIMESTAMP(3)) "
                + "WITH ('connector'='datagen','number-of-rows'='2')");

        // Et sa jumelle avec watermark, sur une source qui ne finit jamais : la fenêtre se
        // *planifie*, et c'est l'exécution qui ne rend pas la main — la forme d'une lecture
        // fenêtrée d'un topic Kafka non borné.
        tableEnv.executeSql(
                "CREATE TABLE IF NOT EXISTS win_unbounded (id STRING, event_time TIMESTAMP(3), "
                + "WATERMARK FOR event_time AS event_time - INTERVAL '5' SECOND) "
                + "WITH ('connector'='datagen','rows-per-second'='5')");
    }

    /**
     * Fully reset all mocks before each test.
     * Using reset() (not clearInvocations()) ensures that thenThrow stubs from one test
     * cannot bleed into subsequent tests.
     * Using doReturn() (not when()) to set the default stub avoids counting the stub-setup
     * call as a real invocation, which would break verify(never()) assertions.
     */
    @BeforeEach
    void resetMocks() throws Exception {
        reset(kafkaAdminService, schemaInferenceService, ddlGeneratorService);
        // Safe default: no auto-registration side-effects for tests that don't configure the mock
        doReturn(List.of()).when(kafkaAdminService).listTopics();
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Basic SQL execution
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void basicSelectReturnsAllRowsAndColumns() {
        QueryResult result = execute("SELECT order_id, amount, state FROM orders");

        assertNoError(result);
        assertEquals(List.of("order_id", "amount", "state"), result.columns());
        assertEquals(4, result.rows().size());
    }

    @Test
    void selectStarReturnsAllColumns() {
        QueryResult result = execute("SELECT * FROM orders");

        assertNoError(result);
        assertTrue(result.columns().containsAll(List.of("order_id", "amount", "state", "customer_id")));
        assertEquals(4, result.rows().size());
    }

    @Test
    void whereClauseFiltersRows() {
        QueryResult result = execute("SELECT order_id, state FROM orders WHERE state = 'RECEIVED'");

        assertNoError(result);
        assertEquals(1, result.rows().size());
        assertEquals("ORD-001", result.rows().get(0).get("order_id"));
    }

    @Test
    void whereWithNumericThresholdFiltersCorrectly() {
        QueryResult result = execute("SELECT order_id FROM orders WHERE amount > 500.0");

        assertNoError(result);
        // ORD-001 (599.99) and ORD-003 (1200.00)
        assertEquals(2, result.rows().size());
        List<Object> ids = result.rows().stream().map(r -> r.get("order_id")).toList();
        assertTrue(ids.containsAll(List.of("ORD-001", "ORD-003")));
    }

    @Test
    void columnAliasAndExpressionWork() {
        QueryResult result = execute(
                "SELECT order_id AS id, amount * 1.2 AS amount_with_tax " +
                "FROM orders WHERE order_id = 'ORD-001'");

        assertNoError(result);
        assertEquals(1, result.rows().size());
        Map<String, Object> row = result.rows().get(0);
        assertTrue(row.containsKey("id"), "Column alias 'id' must be present");
        assertTrue(row.containsKey("amount_with_tax"), "Computed alias must be present");
    }

    @Test
    void innerJoinBetweenTwoInMemoryTables() {
        QueryResult result = execute(
                "SELECT o.order_id, c.name, o.amount " +
                "FROM orders o JOIN customers c ON o.customer_id = c.customer_id");

        assertNoError(result);
        assertEquals(4, result.rows().size());
        result.rows().stream()
                .filter(r -> "ORD-001".equals(r.get("order_id")))
                .findFirst()
                .ifPresentOrElse(
                        r -> assertEquals("Alice", r.get("name")),
                        () -> fail("ORD-001 not found in join result"));
    }

    @Test
    void maxRowsLimitIsRespected() {
        QueryResult result = service.executeSql(
                new QueryRequest("SELECT * FROM orders", null, 2, 10_000L, null));

        assertNoError(result);
        assertTrue(result.rows().size() <= 2, "maxRows=2 must cap the result");
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // EXPLAIN
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void explainReturnsQueryPlanWithoutError() {
        QueryResult result = execute(
                "EXPLAIN SELECT order_id, state FROM orders WHERE state = 'SHIPPED'");

        assertNoError(result);
        assertFalse(result.rows().isEmpty(), "EXPLAIN must return at least one row (the plan text)");
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Security — blocked statement types
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void insertStatementIsRejected() {
        assertHasError(execute("INSERT INTO orders VALUES ('X', 1.0, 'ERR', 'C-X')"));
    }

    @Test
    void dropTableStatementIsRejected() {
        assertHasError(execute("DROP TABLE orders"));
    }

    @Test
    void updateStatementIsRejected() {
        assertHasError(execute("UPDATE orders SET state = 'HACKED' WHERE order_id = 'ORD-001'"));
    }

    @Test
    void deleteStatementIsRejected() {
        assertHasError(execute("DELETE FROM orders WHERE order_id = 'ORD-001'"));
    }

    @Test
    void truncateStatementIsRejected() {
        assertHasError(execute("TRUNCATE TABLE orders"));
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // CREATE TABLE (user-provided DDL)
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void createTableDdlIsAllowedAndExecuted() {
        String ddl = "CREATE TABLE user_ddl_table (" +
                "  event_id STRING, payload STRING" +
                ") WITH ('connector'='datagen','number-of-rows'='0')";

        QueryResult result = execute(ddl);

        assertNull(result.error(), "CREATE TABLE must succeed, got: " + result.error());
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Double-quoted identifier normalization
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void doubleQuotedTableIdentifierIsNormalizedToBacktick() {
        // Standard SQL uses double quotes for identifiers; Flink uses backticks.
        QueryResult result = execute("SELECT order_id FROM \"orders\" WHERE state = 'RECEIVED'");
        assertNoError(result);
        assertEquals(1, result.rows().size());
    }

    @Test
    void singleQuoteStringLiteralContainingDoubleQuoteIsPreserved() {
        // A string literal must NOT have its content altered during identifier normalization.
        QueryResult result = execute("SELECT order_id FROM orders WHERE state = 'RECEIVED'");
        assertNoError(result);
        assertEquals(1, result.rows().size());
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Latest-offset hint injection
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void latestOffsetModeInjectsValidOptionsHintIntoSql() {
        // kafka_for_hint_test was registered with the Kafka connector in @BeforeAll.
        // The latest-offset hint will be injected as SQL OPTIONS(), which is valid Flink SQL syntax.
        // The query will fail because there is no Kafka broker, but the error must be about
        // Kafka connectivity — NOT about an invalid SQL hint or OPTIONS syntax error.
        QueryResult result = service.executeSql(
                new QueryRequest("SELECT * FROM kafka_for_hint_test", null, 5, 2_000L, "latest-offset"));

        // Query must fail (no broker), but for the right reason
        assertNotNull(result.error(), "Expected a failure without a live Kafka broker");
        String error = result.error().toLowerCase();
        assertFalse(
                error.contains("invalid hint") || error.contains("hint is not supported") ||
                error.contains("no match found") || error.contains("sql parse"),
                "Error must NOT be about OPTIONS hint syntax. The hint injection must produce " +
                "valid SQL. Actual error: " + result.error());
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Auto-registration (mocked Kafka + schema inference + DDL generator)
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void autoRegistrationRegistersTableWhenTopicExists() throws Exception {
        // Use doReturn() to avoid any invocation-counting ambiguity
        doReturn(List.of("auto.reg.topic")).when(kafkaAdminService).listTopics();
        doReturn(MessageFormat.JSON).when(schemaInferenceService).detectFormat("auto.reg.topic");
        doReturn(Map.of("event_id", "STRING", "payload", "STRING"))
                .when(schemaInferenceService).inferSchema(anyString(), any());
        // Auto-registration creates a bounded datagen table (2 rows). With the Flink planner
        // restored, the SELECT runs through Flink and reads those 2 rows.
        doReturn("CREATE TABLE auto_reg_topic (" +
                "  event_id STRING, payload STRING" +
                ") WITH ('connector'='datagen','number-of-rows'='2')")
                .when(ddlGeneratorService).generateDdl(anyString(), any(), any());
        // Fallback data if the Flink planner path is unavailable and kafkaDirectSelect() takes over.
        doReturn(List.of(
                new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                        "auto.reg.topic", 0, 0L, null, "{\"event_id\":\"E1\",\"payload\":\"p1\"}"),
                new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                        "auto.reg.topic", 0, 1L, null, "{\"event_id\":\"E2\",\"payload\":\"p2\"}")
        )).when(kafkaAdminService).getRecentRecords(eq("auto.reg.topic"), anyInt());

        QueryResult result = execute("SELECT event_id, payload FROM auto_reg_topic");

        assertNoError(result);
        assertEquals(2, result.rows().size(), "Must return 2 rows from the registered table");
        assertEquals("FLINK", result.engine(), "SELECT now runs through the restored Flink planner");
        verify(schemaInferenceService).detectFormat("auto.reg.topic");
        verify(ddlGeneratorService).generateDdl(anyString(), any(), any());
    }

    @Test
    void selectResolvesTableAlreadyInFlinkCatalog() throws Exception {
        // 'orders' was registered in @BeforeAll as a Flink temporary view (not a Kafka topic).
        // autoRegisterTableIfNeeded() finds it in listTables() and skips Kafka registration; the
        // restored Flink planner then reads the in-memory view directly. (Before the planner was
        // re-enabled, kafkaDirectSelect() failed here because 'orders' is not a Kafka topic.)
        QueryResult result = execute("SELECT order_id FROM orders");

        assertNoError(result);
        assertEquals(4, result.rows().size(), "Flink resolves the in-memory 'orders' view (4 rows)");
        assertEquals("FLINK", result.engine(), "A Flink-catalog table is read through the Flink planner");
    }

    @Test
    void autoRegistrationReturnsErrorWhenKafkaIsUnreachable() throws Exception {
        doThrow(new RuntimeException("Connection refused: localhost:9092"))
                .when(kafkaAdminService).listTopics();

        QueryResult result = execute("SELECT * FROM unreachable_topic_xyz");

        assertHasError(result);
        String err = result.error();
        assertTrue(err.contains("Cannot reach Kafka broker") || err.contains("Connection refused"),
                "Error must mention Kafka connectivity, got: " + err);
    }

    @Test
    void autoRegistrationDdlFailureReturnsMeaningfulError() throws Exception {
        doReturn(List.of("broken.topic")).when(kafkaAdminService).listTopics();
        doReturn(MessageFormat.JSON).when(schemaInferenceService).detectFormat(anyString());
        // For format=JSON, an empty schema (Map.of()) would normally cause autoRegisterTableIfNeeded
        // to return AutoRegResult.skip(). To force a DDL failure, we return a mock schema.
        doReturn(Map.of("id", "BIGINT")).when(schemaInferenceService).inferSchema(anyString(), any());
        doReturn("NOT VALID DDL !!!").when(ddlGeneratorService).generateDdl(anyString(), any(), any());

        QueryResult result = execute("SELECT * FROM broken_topic");

        assertHasError(result);
        assertTrue(result.error().contains("broken_topic") || result.error().contains("broken.topic"),
                "Error must mention the topic that failed to register, got: " + result.error());
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Timeout
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void queryTimeoutReturnsEmptyResultInsteadOfHanging() {
        // infinite_source has no 'number-of-rows' limit — it generates rows forever.
        // With limit=Integer.MAX_VALUE and a very short timeout, the CompletableFuture
        // expires and the catch block returns QueryResult([], [], duration, null).
        // Note: TimeoutException.getMessage() is null in the JDK, so result.error() is null —
        // but the result is distinguishable from a successful query because both columns
        // and rows are empty (a normal SELECT always returns at least the column list).
        long start = System.currentTimeMillis();
        QueryResult result = service.executeSql(
                new QueryRequest("SELECT * FROM infinite_source", null,
                        Integer.MAX_VALUE, 200L, null));
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(result.rows().isEmpty(),    "Timed-out query must return no rows");
        assertTrue(result.columns().isEmpty(), "Timed-out query must return no columns " +
                "(distinguishes timeout from a successful but empty SELECT)");
        assertTrue(elapsed < 10_000,
                "A timed-out query must return promptly, but took " + elapsed + "ms");
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // XmlExtract UDF
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void xmlExtractUdfIsRegisteredAndParsesXml() {
        QueryResult result = execute(
                "SELECT XmlExtract(raw_value, '/Order/Customer') AS customer FROM xml_messages");

        assertNoError(result);
        assertEquals(2, result.rows().size());
        List<Object> customers = result.rows().stream().map(r -> r.get("customer")).toList();
        assertTrue(customers.contains("Alice"), "XmlExtract must find 'Alice'");
        assertTrue(customers.contains("Bob"),   "XmlExtract must find 'Bob'");
    }

    @Test
    void xmlExtractReturnsNullForMissingPath() {
        QueryResult result = execute(
                "SELECT XmlExtract(raw_value, '/Order/NonExistent') AS missing FROM xml_messages");

        assertNoError(result);
        assertEquals(2, result.rows().size());
        result.rows().forEach(r ->
                assertNull(r.get("missing"), "Missing XPath should yield null, not an error"));
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // WHERE clauses the direct engine cannot apply
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void supportedWhereClauseProducesNoWarning() {
        assertTrue(service.unsupportedWhereFragments(
            "SELECT * FROM orders WHERE status = 'NEW' AND region = 'EU' LIMIT 10").isEmpty());
    }

    @Test
    void noWhereClauseProducesNoWarning() {
        assertTrue(service.unsupportedWhereFragments("SELECT * FROM orders LIMIT 10").isEmpty());
    }

    @Test
    void comparisonOperatorIsReportedAsIgnored() {
        List<String> warnings = service.unsupportedWhereFragments(
            "SELECT * FROM orders WHERE amount > 100 LIMIT 10");

        assertEquals(1, warnings.size(), "a predicate that is silently dropped must be reported");
        assertTrue(warnings.get(0).contains("amount > 100"), warnings.get(0));
    }

    @Test
    void orIsReportedBecauseConditionsAreCombinedWithAnd() {
        List<String> warnings = service.unsupportedWhereFragments(
            "SELECT * FROM orders WHERE status = 'NEW' OR status = 'SHIPPED'");

        assertEquals(1, warnings.size(), "OR is evaluated as AND, so the result would be wrong");
        assertTrue(warnings.get(0).contains("OR"), warnings.get(0));
    }

    @Test
    void likeIsReportedAsIgnored() {
        List<String> warnings = service.unsupportedWhereFragments(
            "SELECT * FROM orders WHERE customer LIKE 'ACME%'");

        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("LIKE"), warnings.get(0));
    }

    @Test
    void groupByAfterASupportedWhereIsNotMistakenForAnIgnoredPredicate() {
        assertTrue(service.unsupportedWhereFragments(
            "SELECT status, COUNT(*) AS metric_value FROM orders WHERE region = 'EU' "
                + "GROUP BY status").isEmpty());
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // An invalid query is reported, not quietly served by the direct reader
    //
    // The direct reader only regex-matches the table name out of the FROM clause, so before
    // these fixes a typo came back as a page of rows and the query looked like it worked.
    // ──────────────────────────────────────────────────────────────────────────────

    // ──────────────────────────────────────────────────────────────────────────────
    // Two direct aggregates over one topic come out of one read
    //
    // Two counts under different WHERE clauses over the same topic — which is what a same-topic
    // TOPIC_COUNT_DELTA is — each downloaded and parsed up to AGGREGATE_SCAN_RECORDS records,
    // thirty seconds apart, and the per-cycle memoization one layer up keys on the SQL so it never
    // brought them together. Sharing also makes the two counts describe the same instant.
    // ──────────────────────────────────────────────────────────────────────────────

    /** Three records on one topic, two of which carry status OK. */
    private void stubCountableTopic(String topic) throws Exception {
        String table = topic.replace('.', '_');
        doReturn(List.of(topic)).when(kafkaAdminService).listTopics();
        doReturn(MessageFormat.JSON).when(schemaInferenceService).detectFormat(anyString());
        doReturn(Map.of("status", "STRING")).when(schemaInferenceService).inferSchema(anyString(), any());
        doReturn("CREATE TABLE " + table + " (status STRING) "
                + "WITH ('connector'='datagen','number-of-rows'='0')")
                .when(ddlGeneratorService).generateDdl(anyString(), any(), any());
        doReturn(List.of(
                new org.apache.kafka.clients.consumer.ConsumerRecord<>(topic, 0, 0L, null, "{\"status\":\"OK\"}"),
                new org.apache.kafka.clients.consumer.ConsumerRecord<>(topic, 0, 1L, null, "{\"status\":\"OK\"}"),
                new org.apache.kafka.clients.consumer.ConsumerRecord<>(topic, 0, 2L, null, "{\"status\":\"KO\"}")
        )).when(kafkaAdminService).getEarliestRecords(eq(topic), anyInt());
    }

    private QueryRequest directCount(String sql) {
        return QueryRequest.directSql(sql, 50, 10_000L, "earliest-offset");
    }

    @Test
    void twoDirectAggregatesOverOneTopicShareASingleRead() throws Exception {
        stubCountableTopic("shared.count.topic");

        FlinkSqlService.QueryPair pair = service.executeSqlPair(
                directCount("SELECT COUNT(*) AS metric_value FROM shared_count_topic WHERE status = 'KO'"),
                directCount("SELECT COUNT(*) AS metric_value FROM shared_count_topic WHERE status = 'OK'"));

        assertNoError(pair.first());
        assertNoError(pair.second());
        assertEquals(1L, ((Number) pair.first().rows().get(0).get("metric_value")).longValue());
        assertEquals(2L, ((Number) pair.second().rows().get(0).get("metric_value")).longValue());
        assertTrue(pair.sharedScan(), "the second count must come out of the first one's records");
        verify(kafkaAdminService, times(1)).getEarliestRecords(eq("shared.count.topic"), anyInt());
    }

    @Test
    void twoAggregatesOverDifferentTopicsShareNothing() throws Exception {
        doReturn(List.of("left.count.topic", "right.count.topic")).when(kafkaAdminService).listTopics();
        doReturn(MessageFormat.JSON).when(schemaInferenceService).detectFormat(anyString());
        doReturn(Map.of("status", "STRING")).when(schemaInferenceService).inferSchema(anyString(), any());
        // One DDL per topic: a single table name would make the second registration fail with
        // "already exists", and auto-registration returns before the direct read ever happens.
        doAnswer(invocation -> "CREATE TABLE "
                + invocation.getArgument(0).toString().replace('.', '_')
                + " (status STRING) WITH ('connector'='datagen','number-of-rows'='0')")
                .when(ddlGeneratorService).generateDdl(anyString(), any(), any());
        doReturn(List.of(new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                "left.count.topic", 0, 0L, null, "{\"status\":\"OK\"}")))
                .when(kafkaAdminService).getEarliestRecords(eq("left.count.topic"), anyInt());
        doReturn(List.of()).when(kafkaAdminService).getEarliestRecords(eq("right.count.topic"), anyInt());

        FlinkSqlService.QueryPair pair = service.executeSqlPair(
                directCount("SELECT COUNT(*) AS metric_value FROM right_count_topic"),
                directCount("SELECT COUNT(*) AS metric_value FROM left_count_topic"));

        // A record read from one topic must never answer a question about another, and the flag
        // is what lets the caller keep saying the two counts are a whole query apart.
        assertFalse(pair.sharedScan());
        verify(kafkaAdminService, times(1)).getEarliestRecords(eq("left.count.topic"), anyInt());
        verify(kafkaAdminService, times(1)).getEarliestRecords(eq("right.count.topic"), anyInt());
    }

    @Test
    void aSinceReadModeReadsForwardFromTheInstantItNames() throws Exception {
        stubCountableTopic("windowed.count.topic");
        doReturn(List.of(new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                "windowed.count.topic", 0, 9L, null, "{\"status\":\"OK\"}")))
                .when(kafkaAdminService).getRecordsSinceTimestamp(eq("windowed.count.topic"), eq(1_700_000_000_000L), anyInt());

        QueryResult result = service.executeSql(QueryRequest.directSql(
                "SELECT COUNT(*) AS metric_value FROM windowed_count_topic", 50, 10_000L,
                FlinkSqlService.sinceReadMode(1_700_000_000_000L)));

        // The two offset modes say which end of the log to enter by; neither expresses "the last
        // ten minutes", which is what two topics read over one window need.
        assertNoError(result);
        assertEquals(1L, ((Number) result.rows().get(0).get("metric_value")).longValue());
        verify(kafkaAdminService, never()).getEarliestRecords(eq("windowed.count.topic"), anyInt());
        verify(kafkaAdminService, never()).getRecentRecords(eq("windowed.count.topic"), anyInt());
    }

    /** Registers 'strict.mode.topic' as a 2-row datagen table, with Kafka records behind it. */
    private void stubRegisteredTopicWithRecords() throws Exception {
        doReturn(List.of("strict.mode.topic")).when(kafkaAdminService).listTopics();
        doReturn(MessageFormat.JSON).when(schemaInferenceService).detectFormat("strict.mode.topic");
        doReturn(Map.of("event_id", "STRING", "payload", "STRING"))
                .when(schemaInferenceService).inferSchema(anyString(), any());
        doReturn("CREATE TABLE strict_mode_topic (" +
                "  event_id STRING, payload STRING" +
                ") WITH ('connector'='datagen','number-of-rows'='2')")
                .when(ddlGeneratorService).generateDdl(anyString(), any(), any());
        // If the direct reader were to take over, it would happily return these.
        doReturn(List.of(
                new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                        "strict.mode.topic", 0, 0L, null, "{\"event_id\":\"E1\",\"payload\":\"p1\"}"),
                new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                        "strict.mode.topic", 0, 1L, null, "{\"event_id\":\"E2\",\"payload\":\"p2\"}")
        )).when(kafkaAdminService).getRecentRecords(eq("strict.mode.topic"), anyInt());
    }

    @Test
    void anUnknownColumnIsReportedInsteadOfFallingBackToRowsOfNulls() throws Exception {
        stubRegisteredTopicWithRecords();

        QueryResult result = execute("SELECT nonexistent_col FROM strict_mode_topic");

        assertHasError(result);
        assertTrue(result.rows().isEmpty(), "A rejected query must not return rows, got: " + result.rows());
        assertTrue(result.error().toLowerCase().contains("nonexistent_col"),
                "The error must name the offending column, got: " + result.error());
    }

    @Test
    void aSyntaxErrorIsReportedWithItsPosition() throws Exception {
        stubRegisteredTopicWithRecords();

        QueryResult result = execute("SELECT event_id, FROM strict_mode_topic");

        assertHasError(result);
        assertTrue(result.rows().isEmpty(), "A malformed query must not return rows, got: " + result.rows());
        assertTrue(result.error().matches("(?s).*line \\d+, column \\d+.*"),
                "The error must carry a line/column so the editor can point at it, got: " + result.error());
    }

    /**
     * Le disjoncteur se rouvre tout seul, et se referme s'il avait raison.
     *
     * <p>Il latchait pour la vie du processus, « redémarrez l'application » étant la seule sortie.
     * Défendable tant que la cause supposée était un défaut de version de Flink ; ce dépôt a
     * depuis vu une panne d'<em>environnement</em> le déclencher — un job qui n'obtenait pas ses
     * emplacements — donc un redémarrage était exigé pour une chose déjà réparée.
     */
    @Test
    void theSelectCircuitBreakerReopensOnceTheIntervalHasPassed() throws Exception {
        stubRegisteredTopicWithRecords();

        service.tripFlinkSelectAt(System.currentTimeMillis());
        QueryResult stillClosed = service.executeSql(QueryRequest.sql(
            "SELECT event_id FROM strict_mode_topic", 10, 5_000L, null));
        assertEquals("KAFKA_DIRECT", stillClosed.engine(), "inside the interval the planner is not tried");
        assertTrue(service.isFlinkSelectDisabled());

        service.tripFlinkSelectAt(
            System.currentTimeMillis() - FlinkSqlService.FLINK_SELECT_RETRY_AFTER_MS - 1);
        QueryResult retried = service.executeSql(QueryRequest.sql(
            "SELECT event_id FROM strict_mode_topic", 10, 5_000L, null));

        assertNoError(retried);
        assertEquals("FLINK", retried.engine(), "past the interval one attempt is allowed");
        assertFalse(service.isFlinkSelectDisabled(),
            "and a successful attempt clears the latch — leaving it up would make the warning "
                + "say the engine is off while it answers");
    }

    @Test
    void repeatedTyposDoNotTripTheSelectCircuitBreaker() throws Exception {
        stubRegisteredTopicWithRecords();

        // One more than FLINK_SELECT_FAILURE_THRESHOLD. If user errors counted, the planner
        // would be disabled for the rest of the process and every later SELECT would silently
        // downgrade to the direct reader.
        for (int i = 0; i < 4; i++) {
            assertHasError(execute("SELECT nonexistent_col FROM strict_mode_topic"));
        }

        QueryResult valid = execute("SELECT event_id, payload FROM strict_mode_topic");

        assertNoError(valid);
        assertEquals("FLINK", valid.engine(), "The Flink planner must still be in use after user typos");
    }

    @Test
    void anUnregisteredButExistingTopicStillFallsBackToTheDirectReader() throws Exception {
        // Schema inference finds nothing (empty topic), so auto-registration is deliberately
        // skipped and the planner is *expected* to report the table as unknown. That is our
        // doing, not a typo — the direct reader must still serve the query.
        doReturn(List.of("no.schema.topic")).when(kafkaAdminService).listTopics();
        doReturn(MessageFormat.JSON).when(schemaInferenceService).detectFormat("no.schema.topic");
        doReturn(Map.<String, String>of()).when(schemaInferenceService).inferSchema(anyString(), any());
        doReturn(List.of(
                new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                        "no.schema.topic", 0, 0L, null, "{\"id\":\"A\"}"),
                new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                        "no.schema.topic", 0, 1L, null, "{\"id\":\"B\"}")
        )).when(kafkaAdminService).getRecentRecords(eq("no.schema.topic"), anyInt());

        QueryResult result = execute("SELECT id FROM no_schema_topic");

        assertNoError(result);
        assertEquals(2, result.rows().size(), "The direct reader must still serve an unregistered topic");
        verify(ddlGeneratorService, never()).generateDdl(anyString(), any(), any());

        // Et le changement de moteur est dit. Sans cela, la réponse est `engine: KAFKA_DIRECT`,
        // `error: null`, `warnings: []` — indiscernable d'une requête que ce lecteur était censé
        // traiter, alors que JOIN et sous-requêtes viennent d'être perdus en silence.
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("direct Kafka reader")),
                "the engine change must travel with the rows, got: " + result.warnings());
        // Le motif est le nôtre — aucun schéma n'a pu être inféré — et non le « Object not found »
        // du planner, qui enverrait chercher une faute de frappe dans un nom correct.
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("No schema could be inferred")),
                "a deliberate skip must not be reported as the planner's own complaint, got: "
                    + result.warnings());
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("not found")),
                "got: " + result.warnings());
    }

    /**
     * Une requête que le planner sert vraiment ne porte aucun avertissement de repli.
     *
     * <p>Le pendant du cas ci-dessus, et il compte autant : un avertissement qui apparaît toujours
     * cesse d'être lu. C'est ce qui rend le premier utilisable comme signal.
     */
    @Test
    void aQueryThePlannerAnswersCarriesNoFallbackWarning() throws Exception {
        stubRegisteredTopicWithRecords();

        QueryResult result = execute("SELECT event_id, payload FROM strict_mode_topic");

        assertNoError(result);
        assertEquals("FLINK", result.engine());
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("direct Kafka reader")),
                "got: " + result.warnings());
    }

    /**
     * « Les plus récentes » est une question que seul le lecteur direct sait poser.
     *
     * <p>Tant que le planner ne répondait à rien, {@code readMode} était honoré par accident. Une
     * fois le moteur réparé, la table auto-enregistrée démarre en {@code earliest-offset} et le
     * sélecteur « Latest » rendait les lignes les plus <em>anciennes</em> — une réponse plausible
     * et fausse, ce qui est le pire des deux sens.
     */
    @Test
    void aNamedRecentReadModeIsAnsweredByTheReaderThatCanHonourIt() throws Exception {
        stubRegisteredTopicWithRecords();

        QueryResult latest = service.executeSql(QueryRequest.sql(
            "SELECT event_id, payload FROM strict_mode_topic", 10, 5_000L, "latest-offset"));

        assertNoError(latest);
        assertEquals("KAFKA_DIRECT", latest.engine(),
            "the planner cannot express \"the most recent N records\", so it must not answer it");
        assertTrue(latest.warnings().stream().anyMatch(w -> w.contains("most recent")),
            "and the reader change must say why, got: " + latest.warnings());
    }

    /**
     * Mais seulement quand il est <em>nommé</em>. {@code null} veut dire « l'appelant ne se
     * prononce pas » — l'audit, les aperçus de table, la plupart des tests — et le renvoyer au
     * lecteur direct reviendrait à défaire la réparation du planner pour tout le monde.
     */
    @Test
    void anAbsentReadModeIsNotAnIntentionAndStaysOnThePlanner() throws Exception {
        stubRegisteredTopicWithRecords();

        QueryResult unspecified = service.executeSql(QueryRequest.sql(
            "SELECT event_id, payload FROM strict_mode_topic", 10, 5_000L, null));

        assertNoError(unspecified);
        assertEquals("FLINK", unspecified.engine());

        QueryResult earliest = service.executeSql(QueryRequest.sql(
            "SELECT event_id, payload FROM strict_mode_topic", 10, 5_000L, "earliest-offset"));
        assertEquals("FLINK", earliest.engine(),
            "earliest is the one mode the planner does express");
    }

    @Test
    void aTrulyMissingTableIsReportedRatherThanScannedForNothing() throws Exception {
        doReturn(List.of("some.other.topic")).when(kafkaAdminService).listTopics();

        QueryResult result = execute("SELECT id FROM totally_absent_table");

        assertHasError(result);
        assertTrue(result.error().toLowerCase().contains("totally_absent_table"),
                "The error must name the table the user asked for, got: " + result.error());
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Windowed reads on the direct engine: approximate, and say so
    // ──────────────────────────────────────────────────────────────────────────────

    /** Puts timestamped records behind 'win.topic' and forces the direct reader. */
    private void stubWindowTopic() throws Exception {
        doReturn(List.of("win.topic")).when(kafkaAdminService).listTopics();
        // Empty schema → auto-registration is skipped, so the query lands on the direct reader.
        doReturn(MessageFormat.JSON).when(schemaInferenceService).detectFormat(anyString());
        doReturn(Map.<String, String>of()).when(schemaInferenceService).inferSchema(anyString(), any());
        doReturn(List.of(
                new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                        "win.topic", 0, 0L, null, "{\"id\":\"a\",\"event_time\":\"2026-01-01T00:00:00Z\"}"),
                new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                        "win.topic", 0, 1L, null, "{\"id\":\"b\",\"event_time\":\"2026-01-01T00:07:00Z\"}")
        )).when(kafkaAdminService).getRecentRecords(eq("win.topic"), anyInt());
    }

    @Test
    void aTumblingWindowRunsOnTheDirectReaderWithoutCaveats() throws Exception {
        stubWindowTopic();

        QueryResult result = execute("SELECT window_start, window_end, COUNT(*) AS c FROM TABLE("
                + "TUMBLE(TABLE win_topic, DESCRIPTOR(event_time), INTERVAL '5' MINUTE)) "
                + "GROUP BY window_start, window_end");

        assertNoError(result);
        assertEquals(2, result.rows().size(), "two records 7 minutes apart fall in two 5-minute buckets");
        // Ce qui est affirmé, c'est l'absence de *caveat d'approximation* — pas l'absence de
        // tout avertissement : cette requête tourne sur le lecteur direct, et le repli le dit
        // désormais, ce qui est vrai ici comme ailleurs. HOP et SESSION, eux, ajoutent leur
        // approximation par-dessus, et c'est cette phrase-là que les cas suivants cherchent.
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("approximated")),
                "TUMBLE is emulated exactly, got: " + result.warnings());
    }

    @Test
    void aHoppingWindowIsApproximatedAndTheResultSaysSo() throws Exception {
        stubWindowTopic();

        // Before: the caller routed HOP here and a TUMBLE-only regex rejected it with
        // "Cannot parse TUMBLE syntax", which explained neither the cause nor the fix.
        QueryResult result = execute("SELECT window_start, window_end, COUNT(*) AS c FROM TABLE("
                + "HOP(TABLE win_topic, DESCRIPTOR(event_time), INTERVAL '1' MINUTE, INTERVAL '5' MINUTE)) "
                + "GROUP BY window_start, window_end");

        assertNoError(result);
        assertFalse(result.rows().isEmpty());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("HOP")),
                "the approximation must travel with the rows, got: " + result.warnings());
    }

    @Test
    void aSessionWindowWithAPartitionKeyStillParses() throws Exception {
        stubWindowTopic();

        // The PARTITION BY clause sits between the table and the descriptor — the regex has to
        // step over it rather than fail to match.
        QueryResult result = execute("SELECT window_start, window_end, COUNT(*) AS c FROM TABLE("
                + "SESSION(TABLE win_topic PARTITION BY id, DESCRIPTOR(event_time), INTERVAL '5' MINUTE)) "
                + "GROUP BY window_start, window_end");

        assertNoError(result);
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("SESSION")),
                "expected the approximation caveat, got: " + result.warnings());
    }

    @Test
    void theBucketWidthOfAHopIsItsSizeNotItsSlide() throws Exception {
        stubWindowTopic();

        // HOP(slide, size): with a 1-hour size both records land in one bucket, whereas reading
        // the slide (1 minute) as the width would produce two.
        QueryResult result = execute("SELECT window_start, window_end, COUNT(*) AS c FROM TABLE("
                + "HOP(TABLE win_topic, DESCRIPTOR(event_time), INTERVAL '1' MINUTE, INTERVAL '1' HOUR)) "
                + "GROUP BY window_start, window_end");

        assertNoError(result);
        assertEquals(1, result.rows().size(), "both records belong to the same 1-hour bucket");
    }

    /**
     * Une fenêtre sur une colonne sans watermark : le repli est expliqué, pas recopié du planner.
     *
     * <p>Le planner refuse de construire la fenêtre — « The window function requires the timecol
     * is a time attribute type, but is TIMESTAMP(3) » — et il le dit enveloppé dans la règle
     * Calcite qui a échoué, ses arguments et le plan {@code rel#…}. Ce pavé arrivait tel quel dans
     * les avertissements de l'éditeur, derrière une phrase disant que la requête s'était repliée
     * « faute de JOIN et de sous-requêtes » : trois cents caractères d'état interne, et pas un mot
     * sur la colonne en cause ni sur ce qui la répare.
     *
     * <p>Le repli lui-même est bon et reste : ce lecteur calcule vraiment la fenêtre, en résolvant
     * la colonne dans le message. Ce qui change est ce qu'on en dit.
     */
    @Test
    void aWindowOnAColumnWithNoWatermarkSaysWhichColumnAndHowToFixIt() throws Exception {
        stubNoWatermarkTopic();

        QueryResult result = execute("SELECT window_start, window_end, COUNT(*) AS c FROM TABLE("
                + "TUMBLE(TABLE win_no_watermark, DESCRIPTOR(event_time), INTERVAL '5' MINUTE)) "
                + "GROUP BY window_start, window_end");

        assertNoError(result);
        assertEquals("KAFKA_DIRECT", result.engine(), "the direct reader answers the window");
        String caveat = result.warnings().stream()
                .filter(w -> w.contains("time attribute"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no time-attribute caveat, got: " + result.warnings()));
        assertTrue(caveat.contains("`event_time`"),
                "the caveat must name the column the window is opened over, got: " + caveat);
        assertTrue(caveat.contains("WATERMARK FOR"),
                "and the gesture that fixes it, got: " + caveat);
        assertFalse(caveat.contains("rel#"),
                "the planner's internal plan has no business in a warning, got: " + caveat);
    }

    /**
     * Et elle ne compte pas pour le disjoncteur.
     *
     * <p>C'est la moitié qui coûtait le plus cher : classé en panne moteur, ce refus incrémentait
     * le compteur, donc trois fenêtres d'affilée — la chose la plus naturelle à faire dans
     * l'assistant de fenêtrage — coupaient le planner Flink pour <em>toutes</em> les requêtes du
     * processus pendant dix minutes, jointures et sous-requêtes comprises. La définition d'une
     * table n'est pas une panne du moteur.
     */
    @Test
    void suchAWindowNeverTripsTheCircuitBreaker() throws Exception {
        stubNoWatermarkTopic();
        String windowed = "SELECT window_start, COUNT(*) AS c FROM TABLE("
                + "TUMBLE(TABLE win_no_watermark, DESCRIPTOR(event_time), INTERVAL '5' MINUTE)) "
                + "GROUP BY window_start";

        for (int i = 0; i < 4; i++) {
            assertNoError(execute(windowed));
        }

        assertFalse(service.isFlinkSelectDisabled(),
                "a table without a watermark must not disable the planner for the whole process");
        // Et le planner répond toujours à ce qui n'est pas une fenêtre.
        assertEquals("FLINK", execute("SELECT order_id FROM orders").engine());
    }

    /**
     * Une fenêtre que le planner accepte mais ne peut pas terminer : le message le dit.
     *
     * <p>Le watermark posé, la fenêtre se planifie — et une source Kafka non bornée ne se termine
     * pas, donc sa dernière fenêtre ne se ferme que quand des enregistrements plus récents
     * arrivent : la collecte ne rend la main qu'au plafond de lignes ou au budget. Le message
     * générique de dépassement de délai décrit alors mal ce qui s'est passé — « le topic a
     * peut-être moins de messages que la limite » envoie chercher un problème de taille — là où
     * c'est une propriété de la lecture, qui a une sortie.
     */
    @Test
    void aWindowThePlannerCannotFinishSaysThatRatherThanQuotingATimeout() throws Exception {
        doReturn(List.of("win.unbounded")).when(kafkaAdminService).listTopics();
        doReturn(List.of(
                new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                        "win.unbounded", 0, 0L, null,
                        "{\"id\":\"a\",\"event_time\":\"2026-01-01T00:00:00Z\"}")
        )).when(kafkaAdminService).getRecentRecords(eq("win.unbounded"), anyInt());

        QueryResult result = service.executeSql(new QueryRequest(
                "SELECT window_start, COUNT(*) AS c FROM TABLE("
                    + "TUMBLE(TABLE win_unbounded, DESCRIPTOR(event_time), INTERVAL '5' MINUTE)) "
                    + "GROUP BY window_start", null, 50, 200L, null));

        assertNoError(result);
        assertEquals("KAFKA_DIRECT", result.engine());
        String caveat = result.warnings().stream()
                .filter(w -> w.contains("could not finish"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no windowed-read caveat, got: " + result.warnings()));
        assertTrue(caveat.contains("scan.bounded.mode"),
                "the caveat must name the way out, got: " + caveat);
        assertFalse(caveat.contains("fewer messages than the limit"),
                "the generic timeout sentence sends the reader after the wrong thing, got: " + caveat);
    }

    /** Des enregistrements derrière `win_no_watermark`, pour que le repli ait de quoi répondre. */
    private void stubNoWatermarkTopic() throws Exception {
        // Le topic existe pour le lecteur direct ; la table, elle, est déjà enregistrée, donc
        // l'auto-enregistrement passe son chemin et la définition sans watermark est bien celle
        // que le planner voit.
        doReturn(List.of("win.no.watermark")).when(kafkaAdminService).listTopics();
        doReturn(List.of(
                new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                        "win.no.watermark", 0, 0L, null,
                        "{\"id\":\"a\",\"event_time\":\"2026-01-01T00:00:00Z\"}"),
                new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                        "win.no.watermark", 0, 1L, null,
                        "{\"id\":\"b\",\"event_time\":\"2026-01-01T00:07:00Z\"}")
        )).when(kafkaAdminService).getRecentRecords(eq("win.no.watermark"), anyInt());
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Client-supplied query id (what makes "stop this query" possible)
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void aWellFormedClientQueryIdIsKeptSoTheClientCanCancelWithIt() {
        String clientId = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";

        assertEquals(clientId, FlinkSqlService.resolveQueryId(clientId));
    }

    @Test
    void aMissingOrJunkQueryIdIsReplacedByAGeneratedOne() {
        // The id becomes a job-store key and lands in log lines, so it must not carry free text.
        for (String junk : new String[] { null, "", "short", "has spaces", "../../etc/passwd",
                                          "a".repeat(65), "quote'injection" }) {
            String resolved = FlinkSqlService.resolveQueryId(junk);

            assertNotEquals(junk, resolved);
            assertDoesNotThrow(() -> java.util.UUID.fromString(resolved),
                    "fallback must be a plain UUID, got: " + resolved);
        }
    }

    @Test
    void anExplicitQueryIdSurvivesIntoTheJobRegistry() throws Exception {
        stubRegisteredTopicWithRecords();
        String clientId = "11111111-2222-3333-4444-555555555555";

        QueryResult result = service.executeSql(
                new QueryRequest("SELECT event_id FROM strict_mode_topic", null, 50, 10_000L, null, clientId));

        assertNoError(result);
        // The registry is what POST /api/query/cancel/{queryId} looks the run up in; a bounded
        // datagen job may already have finished, so we only assert the id was never rewritten.
        assertEquals(clientId, FlinkSqlService.resolveQueryId(clientId));
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────────

    private QueryResult execute(String sql) {
        return service.executeSql(new QueryRequest(sql, null, 50, 10_000L, null));
    }

    private void assertNoError(QueryResult result) {
        assertNull(result.error(), "Expected no error but got: " + result.error());
    }

    private void assertHasError(QueryResult result) {
        assertNotNull(result.error(), "Expected an error but query succeeded with rows: " + result.rows());
        assertFalse(result.error().isBlank(), "Error message must not be blank");
    }

    // ────────────────────────────────────────────────────────────────────────────
    // ReDoS: the SQL-parsing regexes run on a statement the caller supplies, on the
    // request thread, before the query timeout bounds anything. Several carried the
    // classic ambiguity — two quantifiers competing for the same characters — and
    // CodeQL's java/polynomial-redos flagged them. Measured before the fix, on the
    // inputs CodeQL names: SELECT_PROJECTION and AGGREGATE_CALL took ~1.5 s at 1 000
    // padding characters and over ten seconds at 2 000; the block-comment stripper
    // did not merely run slow but threw StackOverflowError.
    //
    // The fix is possessive quantifiers (and a lazy scan for the comment stripper),
    // chosen because each was verified to leave every match and every captured group
    // identical over a corpus of real and degenerate SQL. Three sibling patterns —
    // GROUP BY and the two WHERE blocks — were deliberately NOT converted: possessive
    // there changes what they match on all-whitespace input, and they measure linear
    // anyway. DdlGeneratorService.SENSITIVE_PROP was left alone too, and that one
    // matters: making its key scan possessive stops 'password' from ever matching, so
    // the credential masking silently passes secrets through.
    //
    // Both halves are pinned below, because either alone would be a false comfort: a
    // fast regex that no longer parses SQL is not a fix, and unchanged parsing that
    // still hangs is not either.

    /** The padding that makes the old patterns blow up. Enormous margin: the fixed ones are ~0 ms. */
    private static String pad(String prefix, int n) {
        return prefix + " ".repeat(n);
    }

    @Test
    void redosSelectProjectionIsBounded() {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
            () -> service.extractSelectedColumns(pad("select ", 20_000)),
            "SELECT projection regex did not terminate — the possessive quantifiers were lost");
    }

    @Test
    void redosAggregateAndWindowScanAreBounded() {
        // Both go through the direct engine's aggregate detection.
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            service.extractSelectedColumns(pad("select sum(", 20_000));
            service.extractSelectedColumns(pad("select count(distinct ", 20_000));
        });
    }

    // Deliberately no timing test for the WHERE condition scan. Making it possessive is a
    // constant-factor win (measured 1 063 ms -> 279 ms on 8 000 characters) and it clears the
    // CodeQL alert, but it is NOT a complexity fix: the scan is unanchored, so a long run that
    // cannot start a condition still costs O(n^2) overall. There is no input size at which the
    // old form fails this and the new one passes, and a timing test that cannot separate them
    // would assert nothing while looking like it did.

    @Test
    void redosBlockCommentStripperDoesNotOverflowTheStack() {
        // This one threw StackOverflowError rather than running slow: the nested quantifier
        // of the unrolled-loop form recursed once per repetition. An Error, not an Exception,
        // so nothing on the request path would have caught it.
        // 20 000 repetitions with no closing delimiter anywhere: the old regex overflowed the
        // stack, and a lazy-scan rewrite of it was still quadratic here. The scanner is linear.
        String bomb = "/**" + ")/**".repeat(20_000);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
            () -> assertNotNull(service.stripSqlComments(bomb)));
    }

    @Test
    void strippingCommentsStillBehaves() {
        assertEquals("SELECT 1", service.stripSqlComments("/* lead */ SELECT 1"));
        assertEquals("SELECT   a FROM t", service.stripSqlComments("SELECT /* mid */ a FROM t"));
        assertEquals("SELECT 1", service.stripSqlComments("/* a */ /* b */ SELECT 1").trim());
        assertEquals("SELECT 1", service.stripSqlComments("/* multi\nline */ SELECT 1"));
        assertEquals("SELECT 1", service.stripSqlComments("SELECT 1 -- trailing"). trim());
        // An unterminated block comment is not a comment: it must not swallow the statement.
        assertTrue(service.stripSqlComments("/* never closed SELECT 1").contains("SELECT 1"));
    }

    /**
     * Un hint survit au nettoyage — c'est du SQL, pas un commentaire.
     *
     * <p>La syntaxe des hints de Calcite et de Flink est en forme de commentaire : seul le
     * {@code +} qui suit l'ouverture les distingue. Ce nettoyage les effaçait donc tous avant que
     * la requête n'atteigne le planner, si bien qu'un {@code OPTIONS(…)} écrit dans l'éditeur
     * n'avait jamais aucun effet — et que l'expérience concluant que ce connecteur refuse
     * {@code scan.bounded.mode} portait sur une option qui n'était jamais partie.
     */
    @Test
    void aHintIsNotAComment() {
        String hinted = "SELECT * FROM t /*+ OPTIONS('scan.startup.mode'='earliest-offset') */";
        assertEquals(hinted, service.stripSqlComments(hinted),
            "a hint must reach the planner verbatim");

        // Et un commentaire ordinaire dans la même requête s'en va toujours.
        String mixed = service.stripSqlComments("SELECT /* why */ * FROM t /*+ OPTIONS('k'='v') */");
        assertFalse(mixed.contains("why"), mixed);
        assertTrue(mixed.endsWith("/*+ OPTIONS('k'='v') */"), mixed);

        // Le hint ne perturbe ni la reconnaissance de la table ni la liste blanche : les deux
        // travaillent sur le texte nettoyé, qui le porte désormais.
        assertEquals("t", service.extractPrimaryTable(hinted));
        assertTrue(service.stripSqlComments("/* lead */ " + hinted).startsWith("SELECT"));
    }

    @Test
    void projectionAndWhereParsingAreUnchanged() {
        assertEquals(List.of("id", "name"), service.extractSelectedColumns("SELECT id, name FROM t"));
        assertEquals(List.of("id", "name"), service.extractSelectedColumns("SELECT  id ,  name   FROM   t"));
        assertEquals(List.of(), service.extractSelectedColumns("SELECT * FROM t"));
        assertEquals(List.of("a"), service.extractSelectedColumns("select\na\nfrom t"));
        // A column named like the keyword must not end the projection early.
        assertEquals(List.of("from_id"), service.extractSelectedColumns("SELECT from_id FROM t"));

        assertEquals(Map.of("status", "SHIPPED"),
            service.extractSimpleWhere("SELECT * FROM o WHERE status = 'SHIPPED'"));
        assertEquals(Map.of("a", "x", "b", "y"),
            service.extractSimpleWhere("SELECT * FROM o WHERE a='x' AND b = 'y' LIMIT 10"));
        assertEquals(Map.of("customer.city", "Lyon"),
            service.extractSimpleWhere("SELECT * FROM o WHERE `customer.city` = 'Lyon'"));
        assertTrue(service.extractSimpleWhere("SELECT * FROM o").isEmpty());

        // And the warning about what the direct engine dropped still fires.
        assertFalse(service.unsupportedWhereFragments("SELECT * FROM o WHERE a > 5").isEmpty());
        assertTrue(service.unsupportedWhereFragments("SELECT * FROM o WHERE a = 'x'").isEmpty());
    }


    /**
     * Le DDL généré porte les identifiants du cluster — `DdlGeneratorService` recopie toutes les
     * propriétés client Kafka dans le `WITH (…)`, mots de passe SSL et `sasl.jaas.config`
     * compris, parce que le connecteur Flink en a besoin. Le fichier de log, non.
     *
     * Ce test lit la sortie réelle du logger plutôt que de vérifier qu'une fonction a été
     * appelée : ce qui doit être vrai, c'est qu'aucun secret n'atteint l'appender, quel que soit
     * le chemin par lequel la ligne est construite.
     */
    @Test
    void theLoggedDdlCarriesNoCredentials() throws Exception {
        var logger = (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(FlinkSqlService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        var previousLevel = logger.getLevel();
        logger.addAppender(appender);
        // DEBUG explicitement : la ligne visée n'existe qu'à ce niveau, et c'est celui qu'un
        // opérateur active pour diagnostiquer — donc le seul où le test a un sens.
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            doReturn(List.of("secret.topic")).when(kafkaAdminService).listTopics();
            doReturn(MessageFormat.JSON).when(schemaInferenceService).detectFormat("secret.topic");
            doReturn(Map.of("id", "STRING")).when(schemaInferenceService).inferSchema(anyString(), any());
            doReturn("CREATE TABLE secret_topic (id STRING) WITH ("
                    + "'connector'='datagen','number-of-rows'='1',"
                    + "'properties.ssl.truststore.password'='HUNTER2-TRUSTSTORE',"
                    + "'properties.ssl.keystore.password'='HUNTER2-KEYSTORE',"
                    + "'properties.sasl.jaas.config'='org.apache...required username=\"k\" password=\"HUNTER2-SASL\";')")
                    .when(ddlGeneratorService).generateDdl(anyString(), any(), any());

            execute("SELECT id FROM secret_topic");

            String logged = appender.list.stream()
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .collect(java.util.stream.Collectors.joining("\n"));
            assertFalse(logged.contains("HUNTER2"),
                "un identifiant du DDL a atteint le log :\n" + logged);
            assertTrue(logged.contains("Auto-registering table"),
                "la ligne visée n'a pas été journalisée — le test ne prouverait rien :\n" + logged);
            assertTrue(logged.contains("******"),
                "le DDL journalisé devrait porter les valeurs masquées :\n" + logged);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }
    }
}
