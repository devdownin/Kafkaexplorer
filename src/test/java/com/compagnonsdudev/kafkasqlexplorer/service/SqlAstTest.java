// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ce que le parseur répond, et ce qu'il refuse de répondre.
 *
 * <p>Ces cas ne testent pas Calcite : ils épinglent la <em>configuration</em> et la descente
 * écrites ici, c'est-à-dire les seules choses qui peuvent faire dire à ce fichier autre chose que
 * ce que le moteur exécutera juste après. Une montée de version de Flink qui changerait la forme
 * de l'arbre — un alias qui cesse d'être un {@code SqlBasicCall}, un {@code LIMIT} qui migre du
 * {@code SqlOrderBy} vers le {@code SqlSelect} — casse ici plutôt qu'en production, où elle se
 * lirait comme une perte de précision silencieuse.
 */
class SqlAstTest {

    private SqlAst.Read read(String sql) {
        Optional<SqlAst.Read> parsed = SqlAst.read(sql);
        assertTrue(parsed.isPresent(), "should have parsed: " + sql);
        return parsed.get();
    }

    @Test
    void namesTheSourceAndItsAlias() {
        SqlAst.Read read = read("SELECT id FROM demo_orders o WHERE o.id = 'a'");

        assertEquals(List.of(new SqlAst.Source("demo_orders", "o")), read.sources());
        assertEquals(List.of("demo_orders", "o"), SqlAst.qualifiers(read));

        // `AS` explicite, et un nom entre accents graves qui contient un point.
        SqlAst.Read quoted = read("SELECT id FROM `my.topic` AS m");
        assertEquals(List.of(new SqlAst.Source("my.topic", "m")), quoted.sources());
    }

    /**
     * Le plafond de lignes vit sur l'enveloppe, pas sur le SELECT.
     *
     * <p>`LIMIT 7` seul produit un {@code SqlOrderBy} <em>sans</em> ORDER BY : le lire sur le
     * {@code SqlSelect} rendrait toujours « pas de plafond », c'est-à-dire une page plus large que
     * demandée. Mesuré sur le parseur.
     */
    @Test
    void readsTheRowCapWhereverItSits() {
        assertEquals(OptionalInt.of(7), read("SELECT id FROM t LIMIT 7").rowCap());
        assertEquals(OptionalInt.of(3), read("SELECT id FROM t ORDER BY id LIMIT 3").rowCap());
        assertEquals(OptionalInt.empty(), read("SELECT id FROM t").rowCap());
        // Un `LIMIT` cité dans une valeur n'en est pas un.
        assertEquals(OptionalInt.empty(), read("SELECT id FROM t WHERE note = 'limit 1'").rowCap());
    }

    /**
     * Une égalité sous un {@code OR} n'est pas une égalité applicable.
     *
     * <p>C'est le défaut que le motif ne pouvait pas voir : il lisait {@code colonne = 'valeur'}
     * n'importe où après le WHERE, donc {@code WHERE a = 'x' OR b = 'y'} filtrait sur {@code a}
     * seul — des lignes valides écartées, en silence. L'arbre ne descend que les conjonctions.
     */
    @Test
    void onlyConjunctionsBecomeAppliedConditions() {
        SqlAst.Read conjunction = read("SELECT id FROM t WHERE a = 'x' AND b = 'y'");
        assertEquals(List.of(new SqlAst.Condition("a", "x"), new SqlAst.Condition("b", "y")),
            conjunction.equalities());
        assertTrue(conjunction.otherPredicates().isEmpty());

        SqlAst.Read disjunction = read("SELECT id FROM t WHERE a = 'x' OR b = 'y'");
        assertTrue(disjunction.equalities().isEmpty(),
            "an equality under an OR must not be applied on its own, got: " + disjunction.equalities());
        assertEquals(1, disjunction.otherPredicates().size());

        // Et ce que ce lecteur ne sait pas appliquer est rapporté tel qu'écrit.
        SqlAst.Read mixed = read("SELECT id FROM t WHERE state = 'NEW' AND amount > 500");
        assertEquals(List.of(new SqlAst.Condition("state", "NEW")), mixed.equalities());
        assertEquals(List.of("amount > 500"), mixed.otherPredicates());
    }

    /** Une condition d'un {@code HAVING} n'est pas une condition de ligne. */
    @Test
    void aHavingIsNotAWhere() {
        SqlAst.Read read = read(
            "SELECT state, COUNT(*) AS n FROM t WHERE a = 'x' GROUP BY state HAVING COUNT(*) = 2");

        assertEquals(List.of(new SqlAst.Condition("a", "x")), read.equalities());
    }

