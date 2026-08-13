// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.internal.TableEnvironmentImpl;
import org.apache.flink.table.catalog.CatalogView;
import org.apache.flink.table.operations.Operation;
import org.apache.flink.table.operations.QueryOperation;
import org.apache.flink.table.operations.SinkModifyOperation;
import org.apache.flink.table.operations.SourceQueryOperation;
import org.apache.flink.table.operations.ddl.CreateViewOperation;
import org.apache.flink.types.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a dependency graph of the current SQL environment.
 * Nodes: Kafka topics, Flink tables, views and active INSERT jobs.
 * Edges: derived by parsing DDL / SQL with regex (Flink 2.x has no programmatic lineage API).
 */
@Service
public class LineageService {

    private static final Logger log = LoggerFactory.getLogger(LineageService.class);

    private final TableEnvironment   tableEnv;
    private final FlinkRuntimeCoordinator runtimeCoordinator;
    private final FlinkSqlService    flinkSqlService;
    private final KafkaAdminService  kafkaAdminService;

    private static final Pattern TOPIC_PATTERN  = Pattern.compile("'topic'\\s*=\\s*'([^']+)'");
    private static final Pattern FROM_PATTERN   = Pattern.compile("(?i)FROM\\s+([^\\s,;()\\n]+)", Pattern.MULTILINE);
    private static final Pattern JOIN_PATTERN   = Pattern.compile("(?i)JOIN\\s+([^\\s,;()\\n]+)", Pattern.MULTILINE);
    private static final Pattern INSERT_PATTERN = Pattern.compile("(?i)INSERT\\s+INTO\\s+([^\\s(]+)");

