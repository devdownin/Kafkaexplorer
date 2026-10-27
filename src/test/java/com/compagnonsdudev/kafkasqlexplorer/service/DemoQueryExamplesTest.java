// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * Les requêtes que la démo propose s'exécutent sur les topics que la démo crée.
 *
 * <p>{@code docs/QUERY-EXAMPLES.md} promet qu'elles « run out of the box », et {@code setup-demo.sh}
 * les affiche en fin de seed : toutes nomment les topics par leur vrai nom entre guillemets
 * ({@code FROM "demo.orders.1.received"}), et cette écriture répondait « Object not found » jusqu'à
 * {@code withTopicTableNames}. Rien ne l'a vu, parce que rien ne lisait ces fichiers. Ce test les
 * lit, avec les leçons de l'aide intégrée ({@code helpContent.ts}), dont Vitest ne peut vérifier que
 * la forme : chaque topic cité doit être créé par un seeder, et chaque instruction doit sortir de la
 * résolution des noms lisible par la grammaire, chaque source désignant la table d'un topic seedé.
 *
 * <p>Les exemples de migration ksqlDB → Flink lisent les topics péage de {@code setup-demo-peage.sh},
 * seeder facultatif qu'aucun stack ne lance : ils y sont tenus comme les autres.
 *
 * <p>Les topics sont pris dans les seeders et non recopiés ici, sur la règle que
 * {@code docs/check-eval-fixture.py} applique déjà : une liste écrite à côté dérive avec eux.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoQueryExamplesTest {

    private static final Path ROOT = Path.of(".").toAbsolutePath().normalize();

    /** Une entrée du tableau {@code TOPICS} du seeder : {@code "demo.orders.nested:6:…"}. */
    private static final Pattern SEEDED_TOPIC = Pattern.compile("(?m)^\\s*\"(demo\\.[\\w.-]+?)(?::[^\"]*)?\"\\s*$");
    private static final Pattern SC_STEP = Pattern.compile("(?m)^\\s*\"(\\d{2}\\.[\\w.]+)\"\\s*$");
    private static final Pattern AVRO_TOPIC = Pattern.compile("--create --topic (demo\\.avro\\.[\\w.-]+)");
    /** Le seeder péage nomme ses topics dans des variables, puis les passe à {@code create_topic}. */
    private static final Pattern SHELL_VARIABLE = Pattern.compile("(?m)^([A-Z_]+)=\"([\\w.-]+)\"$");
    private static final Pattern CREATED_VARIABLE = Pattern.compile("(?m)^create_topic \"\\$([A-Z_]+)\"");
    private static final Pattern SQL_BLOCK = Pattern.compile("(?s)```sql\\n(.*?)```");
    private static final Pattern ECHO = Pattern.compile("(?m)^echo \"(.*)\"\\s*$");
    private static final Pattern QUOTED_NAME = Pattern.compile("\"([^\"]+)\"");
    /** Le champ {@code sql} d'une leçon : une chaîne entre apostrophes ou un gabarit entre accents graves. */
    private static final Pattern LESSON_SQL =
        Pattern.compile("(?s)\\bsql: (?:'((?:[^'\\\\]|\\\\.)*)'|`((?:[^`\\\\]|\\\\.)*)`)");
    private static final Pattern CTE_NAME = Pattern.compile("(?i)(?:\\bWITH|,)\\s+(\\w+)\\s+AS\\s*\\(");

    private Set<String> topics;
    private FlinkSqlService service;

    @BeforeAll
    void setUp(@TempDir Path tempDir) throws Exception {
        topics = seededTopics();

        StreamTableEnvironment tableEnv = StreamTableEnvironment.create(
            StreamExecutionEnvironment.createLocalEnvironment(),
            EnvironmentSettings.newInstance().inStreamingMode().build());
        ExplorerConfig config = new ExplorerConfig();
        config.setFlinkTableStorePath(tempDir.resolve("flink-tables.json").toString());
        FlinkRuntimeCoordinator coordinator = new FlinkRuntimeCoordinator(tableEnv);
        KafkaAdminService kafka = mock(KafkaAdminService.class);
        doReturn(List.copyOf(topics)).when(kafka).listTopics();
        service = new FlinkSqlService(tableEnv, coordinator, config,
            new SqlQueryValidator(config, tableEnv, coordinator), kafka,
            mock(SchemaInferenceService.class), mock(DdlGeneratorService.class), new FlinkTableStore(config));
    }

    @Test
    void theSeedersAndTheExamplesWereBothRead() throws Exception {
        // Un motif qui ne trouve plus rien ferait passer tous les cas ci-dessous à vide.
        assertTrue(topics.contains("demo.orders.1.received"), "got: " + topics);
        assertTrue(topics.contains("demo.sc.13.carrier.assigned.out"), "got: " + topics);
        assertTrue(topics.contains("demo.avro.orders"), "got: " + topics);
        assertTrue(topics.contains("VA_PEAGE_CLASSIFICATION_FLOWS_EXTEND"), "got: " + topics);
        assertTrue(examples().size() >= 20, "got: " + examples());
        assertTrue(examples().stream().anyMatch(sql -> sql.contains("\"demo.avro.orders\"") && sql.contains("SHIPPED")),
            "the Avro seeder's suggestion was not read: " + examples());
        assertTrue(examples().stream().anyMatch(sql -> sql.contains("TUMBLE(TABLE demo_iot_sensors")),
            "the help's lessons were not read: " + examples());
    }

    @TestFactory
    Stream<DynamicTest> everyDemoQueryNamesSeededTopicsAndResolvesToTheirTables() throws Exception {
        return examples().stream().map(sql -> DynamicTest.dynamicTest(sql.replace('\n', ' '), () -> {
            Matcher quoted = QUOTED_NAME.matcher(SqlStatements.outsideLiterals(sql));
            while (quoted.find()) {
                String name = sql.substring(quoted.start(1), quoted.end(1));
                assertTrue(topics.contains(name), "`" + name + "` is not created by any demo seeder");
            }

            String resolved = service.withTopicTableNames(FlinkSqlService.prepareSql(sql));
            // Le nom d'une CTE est une source pour la grammaire et un topic pour personne.
            Set<String> ctes = new LinkedHashSet<>();
            Matcher cte = CTE_NAME.matcher(resolved);
            while (cte.find()) ctes.add(cte.group(1));
            String body = resolved.replaceFirst("(?i)^EXPLAIN\\s+", "");
            Optional<SqlAst.Read> read = SqlAst.read(SqlStatements.withoutLeadingCte(body));
            assertTrue(read.isPresent(), "the grammar cannot read the resolved statement: " + resolved);
            // Les sources qu'enregistrerait l'auto-enregistrement, sous-requêtes comprises.
            List<String> sources = service.extractSourceTables(body);
            assertFalse(sources.isEmpty(), "no source read in: " + resolved);
            for (String source : sources) {
                if (ctes.contains(source)) continue;
                assertTrue(topics.stream().anyMatch(t -> DdlGeneratorService.toTableName(t).equals(source)),
                    "`" + source + "` is not the table of a seeded topic, in: " + resolved);
            }
        }));
    }

    /**
     * The screenshot fixtures name tables, not quoted topics, and that is deliberate: the editor
     * lists them under that name in its schema browser and the documentation's captions repeat it.
     * What can go wrong is a table no seeder creates — the picture would then show a query that
     * answers "Object not found" on the stack it documents — so each one is held to the seeders.
     */
    @Test
    void theScreenshotFixturesReadTablesOfSeededTopics() throws Exception {
        Pattern from = Pattern.compile("(?im)^\\s*(?:FROM|JOIN)\\s+(demo_\\w+)\\s*$");
        Set<String> tables = new LinkedHashSet<>();
        for (String file : List.of("fixtures.mjs", "capture.mjs", "layout-probe.mjs")) {
            Matcher match = from.matcher(Files.readString(ROOT.resolve("docs/screenshots").resolve(file)));
            while (match.find()) tables.add(match.group(1));
        }
        assertTrue(tables.contains("demo_orders_5_shipped"), "the fixtures were not read: " + tables);
        for (String table : tables) {
            assertTrue(topics.stream().anyMatch(t -> DdlGeneratorService.toTableName(t).equals(table)),
                "`" + table + "` is not the table of a topic any demo seeder creates");
        }
    }

    private static Set<String> seededTopics() throws Exception {
        Set<String> seeded = new LinkedHashSet<>();
        String demo = Files.readString(ROOT.resolve("setup-demo.sh"));
        Matcher topic = SEEDED_TOPIC.matcher(demo);
        while (topic.find()) seeded.add(topic.group(1));
        Matcher step = SC_STEP.matcher(demo);
        while (step.find()) {
            for (String suffix : List.of("in", "out", "audit")) seeded.add("demo.sc." + step.group(1) + "." + suffix);
        }
        Matcher avro = AVRO_TOPIC.matcher(Files.readString(ROOT.resolve("setup-demo-avro.sh")));
        while (avro.find()) seeded.add(avro.group(1));
        String peage = Files.readString(ROOT.resolve("setup-demo-peage.sh"));
        Map<String, String> variables = new HashMap<>();
        Matcher variable = SHELL_VARIABLE.matcher(peage);
        while (variable.find()) variables.put(variable.group(1), variable.group(2));
        Matcher created = CREATED_VARIABLE.matcher(peage);
        while (created.find()) seeded.add(variables.get(created.group(1)));
        return seeded;
    }

    /** Chaque instruction des blocs {@code sql} du guide, puis chaque suggestion affichée par le seeder. */
    private static List<String> examples() throws Exception {
        List<String> statements = new ArrayList<>();
        Matcher block = SQL_BLOCK.matcher(Files.readString(ROOT.resolve("docs/QUERY-EXAMPLES.md")));
        while (block.find()) {
            for (String statement : block.group(1).split(";")) {
                if (!statement.isBlank()) statements.add(statement.strip());
            }
        }

        // Une suggestion tient sur une ligne `echo`, ou sur deux quand la seconde reprend au FROM.
        List<String> lines = new ArrayList<>();
        for (String script : List.of("setup-demo.sh", "setup-demo-avro.sh")) {
            Matcher echo = ECHO.matcher(Files.readString(ROOT.resolve(script)));
            while (echo.find()) lines.add(echo.group(1).replace("\\\"", "\""));
        }
        for (int i = 0; i < lines.size(); i++) {
            int select = lines.get(i).indexOf("SELECT ");
            if (select < 0) continue;
            String statement = lines.get(i).substring(select);
            if (i + 1 < lines.size() && lines.get(i + 1).strip().startsWith("FROM ")) {
                statement += " " + lines.get(++i).strip();
            }
            statements.add(statement.replaceFirst("\\s*\\([A-Z]+\\)$", "").strip());
        }

        // Les leçons qui lisent ; un CREATE TABLE ne lit rien, il déclare.
        String help = Files.readString(ROOT.resolve("src/main/webapp/src/pages/helpContent.ts"));
        String lessons = help.substring(help.indexOf("export const LESSONS"), help.indexOf("export const ENGINE_MATRIX"));
        Matcher lesson = LESSON_SQL.matcher(lessons);
        while (lesson.find()) {
            String sql = (lesson.group(1) != null ? lesson.group(1) : lesson.group(2))
                .replace("\\'", "'").replace("\\`", "`");
            if (sql.strip().matches("(?is)^(SELECT|WITH|EXPLAIN)\\b.*")) statements.add(sql.strip());
        }
        return statements;
    }
}
