package com.yourcompany.kafkasqlexplorer.service;

import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.types.Row;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class LineageService {

    private final StreamTableEnvironment tableEnv;
    private final FlinkSqlService flinkSqlService;
    private static final Pattern TOPIC_PATTERN = Pattern.compile("'topic'\\s*=\\s*'([^']+)'");
    private static final Pattern FROM_PATTERN = Pattern.compile("(?i)FROM\\s+([^\\s,;()\\n]+)", Pattern.MULTILINE);
    private static final Pattern JOIN_PATTERN = Pattern.compile("(?i)JOIN\\s+([^\\s,;()\\n]+)", Pattern.MULTILINE);

    public LineageService(StreamTableEnvironment tableEnv, FlinkSqlService flinkSqlService) {
        this.tableEnv = tableEnv;
        this.flinkSqlService = flinkSqlService;
    }

    public Map<String, Object> getLineage() {
        List<Map<String, String>> nodes = new ArrayList<>();
        List<Map<String, String>> edges = new ArrayList<>();

        Set<String> processedTables = new HashSet<>();

        // 1. Process Active Jobs (INSERT INTO ...)
        flinkSqlService.getActiveJobsDetails().forEach((id, info) -> {
            String sql = info.sql();
            if (sql.toUpperCase().contains("INSERT INTO")) {
                String targetTable = extractInsertTarget(sql);
                Set<String> sourceTables = extractSources(sql);

                String queryNodeId = "query_" + id.substring(0, 8);
                nodes.add(Map.of("id", queryNodeId, "label", "INSERT", "type", "query"));

                for (String source : sourceTables) {
                    edges.add(Map.of("from", source, "to", queryNodeId, "label", "reads"));
                }
                if (targetTable != null) {
                    edges.add(Map.of("from", queryNodeId, "to", targetTable, "label", "writes"));
                }
            }
        });

        // 2. Process Tables
        for (String tableName : tableEnv.listTables()) {
            nodes.add(Map.of("id", tableName, "label", tableName, "type", "table"));
            processedTables.add(tableName);

            String ddl = getDdl(tableName, "TABLE");
            if (ddl != null) {
                Matcher matcher = TOPIC_PATTERN.matcher(ddl);
                if (matcher.find()) {
                    String topicName = matcher.group(1);
                    String topicId = "topic_" + topicName;
                    nodes.add(Map.of("id", topicId, "label", topicName, "type", "topic"));
                    edges.add(Map.of("from", topicId, "to", tableName, "label", "source"));
                }
            }
        }

        // 3. Process Views
        for (String viewName : tableEnv.listViews()) {
            if (!processedTables.contains(viewName)) {
                nodes.add(Map.of("id", viewName, "label", viewName, "type", "view"));
                processedTables.add(viewName);
            }

            String ddl = getDdl(viewName, "VIEW");
            if (ddl != null) {
                Set<String> sources = extractSources(ddl);
                for (String source : sources) {
                    edges.add(Map.of("from", source, "to", viewName, "label", "depends"));
                }
            }
        }

        return Map.of("nodes", nodes, "edges", edges);
    }

    private String getDdl(String name, String type) {
        try {
            TableResult result = tableEnv.executeSql("SHOW CREATE " + type + " " + name);
            try (org.apache.flink.util.CloseableIterator<Row> it = result.collect()) {
                if (it.hasNext()) {
                    return it.next().getField(0).toString();
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private String extractInsertTarget(String sql) {
        Pattern pattern = Pattern.compile("(?i)INSERT\\s+INTO\\s+([^\\s(]+)");
        Matcher matcher = pattern.matcher(sql);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private Set<String> extractSources(String ddl) {
        Set<String> sources = new HashSet<>();
        Matcher fromMatcher = FROM_PATTERN.matcher(ddl);
        while (fromMatcher.find()) {
            sources.add(fromMatcher.group(1).trim());
        }
        Matcher joinMatcher = JOIN_PATTERN.matcher(ddl);
        while (joinMatcher.find()) {
            sources.add(joinMatcher.group(1).trim());
        }
        return sources;
    }
}