    /** Shape a Flink table/view name must have before it can be spliced into SHOW CREATE. */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_$]*");

    private static final Pattern LINE_COMMENT  = Pattern.compile("--[^\\n]*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");

    /** Tokens captured by FROM/JOIN that are NOT table identifiers. */
    private static final Set<String> SQL_KEYWORDS = Set.of(
        "TABLE", "DUAL", "LATERAL", "UNNEST", "VALUES",
        "HAVING", "GROUP", "ORDER", "LIMIT", "UNION", "EXCEPT", "INTERSECT",
        "AS", "ON", "USING", "CROSS", "INNER", "OUTER", "LEFT", "RIGHT", "FULL",
        "NULL", "TRUE", "FALSE", "SELECT", "WHERE", "WITH"
    );

    @Autowired
    public LineageService(TableEnvironment tableEnv, FlinkRuntimeCoordinator runtimeCoordinator, FlinkSqlService flinkSqlService,
                          KafkaAdminService kafkaAdminService) {
        this.tableEnv         = tableEnv;
        this.runtimeCoordinator = runtimeCoordinator;
        this.flinkSqlService  = flinkSqlService;
        this.kafkaAdminService = kafkaAdminService;
    }

    /** Convenience overload — keeps existing callers (and tests) working. */
    public Map<String, Object> getLineage() {
        return getLineage(false);
    }

    /**
     * Builds the graph. Cached (see {@code explorer.cache-expire-seconds}, 30s by default)
     * because a cold build runs one {@code SHOW CREATE} statement per table and per view,
     * each taking the Flink runtime read lock.
     *
     * <p>Only external calls go through the cache — the no-arg {@link #getLineage()} overload
     * invokes this method directly on {@code this}, which bypasses the Spring proxy.
     */
    @Cacheable(value = "lineage", key = "#connectedOnly")
    public Map<String, Object> getLineage(boolean connectedOnly) {
        // Keyed by node id so a topic backing several tables yields a single node.
        Map<String, Map<String, Object>> nodesById = new LinkedHashMap<>();
        List<Map<String, String>> edges = new ArrayList<>();

        // Statements whose dependencies had to be guessed by the regex scan rather than read
        // from the plan — reported, never swallowed: an edge that is missing for that reason is
        // indistinguishable, on the graph, from a dependency that does not exist.
        List<String> guessed = new ArrayList<>();

        // 1. Active INSERT INTO jobs
        flinkSqlService.getActiveJobsDetails().forEach((id, info) -> {
            String sql = info.sql();
            if (sql.toUpperCase(Locale.ROOT).contains("INSERT INTO")) {
                SqlDependencies dependencies = dependenciesOf(sql);
                if (!dependencies.parsed()) guessed.add("job " + id);
                String queryNodeId = "query_" + id;
                putNode(nodesById, mkNode(queryNodeId, "INSERT", "query"));
                dependencies.sources().forEach(src -> edges.add(mkEdge(src, queryNodeId, "reads")));
                if (dependencies.target() != null) {
                    edges.add(mkEdge(queryNodeId, dependencies.target(), "writes"));
                }
            }
        });

        // 2. Flink tables and views. listTables() returns BOTH, so the view names decide the
        //    node type — and spare us a SHOW CREATE TABLE that would fail on every view.
        Set<String> viewNames = new LinkedHashSet<>(
            Arrays.asList(runtimeCoordinator.runRead("lineage-list-views", () -> tableEnv.listViews())));

        for (String tableName : runtimeCoordinator.runRead("lineage-list-tables", () -> tableEnv.listTables())) {
            if (viewNames.contains(tableName)) continue;   // handled as a view below
            putNode(nodesById, mkNode(tableName, tableName, "table"));
            String ddl = getDdl(tableName, "TABLE");
            if (ddl != null) {
                Matcher m = TOPIC_PATTERN.matcher(ddl);
                if (m.find()) {
                    String topicName = m.group(1);
                    String topicId   = "topic_" + topicName;
                    putNode(nodesById, mkTopicNode(topicId, topicName, 0L));
                    edges.add(mkEdge(topicId, tableName, "source"));
                }
            }
        }

        // 3. Flink views
        for (String viewName : viewNames) {
            putNode(nodesById, mkNode(viewName, viewName, "view"));
            String ddl = getDdl(viewName, "VIEW");
            if (ddl != null) {
                SqlDependencies dependencies = dependenciesOf(ddl);
                if (!dependencies.parsed()) guessed.add("view " + viewName);
                dependencies.sources().forEach(src -> edges.add(mkEdge(src, viewName, "depends")));
            }
        }

        // 4. All Kafka topics — enrich existing topic nodes with message counts,
        //    then add orphan topics (not yet wired to any Flink table).
        try {
            List<String> kafkaTopics = kafkaAdminService.listTopics();
            Map<String, Long> sizes  = kafkaTopics.isEmpty()
                ? Collections.emptyMap()
                : kafkaAdminService.getTopicsSize(kafkaTopics);

            // Patch messageCount onto already-present topic nodes (in place — they are ours).
            nodesById.values().stream()
                .filter(n -> "topic".equals(n.get("type")))
                .forEach(n -> n.put("messageCount", sizes.getOrDefault((String) n.get("label"), 0L)));

            for (String topic : kafkaTopics) {
                putNode(nodesById, mkTopicNode("topic_" + topic, topic, sizes.getOrDefault(topic, 0L)));
            }
        } catch (Exception e) {
            log.debug("Could not enrich lineage with Kafka topics: {}", e.getMessage());
        }

        // 5. Drop edges whose endpoints are not real nodes. Regex source extraction also picks
        //    up CTE names and aliases; such edges are unrenderable and would otherwise inflate
        //    the edge count and keep unrelated nodes alive under connectedOnly.
        edges.removeIf(e -> !nodesById.containsKey(e.get("from")) || !nodesById.containsKey(e.get("to")));

        List<Map<String, Object>> nodes = new ArrayList<>(nodesById.values());

        // 6. Connected-only filter: drop nodes that appear in no edge
        if (connectedOnly) {
            Set<String> wired = new HashSet<>();
            edges.forEach(e -> { wired.add(e.get("from")); wired.add(e.get("to")); });
            nodes.removeIf(n -> !wired.contains(n.get("id")));
        }

        List<String> warnings = new ArrayList<>();
        if (!guessed.isEmpty()) {
            warnings.add(guessed.size() + " statement(s) could not be resolved by Flink's parser; "
                + "their dependencies were read off the SQL text instead and may be incomplete ("
                + String.join(", ", guessed.size() <= 8 ? guessed : guessed.subList(0, 8))
                + (guessed.size() > 8 ? " and " + (guessed.size() - 8) + " more" : "") + ").");
        }

        // The result is shared by every caller for the cache TTL — hand out read-only lists.
        return Map.of(
            "nodes", Collections.unmodifiableList(nodes),
            "edges", Collections.unmodifiableList(edges),
            "warnings", Collections.unmodifiableList(warnings));
    }

    /**
     * Returns the DDL of a table or view, masked of credentials.
     *
     * <p>{@code name} reaches us straight from the URL and is concatenated into a
     * {@code SHOW CREATE} statement, so it is checked against the objects actually registered
     * in the Flink environment — this endpoint does not go through {@link SqlQueryValidator}.
     */
    public String getDdlForNode(String name) {
        if (name == null || !IDENTIFIER_PATTERN.matcher(name).matches() || !isKnownObject(name)) {
            return "-- DDL not available for: " + name;
        }
        String ddl = getDdl(name, "TABLE");
        if (ddl == null) ddl = getDdl(name, "VIEW");
        if (ddl != null) {
            // SHOW CREATE TABLE echoes connector credentials (SASL/SSL) — never send them to the UI.
            return DdlGeneratorService.maskSensitiveProperties(ddl);
        }
        return "-- DDL not available for: " + name;
    }

    /** True when the name is a table or view registered in the current Flink environment. */
    private boolean isKnownObject(String name) {
        try {
            for (String t : runtimeCoordinator.runRead("lineage-list-tables", () -> tableEnv.listTables())) {
                if (t.equals(name)) return true;
            }
            for (String v : runtimeCoordinator.runRead("lineage-list-views", () -> tableEnv.listViews())) {
                if (v.equals(name)) return true;
            }
        } catch (Exception e) {
            log.debug("Could not list Flink objects while validating '{}': {}", name, e.getMessage());
        }
        return false;
    }

    /** Adds a node unless one with the same id is already present. */
    private static void putNode(Map<String, Map<String, Object>> nodesById, Map<String, Object> node) {
        nodesById.putIfAbsent((String) node.get("id"), node);
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

    // ── SQL parsing ──────────────────────────────────────────────────────────

    /**
     * What one statement reads and writes — and whether Flink's parser said so, or the regex
     * fallback guessed it. The distinction travels all the way to the UI: a graph built by
     * guessing is still useful, but it must not be presented as one built by reading the plan.
     */
    public record SqlDependencies(Set<String> sources, String target, boolean parsed) { }

    /**
     * Resolves a statement's dependencies, through Flink's own parser when it can.
     *
     * <p>The regex scan below is a lexical approximation, and it is wrong in ways that matter:
     * {@code FROM TABLE(TUMBLE(TABLE orders, …))} yields the keyword {@code TABLE} and loses
     * {@code orders} entirely — the same trap that once broke auto-registration in
     * {@link FlinkSqlService} — a quoted identifier carrying a hyphen is dropped by the
     * identifier filter, and only the first {@code INSERT INTO} of a multi-statement script is
     * ever seen. The parser resolves the real operation tree instead, so a source is a source
     * because the planner says it is.
     *
     * <p>It falls back rather than fails: a statement referencing a table that no longer exists
     * cannot be resolved, and a lineage graph missing one edge beats a lineage page that errors.
     */
    private SqlDependencies dependenciesOf(String sql) {
        SqlDependencies parsed = parseWithFlink(sql, true);
        return parsed != null ? parsed
            : new SqlDependencies(extractSources(sql), extractInsertTarget(sql), false);
    }

    /**
     * @param expandViews follow a {@code CREATE VIEW} down to the query it wraps — that is the
     *                    shape {@code SHOW CREATE VIEW} returns, and the sources are inside it.
     *                    Cleared on the recursive call, so a view can never send us round again.
     * @return null when the parser is unavailable (a plain {@link TableEnvironment}, as in the
     *         unit tests) or when it refuses the statement
     */
    private SqlDependencies parseWithFlink(String sql, boolean expandViews) {
        if (!(tableEnv instanceof TableEnvironmentImpl impl)) {
            return null;
        }
        List<Operation> operations;
        try {
            operations = runtimeCoordinator.runRead("lineage-parse", () -> impl.getParser().parse(sql));
        } catch (Exception e) {
            log.debug("Flink could not parse a statement for lineage, falling back to the regex scan: {}",
                e.getMessage());
            return null;
        }
        if (operations == null || operations.isEmpty()) {
            return null;
        }

        Set<String> sources = new LinkedHashSet<>();
        String target = null;
        for (Operation operation : operations) {
            if (operation instanceof SinkModifyOperation sink) {
                if (target == null) {
                    target = sink.getContextResolvedTable().getIdentifier().getObjectName();
                }
                collectSources(sink.getChild(), sources);
            } else if (operation instanceof QueryOperation query) {
                collectSources(query, sources);
            } else if (expandViews && operation instanceof CreateViewOperation view) {
                CatalogView catalogView = view.getCatalogView();
                String query = catalogView.getOriginalQuery() != null && !catalogView.getOriginalQuery().isBlank()
                    ? catalogView.getOriginalQuery()
                    : catalogView.getExpandedQuery();
                SqlDependencies inner = query == null ? null : parseWithFlink(query, false);
                if (inner == null) {
                    return null;
                }
                sources.addAll(inner.sources());
            }
        }
        return new SqlDependencies(sources, target, true);
    }

    /** Every table the planner resolved under this operation, however deeply nested. */
    private static void collectSources(QueryOperation operation, Set<String> into) {
        if (operation == null) return;
        if (operation instanceof SourceQueryOperation source) {
            into.add(source.getContextResolvedTable().getIdentifier().getObjectName());
        }
        for (QueryOperation child : operation.getChildren()) {
            collectSources(child, into);
        }
    }

    // ── Regex fallback ───────────────────────────────────────────────────────

    private String extractInsertTarget(String sql) {
        Matcher m = INSERT_PATTERN.matcher(sql);
        return m.find() ? m.group(1).trim() : null;
    }

    private Set<String> extractSources(String sql) {
        String scrubbed = scrubForIdentifierScan(sql);
        Set<String> sources = new HashSet<>();
        Matcher fm = FROM_PATTERN.matcher(scrubbed);
        while (fm.find()) addIfValidIdentifier(sources, fm.group(1));
        Matcher jm = JOIN_PATTERN.matcher(scrubbed);
        while (jm.find()) addIfValidIdentifier(sources, jm.group(1));
        return sources;
    }

    /**
     * Removes comments and blanks out string literals so a commented-out or quoted
     * {@code FROM x} cannot be mistaken for a real dependency. Literals are replaced by an
     * empty pair of quotes rather than deleted, to keep the surrounding tokens apart.
     *
     * <p>Only for the FROM/JOIN scan — never run this over DDL before matching the
     * {@code 'topic' = '...'} property, which lives inside string literals.
     */
    private static String scrubForIdentifierScan(String sql) {
        String out = BLOCK_COMMENT.matcher(sql).replaceAll(" ");
        out = LINE_COMMENT.matcher(out).replaceAll(" ");
        return STRING_LITERAL.matcher(out).replaceAll("''");
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
            return runtimeCoordinator.runRead(
                "lineage-show-create-" + type.toLowerCase(Locale.ROOT),
                () -> {
                    TableResult result = tableEnv.executeSql("SHOW CREATE " + type + " " + name);
                    org.apache.flink.util.CloseableIterator<Row> it = result.collect();
                    try {
                        if (it.hasNext()) return it.next().getField(0).toString();
                    } catch (Exception e) {
                        throw new IllegalStateException("Failed to read Flink DDL for " + type + " " + name, e);
                    } finally {
                        try {
                            it.close();
                        } catch (Exception e) {
                            throw new IllegalStateException("Failed to close Flink DDL iterator for " + type + " " + name, e);
                        }
                    }
                    return null;
                }
            );
        } catch (Exception e) {
            log.debug("Failed to get DDL for {} {}: {}", type, name, e.getMessage());
        }
        return null;
    }
}