    /**
     * La projection se lit par élément, pas par virgule.
     *
     * <p>{@code JSON_VALUE(c, '$.a')} contient une virgule : le découpage lexical en faisait deux
     * colonnes, dont aucune n'existe. Et une expression est marquée comme telle, ce dont
     * l'appelant se sert pour refuser au lieu de rendre une colonne de {@code null}.
     */
    @Test
    void readsTheProjectionByItemAndFlagsWhatIsNotAColumn() {
        SqlAst.Read read = read("SELECT JSON_VALUE(c, '$.a') AS x, o.state, amount AS montant FROM t o");

        assertEquals(3, read.projection().size());
        assertFalse(read.projection().get(0).plainColumn(), "a function call is not a column");
        assertEquals("x", read.projection().get(0).output());
        // Une colonne qualifiée sort sous son dernier segment, comme chez le planner.
        assertEquals("o.state", read.projection().get(1).path());
        assertEquals("state", read.projection().get(1).output());
        assertEquals("montant", read.projection().get(2).output());

        assertTrue(read("SELECT * FROM t").star());
    }

    /** {@code FROM a, b} est une jointure croisée, et c'est celle qu'aucun garde textuel ne voit. */
    @Test
    void recognisesBothSpellingsOfACrossJoin() {
        assertTrue(read("SELECT * FROM a, b").crossJoin());
        assertTrue(read("SELECT * FROM a CROSS JOIN b").crossJoin());

        SqlAst.Read inner = read("SELECT * FROM a JOIN b ON a.id = b.id");
        assertFalse(inner.crossJoin());
        assertTrue(inner.join(), "an ON join is a join, and this reader cannot honour it either");
    }

    /** Une fenêtre nomme sa source, et {@code DESCRIPTOR} nomme une colonne — pas une table. */
    @Test
    void findsTheSourceOfAWindowedRead() {
        SqlAst.Read read = read("SELECT window_start, COUNT(*) AS c FROM TABLE("
            + "TUMBLE(TABLE win_topic, DESCRIPTOR(event_time), INTERVAL '5' MINUTE)) "
            + "GROUP BY window_start");

        assertEquals(List.of("win_topic"), SqlAst.tableNames(read));
    }

    /** Une sous-requête est vue comme telle : ce lecteur lirait la première table et rien d'autre. */
    @Test
    void seesASubquery() {
        assertTrue(read("SELECT COUNT(*) AS n FROM (SELECT id FROM t)").subquery());
    }

    /**
     * Ce qui n'est pas une lecture simple ne reçoit pas de réponse, et c'est le contrat.
     *
     * <p>L'appelant garde son chemin lexical : une instruction que la grammaire refuse doit être
     * traitée comme avant, et c'est le moteur qui rendra l'erreur — pas ce fichier.
     */
    @Test
    void answersNothingRatherThanGuessing() {
        assertTrue(SqlAst.read("SELECT id, FROM t").isEmpty(), "a syntax error is not our verdict");
        assertTrue(SqlAst.read("INSERT INTO s SELECT id FROM t").isEmpty());
        assertTrue(SqlAst.read("CREATE TABLE t (id STRING) WITH ('connector'='kafka')").isEmpty());
        assertTrue(SqlAst.read("WITH r AS (SELECT * FROM t) SELECT id FROM r").isEmpty());
        assertTrue(SqlAst.read(null).isEmpty());
        assertTrue(SqlAst.read("   ").isEmpty());
    }

    /** Et une entrée pathologique ne coûte pas plus qu'un refus. */
    @Test
    void aPathologicalStatementIsRefusedPromptly() {
        String bomb = "/**" + ")/**".repeat(20_000);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
            () -> assertTrue(SqlAst.read(bomb).isEmpty()));
    }

    /**
     * L'analyse est mémoïsée, et ce qui est rendu deux fois est la <em>même</em> réponse.
     *
     * <p>Le cache est ce qui permet aux six appels d'une seule lecture SQL de ne coûter qu'une
     * analyse ; il n'est sûr que parce que {@link SqlAst.Read} est immuable, donc partageable. Le
     * pin est l'identité de l'objet : si une évolution rendait ce record mutable, ou reconstruisait
     * ses listes en dehors de {@code List.copyOf}, c'est ici qu'il faudrait s'en apercevoir plutôt
     * que dans un appelant qui aurait modifié la réponse d'un autre.
     */
    @Test
    void theSameStatementIsAnalysedOnce() {
        String sql = "SELECT o.id FROM demo_orders o WHERE o.state = 'NEW' LIMIT 12";
        SqlAst.Read first = read(sql);
        assertSame(first, SqlAst.read(sql).orElseThrow());
        // Une chaîne égale mais non identique répond pareil : la clé est le texte, pas la référence.
        assertSame(first, SqlAst.read(new String(sql.toCharArray())).orElseThrow());

        // Un refus est retenu comme une réussite — c'est le cas fréquent sur ce chemin.
        assertTrue(SqlAst.read("INSERT INTO s SELECT id FROM t").isEmpty());
        assertTrue(SqlAst.read("INSERT INTO s SELECT id FROM t").isEmpty());

        // Et deux instructions différentes ne se confondent pas.
        assertEquals(List.of("a"), SqlAst.tableNames(read("SELECT id FROM a")));
        assertEquals(List.of("b"), SqlAst.tableNames(read("SELECT id FROM b")));
    }
}
