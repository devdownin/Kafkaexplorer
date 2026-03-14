package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.domain.QueryRequest;
import com.yourcompany.kafkasqlexplorer.domain.QueryResult;
import com.yourcompany.kafkasqlexplorer.config.ExplorerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.types.Row;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;

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
    private final StreamTableEnvironment tableEnv;
    private final ExplorerConfig explorerConfig;
    private final SqlQueryValidator sqlQueryValidator;

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

    public FlinkSqlService(StreamTableEnvironment tableEnv, ExplorerConfig explorerConfig, SqlQueryValidator sqlQueryValidator) {
        this.tableEnv = tableEnv;
        this.explorerConfig = explorerConfig;
        this.sqlQueryValidator = sqlQueryValidator;
        // Register our custom XML extraction function globally in the Flink environment.
        // This allows users to use 'XmlExtract(raw_value, '/path/to/tag')' in their queries.
        this.tableEnv.createTemporarySystemFunction("XmlExtract", XmlExtractUDF.class);
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
    public QueryResult executeSql(QueryRequest request) {
        long startTime = System.currentTimeMillis();
        String queryId = UUID.randomUUID().toString();
        String originalSql = request.sql().trim();
        String sql = originalSql.toUpperCase();

        // Security: Prevent execution of dangerous or unsupported DDL/DML.
        // In a real-world scenario, you might want more granular control or a proper SQL parser.
        if (!sql.startsWith("SELECT") && !sql.startsWith("EXPLAIN") && !sql.startsWith("CREATE TABLE")) {
            return new QueryResult(Collections.emptyList(), Collections.emptyList(), 0, "Only SELECT, EXPLAIN and CREATE TABLE statements are allowed.");
        }

        try {
            sqlQueryValidator.validate(request.sql());
        } catch (IllegalArgumentException e) {
            return new QueryResult(Collections.emptyList(), Collections.emptyList(), 0, "SQL Validation Error: " + e.getMessage());
        }

        TableResult result = null;
        try {
            String sqlToExecute = request.sql();
            String readMode = request.readMode();

            // Magic Read Mode: If the user selected 'latest-offset' and it's a simple SELECT,
            // we try to inject a Flink SQL hint to override the startup mode.
            if ("latest-offset".equals(readMode) && sql.startsWith("SELECT")) {
                if (!sql.contains("OPTIONS") && !sql.contains("/*+")) {
                    // Find the table name - this is a naive regex/string approach
                    // In a production app, use a proper SQL parser.
                    sqlToExecute = injectLatestOffsetHint(request.sql());
                    log.info("Magic Read Mode: Injected latest-offset hint. New SQL: {}", sqlToExecute);
                }
            }

            result = tableEnv.executeSql(sqlToExecute);
            final String finalSql = sqlToExecute;
            result.getJobClient().ifPresent(client -> activeJobs.put(queryId, new JobInfo(finalSql, client)));

            // result.collect() starts the Flink job and provides an iterator to fetch results.
            try (org.apache.flink.util.CloseableIterator<Row> it = result.collect()) {
            List<String> columns = result.getResolvedSchema().getColumnNames();
            List<Map<String, Object>> rows = new ArrayList<>();

            int limit = request.maxRows() != null ? request.maxRows() : explorerConfig.getDefaultMaxRows();
            long timeout = request.timeout() != null ? request.timeout() : explorerConfig.getDefaultQueryTimeoutMs();

            // We use a CompletableFuture to implement the timeout logic.
            // Streaming queries might not produce data immediately, so we don't want to block indefinitely.
            CompletableFuture<List<Map<String, Object>>> future = CompletableFuture.supplyAsync(() -> {
                List<Map<String, Object>> resultRows = new ArrayList<>();
                int count = 0;
                while (it.hasNext() && count < limit) {
                    Row row = it.next();
                    Map<String, Object> mapRow = new HashMap<>();
                    for (int i = 0; i < columns.size(); i++) {
                        mapRow.put(columns.get(i), row.getField(i));
                    }
                    resultRows.add(mapRow);
                    count++;
                }
                return resultRows;
            }, queryExecutor);

            try {
                rows = future.get(timeout, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                log.warn("Query timed out after {}ms: {}", timeout, request.sql());
                future.cancel(true);
                throw te;
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
            cancelJobInternal(result);
            long duration = System.currentTimeMillis() - startTime;
            return new QueryResult(Collections.emptyList(), Collections.emptyList(), duration, e.getMessage());
        } finally {
            activeJobs.remove(queryId);
        }
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

    private String injectLatestOffsetHint(String sql) {
        // Use regex to find the table name after FROM clause and inject the hint
        // Example: SELECT * FROM my_table -> SELECT * FROM my_table /*+ OPTIONS('scan.startup.mode'='latest-offset') */
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
