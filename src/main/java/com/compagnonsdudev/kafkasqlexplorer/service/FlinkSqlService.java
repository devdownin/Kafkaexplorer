// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.domain.ChangelogInfo;
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
import org.apache.flink.table.api.internal.TableEnvironmentImpl;
import org.apache.flink.table.operations.CreateTableASOperation;
import org.apache.flink.table.operations.Operation;
import org.apache.flink.types.Row;
import org.apache.flink.types.RowKind;
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
     * <p><b>It is the only registry left, and that is the point.</b> A {@code FlinkJobStore}
     * used to sit beside it, persisting a record per statement to {@code data/} so a dashboard
     * table could list what had run. That table is gone — nothing submits a job over HTTP any
     * more — and the store went with it rather than staying a file rewritten on every planner
     * answer for no reader. What the map answers is a live question, in memory: which
     * {@code JobClient}s does this process hold right now.
     *
     * <p><b>Two populations, two lifetimes, and knowing which is which is the point.</b> A job
     * from {@link #submitJob} stays here for as long as it runs, which may be days — that is
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
     * <p>Deliberately not {@code UNKNOWN}, which is what a job whose MiniCluster has been shut
     * down under it carries and which the sweep therefore reads as the job being over. A status
     * poll that timed out is neither: reusing UNKNOWN for it meant a single slow answer ended a
     * running job on paper, dropping it out of the map that {@code POST /api/query/cancel} and
     * the 409 guard both read.
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
     * Combien de temps le disjoncteur reste fermé avant de laisser passer une tentative.
     *
     * <p>Il latchait pour la vie du processus, et « redémarrez l'application » était la seule
     * sortie. C'était défendable tant que la cause supposée était un défaut de version de Flink :
     * une panne qui ne se répare pas toute seule. Ce dépôt a maintenant vu l'inverse — un job qui
     * n'obtenait pas ses emplacements, c'est-à-dire une panne d'<em>environnement</em>, qui
     * disparaît dès que la configuration est corrigée — et exige alors un redémarrage pour une
     * chose déjà réparée.
     *
     * <p>Ce que le délai achète est borné et se compte : passé l'intervalle, une tentative est
     * autorisée. Elle réussit, le disjoncteur et le compteur sont remis à zéro ; elle échoue,
     * l'horodatage est réarmé et rien n'a coûté de plus qu'une tentative par intervalle. Plusieurs
     * threads peuvent franchir la porte ensemble et en payer chacun une : c'est borné, et
     * l'alternative — un verrou sur le chemin de lecture — coûterait plus cher que ce qu'elle
     * évite. Une constante et non une propriété : aucun déploiement connu n'a besoin d'une autre
     * valeur, et une propriété que personne ne règle est une surface de configuration de plus.
     */
    /** Package-private: {@code FlinkSqlServiceTest} drives the decay against it. */
    static final long FLINK_SELECT_RETRY_AFTER_MS = 10 * 60 * 1000L;

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

    /** Every table a statement reads: after FROM, and after each JOIN. */
    private static final Pattern SOURCE_TABLE = Pattern.compile(
        "(?i)\\b(?:FROM|JOIN)\\s+[`\"]?([\\w.\\-]+)[`\"]?");

    /**
     * Names the pattern above can match that are keywords rather than tables.
     *
     * <p>{@code FROM TABLE(TUMBLE(TABLE orders, …))} yields {@code TABLE}, and {@code FROM
     * LATERAL TABLE(…)} / {@code FROM UNNEST(…)} the same way. The window call carries the real
     * name and is read separately, exactly as {@link #extractPrimaryTable} does.
     */
    private static final Set<String> NOT_A_TABLE_NAME = Set.of("TABLE", "LATERAL", "UNNEST");

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
    /** The row cap written in the statement itself. */
    private static final Pattern LIMIT_CLAUSE = Pattern.compile("(?i)\\bLIMIT\\s+(\\d+)");

    private final java.util.concurrent.atomic.AtomicInteger flinkSelectFailures = new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile boolean flinkSelectDisabled = false;
    /** Quand le disjoncteur a latché, pour que {@link #FLINK_SELECT_RETRY_AFTER_MS} soit lisible. */
    private volatile long flinkSelectDisabledAt = 0L;

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
                           DdlGeneratorService ddlGeneratorService, FlinkTableStore flinkTableStore) {
        this.tableEnv = tableEnv;
        this.runtimeCoordinator = runtimeCoordinator;
        this.explorerConfig = explorerConfig;
        this.sqlQueryValidator = sqlQueryValidator;
        this.kafkaAdminService = kafkaAdminService;
        this.schemaInferenceService = schemaInferenceService;
        this.ddlGeneratorService = ddlGeneratorService;
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
        // EXPLAIN, SHOW et DESCRIBE ne touchent pas au catalogue : ils prennent le verrou partagé,
        // pas celui de mutation. Prendre le verrou d'écriture pour lister des tables sérialiserait
        // le geste le plus banal de l'éditeur derrière la requête en cours.
        if ("EXPLAIN".equals(statementType) || "SHOW".equals(statementType) || "DESCRIBE".equals(statementType)
                || "DESC".equals(statementType)) {
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
    /** Package-private: driven directly by {@code FlinkSqlServiceTest}, like {@link #stripSqlComments}. */
    String extractPrimaryTable(String sql) {
        // Hors littéraux : `WHERE note = 'select * from autre'` faisait autrement lire « autre »
        // comme la table de la requête — donc chercher un topic de ce nom, et l'enregistrer s'il
        // existe. Ce que les groupes capturent est identique au texte d'origine, les positions
        // étant conservées et un nom de table ne pouvant pas se trouver dans un littéral.
        String scan = SqlStatements.outsideLiterals(sql);
        Matcher window = WINDOW_CALL.matcher(scan);
        if (window.find()) return window.group(2);
        Matcher from = FROM_TABLE.matcher(scan);
        return from.find() ? from.group(1) : null;
    }

    /**
     * Every table a statement reads, the primary one first.
     *
     * <p>Auto-registration used to consult {@link #extractPrimaryTable} alone, so a statement
     * reading two topics registered the first and let the planner answer "Object not found" about
     * the second — a name that is perfectly correct, on the shape a JOIN <em>is</em>. It cost most
     * in Flink Job mode, which has no direct reader to catch the query, and it was invisible in
     * read mode for the same reason it was invisible everywhere: the fallback returned rows, from
     * a reader that ignores the join entirely.
     *
     * <p>The primary table stays first because it is the one the direct reader will read if the
     * query falls back — {@code deferToDirect} is decided on it and on nothing else.
     *
     * <p>An INSERT's <strong>target</strong> is excluded by construction: the pattern matches
     * {@code FROM} and {@code JOIN}, never {@code INSERT INTO}. That is deliberate — deriving a
     * sink schema from an empty target topic yields {@code raw_value STRING} and an arity failure,
     * which is a worse answer than "unknown table".
     */
    List<String> extractSourceTables(String sql) {
        Set<String> names = new LinkedHashSet<>();
        String scan = SqlStatements.outsideLiterals(sql);
        String primary = extractPrimaryTable(sql);
        if (primary != null && !NOT_A_TABLE_NAME.contains(primary.toUpperCase(Locale.ROOT))) {
            names.add(primary);
        }
        Matcher window = WINDOW_CALL.matcher(scan);
        while (window.find()) names.add(window.group(2));
        Matcher source = SOURCE_TABLE.matcher(scan);
        while (source.find()) {
            String name = source.group(1);
            if (!NOT_A_TABLE_NAME.contains(name.toUpperCase(Locale.ROOT))) names.add(name);
        }
        return List.copyOf(names);
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
        //
        // `INSERT INTO … SELECT` entre ici pour la même raison, et c'est le mode Job qui en avait
        // besoin : `submitJob` ne passait par aucun enregistrement, donc le raccourci que la barre
        // latérale propose elle-même — un INSERT depuis la table d'un topic — répondait « Object
        // not found » tant qu'un SELECT n'était pas venu enregistrer la source d'abord. Seule la
        // *source* est concernée : `extractPrimaryTable` lit le FROM, donc la cible de l'INSERT
        // est exclue par construction, et c'est voulu — dériver un schéma d'un topic cible vide
        // rendrait `raw_value STRING` et un échec d'arité, pire qu'un « table inconnue ».
        //
        // « INSERT » et non « INSERT INTO » : le garde du mode Job classe l'instruction sur son
        // premier mot, donc `INSERT OVERWRITE` le franchit — et il arrivait ici pour se voir
        // refuser l'enregistrement, si bien que sa source répondait « Object not found » sur un
        // nom de topic parfaitement correct. C'est exactement le défaut corrigé au paragraphe
        // ci-dessus pour `INSERT INTO`, resté debout à un mot-clé près : ce que le mode Job
        // accepte de soumettre, cette méthode doit accepter de servir.
        String body = SqlStatements.classifiableBody(sql);
        // `DESCRIBE <topic>` est la première chose qu'on tape en arrivant d'un client SQL, et sans
        // cette branche elle répondait « table inconnue » sur un topic parfaitement présent — il
        // fallait aller faire un SELECT ailleurs pour enregistrer la table, puis revenir. Un objet
        // que ce n'est pas (`DESCRIBE CATALOG …`, `DESCRIBE JOB …`) ne correspond à aucun topic,
        // donc `registerSourceTable` n'y trouve rien à faire et Flink répond comme avant.
        String described = describedObject(sql);
        if (described != null) {
            AutoRegResult one = registerSourceTable(described);
            if (one.error() != null) return one;
            return one.registered() ? AutoRegResult.tableCreated() : AutoRegResult.skip();
        }
        if (!body.startsWith("SELECT") && !body.startsWith("INSERT") && !body.startsWith("EXECUTE STATEMENT SET")
                && !body.startsWith("STATEMENT SET")) {
            return AutoRegResult.skip();
        }
        // The first FROM is inside the CTE body, which is exactly the source table to register.
        List<String> sources = extractSourceTables(sql);
        if (sources.isEmpty()) return AutoRegResult.skip();

        // Chaque source est enregistrée, pas seulement la première : une jointure entre deux
        // topics non enregistrés répondait « Object not found » sur le second. Le repli sur le
        // lecteur direct, lui, ne se décide que sur la source *primaire* — c'est la seule que ce
        // lecteur lira, donc c'est la seule dont l'absence de schéma le concerne.
        boolean registeredAny = false;
        boolean primaryDeferred = false;
        for (String rawTableRef : sources) {
            AutoRegResult one = registerSourceTable(rawTableRef);
            if (one.error() != null) return one;
            if (one.registered()) registeredAny = true;
            if (one.deferredToDirectReader() && rawTableRef.equals(sources.get(0))) primaryDeferred = true;
        }
        if (primaryDeferred) return AutoRegResult.deferToDirect();
        return registeredAny ? AutoRegResult.tableCreated() : AutoRegResult.skip();
    }

    /** Ce que `DESCRIBE`/`DESC` décrit, quand c'est un nom de table. Sinon {@code null}. */
    private static final Pattern DESCRIBE_TARGET = Pattern.compile(
        "(?i)^DESC(?:RIBE)?\\s+(?!CATALOG\\b|JOB\\b|FUNCTION\\b|MODEL\\b|SYSTEM\\b)"
            + "[`\"]?([\\w.$-]+)[`\"]?\\s*;?\\s*$");

    /**
     * Le nom qu'un {@code DESCRIBE} désigne, ou {@code null} si l'instruction n'en est pas un.
     *
     * <p>Les formes qui ne décrivent pas une table ({@code DESCRIBE CATALOG}, {@code DESCRIBE JOB})
     * sont exclues nommément : rien ne leur correspondrait de toute façon, mais s'en remettre à
     * cela ferait chercher un topic nommé « CATALOG » à chaque appel.
     */
    private static String describedObject(String sql) {
        if (sql == null) return null;
        Matcher m = DESCRIBE_TARGET.matcher(SqlStatements.withoutLeadingCte(sql).trim());
        return m.matches() ? m.group(1) : null;
    }

    /** One source table: already registered, matched to a topic and registered, or left to Flink. */
    private AutoRegResult registerSourceTable(String rawTableRef) {
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
        return stripComments(sql).trim();
    }

    /**
     * Removes SQL comments in a single left-to-right pass, <b>outside string literals</b>.
     *
     * <p>Two defects, one pass. The first is historical: block comments were stripped by a regex
     * whose unrolled-loop form recursed once per repetition in Java's backtracking engine, so a
     * few thousand unterminated openers raised {@link StackOverflowError} — an {@code Error}, so
     * nothing on the request path caught it. A hand-written scan is linear and shorter.
     *
     * <p>The second is that the scan did not know what a literal was. {@code --} and
     * <code>/*</code> were comment openers wherever they appeared, so
     * {@code WHERE note = 'voir -- plus bas'} lost everything after the quote's contents — the
     * statement was <em>rewritten</em> before anything looked at it, and what reached the planner
     * was a different query (usually an unparseable one, reported as the user's syntax error).
     * The delimiters are found on {@link SqlStatements#outsideLiterals}, whose positions match the
     * original character for character, and the spans are cut out of the original.
     *
     * <p>The rest of the semantics is unchanged: a block runs to the <em>first</em> closing
     * delimiter after it, an unterminated block is not a comment and is left as written (so it
     * cannot swallow the statement), each removed block becomes one space — a comment between two
     * tokens must not weld them together — and a line comment stops at its newline, which stays.
     * A hint is not a comment and is kept whole.
     */
    private static String stripComments(String sql) {
        String scan = SqlStatements.outsideLiterals(sql);
        StringBuilder out = new StringBuilder(sql.length());
        int from = 0;
        int i = 0;
        while (i + 1 < scan.length()) {
            char c = scan.charAt(i);
            char next = scan.charAt(i + 1);
            if (c == '-' && next == '-') {
                int end = scan.indexOf('\n', i);
                if (end < 0) end = scan.length();
                out.append(sql, from, i);
                from = end;
                i = end;
                continue;
            }
            if (c == '/' && next == '*') {
                int close = scan.indexOf("*/", i + 2);
                if (close < 0) break;              // unterminated: keep the rest verbatim
                if (!isHint(scan, i)) {
                    out.append(sql, from, i).append(' ');
                    from = close + 2;
                }
                i = close + 2;
                continue;
            }
            i++;
        }
        return out.append(sql, from, sql.length()).toString();
    }

    /**
     * Un hint SQL — {@code /*+ … *}{@code /} — plutôt qu'un commentaire.
     *
     * <p>C'est la syntaxe de Calcite et de Flink pour les options de table
     * ({@code FROM t /*+ OPTIONS('scan.startup.mode'='earliest-offset') *}{@code /}), et elle est
     * <em>en forme de commentaire</em> : le seul caractère qui l'en distingue est le {@code +} qui
     * suit l'ouverture. Ce nettoyage-ci les effaçait donc tous, en silence, avant que la requête
     * n'atteigne le planner — un hint écrit dans l'éditeur n'a jamais rien fait, et la preuve
     * était dans le journal du moteur lui-même, qui rapportait la requête sans son hint.
     *
     * <p>Ce que cela coûtait dépasse l'éditeur : c'est sur ce mécanisme que reposait l'expérience
     * ayant conclu que ce connecteur refuse {@code scan.bounded.mode}. L'option n'était jamais
     * partie, et la clé réellement refusée dans le même {@code WITH (…)} était une autre.
     *
     * <p>Lue sur le texte dont les littéraux sont neutralisés, comme l'ouverture qu'elle qualifie :
     * les positions y sont les mêmes et un {@code +} hors littéral est bien celui qu'on cherche.
     */
    private static boolean isHint(String sql, int open) {
        return open + 2 < sql.length() && sql.charAt(open + 2) == '+';
    }

    /** {@code AS SELECT} / {@code AS WITH} au niveau du statement, littéraux mis à part. */
    private static final Pattern CTAS_TAIL = Pattern.compile("(?i)\\bAS\\s+(?:SELECT|WITH)\\b");

    /**
     * Un {@code CREATE TABLE … AS SELECT}, et non un {@code CREATE TABLE} ordinaire.
     *
     * <p>Les littéraux sont neutralisés avant le test : une option peut parfaitement contenir le
     * texte {@code 'as select'} ({@code WITH ('note' = 'as select …')}), et refuser un CREATE
     * TABLE à cause du contenu d'une chaîne serait un faux positif sur la seule DDL que cette
     * application accepte. Les commentaires, eux, ont déjà été retirés par l'appelant.
     */
    static boolean isCreateTableAsSelect(String upperSql) {
        if (upperSql == null || !upperSql.startsWith("CREATE TABLE")) return false;
        return CTAS_TAIL.matcher(SqlStatements.outsideLiterals(upperSql)).find();
    }

    /**
     * Un CTAS, demandé au parseur de Flink plutôt qu'à une expression régulière.
     *
     * <p>Le test lexical ci-dessus neutralise les littéraux simple-quote et <strong>pas les
     * identifiants entre accents graves</strong>, si bien qu'il refusait du DDL parfaitement
     * ordinaire : mesuré, {@code CREATE TABLE `weird as select` (id STRING) WITH (…)} et
     * {@code CREATE TABLE t (`col as select` STRING) …} étaient tous deux classés CTAS, donc
     * refusés — avec un message expliquant comment scinder en {@code CREATE TABLE} puis
     * {@code INSERT INTO}, à propos d'un {@code CREATE TABLE} qui n'avait rien à scinder. C'est le
     * faux positif qui compte, sur la seule DDL que cette application accepte.
     *
     * <p>{@code CreateTableASOperation} ne s'y trompe pas, et ce dépôt remplace régulièrement le
     * lexical par le parseur pour cette raison — {@code LineageService.parseWithFlink} est le
     * précédent, garde-fous compris. Ils sont repris ici : le parseur n'est consulté que s'il
     * existe (un {@link TableEnvironment} nu, comme dans certains tests, n'en expose pas), l'accès
     * passe par le verrou de lecture du runtime comme tout autre accès à l'environnement, et
     * <strong>l'échec retombe sur le test lexical</strong>, qui échoue fermé. Un CTAS dont la
     * source n'existe pas ne parse pas : la regex le rattrape et le refus tient.
     *
     * @return {@code null} quand la question n'a pas pu être posée — jamais un verdict inventé
     */
    private Boolean parserSaysCreateTableAsSelect(String sql) {
        if (!(tableEnv instanceof TableEnvironmentImpl impl)) return null;
        try {
            List<Operation> operations =
                runtimeCoordinator.runRead("classify-ctas", () -> impl.getParser().parse(sql));
            if (operations == null || operations.isEmpty()) return null;
            return operations.stream().anyMatch(op -> op instanceof CreateTableASOperation);
        } catch (Exception e) {
            log.debug("Flink could not parse a CREATE TABLE for classification, using the lexical test: {}",
                e.getMessage());
            return null;
        }
    }

    /** Le parseur d'abord, le test lexical quand il n'a pas pu répondre. */
    private boolean isCreateTableAsSelect(String sql, String upperBody) {
        if (upperBody == null || !upperBody.startsWith("CREATE TABLE")) return false;
        Boolean parsed = parserSaysCreateTableAsSelect(sql);
        return parsed != null ? parsed : isCreateTableAsSelect(upperBody);
    }

    private String extractStatementType(String sql) {
        if (sql == null || sql.isBlank()) return "UNKNOWN";
        // Classified past a leading CTE chain, so `WITH … INSERT INTO` and `WITH … SELECT` are
        // routed like the statements they actually are.
        String upper = SqlStatements.classifiableBody(stripSqlComments(sql));
        // Un STATEMENT SET est la façon dont Flink écrit un fan-out : plusieurs INSERT depuis une
        // même source dans **un seul** job, donc une seule lecture du topic. Il est classé avant
        // l'INSERT parce qu'il en contient, et nommé à part parce que le mode Job doit pouvoir le
        // reconnaître sans confondre « plusieurs instructions » et « une instruction composée ».
        if (upper.startsWith("EXECUTE STATEMENT SET") || upper.startsWith("STATEMENT SET")) return "STATEMENT_SET";
        if (upper.startsWith("INSERT INTO")) return "INSERT";
        if (upper.startsWith("CREATE TABLE")) return "CREATE_TABLE";
        if (upper.startsWith("SELECT")) return "SELECT";
        if (upper.startsWith("EXPLAIN")) return "EXPLAIN";
        return upper.split("\\s+", 2)[0];
    }

    /**
     * Reads a held job's status from the runtime and words it.
     *
     * <p>Package-private rather than private, and that is a test decision worth stating: it is
     * where {@code CANCELLING} and {@link #STATUS_UNAVAILABLE} are decided, and until the
     * dashboard's job table was removed those two were reachable from a test through
     * {@code getActiveJobs()}. The only public caller left is {@link #submitJob}, which no HTTP
     * path reaches, so asserting the classification would otherwise mean asserting it through the
     * sweep — which can only say "kept" or "dropped" and cannot tell a job that timed out from one
     * that is running.
     */
    FlinkJobSummary buildJobSummary(JobInfo info) {
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

    /**
     * Drops from {@link #heldJobs} every job the runtime says has ended.
     *
     * <p>It was {@code syncPersistedJobs}, and the name described the half that has been removed:
     * it wrote a snapshot of each held job to {@code FlinkJobStore} on the way past, and the
     * sweep was the side effect. With no store the sweep is the whole method, and it is the part
     * that was load-bearing — a finished query stays in the map until something looks, and three
     * callers act on what the map says.
     *
     * <p>{@link #buildJobSummary} blocks up to 150 ms on the Flink status call per job, so a
     * serial pass over N jobs would block up to N×150 ms; the statuses are polled in parallel and
     * only the removal is done in order. A job whose status could not be read carries
     * {@link #STATUS_UNAVAILABLE} and no {@code endedAt}, so it stays — "we could not ask" is not
     * an answer, and treating it as one is how a running job used to be dropped here.
     */
    private void reapEndedJobs() {
        List<Map.Entry<String, JobInfo>> entries = new ArrayList<>(heldJobs.entrySet());
        if (entries.isEmpty()) return;

        Map<String, FlinkJobSummary> summaries = entries.stream()
            .map(e -> CompletableFuture.supplyAsync(
                () -> Map.entry(e.getKey(), buildJobSummary(e.getValue())), queryExecutor))
            .toList().stream()
            .map(CompletableFuture::join)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        for (Map.Entry<String, JobInfo> e : entries) {
            if (summaries.get(e.getKey()).endedAt() != null) {
                heldJobs.remove(e.getKey());
            }
        }
    }

    /**
     * Les instructions que le mode Job soumet : un INSERT continu, ou un STATEMENT SET qui en
     * réunit plusieurs en un job.
     *
     * <p>Écrite une fois plutôt que deux : {@code executeSync} refuse exactement ce que
     * {@code submitJob} accepte, et deux listes qui divergent laissent une instruction refusée
     * des deux côtés — avec, en mode lecture, le message « Only SELECT, EXPLAIN and CREATE TABLE »
     * qui se lit comme une restriction de sécurité plutôt que comme « à envoyer ailleurs ».
     */
    static boolean isJobModeStatement(String statementType) {
        return "INSERT".equals(statementType) || "STATEMENT_SET".equals(statementType);
    }

    private String prepareSql(String sql) {
        return stripSqlComments(normalizeIdentifierQuotes(sql.trim()));
    }

    /**
     * Runs a statement and returns its rows.
     *
     * <p>An {@code INSERT INTO} is refused here rather than by {@code executeSql}'s whitelist, and
     * the difference is the message: "Only SELECT, EXPLAIN and CREATE TABLE statements are allowed"
     * reads as a security restriction, where the truth is narrower — this application submits no
     * continuous job. It used to point at {@code POST /api/query/jobs}, which was the Flink Job
     * mode of the SQL editor; that mode did not work and has been removed, endpoint included, so a
     * message naming it would send an operator to a door that is no longer there — which is what
     * this one did for a while after the removal, the javadoc having been corrected and the string
     * beneath it left alone.
     */
    public QueryResult executeSync(QueryRequest request) {
        String strippedSql = prepareSql(request.sql());
        if (isJobModeStatement(extractStatementType(strippedSql))) {
            return new QueryResult(
                Collections.emptyList(),
                Collections.emptyList(),
                0,
                "INSERT INTO is not run by this application: it reads, and returns rows. "
                    + "Submitting a continuous job was removed because it did not work — run the "
                    + "pipeline as a Flink job of your own, and read what it produces here."
            );
        }
        return executeSql(request);
    }

    public FlinkJobSummary submitJob(QueryRequest request) {
        long startedAt = System.currentTimeMillis();
        // L'identifiant de l'appelant quand il en fournit un, comme en lecture — `submitJob` en
        // fabriquait un et ne le rendait que dans sa réponse. Si celle-ci se perd (délai réseau,
        // onglet fermé), le job tourne et personne n'a son id : il n'est plus annulable qu'en le
        // reconnaissant à son SQL dans le tableau de bord. `resolveQueryId` refuse ce qui ne peut
        // pas servir de clé de magasin ni de ligne de journal et en fabrique un à la place.
        String queryId = resolveQueryId(request.queryId());
        String strippedSql = prepareSql(request.sql());
        String statementType = extractStatementType(strippedSql);

        if (!isJobModeStatement(statementType)) {
            throw new IllegalArgumentException(JOB_MODE_REFUSAL);
        }

        refuseIfTooManyJobsAreHeld();

        try {
            sqlQueryValidator.validate(strippedSql);

            /*
             * Enregistrer la table source, comme le fait une lecture.
             *
             * Ce chemin ne le faisait pas, si bien que le raccourci proposé par la barre latérale
             * elle-même — un `INSERT INTO … SELECT … FROM <table d'un topic>` — échouait sur
             * « Object not found » tant qu'un SELECT n'était pas venu enregistrer la source dans
             * ce processus. Le mode Job devenait donc dépendant d'un geste fait ailleurs, ce que
             * rien n'indiquait.
             *
             * Un échec est rapporté ici plutôt que laissé remonter en erreur de planner : c'est la
             * même règle qu'en lecture, à une exception près qui compte. `deferToDirect` n'a aucun
             * sens en mode Job — il n'y a pas de lecteur direct pour rattraper la requête — donc
             * l'absence de schéma devient un refus qui nomme sa cause, au lieu d'un « table
             * inconnue » sur un nom parfaitement correct.
             */
            AutoRegResult autoReg = autoRegisterTableIfNeeded(strippedSql);
            if (autoReg.error() != null) {
                throw new IllegalStateException(autoReg.error());
            }
            if (autoReg.deferredToDirectReader()) {
                throw new IllegalArgumentException(
                    "No schema could be inferred for the topic this statement reads, so no Flink "
                  + "table could be registered for it — the topic may be empty, or its messages "
                  + "unreadable. A streaming job needs a typed source: create the table yourself "
                  + "with CREATE TABLE, then submit the INSERT.");
            }

            TableResult result = executeMutationSql("submit-job", strippedSql);
            JobClient client = result.getJobClient()
                .orElseThrow(() -> new IllegalStateException("Flink did not return a JobClient for the submitted job."));

            JobInfo info = new JobInfo(queryId, strippedSql, statementType, "ASYNC_JOB", client, startedAt);
            heldJobs.put(queryId, info);
            return buildJobSummary(info);
        } catch (RuntimeException e) {
            // explainMultiStatementRefusal re-words Flink's "only single statement supported" to
            // name the two ways out. The failure now travels only as this exception: the three
            // `FlinkJobStore.create` calls that used to file it — one here, one on the refusal
            // above, one on success — wrote to a file whose only reader was the dashboard's job
            // table, and that table is gone.
            throw explainMultiStatementRefusal(e);
        }
    }

    /** Ce que {@link #submitJob} n'exécute pas. */
    static final String JOB_MODE_REFUSAL =
        "Only INSERT and STATEMENT SET statements are allowed in Flink Job mode.";

    /**
     * Un job continu tenu de plus n'est pas gratuit, et rien ne le disait.
     *
     * <p>Mesuré sur ce runtime : chaque soumission démarre <strong>son propre MiniCluster</strong>
     * — l'exécution locale n'en partage pas — soit environ <strong>80 threads et 6 Mo de tas par
     * job</strong>, dans le processus qui sert aussi l'interface (six jobs tenus : 482 threads).
     * Ce n'est pas une famine de slots : une lecture pendant un INSERT continu répond toujours par
     * le planner, ce qui a été vérifié avant d'écrire ce garde-fou. C'est un coût qui s'accumule
     * en silence, sur un geste qu'on répète sans y penser depuis l'éditeur.
     *
     * <p>Le refus <strong>nomme le compte et le réglage</strong> plutôt que d'être un plafond muet,
     * et {@code 0} le retire — un opérateur qui sait ce qu'il fait n'a pas à être arrêté ici. Seuls
     * les jobs asynchrones comptent : une lecture synchrone tient aussi son job, mais le temps de
     * sa requête HTTP.
     */
    private void refuseIfTooManyJobsAreHeld() {
        int max = explorerConfig.getMaxConcurrentJobs();
        if (max <= 0) return;
        // getHeldJobs() réconcilie avant de répondre, donc un job terminé ne compte pas.
        long held = getHeldJobs().values().stream()
            .filter(info -> "ASYNC_JOB".equals(info.executionMode()))
            .count();
        if (held < max) return;
        throw new IllegalArgumentException(String.format(
            "This deployment already holds %d streaming job(s), which is the maximum "
                + "(explorer.max-concurrent-jobs = %d). Each one runs its own embedded Flink "
                + "cluster — about 80 threads — inside the process that serves this UI. Stop one "
                + "with POST /api/query/cancel/{queryId}, or raise the setting.", held, max));
    }

    /**
     * « only single statement supported » est la phrase de Flink, et elle ne dit pas quoi faire.
     *
     * <p>Deux instructions collées dans une soumission sont un geste courant, et il y a deux
     * réponses : les lancer l'une après l'autre (`Run all`, que l'éditeur fait déjà) ou en faire
     * un seul job (`STATEMENT SET`, que le mode Job accepte désormais). Le refus les nomme.
     */
    private static RuntimeException explainMultiStatementRefusal(RuntimeException e) {
        String message = SqlErrorClassifier.explain(e);
        if (!message.toLowerCase(Locale.ROOT).contains("only single statement supported")) return e;
        return new IllegalArgumentException(
            "A submission carries one statement, and this one holds several. Run them one after "
                + "another with Run all, or submit them as a single job: "
                + "EXECUTE STATEMENT SET BEGIN <insert>; <insert>; END.", e);
    }

    /**
     * Le résultat avec chaque cellule textuelle passée par le masquage des identifiants.
     *
     * <p>Cellule par cellule plutôt que sur la seule colonne attendue : le nom de la colonne que
     * Flink donne à {@code SHOW CREATE TABLE} n'est pas un contrat, et une réserve écrite sur un
     * nom est une réserve qui saute à la version suivante.
     */
    private static QueryResult maskDdlCells(QueryResult result) {
        List<Map<String, Object>> masked = new ArrayList<>(result.rows().size());
        for (Map<String, Object> row : result.rows()) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.replaceAll((key, value) -> value instanceof String text
                ? DdlGeneratorService.maskSensitiveProperties(text) : value);
            masked.add(copy);
        }
        return new QueryResult(result.columns(), masked, result.durationMs(), result.error(),
            result.tableRegistered(), result.engine(), result.warnings(), result.changelog());
    }

    static final String STATEMENT_NOT_ALLOWED =
        "Only SELECT, EXPLAIN, SHOW, DESCRIBE and CREATE TABLE statements are allowed.";

    /**
     * Les formes de {@code SHOW} que ce moteur sert. Liste close, et c'est le fond du garde-fou :
     * la whitelist classe sur le premier mot, donc admettre « tout ce qui commence par SHOW »
     * admettrait aussi ce que la prochaine version de Flink mettra derrière ce mot-clé.
     */
    private static final Set<String> SHOW_FORMS = Set.of(
        "CATALOGS", "CURRENT CATALOG", "DATABASES", "CURRENT DATABASE", "TABLES", "VIEWS",
        "COLUMNS", "CREATE TABLE", "CREATE VIEW", "CREATE CATALOG", "FUNCTIONS",
        "USER FUNCTIONS", "MODULES", "FULL MODULES", "JARS", "PARTITIONS", "PROCEDURES", "JOBS");

    /**
     * {@code SHOW …} et {@code DESCRIBE …} : des lectures pures, refusées comme si elles étaient
     * dangereuses.
     *
     * <p>C'est ce qu'on tape en premier en arrivant d'un client SQL, et le refus générique
     * — « Only SELECT, EXPLAIN and CREATE TABLE statements are allowed » — se lisait comme une
     * restriction de sécurité sur deux instructions qui ne peuvent rien écrire. L'application
     * détient déjà la réponse ailleurs ({@code /api/query/init} liste les tables,
     * {@code /api/query/schema/{table}} rend les colonnes), ce qui rendait le refus d'autant moins
     * justifiable : ce n'était pas une donnée protégée, c'était la même donnée par une autre porte.
     *
     * <p>La liste des {@code SHOW} est close et le reste est refusé — {@code USE}, {@code SET},
     * {@code LOAD MODULE} changent l'état de la session, {@code ALTER} et {@code DROP} écrivent.
     * {@code DESCRIBE} n'a pas besoin de liste : toutes ses formes décrivent un objet.
     */
    static boolean isIntrospectionStatement(String upperBody) {
        if (upperBody == null) return false;
        if (startsWithWord(upperBody, "DESCRIBE") || startsWithWord(upperBody, "DESC")) return true;
        if (!startsWithWord(upperBody, "SHOW")) return false;
        String rest = upperBody.substring(4).trim();
        return SHOW_FORMS.stream().anyMatch(form -> startsWithWord(rest, form));
    }

    /** {@code word} en tête, comme mot entier — « DESCRIPTOR » ne commence pas par « DESC ». */
    private static boolean startsWithWord(String text, String word) {
        if (!text.startsWith(word)) return false;
        int after = word.length();
        if (after == text.length()) return true;
        char next = text.charAt(after);
        return !Character.isLetterOrDigit(next) && next != '_' && next != '$';
    }

    /**
     * Vrai pour un {@code SHOW CREATE …}, dont la réponse est un DDL — donc des propriétés client
     * Kafka, donc les mots de passe SSL et le secret {@code sasl.jaas.config}. C'est la règle que
     * ce dépôt applique partout ailleurs (le point de terminaison topic, l'aperçu de DDL, la
     * lignée) et que ce chemin-ci n'avait jamais eue, faute d'y donner accès.
     */
    private static boolean isShowCreate(String upperBody) {
        if (upperBody == null || !startsWithWord(upperBody, "SHOW")) return false;
        return startsWithWord(upperBody.substring(4).trim(), "CREATE");
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
        if (!sql.startsWith("SELECT") && !sql.startsWith("EXPLAIN") && !sql.startsWith("CREATE TABLE")
                && !isIntrospectionStatement(sql)) {
            return new QueryResult(Collections.emptyList(), Collections.emptyList(), 0, STATEMENT_NOT_ALLOWED);
        }
        /*
         * `CREATE TABLE … AS SELECT` est un INSERT déguisé, et la whitelist le laissait passer
         * parce qu'elle classe sur le premier mot — le même défaut que `INSERT OVERWRITE` un cran
         * plus loin, ici avec des conséquences plus lourdes. Ce chemin-ci est celui de la
         * *lecture* : un CTAS y crée la table **et démarre le job qui l'alimente**, sans passer
         * par `submitJob`. Le job n'entre donc dans aucun registre — il est invisible au tableau
         * de bord, ne compte pas contre `explorer.max-concurrent-jobs`, et
         * `POST /api/query/cancel/{queryId}` n'a aucun identifiant pour l'atteindre. Sur une
         * source Kafka, cela veut dire un job continu que rien ne peut ni voir ni arrêter, lancé
         * par le point d'entrée que `executeSync` refuse justement aux INSERT.
         *
         * Le refus nomme la marche à suivre — le `CREATE TABLE` puis l'`INSERT INTO`, que
         * l'éditeur sait enchaîner avec « Run all » — plutôt que de s'arrêter sur une interdiction.
         */
        if (isCreateTableAsSelect(strippedSql, sql)) {
            return new QueryResult(Collections.emptyList(), Collections.emptyList(), 0,
                "CREATE TABLE … AS SELECT starts a job that writes rows, so it is not run here: "
                    + "the job would be invisible to the dashboard and could not be cancelled. "
                    + "Declare the table with CREATE TABLE, then run the INSERT INTO that fills "
                    + "it as a Flink job of your own.");
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
                // Le mode de lecture demandé que le planner ne sait pas exprimer, gardé pour que
                // le résultat le dise plutôt que de le laisser passer pour honoré.
                String unhonouredReadMode = null;
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
                /*
                 * Une fenêtre posée sur un topic Kafka est répondue par le lecteur direct, et elle
                 * lui est confiée **nommément** plutôt qu'après un échec.
                 *
                 * Ce n'est pas une préférence : une source Kafka est non bornée, donc elle ne se
                 * termine jamais, donc la dernière fenêtre ne se ferme pas — et la boucle de
                 * collecte ne rend la main qu'au plafond de lignes ou au budget. Une requête
                 * fenêtrée qui produit moins de lignes que le plafond, ce qui est le cas ordinaire
                 * (`SELECT window_start, COUNT(*) …` sur un topic d'une journée en fenêtres de
                 * cinq minutes), ne pouvait donc que dépenser ses dix secondes et se replier ici,
                 * en jetant au passage les lignes déjà collectées. Le repli était l'issue, pas
                 * l'exception : autant y aller sans payer le budget.
                 *
                 * Trois garde-fous, et chacun ferme une façon de rendre des lignes fausses.
                 *
                 * La **forme** : `namesOneSourceOnly` (la même définition que celle des
                 * métriques, pas une copie) refuse une fenêtre jointe à une autre table ou posée
                 * sur une sous-requête. Ce lecteur lirait la première table et ignorerait le
                 * reste, en silence — exactement ce que le classifieur d'erreurs existe pour
                 * empêcher.
                 *
                 * La **table** : elle doit être un topic Kafka que cette application a enregistré
                 * elle-même. Une table écrite par un opérateur est la sienne, et ses options — un
                 * scan borné, un watermark — sont peut-être précisément ce qui permet au planner
                 * de répondre ; la lui retirer serait défaire son travail.
                 *
                 * Le **hint** : une instruction qui porte ses propres options de connecteur —
                 * un hint Calcite `OPTIONS(…)` — dit ce qu'elle veut de la source (un
                 * `scan.bounded.mode`, typiquement, qui la fait terminer), donc elle part au
                 * planner comme demandé.
                 */
                if (SqlStatements.hasWindowTableCall(sqlToExecute)
                        && MetricService.namesOneSourceOnly(sqlToExecute)
                        && !SqlStatements.carriesAnOptionsHint(sqlToExecute)
                        && isGeneratedKafkaTable(extractPrimaryTable(sqlToExecute))) {
                    QueryResult windowed = kafkaDirectSelect(sqlToExecute, readMode, limit, startTime);
                    windowed = withExtraWarning(windowed, WINDOWED_READ_NOTE);
                    return autoReg.registered() ? withRegisteredFlag(windowed) : windowed;
                }
                /*
                 * Un mode de lecture nommé est une question que seul ce lecteur sait poser.
                 *
                 * Tant que le planner ne répondait à rien, chaque SELECT sur un topic Kafka
                 * retombait ici et `readMode` était honoré par accident. Le planner réparé, la
                 * table auto-enregistrée démarre toujours en `earliest-offset` : le sélecteur
                 * « Latest » de l'éditeur rendait alors exactement les mêmes lignes que
                 * « Earliest » — les plus anciennes, à la question « les plus récentes ». C'est le
                 * pire sens : une réponse plausible et fausse plutôt qu'un refus.
                 *
                 * Passer `latest-offset` au planner ne réparerait rien — un scan Kafka qui
                 * commence et s'arrête à `latest-offset` ne lit rien du tout — donc c'est le
                 * lecteur direct qui répond, comme il le fait déjà pour les modèles de métrique
                 * qui le demandent nommément.
                 *
                 * Deux garde-fous. Le mode doit être **nommé** : `null` tombe sur la branche
                 * « récent » de `fetchForDirectRead`, donc réagir à l'absence renverrait au
                 * lecteur direct tout appelant qui ne se prononce pas — l'audit, les aperçus de
                 * table, les tests — et défertait la réparation du moteur. Et la forme doit être
                 * une lecture que ce lecteur sait honorer : sur un JOIN ou une sous-requête il
                 * lirait une seule table et ignorerait le reste, donc là c'est le planner qui
                 * répond et l'avertissement dit que le mode n'a pas pu l'être.
                 */
                if (namesARecentReadMode(readMode) && extractPrimaryTable(sqlToExecute) != null) {
                    if (MetricService.isSingleTableRead(sqlToExecute)) {
                        QueryResult direct = kafkaDirectSelect(sqlToExecute, readMode, limit, startTime);
                        direct = withExtraWarning(direct, DIRECT_READER_CAVEAT
                            + " It answered because \"" + readMode + "\" asks for the most recent "
                            + "records, which the Flink planner has no way to express.");
                        return autoReg.registered() ? withRegisteredFlag(direct) : direct;
                    }
                    unhonouredReadMode = readMode;
                }
                if (explorerConfig.isFlinkSelectEnabled() && takePlannerAttempt()) {
                    try {
                        QueryResult flinkResult = executeViaFlinkPlanner(queryId, sqlToExecute, "SELECT", limit, timeout, startTime);
                        if (flinkResult.error() == null) {
                            clearFlinkSelectLatch();
                            if (unhonouredReadMode != null) {
                                flinkResult = withExtraWarning(flinkResult, "The read mode \""
                                    + unhonouredReadMode + "\" was not applied: this statement needs "
                                    + "the Flink planner, which reads a Kafka table from the offset "
                                    + "its definition names (earliest by default). These rows are "
                                    + "not necessarily the most recent ones.");
                            }
                            return autoReg.registered() ? withRegisteredFlag(flinkResult) : flinkResult;
                        }
                        // A timeout means the planner worked but the job was slow (empty/large topic):
                        // fall back for this query, but don't count it toward the circuit breaker.
                        if (flinkResult.error().startsWith("Query timed out")) {
                            flinkSelectFailures.set(0);
                            log.warn("Flink SELECT timed out — falling back to direct Kafka read for this query");
                            // Sur une fenêtre, « la requête a dépassé son délai » décrit mal ce qui
                            // s'est passé, et le message générique envoie chercher un topic trop
                            // gros : une source Kafka non bornée ne se termine pas, donc la
                            // dernière fenêtre ne se ferme que quand des enregistrements plus
                            // récents arrivent, et la collecte ne rend la main qu'au plafond de
                            // lignes ou au budget. C'est une propriété de la lecture, pas une
                            // lenteur, et elle a une sortie que le message nomme.
                            engineFailure = SqlStatements.hasWindowTableCall(sqlToExecute)
                                ? "The Flink planner accepted this window but could not finish it "
                                    + "within " + timeout + " ms: a Kafka source is unbounded, so its "
                                    + "last window closes only when later records arrive. The direct "
                                    + "reader bucketed the records it read instead. Bound the read to "
                                    + "let the planner answer — declare the table with "
                                    + "'scan.bounded.mode' = 'latest-offset' — or raise the timeout."
                                : flinkResult.error();
                        } else {
                            String noTimeAttribute = windowNeedsATimeAttribute(flinkResult.error(), sqlToExecute);
                            if (noTimeAttribute != null) {
                                flinkSelectFailures.set(0);
                                engineFailure = noTimeAttribute;
                            } else {
                                QueryResult rejected = rejectIfUserError(
                                    flinkResult.error(), sqlToExecute, startTime, autoReg.deferredToDirectReader());
                                if (rejected != null) return rejected;
                                recordFlinkSelectFailure(flinkResult.error());
                                engineFailure = SqlErrorClassifier.readable(flinkResult.error());
                            }
                        }
                    } catch (Throwable t) {
                        String explained = SqlErrorClassifier.explain(t);
                        String noTimeAttribute = windowNeedsATimeAttribute(explained, sqlToExecute);
                        if (noTimeAttribute != null) {
                            flinkSelectFailures.set(0);
                            engineFailure = noTimeAttribute;
                        } else {
                            QueryResult rejected = rejectIfUserError(
                                explained, sqlToExecute, startTime, autoReg.deferredToDirectReader());
                            if (rejected != null) return rejected;
                            recordFlinkSelectFailure(t.toString());
                            engineFailure = SqlErrorClassifier.readable(explained);
                        }
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
                /*
                 * Dire *pourquoi* cette requête-ci a changé de moteur, et pas seulement que le
                 * planner a fini par être coupé.
                 *
                 * `engineFailure` était calculé ici, lu nulle part, et jeté. Un repli ponctuel
                 * rendait donc `engine: KAFKA_DIRECT`, `error: null`, `warnings: []` — indiscernable
                 * d'une requête que le lecteur direct était censé traiter. C'est ce silence qui a
                 * gardé trois défauts distincts invisibles : une option refusée par le connecteur,
                 * un job qui ne pouvait pas obtenir ses emplacements, et une boucle de collecte qui
                 * bloquait sur sa dernière ligne. Les trois se lisaient « la requête a marché ».
                 *
                 * Le verrou ne suffisait pas à le remplacer, et pas seulement parce qu'il arrive
                 * après trois échecs : un *dépassement de délai* remet le compteur à zéro
                 * délibérément (le planner a fonctionné, le job était lent), donc la panne la plus
                 * courante ici ne l'atteint jamais. Une dégradation permanente et le motif de cette
                 * requête sont deux faits différents, alors les deux phrases s'ajoutent au lieu de
                 * se remplacer.
                 *
                 * Une erreur *utilisateur* ne passe pas par là : `rejectIfUserError` a déjà rendu
                 * le message du planner à l'appelant.
                 */
                if (autoReg.deferredToDirectReader()) {
                    // Le repli est *notre* décision, pas une panne : l'inférence n'a rien trouvé
                    // (topic vide ou illisible), donc la table n'a jamais été enregistrée et le
                    // « Object not found » du planner dit notre propre choix. Répercuter ce
                    // message enverrait chercher une faute de frappe dans un nom correct.
                    qr = withExtraWarning(qr, DIRECT_READER_CAVEAT
                        + " No schema could be inferred for this topic, so no Flink table was "
                        + "registered for it — the topic may be empty, or its messages unreadable.");
                } else if (engineFailure != null) {
                    qr = withExtraWarning(qr, DIRECT_READER_CAVEAT + " " + engineFailure);
                }
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

            // CREATE TABLE / EXPLAIN / SHOW / DESCRIBE go through the Flink planner directly.
            String statementType = extractStatementType(sqlToExecute);
            QueryResult result = executeViaFlinkPlanner(queryId, sqlToExecute, statementType, limit, timeout, startTime);
            if (isShowCreate(sql) && result.error() == null) {
                // Ce que rend `SHOW CREATE TABLE`, c'est du DDL : les propriétés client Kafka, donc
                // les mots de passe SSL et le secret `sasl.jaas.config`. Même règle que partout
                // ailleurs — la lignée le faisait déjà pour la même instruction.
                result = maskDdlCells(result);
            }
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
            System.currentTimeMillis() - startTime,
            SqlErrorClassifier.readable(classification.message()), false, "FLINK");
    }

    /**
     * Le mode de lecture demande-t-il explicitement les enregistrements <em>récents</em> ?
     *
     * <p>Explicitement : {@code null} veut dire « l'appelant ne se prononce pas », et le lecteur
     * direct le traite comme récent par défaut — mais en déduire une intention renverrait vers lui
     * tout appelant silencieux, ce qui reviendrait à défaire la réparation du planner.
     * {@code earliest-offset} est la seule des trois valeurs que le planner sache exprimer.
     */
    private static boolean namesARecentReadMode(String readMode) {
        if (readMode == null || readMode.isBlank()) return false;
        String mode = readMode.trim();
        return "latest-offset".equals(mode) || mode.startsWith(SINCE_READ_MODE_PREFIX);
    }

    /**
     * Ce qu'un résultat fenêtré dit du moteur qui l'a produit.
     *
     * <p>Rien n'a échoué ici — c'est le lecteur qui répond à cette forme — donc la phrase ne
     * commence pas par {@link #DIRECT_READER_CAVEAT}, qui annonce un repli. Elle dit ce que le
     * lecteur a fait, ce qu'il ne fait pas, et par où passer pour obtenir les fenêtres du planner.
     * L'approximation de HOP, CUMULATE et SESSION est ajoutée séparément par
     * {@code kafkaWindowSelect}, qui seul sait laquelle a été demandée.
     */
    private static final String WINDOWED_READ_NOTE =
        "This window was computed by the direct Kafka reader, over the records it read (at most "
            + "100 000), bucketed by the column the DESCRIPTOR names. The Flink planner is not "
            + "asked: a Kafka source is unbounded, so its last window never closes and the query "
            + "would spend its whole budget before falling back here anyway. To get the planner's "
            + "windows instead, declare the table yourself with 'scan.bounded.mode' = "
            + "'latest-offset' and a WATERMARK on the time column — a bounded scan ends, so every "
            + "window closes.";

    /**
     * Cette table est-elle un topic Kafka que <em>nous</em> avons enregistré ?
     *
     * <p>Le registre des définitions écrites à la main tranche en premier et négativement : une
     * table qu'un opérateur a tapée reste au planner, quoi qu'elle nomme comme topic. Le reste est
     * une correspondance de nom avec le catalogue Kafka, la même que celle de
     * l'auto-enregistrement et du lecteur direct — qui la refera juste après, sur un appel mis en
     * cache.
     *
     * <p>Un broker injoignable rend {@code false} : le planner décide alors, comme avant. Une
     * requête ne doit pas changer de moteur parce qu'une liste de topics n'a pas pu être lue.
     */
    private boolean isGeneratedKafkaTable(String rawTableRef) {
        if (rawTableRef == null) return false;
        String flinkTableName = DdlGeneratorService.toTableName(rawTableRef);
        for (FlinkTableStore.StoredTable stored : flinkTableStore.all()) {
            if (stored.name().equals(flinkTableName)) return false;
        }
        try {
            return kafkaAdminService.listTopics().stream()
                .anyMatch(topic -> DdlGeneratorService.toTableName(topic).equals(flinkTableName));
        } catch (Exception e) {
            log.debug("Could not list topics while routing a windowed read: {}", e.getMessage());
            return false;
        }
    }

    /**
     * La phrase à rapporter quand le planner a refusé une <em>fenêtre</em> faute d'attribut
     * temporel, ou {@code null} quand ce n'est pas ce qui s'est passé.
     *
     * <p>« The window function requires the timecol is a time attribute type, but is
     * TIMESTAMP(3) » n'est pas une panne : c'est la définition de la table qui ne déclare aucun
     * watermark sur la colonne que la fenêtre désigne. Lu comme une panne moteur, ce refus faisait
     * trois choses fausses à la fois. Il partait dans les warnings sous sa forme brute — la règle
     * Calcite, ses arguments et le plan {@code rel#…} — c'est-à-dire la seule partie du message
     * qui ne dit rien à personne. Il comptait pour le disjoncteur, donc trois fenêtres d'affilée
     * suffisaient à couper le planner pour <em>toutes</em> les requêtes du processus pendant dix
     * minutes. Et il ne nommait ni la colonne ni le geste qui répare.
     *
     * <p>Le repli, lui, reste : ce lecteur sait vraiment répondre à une fenêtre — il regroupe par
     * horodatage, en approximant HOP, CUMULATE et SESSION en fenêtres jointives, et il le dit.
     * Refuser à la place lui retirerait une capacité réelle, sur la forme la plus courante ici :
     * une fenêtre sur une colonne temporelle <em>du message</em>, que ce lecteur analyse très bien
     * et sur laquelle aucun watermark ne peut être déclaré depuis un schéma inféré.
     *
     * <p>Depuis que {@code DdlGeneratorService} déclare un watermark sur l'{@code event_time}
     * qu'il ajoute, la fenêtre par défaut de l'assistant passe par le planner : ce chemin-ci ne
     * concerne plus que les colonnes temporelles venues du payload et les tables écrites à la
     * main sans watermark.
     */
    private static String windowNeedsATimeAttribute(String rawError, String sql) {
        if (rawError == null || !SqlStatements.hasWindowTableCall(sql)) return null;
        if (!SqlErrorClassifier.mentionsATimeAttribute(rawError)) return null;
        Matcher window = WINDOW_CALL.matcher(sql);
        String column = window.find() ? window.group(3) : null;
        String named = column == null ? "the column the window is opened over" : "`" + column + "`";
        // Une trace reste, sans le WARN que `recordFlinkSelectFailure` écrivait : ce n'est pas une
        // panne, et une métrique fenêtrée qui se rafraîchit toutes les trente secondes en écrirait
        // une ligne à chaque cycle.
        //
        // La colonne n'y est pas, et c'est délibéré : elle voyage déjà dans l'avertissement du
        // résultat, qui est là où on la lit. L'interpoler ici ajoutait une donnée venue de la
        // requête dans un appel de journal, ce que `java/sensitive-log` signale — et que
        // `LogSafe` ne peut pas clore, CodeQL n'admettant aucune fonction de rédaction comme
        // barrière (voir docs/notes). Une ligne de trace ne vaut pas une alerte de plus dans une
        // liste qui se dépouille à la main.
        log.info("A window was opened over a column with no time attribute — the direct reader "
            + "answered it; the result names the column and the WATERMARK clause that moves it "
            + "to the planner");
        return "The Flink planner cannot open a window over " + named + ": a window function needs a "
            + "time attribute, and that column is an ordinary timestamp. The direct reader bucketed "
            + "the records by it instead. To run this window on the engine, declare the table "
            + "yourself with a watermark on that column — WATERMARK FOR <col> AS <col> - "
            + "INTERVAL '5' SECOND — as the generated tables do for their `event_time` column. "
            + "The planner's own words: " + SqlErrorClassifier.readable(rawError);
    }

    /**
     * L'ouverture commune des avertissements de repli : ce que l'appelant perd en changeant de
     * moteur. Une phrase, pas deux, parce que les deux motifs — notre décision de ne pas
     * enregistrer la table, et une panne du planner — se distinguent par ce qui la suit.
     */
    private static final String DIRECT_READER_CAVEAT =
        "This query fell back to the direct Kafka reader, which supports neither JOIN nor subqueries.";

    /**
     * Why the Flink planner is not answering, in the words a user can act on.
     *
     * <p>Two different states read identically from the outside — an operator turned the planner
     * off, or the circuit breaker turned it off after {@link #FLINK_SELECT_FAILURE_THRESHOLD}
     * failures — and the second one is invisible without this: every later query silently gets an
     * engine that supports neither JOIN nor subqueries.
     */
    /**
     * Le planner a-t-il le droit d'être tenté ?
     *
     * <p>Vrai tant que le disjoncteur n'a pas latché, et de nouveau une fois
     * {@link #FLINK_SELECT_RETRY_AFTER_MS} écoulé depuis le dernier échec — une tentative, dont
     * l'issue décide de la suite : {@code clearFlinkSelectLatch} sur un succès,
     * {@code recordFlinkSelectFailure} réarme l'horodatage sinon.
     */
    private boolean takePlannerAttempt() {
        if (!flinkSelectDisabled) return true;
        if (System.currentTimeMillis() - flinkSelectDisabledAt < FLINK_SELECT_RETRY_AFTER_MS) return false;
        // Le jeton est consommé ici plutôt qu'au vu du résultat, et c'est ce qui rend la borne
        // vraie : une tentative qui expire ne passe ni par le succès ni par l'échec compté, donc
        // réarmer là-bas laisserait un planner qui n'aboutit jamais être retenté à *chaque*
        // requête une fois l'intervalle écoulé. Plusieurs threads peuvent franchir la porte
        // ensemble et en payer chacun une : c'est borné, et un verrou sur le chemin de lecture
        // coûterait plus que ce qu'il éviterait.
        flinkSelectDisabledAt = System.currentTimeMillis();
        return true;
    }

    /**
     * Le planner a répondu : le disjoncteur se rouvre.
     *
     * <p>Le compteur seul ne suffit pas — {@code flinkSelectDisabled} est ce que lisent
     * {@code plannerUnavailableMessage} et {@code isFlinkSelectDisabled()}, donc le laisser levé
     * après une tentative réussie ferait dire à l'avertissement que le moteur est coupé pendant
     * qu'il répond.
     */
    private void clearFlinkSelectLatch() {
        flinkSelectFailures.set(0);
        if (flinkSelectDisabled) {
            flinkSelectDisabled = false;
            log.info("The Flink planner answered again — the circuit breaker is cleared");
        }
    }

    private String plannerUnavailableMessage() {
        if (!explorerConfig.isFlinkSelectEnabled()) {
            return "The Flink planner is disabled (explorer.flink-select-enabled=false), and this "
                 + "query needs it — the direct Kafka reader only reads a topic named after FROM.";
        }
        if (flinkSelectDisabled) {
            return "The Flink planner failed " + FLINK_SELECT_FAILURE_THRESHOLD + " times, so "
                 + "queries fall back to the direct Kafka reader, which supports neither JOIN nor "
                 + "subqueries. It is retried automatically about "
                 + (FLINK_SELECT_RETRY_AFTER_MS / 60_000L) + " minutes after the last failure, so "
                 + "a cause that has been fixed since needs no restart; the log records why it "
                 + "failed.";
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
    /**
     * Sème de test : lever le disjoncteur comme si le dernier échec datait de {@code at}.
     *
     * <p>L'intervalle se compte en minutes ; un test qui l'attendrait ne serait pas un test. Le
     * même idiome que {@code setAdminClientForTest} et {@code createConsumer()} ailleurs.
     */
    void tripFlinkSelectAt(long at) {
        flinkSelectDisabled = true;
        flinkSelectDisabledAt = at;
    }

    public boolean isFlinkSelectDisabled() {
        return flinkSelectDisabled;
    }

    private QueryResult withRegisteredFlag(QueryResult qr) {
        // Sur le record, pas via un constructeur plus court : celui à six arguments remet les
        // warnings à vide, donc une requête qui enregistrait son topic *et* avait une réserve à
        // formuler sur la façon dont il a été lu — la forme la plus courante d'un premier SELECT
        // sur un topic — perdait la réserve en sortant.
        return qr.withRegisteredFlag(true);
    }

    private void recordFlinkSelectFailure(String reason) {
        int failures = flinkSelectFailures.incrementAndGet();
        if (failures >= FLINK_SELECT_FAILURE_THRESHOLD && !flinkSelectDisabled) {
            flinkSelectDisabled = true;
            flinkSelectDisabledAt = System.currentTimeMillis();
            log.warn("Flink SELECT failed {} times (last: {}); disabling the Flink planner path for SELECT "
                + "for this process and using the direct Kafka reader instead. Restart after upgrading Flink to retry.",
                failures, reason);
        } else {
            log.warn("Flink SELECT failed ({}) — falling back to direct Kafka read", reason);
        }
    }

    /**
     * Dit ce qu'un résultat de changelog contient, quand il en est un.
     *
     * <p>Une agrégation, une jointure externe ou une sous-requête scalaire ne rend pas des lignes :
     * elle rend une suite de corrections. Le fait est porté par {@link ChangelogInfo} — que la
     * grille lit pour marquer chaque ligne — et <strong>dit</strong> dans les warnings, parce que
     * la grille n'est pas le seul lecteur : l'export CSV, une capture d'écran collée dans un
     * ticket, un appel direct à l'API n'ont que le texte.
     *
     * <p>Le plafond de lignes s'énonce à part, et c'est la conséquence la plus lourde : il compte
     * les corrections comme des lignes, donc un changelog peut le remplir d'états intermédiaires
     * et être coupé <em>juste avant</em> la seule ligne qui comptait. Le plafond n'est pas modifié
     * — le desserrer sur les corrections rendrait plus de lignes que l'appelant n'en a demandé,
     * et le décider pour lui est exactement ce que le marqueur évite — mais un résultat tronqué
     * ne se lit plus du tout de la même façon, alors il le dit.
     */
    private static QueryResult describeChangelog(QueryResult result, int corrections, int retractions, int limit) {
        if (corrections <= 0) return result;
        int rows = result.rows().size();
        boolean capReached = rows >= limit;
        List<String> warnings = new ArrayList<>(result.warnings());
        warnings.add(String.format(
            "This query updates its answer as it runs: %d of the %d rows returned are corrections "
                + "of an earlier row (%d of them withdraw one), not new records. For a given key "
                + "the answer is the last row carrying it; each row says which it is.",
            corrections, rows, retractions));
        if (capReached) {
            warnings.add(String.format(
                "The %d-row cap counted those corrections, so this changelog is cut off before the "
                    + "query settled — the last row is not necessarily the final answer. Raise "
                    + "\"Rows\" to see it through.", limit));
        }
        return result.withWarnings(warnings)
            .withChangelog(new ChangelogInfo(rows, corrections, retractions, capReached));
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
            // Combien des lignes collectées sont des *corrections* et non des enregistrements.
            // Écrites depuis la tâche de collecte, lues ici une fois `future.get()` rendu : la
            // barrière happens-before de la complétion du future les publie.
            final java.util.concurrent.atomic.AtomicInteger corrections =
                new java.util.concurrent.atomic.AtomicInteger();
            final java.util.concurrent.atomic.AtomicInteger retractions =
                new java.util.concurrent.atomic.AtomicInteger();

            // We use a CompletableFuture to implement the timeout logic.
            // Streaming queries might not produce data immediately, so we don't want to block indefinitely.
            CompletableFuture<List<Map<String, Object>>> future;
            try {
                future = CompletableFuture.supplyAsync(() -> {
                    try {
                        List<Map<String, Object>> resultRows = new ArrayList<>();
                        int count = 0;
                        // L'ordre des deux termes est porteur : `hasNext()` bloque sur une
                        // source non bornée, donc l'interroger une fois le quota atteint
                        // fait attendre un enregistrement dont on n'a plus besoin. Écrit
                        // `it.hasNext() && count < limit`, une lecture d'un topic qui tient
                        // exactement dans la limite collectait toutes ses lignes puis
                        // attendait la suivante jusqu'à expiration du budget — rapportée
                        // comme un dépassement de délai, donc un repli silencieux sur le
                        // lecteur direct. Le quota se vérifie en premier : il se lit sans
                        // rien demander à personne.
                        while (count < limit && it.hasNext()) {
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
                            /*
                             * Le RowKind voyage avec la ligne, au lieu d'être journalisé une fois
                             * puis jeté.
                             *
                             * Une requête « mise à jour » ne rend pas des lignes mais une suite de
                             * corrections : sur trois lignes en entrée, `SELECT COUNT(*)` en émet
                             * cinq — +I(1), -U(1), +U(2), -U(2), +U(3). Sans marqueur, la grille
                             * présentait les corrections comme des résultats, et il fallait
                             * deviner que seule la dernière comptait.
                             *
                             * Sous un nom réservé plutôt que comme une colonne : `columns` vient
                             * du schéma résolu, c'est ce que la grille, le tri et l'export lisent,
                             * et y ajouter une entrée ferait passer une propriété de la ligne pour
                             * une valeur du résultat. Écrit seulement quand il y a quelque chose à
                             * dire — un `+I` est l'ordinaire, et l'écrire sur chaque ligne d'un
                             * SELECT ordinaire coûterait une entrée de map par ligne pour la
                             * valeur par défaut.
                             */
                            RowKind kind = row.getKind();
                            if (kind != RowKind.INSERT) {
                                mapRow.put(ChangelogInfo.ROW_KIND_KEY, kind.shortString());
                                corrections.incrementAndGet();
                                if (kind == RowKind.DELETE || kind == RowKind.UPDATE_BEFORE) {
                                    retractions.incrementAndGet();
                                }
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
            QueryResult answered = new QueryResult(columns, rows, duration, null, false, "FLINK");
            return describeChangelog(answered, corrections.get(), retractions.get(), limit);
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
     * fetcher closes the iterator, which takes the Flink job with it. It ran a status poll on the
     * way out to file the outcome, which cost up to 150 ms on the request's own thread; with the
     * job store gone there is nothing to file it to, so the entry is simply handed back.
     */
    private void releaseSyncJob(JobInfo info) {
        if (info == null) return;
        if (info.endedAt() == null) {
            info.markEnded(System.currentTimeMillis());
        }
        heldJobs.remove(info.queryId());
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

    /**
     * Les mots par lesquels une colonne peut être qualifiée dans cette instruction : le nom de la
     * table, et l'alias qu'elle porte s'il y en a un.
     *
     * <p>{@code SELECT o.state FROM demo_orders o WHERE o.state = 'SHIPPED'} est du SQL parfaitement
     * ordinaire, et ce lecteur y répondait <strong>zéro ligne</strong> : la condition portait la clé
     * {@code o.state}, cherchée telle quelle dans le message puis segment par segment dans un objet
     * {@code o} qui n'existe pas, donc chaque ligne était rejetée. Une grille vide sur une requête
     * juste — la pire des deux directions, une réponse plausible et fausse plutôt qu'un refus. La
     * projection avait le même défaut, en rendant une colonne de {@code null}.
     *
     * <p>L'alias est reconnu avec ou sans {@code AS}, et un mot-clé qui suit le nom de la table
     * n'en est pas un — sans cette liste, {@code FROM orders WHERE …} donnerait l'alias
     * « WHERE ». Le nom de la table est toujours retenu : {@code SELECT orders.state FROM orders}
     * se qualifie sans alias.
     */
    private static final Pattern FROM_ALIAS = Pattern.compile(
        "(?i)\\bFROM\\s+[`\"]?[\\w.\\-]+[`\"]?(?:\\s+AS)?\\s+([A-Za-z_]\\w*)");

    /** Ce qui suit une table sans en être l'alias. */
    private static final Set<String> NOT_AN_ALIAS = Set.of(
        "AS", "WHERE", "GROUP", "ORDER", "HAVING", "LIMIT", "OFFSET", "FETCH", "JOIN", "INNER",
        "LEFT", "RIGHT", "FULL", "CROSS", "OUTER", "ON", "USING", "UNION", "EXCEPT", "INTERSECT",
        "WINDOW", "FOR", "EMIT", "PARTITION", "TABLESAMPLE", "WITH");

    static Set<String> tableQualifiers(String scan, String tableRef) {
        Set<String> qualifiers = new LinkedHashSet<>();
        if (tableRef != null) qualifiers.add(tableRef.toLowerCase(Locale.ROOT));
        Matcher alias = FROM_ALIAS.matcher(scan);
        if (alias.find() && !NOT_AN_ALIAS.contains(alias.group(1).toUpperCase(Locale.ROOT))) {
            qualifiers.add(alias.group(1).toLowerCase(Locale.ROOT));
        }
        return qualifiers;
    }

    /**
     * La même colonne sans son préfixe de table, ou {@code null} quand elle n'en porte pas.
     *
     * <p>Seulement quand le préfixe est bien celui de cette table : un champ imbriqué
     * {@code customer.name} n'a rien à voir avec une qualification, et le retirer ferait chercher
     * un {@code name} de premier niveau qui n'existe pas.
     */
    static String withoutQualifier(String path, Set<String> qualifiers) {
        int dot = path.indexOf('.');
        if (dot <= 0 || dot + 1 >= path.length()) return null;
        return qualifiers.contains(path.substring(0, dot).toLowerCase(Locale.ROOT))
            ? path.substring(dot + 1)
            : null;
    }

    /**
     * La valeur d'une colonne du message, le préfixe de table retiré <em>en dernier recours</em>.
     *
     * <p>Dans cet ordre parce qu'un payload XML aplati porte des clés à points : si le message a
     * réellement un champ nommé {@code o.state}, c'est lui la réponse, et le préfixe n'est essayé
     * que lorsque la recherche directe n'a rien donné.
     */
    private Object resolveColumn(Map<String, Object> row, String path, Set<String> qualifiers) {
        Object direct = getNestedValue(row, path);
        if (direct != null) return direct;
        String bare = withoutQualifier(path, qualifiers);
        return bare == null ? null : getNestedValue(row, bare);
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
        // Chaque lecture lexicale de cette méthode se fait sur le texte dont les littéraux sont
        // neutralisés, et une seule fois : les positions y sont celles de `sql`, donc ce qui est
        // capturé hors littéral est le texte d'origine. Sans cela, `WHERE note = 'from ailleurs'`
        // choisissait la mauvaise table et `WHERE note = 'limit 1'` tronquait la lecture.
        String scan = SqlStatements.outsideLiterals(sql);
        /*
         * L'instruction est analysée une fois, et ce qu'en dit le parseur prime sur ce qu'en
         * disent les motifs : quelles sources, quels alias, quel plafond, quelle projection,
         * quelles égalités. Chaque motif reste dessous et répond quand la grammaire a refusé —
         * c'est-à-dire sur une requête que le moteur refusera de toute façon.
         */
        Optional<SqlAst.Read> ast = SqlAst.read(sql);
        // Extract table name from FROM clause. Le motif est celui de `FROM_TABLE`, partagé plutôt
        // que recopié : la copie locale n'admettait pas le guillemet double que l'autre acceptait.
        Matcher fromMatcher = FROM_TABLE.matcher(scan);
        if (!fromMatcher.find()) {
            return new QueryResult(Collections.emptyList(), Collections.emptyList(),
                System.currentTimeMillis() - startTime, "Cannot parse table name from SQL");
        }
        String rawTableRef = fromMatcher.group(1);
        String flinkTableName = DdlGeneratorService.toTableName(rawTableRef);
        // Le nom de la table et son alias : ce par quoi une colonne peut être qualifiée. Le
        // parseur les donne exactement — y compris `AS` implicite et nom entre accents graves —
        // là où le motif les devine.
        Set<String> qualifiers = ast.isPresent()
            ? new LinkedHashSet<>(SqlAst.qualifiers(ast.get()))
            : tableQualifiers(scan, rawTableRef);

        // Detect TUMBLE / HOP / CUMULATE / SESSION window functions — route to dedicated handler.
        // La règle est partagée avec `isSingleTableRead` : la copie locale omettait CUMULATE, si
        // bien qu'une fenêtre cumulative arrivée ici n'était pas approximée mais traitée comme une
        // agrégation ordinaire — la fenêtre disparaissait sans un mot.
        if (SqlStatements.hasWindowTableCall(sql)) {
            return kafkaWindowSelect(sql, readMode, limit, startTime);
        }

        // Detect aggregate functions in the SELECT portion only (before FROM)
        boolean isAggregate = AGGREGATE_PRESENT.matcher(scan.substring(0, fromMatcher.start())).find();

        // Respect LIMIT from SQL if smaller than configured limit (skip for aggregates)
        if (!isAggregate) {
            OptionalInt parsedCap = ast.map(SqlAst.Read::rowCap).orElse(OptionalInt.empty());
            if (parsedCap.isPresent()) {
                limit = Math.min(limit, parsedCap.getAsInt());
            } else {
                Matcher limitMatcher = LIMIT_CLAUSE.matcher(scan);
                if (limitMatcher.find()) {
                    limit = Math.min(limit, Integer.parseInt(limitMatcher.group(1)));
                }
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
        List<String> requestedCols = isAggregate ? Collections.emptyList() : selectedColumns(sql, ast);
        Map<String, String> whereConds = simpleWhere(sql, ast);
        List<String> whereWarnings = unsupportedPredicates(sql, ast);
        /*
         * Une expression que ce lecteur ne sait pas calculer est refusée, pas rendue vide.
         *
         * `JSON_VALUE(c, '$.a')` ou `amount * 1.2` étaient cherchés comme s'ils étaient des noms de
         * champ : introuvables, donc une colonne de `null` — une réponse, avec la bonne en-tête et
         * la mauvaise valeur. Seul le planner sait les évaluer, et le dire est la seule réponse
         * honnête ; la branche agrégat, elle, a son propre calcul et n'est pas concernée.
         */
        if (!isAggregate && ast.isPresent()) {
            String uncomputable = ast.get().projection().stream()
                .filter(item -> !item.plainColumn())
                .map(SqlAst.Projected::path)
                .findFirst().orElse(null);
            if (uncomputable != null) {
                return new QueryResult(Collections.emptyList(), Collections.emptyList(),
                    System.currentTimeMillis() - startTime,
                    "The direct Kafka reader cannot compute \"" + uncomputable + "\": it reads "
                        + "fields out of the message and evaluates no expression. Only the Flink "
                        + "planner computes it — retry when it is available, or project the "
                        + "underlying column and compute outside the query.",
                    false, "KAFKA_DIRECT");
            }
        }

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
            if (!matchesWhereConditions(row, whereConds, qualifiers)) continue;
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
                    // Sans alias explicite, la colonne sort sous son nom *nu* : c'est ce que le
                    // planner produit pour `SELECT o.state`, et deux moteurs qui nomment
                    // différemment la même colonne cassent tout ce qui lit le résultat par son nom.
                    String bare = withoutQualifier(sourceCol, qualifiers);
                    String outputCol = aliasParts.length > 1 ? aliasParts[1].trim()
                        : (bare != null ? bare : sourceCol);
                    projected.put(outputCol, toSerializable(resolveColumn(row, sourceCol, qualifiers)));
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
                if (p.length > 1) return p[1].trim();
                // Le même nom nu que celui sous lequel la valeur est rangée : sinon l'en-tête
                // annonce `o.state` pendant que la ligne porte `state`, et la colonne s'affiche
                // vide.
                String bare = withoutQualifier(p[0].trim(), qualifiers);
                return bare != null ? bare : p[0].trim();
            }).collect(Collectors.toList());
        log.debug("[KafkaDirect] topic='{}' rows={} readMode={}", topic, rows.size(), readMode);
        List<String> notes = new ArrayList<>(whereWarnings);
        /*
         * Une page incomplète dont la lecture a buté sur son propre plafond dit ce qu'elle a lu.
         *
         * La branche agrégat le faisait déjà (`AGGREGATE_SCAN_CAPPED`) et celle-ci non, alors que
         * c'est là que le silence trompe le plus : un `WHERE` qui ne trouve rien dans la tranche
         * lue rend une grille vide, indiscernable d'un « aucun enregistrement ne correspond ». Sur
         * un topic plus ancien que le plafond, la réponse est même systématiquement fausse dans ce
         * sens. La condition dit exactement ce qu'on sait : la lecture s'est arrêtée sur son
         * plafond (`records.size() >= fetch`) et la page n'est pas pleine, donc il peut rester des
         * lignes au-delà.
         */
        if (rows.size() < limit && records.size() >= fetch) {
            notes.add(SCAN_CAPPED + " — this read covered " + records.size() + " record(s) of '"
                + topic + "', taken " + describeScanEnd(readMode) + ", and returned "
                + rows.size() + " row(s). Rows matching this query may lie beyond them: narrow the "
                + "WHERE clause, or read from the other end.");
        }
        return new QueryResult(columns, rows, System.currentTimeMillis() - startTime, null, false, "KAFKA_DIRECT")
            .withWarnings(notes);
    }

    /** The head of the caveat a projection carries when its scan stopped on the row it was capped at. */
    public static final String SCAN_CAPPED = "Scan ceiling reached";

    /**
     * Which end of the topic a direct read entered by, in the words a caveat can use.
     *
     * <p>Deliberately not {@code MetricService}'s sentence of the same shape: there {@code null}
     * describes that module's own default (earliest), here it describes what
     * {@link #fetchForDirectRead} actually does with it (the recent end). Sharing one phrase
     * between the two would make one of them lie.
     */
    private static String describeScanEnd(String readMode) {
        Long since = sinceTimestampOf(readMode);
        if (since != null) return "forward from " + java.time.Instant.ofEpochMilli(since);
        return "earliest-offset".equals(readMode)
            ? "from the oldest records forward"
            : "from the most recent records backwards";
    }

    /**
     * Computes SQL aggregate functions (COUNT/SUM/AVG/MAX/MIN) over pre-fetched Kafka rows.
     * Supports optional GROUP BY with one or more simple column names.
     * Each aggregate in the SELECT must have an alias (e.g. COUNT(*) AS metric_value).
     */
    private QueryResult kafkaAggregateSelect(String sql, List<Map<String, Object>> inputRows, long startTime) {
        // Le préfixe de table qu'une colonne peut porter — `SUM(o.amount)`, `GROUP BY o.state` —
        // dérivé de l'instruction plutôt que passé de main en main : cette méthode a le SQL, et un
        // paramètre de plus sur trois appels serait une occasion de plus de l'oublier.
        String scan = SqlStatements.outsideLiterals(sql);
        Set<String> qualifiers = tableQualifiers(scan, extractPrimaryTable(sql));
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
        // Bornes cherchées hors littéraux, contenu découpé dans l'original : un `GROUP BY` cité
        // dans une valeur ne groupe rien, et une colonne entre accents graves doit rester lisible
        // (le texte neutralisé, lui, l'a vidée).
        Matcher gm = GROUP_BY_BLOCK.matcher(scan);
        if (gm.find()) {
            for (String c : sql.substring(gm.start(1), gm.end(1)).split(",")) {
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
                        Object v = resolveColumn(row, c, qualifiers);
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
                result.put(agg[2], evalAggregate(agg[0], agg[1], "true".equals(agg[3]), groupRows, qualifiers));
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
        // Une fenêtre n'a pas d'alias de table à porter — `TUMBLE(TABLE t, …)` n'en offre pas la
        // place — mais le nom de la table qualifie ses colonnes comme partout ailleurs.
        Set<String> qualifiers = tableQualifiers(SqlStatements.outsideLiterals(sql), tableName);

        // Bucket messages by tumbling window
        Map<Long, List<Map<String, Object>>> windows = new LinkedHashMap<>();
        for (var record : records) {
            String value = record.value();
            if (value == null || value.isBlank()) continue;
            Map<String, Object> row = parseMessageToRow(value);
            if (row.isEmpty()) continue;
            if (!matchesWhereConditions(row, whereConds, qualifiers)) continue;

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
                row.put(agg[2], evalAggregate(agg[0], agg[1], "true".equals(agg[3]), entry.getValue(), qualifiers));
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
    private Object evalAggregate(String func, String col, boolean distinct,
                                 List<Map<String, Object>> rows, Set<String> qualifiers) {
        if ("COUNT".equals(func)) {
            // Counts are integral — returning double made the UI display "42.0"
            if ("*".equals(col)) return (long) rows.size();
            if (distinct) {
                return (long) rows.stream()
                    .map(r -> resolveColumn(r, col, qualifiers)).filter(v -> v != null)
                    .map(Object::toString)
                    .collect(Collectors.toSet()).size();
            }
            return rows.stream().filter(r -> resolveColumn(r, col, qualifiers) != null).count();
        }
        List<Double> nums = rows.stream()
            .map(r -> asDouble(resolveColumn(r, col, qualifiers)))
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
        return selectedColumns(sql, SqlAst.read(sql));
    }

    /**
     * Les colonnes projetées, sous la forme {@code source AS sortie} que la boucle de lecture sait
     * relire.
     *
     * <p>Du parseur quand il a répondu, parce que le découpage lexical se fait sur les virgules et
     * qu'un appel de fonction en contient : {@code SELECT JSON_VALUE(c, '$.a') AS x} devenait deux
     * colonnes, {@code JSON_VALUE(c} et {@code '$.a') AS x}, dont aucune n'existe. L'arbre rend les
     * éléments tels qu'ils sont écrits, alias compris, et dit lesquels sont de simples colonnes —
     * ce dont l'appelant se sert pour refuser ce qu'il ne peut pas calculer.
     */
    private List<String> selectedColumns(String sql, Optional<SqlAst.Read> ast) {
        if (ast.isPresent()) {
            SqlAst.Read read = ast.get();
            if (read.star()) return Collections.emptyList();
            return read.projection().stream()
                .map(item -> item.path().equals(item.output())
                    ? item.path()
                    : item.path() + " AS " + item.output())
                .collect(Collectors.toList());
        }
        Matcher m = SELECT_PROJECTION.matcher(sql.trim());
        if (!m.find()) return Collections.emptyList();
        String cols = m.group(1).trim();
        if (cols.equals("*")) return Collections.emptyList();
        return Arrays.stream(cols.split(","))
            .map(c -> c.replace("`", "").trim())
            .filter(c -> !c.isEmpty())
            .collect(Collectors.toList());
    }

    /** GROUP BY body, up to whatever clause follows it. Compiled once, not per aggregate read. */
    private static final Pattern GROUP_BY_BLOCK = Pattern.compile(
        "(?i)\\bGROUP\\s+BY\\s+([^;]+?)(?:\\s+HAVING\\b|\\s+ORDER\\b|\\s+LIMIT\\b|\\s*;|\\s*$)");

    /** WHERE body, up to the row cap or the end of the statement. Compiled once, not per read. */
    private static final Pattern WHERE_BLOCK =
        Pattern.compile("(?i)\\bWHERE\\s+(.+?)(?:\\bLIMIT\\b|;|$)", Pattern.DOTALL);

    /** WHERE body, stopping before the clauses that follow it — otherwise GROUP BY looks unsupported. */
    private static final Pattern WHERE_WARNING_BLOCK = Pattern.compile(
        "(?i)\\bWHERE\\s+(.+?)(?:\\bGROUP\\s+BY\\b|\\bORDER\\s+BY\\b|\\bHAVING\\b|\\bLIMIT\\b|;|$)",
        Pattern.DOTALL);

    /** Package-private: driven directly by {@code FlinkSqlServiceTest}. */
    Map<String, String> extractSimpleWhere(String sql) {
        return simpleWhere(sql, SqlAst.read(sql));
    }

    /**
     * Les égalités du WHERE que ce lecteur sait appliquer.
     *
     * <p>Du parseur quand il a répondu, et c'est une correction en soi : le motif lisait
     * {@code colonne = 'valeur'} <em>n'importe où</em> après le WHERE, donc il retenait une
     * égalité placée sous un {@code OR} — {@code WHERE a = 'x' OR b = 'y'} filtrait sur {@code a}
     * seul, ce qui est faux dans le sens qui perd des lignes — et il retenait aussi celles d'un
     * {@code HAVING}, la borne de sa clause s'arrêtant plus loin que celle de son jumeau
     * d'avertissement. L'arbre ne descend que les conjonctions, donc les deux disparaissent.
     */
    private Map<String, String> simpleWhere(String sql, Optional<SqlAst.Read> ast) {
        if (ast.isPresent()) {
            Map<String, String> parsed = new LinkedHashMap<>();
            for (SqlAst.Condition condition : ast.get().equalities()) {
                parsed.put(condition.column(), condition.value());
            }
            return parsed;
        }
        Map<String, String> conditions = new LinkedHashMap<>();
        // Les bornes de la clause se cherchent hors littéraux — un `WHERE` ou un `LIMIT` cité dans
        // une valeur les déplaçait — et le contenu se découpe dans le texte d'origine, puisque
        // c'est justement la valeur des littéraux qu'on vient y chercher. Les positions des deux
        // textes coïncident, c'est tout l'objet de `outsideLiterals`.
        Matcher wm = WHERE_BLOCK.matcher(SqlStatements.outsideLiterals(sql));
        if (!wm.find()) return conditions;
        String whereClause = sql.substring(wm.start(1), wm.end(1)).trim();
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
        return unsupportedPredicates(sql, SqlAst.read(sql));
    }

    /**
     * Ce que le WHERE demande et que ce lecteur n'a pas appliqué.
     *
     * <p>Une seule extraction pour les deux réponses quand le parseur a répondu : ce qui n'est pas
     * devenu une égalité <em>est</em> ce qui est signalé, par construction. Les deux motifs
     * s'accordaient mal — l'un lisait la clause jusqu'au {@code LIMIT}, l'autre s'arrêtait au
     * {@code GROUP BY} — si bien qu'une condition pouvait être appliquée sans être signalée.
     */
    private List<String> unsupportedPredicates(String sql, Optional<SqlAst.Read> ast) {
        if (ast.isPresent()) {
            List<String> others = ast.get().otherPredicates();
            if (others.isEmpty()) return List.of();
            return List.of("The direct engine applied only the \"column = 'value'\" conditions of this "
                + "WHERE clause. Ignored: \"" + String.join(" / ", others) + "\" — rows that do not "
                + "satisfy it may appear in the result.");
        }
        Matcher wm = WHERE_WARNING_BLOCK.matcher(SqlStatements.outsideLiterals(sql));
        if (!wm.find()) {
            return List.of();
        }
        // Découpé dans l'original : ce qui est rapporté à l'utilisateur, c'est le fragment qu'il a
        // écrit, valeurs comprises.
        String remainder = sql.substring(wm.start(1), wm.end(1))
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

    /**
     * @param qualifiers ce par quoi une colonne peut être préfixée dans cette instruction — le nom
     *                   de la table et son alias ; vide quand la question ne se pose pas
     */
    private boolean matchesWhereConditions(Map<String, Object> row, Map<String, String> conditions,
                                           Set<String> qualifiers) {
        for (Map.Entry<String, String> cond : conditions.entrySet()) {
            Object val = resolveColumn(row, cond.getKey(), qualifiers);
            if (val == null) {
                val = findValueIgnoreCase(row, cond.getKey());
                String bare = val == null ? withoutQualifier(cond.getKey(), qualifiers) : null;
                if (bare != null) val = findValueIgnoreCase(row, bare);
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
            if (requestCancel(info)) {
                return CancelOutcome.CANCELLED;
            }
            // The job had already finished, taking its MiniCluster with it. Nothing was cancelled,
            // so saying CANCELLED would be the very claim this enum exists to prevent — and the
            // unguarded `cancel()` that used to sit here answered the Stop button with a 500
            // instead, on the most ordinary race there is: pressing Stop as the query completes.
            info.markEnded(System.currentTimeMillis());
            heldJobs.remove(queryId);
            return CancelOutcome.NO_ACTIVE_JOB;
        }
        // Nothing under that id: it finished and was swept, it was a KAFKA_DIRECT scan that never
        // had a Flink job, or the id is unknown. All three used to write a record into
        // `FlinkJobStore` on the way out — care was taken there not to overwrite how a job had
        // actually ended — but the only reader of those records was the dashboard's job table,
        // which is gone. The outcome the caller gets is unchanged; only the filing is.
        return CancelOutcome.NO_ACTIVE_JOB;
    }

    /**
     * The live registry, swept.
     *
     * <p>It used to skip the sweep, and the asymmetry was not cosmetic: a finished query stays in
     * {@link #heldJobs} until <em>something</em> looks, and the three callers here are the ones
     * that act on the answer — {@code POST /api/config} counts them to refuse a cluster repoint
     * with 409, the lineage graph draws a node per job, and the KPI suggestions derive a pipeline
     * edge from each. A query the user ran and finished would therefore go on refusing their next
     * config save until some other screen happened to sweep. It stayed invisible because the
     * dashboard polled a sibling method every 30 s, so a browser being open hid it — which is
     * exactly why it surfaced the day a probe ran with no browser open at all. That sibling has
     * since been removed with the dashboard's job table, which makes this the <em>only</em> path
     * that sweeps: skipping it here would bring the defect straight back.
     *
     * <p>Affordable: {@link #reapEndedJobs} returns immediately on an empty map, and none of these
     * callers is on a timer — each is a user gesture, where the ≤150 ms status poll per live job
     * is not worth trading correctness for.
     */
    public Map<String, JobInfo> getHeldJobs() {
        reapEndedJobs();
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
