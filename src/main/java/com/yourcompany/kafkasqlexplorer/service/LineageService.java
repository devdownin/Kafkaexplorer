package com.yourcompany.kafkasqlexplorer.service;

import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.types.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Builds a dependency graph of the current SQL environment.
 * Nodes: Kafka topics, Flink tables, views and active INSERT jobs.
 * Edges: derived by parsing DDL / SQL with regex (Flink 2.x has no programmatic lineage API).
 */
@Service
public class LineageService {

    private static final Logger log = LoggerFactory.getLogger(LineageService.class);

    private final TableEnvironment   tableEnv;
    private final FlinkSqlService    flinkSqlService;
    private final KafkaAdminService  kafkaAdminService;

    private static final Pattern TOPIC_PATTERN  = Pattern.compile("'topic'\\s*=\\s*'([^']+)'");
    private static final Pattern FROM_PATTERN   = Pattern.compile("(?i)FROM\\s+([^\\s,;()\\n]+)", Pattern.MULTILINE);
    private static final Pattern JOIN_PATTERN   = Pattern.compile("(?i)JOIN\\s+([^\\s,;()\\n]+)", Pattern.MULTILINE);
    private static final Pattern INSERT_PATTERN = Pattern.compile("(?i)INSERT\\s+INTO\\s+([^\\s(]+)");

    /** Tokens captured by FROM/JOIN that are NOT table identifiers. */
    private static final Set<String> SQL_KEYWORDS = Set.of(
        "TABLE", "DUAL", "LATERAL", "UNNEST", "VALUES",
        "HAVING", "GROUP", "ORDER", "LIMIT", "UNION", "EXCEPT", "INTERSECT",
        "AS", "ON", "USING", "CROSS", "INNER", "OUTER", "LEFT", "RIGHT", "FULL",
        "NULL", "TRUE", "FALSE", "SELECT", "WHERE", "WITH"
    );

    public LineageService(TableEnvironment tableEnv, FlinkSqlService flinkSqlService,
                          KafkaAdminService kafkaAdminService) {
        this.tableEnv         = tableEnv;
        this.flinkSqlService  = flinkSqlService;
        this.kafkaAdminService = kafkaAdminService;
    }

    /** Convenience overload — keeps existing callers (and tests) working. */
    public Map<String, Object> getLineage() {
        return getLineage(false);
    }

    public Map<String, Object> getLineage(boolean connectedOnly) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, String>> edges = new ArrayList<>();
        Set<String> processedTables = new HashSet<>();

        // 1. Active INSERT INTO jobs
        flinkSqlService.getActiveJobsDetails().forEach((id, info) -> {
            String sql = info.sql();
            if (sql.toUpperCase().contains("INSERT INTO")) {
                String target = extractInsertTarget(sql);
                Set<String> sources = extractSources(sql);
                String queryNodeId = "query_" + id.substring(0, 8);
                nodes.add(mkNode(queryNodeId, "INSERT", "query"));
                sources.forEach(src -> edges.add(mkEdge(src, queryNodeId, "reads")));
                if (target != null) edges.add(mkEdge(queryNodeId, target, "writes"));
            }
        });

        // 2. Flink tables — find backing Kafka topic via DDL 'topic' property
        for (String tableName : tableEnv.listTables()) {
            nodes.add(mkNode(tableName, tableName, "table"));
            processedTables.add(tableName);
            String ddl = getDdl(tableName, "TABLE");
            if (ddl != null) {
                Matcher m = TOPIC_PATTERN.matcher(ddl);
                if (m.find()) {
                    String topicName = m.group(1);
                    String topicId   = "topic_" + topicName;
                    nodes.add(mkTopicNode(topicId, topicName, 0L));
                    edges.add(mkEdge(topicId, tableName, "source"));
                }
            }
        }

        // 3. Flink views
        for (String viewName : tableEnv.listViews()) {
            if (!processedTables.contains(viewName)) {
                nodes.add(mkNode(viewName, viewName, "view"));
                processedTables.add(viewName);
            }
            String ddl = getDdl(viewName, "VIEW");
            if (ddl != null) {
                extractSources(ddl).forEach(src -> edges.add(mkEdge(src, viewName, "depends")));
            }
        }

