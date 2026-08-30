// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.QueryResult;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.types.Row;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Les <em>types</em> de requête Flink SQL que ce moteur reçoit, et ce que chacun répond.
 *
 * <p>{@code FlinkSqlServiceTest} couvre le SELECT de base — projection, WHERE, alias, jointure
 * interne, plafond de lignes — plus les fenêtres du <em>lecteur direct</em>. Ce qui n'était couvert
 * par rien, c'est tout le reste de ce qu'un opérateur écrit réellement : une agrégation avec
 * {@code HAVING}, un {@code DISTINCT}, une jointure externe, une sous-requête, les opérateurs
 * ensemblistes, un tri, une pagination, une fenêtre passée <em>par le planner</em>, les trois
 * formes d'{@code EXPLAIN}, les variantes de {@code CREATE TABLE} — et la famille des instructions
 * que la whitelist refuse, dont une seule était épinglée par famille.
 *
 * <p>Ce n'est pas de la couverture pour la couverture. Chaque forme traverse la même chaîne de
 * gardes écrits ici (whitelist, validateur, auto-enregistrement, classification du refus), et
 * c'est cette chaîne qui décide de la chose la plus lourde de conséquences dans ce service :
 * <strong>si un échec se replie sur le lecteur direct ou revient à l'appelant</strong>. Un repli
 * mal déclenché ne rend pas une erreur — il rend la phrase d'un <em>autre</em> moteur sur une
 * requête qu'il n'a jamais su exécuter.
 *
 * <p>Deux défauts sont sortis de cette énumération et sont épinglés ici :
 * <ul>
 *   <li>un {@code ORDER BY} non borné et un {@code EXISTS} corrélé — du SQL valide qui n'a pas de
 *       sens sur un flux — étaient classés en panne moteur, donc repliés, donc rendus comme
 *       « Table 'k_orders' not found » <em>sur une table qui existe</em>, la vraie raison partant
 *       dans les warnings ;</li>
 *   <li>{@code CREATE TABLE … AS SELECT} franchissait la whitelist (qui classe sur le premier mot)
 *       et démarrait, depuis le chemin de <em>lecture</em>, un job d'écriture qu'aucun registre ne
 *       voyait — voir {@code aCtasIsAnInsertWearingACreateTableHat}.</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlinkSqlServiceStatementKindsTest {

    private FlinkSqlService service;

    @BeforeAll
    void setup() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment();
        // Un job dont le parallélisme dépasse les slots ne se déploie jamais et expire sans rien
        // dire — le défaut documenté dans FlinkConfig. La donnée tient en trois lignes.
        env.setParallelism(1);
        StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env,
                EnvironmentSettings.newInstance().inStreamingMode().build());

        ExplorerConfig config = new ExplorerConfig();
        config.setDefaultMaxRows(20);
        config.setDefaultQueryTimeoutMs(15_000);
        config.setFlinkJobStorePath(Files.createTempFile("kinds-jobs-", ".json").toString());
        config.setFlinkTableStorePath(Files.createTempFile("kinds-tables-", ".json").toString());

        KafkaAdminService kafkaAdminService = mock(KafkaAdminService.class);
        // Aucun topic : une table que Flink ne connaît pas ne peut pas être auto-enregistrée, donc
        // un refus vient bien du planner et non d'un enregistrement qui aurait changé la donne.
        when(kafkaAdminService.listTopics()).thenReturn(List.of());

        FlinkRuntimeCoordinator coordinator = new FlinkRuntimeCoordinator(tableEnv);
        service = new FlinkSqlService(tableEnv, coordinator, config,
                new SqlQueryValidator(config, tableEnv, coordinator), kafkaAdminService,
                mock(SchemaInferenceService.class), mock(DdlGeneratorService.class),
                new FlinkJobStore(config), new FlinkTableStore(config));

        tableEnv.createTemporaryView("k_orders",
                tableEnv.fromValues(
                        DataTypes.ROW(
                                DataTypes.FIELD("order_id", DataTypes.STRING()),
                                DataTypes.FIELD("amount", DataTypes.DOUBLE()),
                                DataTypes.FIELD("state", DataTypes.STRING()),
                                DataTypes.FIELD("customer_id", DataTypes.STRING())),
                        Row.of("ORD-1", 100.0, "RECEIVED", "C-1"),
                        Row.of("ORD-2", 200.0, "SHIPPED", "C-2"),
                        Row.of("ORD-3", 50.0, "SHIPPED", "C-1")));

        tableEnv.createTemporaryView("k_customers",
                tableEnv.fromValues(
                        DataTypes.ROW(
                                DataTypes.FIELD("customer_id", DataTypes.STRING()),
                                DataTypes.FIELD("name", DataTypes.STRING())),
                        Row.of("C-1", "Alice"),
                        Row.of("C-2", "Bob")));

        // Une table avec un attribut temporel : sans watermark, ni une fenêtre ni un OVER n'ont de
        // sens, et c'est la moitié du SQL de flux qu'aucun test n'atteignait par le planner.
        tableEnv.executeSql("CREATE TABLE k_events (id BIGINT, ts TIMESTAMP(3), "
                + "WATERMARK FOR ts AS ts - INTERVAL '1' SECOND) WITH "
                + "('connector'='datagen','number-of-rows'='10','rows-per-second'='10')");
        tableEnv.executeSql("CREATE TABLE k_sink (order_id STRING) WITH ('connector'='blackhole')");
    }

    // ── Ce que le planner répond ──────────────────────────────────────────────────────

    @Test
    void aggregationAndGroupingRunOnThePlanner() {
        answered("SELECT state, COUNT(*) AS n FROM k_orders GROUP BY state HAVING COUNT(*) > 1");
        answered("SELECT DISTINCT state FROM k_orders");
        // Sans alias : `metric_value` est la convention des métriques, pas une exigence du moteur.
        answered("SELECT COUNT(*) FROM k_orders");
        answered("SELECT customer_id, SUM(amount) AS total, AVG(amount) AS mean FROM k_orders GROUP BY customer_id");
    }

    /**
     * Les jointures externes, que le lecteur direct ne sait pas servir du tout — donc les formes
     * pour lesquelles un repli silencieux serait le plus trompeur.
     */
    @Test
    void outerJoinsRunOnThePlanner() {
        answered("SELECT o.order_id, c.name FROM k_orders o "
                + "LEFT JOIN k_customers c ON o.customer_id = c.customer_id");
        answered("SELECT o.order_id, c.name FROM k_orders o "
                + "FULL OUTER JOIN k_customers c ON o.customer_id = c.customer_id");
    }

    @Test
    void subqueriesRunOnThePlanner() {
        answered("SELECT order_id FROM k_orders "
                + "WHERE customer_id IN (SELECT customer_id FROM k_customers)");
        answered("SELECT order_id, (SELECT COUNT(*) FROM k_customers) AS n FROM k_orders");
        answered("SELECT order_id FROM (SELECT * FROM k_orders WHERE amount > 60) t");
    }

    @Test
    void setOperationsRunOnThePlanner() {
        answered("SELECT order_id FROM k_orders UNION ALL SELECT order_id FROM k_orders");
        answered("SELECT order_id FROM k_orders UNION SELECT order_id FROM k_orders");
        answered("SELECT customer_id FROM k_orders INTERSECT SELECT customer_id FROM k_customers");
        // EXCEPT rend zéro ligne sur ce jeu, ce qui est une réponse : pas une erreur.
        QueryResult except = run("SELECT customer_id FROM k_orders EXCEPT SELECT customer_id FROM k_customers");
        assertNull(except.error(), String.valueOf(except.error()));
        assertEquals("FLINK", except.engine());
    }

    @Test
    void expressionsAndLiteralSourcesRunOnThePlanner() {
        answered("SELECT CASE WHEN amount > 100 THEN 'BIG' ELSE 'SMALL' END AS bucket, "
                + "CAST(amount AS INT) AS rounded FROM k_orders");
        answered("SELECT UPPER(order_id) AS u, SUBSTRING(order_id, 1, 3) AS prefix, "
                + "CHAR_LENGTH(state) AS len FROM k_orders");
        answered("SELECT * FROM (VALUES ('a', 1), ('b', 2)) AS t(k, v)");
        answered("WITH big AS (SELECT * FROM k_orders WHERE amount > 60) SELECT order_id FROM big");
    }

    /**
     * Trier et paginer.
     *
     * <p>Un {@code ORDER BY} <em>borné</em> passe, l'illimité non — voir le refus plus bas. La
     * distinction est celle du flux, et elle mérite d'être écrite : c'est la même requête à un
     * {@code LIMIT} près.
     */
    @Test
    void orderingAndPagingRunWhenTheyAreBounded() {
        answered("SELECT order_id FROM k_orders ORDER BY order_id LIMIT 2");
        answered("SELECT order_id FROM k_orders LIMIT 2 OFFSET 1");
    }

    /**
     * Les fenêtres <em>par le planner</em>, sur une table qui porte un watermark.
     *
     * <p>Les cas existants couvrent l'approximation du lecteur direct — qui range HOP et SESSION
     * sur TUMBLE et le dit dans les warnings. Ici la fenêtre est calculée par Flink : le résultat
     * ne porte donc aucun avertissement d'approximation, et c'est cela qu'il faut pouvoir
     * distinguer.
     */
    @Test
    void windowFunctionsRunOnThePlannerWithoutApproximation() {
        QueryResult tumble = run("SELECT window_start, COUNT(*) AS n FROM TABLE("
                + "TUMBLE(TABLE k_events, DESCRIPTOR(ts), INTERVAL '1' MINUTE)) "
                + "GROUP BY window_start, window_end");
        assertNull(tumble.error(), String.valueOf(tumble.error()));
        assertEquals("FLINK", tumble.engine());
        assertTrue(tumble.warnings().stream().noneMatch(x -> x.toLowerCase(Locale.ROOT).contains("approximat")),
                "une fenêtre calculée par Flink n'est approximée par rien : " + tumble.warnings());

        answered("SELECT window_start, COUNT(*) AS n FROM TABLE("
                + "HOP(TABLE k_events, DESCRIPTOR(ts), INTERVAL '30' SECOND, INTERVAL '1' MINUTE)) "
                + "GROUP BY window_start, window_end");
    }

    /** Un OVER, la seule agrégation qui garde chaque ligne — hors de portée du lecteur direct. */
    @Test
    void anOverAggregateRunsOnThePlanner() {
        answered("SELECT id, SUM(id) OVER (PARTITION BY id ORDER BY ts) AS total FROM k_events");
    }

    // ── Ce que le planner refuse, et à qui il l'impute ────────────────────────────────

    /**
     * Ce que le mode streaming ne sait pas construire revient à l'appelant, avec la phrase du
     * planner — pas repliée sur un lecteur qui répondrait autre chose.
     *
     * <p>C'est le défaut que cette énumération a trouvé. Un {@code ORDER BY} non borné et un
     * {@code EXISTS} corrélé sont du SQL valide qui n'a pas de sens sur un flux ; classés en panne
     * moteur, ils tombaient sur le lecteur direct, qui ne connaît que des topics Kafka et
     * répondait <em>« Table 'k_orders' not found. No matching Kafka topic exists. »</em> — sur une
     * table parfaitement présente au catalogue — pendant que la vraie raison finissait dans les
     * warnings, où rien ne la lit.
     */
    @Test
    void whatTheStreamingPlannerCannotBuildComesBackWithItsOwnReason() {
        QueryResult sorted = run("SELECT order_id FROM k_orders ORDER BY order_id");
        assertNotNull(sorted.error());
        assertTrue(sorted.error().toLowerCase(Locale.ROOT).contains("sort on a non-time-attribute"),
                "le refus doit être celui du planner : " + sorted.error());
        assertFalse(sorted.error().contains("not found"),
                "et surtout pas l'avis du lecteur direct sur une table qui existe : " + sorted.error());

        QueryResult correlated = run("SELECT order_id FROM k_orders o WHERE EXISTS ("
                + "SELECT 1 FROM k_customers c WHERE c.customer_id = o.customer_id)");
        assertNotNull(correlated.error());
        assertTrue(correlated.error().toLowerCase(Locale.ROOT).contains("correlate variable"),
                correlated.error());
        assertFalse(correlated.error().contains("not found"), correlated.error());
    }

    // ── EXPLAIN, dans ses trois formes ────────────────────────────────────────────────

    @Test
    void everyExplainFormIsAnswered() {
        answered("EXPLAIN SELECT * FROM k_orders");
        answered("EXPLAIN PLAN FOR SELECT * FROM k_orders");
        answered("EXPLAIN CHANGELOG_MODE SELECT state, COUNT(*) AS n FROM k_orders GROUP BY state");
    }

    // ── CREATE TABLE, et ce qui lui ressemble ─────────────────────────────────────────

    @Test
    void theCreateTableVariantsThisApplicationRunsAreExecuted() {
        answered("CREATE TABLE k_kinds_a (id STRING) WITH ('connector'='blackhole')");
        answered("CREATE TABLE IF NOT EXISTS k_kinds_a (id STRING) WITH ('connector'='blackhole')");
        answered("CREATE TABLE k_kinds_like LIKE k_sink");
        assertTrue(service.listTables().contains("k_kinds_a"));
        assertTrue(service.listTables().contains("k_kinds_like"));
    }

    /**
     * {@code CREATE TABLE … AS SELECT} est un INSERT sous un autre nom, et il passait.
     *
     * <p>La whitelist classe sur le premier mot, donc un CTAS la franchissait — sur le chemin de la
     * <em>lecture</em>, celui-là même qui refuse les INSERT en les renvoyant vers le mode Job. Il y
     * créait la table <strong>et démarrait le job qui l'alimente</strong>, sans passer par
     * {@code submitJob} : aucun enregistrement au magasin, donc invisible au tableau de bord, hors
     * du plafond {@code explorer.max-concurrent-jobs}, et sans identifiant pour
     * {@code POST /api/query/cancel/{queryId}}. Sur une source Kafka, c'est un job continu que rien
     * ne peut voir ni arrêter.
     *
     * <p>Le refus nomme les deux gestes qui font la même chose de façon visible.
     */
    @Test
    void aCtasIsAnInsertWearingACreateTableHat() {
        QueryResult ctas = run("CREATE TABLE k_kinds_ctas WITH ('connector'='blackhole') "
                + "AS SELECT order_id FROM k_orders");

        assertNotNull(ctas.error());
        assertTrue(ctas.error().contains("INSERT INTO"),
                "le refus doit nommer le geste équivalent : " + ctas.error());
        assertFalse(service.listTables().contains("k_kinds_ctas"),
                "rien ne doit avoir été créé : le refus vient avant l'exécution");
    }

    /** Le même test, sans le planner : la lecture de la forme est à part et se teste seule. */
    @Test
    void aCreateTableIsNotMistakenForACtasBecauseOfAStringLiteral() {
        assertTrue(FlinkSqlService.isCreateTableAsSelect("CREATE TABLE T AS SELECT A FROM B"));
        assertTrue(FlinkSqlService.isCreateTableAsSelect(
                "CREATE TABLE T WITH ('CONNECTOR'='BLACKHOLE') AS SELECT A FROM B"));
        assertTrue(FlinkSqlService.isCreateTableAsSelect(
                "CREATE TABLE T AS WITH X AS (SELECT 1) SELECT * FROM X"));

        // Une option peut contenir ce texte ; refuser là-dessus serait un faux positif sur la
        // seule DDL que cette application accepte.
        assertFalse(FlinkSqlService.isCreateTableAsSelect(
                "CREATE TABLE T (ID STRING) WITH ('NOTE'='AS SELECT SOMETHING')"));
        // La colonne calculée que tout DDL généré porte.
        assertFalse(FlinkSqlService.isCreateTableAsSelect(
                "CREATE TABLE T (ID STRING, PROC_TIME AS PROCTIME()) WITH ('CONNECTOR'='KAFKA')"));
        assertFalse(FlinkSqlService.isCreateTableAsSelect("SELECT * FROM T"));
    }

    // ── Ce que la whitelist refuse, famille par famille ───────────────────────────────

    /**
     * Une seule instruction par famille était épinglée (INSERT, DROP, UPDATE, DELETE, TRUNCATE).
     *
     * <p>Ce qui manquait est ce qu'un opérateur tape le plus naturellement en arrivant d'un client
     * SQL — {@code SHOW TABLES}, {@code DESCRIBE}, {@code USE}, {@code SET} — et les DDL que Flink
     * accepte mais que cette application ne veut pas exécuter. Le message est délibérément le même
     * pour toutes : c'est une restriction, et elle se lit comme telle.
     */
    @Test
    void everyOtherStatementKindIsRefusedByTheWhitelist() {
        for (String sql : List.of(
                "SHOW TABLES",
                "DESCRIBE k_orders",
                "USE CATALOG default_catalog",
                "SET 'table.exec.resource.default-parallelism' = '1'",
                "RESET 'table.exec.resource.default-parallelism'",
                "ALTER TABLE k_sink RENAME TO k_sink_renamed",
                "CREATE VIEW k_kinds_view AS SELECT * FROM k_orders",
                "CREATE TEMPORARY TABLE k_kinds_tmp (id STRING) WITH ('connector'='blackhole')",
                "ANALYZE TABLE k_orders COMPUTE STATISTICS",
                "LOAD MODULE hive",
                "   ")) {
            QueryResult refused = run(sql);
            assertNotNull(refused.error(), sql);
            assertTrue(refused.error().contains("Only SELECT, EXPLAIN and CREATE TABLE"),
                    sql + " → " + refused.error());
            assertTrue(refused.rows().isEmpty(), sql);
        }
        assertFalse(service.listTables().contains("k_kinds_tmp"),
                "une DDL refusée ne doit rien avoir créé");
    }

    // ── Outils ────────────────────────────────────────────────────────────────────────

    private QueryResult run(String sql) {
        return service.executeSql(QueryRequest.sql(sql, 20, 15_000L, null));
    }

    /** Exécute, exige que le planner ait répondu, et rend au moins une ligne. */
    private void answered(String sql) {
        QueryResult result = run(sql);
        assertNull(result.error(), sql + " → " + result.error());
        assertEquals("FLINK", result.engine(), sql + " doit être répondu par le planner");
        assertFalse(result.rows().isEmpty(), sql + " doit rendre au moins une ligne");
    }
}
