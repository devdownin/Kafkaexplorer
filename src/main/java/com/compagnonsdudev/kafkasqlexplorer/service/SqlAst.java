// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.sql.JoinType;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.flink.sql.parser.impl.FlinkSqlParserImpl;
import org.apache.flink.sql.parser.validate.FlinkSqlConformance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Ce qu'une instruction <em>est</em>, demandé au parseur plutôt qu'à des expressions régulières.
 *
 * <p>Tout le chemin SQL de cette application décidait par motif : quelle table est lue, quel alias
 * elle porte, où s'arrête la clause WHERE, ce que projette le SELECT, s'il y a une jointure
 * croisée. Un motif ne sait pas ce qu'est une parenthèse ni une sous-requête, et la liste des
 * défauts que cela a produits est longue — une colonne qualifiée qui ne résout pas, une projection
 * découpée au milieu d'un appel de fonction, un {@code FROM a, b} qu'aucun garde ne voit passer.
 * Le parseur de Flink, lui, le sait : c'est le même qui refusera ou acceptera la requête juste
 * après.
 *
 * <p><b>Sans catalogue, et c'est ce qui rend la chose utilisable ici.</b> {@code Parser.parse} de
 * {@code TableEnvironment} <em>valide</em> — il résout les tables — donc il échoue sur un topic
 * pas encore enregistré, c'est-à-dire précisément le cas où l'auto-enregistrement a besoin de
 * connaître le nom. La grammaire seule ({@link SqlParser#parseStmt()}) répond sur n'importe quel
 * nom, existant ou non.
 *
 * <p><b>Rien n'en dépend.</b> Chaque appelant garde son chemin lexical et s'en sert quand
 * {@link #read} ne répond pas : une instruction que la grammaire refuse doit continuer d'être
 * traitée comme avant — c'est le moteur qui rendra l'erreur, pas ce fichier. Même discipline que
 * {@code parserSaysCreateTableAsSelect} et {@code LineageService.parseWithFlink}, avec la même
 * conséquence : un défaut de configuration du parseur se voit comme une perte de précision, jamais
 * comme une requête cassée.
 */
public final class SqlAst {

    private static final Logger log = LoggerFactory.getLogger(SqlAst.class);

    private SqlAst() {
    }

    /**
     * La configuration de Flink, écrite ici parce que la sienne n'est pas exposée.
     *
     * <p>Les trois valeurs qui comptent : l'accent grave délimite les identifiants, la casse n'est
     * touchée dans aucun sens, et la conformité est celle du dialecte de Flink. Une autre
     * configuration analyserait un autre langage que celui que le moteur exécute juste après, ce
     * qui est la seule façon dont ce fichier pourrait mentir.
     */
    private static SqlParser.Config parserConfig() {
        return SqlParser.config()
            .withParserFactory(FlinkSqlParserImpl.FACTORY)
            .withQuoting(Quoting.BACK_TICK)
            .withUnquotedCasing(Casing.UNCHANGED)
            .withQuotedCasing(Casing.UNCHANGED)
            .withConformance(FlinkSqlConformance.DEFAULT);
    }

    /** Une source lue par l'instruction : son nom tel qu'écrit, et l'alias qu'elle porte. */
    public record Source(String name, String alias) {
    }

    /** Une condition {@code colonne = 'valeur'} — la seule forme que le lecteur direct applique. */
    public record Condition(String column, String value) {
    }

    /** Un élément de la liste de projection. */
    public record Projected(String path, String output, boolean plainColumn) {
    }

    /**
     * Ce que le lecteur direct a besoin de savoir d'une lecture, et rien de plus.
     *
     * @param sources          les tables lues, la première étant celle du FROM
     * @param subquery         une sous-requête est lue quelque part
     * @param join             une jointure explicite (ON / USING) est présente
     * @param crossJoin        une jointure croisée, écrite {@code CROSS JOIN} ou {@code FROM a, b}
     * @param rowCap           le {@code LIMIT} écrit dans l'instruction
     * @param star             la projection est {@code *}
     * @param projection       les éléments projetés, dans l'ordre
     * @param equalities       les égalités du WHERE que le lecteur direct sait appliquer
     * @param otherPredicates  le reste du WHERE, tel qu'écrit, pour être <em>signalé</em>
     */
    public record Read(List<Source> sources, boolean subquery, boolean join, boolean crossJoin,
                       OptionalInt rowCap, boolean star, List<Projected> projection,
                       List<Condition> equalities, List<String> otherPredicates) {
    }

    /**
     * L'instruction analysée, ou {@code Optional.empty()} quand la grammaire l'a refusée ou que ce
     * n'est pas une lecture simple (un {@code INSERT}, une union, un {@code EXPLAIN}).
     */
    public static Optional<Read> read(String sql) {
        if (sql == null || sql.isBlank()) return Optional.empty();
        SqlNode statement;
        try {
            statement = SqlParser.create(sql, parserConfig()).parseStmt();
        } catch (Exception | LinkageError e) {
            // La grammaire a refusé, ou le parseur n'est pas là où on le croyait. L'appelant a son
            // chemin lexical ; c'est le moteur qui rendra l'erreur à l'utilisateur, pas nous.
            log.debug("Statement not parsed for its shape, falling back to the lexical path: {}",
                e.toString());
            return Optional.empty();
        }

        SqlSelect select;
        SqlNode fetch;
        if (statement instanceof SqlOrderBy ordered) {
            // `LIMIT 7` seul produit un SqlOrderBy sans ORDER BY : le plafond vit là, pas sur le
            // SELECT. Mesuré sur le parseur, pas supposé.
            if (!(ordered.query instanceof SqlSelect inner)) return Optional.empty();
            select = inner;
            fetch = ordered.fetch;
        } else if (statement instanceof SqlSelect plain) {
            select = plain;
            fetch = plain.getFetch();
        } else {
            return Optional.empty();
        }

        Shape shape = new Shape();
        walkFrom(select.getFrom(), shape);

        List<Condition> equalities = new ArrayList<>();
        List<String> others = new ArrayList<>();
        splitPredicate(select.getWhere(), equalities, others);

        List<Projected> projection = new ArrayList<>();
        boolean star = false;
        SqlNodeList selectList = select.getSelectList();
        if (selectList != null) {
            for (SqlNode item : selectList) {
                if (item instanceof SqlIdentifier identifier && identifier.isStar()) {
                    star = true;
                    continue;
                }
                projection.add(projected(item));
            }
        }

        return Optional.of(new Read(List.copyOf(shape.sources), shape.subquery, shape.join,
            shape.crossJoin, rowCap(fetch), star, List.copyOf(projection),
            List.copyOf(equalities), List.copyOf(others)));
    }

    /** Ce que la descente du FROM accumule. */
    private static final class Shape {
        final List<Source> sources = new ArrayList<>();
        boolean subquery;
        boolean join;
        boolean crossJoin;
    }

    private static void walkFrom(SqlNode from, Shape shape) {
        if (from == null) return;
        if (from instanceof SqlIdentifier identifier) {
            shape.sources.add(new Source(nameOf(identifier), null));
            return;
        }
        if (from instanceof SqlJoin joinNode) {
            JoinType type = joinNode.getJoinType();
            if (type == JoinType.COMMA || type == JoinType.CROSS) {
                // `FROM a, b` est une jointure croisée écrite à l'ancienne, et c'est exactement
                // celle qu'un garde textuel cherchant « CROSS JOIN » ne voit jamais passer.
                shape.crossJoin = true;
            } else {
                shape.join = true;
            }
            walkFrom(joinNode.getLeft(), shape);
            walkFrom(joinNode.getRight(), shape);
            return;
        }
        if (from instanceof SqlSelect || from.getKind() == SqlKind.ORDER_BY
                || from.getKind() == SqlKind.UNION || from.getKind() == SqlKind.INTERSECT
                || from.getKind() == SqlKind.EXCEPT) {
            shape.subquery = true;
            return;
        }
        if (from instanceof SqlBasicCall call && call.getKind() == SqlKind.AS) {
            List<SqlNode> operands = call.getOperandList();
            SqlNode aliased = operands.isEmpty() ? null : operands.get(0);
            String alias = operands.size() > 1 && operands.get(1) instanceof SqlIdentifier id
                ? id.getSimple() : null;
            if (aliased instanceof SqlIdentifier identifier) {
                shape.sources.add(new Source(nameOf(identifier), alias));
                return;
            }
            int before = shape.sources.size();
            walkFrom(aliased, shape);
            // L'alias porte sur ce qui vient d'être ajouté — une fenêtre nommée, typiquement.
            if (alias != null && shape.sources.size() == before + 1) {
                Source source = shape.sources.get(before);
                shape.sources.set(before, new Source(source.name(), alias));
            }
            return;
        }
        if (from instanceof SqlCall call) {
            // TABLE(TUMBLE(TABLE t, DESCRIPTOR(ts), …)) et les autres fonctions de table : la
            // source est un identifiant enfoui dans les opérandes, et c'est le seul qu'on garde —
            // DESCRIPTOR nomme une colonne, pas une table.
            for (SqlNode operand : call.getOperandList()) {
                if (operand == null) continue;
                if (operand instanceof SqlIdentifier identifier) {
                    if (!isDescriptorArgument(call)) shape.sources.add(new Source(nameOf(identifier), null));
                } else {
                    walkFrom(operand, shape);
                }
            }
        }
    }

    /** {@code DESCRIPTOR(event_time)} nomme une colonne : ses identifiants ne sont pas des tables. */
    private static boolean isDescriptorArgument(SqlCall call) {
        String operator = call.getOperator().getName();
        return "DESCRIPTOR".equalsIgnoreCase(operator);
    }

    private static String nameOf(SqlIdentifier identifier) {
        return String.join(".", identifier.names);
    }

    /** Découpe un WHERE en égalités applicables et en reste — une lecture, deux réponses. */
    private static void splitPredicate(SqlNode predicate, List<Condition> equalities, List<String> others) {
        if (predicate == null) return;
        if (predicate.getKind() == SqlKind.AND && predicate instanceof SqlCall call) {
            for (SqlNode operand : call.getOperandList()) {
                splitPredicate(operand, equalities, others);
            }
            return;
        }
        if (predicate.getKind() == SqlKind.EQUALS && predicate instanceof SqlCall call
                && call.getOperandList().size() == 2) {
            SqlNode left = call.getOperandList().get(0);
            SqlNode right = call.getOperandList().get(1);
            Condition condition = equality(left, right);
            if (condition == null) condition = equality(right, left);
            if (condition != null) {
                equalities.add(condition);
                return;
            }
        }
        others.add(predicate.toString().replace("\n", " ").replace("`", ""));
    }

    private static Condition equality(SqlNode column, SqlNode value) {
        if (!(column instanceof SqlIdentifier identifier) || identifier.isStar()) return null;
        if (!(value instanceof SqlLiteral literal)) return null;
        Object plain = literal.getValue() == null ? null : literal.toValue();
        return plain == null ? null : new Condition(nameOf(identifier), String.valueOf(plain));
    }

    private static Projected projected(SqlNode item) {
        if (item instanceof SqlBasicCall call && call.getKind() == SqlKind.AS) {
            List<SqlNode> operands = call.getOperandList();
            SqlNode source = operands.get(0);
            String alias = operands.size() > 1 && operands.get(1) instanceof SqlIdentifier id
                ? id.getSimple() : null;
            if (source instanceof SqlIdentifier identifier) {
                return new Projected(nameOf(identifier), alias != null ? alias : lastSegment(identifier), true);
            }
            return new Projected(text(source), alias != null ? alias : text(source), false);
        }
        if (item instanceof SqlIdentifier identifier) {
            // Le nom de sortie est le dernier segment : `SELECT o.state` rend une colonne `state`,
            // et c'est ce que le planner produit pour la même requête.
            return new Projected(nameOf(identifier), lastSegment(identifier), true);
        }
        return new Projected(text(item), text(item), false);
    }

    private static String lastSegment(SqlIdentifier identifier) {
        return identifier.names.get(identifier.names.size() - 1);
    }

    private static String text(SqlNode node) {
        return node.toString().replace("\n", " ").replace("`", "");
    }

    private static OptionalInt rowCap(SqlNode fetch) {
        if (!(fetch instanceof SqlLiteral literal)) return OptionalInt.empty();
        try {
            return OptionalInt.of(literal.intValue(true));
        } catch (RuntimeException e) {
            return OptionalInt.empty();
        }
    }

    /** Le nom d'une source, sans son alias, telle que l'instruction l'écrit. */
    public static List<String> tableNames(Read read) {
        List<String> names = new ArrayList<>();
        for (Source source : read.sources()) {
            if (!names.contains(source.name())) names.add(source.name());
        }
        return names;
    }

    /** Ce par quoi une colonne de cette lecture peut être qualifiée : chaque nom et chaque alias. */
    public static List<String> qualifiers(Read read) {
        List<String> qualifiers = new ArrayList<>();
        for (Source source : read.sources()) {
            qualifiers.add(source.name().toLowerCase(Locale.ROOT));
            if (source.alias() != null) qualifiers.add(source.alias().toLowerCase(Locale.ROOT));
        }
        return qualifiers;
    }
}
