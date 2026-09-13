// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpErrorCode;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpGuard;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpToolException;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard;
import com.compagnonsdudev.kafkasqlexplorer.service.DdlGeneratorService;
import com.compagnonsdudev.kafkasqlexplorer.service.KafkaAdminService;
import com.compagnonsdudev.kafkasqlexplorer.service.SqlAst;
import com.compagnonsdudev.kafkasqlexplorer.service.SqlStatements;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code explorer.mcp.allowed-topic-prefixes}, applied to a SQL statement.
 *
 * <p><b>Why this exists at all.</b> Every other tool takes a topic name and hands it to
 * {@link ToolGuard#checkTopicScope}; {@code kex_sql_query} takes a statement, and it had no scope
 * check of any kind. That is not a lesser hole than a missing check elsewhere — it is the whole
 * setting, because {@code FlinkSqlService} resolves any name in a {@code FROM} to a topic and reads
 * it. On a deployment restricted to {@code demo.}, {@code kex_preview_messages} refused
 * {@code internal.mcp.audit} with {@code -32041} while {@code SELECT * FROM internal.mcp.audit}
 * returned its rows, and the console displayed the prefix list as the deployment's posture
 * throughout.
 *
 * <p><b>The reference has to be resolved, not pattern-matched.</b> A prefix is written in topic
 * terms ({@code demo.}) and a statement names Flink tables, which
 * {@link DdlGeneratorService#toTableName} derives by replacing dots and hyphens — so
 * {@code demo.orders}, {@code demo-orders} and {@code demo_orders} are one identifier by the time
 * they reach the SQL, and no prefix test on the written name can tell them apart. Each reference is
 * therefore matched back to the topic it would register, exactly as {@code registerSourceTable}
 * does, and the <em>topic</em> is what the guard sees.
 *
 * <p><b>A reference that resolves to nothing is refused, not allowed.</b> It is a table an operator
 * wrote by hand, and a hand-written table carries its own {@code 'topic'} option — which is the one
 * way left to read outside the scope once the resolution above is in place. Refusing it costs a
 * deployment that mixes scoped MCP with hand-written tables, and that is the right side to err on:
 * the alternative reads the topic and says nothing.
 *
 * <p><b>Nothing here runs when the scope is unrestricted</b>, which is the shipped default
 * ({@code "*"}), so the common deployment pays neither the parse nor the topic listing.
 */
class SqlSourceScope {

    /** {@code 'topic' = 'x'} inside a {@code WITH (...)}, which is how a table names what it reads. */
    private static final Pattern DECLARED_TOPIC = Pattern.compile(
            "(?i)['\"]topic['\"]\\s*=\\s*['\"]([^'\"]+)['\"]");

    /** {@code WITH name AS (} / {@code , name AS (} — what binds a CTE, and where its body starts. */
    private static final Pattern CTE_BINDING = Pattern.compile(
            "(?i)(?:\\bWITH\\b|,)\\s*([\\w$]+)\\s*(?:\\([^)]*\\))?\\s*AS\\s*\\(");

    /** The object a {@code DESCRIBE} names — the one non-SELECT shape that registers a topic. */
    private static final Pattern DESCRIBE_TARGET = Pattern.compile(
            "(?i)^DESC(?:RIBE)?\\s+(?!CATALOG\\b|JOB\\b|FUNCTION\\b|MODEL\\b|SYSTEM\\b)"
                    + "[`\"]?([\\w.$-]+)[`\"]?\\s*;?\\s*$");

    private final KafkaAdminService kafka;
    private final ToolGuard guard;

    SqlSourceScope(KafkaAdminService kafka, ToolGuard guard) {
        this.kafka = kafka;
        this.guard = guard;
    }

    /** True when this deployment restricts nothing, so no statement needs looking at. */
    boolean unrestricted() {
        return McpProperties.unrestricted(guard.properties().getAllowedTopicPrefixes());
    }

    /**
     * Refuses a statement that could read a topic outside the configured prefixes.
     *
     * <p>Called before {@code executeSync}, so the refusal precedes the read — a scope check that
     * runs after it has already disclosed what it refused.
     */
    void check(String sql) {
        if (unrestricted() || sql == null || sql.isBlank()) {
            return;
        }
        // A topic named literally in a CREATE TABLE is checked first and without any I/O: the
        // statement says which topic it wants, so nothing has to be resolved to know it.
        guard.checkTopicScope(declaredTopics(sql));

        List<String> references = referencedTables(sql);
        if (references.isEmpty()) {
            return;
        }

        List<String> topics = topics();
        List<String> resolved = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (String reference : references) {
            String flinkName = DdlGeneratorService.toTableName(reference);
            topics.stream()
                    .filter(topic -> DdlGeneratorService.toTableName(topic).equals(flinkName))
                    .findFirst()
                    .ifPresentOrElse(resolved::add, () -> unresolved.add(reference));
        }
        guard.checkTopicScope(resolved);
        if (!unresolved.isEmpty()) {
            throw new McpToolException(McpErrorCode.OUT_OF_SCOPE, McpGuard.SCOPE,
                    ("this statement reads %s, which matches no topic on this cluster, so it cannot "
                            + "be shown to be within explorer.mcp.allowed-topic-prefixes (%s). A "
                            + "table defined by hand carries its own topic, which is why a name "
                            + "that cannot be resolved is refused rather than read.")
                            .formatted(String.join(", ", unresolved),
                                    String.join(", ", guard.properties().getAllowedTopicPrefixes())));
        }
    }

    /**
     * Which tables the statement reads.
     *
     * <p>Only the shapes that can reach a topic are considered, and they are the shapes
     * {@code FlinkSqlService.autoRegister} acts on: a {@code SELECT} (past a leading CTE) and a
     * {@code DESCRIBE}. An {@code EXPLAIN} registers nothing, so it reads nothing.
     *
     * <p><b>A SELECT the parser could not read is refused rather than waved through.</b> Returning
     * an empty list there would make an unparseable statement the way around this guard, which is
     * the one outcome worth more than the false positives it costs — and the caller is told which
     * it is.
     */
    private List<String> referencedTables(String sql) {
        String described = describedObject(sql);
        if (described != null) {
            return List.of(described);
        }
        String body = SqlStatements.classifiableBody(sql);
        if (!body.startsWith("SELECT")) {
            return List.of();
        }
        Optional<SqlAst.Read> ast = SqlAst.read(SqlStatements.withoutLeadingCte(sql));
        List<String> names = new ArrayList<>(ast.map(SqlAst::tableNames).orElse(List.of()));
        // A CTE body reads topics too, and `withoutLeadingCte` has just removed it from what the
        // parser sees. Without this, `WITH x AS (SELECT * FROM demo.orders) SELECT * FROM x` is a
        // statement whose only visible source is `x` — refused as unresolvable, which is the safe
        // direction and the wrong answer.
        for (String cteBody : cteBodies(sql)) {
            Optional<SqlAst.Read> inner = SqlAst.read(cteBody);
            if (inner.isEmpty()) {
                names.clear();   // unreadable: fall to the refusal below rather than half-check it
                break;
            }
            SqlAst.tableNames(inner.get()).forEach(name -> {
                if (!names.contains(name)) {
                    names.add(name);
                }
            });
        }
        if (names.isEmpty()) {
            throw new McpToolException(McpErrorCode.OUT_OF_SCOPE, McpGuard.SCOPE,
                    ("this server restricts reads to %s, and it could not determine which tables "
                            + "this statement reads, so it cannot be shown to stay inside them. "
                            + "Write the sources as plain table names in the FROM and the JOINs.")
                            .formatted(String.join(", ", guard.properties().getAllowedTopicPrefixes())));
        }
        // A CTE name is a source to the parser and a topic to nobody, so it is dropped here rather
        // than refused as unresolvable below.
        Set<String> ctes = cteNames(sql);
        return names.stream()
                .filter(name -> !ctes.contains(name.toLowerCase(Locale.ROOT)))
                .toList();
    }

    /** Every {@code 'topic' = '…'} the statement declares, outside its string literals' contents. */
    private static List<String> declaredTopics(String sql) {
        Set<String> declared = new LinkedHashSet<>();
        Matcher matcher = DECLARED_TOPIC.matcher(sql);
        while (matcher.find()) {
            declared.add(matcher.group(1));
        }
        return List.copyOf(declared);
    }

    private static String describedObject(String sql) {
        Matcher matcher = DESCRIBE_TARGET.matcher(sql.trim());
        return matcher.matches() ? matcher.group(1) : null;
    }

    /**
     * The body of each CTE, sliced out of the original statement.
     *
     * <p>The delimiters are found on the masked text — a parenthesis inside a string literal is
     * not a parenthesis — and the slice is taken from the original, because the body has to be
     * parsed with its values intact.
     */
    private static List<String> cteBodies(String sql) {
        List<String> bodies = new ArrayList<>();
        if (!SqlStatements.startsWithCte(sql)) {
            return bodies;
        }
        String masked = SqlStatements.outsideLiterals(sql);
        Matcher matcher = CTE_BINDING.matcher(masked);
        while (matcher.find()) {
            int open = matcher.end() - 1;
            int close = matchingParen(masked, open);
            if (close < 0) {
                return List.of();   // unbalanced: let the caller refuse rather than guess
            }
            bodies.add(sql.substring(open + 1, close));
        }
        return bodies;
    }

    /** The index of the parenthesis closing the one at {@code open}, or -1. */
    private static int matchingParen(String masked, int open) {
        int depth = 0;
        for (int i = open; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** The names a leading {@code WITH} binds, lower-cased for comparison. */
    private static Set<String> cteNames(String sql) {
        Set<String> names = new LinkedHashSet<>();
        if (!SqlStatements.startsWithCte(sql)) {
            return names;
        }
        String masked = SqlStatements.outsideLiterals(sql);
        Matcher matcher = CTE_BINDING.matcher(masked);
        while (matcher.find()) {
            names.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return names;
    }

    /**
     * The cluster's topics, or a dependency failure.
     *
     * <p>Listing them is metadata rather than a payload read, and it is the same call
     * auto-registration is about to make — so the scope decision still precedes every read of what
     * it might refuse. A broker that cannot answer makes this fail closed: an unverifiable scope is
     * not an open one.
     */
    private List<String> topics() {
        try {
            return kafka.listTopics();
        } catch (Exception e) {
            throw new McpToolException(McpErrorCode.DEPENDENCY_UNAVAILABLE,
                    "the topics could not be listed, so this statement's sources could not be "
                            + "checked against explorer.mcp.allowed-topic-prefixes: " + e.getMessage());
        }
    }

    /**
     * Whether a registered Flink table is one an in-scope topic would have produced.
     *
     * <p>{@code kex_list_tables} filters on this. It is the quieter half of the same hole: a table
     * registered by an earlier out-of-scope query would otherwise be listed with its columns and
     * its DDL.
     */
    boolean tableIsInScope(String tableName, List<String> topics) {
        if (unrestricted()) {
            return true;
        }
        List<String> prefixes = guard.properties().getAllowedTopicPrefixes();
        return topics.stream()
                .filter(topic -> prefixes.stream().anyMatch(topic::startsWith))
                .anyMatch(topic -> DdlGeneratorService.toTableName(topic).equals(tableName));
    }

    /** The topic list for {@link #tableIsInScope}, read once per call. */
    List<String> topicsForFiltering() {
        return unrestricted() ? List.of() : topics();
    }
}
