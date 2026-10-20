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
import java.util.LinkedHashSet;
import java.util.List;
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
 * lit : chaque topic cité doit être créé par un seeder, et chaque instruction doit sortir de la
 * résolution des noms lisible par la grammaire, chaque source désignant la table d'un topic seedé.
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
    private static final Pattern SQL_BLOCK = Pattern.compile("(?s)```sql\\n(.*?)```");
    private static final Pattern ECHO = Pattern.compile("(?m)^echo \"(.*)\"\\s*$");
    private static final Pattern QUOTED_NAME = Pattern.compile("\"([^\"]+)\"");

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
        assertTrue(examples().size() >= 10, "got: " + examples());
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
            Optional<SqlAst.Read> read = SqlAst.read(resolved);
            assertTrue(read.isPresent(), "the grammar cannot read the resolved statement: " + resolved);
            List<String> sources = SqlAst.tableNames(read.get());
            assertFalse(sources.isEmpty(), "no source read in: " + resolved);
            for (String source : sources) {
                assertTrue(topics.stream().anyMatch(t -> DdlGeneratorService.toTableName(t).equals(source)),
                    "`" + source + "` is not the table of a seeded topic, in: " + resolved);
            }
        }));
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
        Matcher echo = ECHO.matcher(Files.readString(ROOT.resolve("setup-demo.sh")));
        while (echo.find()) lines.add(echo.group(1).replace("\\\"", "\""));
        for (int i = 0; i < lines.size(); i++) {
            int select = lines.get(i).indexOf("SELECT ");
            if (select < 0) continue;
            String statement = lines.get(i).substring(select);
            if (i + 1 < lines.size() && lines.get(i + 1).strip().startsWith("FROM ")) {
                statement += " " + lines.get(++i).strip();
            }
            statements.add(statement.replaceFirst("\\s*\\([A-Z]+\\)$", "").strip());
        }
        return statements;
    }
}
