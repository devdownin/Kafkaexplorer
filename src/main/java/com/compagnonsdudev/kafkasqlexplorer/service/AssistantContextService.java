// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.AssistantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which tables the model is shown, and their columns — decided here, deterministically, before
 * any model is asked anything.
 *
 * <p>A small model's context window is the constraint: the demo alone has 79 topics. The order is
 * the one {@code EMBEDDED-LLM-SCOPE.md} §5.2 sets — the tables the user put in scope, the sources
 * of the statement being fixed, the names written in the question, then a lexical match — and the
 * first that come are the ones described, up to {@code explorer.assistant.max-tables}. The rest
 * are <em>named</em> in the answer, never silently dropped.
 *
 * <p>Names and types only. A message value is both business data and text controlled by whoever
 * produces to the topic, i.e. an injection vector; the column names are enough to write the query.
 */
@Service
public class AssistantContextService {

    private static final Logger log = LoggerFactory.getLogger(AssistantContextService.class);

    /** A token that could be a table or topic name as a user writes one. */
    private static final Pattern NAME_TOKEN = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.\\-]*[A-Za-z0-9_]");
    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");
    /** Words that match half the catalogue and say nothing about which table is meant. */
    private static final Set<String> STOPWORDS = Set.of(
        "the", "and", "for", "with", "from", "per", "par", "les", "des", "une", "dans", "sur",
        "how", "many", "what", "which", "count", "number", "nombre", "combien", "show", "list",
        "all", "tous", "toutes", "each", "chaque", "by", "last", "dernier", "derniers", "minute",
        "minutes", "hour", "hours", "heure", "heures", "day", "days", "jour", "jours", "topic",
        "topics", "table", "tables", "messages", "message", "records", "rows", "demo");

    /** One table as described to the model. */
    public record TableContext(String name, Map<String, String> columns, int columnsOmitted) {
    }

    /** What was described, and what was not, by name. */
    public record Context(List<TableContext> tables, List<String> omitted) {
        public List<String> tableNames() {
            return tables.stream().map(TableContext::name).toList();
        }
    }

    private final FlinkSqlService flinkSqlService;
    private final KafkaAdminService kafkaAdminService;
    private final AssistantProperties properties;

    public AssistantContextService(FlinkSqlService flinkSqlService, KafkaAdminService kafkaAdminService,
                                   AssistantProperties properties) {
        this.flinkSqlService = flinkSqlService;
        this.kafkaAdminService = kafkaAdminService;
        this.properties = properties;
    }

    /**
     * @param question the request, for name and lexical matching; may be blank
     * @param sql      the statement being fixed, whose sources come first after the explicit ones
     * @param explicit tables or topics the user put in scope
     */
    public Context build(String question, String sql, List<String> explicit) {
        Map<String, String> known = knownNames();
        List<String> statementSources = new ArrayList<>();
        if (sql != null && !sql.isBlank()) {
            String prepared = flinkSqlService.withTopicTableNames(FlinkSqlService.prepareSql(sql));
            SqlAst.read(prepared).ifPresent(read -> statementSources.addAll(SqlAst.tableNames(read)));
        }
        List<String> candidates = candidates(question, statementSources, explicit, known);

        List<TableContext> described = new ArrayList<>();
        List<String> omitted = new ArrayList<>();
        for (String candidate : candidates) {
            if (described.size() >= properties.getMaxTables()) {
                omitted.add(candidate);
                continue;
            }
            String ref = known.getOrDefault(key(candidate), candidate);
            Map<String, String> columns;
            try {
                columns = flinkSqlService.sourceSchema(ref);
            } catch (RuntimeException e) {
                log.debug("Assistant could not describe '{}': {}", candidate, e.toString());
                columns = Map.of();
            }
            if (columns.isEmpty()) {
                omitted.add(candidate);
                continue;
            }
            described.add(capped(DdlGeneratorService.toTableName(ref), columns));
        }
        return new Context(described, omitted);
    }

    /**
     * The candidate tables, in the order they are described, as table names.
     *
     * @param known table name (lower case) → the name it is registered or published under
     */
    static List<String> candidates(String question, List<String> statementSources,
                                   List<String> explicit, Map<String, String> known) {
        Set<String> ordered = new LinkedHashSet<>();
        if (explicit != null) {
            for (String name : explicit) {
                if (name != null && !name.isBlank()) ordered.add(DdlGeneratorService.toTableName(name.strip()));
            }
        }
        for (String source : statementSources) {
            ordered.add(DdlGeneratorService.toTableName(source));
        }
        String text = question == null ? "" : question;
        Matcher token = NAME_TOKEN.matcher(text);
        while (token.find()) {
            String candidate = key(token.group());
            if (known.containsKey(candidate)) ordered.add(DdlGeneratorService.toTableName(known.get(candidate)));
        }
        if (ordered.isEmpty()) {
            ordered.addAll(lexicalMatches(text, known));
        }
        // Deduplicated on the case-insensitive key: `Orders` and `orders` are one table.
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String name : ordered) byKey.putIfAbsent(key(name), name);
        return new ArrayList<>(byKey.values());
    }

    /** Tables whose name shares a significant word with the question, best match first. */
    static List<String> lexicalMatches(String question, Map<String, String> known) {
        Set<String> words = new LinkedHashSet<>();
        Matcher word = WORD.matcher(question.toLowerCase(Locale.ROOT));
        while (word.find()) {
            String w = word.group();
            if (w.length() >= 3 && !STOPWORDS.contains(w)) words.add(singular(w));
        }
        if (words.isEmpty()) return List.of();
        record Scored(String name, int score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (String original : known.values()) {
            String table = DdlGeneratorService.toTableName(original);
            int score = 0;
            for (String part : table.toLowerCase(Locale.ROOT).split("_+")) {
                if (part.length() >= 3 && words.contains(singular(part))) score++;
            }
            if (score > 0) scored.add(new Scored(table, score));
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed()
            .thenComparingInt(s -> s.name().length())
            .thenComparing(Scored::name));
        return scored.stream().map(Scored::name).toList();
    }

    private TableContext capped(String table, Map<String, String> columns) {
        int max = properties.getMaxColumnsPerTable();
        Map<String, String> kept = new LinkedHashMap<>();
        for (Map.Entry<String, String> column : columns.entrySet()) {
            if (kept.size() >= max) break;
            kept.put(column.getKey(), column.getValue());
        }
        return new TableContext(table, kept, columns.size() - kept.size());
    }

    /** Registered tables first, then topics; internal topics are not candidates. */
    private Map<String, String> knownNames() {
        Map<String, String> known = new LinkedHashMap<>();
        try {
            for (String table : flinkSqlService.listTables()) known.putIfAbsent(key(table), table);
        } catch (RuntimeException e) {
            log.debug("Assistant could not list Flink tables: {}", e.toString());
        }
        try {
            for (String topic : kafkaAdminService.listTopics()) {
                if (topic.startsWith("_")) continue;
                known.putIfAbsent(key(topic), topic);
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.debug("Assistant could not list topics: {}", e.toString());
        }
        return known;
    }

    private static String key(String name) {
        String stripped = name.strip();
        if (stripped.length() >= 2 && (stripped.startsWith("`") || stripped.startsWith("\""))) {
            stripped = stripped.substring(1, stripped.length() - 1);
        }
        return DdlGeneratorService.toTableName(stripped).toLowerCase(Locale.ROOT);
    }

    private static String singular(String word) {
        return word.length() > 3 && word.endsWith("s") ? word.substring(0, word.length() - 1) : word;
    }
}
