// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.FlinkJobSummary;
import com.compagnonsdudev.kafkasqlexplorer.domain.FlinkManagedJobDetails;
import com.compagnonsdudev.kafkasqlexplorer.domain.MessageFormat;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Les formes qu'un {@code INSERT} peut prendre, et ce que chacune répond.
 *
 * <p>Le mode Job est le seul geste de l'éditeur SQL <em>sans repli</em> : un SELECT qui échoue
 * retombe sur le lecteur direct et rend quand même des lignes, un INSERT n'a rien derrière lui.
 * Ce qu'il répond est donc tout ce que l'opérateur obtient — et jusqu'ici une seule forme était
 * couverte, {@code INSERT INTO <sink> SELECT * FROM <source>}, sur un {@code TableResult}
 * bouchonné qui ne soumettait rien. Tout le reste — une liste de colonnes, un {@code VALUES},
 * une agrégation, une jointure, un {@code INSERT OVERWRITE}, un point-virgule final — n'était
 * assuré par rien, alors que chacune traverse trois gardes écrits ici : la classification de
 * l'instruction ({@code extractStatementType}), l'auto-enregistrement de la source
 * ({@code autoRegisterTableIfNeeded}) et le validateur.
 *
 * <p>Les cas qui aboutissent soumettent un <strong>vrai job</strong> au MiniCluster local, sur des
 * sources bornées et un sink {@code blackhole} : une soumission qui « réussit » sans que Flink ait
 * rendu de {@code JobClient} ne prouverait rien, et c'est précisément ce qu'un mock rendait
 * possible. Les cas qui échouent vérifient <em>qui</em> est déclaré fautif — la classification
 * décide du statut HTTP servi par {@code QueryController.submitJob}, et un refus rangé en panne
 * moteur envoie l'opérateur chercher un incident là où il a une faute de frappe.
 *
 * <p>Deux défauts sont sortis de cette énumération et sont épinglés ici :
 * <ul>
 *   <li>une projection qui ne rentre pas dans le sink <em>par le nombre de colonnes</em> était une
 *       panne moteur (500) quand la même faute <em>par le type</em> était une erreur d'appelant
 *       (400) — voir {@code aProjectionThatDoesNotFitTheSinkIsTheCallersFault} ;</li>
 *   <li>{@code INSERT OVERWRITE} franchit le garde du mode Job (qui classe sur le premier mot)
 *       mais était refusé par l'auto-enregistrement, donc sa source répondait « Object not found »
 *       sur un nom de topic correct — voir {@code insertOverwriteRegistersItsSourceLikeAnyInsert}.</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlinkSqlServiceInsertVariantsTest {

    private StreamTableEnvironment tableEnv;
    private FlinkSqlService service;
    private FlinkJobStore jobStore;
    private ExplorerConfig config;
    private KafkaAdminService kafkaAdminService;
    private SchemaInferenceService schemaInferenceService;
    private DdlGeneratorService ddlGeneratorService;

    @BeforeAll
    void setup() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment();
        // Un job dont le parallélisme dépasse les slots disponibles ne se déploie jamais : il
        // reste SCHEDULED jusqu'à expiration du budget, sans erreur. Le défaut est documenté dans
        // FlinkConfig ; ici la donnée tient en deux lignes, donc un slot suffit et le fixer met la
        // suite à l'abri du nombre de cœurs de la machine qui l'exécute.
        env.setParallelism(1);
        tableEnv = StreamTableEnvironment.create(env,
                EnvironmentSettings.newInstance().inStreamingMode().build());

        config = new ExplorerConfig();
        config.setDefaultMaxRows(50);
        config.setDefaultQueryTimeoutMs(10_000);
        config.setFlinkJobStorePath(Files.createTempFile("insert-variants-jobs-", ".json").toString());
        config.setFlinkTableStorePath(Files.createTempFile("insert-variants-tables-", ".json").toString());
        // Les défauts livrés : le validateur refuse les jointures croisées, et c'est un des refus
        // que cette classe vérifie.
        assertFalse(config.isAllowCrossJoin());

        kafkaAdminService = mock(KafkaAdminService.class);
        when(kafkaAdminService.listTopics()).thenReturn(List.of());
        schemaInferenceService = mock(SchemaInferenceService.class);
        ddlGeneratorService = mock(DdlGeneratorService.class);

        FlinkRuntimeCoordinator coordinator = new FlinkRuntimeCoordinator(tableEnv);
        SqlQueryValidator validator = new SqlQueryValidator(config, tableEnv, coordinator);
        jobStore = new FlinkJobStore(config);
        service = new FlinkSqlService(tableEnv, coordinator, config, validator, kafkaAdminService,
                schemaInferenceService, ddlGeneratorService, jobStore, new FlinkTableStore(config));

        // Sources bornées : le job se termine tout seul, donc rien ne reste en vol d'un cas à
        // l'autre même si l'annulation arrive après la fin.
        tableEnv.createTemporaryView("ins_orders",
                tableEnv.fromValues(
                        DataTypes.ROW(
                                DataTypes.FIELD("order_id", DataTypes.STRING()),
                                DataTypes.FIELD("amount", DataTypes.DOUBLE()),
                                DataTypes.FIELD("state", DataTypes.STRING()),
                                DataTypes.FIELD("customer_id", DataTypes.STRING())),
                        Row.of("ORD-001", 599.99, "RECEIVED", "C-001"),
                        Row.of("ORD-002", 12.00, "SHIPPED", "C-002")));

        tableEnv.createTemporaryView("ins_customers",
                tableEnv.fromValues(
                        DataTypes.ROW(
                                DataTypes.FIELD("customer_id", DataTypes.STRING()),
                                DataTypes.FIELD("name", DataTypes.STRING())),
                        Row.of("C-001", "Alice"),
                        Row.of("C-002", "Bob")));

        tableEnv.executeSql("CREATE TABLE IF NOT EXISTS ins_sink "
                + "(order_id STRING, amount DOUBLE) WITH ('connector'='blackhole')");
        tableEnv.executeSql("CREATE TABLE IF NOT EXISTS ins_counts "
                + "(state STRING, n BIGINT) WITH ('connector'='blackhole')");
        // Sans fin, comme un topic Kafka : le job du cas sur le plafond doit être encore tenu
        // quand la soumission suivante arrive.
        tableEnv.executeSql("CREATE TABLE IF NOT EXISTS ins_infinite "
                + "(order_id STRING, amount DOUBLE) WITH ('connector'='datagen','rows-per-second'='2')");
        tableEnv.executeSql("CREATE TABLE IF NOT EXISTS ins_partitioned "
                + "(order_id STRING, dt STRING) PARTITIONED BY (dt) WITH ('connector'='blackhole')");
    }

    // ── Ce qui part comme job ─────────────────────────────────────────────────────────

    @Test
    void theOrdinaryInsertSelectIsSubmittedAsAJob() {
        FlinkJobSummary summary = service.submitJob(QueryRequest.sql(
                "INSERT INTO ins_sink SELECT order_id, amount FROM ins_orders", 50, 10_000L, null));

        assertEquals("INSERT", summary.statementType());
        assertFalse(summary.queryId().isBlank());
        assertFalse(summary.flinkJobId().isBlank(), "Flink a rendu un JobClient, donc le job existe");
        FlinkManagedJobDetails persisted = jobStore.findById(summary.queryId()).orElseThrow();
        assertEquals("INSERT", persisted.statementType());
        assertEquals("ASYNC_JOB", persisted.executionMode());
        assertNull(persisted.errorMessage());

        service.cancelJob(summary.queryId());
    }

    /**
     * Une liste de colonnes explicite, dans l'ordre du sink ou non, complète ou non.
     *
     * <p>C'est la forme que la barre latérale de l'éditeur génère elle-même en mode Job
     * ({@code insertableColumns} : elle nomme les colonnes plutôt que d'écrire {@code SELECT *},
     * pour laisser dehors la colonne calculée {@code proc_time} du DDL généré), donc c'est la
     * forme qu'un opérateur soumet en un clic.
     */
    @Test
    void anExplicitColumnListIsAccepted() {
        submitted("INSERT INTO ins_sink (order_id, amount) SELECT order_id, amount FROM ins_orders");
        submitted("INSERT INTO ins_sink (amount, order_id) SELECT amount, order_id FROM ins_orders");
        submitted("INSERT INTO ins_sink (order_id) SELECT order_id FROM ins_orders");
    }

    /**
     * Un {@code INSERT} n'a pas forcément de source à enregistrer.
     *
     * <p>{@code extractPrimaryTable} lit le {@code FROM} et rend {@code null} quand il n'y en a
     * pas : l'auto-enregistrement doit alors <em>passer son tour</em>, pas échouer. C'est le seul
     * moyen d'écrire une ligne dans un topic depuis l'éditeur — ce qu'on fait pour amorcer un
     * pipeline ou vérifier qu'un sink est bien branché.
     */
    @Test
    void anInsertWithNoSourceTableIsAccepted() {
        submitted("INSERT INTO ins_sink VALUES ('ORD-9', 10.0)");
        submitted("INSERT INTO ins_sink VALUES ('A', 1.0), ('B', 2.0)");
        submitted("INSERT INTO ins_sink (order_id, amount) VALUES ('C', 3.0)");
    }

    /**
     * La source peut être n'importe quelle requête — y compris les deux formes que le lecteur
     * direct ne sait pas servir (jointure, sous-requête), qui n'ont ici aucun repli et doivent
     * donc passer par le planner.
     */
    @Test
    void theSourceMayBeAnyQueryShape() {
        submitted("INSERT INTO ins_sink SELECT order_id, amount FROM ins_orders WHERE amount > 1");
        submitted("INSERT INTO ins_counts SELECT state, COUNT(*) FROM ins_orders GROUP BY state");
        submitted("INSERT INTO ins_sink SELECT o.order_id, o.amount FROM ins_orders o "
                + "JOIN ins_customers c ON o.customer_id = c.customer_id");
        submitted("INSERT INTO ins_sink SELECT order_id, amount FROM ins_orders "
                + "UNION ALL SELECT order_id, amount FROM ins_orders");
        submitted("INSERT INTO ins_sink SELECT order_id, amount FROM (SELECT * FROM ins_orders) t");
    }

    /**
     * La casse, les commentaires de tête et le point-virgule final ne changent pas ce que
     * l'instruction est.
     *
     * <p>Les trois gardes du chemin sont des {@code startsWith} sur le texte : une minuscule ou un
     * commentaire en première ligne les fait échouer si rien ne les normalise, et c'est le défaut
     * qui avait rendu les CTE inexécutables. Le point-virgule est ce que colle un opérateur qui
     * copie une instruction depuis un script.
     */
    @Test
    void theStatementIsRecognisedPastItsPresentation() {
        submitted("insert into ins_sink select order_id, amount from ins_orders");
        submitted("-- alimente le sink\nINSERT INTO ins_sink SELECT order_id, amount FROM ins_orders");
        submitted("/* alimente le sink */ INSERT INTO ins_sink SELECT order_id, amount FROM ins_orders");
        submitted("INSERT INTO ins_sink SELECT order_id, amount FROM ins_orders;");
    }

    /**
     * Un CTE se place <em>dans</em> l'INSERT, jamais devant.
     *
     * <p>{@code extractStatementType} classe l'instruction après la chaîne {@code WITH … AS ( … )}
     * de tête, si bien qu'un {@code WITH … INSERT INTO} est bien reconnu comme un INSERT et
     * atteint Flink — qui le refuse, la grammaire de Calcite n'admettant pas cette forme. Le refus
     * est donc celui du parseur, avec sa position, et il est classé comme une faute de l'appelant
     * (400) et non comme une panne (500). La forme valide, elle, passe.
     */
    @Test
    void aCteBelongsInsideTheInsertNotBeforeIt() {
        submitted("INSERT INTO ins_sink WITH recent AS (SELECT order_id, amount FROM ins_orders) "
                + "SELECT * FROM recent");

        SqlErrorClassifier.Classification refusal = refused(
                "WITH recent AS (SELECT order_id, amount FROM ins_orders) "
                        + "INSERT INTO ins_sink SELECT * FROM recent");

        assertTrue(refusal.isUserError(), refusal.message());
        assertTrue(refusal.message().contains("SQL parse failed"), refusal.message());
    }

    /** Une clause {@code PARTITION} atteint Flink, qui décide selon le sink. */
    @Test
    void aPartitionClauseReachesFlink() {
        submitted("INSERT INTO ins_partitioned PARTITION (dt='2026-01-01') "
                + "SELECT order_id FROM ins_orders");
    }

    // ── Ce qui est refusé, et par qui ─────────────────────────────────────────────────

    /**
     * Une projection qui ne rentre pas dans le sink est une faute de l'appelant — <em>par le
     * nombre de colonnes comme par le type</em>.
     *
     * <p>Flink dit ces deux fautes de deux façons, et seule la seconde était reconnue : la même
     * erreur — la requête ne correspond pas à la table cible — répondait 400 dans un cas et 500
     * dans l'autre. Et c'est le cas mal classé qui est le plus courant :
     * {@code INSERT INTO <sink> SELECT * FROM <source>} sur une table auto-générée ramène la
     * colonne calculée {@code proc_time} qu'aucun sink n'accepte, ce que la barre latérale évite
     * précisément en nommant les colonnes. Un 500 dit « le serveur est en panne » et envoie
     * chercher un incident qui n'existe pas.
     */
    @Test
    void aProjectionThatDoesNotFitTheSinkIsTheCallersFault() {
        SqlErrorClassifier.Classification byArity =
                refused("INSERT INTO ins_sink SELECT * FROM ins_orders");
        assertTrue(byArity.isUserError(),
                "une arité qui ne colle pas n'est pas une panne du moteur : " + byArity.message());
        assertTrue(byArity.message().toLowerCase(Locale.ROOT).contains("different number of columns"),
                byArity.message());

        SqlErrorClassifier.Classification byType =
                refused("INSERT INTO ins_sink SELECT amount, order_id FROM ins_orders");
        assertTrue(byType.isUserError(), byType.message());
        assertEquals(byArity.kind(), byType.kind(),
                "une même faute — la requête ne rentre pas dans le sink — ne peut pas avoir deux statuts");
    }

    /** Un nom qui ne résout nulle part, des deux côtés de l'instruction. */
    @Test
    void aNameThatResolvesToNothingIsTheCallersFault() {
        SqlErrorClassifier.Classification sink =
                refused("INSERT INTO no_such_sink SELECT order_id, amount FROM ins_orders");
        assertTrue(sink.isUserError(), sink.message());
        assertTrue(sink.message().contains("no_such_sink"), sink.message());

        SqlErrorClassifier.Classification source =
                refused("INSERT INTO ins_sink SELECT order_id, amount FROM no_such_source");
        assertTrue(source.isUserError(), source.message());
        assertTrue(source.message().contains("no_such_source"), source.message());

        SqlErrorClassifier.Classification column =
                refused("INSERT INTO ins_sink SELECT order_id, nope FROM ins_orders");
        assertTrue(column.isUserError(), column.message());
        assertTrue(column.message().contains("nope"), column.message());

        assertTrue(refused("INSERT INTO ins_sink SELEKT order_id FROM ins_orders").isUserError());
    }

    /**
     * {@code INSERT OVERWRITE} franchit le garde du mode Job, et enregistre sa source comme
     * n'importe quel INSERT.
     *
     * <p>Le garde classe sur le premier mot, donc {@code INSERT OVERWRITE} passe : c'est le bon
     * arbitrage — le refus de Flink nomme la cause exacte (ce sink n'implémente pas
     * {@code SupportsOverwrite}) là où un refus écrit ici ne dirait que « seuls les INSERT INTO
     * sont acceptés ». Mais l'auto-enregistrement, lui, ne reconnaissait que {@code INSERT INTO} :
     * la source d'un {@code INSERT OVERWRITE} n'était jamais enregistrée, et Flink répondait
     * « Object not found » sur un nom de topic parfaitement correct — le défaut même que
     * {@code submitJob} avait corrigé pour {@code INSERT INTO}, resté debout à un mot-clé près.
     *
     * <p>Le refus qui suit est celui du sink, et il est classé comme une faute de l'appelant : la
     * table ne sait pas faire ce que l'instruction lui demande, le moteur n'est pas tombé.
     */
    @Test
    void insertOverwriteRegistersItsSourceLikeAnyInsert() throws Exception {
        // Un topic dont le nom pointé devient une table à underscores, comme sur un vrai cluster.
        when(kafkaAdminService.listTopics()).thenReturn(List.of("ins.over.src"));
        when(schemaInferenceService.detectFormat("ins.over.src")).thenReturn(MessageFormat.JSON);
        Map<String, String> schema = new LinkedHashMap<>();
        schema.put("order_id", "STRING");
        schema.put("amount", "DOUBLE");
        when(schemaInferenceService.inferSchema(anyString(), any(MessageFormat.class))).thenReturn(schema);
        when(ddlGeneratorService.generateDdl(anyString(), any(), any())).thenReturn(
                "CREATE TABLE ins_over_src (order_id STRING, amount DOUBLE) "
                        + "WITH ('connector'='datagen','number-of-rows'='1')");

        assertFalse(service.listTables().contains("ins_over_src"),
                "la source doit être absente, sinon ce cas ne prouve rien de l'enregistrement");

        SqlErrorClassifier.Classification refusal = refused(
                "INSERT OVERWRITE ins_sink SELECT order_id, amount FROM ins_over_src");

        assertTrue(service.listTables().contains("ins_over_src"),
                "la source doit avoir été enregistrée par la soumission elle-même");
        assertTrue(refusal.message().contains("SupportsOverwrite"),
                "le refus doit être celui du sink, pas un « table inconnue » sur un nom correct : "
                        + refusal.message());
        assertTrue(refusal.isUserError(),
                "un sink qui ne sait pas écraser n'est pas une panne du moteur : " + refusal.message());

        when(kafkaAdminService.listTopics()).thenReturn(List.of());
    }

    /**
     * Un hint d'options est refusé quand il porte sur une vue — et c'est l'instruction qui est en
     * cause, pas le moteur. Un hint sur une <em>table</em>, lui, atteint bien le planner : c'est
     * ce que {@code stripSqlComments} préserve, un hint Calcite étant en forme de commentaire.
     */
    @Test
    void aHintOnAViewIsTheCallersFault() {
        SqlErrorClassifier.Classification refusal = refused(
                "INSERT INTO ins_sink SELECT order_id, amount FROM ins_orders "
                        + "/*+ OPTIONS('scan.startup.mode'='earliest-offset') */");

        assertTrue(refusal.message().contains("Hints can only be applied to tables"),
                "le hint doit avoir atteint le planner, donc ne pas avoir été effacé : " + refusal.message());
        assertTrue(refusal.isUserError(), refusal.message());
    }

    /** Une soumission ne porte qu'une instruction : Flink refuse, et le refus est un 400. */
    @Test
    void severalStatementsInOneSubmissionAreRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> service.submitJob(QueryRequest.sql(
                        "INSERT INTO ins_sink VALUES ('A', 1.0); INSERT INTO ins_sink VALUES ('B', 2.0)",
                        50, 10_000L, null)));

        assertTrue(refused.getMessage().contains("Run all"), refused.getMessage());
        assertTrue(refused.getMessage().contains("STATEMENT SET"),
                "le refus doit nommer la forme qui réunit plusieurs INSERT en un job : "
                        + refused.getMessage());
    }

    /**
     * Ce que le mode Job refuse d'exécuter est refusé <em>et consigné</em>.
     *
     * <p>Le refus est une {@code IllegalArgumentException}, que le contrôleur rend en 400 avec sa
     * phrase ; et il laisse une trace FAILED dans le magasin, qui est ce que la carte du tableau
     * de bord affiche — sans quoi la raison n'existerait nulle part.
     */
    @Test
    void whatJobModeDoesNotRunIsRefusedAndRecorded() {
        for (String sql : List.of(
                "SELECT order_id FROM ins_orders",
                "CREATE TABLE ins_nope (id STRING) WITH ('connector'='blackhole')",
                "DROP TABLE ins_sink")) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> service.submitJob(QueryRequest.sql(sql, 50, 10_000L, null)), sql);
            assertTrue(refused.getMessage().contains("Only INSERT and STATEMENT SET statements are allowed"),
                    refused.getMessage());

            FlinkManagedJobDetails recorded = jobStore.listAll().stream()
                    .filter(j -> sql.equals(j.sql()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("aucune trace du refus pour : " + sql));
            assertEquals("FAILED", recorded.status());
            assertTrue(recorded.errorMessage().contains("Only INSERT and STATEMENT SET statements are allowed"),
                    recorded.errorMessage());
        }
    }

    /** Le garde des jointures croisées vaut aussi en mode Job — le validateur y passe en premier. */
    @Test
    void aCrossJoinIsRefusedInJobModeToo() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> service.submitJob(QueryRequest.sql(
                        "INSERT INTO ins_sink SELECT o.order_id, o.amount FROM ins_orders o "
                                + "CROSS JOIN ins_customers c", 50, 10_000L, null)));

        assertTrue(refused.getMessage().contains("Cross joins are not allowed"), refused.getMessage());
    }

    // ── Le mode lecture, en face ──────────────────────────────────────────────────────

    /**
     * En mode lecture, toutes ces formes reçoivent la même réponse : celle qui nomme la cause.
     *
     * <p>{@code executeSync} classe l'instruction sur la même règle que la soumission, donc une
     * minuscule, un commentaire de tête, un corps en CTE ou un {@code INSERT OVERWRITE} ne peuvent
     * pas glisser à travers la whitelist et se faire refuser avec « Only SELECT, EXPLAIN, SHOW,
     * DESCRIBE and CREATE TABLE » — un message qui se lit comme une restriction de sécurité alors
     * que ce qui est en jeu est la forme de cet écran.
     *
     * <p>Ce cas exigeait auparavant que le refus <em>dise où aller</em>, en nommant
     * {@code /api/query/jobs}. Ce point d'entrée a été retiré avec le mode Job, donc l'exigence
     * s'est inversée : le refus doit nommer la cause et surtout <strong>ne plus nommer</strong> une
     * porte qui n'existe plus. C'est la même correction que
     * {@code FlinkSqlServiceJobRegistryTest.executeSyncRefusesAnInsertWithoutPointingAtARemovedEndpoint},
     * qui la pinçait sur une seule forme là où celui-ci l'exige sur les cinq.
     */
    @Test
    void everyInsertShapeIsRefusedInReadModeWithoutPointingAtARemovedEndpoint() {
        for (String sql : List.of(
                "INSERT INTO ins_sink SELECT order_id, amount FROM ins_orders",
                "insert into ins_sink values ('A', 1.0)",
                "-- commentaire\nINSERT INTO ins_sink VALUES ('B', 2.0)",
                "INSERT INTO ins_sink WITH r AS (SELECT order_id, amount FROM ins_orders) SELECT * FROM r",
                "INSERT OVERWRITE ins_sink SELECT order_id, amount FROM ins_orders")) {
            QueryResult result = service.executeSync(QueryRequest.sql(sql, 50, 10_000L, null));

            assertNotNull(result.error(), sql);
            assertTrue(result.error().contains("INSERT INTO is not run by this application"),
                    "le refus doit nommer la cause : " + result.error() + " — " + sql);
            assertFalse(result.error().contains("/api/query/jobs"),
                    "et ne pas nommer une porte retirée : " + result.error() + " — " + sql);
            assertTrue(result.rows().isEmpty(), sql);
        }
    }

    // ── Ce que la soumission fait avant d'exécuter ────────────────────────────────────

    /**
     * Un STATEMENT SET est un job, pas plusieurs.
     *
     * <p>C'est la forme Flink du fan-out : plusieurs INSERT depuis une même source dans un seul
     * job, donc <em>une seule</em> lecture du topic. Elle était refusée par le garde du mode Job,
     * et l'équivalent — N soumissions — coûte N lectures de la même source et N clusters Flink
     * embarqués. Les sources de chacun des INSERT sont enregistrées comme celles d'un INSERT seul.
     */
    @Test
    void aStatementSetIsSubmittedAsOneJob() {
        FlinkJobSummary summary = service.submitJob(QueryRequest.sql(
                "EXECUTE STATEMENT SET BEGIN "
                        + "INSERT INTO ins_sink SELECT order_id, amount FROM ins_orders; "
                        + "INSERT INTO ins_counts SELECT state, COUNT(*) FROM ins_orders GROUP BY state; "
                        + "END", 50, 10_000L, null));

        assertEquals("STATEMENT_SET", summary.statementType());
        assertFalse(summary.flinkJobId().isBlank(), "les deux INSERT partagent un seul job Flink");

        service.cancelJob(summary.queryId());
    }

    /**
     * Toutes les sources sont enregistrées, pas seulement la première.
     *
     * <p>Une jointure entre deux topics non enregistrés répondait « Object not found » sur le
     * second — un nom parfaitement correct — et le mode Job n'a aucun repli pour rattraper ça.
     */
    @Test
    void everySourceOfTheStatementIsRegisteredNotOnlyTheFirst() throws Exception {
        when(kafkaAdminService.listTopics()).thenReturn(List.of("ins.left.src", "ins.right.src"));
        when(schemaInferenceService.detectFormat(anyString())).thenReturn(MessageFormat.JSON);
        Map<String, String> schema = new LinkedHashMap<>();
        schema.put("order_id", "STRING");
        schema.put("amount", "DOUBLE");
        when(schemaInferenceService.inferSchema(anyString(), any(MessageFormat.class))).thenReturn(schema);
        when(ddlGeneratorService.generateDdl(anyString(), any(), any())).thenAnswer(call ->
                "CREATE TABLE " + DdlGeneratorService.toTableName(call.getArgument(0))
                        + " (order_id STRING, amount DOUBLE) "
                        + "WITH ('connector'='datagen','number-of-rows'='1')");

        assertFalse(service.listTables().contains("ins_left_src"));
        assertFalse(service.listTables().contains("ins_right_src"));

        FlinkJobSummary summary = service.submitJob(QueryRequest.sql(
                "INSERT INTO ins_sink SELECT l.order_id, l.amount FROM ins_left_src l "
                        + "JOIN ins_right_src r ON l.order_id = r.order_id", 50, 10_000L, null));

        assertTrue(service.listTables().contains("ins_left_src"));
        assertTrue(service.listTables().contains("ins_right_src"),
                "la table du JOIN doit être enregistrée elle aussi, sinon son nom correct "
                        + "répond « Object not found »");
        service.cancelJob(summary.queryId());
        when(kafkaAdminService.listTopics()).thenReturn(List.of());
    }

    /**
     * L'identifiant de l'appelant est repris, comme en lecture.
     *
     * <p>Sans lui, l'id n'existe que dans la réponse : si elle se perd (délai réseau, onglet
     * fermé), le job tourne et rien ne peut plus l'annuler par son nom.
     */
    @Test
    void aSubmissionKeepsTheQueryIdItsCallerChose() {
        QueryRequest request = QueryRequest.sql(
                "INSERT INTO ins_sink SELECT order_id, amount FROM ins_orders", 50, 10_000L, null);
        FlinkJobSummary summary = service.submitJob(new QueryRequest(
                request.sql(), request.topic(), request.maxRows(), request.timeout(),
                request.readMode(), "insert-variants-42", request.directRead()));

        assertEquals("insert-variants-42", summary.queryId());
        assertEquals(FlinkSqlService.CancelOutcome.CANCELLED, service.cancelJob("insert-variants-42"));
    }

    /**
     * Un job continu de plus n'est pas gratuit, et le refus le dit.
     *
     * <p>Mesuré : chaque soumission démarre son propre MiniCluster — l'exécution locale n'en
     * partage pas — soit ~80 threads par job dans le processus qui sert aussi l'interface. Ce
     * n'est pas une famine de slots (une lecture pendant un INSERT continu répond toujours par le
     * planner, ce qui a été vérifié) : c'est un coût qui s'accumule sans que rien ne le dise.
     */
    @Test
    void pastTheCapASubmissionIsRefusedWithItsCount() {
        config.setMaxConcurrentJobs(1);
        try {
            FlinkJobSummary first = service.submitJob(QueryRequest.sql(
                    "INSERT INTO ins_sink SELECT order_id, amount FROM ins_infinite", 50, 10_000L, null));

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> service.submitJob(QueryRequest.sql(
                            "INSERT INTO ins_sink SELECT order_id, amount FROM ins_infinite",
                            50, 10_000L, null)));

            assertTrue(refused.getMessage().contains("explorer.max-concurrent-jobs"),
                    "le refus doit nommer le réglage : " + refused.getMessage());
            assertTrue(refused.getMessage().contains("1"), refused.getMessage());

            service.cancelJob(first.queryId());
        } finally {
            config.setMaxConcurrentJobs(10);
        }
    }

    /**
     * Le pré-vol attrape une faute de syntaxe dans un INSERT, sans rien soumettre.
     *
     * <p>`SqlQueryValidator` sortait avant l'EXPLAIN pour tout ce qui n'est pas SELECT/EXPLAIN,
     * si bien que l'éditeur — qui appelle `/api/query/validate` avant chaque Run, mode Job compris
     * — ne vérifiait rien du tout sur un INSERT : la faute n'était trouvée qu'en soumettant, ce
     * qui laisse un enregistrement FAILED dans le magasin. Une table non résolue, elle, doit
     * rester avalée : ce contrôle passe avant l'auto-enregistrement des sources.
     */
    @Test
    void thePreflightRejectsAnInsertSyntaxErrorAndTolerantsAnUnresolvedTable() {
        SqlQueryValidator validator = new SqlQueryValidator(
                config, tableEnv, new FlinkRuntimeCoordinator(tableEnv));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> validator.validate("INSERT INTO ins_sink SELEKT order_id FROM ins_orders"));
        assertTrue(refused.getMessage().contains("SQL parse failed"), refused.getMessage());

        assertDoesNotThrow(
                () -> validator.validate("INSERT INTO ins_sink SELECT order_id, amount FROM not_registered_yet"),
                "une table non résolue est attendue à ce stade — la source n'est pas encore enregistrée");
    }

    // ── Outils ────────────────────────────────────────────────────────────────────────

    /** Soumet, exige un job réel, puis rend la main au MiniCluster. */
    private void submitted(String sql) {
        FlinkJobSummary summary = service.submitJob(QueryRequest.sql(sql, 50, 10_000L, null));

        assertEquals("INSERT", summary.statementType(), sql);
        assertNotNull(summary.flinkJobId(), "Flink doit avoir rendu un JobClient : " + sql);
        assertFalse(summary.flinkJobId().isBlank(), sql);

        service.cancelJob(summary.queryId());
    }

    /** Soumet en attendant un refus, et rend sa classification — celle qui décide du statut HTTP. */
    private SqlErrorClassifier.Classification refused(String sql) {
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.submitJob(QueryRequest.sql(sql, 50, 10_000L, null)), sql);
        return SqlErrorClassifier.classify(error);
    }
}