        // 4. All Kafka topics — enrich existing topic nodes with message counts,
        //    then add orphan topics (not yet wired to any Flink table).
        try {
            List<String> kafkaTopics = kafkaAdminService.listTopics();
            Map<String, Long> sizes  = kafkaTopics.isEmpty()
                ? Collections.emptyMap()
                : kafkaAdminService.getTopicsSize(kafkaTopics);

            // Patch messageCount onto already-present topic nodes
            for (int i = 0; i < nodes.size(); i++) {
                Map<String, Object> n = nodes.get(i);
                if ("topic".equals(n.get("type"))) {
                    String label = (String) n.get("label");
                    Map<String, Object> patched = new LinkedHashMap<>(n);
                    patched.put("messageCount", sizes.getOrDefault(label, 0L));
                    nodes.set(i, patched);
                }
            }

            // Add remaining Kafka topics not yet in the graph
            Set<String> existingTopicLabels = nodes.stream()
                .filter(n -> "topic".equals(n.get("type")))
                .map(n -> (String) n.get("label"))
                .collect(Collectors.toSet());

            for (String topic : kafkaTopics) {
                if (!existingTopicLabels.contains(topic)) {
                    nodes.add(mkTopicNode("topic_" + topic, topic, sizes.getOrDefault(topic, 0L)));
                }
            }
        } catch (Exception e) {
            log.debug("Could not enrich lineage with Kafka topics: {}", e.getMessage());
        }

        // 5. Connected-only filter: drop nodes that appear in no edge
        if (connectedOnly) {
            Set<String> wired = new HashSet<>();
            edges.forEach(e -> { wired.add(e.get("from")); wired.add(e.get("to")); });
            nodes.removeIf(n -> !wired.contains(n.get("id")));
        }

        return Map.of("nodes", nodes, "edges", edges);
    }

    public String getDdlForNode(String name) {
        String ddl = getDdl(name, "TABLE");
        if (ddl != null) return ddl;
        ddl = getDdl(name, "VIEW");
        if (ddl != null) return ddl;
        return "-- DDL not available for: " + name;
    }

    // ── Node / edge builders ──────────────────────────────────────────────────

    private static Map<String, Object> mkNode(String id, String label, String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("label", label); m.put("type", type);
        return m;
    }

    private static Map<String, Object> mkTopicNode(String id, String label, long messageCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("label", label); m.put("type", "topic");
        m.put("messageCount", messageCount);
        return m;
    }

    private static Map<String, String> mkEdge(String from, String to, String label) {
        return Map.of("from", from, "to", to, "label", label);
    }

    // ── SQL parsing helpers ──────────────────────────────────────────────────

    private String extractInsertTarget(String sql) {
        Matcher m = INSERT_PATTERN.matcher(sql);
        return m.find() ? m.group(1).trim() : null;
    }

    private Set<String> extractSources(String sql) {
        Set<String> sources = new HashSet<>();
        Matcher fm = FROM_PATTERN.matcher(sql);
        while (fm.find()) addIfValidIdentifier(sources, fm.group(1));
        Matcher jm = JOIN_PATTERN.matcher(sql);
        while (jm.find()) addIfValidIdentifier(sources, jm.group(1));
        return sources;
    }

    /**
     * Accepts a token only when it looks like a real SQL identifier:
     * no parentheses, not a reserved keyword, starts with letter or underscore.
     */
    private static void addIfValidIdentifier(Set<String> set, String token) {
        String clean = token.replace("`", "").trim();
        if (clean.isEmpty() || clean.contains("(") || clean.contains(")")) return;
        if (SQL_KEYWORDS.contains(clean.toUpperCase(Locale.ROOT))) return;
        if (!clean.matches("[a-zA-Z_][a-zA-Z0-9_.]*")) return;
        set.add(clean);
    }

    private String getDdl(String name, String type) {
        try {
            TableResult result = tableEnv.executeSql("SHOW CREATE " + type + " " + name);
            try (org.apache.flink.util.CloseableIterator<Row> it = result.collect()) {
                if (it.hasNext()) return it.next().getField(0).toString();
            }
        } catch (Exception e) {
            log.debug("Failed to get DDL for {} {}: {}", type, name, e.getMessage());
        }
        return null;
    }
}
