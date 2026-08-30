// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.MessageFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DdlGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(DdlGeneratorService.class);
    private final KafkaConfig kafkaConfig;
    private final NamingConventionService namingConventionService;

    public DdlGeneratorService(KafkaConfig kafkaConfig, NamingConventionService namingConventionService) {
        this.kafkaConfig = kafkaConfig;
        this.namingConventionService = namingConventionService;
    }

    /**
     * Ce que Kafka accepte dans un nom de topic, et jusqu'où.
     *
     * <p>La borne de longueur n'est pas décorative : un nom qui arrive d'une requête est recopié
     * dans le DDL généré, et ce DDL repasse ensuite par {@link #maskSensitiveProperties}, dont le
     * motif encadre une alternance de deux {@code [^']*} — quadratique sur une entrée choisie.
     * Tant que le nom venait d'un topic qui existe, la question ne se posait pas ; le point
     * d'entrée qui génère le DDL d'une cible <em>à créer</em> l'a rendue réelle, et CodeQL l'a
     * signalée à ce titre. Refuser ici est de toute façon la bonne réponse : un nom que Kafka ne
     * pourrait pas porter ne décrit aucun topic, et un DDL bâti dessus ne servirait à rien.
     *
     * <p>{@code ExplorerConfig} valide le même alphabet sur un <em>préfixe</em> ; c'est une autre
     * question (un fragment, pas un nom entier), donc les deux règles restent distinctes plutôt
     * que fondues en une qui répondrait mal aux deux.
     */
    private static final Pattern TOPIC_NAME = Pattern.compile("[a-zA-Z0-9._-]+");

    /** La limite de Kafka elle-même. */
    private static final int MAX_TOPIC_NAME_LENGTH = 249;

    /** Whether Kafka could carry this as a topic name at all. */
    public static boolean isValidTopicName(String topic) {
        return topic != null
            && !topic.isBlank()
            && topic.length() <= MAX_TOPIC_NAME_LENGTH
            && !topic.equals(".")
            && !topic.equals("..")
            && TOPIC_NAME.matcher(topic).matches();
    }


    public String generateDdl(String topicName, Map<String, String> schema, MessageFormat format) {
        return generateDdl(topicName, schema, format, "earliest-offset");
    }

    /**
     * Matches DDL properties whose key carries a credential (SSL passwords, Confluent
     * secrets, SASL JAAS config with inline username/password).
     */
    private static final Pattern SENSITIVE_PROP_PATTERN = Pattern.compile(
        "(?i)('[^']*(?:password|secret|sasl\\.jaas\\.config)[^']*'\\s*=\\s*)'[^']*'");

    /**
     * Redacts credential values from a DDL string before it is returned to the UI
     * (topic detail, DDL preview, lineage SHOW CREATE TABLE). The full unmasked DDL is
     * still used internally for table registration — the Flink connector needs the real
     * credentials — but must never reach the browser.
     */
    public static String maskSensitiveProperties(String ddl) {
        if (ddl == null) return null;
        return SENSITIVE_PROP_PATTERN.matcher(ddl).replaceAll("$1'******'");
    }

    /** Key/value split of a sensitive property: group(1) = key, group(2) = value (quotes stripped). */
    private static final Pattern SENSITIVE_KV_PATTERN = Pattern.compile(
        "(?i)'([^']*(?:password|secret|sasl\\.jaas\\.config)[^']*)'\\s*=\\s*'([^']*)'");

    private static final String MASK = "******";

    /**
     * Restores masked credential values in an edited DDL from the stored original. The UI only
     * ever receives DDL with credentials masked ({@link #maskSensitiveProperties}); when it sends
     * that DDL back on save, each {@code '******'} sensitive value is replaced with the real value
     * carried by the same property key in {@code storedDdl}. Non-sensitive edits in
     * {@code editedDdl} are preserved, and secrets the user did not retype are not lost. A masked
     * value with no stored counterpart is left as-is (Flink registration then fails loudly rather
     * than silently persisting a redacted secret).
     */
    public static String restoreMaskedProperties(String editedDdl, String storedDdl) {
        if (editedDdl == null || storedDdl == null || !editedDdl.contains(MASK)) {
            return editedDdl;
        }
        Map<String, String> realValues = new HashMap<>();
        Matcher stored = SENSITIVE_KV_PATTERN.matcher(storedDdl);
        while (stored.find()) {
            realValues.put(stored.group(1).toLowerCase(Locale.ROOT), stored.group(2));
        }

        Matcher edited = SENSITIVE_KV_PATTERN.matcher(editedDdl);
        StringBuilder out = new StringBuilder();
        while (edited.find()) {
            String match = edited.group(0);
            if (MASK.equals(edited.group(2))) {
                String real = realValues.get(edited.group(1).toLowerCase(Locale.ROOT));
                if (real != null) {
                    // The match ends with '******'; swap only the value token, keeping key + spacing.
                    match = match.substring(0, match.length() - ("'" + MASK + "'").length())
                        + "'" + real + "'";
                }
            }
            edited.appendReplacement(out, Matcher.quoteReplacement(match));
        }
        edited.appendTail(out);
        return out.toString();
    }

    /**
     * Converts a Kafka topic name to a valid Flink SQL table name.
     * Dots and hyphens are invalid unquoted identifiers in Flink SQL, so they are replaced with underscores.
     * The original topic name is preserved in the 'topic' connector property.
     */
    public static String toTableName(String topicName) {
        return topicName.replace('.', '_').replace('-', '_');
    }

    public String generateDdl(String topicName, Map<String, String> schema, MessageFormat format, String startupMode) {
        // Validation of startupMode to prevent SQL injection
        if (startupMode == null || (!startupMode.equals("earliest-offset") && !startupMode.equals("latest-offset"))) {
            startupMode = "earliest-offset";
        }

        String tableName = toTableName(topicName);
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS ").append(tableName).append(" (\n");

        List<String> columns = new ArrayList<>();
        if (format == MessageFormat.XML) {
            // No Flink native XML format: read message as raw string, parse via XmlExtract UDF.
            columns.add("    `raw_value` STRING");
        } else {
            // JSON, AVRO and AUTO:
            // Guard: an empty schema produces an invalid DDL (empty column list).
            // Fall back to a single raw_value STRING so the table can be registered.
            List<String> cols = new ArrayList<>(schema.keySet());
            if (cols.isEmpty()) {
                columns.add("    `raw_value` STRING");
            } else {
                for (String colName : cols) {
                    columns.add("    `" + colName + "` " + schema.get(colName));
                }
            }
        }

        // Add standard technical columns (Metadata and Computed)
        // Guard against duplicate column names if they are already in the schema
        if (!schema.containsKey("event_time")) {
            columns.add("    `event_time` TIMESTAMP(3) METADATA FROM 'timestamp'");
        }
        if (!schema.containsKey("proc_time")) {
            columns.add("    `proc_time` AS PROCTIME()");
        }

        // Write all columns
        for (int i = 0; i < columns.size(); i++) {
            sb.append(columns.get(i));
            if (i < columns.size() - 1) sb.append(",");
            sb.append("\n");
        }

        sb.append(") WITH (\n");
        sb.append("    'topic' = '").append(topicName).append("',\n");
        sb.append("    'properties.group.id' = 'flink_table_").append(tableName).append("',\n");
        // Explicit, not inherited: every reader this application opens is forbidden to commit, and
        // a table registered for one SELECT is no exception. Left to the connector's defaults, a
        // committed offset under a group with no live member is what the cluster audit grades
        // CRITICAL — the application inventing a finding about itself. See ExplorerConsumerGroups.
        sb.append("    'properties.enable.auto.commit' = 'false',\n");
        sb.append("    'connector' = 'kafka',\n");

        // Add Kafka connection properties
        kafkaConfig.getKafkaProperties().forEach((key, value) -> {
            sb.append("    'properties.").append(key).append("' = '").append(value).append("',\n");
        });

        // Le format est déclaré en `value.format`, donc ses options se préfixent `value.` elles
        // aussi — et ce n'est pas une convention de style : le connecteur *refuse* la clé nue.
        // Écrite `json.ignore-parse-errors`, chaque table JSON générée ici était rejetée par
        // `FactoryUtil.validateUnconsumedKeys` au moment où Flink construisait la source ou le
        // puits — pas à la création de la table, qui ne valide rien, et c'est précisément ce qui
        // l'a rendu invisible : le SELECT retombait sur le lecteur direct (une panne moteur, donc
        // un repli silencieux — des lignes arrivaient, sans JOIN ni sous-requête, et trois échecs
        // coupent le planner pour le reste du processus), et l'INSERT, qui n'a pas de repli,
        // répondait 500 sans corps. Mesuré contre flink-connector-kafka 5.0.0-2.2, dont la liste
        // « Supported options » de l'exception nomme `value.json.ignore-parse-errors`.
        if (format == MessageFormat.XML) {
            sb.append("    'value.format' = 'raw',\n");
        } else if (format == MessageFormat.AVRO) {
            sb.append("    'value.format' = 'avro-confluent',\n");
            // Même règle, non mesurée : aucune stack de ce dépôt ne porte de Schema Registry.
            sb.append("    'value.avro-confluent.url' = '").append(kafkaConfig.getSchemaRegistryUrl()).append("',\n");
        } else {
            sb.append("    'value.format' = 'json',\n");
            sb.append("    'value.json.ignore-parse-errors' = 'true',\n");
        }

        sb.append("    'scan.startup.mode' = '").append(startupMode).append("'\n");
        sb.append(");");

        return sb.toString();
    }
}
