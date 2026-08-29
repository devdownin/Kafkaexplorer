// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.domain.FlinkManagedJobDetails;
import com.compagnonsdudev.kafkasqlexplorer.domain.FlinkJobSummary;
import com.compagnonsdudev.kafkasqlexplorer.domain.MessageFormat;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryResult;
import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.parser.SecureXml;
import com.compagnonsdudev.kafkasqlexplorer.util.LogSafe;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.JobStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.types.Row;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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
    private final FlinkRuntimeCoordinator runtimeCoordinator;
    private final ExplorerConfig explorerConfig;
    private final SqlQueryValidator sqlQueryValidator;
    private final KafkaAdminService kafkaAdminService;
    private final SchemaInferenceService schemaInferenceService;
    private final DdlGeneratorService ddlGeneratorService;
    private final FlinkJobStore flinkJobStore;
    private final FlinkTableStore flinkTableStore;

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
     * The jobs this process currently holds a live {@link JobClient} for.
     *
     * <p>It was called {@code activeJobs}, and that name collided with a different notion of
     * "active" one method away: {@link #getActiveJobs()} answers from the <em>store</em>, filtered
     * on {@link FlinkJobStore#isTerminal}, while this map is what the runtime is holding right now.
     * Two meanings under one word, on the two methods a caller has to choose between.
     *
     * <p><b>Two populations, two lifetimes, and knowing which is which is the point.</b> A job
     * submitted in Flink Job mode stays here for as long as it runs, which may be days — that is
     * what {@code POST /api/config}'s 409 guard, the lineage graph and the KPI suggestions are
     * asking about. A synchronous read is here only for the duration of its HTTP request, because
     * {@code POST /api/query/cancel/{queryId}} has to find it while the query runs; it is handed
     * back by {@link #releaseSyncJob} on the way out, and before that fix it was not, which is how
     * this map came to accumulate a {@code JobClient} per planner-answered metric refresh.
     *
     * <p>So: a job leaves here when it ends, when its read returns, or when the status sweep finds
     * it terminal. Nothing else should be put in it — an entry that no path removes is a leak with
     * a Flink job attached, and this map has already been that once.
     */
    private final Map<String, JobInfo> heldJobs = new ConcurrentHashMap<>();

    /**
     * The status of a job whose status could not be read.
     *
     * <p>Deliberately not {@code UNKNOWN}: {@link FlinkJobStore#isTerminal} counts that one as the
     * job being over — it is what a record recovered from the file carries, and what a job whose
     * MiniCluster has been shut down under it carries. A status poll that timed out is neither.
     * Reusing UNKNOWN for it meant a single slow answer ended a running job on paper.
     */
    static final String STATUS_UNAVAILABLE = "UNAVAILABLE";

    /**
     * Circuit breaker for the Flink SELECT path. If the planner keeps failing (e.g. the
     * historical FlinkRelMetadataQuery NPE is still present in the running Flink version),
     * we stop attempting it after {@link #FLINK_SELECT_FAILURE_THRESHOLD} failures so every
     * SELECT does not pay the cost of a planner attempt before falling back to the direct
     * Kafka reader. Reset on process restart.
     */
    private static final int FLINK_SELECT_FAILURE_THRESHOLD = 3;

    /**
     * Floor on the wait for the Flink runtime, whatever the query's own timeout is. Entering a free
     * runtime costs milliseconds; this only matters when several statements arrive at once, and a
     * caller with a 1 s timeout should still be allowed to queue briefly rather than fail on the
     * spot.
     */
    private static final long MIN_RUNTIME_WAIT_MS = 5_000;

    /**
     * Shape a client-supplied query id must have to be trusted as a job key. Anything else is
     * replaced by a server-generated one — the id ends up in the job store and in log lines, so
     * it must not carry arbitrary text.
     */
    private static final Pattern CLIENT_QUERY_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    /**
     * A windowed table function call. The table may carry a {@code PARTITION BY} (SESSION), and the
     * trailing group collects every INTERVAL argument: HOP takes (slide, size) and CUMULATE takes
     * (step, max), so the bucket width is always the last one.
     */
    private static final Pattern WINDOW_CALL = Pattern.compile(
        "(?i)\\b(TUMBLE|HOP|CUMULATE|SESSION)\\s*\\(\\s*TABLE\\s+(\\w[\\w.]*)"
            + "(?:\\s+PARTITION\\s+BY\\s+[^,]+)?\\s*,\\s*DESCRIPTOR\\s*\\(\\s*(\\w+)\\s*\\)"
            + "((?:\\s*,\\s*INTERVAL\\s+'\\d+'\\s+\\w+)+)\\s*\\)");

    /** First table named after FROM — backticked, quoted or bare. */
    private static final Pattern FROM_TABLE = Pattern.compile("(?i)\\bFROM\\s+[`\"]?([\\w.\\-]+)[`\"]?");

    /** One INTERVAL argument of a window call. Flink accepts both MINUTE and MINUTES. */
    private static final Pattern WINDOW_INTERVAL = Pattern.compile(
        "(?i)INTERVAL\\s+'(\\d+)'\\s+(MINUTE|HOUR|SECOND|DAY)S?");

    /** "Object 'x' not found" / "Table 'x' not found" — the planner's way of saying it has no such table. */
    private static final Pattern UNKNOWN_OBJECT = Pattern.compile(
        "(?:object|table)\\s+['\"][^'\"]*['\"]\\s+not found|does not exist", Pattern.CASE_INSENSITIVE);

    /**
     * The projection list of a SELECT — everything between SELECT and the first FROM.
     *
     * <p>Hoisted because it was compiled inline at three separate call sites with byte-identical
     * source (the aggregate parser, the windowed aggregate parser, and the column extractor).
     * Three copies of one grammar rule is how the three come to disagree: whichever site is edited
     * next takes the other two out of step silently, and none of them is covered by a test that
     * would notice. The compile-per-call was the smaller half of it.
     */
    private static final Pattern SELECT_PROJECTION =
        Pattern.compile("(?i)^\\s*+SELECT\\s++(.+?)\\s++FROM\\b", Pattern.DOTALL);

    /**
     * One aggregate call in a projection: {@code [func, DISTINCT?, column, alias?]}.
     *
     * <p>Every quantifier is possessive and the column is {@code [^)]++} rather than
     * {@code [^)]+?\\s*}, because {@code [^)]} and {@code \\s} both match a space: the two
     * competed for the same characters, and CodeQL's java/polynomial-redos was right about it —
     * measured at 857 ms on {@code "sum(a"} followed by 20 000 spaces, and past eight seconds on
     * {@code "sum("} followed by the same. It is 1 ms now. The capture therefore keeps any
     * trailing whitespace, which changes nothing: both call sites already {@code trim()} it, and
     * the equivalence was checked match-by-match over a corpus of real and degenerate SQL.
     */
    private static final Pattern AGGREGATE_CALL = Pattern.compile(
        "(?i)(COUNT|SUM|AVG|MAX|MIN)\\s*+\\(\\s*+(DISTINCT\\s++)?([^)]++)\\)(?:\\s++AS\\s++[`\"]?+([\\w]++)[`\"]?+)?");

    /** Does this projection call an aggregate at all? Cheaper than parsing the calls out. */
    private static final Pattern AGGREGATE_PRESENT =
        Pattern.compile("(?i)\\b(COUNT|SUM|AVG|MAX|MIN)\\s*\\(");

    /** A windowed read: {@code TABLE(TUMBLE(...))} and its siblings. */
    private static final Pattern WINDOW_TABLE_CALL =
        Pattern.compile("(?i)\\bTABLE\\s*\\(\\s*(TUMBLE|HOP|SESSION)\\s*\\(");

    /** The row cap written in the statement itself. */
    private static final Pattern LIMIT_CLAUSE = Pattern.compile("(?i)\\bLIMIT\\s+(\\d+)");

    private final java.util.concurrent.atomic.AtomicInteger flinkSelectFailures = new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile boolean flinkSelectDisabled = false;

    public static final class JobInfo {
        private final String queryId;
        private final String sql;
        private final String statementType;
        private final String executionMode;
        private final JobClient client;
        private final String flinkJobId;
        private final long startedAt;
        private volatile Long endedAt;
        private volatile boolean cancelRequested;
        private volatile Long cancelRequestedAt;

        public JobInfo(String queryId, String sql, String statementType, String executionMode, JobClient client, long startedAt) {
            this.queryId = queryId;
            this.sql = sql;
            this.statementType = statementType;
            this.executionMode = executionMode;
            this.client = client;
            this.flinkJobId = client.getJobID().toString();
            this.startedAt = startedAt;
        }

        public String queryId() { return queryId; }
        public String sql() { return sql; }
        public String statementType() { return statementType; }
        public String executionMode() { return executionMode; }
        public JobClient client() { return client; }
        public String flinkJobId() { return flinkJobId; }
        public long startedAt() { return startedAt; }
        public Long endedAt() { return endedAt; }
        public boolean cancelRequested() { return cancelRequested; }
        public Long cancelRequestedAt() { return cancelRequestedAt; }
        public void markCancelRequested() {
            this.cancelRequested = true;
            this.cancelRequestedAt = System.currentTimeMillis();
        }
        public void markEnded(long endedAt) { this.endedAt = endedAt; }
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public FlinkSqlService(TableEnvironment tableEnv, FlinkRuntimeCoordinator runtimeCoordinator,
                           ExplorerConfig explorerConfig, SqlQueryValidator sqlQueryValidator,
                           KafkaAdminService kafkaAdminService, SchemaInferenceService schemaInferenceService,
                           DdlGeneratorService ddlGeneratorService, FlinkJobStore flinkJobStore,
                           FlinkTableStore flinkTableStore) {
        this.tableEnv = tableEnv;
        this.runtimeCoordinator = runtimeCoordinator;
        this.explorerConfig = explorerConfig;
        this.sqlQueryValidator = sqlQueryValidator;
        this.kafkaAdminService = kafkaAdminService;
        this.schemaInferenceService = schemaInferenceService;
        this.ddlGeneratorService = ddlGeneratorService;
        this.flinkJobStore = flinkJobStore;
        this.flinkTableStore = flinkTableStore;
        // Register our custom XML extraction function globally in the Flink environment.
        runtimeCoordinator.runMutation("register-xml-extract-udf", () ->
            this.tableEnv.createTemporarySystemFunction("XmlExtract", XmlExtractUDF.class)
        );
    }

    public List<String> listTables() {
        return runtimeCoordinator.runRead("list-tables", () -> Arrays.asList(tableEnv.listTables()));
    }

    public List<String> listViews() {
        return runtimeCoordinator.runRead("list-views", () -> Arrays.asList(tableEnv.listViews()));
    }

    public Map<String, String> getTableSchema(String tableName) {
        try {
            return runtimeCoordinator.runRead("get-table-schema", () -> {
                Map<String, String> schema = new LinkedHashMap<>();
                tableEnv.from(tableName).getResolvedSchema().getColumns().forEach(col -> {
                    schema.put(col.getName(), col.getDataType().toString());
                });
                return schema;
            });
        } catch (RuntimeException e) {
            log.debug("Table not found: {}", LogSafe.name(tableName));
            return new LinkedHashMap<>();
        }
    }

    /**
     * @param waitMs how long the caller may wait to get onto the Flink runtime. A synchronous query
     *               passes its own budget: waiting three times the query timeout just to reach the
     *               planner, and only then starting to count, is not what the timeout promises.
     */
    private TableResult executeManagedSql(String operationName, String statementType, String sql, long waitMs) {
        if ("EXPLAIN".equals(statementType)) {
            return runtimeCoordinator.runRead(operationName, () -> tableEnv.executeSql(sql), waitMs);
        }
        return runtimeCoordinator.runMutation(operationName, () -> tableEnv.executeSql(sql), waitMs);
    }

    protected TableResult executeMutationSql(String operationName, String sql) {
        return runtimeCoordinator.runMutation(operationName, () -> tableEnv.executeSql(sql));
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
     * @param error                  non-null when a matching Kafka topic was found but registering
     *                               it as a Flink table failed; the query stops there.
     * @param registered             a table was created, so the UI should refresh its schema browser.
     * @param deferredToDirectReader the Kafka topic exists but was deliberately left unregistered,
     *                               so the Flink planner is expected to report it as unknown.
     *                               Without this flag that "not found" reads as a user typo and the
     *                               query would be rejected instead of falling back to the direct
     *                               reader that was meant to serve it.
     */
    private record AutoRegResult(String error, boolean registered, boolean deferredToDirectReader) {
        static AutoRegResult skip()          { return new AutoRegResult(null,  false, false); }
        static AutoRegResult tableCreated()  { return new AutoRegResult(null,  true,  false); }
        static AutoRegResult fail(String e)  { return new AutoRegResult(e,     false, false); }
        static AutoRegResult deferToDirect() { return new AutoRegResult(null,  false, true);  }
    }

    /**
     * The table a SELECT actually reads from.
     *
     * <p>Matching on FROM alone is wrong for a windowed query: {@code FROM TABLE(TUMBLE(TABLE
     * orders, …))} yields the keyword {@code TABLE}, so no topic ever matched, the table was never
     * registered, and the planner's resulting "Object 'orders' not found" looked exactly like a
     * typo. The window call is therefore consulted first — it carries the real name.
     */
    private String extractPrimaryTable(String sql) {
        Matcher window = WINDOW_CALL.matcher(sql);
        if (window.find()) return window.group(2);
        Matcher from = FROM_TABLE.matcher(sql);
        return from.find() ? from.group(1) : null;
    }

    /**
     * Before executing a SELECT, checks if the referenced table is already registered in Flink.
     * If not, looks for a Kafka topic whose sanitized name (dots/hyphens → underscores) matches,
     * infers its schema, generates the DDL and registers it automatically.
     */
    private AutoRegResult autoRegisterTableIfNeeded(String sql) {
        // Past a leading CTE chain: a `WITH … SELECT` skipped registration entirely, so the topic
        // its body reads was never registered and the planner answered "Object not found" — which
        // is how supporting CTEs at the guard alone would have produced a different dead end.
        if (!SqlStatements.classifiableBody(sql).startsWith("SELECT")) return AutoRegResult.skip();
        // The first FROM is inside the CTE body, which is exactly the source table to register.
        String rawTableRef = extractPrimaryTable(sql);
        if (rawTableRef == null) return AutoRegResult.skip();

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
            if (schema.isEmpty() && format != MessageFormat.XML) {
                // No schema could be inferred (empty topic or unreadable messages).
                // Skip Flink registration — KAFKA_DIRECT will read the topic directly.
                log.info("Skipping auto-registration for '{}': schema inference returned empty (topic may be empty)", matchingTopic);
                return AutoRegResult.deferToDirect();
            }
            String ddl = ddlGeneratorService.generateDdl(matchingTopic, schema, format);
            if (ddl == null || !ddl.startsWith("CREATE TABLE")) {
                return AutoRegResult.fail("DDL Generator produced invalid SQL for topic " + matchingTopic);
            }
            // Masqué, et c'est le DDL *brut* qui part à Flink juste en dessous : le connecteur a
            // besoin des vrais identifiants, le fichier de log n'en a pas besoin. `generateDdl`
            // recopie toutes les propriétés client Kafka dans le `WITH (…)`, mots de passe SSL et
            // `sasl.jaas.config` compris, donc journaliser `ddl` tel quel écrivait le secret
            // Confluent en clair dans `logs/kafkaexplorer.log` — un volume nommé dans toutes les
            // stacks, et le premier fichier qu'on colle dans un rapport de bug. En DEBUG
            // seulement, mais c'est précisément le niveau qu'un opérateur active pour comprendre
            // pourquoi une requête échoue, c'est-à-dire quand cette ligne s'exécute.
            log.debug("Auto-registering table '{}' with DDL:\n{}", LogSafe.name(flinkTableName),
                DdlGeneratorService.maskSensitiveProperties(ddl));
            executeMutationSql("auto-register-table", ddl);
            log.info("Auto-registered table '{}' for Kafka topic '{}'",
                LogSafe.name(flinkTableName), LogSafe.name(matchingTopic));
            return AutoRegResult.tableCreated();
        } catch (Exception e) {
            log.error("Auto-registration failed for topic '{}' (table '{}'): {}",
                LogSafe.name(matchingTopic), LogSafe.name(flinkTableName), e.getMessage(), e);
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
    /** Package-private: driven directly by {@code FlinkSqlServiceTest}, like {@link #unsupportedWhereFragments}. */
    String stripSqlComments(String sql) {
        if (sql == null) return null;
        sql = stripBlockComments(sql);
        // Line comments stay a regex: `--[^\n]*` has no ambiguity, so it is linear.
        sql = sql.replaceAll("--[^\n]*", "");
        return sql.trim();
    }

    /**
     * Removes SQL block comments in a single left-to-right pass.
     *
     * <p>This was a regex, and not a slow one — a <em>crashing</em> one. The unrolled-loop form
     * it used recursed once per repetition in Java's backtracking engine, so a statement built
     * from a few thousand unterminated comment openers raised {@link StackOverflowError} — an
     * {@code Error}, so nothing on the request path caught it. Rewriting it as a lazy scan
     * removed the crash and left it quadratic: with no closing delimiter anywhere, every opener
     * in the input rescans the whole remainder.
     *
     * <p>A hand-written scan is linear, allocates one builder, and is the shorter code. The
     * semantics are the regex's: a block runs to the <em>first</em> closing delimiter after it,
     * an unterminated block is not a comment and is left as written (so it cannot swallow the
     * statement), and each removed block becomes one space — a comment between two tokens must
     * not weld them together.
     */
    private static String stripBlockComments(String sql) {
        int open = sql.indexOf("/*");
        if (open < 0) return sql;
        StringBuilder out = new StringBuilder(sql.length());
        int from = 0;
        while (open >= 0) {
            int close = sql.indexOf("*/", open + 2);
            if (close < 0) break;              // unterminated: keep the rest verbatim
            out.append(sql, from, open).append(' ');
            from = close + 2;
            open = sql.indexOf("/*", from);
        }
        return out.append(sql, from, sql.length()).toString();
    }

    private String extractStatementType(String sql) {
        if (sql == null || sql.isBlank()) return "UNKNOWN";
        // Classified past a leading CTE chain, so `WITH … INSERT INTO` and `WITH … SELECT` are
        // routed like the statements they actually are.
        String upper = SqlStatements.classifiableBody(stripSqlComments(sql));
        if (upper.startsWith("INSERT INTO")) return "INSERT";
        if (upper.startsWith("CREATE TABLE")) return "CREATE_TABLE";
        if (upper.startsWith("SELECT")) return "SELECT";
        if (upper.startsWith("EXPLAIN")) return "EXPLAIN";
        return upper.split("\\s+", 2)[0];
    }

    private FlinkJobSummary buildJobSummary(JobInfo info) {
        String status = "UNKNOWN";
        try {
            JobStatus flinkStatus = info.client().getJobStatus().get(150, TimeUnit.MILLISECONDS);
            status = flinkStatus.name();
            if (flinkStatus.isGloballyTerminalState() && info.endedAt() == null) {
                info.markEnded(System.currentTimeMillis());
            }
        } catch (IllegalStateException gone) {
            // The embedded runtime gives each job its own MiniCluster and takes it down when the
            // job reaches a terminal state, so every later call on that JobClient answers
            // "MiniCluster is not yet running or has already been shut down". That is an answer
            // about the *job*: it is over. Read as a mere unreadable status — which is what this
            // catch did, all exceptions together — the job kept `endedAt == null` and therefore
            // never left `heldJobs`, so `getHeldJobs()` went on reporting a finished
            // query as running: `POST /api/config` refused a cluster repoint with 409, the lineage
            // graph drew a node for it, and the KPI suggestions derived an edge from it, for the
            // rest of the process. What we do *not* know is how it ended, so the status stays
            // UNKNOWN — which the job store already treats as terminal — rather than a FINISHED
            // nobody observed.
            if (info.endedAt() == null) {
                info.markEnded(System.currentTimeMillis());
            }
            log.debug("[FlinkSQL] queryId={} is no longer held by the Flink runtime: {}",
                info.queryId(), gone.getMessage());
        } catch (Exception e) {
            // Anything else — a status call that ran out of its 150 ms, an interrupt — says
            // nothing about the job. It used to be reported as UNKNOWN, which the job store
            // counts as *terminal*: one slow answer stamped an `endedAt` on a job that was still
            // running, dropped it out of `getActiveJobs()` (the dashboard's list) and put it on
            // the retention clock, so a long INSERT could be pruned out of the store while it ran.
            // STATUS_UNAVAILABLE is the same distinction the branch above draws from the other
            // side: "the runtime no longer holds this job" is an answer, "we could not ask" is not.
            status = info.cancelRequested() ? "CANCEL_REQUESTED" : STATUS_UNAVAILABLE;
            log.debug("[FlinkSQL] queryId={} status could not be read: {}",
                info.queryId(), SqlErrorClassifier.explain(e));
        }

        if (info.cancelRequested() && "RUNNING".equals(status)) {
            status = "CANCELLING";
        }

        return new FlinkJobSummary(
            info.queryId(),
            info.flinkJobId(),
            info.statementType(),
            status,
            info.sql(),
            info.startedAt(),
            info.endedAt(),
            info.cancelRequested()
        );
    }

    private void persistJobSnapshot(JobInfo info, FlinkJobSummary summary, String statusDetail, String errorMessage) {
        FlinkManagedJobDetails updated = flinkJobStore.update(
            info.queryId(),
            summary.status(),
            statusDetail,
            errorMessage,
            summary.cancelRequested(),
            info.cancelRequestedAt(),
            summary.endedAt(),
            info.flinkJobId()
        );
        if (updated == null) {
            flinkJobStore.create(
                info.queryId(),
                info.flinkJobId(),
                info.statementType(),
                info.executionMode(),
                summary.status(),
                statusDetail,
                info.sql(),
                info.startedAt(),
                errorMessage
            );
        }
    }

    private void syncPersistedJobs() {
        // buildJobSummary blocks up to 150ms on the Flink status call per job, so a serial sweep of
        // N jobs would block up to N×150ms. Poll the statuses in parallel; keep persistence serial
        // (single writer to the job store) and remove jobs that have reached a terminal state.
        List<Map.Entry<String, JobInfo>> entries = new ArrayList<>(heldJobs.entrySet());
        if (entries.isEmpty()) return;

        Map<String, FlinkJobSummary> summaries = entries.stream()
            .map(e -> CompletableFuture.supplyAsync(
                () -> Map.entry(e.getKey(), buildJobSummary(e.getValue())), queryExecutor))
            .toList().stream()
            .map(CompletableFuture::join)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        for (Map.Entry<String, JobInfo> e : entries) {
            JobInfo info = e.getValue();
            FlinkJobSummary summary = summaries.get(e.getKey());
            String statusDetail = summary.cancelRequested() ? "Cancellation requested by user" : null;
            persistJobSnapshot(info, summary, statusDetail, null);
            if (summary.endedAt() != null) {
                heldJobs.remove(e.getKey());
            }
        }
    }

    private String prepareSql(String sql) {
        return stripSqlComments(normalizeIdentifierQuotes(sql.trim()));
    }

    public QueryResult executeSync(QueryRequest request) {
        String strippedSql = prepareSql(request.sql());
        if ("INSERT".equals(extractStatementType(strippedSql))) {
            return new QueryResult(
                Collections.emptyList(),
                Collections.emptyList(),
                0,
                "INSERT INTO statements must be submitted via /api/query/jobs in Flink Job mode."
            );
        }
        return executeSql(request);
    }

    public FlinkJobSummary submitJob(QueryRequest request) {
        long startedAt = System.currentTimeMillis();
        String queryId = UUID.randomUUID().toString();
        String strippedSql = prepareSql(request.sql());
        String statementType = extractStatementType(strippedSql);

        if (!"INSERT".equals(statementType)) {
            flinkJobStore.create(
                queryId,
                null,
                statementType,
                "ASYNC_JOB",
                "FAILED",
                "Rejected before execution",
                strippedSql,
                startedAt,
                "Only INSERT INTO statements are allowed in Flink Job mode."
            );
            throw new IllegalArgumentException("Only INSERT INTO statements are allowed in Flink Job mode.");
        }

        try {
            sqlQueryValidator.validate(strippedSql);

            TableResult result = executeMutationSql("submit-job", strippedSql);
            JobClient client = result.getJobClient()
                .orElseThrow(() -> new IllegalStateException("Flink did not return a JobClient for the submitted job."));

            JobInfo info = new JobInfo(queryId, strippedSql, statementType, "ASYNC_JOB", client, startedAt);
            heldJobs.put(queryId, info);
            FlinkJobSummary summary = buildJobSummary(info);
            flinkJobStore.create(
                queryId,
                info.flinkJobId(),
                statementType,
                info.executionMode(),
                summary.status(),
                "Submitted via Flink Job mode",
                strippedSql,
                startedAt,
                null
            );
            return summary;
        } catch (RuntimeException e) {
            flinkJobStore.create(
                queryId,
                null,
                statementType,
                "ASYNC_JOB",
                "FAILED",
                "Submission failed before a Flink JobClient was available",
                strippedSql,
                startedAt,
                e.getMessage()
            );
            throw e;
        }
    }

    public QueryResult executeSql(QueryRequest request) {
        long startTime = System.currentTimeMillis();
        String queryId = resolveQueryId(request.queryId());
        // Normalize double-quoted identifiers to backticks before any parsing/validation
        String originalSql = normalizeIdentifierQuotes(request.sql().trim());
        // Strip comments before keyword checks — a query like "-- comment\nSELECT ..."
        // must not be rejected because startsWith("SELECT") would fail on the comment line.
        String strippedSql = stripSqlComments(originalSql);
        // The guard classifies the statement *past* a leading `WITH … AS ( … )` chain, which is
        // what made a common table expression impossible to run: it begins with WITH, so it was
        // refused as if it were dangerous DDL. `withoutLeadingCte` fails closed — a WITH whose
        // shape it does not recognise comes back unchanged and is refused exactly as before.
        String sql = SqlStatements.classifiableBody(strippedSql);

        // Security: Prevent execution of dangerous or unsupported DDL/DML.
        if (!sql.startsWith("SELECT") && !sql.startsWith("EXPLAIN") && !sql.startsWith("CREATE TABLE")) {
            return new QueryResult(Collections.emptyList(), Collections.emptyList(), 0, "Only SELECT, EXPLAIN and CREATE TABLE statements are allowed.");
        }

        try {
            sqlQueryValidator.validate(strippedSql);
        } catch (IllegalArgumentException e) {
            return new QueryResult(Collections.emptyList(), Collections.emptyList(), 0, "SQL Validation Error: " + e.getMessage());
        }

        try {
            AutoRegResult autoReg = autoRegisterTableIfNeeded(strippedSql);
            if (autoReg.error() != null) {
                log.error("Table auto-registration failed — query='{}' error='{}'",
                    LogSafe.text(request.sql()), LogSafe.text(autoReg.error()));
                return new QueryResult(Collections.emptyList(), Collections.emptyList(),
                        System.currentTimeMillis() - startTime, autoReg.error());
            }
            String sqlToExecute = strippedSql;
            String readMode = request.readMode();
            int limit = request.maxRows() != null ? request.maxRows() : explorerConfig.getDefaultMaxRows();
            long timeout = request.timeout() != null ? request.timeout() : explorerConfig.getDefaultQueryTimeoutMs();

            boolean isCte = SqlStatements.startsWithCte(sqlToExecute);
            if (SqlStatements.classifiableBody(sqlToExecute).startsWith("SELECT")) {
                // Prefer the real Flink planner when enabled and not tripped by the circuit breaker.
                // The FlinkRelMetadataQuery NPE that historically forced the bypass is version
                // dependent, so on an *engine* failure we fall back to the in-process direct Kafka
                // reader and the query still succeeds. A failure caused by the statement itself is
                // returned instead — see the no-fallback note below.
                // Why the planner did not answer, kept for the fallback to report. Without it the
                // caller was told whatever the *direct reader* complained about, which describes a
                // different query engine's opinion of a statement it was never meant to run.
                String engineFailure = null;
                /*
                 * One caller asks for the direct reader by name, and the planner is not consulted
                 * at all for it: `readMode` is honoured by that reader alone, so "the most recent
                 * N records" has no expression here — a Kafka scan starting at latest-offset and
                 * bounded at latest-offset reads nothing — and a caller whose question is "recent"
                 * must not be answered with the oldest rows the cap allowed. The metric templates
                 * ask it only for a single-table read, which is the shape this reader can answer;
                 * see QueryRequest.directRead().
                 */
                if (request.wantsDirectRead() && extractPrimaryTable(sqlToExecute) != null) {
                    QueryResult direct = kafkaDirectSelect(sqlToExecute, readMode, limit, startTime);
                    return autoReg.registered() ? withRegisteredFlag(direct) : direct;
                }
                if (explorerConfig.isFlinkSelectEnabled() && !flinkSelectDisabled) {
                    try {
                        QueryResult flinkResult = executeViaFlinkPlanner(queryId, sqlToExecute, "SELECT", limit, timeout, startTime);
                        if (flinkResult.error() == null) {
                            flinkSelectFailures.set(0);
                            return autoReg.registered() ? withRegisteredFlag(flinkResult) : flinkResult;
                        }
                        // A timeout means the planner worked but the job was slow (empty/large topic):
                        // fall back for this query, but don't count it toward the circuit breaker.
                        if (flinkResult.error().startsWith("Query timed out")) {
                            flinkSelectFailures.set(0);
                            log.warn("Flink SELECT timed out — falling back to direct Kafka read for this query");
                            engineFailure = flinkResult.error();
                        } else {
                            QueryResult rejected = rejectIfUserError(
                                flinkResult.error(), sqlToExecute, startTime, autoReg.deferredToDirectReader());
                            if (rejected != null) return rejected;
                            recordFlinkSelectFailure(flinkResult.error());
                            engineFailure = flinkResult.error();
                        }
                    } catch (Throwable t) {
                        QueryResult rejected = rejectIfUserError(
                            SqlErrorClassifier.explain(t), sqlToExecute, startTime, autoReg.deferredToDirectReader());
                        if (rejected != null) return rejected;
                        recordFlinkSelectFailure(t.toString());
                        engineFailure = SqlErrorClassifier.explain(t);
                    }
                }
                /*
                 * A common table expression never falls back to the direct reader.
                 *
                 * That reader regex-matches a table name out of `FROM`, so on `WITH recent AS
                 * (SELECT … FROM orders) SELECT * FROM recent` it would read whichever name it
                 * happened to match and return **rows** — from the wrong place, or none, with no
                 * indication that the CTE was never applied. Wrong rows are worse than a refusal,
                 * which is the whole reason user errors stopped falling back in the first place.
                 */
                if (isCte) {
                    return new QueryResult(Collections.emptyList(), Collections.emptyList(),
                            System.currentTimeMillis() - startTime,
                            "This query uses a common table expression, which only the Flink engine can run. "
                          + "The Flink planner did not answer, so it was not run — rather than reading the "
                          + "topics directly and returning rows that ignore the WITH clause.");
                }
                /*
                 * A SELECT that names no table never falls back either, for the same reason as the
                 * CTE above: the direct reader begins by regex-matching a name out of `FROM`, so on
                 * `SELECT 1 + 1` it can only answer "Cannot parse table name from SQL" — its own
                 * complaint about a statement it was never meant to run, handed to the user in
                 * place of the engine's real one. That is exactly what the startup warmup probe
                 * reported for months: the true cause (a job that could not be submitted) sat one
                 * WARN line above, while the line everyone read named a table nobody had written.
                 */
                if (extractPrimaryTable(sqlToExecute) == null) {
                    return new QueryResult(Collections.emptyList(), Collections.emptyList(),
                            System.currentTimeMillis() - startTime,
                            engineFailure != null ? engineFailure : plannerUnavailableMessage());
                }
                QueryResult qr = kafkaDirectSelect(sqlToExecute, readMode, limit, startTime);
                // Say that the planner is out of the picture for the rest of this process, on the
                // queries it actually affects. The latch was written in one place and read in one
                // place and surfaced nowhere, so a user got `engine: KAFKA_DIRECT` — no JOIN, no
                // subquery — with nothing saying it had become permanent.
                if (flinkSelectDisabled) {
                    qr = withExtraWarning(qr, plannerUnavailableMessage());
                }
                // Propagate auto-registration flag so the frontend can refresh its schema browser.
                return autoReg.registered() ? withRegisteredFlag(qr) : qr;
            }

            // CREATE TABLE / EXPLAIN go through the Flink planner directly.
            String statementType = extractStatementType(sqlToExecute);
            QueryResult result = executeViaFlinkPlanner(queryId, sqlToExecute, statementType, limit, timeout, startTime);
            if ("CREATE_TABLE".equals(statementType) && result.error() == null) {
                // Kept only once it has actually worked, and only for a statement that came
                // through the API: auto-registration writes its DDL through executeMutationSql and
                // never lands here, which is the distinction the store rests on. A table derived
                // from a topic is re-derived on demand; one somebody typed is not.
                rememberTableDefinition(sqlToExecute);
            }
            return result;
        } catch (Exception e) {
            log.error("Flink SQL execution error — query='{}' error='{}'",
                LogSafe.text(request.sql()), LogSafe.text(e.getMessage()), e);
            long duration = System.currentTimeMillis() - startTime;
            return new QueryResult(Collections.emptyList(), Collections.emptyList(), duration,
                SqlErrorClassifier.explain(e));
        }
    }

    /**
     * Keeps a hand-written table definition, without ever letting that cost the query.
     *
     * <p>The table is registered in Flink for the life of this process whatever happens here; what
     * a failure loses is only its return after a restart, and failing a query the operator was
     * running in order to report that would be the wrong trade.
     */
    private void rememberTableDefinition(String sql) {
        try {
            String name = flinkTableStore.remember(sql);
            if (name != null) {
                log.info("Table definition '{}' will be restored at startup", name);
            }
        } catch (Exception e) {
            log.warn("A table definition was not kept: {}", SqlErrorClassifier.explain(e));
        }
    }

    /**
     * Drops a table from Flink and stops keeping its definition.
     *
     * <p>This exists because {@link FlinkTableStore} takes an escape hatch away: restarting used to
     * be the only way to clear a table from the in-memory catalogue, and a store that could only
     * grow would be a worse defect than the substitution it fixes.
     *
     * <p>The name goes back into a statement, so it is checked against
     * {@link FlinkTableStore#isSafeIdentifier} rather than quoted and hoped for — a backtick in a
     * path variable is SQL injection into an engine that will happily run whatever DDL it is
     * handed. Both halves are attempted even when the first fails: a table Flink does not know
     * (created by an older build, or already dropped) must still be removable from the store, or
     * the boot would go on replaying a definition nobody can get rid of.
     *
     * @return whether anything was actually dropped or forgotten
     */
    /**
     * What dropping a table achieved — the shape {@link CancelOutcome} already uses here, and for
     * the same reason.
     *
     * <p>A boolean could not tell "there was no such table" from "there was, and the engine refused
     * it", so the answer asserted the first whenever the second happened. That is the class of
     * claim this codebase removes everywhere else: a message that states something nobody checked.
     */
    public enum DropOutcome {
        /** Removed from Flink's catalogue, from the store of kept definitions, or from both. */
        DROPPED,
        /** Neither Flink nor the store had a table of that name, so there was nothing to do. */
        NOT_FOUND,
        /** It existed and could not be dropped; the reason is on the server log. */
        REFUSED
    }

    public DropOutcome dropTable(String name) {
        if (!FlinkTableStore.isSafeIdentifier(name)) {
            // The offending text is not echoed. It is a path variable, so it goes into the answer
            // and could go into a log; a name refused for containing something that does not
            // belong in an identifier is the last thing to repeat back verbatim.
            throw new IllegalArgumentException(
                "That is not a table name this application will put into a statement: only letters, "
                    + "digits, '_' and '$' are accepted, and it must not start with a digit.");
        }
        // Resolved against what exists, never interpolated. What reaches the statement, the log and
        // the store is this application's own string — Flink's catalogue entry or the store's key —
        // and the request only ever selects between them. That is a stronger guarantee than
        // matching the request against a pattern and trusting the match downstream, and it is the
        // one a reader can check without leaving this method.
        String table = knownTableNamed(name);
        if (table == null) {
            // Nothing of that name in either place, so there is nothing to drop and no statement
            // worth submitting. The caller is told plainly rather than being shown the failure of
            // a DROP that was never going to work.
            return DropOutcome.NOT_FOUND;
        }
        boolean dropped = false;
        try {
            executeMutationSql("drop-table", "DROP TABLE `" + table + "`");
            dropped = true;
        } catch (Exception e) {
            // Reported, not thrown: the store still has to be cleaned up below, and a table the
            // store knows while Flink does not — one an older build wrote, or one already dropped
            // — is a normal answer here rather than a failure.
            log.info("DROP TABLE `{}` did not run: {}", table, SqlErrorClassifier.explain(e));
        }
        boolean forgotten = flinkTableStore.forget(table);
        // Known but neither dropped nor forgotten: it is in Flink's catalogue and the engine would
        // not remove it. Saying "no such table" there would be the answer to a question nobody
        // asked, about a table that is plainly still present in the schema browser.
        return dropped || forgotten ? DropOutcome.DROPPED : DropOutcome.REFUSED;
    }

    /**
     * This application's own spelling of a table the request named, or {@code null}.
     *
     * <p>Flink's catalogue first, then the definitions kept for replay: a table can legitimately be
     * in the store and not in Flink — written by an older build, or already dropped — and it still
     * has to be removable, or the boot would go on replaying a definition nobody can get rid of.
     *
     * <p>The comparison is exact. {@code FlinkTableStore.forget} matches case-insensitively as a
     * courtesy to a name read off a screen, but that is about forgetting an entry, whereas this
     * name is going into a statement, and Flink identifiers are case-sensitive.
     */
    private String knownTableNamed(String requested) {
        for (String table : listTables()) {
            if (table.equals(requested)) return table;
        }
        for (FlinkTableStore.StoredTable stored : flinkTableStore.all()) {
            if (stored.name().equals(requested)) return stored.name();
        }
        return null;
    }

    /**
     * Uses the caller's query id when it is well-formed, otherwise mints one.
     *
     * <p>A synchronous run only learns a server-generated id from its own response, by which time
     * the query is over — so cancelling one requires the caller to have named it beforehand.
     */
    static String resolveQueryId(String requested) {
        return requested != null && CLIENT_QUERY_ID.matcher(requested).matches()
            ? requested
            : UUID.randomUUID().toString();
    }

    /**
     * Returns a failed {@link QueryResult} when the planner rejected the statement itself, or null
     * when the failure looks like an engine fault the direct reader can work around.
     *
     * <p>Falling back on a user error is actively harmful: the direct reader only regex-matches the
     * table name out of the FROM clause, so {@code SELECT id, FROM orders} or a misspelled column
     * comes back as a page of rows and the query looks like it worked. The planner already said
     * precisely what is wrong, with a line and column — that answer is the useful one, and it does
     * not count toward the circuit breaker either, or three typos would disable the Flink planner
     * for the rest of the process.
     */
    private QueryResult rejectIfUserError(String rawError, String sql, long startTime, boolean deferredToDirectReader) {
        SqlErrorClassifier.Classification classification = SqlErrorClassifier.classify(rawError);
        if (!classification.isUserError()) return null;
        // The topic exists, we chose not to register it, and the planner is only saying so.
        // That is our doing, not the user's — the direct reader is the intended path here.
        if (deferredToDirectReader && UNKNOWN_OBJECT.matcher(classification.message()).find()) return null;
        log.debug("Rejecting invalid SELECT without falling back — query='{}' error='{}'",
                LogSafe.text(sql), LogSafe.text(classification.message()));
        flinkSelectFailures.set(0);
        return new QueryResult(Collections.emptyList(), Collections.emptyList(),
            System.currentTimeMillis() - startTime, classification.message(), false, "FLINK");
    }

    /**
     * Why the Flink planner is not answering, in the words a user can act on.
     *
     * <p>Two different states read identically from the outside — an operator turned the planner
     * off, or the circuit breaker turned it off after {@link #FLINK_SELECT_FAILURE_THRESHOLD}
     * failures — and the second one is invisible without this: it latches for the lifetime of the
     * process, so every later query silently gets an engine that supports neither JOIN nor
     * subqueries.
     */
    private String plannerUnavailableMessage() {
        if (!explorerConfig.isFlinkSelectEnabled()) {
            return "The Flink planner is disabled (explorer.flink-select-enabled=false), and this "
                 + "query needs it — the direct Kafka reader only reads a topic named after FROM.";
        }
        if (flinkSelectDisabled) {
            return "The Flink planner failed " + FLINK_SELECT_FAILURE_THRESHOLD + " times and is "
                 + "disabled for the rest of this process, so queries fall back to the direct Kafka "
                 + "reader, which supports neither JOIN nor subqueries. Restart the application to "
                 + "retry it; the log records why it failed.";
        }
        return "The Flink planner did not answer, and this query needs it — the direct Kafka reader "
             + "only reads a topic named after FROM.";
    }

    /** Appends one caveat, keeping those the direct reader already reported. */
    private static QueryResult withExtraWarning(QueryResult qr, String warning) {
        List<String> merged = new ArrayList<>(qr.warnings() == null ? List.of() : qr.warnings());
        if (!merged.contains(warning)) merged.add(warning);
        return qr.withWarnings(merged);
    }

    /** Whether the circuit breaker has taken the Flink planner out for this process. */
    public boolean isFlinkSelectDisabled() {
        return flinkSelectDisabled;
    }

    private QueryResult withRegisteredFlag(QueryResult qr) {
        return new QueryResult(qr.columns(), qr.rows(), qr.durationMs(), qr.error(), true, qr.engine());
    }

    private void recordFlinkSelectFailure(String reason) {
        int failures = flinkSelectFailures.incrementAndGet();
        if (failures >= FLINK_SELECT_FAILURE_THRESHOLD && !flinkSelectDisabled) {
            flinkSelectDisabled = true;
            log.warn("Flink SELECT failed {} times (last: {}); disabling the Flink planner path for SELECT "
                + "for this process and using the direct Kafka reader instead. Restart after upgrading Flink to retry.",
                failures, reason);
        } else {
            log.warn("Flink SELECT failed ({}) — falling back to direct Kafka read", reason);
        }
    }

    /**
     * Executes a statement through the embedded Flink planner and collects up to {@code limit}
     * rows with a {@code timeout} guard. Returns a {@link QueryResult} whose {@code error()} is
     * non-null on failure, so SELECT callers can detect it and fall back to the direct Kafka reader.
     */
    private QueryResult executeViaFlinkPlanner(String queryId, String finalSql, String statementType,
                                               int limit, long timeout, long startTime) {
        TableResult result = null;
        // The job this call registers, so the finally below can hand it back. It has to be
        // released here: a synchronous read is over when this method returns, and nothing else
        // knows that. Left in `heldJobs`, it stayed there until some *other* caller happened to
        // run the status sweep — which on a headless deployment is nobody, while the metric
        // refresh loop keeps adding one every thirty seconds, each holding a JobClient. An open
        // browser hid it, the dashboard poll being the only sweeper there is.
        final java.util.concurrent.atomic.AtomicReference<JobInfo> registered =
            new java.util.concurrent.atomic.AtomicReference<>();
        try {
            // Le budget d'attente vaut celui de la requête, avec un plancher : sur un runtime
            // libre, entrer coûte quelques millisecondes ; sur un runtime occupé, l'appelant doit
            // ressortir avec un message plutôt que d'attendre indéfiniment que la place se libère.
            long enterBudgetMs = Math.max(MIN_RUNTIME_WAIT_MS, timeout);
            result = executeManagedSql(
                "execute-sql-" + statementType.toLowerCase(Locale.ROOT), statementType, finalSql, enterBudgetMs);
            result.getJobClient().ifPresent(client -> {
                JobInfo info = new JobInfo(queryId, finalSql, statementType, "SYNC_READ", client, System.currentTimeMillis());
                heldJobs.put(queryId, info);
                registered.set(info);
                FlinkJobSummary summary = buildJobSummary(info);
                // create() writes the record; the update that used to follow it repeated the same
                // status and the same detail, so it bought a second full rewrite of the store file
                // and nothing else.
                flinkJobStore.create(
                    queryId,
                    info.flinkJobId(),
                    statementType,
                    info.executionMode(),
                    summary.status(),
                    "Executed through synchronous exploration mode",
                    finalSql,
                    info.startedAt(),
                    null
                );
            });

            final TableResult tableResult = result;
            // result.collect() starts the Flink job and provides an iterator to fetch results.
            // The iterator is NOT thread-safe and is touched by a single thread only — the fetcher
            // task below, which also closes it in its finally. Closing it from this (calling) thread
            // via try-with-resources would race with an in-flight read on timeout, so on timeout we
            // instead cancel the Flink job to unblock the fetcher and let it close the iterator.
            final org.apache.flink.util.CloseableIterator<Row> it = result.collect();
            List<String> columns = result.getResolvedSchema().getColumnNames();
            log.debug("[FlinkSQL] queryId={} sql='{}' resolvedColumns={} resolvedSchema={}",
                    LogSafe.name(queryId), LogSafe.text(finalSql), columns, result.getResolvedSchema());
            List<Map<String, Object>> rows;

            // We use a CompletableFuture to implement the timeout logic.
            // Streaming queries might not produce data immediately, so we don't want to block indefinitely.
            CompletableFuture<List<Map<String, Object>>> future;
            try {
                future = CompletableFuture.supplyAsync(() -> {
                    try {
                        List<Map<String, Object>> resultRows = new ArrayList<>();
                        int count = 0;
                        while (it.hasNext() && count < limit) {
                            Row row = it.next();
                            if (count == 0 && log.isDebugEnabled()) {
                                log.debug("[FlinkSQL] queryId={} first row arity={} kind={} rowString='{}'",
                                        queryId, row.getArity(), row.getKind(), row);
                                for (int i = 0; i < columns.size(); i++) {
                                    Object field = row.getField(i);
                                    log.debug("[FlinkSQL] queryId={} col[{}]='{}' valueType={} value='{}'",
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
                        log.debug("[FlinkSQL] queryId={} total rows fetched={}", queryId, resultRows.size());
                        return resultRows;
                    } finally {
                        // Only the fetcher thread ever touches the iterator, so it closes it too —
                        // never the calling thread, even on timeout.
                        try {
                            it.close();
                        } catch (Exception ce) {
                            log.debug("[FlinkSQL] queryId={} error closing result iterator: {}", queryId, ce.getMessage());
                        }
                    }
                }, queryExecutor);
            } catch (RuntimeException submitFailure) {
                // The fetcher never started, so it will never close the iterator — do it here.
                try { it.close(); } catch (Exception ignored) { }
                throw submitFailure;
            }

            try {
                rows = future.get(timeout, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                log.warn("Query timed out after {}ms: {}", timeout, LogSafe.text(finalSql));
                // Cancel the job first so the fetcher's hasNext() unblocks and its finally closes the
                // iterator; then abandon the fetch. The iterator is never closed from this thread.
                cancelJobInternal(tableResult);
                future.cancel(true);
                long duration = System.currentTimeMillis() - startTime;
                return new QueryResult(Collections.emptyList(), Collections.emptyList(), duration,
                    "Query timed out after " + timeout + "ms. The Kafka topic may have fewer messages than the limit, " +
                    "or the broker is slow. Try adding LIMIT, reducing maxRows, or switching to 'latest-offset' mode.");
            } catch (ExecutionException ee) {
                log.error("Query execution failed: {}", LogSafe.text(finalSql), ee.getCause());
                Throwable cause = ee.getCause();
                if (cause instanceof Exception ex) throw ex;
                throw new RuntimeException(cause);
            }

            long duration = System.currentTimeMillis() - startTime;
            return new QueryResult(columns, rows, duration, null, false, "FLINK");
        } catch (Exception e) {
            log.error("Flink SQL execution error — query='{}' error='{}'",
                LogSafe.text(finalSql), LogSafe.text(e.getMessage()), e);
            cancelJobInternal(result);
            long duration = System.currentTimeMillis() - startTime;
            // explain() flattens the cause chain and is never blank. e.getMessage() alone is null
            // for a bare NullPointerException, which left error() null — the caller then read the
            // crash as a successful run of zero rows.
            return new QueryResult(Collections.emptyList(), Collections.emptyList(), duration,
                SqlErrorClassifier.explain(e));
        } finally {
            releaseSyncJob(registered.get());
        }
    }

    /**
     * Hands back the job a synchronous read registered, once that read has returned.
     *
     * <p>The cancel endpoint needs the entry <em>while</em> the query runs — that is the whole
     * point of {@code POST /api/query/cancel/{queryId}} — and nothing needs it afterwards: the
     * fetcher closes the iterator, which takes the Flink job with it. Whatever the runtime still
     * says about it is recorded rather than invented, so a job whose MiniCluster is already gone
     * is filed as UNKNOWN exactly as the status sweep would have filed it.
     */
    private void releaseSyncJob(JobInfo info) {
        if (info == null) return;
        if (info.endedAt() == null) {
            info.markEnded(System.currentTimeMillis());
        }
        heldJobs.remove(info.queryId());
        try {
            persistJobSnapshot(info, buildJobSummary(info), "Synchronous read finished", null);
        } catch (RuntimeException e) {
            // Bookkeeping must never replace the answer the caller is holding — this runs from a
            // finally, including the one on the failure path.
            log.debug("[FlinkSQL] queryId={} could not be filed after its read: {}",
                info.queryId(), SqlErrorClassifier.explain(e));
        }
    }

    /**
     * Direct Kafka reader for SELECT queries — used as a fallback when the Flink planner path is
     * disabled or fails (historically a persistent FlinkRelMetadataQuery NPE, reproduced on Flink
     * 1.18/1.20/2.0). Reads from Kafka directly, bypassing the Flink optimizer.
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
                Document doc = SecureXml.documentBuilder()
                    .parse(new ByteArrayInputStream(trimmed.getBytes(StandardCharsets.UTF_8)));
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

    /** How many records an in-process aggregate may read before it stops and says it stopped. */
    public static final int AGGREGATE_SCAN_RECORDS = 100_000;

    /** The head of the caveat an aggregate carries when it stopped on that ceiling. */
    public static final String AGGREGATE_SCAN_CAPPED = "Aggregate scan ceiling reached";

    /**
     * The read mode that names an <b>instant</b> rather than an end of the log.
     *
     * <p>{@code readMode} is honoured by the direct reader alone, and it had exactly two values:
     * enter by the oldest offset, or by the newest. Neither expresses "the last ten minutes", and
     * the difference matters to any caller reading two topics that must describe the same window —
     * a row cap over two topics of different throughputs reads two different stretches of time, so
     * the pairs they yield are an accident of those throughputs. The instant travels in the mode
     * itself rather than as a new field on {@link QueryRequest}: it is a direct-reader concept, and
     * the string already carries direct-reader-only meaning.
     */
    static final String SINCE_READ_MODE_PREFIX = "since:";

    /** Build the read mode that reads from {@code timestampMs} forward. */
    public static String sinceReadMode(long timestampMs) {
        return SINCE_READ_MODE_PREFIX + timestampMs;
    }

    /** The instant a {@code since:} read mode names, or {@code null} for the two offset modes. */
    static Long sinceTimestampOf(String readMode) {
        if (readMode == null || !readMode.startsWith(SINCE_READ_MODE_PREFIX)) return null;
        try {
            return Long.parseLong(readMode.substring(SINCE_READ_MODE_PREFIX.length()).trim());
        } catch (NumberFormatException e) {
            // Minted in this process, never accepted from a request, so this is a defect rather
            // than bad input — said out loud instead of silently widening the read to the whole
            // topic, which is the "a scan that lies" shape the search budgets are written against.
            log.warn("Unparseable '{}' read mode — falling back to the most recent records",
                LogSafe.text(readMode));
            return null;
        }
    }

    /**
     * One direct-reader fetch, and the place two sides of one metric can share it.
     *
     * <p>Two aggregates over the <em>same</em> topic — two counts under different WHERE clauses,
     * which is what a {@code TOPIC_COUNT_DELTA} on one topic is — each downloaded and parsed up to
     * {@link #AGGREGATE_SCAN_RECORDS} records, thirty seconds apart. The per-cycle memoization one
     * layer up keys on the SQL, so it never brought them together. The slot is opened by
     * {@link #executeSqlPair} around the pair and closed after it, so nothing outside that pair can
     * observe a record it did not read itself.
     *
     * <p>Restricted to aggregates on purpose: their fetch size is the constant above whatever the
     * statement says, so there is no larger-read-serving-a-smaller-one to reason about, and a
     * projection stops early at its own row limit, which would leave a partial list behind for the
     * other side. Sharing also makes the two counts describe the <em>same instant</em>, which is
     * the whole of D4 for that case — the same dissolution the offsets count gets.
     */
    private List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> fetchForDirectRead(
            String topic, String readMode, int fetch, boolean shareable) {
        PairScan slot = pairScan.get();
        if (slot != null && shareable) {
            SharedScan open = slot.scan;
            if (open != null && open.topic().equals(topic) && Objects.equals(open.readMode(), readMode)
                && open.fetch() >= fetch) {
                slot.reuses++;
                return open.records();
            }
        }
        Long since = sinceTimestampOf(readMode);
        List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> records =
            since != null
                ? kafkaAdminService.getRecordsSinceTimestamp(topic, since, fetch)
                : "earliest-offset".equals(readMode)
                    ? kafkaAdminService.getEarliestRecords(topic, fetch)
                    : kafkaAdminService.getRecentRecords(topic, fetch);
        if (slot != null && shareable && slot.scan == null) {
            slot.scan = new SharedScan(topic, readMode, fetch, records);
        }
        return records;
    }

    private record SharedScan(String topic, String readMode, int fetch,
                              List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> records) {}

    /** Mutable because the reuse has to be reported back to the caller, not merely performed. */
    private static final class PairScan {
        SharedScan scan;
        int reuses;
    }

    /** Open only for the duration of {@link #executeSqlPair}, on the calling thread. */
    private final ThreadLocal<PairScan> pairScan = new ThreadLocal<>();

    /**
     * Two statements, run in the order given, sharing one topic read when they can.
     *
     * @param sharedScan whether the second statement was answered from the first one's records,
     *                   which is what lets the caller say the two describe the same instant
     *                   instead of one being a whole query older than the other.
     */
    public record QueryPair(QueryResult first, QueryResult second, boolean sharedScan) {}

    /**
     * Run two statements as a pair. The order is the caller's: a count delta reads its right side
     * first so that traffic in between can only overstate the gap, and this method does not
     * second-guess that. Sharing is decided inside {@link #fetchForDirectRead} on what the two
     * reads turn out to be, not on what the SQL looks like from here.
     */
    public QueryPair executeSqlPair(QueryRequest first, QueryRequest second) {
        PairScan slot = new PairScan();
        pairScan.set(slot);
        try {
            QueryResult a = executeSql(first);
            QueryResult b = executeSql(second);
            return new QueryPair(a, b, slot.reuses > 0);
        } finally {
            pairScan.remove();
        }
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
        if (WINDOW_TABLE_CALL.matcher(sql).find()) {
            return kafkaWindowSelect(sql, readMode, limit, startTime);
        }

        // Detect aggregate functions in the SELECT portion only (before FROM)
        boolean isAggregate = AGGREGATE_PRESENT.matcher(sql.substring(0, fromMatcher.start())).find();

        // Respect LIMIT from SQL if smaller than configured limit (skip for aggregates)
        if (!isAggregate) {
            Matcher limitMatcher = LIMIT_CLAUSE.matcher(sql);
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

        // Parse selected columns and WHERE conditions from SQL (needed to size the fetch)
        List<String> requestedCols = isAggregate ? Collections.emptyList() : extractSelectedColumns(sql);
        Map<String, String> whereConds = extractSimpleWhere(sql);
        List<String> whereWarnings = unsupportedWhereFragments(sql);

        // For aggregates, read all available messages. For plain projections, a small
        // overshoot over the limit is enough — but when a WHERE clause filters rows,
        // matches may sit far beyond limit+20 messages, so scan a much larger slice
        // (the row loop still stops as soon as `limit` matches are collected).
        int fetch;
        if (isAggregate) {
            fetch = AGGREGATE_SCAN_RECORDS;
        } else if (whereConds.isEmpty()) {
            fetch = limit + 20;
        } else {
            fetch = Math.min(100_000, Math.max(5_000, limit * 100));
        }
        List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> records =
            fetchForDirectRead(topic, readMode, fetch, isAggregate);

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
                for (String colExpr : requestedCols) {
                    // Handle "source_col AS alias" — fetch by source name, output under alias
                    String[] aliasParts = colExpr.split("(?i)\\s++AS\\s++", 2);
                    String sourceCol = aliasParts[0].trim();
                    String outputCol = aliasParts.length > 1 ? aliasParts[1].trim() : sourceCol;
                    projected.put(outputCol, toSerializable(getNestedValue(row, sourceCol)));
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
            QueryResult aggregate = kafkaAggregateSelect(sql, rows, startTime);
            // The caveats of the WHERE clause were dropped on this branch and kept on the other,
            // so an aggregate filtered by a predicate this reader could not apply came back as a
            // precise-looking number over unfiltered rows.
            for (String warning : whereWarnings) {
                aggregate = withExtraWarning(aggregate, warning);
            }
            /*
             * An aggregate that filled its own ceiling is a floor, and it has to say so.
             *
             * The scan stops at AGGREGATE_SCAN_RECORDS, so a COUNT(*) over a larger topic returns
             * that number and looks exactly like a total. Two such counts compared — which is what
             * a TOPIC_COUNT_DELTA metric does — then differ by nothing, and "no gap" is the one
             * answer a silent-drop alarm must never give by accident. The caveat travels with the
             * result rather than being left for whoever reads it to infer.
             */
            if (records.size() >= fetch) {
                aggregate = withExtraWarning(aggregate, AGGREGATE_SCAN_CAPPED
                    + " — the aggregate covers the first " + fetch + " record(s) read from '"
                    + topic + "', not the whole topic, so a count is a floor rather than a total.");
            }
            return aggregate;
        }

        List<String> columns = requestedCols.isEmpty()
            ? new ArrayList<>(colSet)
            : requestedCols.stream().map(c -> {
                String[] p = c.split("(?i)\\s++AS\\s++", 2);
                return p.length > 1 ? p[1].trim() : p[0].trim();
            }).collect(Collectors.toList());
        log.debug("[KafkaDirect] topic='{}' rows={} readMode={}", topic, rows.size(), readMode);
        return new QueryResult(columns, rows, System.currentTimeMillis() - startTime, null, false, "KAFKA_DIRECT")
            .withWarnings(whereWarnings);
    }

    /**
     * Computes SQL aggregate functions (COUNT/SUM/AVG/MAX/MIN) over pre-fetched Kafka rows.
     * Supports optional GROUP BY with one or more simple column names.
     * Each aggregate in the SELECT must have an alias (e.g. COUNT(*) AS metric_value).
     */
    private QueryResult kafkaAggregateSelect(String sql, List<Map<String, Object>> inputRows, long startTime) {
        // Parse SELECT portion (everything between SELECT and FROM)
        Matcher selMatcher = SELECT_PROJECTION.matcher(sql);
        if (!selMatcher.find()) {
            return new QueryResult(Collections.emptyList(), Collections.emptyList(),
                System.currentTimeMillis() - startTime, "Cannot parse SELECT clause for aggregate query");
        }
        String selectPart = selMatcher.group(1);

        // Each aggregate entry: [func, col, alias, distinct("true"/"false")]
        List<String[]> aggs = new ArrayList<>();
        Matcher am = AGGREGATE_CALL.matcher(selectPart);
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
                    // Resolve nested JSON / flattened XML paths (and case) like WHERE does, instead
                    // of a direct key lookup that would collapse nested paths into a single group.
                    .map(c -> {
                        Object v = getNestedValue(row, c);
                        if (v == null) v = findValueIgnoreCase(row, c);
                        return v == null ? "" : String.valueOf(v);
                    })
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
        log.debug("[KafkaDirect/Agg] inputRows={} groups={} cols={}",
                 inputRows.size(), resultRows.size(), columns);
        // Aggregates are computed over rows the caller already filtered, but the caveat about
        // predicates that were never applied still belongs on this result.
        return new QueryResult(columns, resultRows, System.currentTimeMillis() - startTime, null, false, "KAFKA_DIRECT")
            .withWarnings(unsupportedWhereFragments(sql));
    }

    /**
     * Emulates Flink windowed aggregations over Kafka messages fetched directly, without the
     * Flink planner.
     *
     * Supported syntax:
     *   SELECT window_start, window_end, AGG(...) AS alias
     *   FROM TABLE(TUMBLE(TABLE &lt;topic&gt;, DESCRIPTOR(&lt;time_col&gt;), INTERVAL '&lt;n&gt;' MINUTE|HOUR|SECOND|DAY))
     *   [WHERE ...] GROUP BY window_start, window_end
     *
     * <p>HOP, CUMULATE and SESSION parse too but are <em>approximated</em> as tumbling windows of
     * the same size — this reader buckets by timestamp and emulates neither overlap nor inactivity
     * gaps. The approximation is reported in {@link QueryResult#warnings()} rather than left for
     * the reader to discover: only the Flink planner gives those windows their real semantics.
     * They previously reached this method (the caller routes them here) and died on a TUMBLE-only
     * regex with "Cannot parse TUMBLE syntax", which described neither the cause nor the fix.
     *
     * Time column resolution order:
     *   1. Parsed from the message field named &lt;time_col&gt; (ISO-8601 string or epoch millis/seconds)
     *   2. Kafka record timestamp (fallback)
     */
    private QueryResult kafkaWindowSelect(String sql, String readMode, int limit, long startTime) {
        Matcher window = WINDOW_CALL.matcher(sql);
        if (!window.find()) {
            return new QueryResult(Collections.emptyList(), Collections.emptyList(),
                System.currentTimeMillis() - startTime,
                "Cannot parse the window function. Expected: "
                    + "TABLE(TUMBLE(TABLE <name>, DESCRIPTOR(<time_col>), INTERVAL '<n>' MINUTE))");
        }
        String windowFn    = window.group(1).toUpperCase(Locale.ROOT);
        String tableName   = window.group(2);
        String timeCol     = window.group(3);

        // HOP(slide, size) and CUMULATE(step, max) carry two intervals; the bucket width is the
        // last one. TUMBLE and SESSION carry a single one.
        List<Long> parsed = new ArrayList<>();
        List<String> rendered = new ArrayList<>();
        Matcher scan = WINDOW_INTERVAL.matcher(window.group(4));
        while (scan.find()) {
            long amount = Long.parseLong(scan.group(1));
            String unit = scan.group(2).toUpperCase(Locale.ROOT);
            parsed.add(amount * switch (unit) {
                case "SECOND" -> 1_000L;
                case "HOUR"   -> 3_600_000L;
                case "DAY"    -> 86_400_000L;
                default       -> 60_000L;
            });
            rendered.add(amount + " " + unit.toLowerCase(Locale.ROOT) + (amount > 1 ? "s" : ""));
        }
        if (parsed.isEmpty()) {
            return new QueryResult(Collections.emptyList(), Collections.emptyList(),
                System.currentTimeMillis() - startTime,
                "The window function carries no INTERVAL the direct engine understands "
                    + "(expected MINUTE, HOUR, SECOND or DAY).");
        }
        long intervalMs = parsed.get(parsed.size() - 1);

        List<String> windowWarnings = new ArrayList<>();
        if (!"TUMBLE".equals(windowFn)) {
            windowWarnings.add("The direct engine approximated " + windowFn + " as a tumbling window of "
                + rendered.get(rendered.size() - 1) + ": it buckets by timestamp and emulates neither "
                + "overlapping windows nor inactivity gaps. Only the Flink engine gives " + windowFn
                + " its real semantics.");
        }

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
            fetchForDirectRead(topic, readMode, 100_000, false);

        // Parse aggregate expressions from SELECT
        Matcher selMatcher = SELECT_PROJECTION.matcher(sql);
        if (!selMatcher.find()) {
            return new QueryResult(Collections.emptyList(), Collections.emptyList(),
                System.currentTimeMillis() - startTime, "Cannot parse SELECT clause");
        }
        List<String[]> aggs = new ArrayList<>(); // [func, col, alias, distinct]
        Matcher am = AGGREGATE_CALL.matcher(selMatcher.group(1));
        while (am.find()) {
            String func  = am.group(1).toUpperCase();
            String dist  = am.group(2) != null ? "true" : "false";
            String col   = am.group(3).trim().replace("`", "").replace("\"", "");
            String alias = am.group(4) != null ? am.group(4)
                         : func.toLowerCase() + "_" + col.replace("*", "all");
            aggs.add(new String[]{func, col, alias, dist});
        }

        Map<String, String> whereConds = extractSimpleWhere(sql);
        List<String> whereWarnings = unsupportedWhereFragments(sql);

        // Bucket messages by tumbling window
        Map<Long, List<Map<String, Object>>> windows = new LinkedHashMap<>();
        for (var record : records) {
            String value = record.value();
            if (value == null || value.isBlank()) continue;
            Map<String, Object> row = parseMessageToRow(value);
            if (row.isEmpty()) continue;
            if (!matchesWhereConditions(row, whereConds)) continue;

            // Resolve timestamp: message field → epoch detection → Kafka record ts
            long tsMillis = parseEventTimeMillis(row.get(timeCol), record.timestamp());

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
        log.debug("[KafkaDirect/Window] topic='{}' timeCol='{}' intervalMs={} windows={} rows={}",
                 LogSafe.name(topic), LogSafe.name(timeCol), intervalMs, windows.size(), resultRows.size());
        // The HOP/SESSION approximation travels with the rows, alongside any ignored predicate.
        List<String> allWarnings = new ArrayList<>(windowWarnings);
        allWarnings.addAll(whereWarnings);
        return new QueryResult(columns, resultRows, System.currentTimeMillis() - startTime, null, false, "KAFKA_DIRECT")
            .withWarnings(allWarnings);
    }

    /**
     * Resolves an event-time value (message field) to epoch millis, falling back to the Kafka
     * record's own timestamp. The rule lives in {@link EventTime}: it was written here and again,
     * identically, in {@code MetricService} — this copy even said so, "mirroring the metric
     * engine" — and the process model needed a third caller.
     */
    private long parseEventTimeMillis(Object tsVal, long fallback) {
        return EventTime.toEpochMillis(tsVal, fallback);
    }

    /** Evaluates a single aggregate function over a list of rows. */
    private Object evalAggregate(String func, String col, boolean distinct, List<Map<String, Object>> rows) {
        if ("COUNT".equals(func)) {
            // Counts are integral — returning double made the UI display "42.0"
            if ("*".equals(col)) return (long) rows.size();
            if (distinct) {
                return (long) rows.stream()
                    .map(r -> r.get(col)).filter(v -> v != null)
                    .map(Object::toString)
                    .collect(Collectors.toSet()).size();
            }
            return rows.stream().filter(r -> r.get(col) != null).count();
        }
        List<Double> nums = rows.stream()
            .map(r -> asDouble(r.get(col)))
            .filter(Objects::nonNull)
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

    /**
     * Coerces an aggregate operand to a double. Handles genuine numbers and numeric values stored
     * as strings — XML payloads flatten every field to text, and JSON numbers are sometimes quoted,
     * so without this SUM/AVG/MAX/MIN over those topics would silently return null.
     */
    private Double asDouble(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** Package-private: driven directly by {@code FlinkSqlServiceTest}. */
    List<String> extractSelectedColumns(String sql) {
        Matcher m = SELECT_PROJECTION.matcher(sql.trim());
        if (!m.find()) return Collections.emptyList();
        String cols = m.group(1).trim();
        if (cols.equals("*")) return Collections.emptyList();
        return Arrays.stream(cols.split(","))
            .map(c -> c.replace("`", "").trim())
            .filter(c -> !c.isEmpty())
            .collect(Collectors.toList());
    }

    /** WHERE body, stopping before the clauses that follow it — otherwise GROUP BY looks unsupported. */
    private static final Pattern WHERE_WARNING_BLOCK = Pattern.compile(
        "(?i)\\bWHERE\\s+(.+?)(?:\\bGROUP\\s+BY\\b|\\bORDER\\s+BY\\b|\\bHAVING\\b|\\bLIMIT\\b|;|$)",
        Pattern.DOTALL);

    /** Package-private: driven directly by {@code FlinkSqlServiceTest}. */
    Map<String, String> extractSimpleWhere(String sql) {
        Map<String, String> conditions = new LinkedHashMap<>();
        Pattern whereBlock = Pattern.compile("(?i)\\bWHERE\\s+(.+?)(?:\\bLIMIT\\b|;|$)", Pattern.DOTALL);
        Matcher wm = whereBlock.matcher(sql);
        if (!wm.find()) return conditions;
        String whereClause = wm.group(1).trim();
        // Keep the original case of the column name: message fields are case-sensitive
        // (e.g. "orderId") and lowercasing the key would make every lookup miss.
        // Dots are allowed so nested JSON / flattened XML paths can be filtered.
        Pattern condPattern = Pattern.compile("(?i)`?+([\\w.]++)`?+\\s*+=\\s*+'([^']*+)'");
        Matcher cm = condPattern.matcher(whereClause);
        while (cm.find()) {
            conditions.put(cm.group(1), cm.group(2));
        }
        return conditions;
    }

    /**
     * Predicates the direct engine silently drops. {@link #extractSimpleWhere} only understands
     * {@code column = 'value'} joined by AND; anything else — a comparison, LIKE, IN, OR, NOT —
     * never matches its pattern, so the rows come back unfiltered and the result looks precise
     * when it is not. Surfacing what was ignored is the difference between a narrow engine and a
     * wrong answer.
     */
    List<String> unsupportedWhereFragments(String sql) {
        Matcher wm = WHERE_WARNING_BLOCK.matcher(sql);
        if (!wm.find()) {
            return List.of();
        }
        String remainder = wm.group(1)
            .replaceAll("(?i)`?+[\\w.]++`?+\\s*+=\\s*+'[^']*+'", " ")   // supported conditions
            .replaceAll("(?i)\\bAND\\b", " ")                     // supported combinator
            .replaceAll("[()]", " ")
            .replaceAll("\\s+", " ")
            .trim();
        if (remainder.isEmpty()) {
            return List.of();
        }
        return List.of("The direct engine applied only the \"column = 'value'\" conditions of this "
            + "WHERE clause. Ignored: \"" + remainder + "\" — rows that do not satisfy it may appear "
            + "in the result.");
    }

    private boolean matchesWhereConditions(Map<String, Object> row, Map<String, String> conditions) {
        for (Map.Entry<String, String> cond : conditions.entrySet()) {
            Object val = getNestedValue(row, cond.getKey());
            if (val == null) {
                val = findValueIgnoreCase(row, cond.getKey());
            }
            if (val == null) return false;
            if (!val.toString().equalsIgnoreCase(cond.getValue())) return false;
        }
        return true;
    }

    /** Last-resort lookup for WHERE conditions whose case doesn't match the message fields. */
    private Object findValueIgnoreCase(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    /**
     * What a cancellation request actually achieved.
     *
     * <p>{@code cancelQuery} used to return {@code void}, and the endpoint above it answered 200
     * either way, so a caller could not tell "the Flink job was cancelled" from "there was nothing
     * to cancel". The distinction is not academic here: a {@code KAFKA_DIRECT} scan has no Flink
     * job <em>by construction</em>, so the honest message depends on which engine ran — and the UI
     * had already had to learn, on its own side, not to claim more than it did.
     */
    public enum CancelOutcome {
        /** A live {@code JobClient} was found and asked to cancel. */
        CANCELLED,
        /** No live job under that id: already finished, never a Flink job, or an unknown id. */
        NO_ACTIVE_JOB
    }

    public CancelOutcome cancelQuery(String queryId) {
        JobInfo info = heldJobs.get(queryId);
        if (info != null) {
            info.markCancelRequested();
            persistJobSnapshot(info, buildJobSummary(info), "Cancellation requested by user", null);
            if (requestCancel(info)) {
                return CancelOutcome.CANCELLED;
            }
            // The job had already finished, taking its MiniCluster with it. Nothing was cancelled,
            // so saying CANCELLED would be the very claim this enum exists to prevent — and the
            // unguarded `cancel()` that used to sit here answered the Stop button with a 500
            // instead, on the most ordinary race there is: pressing Stop as the query completes.
            info.markEnded(System.currentTimeMillis());
            heldJobs.remove(queryId);
            persistJobSnapshot(info, buildJobSummary(info),
                "Cancellation requested, but the Flink job had already finished", null);
            return CancelOutcome.NO_ACTIVE_JOB;
        }
        // A job that already ended keeps the status it ended with. Writing UNKNOWN here — which is
        // what this did — erased how the job finished, in the store and in the history the details
        // endpoint serves, on the most ordinary gesture there is: pressing Kill on a dashboard card
        // whose job completed between two five-second polls. The attempt is still recorded, as a
        // history entry under the status that was actually observed; only the verdict is left
        // alone. `null` status means "keep what is there" to the store.
        boolean alreadyEnded = flinkJobStore.findById(queryId)
            .map(job -> FlinkJobStore.isTerminal(job.status()))
            .orElse(false);
        flinkJobStore.update(
            queryId,
            alreadyEnded ? null : "UNKNOWN",
            alreadyEnded
                ? "Cancellation requested after the job had already ended"
                : "Cancellation requested but no live Flink JobClient was available",
            null,
            true,
            System.currentTimeMillis(),
            null,
            null
        );
        return CancelOutcome.NO_ACTIVE_JOB;
    }

    public CancelOutcome cancelJob(String queryId) {
        return cancelQuery(queryId);
    }

    public List<FlinkJobSummary> getActiveJobs() {
        syncPersistedJobs();
        return flinkJobStore.listActive().stream()
            .map(FlinkManagedJobDetails::toSummary)
            .toList();
    }

    public List<FlinkJobSummary> listRecentJobs() {
        syncPersistedJobs();
        return flinkJobStore.listAll().stream()
            .map(FlinkManagedJobDetails::toSummary)
            .toList();
    }

    public Optional<FlinkManagedJobDetails> getJob(String queryId) {
        syncPersistedJobs();
        return flinkJobStore.findById(queryId);
    }

    /**
     * The live registry, reconciled — <em>not</em> the store's idea of what is active.
     *
     * <p>Named {@code getActiveJobsDetails} while {@link #getActiveJobs()} sat beside it answering
     * a different question from a different source: this one is the {@code JobClient}s the runtime
     * holds, that one is the job records the store does not count as terminal. A suffix is not a
     * distinction, and the three callers here act on the answer.
     */
    public Map<String, JobInfo> getHeldJobs() {
        // Reconcile first, exactly as getActiveJobs() does. This method used to skip it, and the
        // asymmetry was not cosmetic: a finished query stays in `heldJobs` until *something*
        // sweeps it, and the three callers here are the ones that act on the answer —
        // `POST /api/config` counts them to refuse a cluster repoint with 409, the lineage graph
        // draws a node per job, and the KPI suggestions derive a pipeline edge from each. A query
        // the user ran and finished would therefore go on refusing their next config save until
        // some *other* screen happened to call getActiveJobs(). It stayed invisible because the
        // dashboard polls that sibling every 30 s, so a browser being open hid it — which is
        // exactly why it surfaced the day a probe ran with no browser open at all.
        //
        // Affordable: syncPersistedJobs() returns immediately on an empty map, and none of these
        // callers is on a timer — each is a user gesture, where the ≤150 ms status poll per live
        // job is not worth trading correctness for.
        syncPersistedJobs();
        // Defensive snapshot — callers only read; never hand out the live internal map.
        return Map.copyOf(heldJobs);
    }

    @PreDestroy
    public void shutdown() {
        // Shared deadline, not another private five seconds — see ShutdownBudget.
        ShutdownBudget.shutdown(queryExecutor);
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

    /**
     * Asks a job to stop, and says whether there was still a job to ask.
     *
     * <p>{@code JobClient.cancel()} is not safe to call on a job that has ended: the embedded
     * runtime gives each job its own MiniCluster and shuts it down when the job reaches a terminal
     * state, so the call throws {@code IllegalStateException("MiniCluster is not yet running or
     * has already been shut down")} — Flink's own {@code Preconditions.checkState}, thrown
     * synchronously rather than handed back in the future. That exception is not a failure to
     * cancel; it is the runtime saying there is nothing left to cancel, which is a legitimate
     * outcome of a well-formed request and is what {@link CancelOutcome#NO_ACTIVE_JOB} is for.
     */
    private boolean requestCancel(JobInfo info) {
        try {
            info.client().cancel();
            return true;
        } catch (IllegalStateException gone) {
            log.debug("[FlinkSQL] queryId={} had nothing left to cancel: {}",
                info.queryId(), gone.getMessage());
            return false;
        }
    }

    /**
     * Best-effort cancellation on the cleanup paths — a timeout, or a query that failed.
     *
     * <p>Everything is swallowed here, and that is the point: this runs from inside a {@code catch}
     * and from the timeout branch, where the caller already holds the answer it owes the user. A
     * job that has just failed has already taken its MiniCluster down, so the unguarded
     * {@code cancel()} threw {@code IllegalStateException} <em>out of the catch block</em> — the
     * real error, and the "query timed out" result with its remedy, were both replaced by a stack
     * trace about a MiniCluster the user has no idea they are running.
     */
    private void cancelJobInternal(TableResult result) {
        if (result == null || result.getJobClient().isEmpty()) return;
        try {
            result.getJobClient().get().cancel();
        } catch (Exception e) {
            log.debug("[FlinkSQL] nothing to cancel while cleaning up: {}", e.getMessage());
        }
    }
}
