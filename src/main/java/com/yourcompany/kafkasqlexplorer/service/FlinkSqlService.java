package com.yourcompany.kafkasqlexplorer.service;

import com.yourcompany.kafkasqlexplorer.domain.QueryRequest;
import com.yourcompany.kafkasqlexplorer.domain.QueryResult;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.types.Row;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class FlinkSqlService {

    private final StreamTableEnvironment tableEnv;

    public FlinkSqlService(StreamTableEnvironment tableEnv) {
        this.tableEnv = tableEnv;
        this.tableEnv.createTemporarySystemFunction("XmlExtract", XmlExtractUDF.class);
    }

    public QueryResult executeSql(QueryRequest request) {
        long startTime = System.currentTimeMillis();
        String sql = request.sql().trim().toUpperCase();
        if (!sql.startsWith("SELECT") && !sql.startsWith("EXPLAIN") && !sql.startsWith("CREATE TABLE")) {
            return new QueryResult(Collections.emptyList(), Collections.emptyList(), 0, "Only SELECT, EXPLAIN and CREATE TABLE statements are allowed.");
        }

        TableResult result = null;
        try (org.apache.flink.util.CloseableIterator<Row> it = (result = tableEnv.executeSql(request.sql())).collect()) {
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

        } catch (Exception e) {
            if (result != null && result.getJobClient().isPresent()) {
                result.getJobClient().get().cancel();
            }
            long duration = System.currentTimeMillis() - startTime;
            return new QueryResult(Collections.emptyList(), Collections.emptyList(), duration, e.getMessage());
        }
    }
}
