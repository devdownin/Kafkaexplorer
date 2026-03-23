// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.domain.MessageFormat;
import com.yourcompany.kafkasqlexplorer.domain.QueryRequest;
import com.yourcompany.kafkasqlexplorer.domain.QueryResult;
import com.yourcompany.kafkasqlexplorer.config.ExplorerConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.types.Row;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Core service for executing SQL statements using the embedded Apache Flink engine.
 * This service manages the lifecycle of Flink jobs and provides an abstraction for
 * running queries against Kafka topics registered as dynamic tables.
 */
@Service
public class FlinkSqlService {

    private static final Logger log = LoggerFactory.getLogger(FlinkSqlService.class);
    private final TableEnvironment tableEnv;
    private final ExplorerConfig explorerConfig;
    private final SqlQueryValidator sqlQueryValidator;
    private final KafkaAdminService kafkaAdminService;
    private final SchemaInferenceService schemaInferenceService;
    private final DdlGeneratorService ddlGeneratorService;

    /**
     * Dedicated executor for fetching results from Flink to avoid blocking Spring's main threads
     * or polluting the common ForkJoinPool.
     */
    private final ExecutorService queryExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setName("flink-query-fetcher-" + t.getId());
        t.setDaemon(true);
        return t;
    });

    /**
     * Stores metadata about currently running Flink jobs.
     * This is crucial for lineage tracking and manual job cancellation.
     */
    private final Map<String, JobInfo> activeJobs = new ConcurrentHashMap<>();

    public record JobInfo(String sql, JobClient client) {}

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * The classloader that owns the Flink planner classes (flink-table-planner-loader).
     * In a Spring Boot fat-jar, Tomcat sets the Thread context classloader to its own
     * web-app classloader, which cannot see Flink/Calcite internals loaded in the planner
     * isolating classloader. Janino (Calcite's code generator) uses the Thread context
     * classloader to compile RelMetadataHandlerProvider — if it can't resolve Flink classes,
     * it leaves metadataHandlerProvider=null → NPE on every SELECT.
     * We capture the planner classloader once and restore it around every executeSql call.
     */
    private final ClassLoader flinkClassLoader;

    public FlinkSqlService(TableEnvironment tableEnv, ExplorerConfig explorerConfig, SqlQueryValidator sqlQueryValidator,
                           KafkaAdminService kafkaAdminService, SchemaInferenceService schemaInferenceService, DdlGeneratorService ddlGeneratorService) {
        this.tableEnv = tableEnv;
        this.explorerConfig = explorerConfig;
        this.sqlQueryValidator = sqlQueryValidator;
        this.kafkaAdminService = kafkaAdminService;
        this.schemaInferenceService = schemaInferenceService;
        this.ddlGeneratorService = ddlGeneratorService;
        this.flinkClassLoader = tableEnv.getClass().getClassLoader();
        log.info("Flink planner classloader: {}", flinkClassLoader);
        // Register our custom XML extraction function globally in the Flink environment.
        this.tableEnv.createTemporarySystemFunction("XmlExtract", XmlExtractUDF.class);
    }

    /**
     * Executes a Flink operation with the planner classloader set as Thread context classloader.
     * This allows Janino to resolve Flink/Calcite internal classes during code generation.
     */
    private <T> T withFlinkCL(java.util.function.Supplier<T> action) {
        ClassLoader saved = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(flinkClassLoader != null ? flinkClassLoader : saved);
        try {
            return action.get();
        } finally {
            Thread.currentThread().setContextClassLoader(saved);
        }
    }

    public List<String> listTables() {
        return Arrays.asList(tableEnv.listTables());
    }

    public List<String> listViews() {
        return Arrays.asList(tableEnv.listViews());
    }

    public Map<String, String> getTableSchema(String tableName) {
        Map<String, String> schema = new LinkedHashMap<>();
        try {
            tableEnv.from(tableName).getResolvedSchema().getColumns().forEach(col -> {
                schema.put(col.getName(), col.getDataType().toString());
            });
        } catch (Exception e) {
            log.debug("Table not found: {}", tableName);
        }
        return schema;
    }

    /**
     * Executes a Flink SQL statement and returns the results as a QueryResult.
     *
     * IMPORTANT: Since Flink streaming queries are technically infinite, we use a
     * combination of LIMIT and TIMEOUT to ensure the web request returns in a
     * reasonable timeframe.
     */
    /**
     * Flink SQL uses backtick-quoted identifiers (e.g. `demo.customers`).
     * Standard SQL and many editors produce double-quoted identifiers ("demo.customers").
     * This method converts double-quoted identifiers to backtick-quoted ones while
     * leaving single-quoted string literals untouched.
     */
    private String normalizeIdentifierQuotes(String sql) {
        if (sql == null || !sql.contains("\"")) return sql;
        StringBuilder sb = new StringBuilder(sql.length());
        boolean inSingleQuote = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'' ) {
                if (inSingleQuote && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    sb.append("''");  // escaped single quote inside string
                    i++;
                } else {
                    inSingleQuote = !inSingleQuote;
                    sb.append(c);
                }
            } else if (c == '"' && !inSingleQuote) {
                sb.append('`');  // double-quote identifier → backtick
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Before executing a SELECT, checks if the referenced table is already registered in Flink.
     * If not, looks for a Kafka topic whose sanitized name (dots/hyphens → underscores) matches,
     * infers its schema, generates the DDL and registers it automatically.
     *
     * @return null on success (or when no registration is needed), or an error message if
     *         a matching Kafka topic was found but table registration failed.
     */
    private record AutoRegResult(String error, boolean registered) {
        static AutoRegResult skip()          { return new AutoRegResult(null,  false); }
        static AutoRegResult tableCreated()  { return new AutoRegResult(null,  true);  }
        static AutoRegResult fail(String e)  { return new AutoRegResult(e,     false); }
    }

    private AutoRegResult autoRegisterTableIfNeeded(String sql) {
        if (!sql.trim().toUpperCase().startsWith("SELECT")) return AutoRegResult.skip();
        // Extract first table name after FROM (handles backtick and unquoted identifiers)
        Pattern pattern = Pattern.compile("(?i)\\bFROM\\s+[`\"]?([\\w.\\-]+)[`\"]?");
        Matcher matcher = pattern.matcher(sql);
        if (!matcher.find()) return AutoRegResult.skip();

        String rawTableRef = matcher.group(1);
        String flinkTableName = DdlGeneratorService.toTableName(rawTableRef);

        if (listTables().contains(flinkTableName)) return AutoRegResult.skip();

        List<String> topics;
        try {
            topics = kafkaAdminService.listTopics();
        } catch (Exception e) {
            log.warn("Could not list Kafka topics during auto-registration: {}", e.getMessage());
            return AutoRegResult.fail("Cannot reach Kafka broker: " + e.getMessage());
        }

        String matchingTopic = topics.stream()
                .filter(t -> DdlGeneratorService.toTableName(t).equals(flinkTableName))
                .findFirst().orElse(null);

        // No matching topic — the user may have typed a wrong name; let Flink report the error.
        if (matchingTopic == null) return AutoRegResult.skip();

        try {
            MessageFormat format = schemaInferenceService.detectFormat(matchingTopic);
            Map<String, String> schema = schemaInferenceService.inferSchema(matchingTopic, format);
            String ddl = ddlGeneratorService.generateDdl(matchingTopic, schema, format);
            log.debug("Auto-registering table '{}' with DDL:\n{}", flinkTableName, ddl);
            withFlinkCL(() -> tableEnv.executeSql(ddl));
            log.info("Auto-registered table '{}' for Kafka topic '{}'", flinkTableName, matchingTopic);
            return AutoRegResult.tableCreated();
        } catch (Exception e) {
            log.error("Auto-registration failed for topic '{}' (table '{}'): {}", matchingTopic, flinkTableName, e.getMessage(), e);
            return AutoRegResult.fail(String.format(
                "Failed to auto-register Flink table '%s' from Kafka topic '%s': %s",
                flinkTableName, matchingTopic, e.getMessage()));
        }
    }

    /**
     * Strips SQL line comments (-- ...) and block comments (/* ... *&#47;) from a SQL string.
     * Used before keyword checks so that leading comments don't cause false rejections.
     * Note: does not handle comments inside string literals (rare in practice).
     */
    private String stripSqlComments(String sql) {
        if (sql == null) return null;
        // Remove block comments /* ... */
        sql = sql.replaceAll("/\\*[^*]*\\*+(?:[^/*][^*]*\\*+)*/", " ");
        // Remove line comments -- ... to end of line
        sql = sql.replaceAll("--[^\n]*", "");
        return sql.trim();
    }

    public QueryResult executeSql(QueryRequest request) {
        long startTime = System.currentTimeMillis();
        String queryId = UUID.randomUUID().toString();
        // Normalize double-quoted identifiers to backticks before any parsing/validation
        String originalSql = normalizeIdentifierQuotes(request.sql().trim());
        // Strip comments before keyword checks — a query like "-- comment\nSELECT ..."
        // must not be rejected because startsWith("SELECT") would fail on the comment line.
        String strippedSql = stripSqlComments(originalSql);
        String sql = strippedSql.toUpperCase();

        // Security: Prevent execution of dangerous or unsupported DDL/DML.
        if (!sql.startsWith("SELECT") && !sql.startsWith("EXPLAIN") && !sql.startsWith("CREATE TABLE")) {
            return new QueryResult(Collections.emptyList(), Collections.emptyList(), 0, "Only SELECT, EXPLAIN and CREATE TABLE statements are allowed.");
        }

        try {
            sqlQueryValidator.validate(strippedSql);
        } catch (IllegalArgumentException e) {
            return new QueryResult(Collections.emptyList(), Collections.emptyList(), 0, "SQL Validation Error: " + e.getMessage());
        }

        TableResult result = null;
        try {
            AutoRegResult autoReg = autoRegisterTableIfNeeded(strippedSql);
            if (autoReg.error() != null) {
                log.error("Table auto-registration failed — query='{}' error='{}'", request.sql(), autoReg.error());
                return new QueryResult(Collections.emptyList(), Collections.emptyList(),
                        System.currentTimeMillis() - startTime, autoReg.error());
            }
            String sqlToExecute = strippedSql;
            String readMode = request.readMode();
            int limit = request.maxRows() != null ? request.maxRows() : explorerConfig.getDefaultMaxRows();
            long timeout = request.timeout() != null ? request.timeout() : explorerConfig.getDefaultQueryTimeoutMs();

            // SELECT queries: Flink's embedded SQL planner NPEs on all SELECT statements due to a
            // FlinkRelMetadataQuery bug (metadataHandlerProvider=null in all tested Flink versions).
            // Bypass Flink entirely for SELECT and read directly from Kafka instead.
            if (sqlToExecute.trim().toUpperCase().startsWith("SELECT")) {
                QueryResult qr = kafkaDirectSelect(sqlToExecute, readMode, limit, startTime);
                // Propagate auto-registration flag so the frontend can refresh its schema browser.
                if (autoReg.registered()) {
                    return new QueryResult(qr.columns(), qr.rows(), qr.durationMs(), qr.error(), true);
                }
                return qr;
            }

            final String finalSql = sqlToExecute;
            result = withFlinkCL(() -> tableEnv.executeSql(finalSql));
            result.getJobClient().ifPresent(client -> activeJobs.put(queryId, new JobInfo(finalSql, client)));

            // result.collect() starts the Flink job and provides an iterator to fetch results.
            try (org.apache.flink.util.CloseableIterator<Row> it = result.collect()) {
            List<String> columns = result.getResolvedSchema().getColumnNames();
            log.info("[FlinkSQL] queryId={} sql='{}' resolvedColumns={} resolvedSchema={}",
                    queryId, sqlToExecute, columns, result.getResolvedSchema());
            List<Map<String, Object>> rows = new ArrayList<>();

            // We use a CompletableFuture to implement the timeout logic.
            // Streaming queries might not produce data immediately, so we don't want to block indefinitely.
            CompletableFuture<List<Map<String, Object>>> future = CompletableFuture.supplyAsync(() -> {
                List<Map<String, Object>> resultRows = new ArrayList<>();
                int count = 0;
                while (it.hasNext() && count < limit) {
                    Row row = it.next();
                    if (count == 0) {
                        log.info("[FlinkSQL] queryId={} first row arity={} kind={} rowString='{}'",
                                queryId, row.getArity(), row.getKind(), row);
                        for (int i = 0; i < columns.size(); i++) {
                            Object field = row.getField(i);
                            log.info("[FlinkSQL] queryId={} col[{}]='{}' valueType={} value='{}'",
                                    queryId, i, columns.get(i),
                                    field == null ? "null" : field.getClass().getName(), field);
                        }
                    }
                    Map<String, Object> mapRow = new HashMap<>();
                    for (int i = 0; i < columns.size(); i++) {
                        Object field = row.getField(i);
                        // Convert non-serializable Flink internal types to String to avoid JSON issues
                        mapRow.put(columns.get(i), field == null ? null : toSerializable(field));
                    }
                    resultRows.add(mapRow);
                    count++;
                }
                log.info("[FlinkSQL] queryId={} total rows fetched={}", queryId, resultRows.size());
                return resultRows;
            }, queryExecutor);

            try {
                rows = future.get(timeout, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                log.warn("Query timed out after {}ms: {}", timeout, request.sql());
                future.cancel(true);
                cancelJobInternal(result);
                long duration = System.currentTimeMillis() - startTime;
                return new QueryResult(Collections.emptyList(), Collections.emptyList(), duration,
                    "Query timed out after " + timeout + "ms. The Kafka topic may have fewer messages than the limit, " +
                    "or the broker is slow. Try adding LIMIT, reducing maxRows, or switching to 'latest-offset' mode.");
            } catch (ExecutionException ee) {
                log.error("Query execution failed: {}", request.sql(), ee.getCause());
                Throwable cause = ee.getCause();
                if (cause instanceof Exception ex) throw ex;
                throw new RuntimeException(cause);
            }

                long duration = System.currentTimeMillis() - startTime;
                return new QueryResult(columns, rows, duration, null);
            }
        } catch (Exception e) {
            log.error("Flink SQL execution error — query='{}' error='{}'", request.sql(), e.getMessage(), e);
            cancelJobInternal(result);
            long duration = System.currentTimeMillis() - startTime;
            return new QueryResult(Collections.emptyList(), Collections.emptyList(), duration, e.getMessage());
        } finally {
            activeJobs.remove(queryId);
        }
    }

    /**
     * Direct Kafka reader for SELECT queries.
     * Flink's embedded SQL planner has a persistent FlinkRelMetadataQuery bug (metadataHandlerProvider=null)
     * that causes NPE on every SELECT in all tested Flink versions (1.18, 1.20, 2.0).
     * This method bypasses the Flink optimizer entirely and reads from Kafka directly.
     * Aggregate functions (COUNT, SUM, AVG, MAX, MIN) are computed in-process over the fetched rows.
     */
    /**
     * Parses a raw Kafka message value into a field map.
     * Tries JSON first; falls back to XML (direct child elements of root → text content).
     * Returns a map with a single "raw_value" entry only as a last resort.
     */
    private Map<String, Object> parseMessageToRow(String value) {
        if (value == null || value.isBlank()) return Map.of();
        String trimmed = value.trim();

        // ── JSON ────────────────────────────────────────────────────────────
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return objectMapper.readValue(trimmed, new TypeReference<LinkedHashMap<String, Object>>() {});
            } catch (Exception ignored) {}
        }

        // ── XML ─────────────────────────────────────────────────────────────
        if (trimmed.startsWith("<")) {
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                factory.setXIncludeAware(false);
                factory.setExpandEntityReferences(false);
                DocumentBuilder builder = factory.newDocumentBuilder();
                Document doc = builder.parse(new ByteArrayInputStream(trimmed.getBytes(StandardCharsets.UTF_8)));
                Map<String, Object> row = new LinkedHashMap<>();
                flattenXmlElement(doc.getDocumentElement(), "", row);
                if (!row.isEmpty()) return row;
            } catch (Exception ignored) {}
        }

        // ── Fallback ─────────────────────────────────────────────────────────
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("raw_value", value);
        return raw;
    }

    /**
     * Recursively flattens an XML element tree into a dot-notation flat map.
     * Root element is skipped (prefix = "" on first call); each nested level
     * appends ".<tagName>" to the key. Leaf nodes store their text content.
     *
     * Example: &lt;order&gt;&lt;customer&gt;&lt;name&gt;John&lt;/name&gt;&lt;/customer&gt;&lt;/order&gt;
     *   → {"customer.name": "John"}
     */
    private void flattenXmlElement(Element element, String prefix, Map<String, Object> row) {
        NodeList children = element.getChildNodes();
        boolean hasElementChildren = false;
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                hasElementChildren = true;
                break;
            }
        }

        if (prefix.isEmpty()) {
            // Root element — iterate directly into its children without including root tag in path
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                    Element child = (Element) children.item(i);
                    flattenXmlElement(child, child.getTagName(), row);
                }
            }
        } else if (!hasElementChildren) {
            // Leaf node — store trimmed text content
            String text = element.getTextContent().trim();
            if (!text.isEmpty()) {
                row.put(prefix, text);
            }
        } else {
            // Container node — recurse into child elements, extending path
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                    Element child = (Element) children.item(i);
                    flattenXmlElement(child, prefix + "." + child.getTagName(), row);
                }
            }
        }
    }

    /**
     * Retrieves a value from a row map using a dot-notation path.
     * Supports both flat maps (XML: key "customer.name" stored directly) and
     * nested maps (JSON: {"customer": {"name": "John"}} traversed via path segments).
     */
    @SuppressWarnings("unchecked")
    private Object getNestedValue(Map<String, Object> row, String path) {
        // Direct key lookup first — covers XML flat maps and top-level JSON keys
        if (row.containsKey(path)) return row.get(path);
        // Dot-notation traversal for nested JSON objects
        String[] parts = path.split("\\.", -1);
        Object current = row;
        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(part);
                if (current == null) return null;
            } else {
                return null;
            }
        }
        return current;
    }

    private QueryResult kafkaDirectSelect(String sql, String readMode, int limit, long startTime) {
        // Extract table name from FROM clause
        Pattern fromPattern = Pattern.compile("(?i)\\bFROM\\s+`?([\\w.\\-]+)`?");
        Matcher fromMatcher = fromPattern.matcher(sql);
        if (!fromMatcher.find()) {
            return new QueryResult(Collections.emptyList(), Collections.emptyList(),
                System.currentTimeMillis() - startTime, "Cannot parse table name from SQL");
        }
        String rawTableRef = fromMatcher.group(1);
        String flinkTableName = DdlGeneratorService.toTableName(rawTableRef);

        // Detect TUMBLE / HOP / SESSION window functions — route to dedicated handler
        if (Pattern.compile("(?i)\\bTABLE\\s*\\(\\s*(TUMBLE|HOP|SESSION)\\s*\\(").matcher(sql).find()) {
            return kafkaWindowSelect(sql, readMode, limit, startTime);
        }

        // Detect aggregate functions in the SELECT portion only (before FROM)
        boolean isAggregate = Pattern.compile("(?i)\\b(COUNT|SUM|AVG|MAX|MIN)\\s*\\(")
            .matcher(sql.substring(0, fromMatcher.start())).find();

        // Respect LIMIT from SQL if smaller than configured limit (skip for aggregates)
        if (!isAggregate) {
            Pattern limitPattern = Pattern.compile("(?i)\\bLIMIT\\s+(\\d+)");
            Matcher limitMatcher = limitPattern.matcher(sql);
            if (limitMatcher.find()) {
                limit = Math.min(limit, Integer.parseInt(limitMatcher.group(1)));
            }
        }

        // Find the matching Kafka topic
        List<String> topics;
        try {
            topics = kafkaAdminService.listTopics();
        } catch (Exception e) {
            return new QueryResult(Collections.emptyList(), Collections.emptyList(),
                System.currentTimeMillis() - startTime, "Cannot reach Kafka broker: " + e.getMessage());
        }
        String topic = topics.stream()
            .filter(t -> DdlGeneratorService.toTableName(t).equals(flinkTableName))
            .findFirst().orElse(null);
        if (topic == null) {
            return new QueryResult(Collections.emptyList(), Collections.emptyList(),
                System.currentTimeMillis() - startTime,
                "Table '" + rawTableRef + "' not found. No matching Kafka topic exists.");
        }

        // For aggregates, read all available messages; for projections, cap at limit
        int fetch = isAggregate ? 100_000 : limit + 20;
        List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> records =
            "earliest-offset".equals(readMode)
                ? kafkaAdminService.getEarliestRecords(topic, fetch)
                : kafkaAdminService.getRecentRecords(topic, fetch);

        // Parse selected columns and WHERE conditions from SQL
        List<String> requestedCols = isAggregate ? Collections.emptyList() : extractSelectedColumns(sql);
        Map<String, String> whereConds = extractSimpleWhere(sql);

        // Build result rows from JSON messages
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> colSet = new LinkedHashSet<>();
        for (var record : records) {
            if (!isAggregate && rows.size() >= limit) break;
            String value = record.value();
            if (value == null || value.isBlank()) continue;
            Map<String, Object> row = parseMessageToRow(value);
            if (row.isEmpty()) continue;
            if (!matchesWhereConditions(row, whereConds)) continue;
            if (isAggregate) {
                rows.add(row);
                continue;
            }
            if (!requestedCols.isEmpty()) {
                Map<String, Object> projected = new LinkedHashMap<>();
                for (String col : requestedCols) {
                    projected.put(col, toSerializable(getNestedValue(row, col)));
                }
                row = projected;
            } else {
                Map<String, Object> serialized = new LinkedHashMap<>();
                row.forEach((k, v) -> serialized.put(k, toSerializable(v)));
                row = serialized;
            }
            colSet.addAll(row.keySet());
            rows.add(row);
        }

        if (isAggregate) {
            return kafkaAggregateSelect(sql, rows, startTime);
        }

        List<String> columns = requestedCols.isEmpty() ? new ArrayList<>(colSet) : requestedCols;
        log.info("[KafkaDirect] topic='{}' rows={} readMode={}", topic, rows.size(), readMode);
        return new QueryResult(columns, rows, System.currentTimeMillis() - startTime, null);
    }

    /**
     * Computes SQL aggregate functions (COUNT/SUM/AVG/MAX/MIN) over pre-fetched Kafka rows.
     * Supports optional GROUP BY with one or more simple column names.
     * Each aggregate in the SELECT must have an alias (e.g. COUNT(*) AS metric_value).
     */
    private QueryResult kafkaAggregateSelect(String sql, List<Map<String, Object>> inputRows, long startTime) {
        // Parse SELECT portion (everything between SELECT and FROM)
        Matcher selMatcher = Pattern.compile("(?i)^\\s*SELECT\\s+(.+?)\\s+FROM\\b", Pattern.DOTALL).matcher(sql);
        if (!selMatcher.find()) {
            return new QueryResult(Collections.emptyList(), Collections.emptyList(),
                System.currentTimeMillis() - startTime, "Cannot parse SELECT clause for aggregate query");
        }
        String selectPart = selMatcher.group(1);

        // Each aggregate entry: [func, col, alias, distinct("true"/"false")]
        List<String[]> aggs = new ArrayList<>();
        Matcher am = Pattern.compile(
            "(?i)(COUNT|SUM|AVG|MAX|MIN)\\s*\\(\\s*(DISTINCT\\s+)?([^)]+?)\\s*\\)(?:\\s+AS\\s+[`\"]?([\\w]+)[`\"]?)?")
            .matcher(selectPart);
        while (am.find()) {
            String func  = am.group(1).toUpperCase();
            String dist  = am.group(2) != null ? "true" : "false";
            String col   = am.group(3).trim().replace("`", "").replace("\"", "");
            String alias = am.group(4) != null ? am.group(4)
                         : func.toLowerCase() + "_" + col.replace("*", "all").replaceAll("[^\\w]", "_");
            aggs.add(new String[]{func, col, alias, dist});
        }
        if (aggs.isEmpty()) {
            return new QueryResult(Collections.emptyList(), Collections.emptyList(),
                System.currentTimeMillis() - startTime, "No aggregate expressions could be parsed from: " + selectPart);
        }

        // Parse optional GROUP BY columns
        List<String> groupCols = new ArrayList<>();
        Matcher gm = Pattern.compile(
            "(?i)\\bGROUP\\s+BY\\s+([^;]+?)(?:\\s+HAVING\\b|\\s+ORDER\\b|\\s+LIMIT\\b|\\s*;|\\s*$)")
            .matcher(sql);
        if (gm.find()) {
            for (String c : gm.group(1).split(",")) {
                String col = c.trim().replace("`", "").replace("\"", "");
                if (!col.isEmpty()) groupCols.add(col);
            }
        }

        // Group input rows (single group when no GROUP BY)
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        if (groupCols.isEmpty()) {
            groups.put("", inputRows);
        } else {
            for (Map<String, Object> row : inputRows) {
                String key = groupCols.stream()
                    .map(c -> String.valueOf(row.getOrDefault(c, "")))
                    .collect(Collectors.joining("\u0001"));
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            }
        }

        // Compute aggregates per group
        List<Map<String, Object>> resultRows = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : groups.entrySet()) {
            String groupKey = entry.getKey();
            List<Map<String, Object>> groupRows = entry.getValue();
            Map<String, Object> result = new LinkedHashMap<>();
            if (!groupCols.isEmpty()) {
                String[] keyParts = groupKey.split("\u0001", -1);
                for (int i = 0; i < groupCols.size() && i < keyParts.length; i++) {
                    result.put(groupCols.get(i), keyParts[i]);
                }
            }
            for (String[] agg : aggs) {
                result.put(agg[2], evalAggregate(agg[0], agg[1], "true".equals(agg[3]), groupRows));
            }
            resultRows.add(result);
        }

        List<String> columns = resultRows.isEmpty() ? Collections.emptyList()
                             : new ArrayList<>(resultRows.get(0).keySet());
        log.info("[KafkaDirect/Agg] inputRows={} groups={} cols={}",
                 inputRows.size(), resultRows.size(), columns);
        return new QueryResult(columns, resultRows, System.currentTimeMillis() - startTime, null);
    }

    /**
     * Emulates Flink TUMBLE (and HOP/SESSION as TUMBLE fallback) window aggregations over
     * Kafka messages fetched directly, without using the Flink planner.
     *
     * Supported syntax:
     *   SELECT window_start, window_end, AGG(...) AS alias
     *   FROM TABLE(TUMBLE(TABLE <topic>, DESCRIPTOR(<time_col>), INTERVAL '<n>' MINUTE|HOUR|SECOND|DAY))
     *   [WHERE ...] GROUP BY window_start, window_end
     *
     * Time column resolution order:
     *   1. Parsed from the message field named <time_col> (ISO-8601 string or epoch millis/seconds)
     *   2. Kafka record timestamp (fallback)
     */
    private QueryResult kafkaWindowSelect(String sql, String readMode, int limit, long startTime) {
        // Parse TUMBLE parameters: TABLE(<tableName>, DESCRIPTOR(<timeCol>), INTERVAL '<n>' <unit>)
        Matcher tumble = Pattern.compile(
            "(?i)TUMBLE\\s*\\(\\s*TABLE\\s+(\\w[\\w.]*)\\s*,\\s*DESCRIPTOR\\s*\\(\\s*(\\w+)\\s*\\)\\s*," +
            "\\s*INTERVAL\\s+'(\\d+)'\\s+(MINUTE|HOUR|SECOND|DAY)S?\\s*\\)")
            .matcher(sql);
        if (!tumble.find()) {
            return new QueryResult(Collections.emptyList(), Collections.emptyList(),
                System.currentTimeMillis() - startTime,
                "Cannot parse TUMBLE syntax. Expected: TABLE(TUMBLE(TABLE <name>, DESCRIPTOR(<time_col>), INTERVAL '<n>' MINUTE))");
        }
        String tableName   = tumble.group(1);
        String timeCol     = tumble.group(2);
        long   intervalMs  = switch (tumble.group(4).toUpperCase()) {
            case "SECOND" -> Long.parseLong(tumble.group(3)) * 1_000L;
            case "MINUTE" -> Long.parseLong(tumble.group(3)) * 60_000L;
            case "HOUR"   -> Long.parseLong(tumble.group(3)) * 3_600_000L;
            case "DAY"    -> Long.parseLong(tumble.group(3)) * 86_400_000L;
            default       -> Long.parseLong(tumble.group(3)) * 60_000L;
        };

        // Resolve Kafka topic
        String flinkTableName = DdlGeneratorService.toTableName(tableName);
        List<String> topics;
        try {
            topics = kafkaAdminService.listTopics();
        } catch (Exception e) {
            return new QueryResult(Collections.emptyList(), Collections.emptyList(),
                System.currentTimeMillis() - startTime, "Cannot reach Kafka broker: " + e.getMessage());
        }
        String topic = topics.stream()
            .filter(t -> DdlGeneratorService.toTableName(t).equals(flinkTableName))
            .findFirst().orElse(null);
        if (topic == null) {
            return new QueryResult(Collections.emptyList(), Collections.emptyList(),
                System.currentTimeMillis() - startTime,
                "Table '" + tableName + "' not found. No matching Kafka topic exists.");
        }

        // Fetch all messages (windows aggregate over the full dataset)
        List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> records =
            "earliest-offset".equals(readMode)
                ? kafkaAdminService.getEarliestRecords(topic, 100_000)
                : kafkaAdminService.getRecentRecords(topic, 100_000);

        // Parse aggregate expressions from SELECT
        Matcher selMatcher = Pattern.compile("(?i)^\\s*SELECT\\s+(.+?)\\s+FROM\\b", Pattern.DOTALL).matcher(sql);
        if (!selMatcher.find()) {
            return new QueryResult(Collections.emptyList(), Collections.emptyList(),
                System.currentTimeMillis() - startTime, "Cannot parse SELECT clause");
        }
        List<String[]> aggs = new ArrayList<>(); // [func, col, alias, distinct]
        Matcher am = Pattern.compile(
            "(?i)(COUNT|SUM|AVG|MAX|MIN)\\s*\\(\\s*(DISTINCT\\s+)?([^)]+?)\\s*\\)(?:\\s+AS\\s+[`\"]?([\\w]+)[`\"]?)?")
            .matcher(selMatcher.group(1));
        while (am.find()) {
            String func  = am.group(1).toUpperCase();
            String dist  = am.group(2) != null ? "true" : "false";
            String col   = am.group(3).trim().replace("`", "").replace("\"", "");
            String alias = am.group(4) != null ? am.group(4)
                         : func.toLowerCase() + "_" + col.replace("*", "all");
            aggs.add(new String[]{func, col, alias, dist});
        }

        Map<String, String> whereConds = extractSimpleWhere(sql);

        // Bucket messages by tumbling window
        Map<Long, List<Map<String, Object>>> windows = new LinkedHashMap<>();
        for (var record : records) {
            String value = record.value();
            if (value == null || value.isBlank()) continue;
            Map<String, Object> row = parseMessageToRow(value);
            if (row.isEmpty()) continue;
            if (!matchesWhereConditions(row, whereConds)) continue;

            // Resolve timestamp: message field → epoch detection → Kafka record ts
            long tsMillis;
            Object tsVal = row.get(timeCol);
            if (tsVal instanceof Number n) {
                tsMillis = n.longValue();
                if (tsMillis < 10_000_000_000L) tsMillis *= 1000; // seconds → millis
            } else if (tsVal instanceof String s) {
                try { tsMillis = java.time.Instant.parse(s).toEpochMilli(); }
                catch (Exception e) { tsMillis = record.timestamp(); }
            } else {
                tsMillis = record.timestamp(); // fallback: Kafka record timestamp
            }

            long bucket = (tsMillis / intervalMs) * intervalMs;
            windows.computeIfAbsent(bucket, k -> new ArrayList<>()).add(row);
        }

        // Build result rows — one per window bucket
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneOffset.UTC);

        List<Map<String, Object>> resultRows = new ArrayList<>();
        for (Map.Entry<Long, List<Map<String, Object>>> entry : windows.entrySet()) {
            long bucketStart = entry.getKey();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("window_start", fmt.format(java.time.Instant.ofEpochMilli(bucketStart)));
            row.put("window_end",   fmt.format(java.time.Instant.ofEpochMilli(bucketStart + intervalMs)));
            for (String[] agg : aggs) {
                row.put(agg[2], evalAggregate(agg[0], agg[1], "true".equals(agg[3]), entry.getValue()));
            }
            resultRows.add(row);
        }
        resultRows.sort(Comparator.comparing(r -> (String) r.get("window_start")));
        if (resultRows.size() > limit) resultRows = resultRows.subList(0, limit);

        List<String> columns = resultRows.isEmpty()
            ? List.of("window_start", "window_end")
            : new ArrayList<>(resultRows.get(0).keySet());
        log.info("[KafkaDirect/Window] topic='{}' timeCol='{}' intervalMs={} windows={} rows={}",
                 topic, timeCol, intervalMs, windows.size(), resultRows.size());
        return new QueryResult(columns, resultRows, System.currentTimeMillis() - startTime, null);
    }

    /** Evaluates a single aggregate function over a list of rows. */
    private Object evalAggregate(String func, String col, boolean distinct, List<Map<String, Object>> rows) {
        if ("COUNT".equals(func)) {
            if ("*".equals(col)) return (double) rows.size();
            if (distinct) {
                return (double) rows.stream()
                    .map(r -> r.get(col)).filter(v -> v != null)
                    .map(Object::toString)
                    .collect(Collectors.toSet()).size();
            }
            return (double) rows.stream().filter(r -> r.get(col) != null).count();
        }
        List<Double> nums = rows.stream()
            .map(r -> r.get(col))
            .filter(v -> v instanceof Number)
            .map(v -> ((Number) v).doubleValue())
            .collect(Collectors.toList());
        if (nums.isEmpty()) return null;
        return switch (func) {
            case "SUM" -> nums.stream().mapToDouble(d -> d).sum();
            case "AVG" -> nums.stream().mapToDouble(d -> d).average().orElse(0.0);
            case "MAX" -> nums.stream().mapToDouble(d -> d).max().orElse(0.0);
            case "MIN" -> nums.stream().mapToDouble(d -> d).min().orElse(0.0);
            default    -> null;
        };
    }

    private List<String> extractSelectedColumns(String sql) {
        Pattern p = Pattern.compile("(?i)^\\s*SELECT\\s+(.+?)\\s+FROM\\b", Pattern.DOTALL);
        Matcher m = p.matcher(sql.trim());
        if (!m.find()) return Collections.emptyList();
        String cols = m.group(1).trim();
        if (cols.equals("*")) return Collections.emptyList();
        return Arrays.stream(cols.split(","))
            .map(c -> c.replace("`", "").trim())
            .filter(c -> !c.isEmpty())
            .collect(Collectors.toList());
    }

    private Map<String, String> extractSimpleWhere(String sql) {
        Map<String, String> conditions = new LinkedHashMap<>();
        Pattern whereBlock = Pattern.compile("(?i)\\bWHERE\\s+(.+?)(?:\\bLIMIT\\b|;|$)", Pattern.DOTALL);
        Matcher wm = whereBlock.matcher(sql);
        if (!wm.find()) return conditions;
        String whereClause = wm.group(1).trim();
        Pattern condPattern = Pattern.compile("(?i)`?([\\w]+)`?\\s*=\\s*'([^']*)'");
        Matcher cm = condPattern.matcher(whereClause);
        while (cm.find()) {
            conditions.put(cm.group(1).toLowerCase(), cm.group(2));
        }
        return conditions;
    }

    private boolean matchesWhereConditions(Map<String, Object> row, Map<String, String> conditions) {
        for (Map.Entry<String, String> cond : conditions.entrySet()) {
            Object val = row.get(cond.getKey());
            if (val == null) return false;
            if (!val.toString().equalsIgnoreCase(cond.getValue())) return false;
        }
        return true;
    }

    public void cancelQuery(String queryId) {
        JobInfo info = activeJobs.remove(queryId);
        if (info != null) {
            info.client().cancel();
        }
    }

    public Map<String, String> getActiveJobs() {
        Map<String, String> jobs = new HashMap<>();
        activeJobs.forEach((id, info) -> {
            try {
                jobs.put(id, info.client().getJobStatus().get(100, TimeUnit.MILLISECONDS).isGloballyTerminalState() ? "TERMINATED" : "RUNNING");
            } catch (Exception e) {
                jobs.put(id, "RUNNING");
            }
        });
        return jobs;
    }

    public Map<String, JobInfo> getActiveJobsDetails() {
        return activeJobs;
    }

    @PreDestroy
    public void shutdown() {
        queryExecutor.shutdown();
        try {
            if (!queryExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                queryExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            queryExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Removes a trailing LIMIT N clause from a SELECT statement.
     * Flink 2.x / Calcite crashes with NPE("metadataHandlerProvider") when LIMIT is present
     * on a streaming Kafka source. Row count is capped by the Java iterator loop instead.
     */
    private String stripLimitClause(String sql) {
        // Match LIMIT at the end of the statement (with optional semicolon), case-insensitive
        return sql.replaceAll("(?i)\\bLIMIT\\s+\\d+\\s*;?\\s*$", "").trim();
    }

    /**
     * Converts Flink internal types that are not JSON-serializable to plain Java types.
     * Without this, objects like GenericRowData or metadata handlers appear as their
     * class toString() (e.g. "metadataHandlerProvider") in the JSON response.
     */
    private Object toSerializable(Object value) {
        if (value == null) return null;
        // Already plain Java types — return as-is
        if (value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        // Flink Row → recurse into fields
        if (value instanceof Row row) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (int i = 0; i < row.getArity(); i++) {
                map.put("f" + i, toSerializable(row.getField(i)));
            }
            log.debug("[FlinkSQL] toSerializable: Row with arity={} converted to map", row.getArity());
            return map;
        }
        // java.time types (LocalDate, LocalTime, LocalDateTime, Instant…) — toString() is ISO-8601
        if (value instanceof java.time.temporal.TemporalAccessor) return value.toString();
        // Byte arrays → Base64 string
        if (value instanceof byte[] bytes) return java.util.Base64.getEncoder().encodeToString(bytes);
        // Collections / arrays — recurse
        if (value instanceof List<?> list) return list.stream().map(this::toSerializable).collect(Collectors.toList());
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), toSerializable(v)));
            return out;
        }
        // Fallback: log the unexpected type so we can identify it
        log.warn("[FlinkSQL] toSerializable: unexpected type {} — using toString(). value='{}'",
                value.getClass().getName(), value);
        return value.toString();
    }

    private String injectLatestOffsetHint(String sql) {
        Pattern pattern = Pattern.compile("(?i)FROM\\s+([^\\s;\\(]+)");
        Matcher matcher = pattern.matcher(sql);
        if (matcher.find()) {
            int tableEndIdx = matcher.end();
            String hint = " /*+ OPTIONS('scan.startup.mode'='latest-offset') */";
            return sql.substring(0, tableEndIdx) + hint + sql.substring(tableEndIdx);
        }
        return sql;
    }

    private void cancelJobInternal(TableResult result) {
        if (result != null && result.getJobClient().isPresent()) {
            result.getJobClient().get().cancel();
        }
    }
}
