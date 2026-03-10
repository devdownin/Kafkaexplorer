package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.domain.QueryRequest;
import com.yourcompany.kafkasqlexplorer.domain.QueryResult;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.types.Row;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class FlinkSqlService {

    private final StreamTableEnvironment tableEnv;
    private final Map<String, JobClient> activeJobs = new ConcurrentHashMap<>();

    public FlinkSqlService(StreamTableEnvironment tableEnv) {
        this.tableEnv = tableEnv;
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
            // Table not found
        }
        return schema;
    }

    public QueryResult executeSql(QueryRequest request) {
        long startTime = System.currentTimeMillis();
        String queryId = UUID.randomUUID().toString();
        String sql = request.sql().trim().toUpperCase();
        if (!sql.startsWith("SELECT") && !sql.startsWith("EXPLAIN") && !sql.startsWith("CREATE TABLE")) {
            return new QueryResult(Collections.emptyList(), Collections.emptyList(), 0, "Only SELECT, EXPLAIN and CREATE TABLE statements are allowed.");
        }

        TableResult result = null;
        try {
            result = tableEnv.executeSql(request.sql());
            result.getJobClient().ifPresent(client -> activeJobs.put(queryId, client));

            try (org.apache.flink.util.CloseableIterator<Row> it = result.collect()) {
            List<String> columns = result.getResolvedSchema().getColumnNames();
            List<Map<String, Object>> rows = new ArrayList<>();

            int limit = request.maxRows() != null ? request.maxRows() : 50;
            long timeout = request.timeout() != null ? request.timeout() : 10000;

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
            });

                rows = future.get(timeout, TimeUnit.MILLISECONDS);

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
        JobClient client = activeJobs.remove(queryId);
        if (client != null) {
            client.cancel();
        }
    }

    public Map<String, String> getActiveJobs() {
        Map<String, String> jobs = new HashMap<>();
        activeJobs.forEach((id, client) -> {
            try {
                jobs.put(id, client.getJobStatus().get(100, TimeUnit.MILLISECONDS).isGloballyTerminalState() ? "TERMINATED" : "RUNNING");
            } catch (Exception e) {
                jobs.put(id, "RUNNING");
            }
        });
        return jobs;
    }

    private void cancelJobInternal(TableResult result) {
        if (result != null && result.getJobClient().isPresent()) {
            result.getJobClient().get().cancel();
        }
    }
}
