// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What a statement the model wrote has to pass before the page shows it as accepted.
 *
 * <p>The model proposes, the planner disposes. Each step reuses the class that already makes that
 * decision for a statement a person typed, so the assistant gains no privilege and no second
 * reading of the dialect: one statement and a {@code SELECT} (the whitelist's classification),
 * sources among the tables the model was shown ({@link SqlAst}), the cross-join and system-table
 * guard ({@link SqlQueryValidator}), then a dry run of the planner with the sources registered
 * ({@link FlinkSqlService#planCheck}).
 *
 * <p>What it cannot see is said in {@code EMBEDDED-LLM-SCOPE.md} §5.5 and on the page: a valid
 * statement that answers another question than the one asked.
 */
@Component
public class AssistantSqlGate {

    /** How a statement fared, and what may be done about it. */
    public enum Verdict {
        /** The planner accepted it. */
        ACCEPTED,
        /** The statement's own fault, and the message can be sent back to the model. */
        REPAIRABLE,
        /** Not a read: never shown, whatever the model does next. */
        FORBIDDEN,
        /** The engine could not answer — busy or failing. Not the model's fault, not sent back. */
        UNCHECKED
    }

    public record Result(Verdict verdict, String message, String sql) {
        public boolean accepted() {
            return verdict == Verdict.ACCEPTED;
        }
    }

    private static final Pattern CTE_NAME = Pattern.compile(
        "(?i)(?:\\bWITH\\b|,)\\s*`?([A-Za-z_][\\w$]*)`?\\s+AS\\s*\\(");
    private static final Pattern METRIC_VALUE_ALIAS = Pattern.compile("(?i)\\bAS\\s+`?metric_value`?(?![\\w$])");

    private final FlinkSqlService flinkSqlService;
    private final SqlQueryValidator sqlQueryValidator;

    public AssistantSqlGate(FlinkSqlService flinkSqlService, SqlQueryValidator sqlQueryValidator) {
        this.flinkSqlService = flinkSqlService;
        this.sqlQueryValidator = sqlQueryValidator;
    }

    /**
     * @param sql     the statement the model returned
     * @param allowed the tables it was shown
     * @param metric  whether it must carry the {@code metric_value} alias a metric reads
     */
    public Result check(String sql, Collection<String> allowed, boolean metric) {
        String statement = trimmed(sql);
        if (statement.isEmpty()) {
            return new Result(Verdict.REPAIRABLE, "No statement was returned.", statement);
        }
        if (SqlStatements.outsideLiterals(statement).indexOf(';') >= 0) {
            return new Result(Verdict.REPAIRABLE,
                "Only one statement is accepted; this answer holds several.", statement);
        }
        String prepared = FlinkSqlService.prepareSql(statement);
        if (prepared == null || prepared.isBlank()) {
            return new Result(Verdict.REPAIRABLE, "The answer holds only comments.", statement);
        }
        String body = SqlStatements.classifiableBody(prepared.trim());
        if (!body.startsWith("SELECT")) {
            return new Result(Verdict.FORBIDDEN,
                "Only a SELECT is accepted from the assistant; this statement is not a read.", statement);
        }

        String rewritten = flinkSqlService.withTopicTableNames(prepared);
        List<String> unknown = unknownSources(rewritten, allowed);
        if (!unknown.isEmpty()) {
            return new Result(Verdict.REPAIRABLE, "The statement reads "
                + unknown.stream().map(n -> "`" + n + "`").collect(Collectors.joining(", "))
                + ", which " + (unknown.size() == 1 ? "is" : "are") + " not among the tables described: "
                + String.join(", ", allowed) + ".", statement);
        }

        try {
            sqlQueryValidator.validate(rewritten);
        } catch (IllegalArgumentException e) {
            return new Result(Verdict.REPAIRABLE, e.getMessage(), statement);
        }

        if (metric && !METRIC_VALUE_ALIAS.matcher(SqlStatements.outsideLiterals(prepared)).find()) {
            return new Result(Verdict.REPAIRABLE, "A metric reads the column aliased AS metric_value, "
                + "and this statement has none.", statement);
        }

        FlinkSqlService.PlanCheck plan;
        try {
            plan = flinkSqlService.planCheck(rewritten);
        } catch (RuntimeException e) {
            return new Result(Verdict.UNCHECKED, "Not checked: " + SqlErrorClassifier.explain(e), statement);
        }
        if (plan.planned()) return new Result(Verdict.ACCEPTED, null, statement);
        if (plan.kind() == SqlErrorClassifier.Kind.USER_ERROR) {
            return new Result(Verdict.REPAIRABLE, plan.message(), statement);
        }
        String prefix = plan.kind() == SqlErrorClassifier.Kind.ENGINE_BUSY
            ? "Not checked — the Flink runtime was busy: "
            : "Not checked — the engine could not plan it: ";
        return new Result(Verdict.UNCHECKED, prefix + plan.message(), statement);
    }

    /** Sources the statement reads that the model was not shown; CTE names are its own. */
    static List<String> unknownSources(String sql, Collection<String> allowed) {
        Set<String> permitted = allowed.stream()
            .map(n -> n.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        Matcher cte = CTE_NAME.matcher(SqlStatements.outsideLiterals(sql));
        while (cte.find()) permitted.add(cte.group(1).toLowerCase(Locale.ROOT));
        List<String> unknown = new ArrayList<>();
        SqlAst.read(sql).ifPresent(read -> {
            for (String name : SqlAst.tableNames(read)) {
                String bare = name.replace("`", "");
                String key = DdlGeneratorService.toTableName(bare).toLowerCase(Locale.ROOT);
                if (!permitted.contains(key) && !permitted.contains(bare.toLowerCase(Locale.ROOT))
                        && !unknown.contains(bare)) {
                    unknown.add(bare);
                }
            }
        });
        return unknown;
    }

    private static String trimmed(String sql) {
        if (sql == null) return "";
        String s = sql.strip();
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```[A-Za-z]*\\s*", "").replaceFirst("\\s*```$", "").strip();
        }
        while (s.endsWith(";")) s = s.substring(0, s.length() - 1).strip();
        return s;
    }
}
