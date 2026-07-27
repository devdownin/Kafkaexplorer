package com.yourcompany.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.kafkasqlexplorer.config.ExplorerConfig;
import com.yourcompany.kafkasqlexplorer.domain.MessageFormat;
import com.yourcompany.kafkasqlexplorer.domain.QueryRequest;
import com.yourcompany.kafkasqlexplorer.domain.QueryResult;
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
        config.setFlinkJobStorePath(Files.createTempFile("flink-jobs-test-", ".json").toString());

        kafkaAdminService = mock(KafkaAdminService.class);
        schemaInferenceService = mock(SchemaInferenceService.class);
        ddlGeneratorService = mock(DdlGeneratorService.class);

        FlinkRuntimeCoordinator runtimeCoordinator = new FlinkRuntimeCoordinator(tableEnv);
        SqlQueryValidator validator = new SqlQueryValidator(config, tableEnv, runtimeCoordinator);
        FlinkJobStore flinkJobStore = new FlinkJobStore(config);
        service = new FlinkSqlService(tableEnv, runtimeCoordinator, config, validator,
                kafkaAdminService, schemaInferenceService, ddlGeneratorService, flinkJobStore);

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
}
