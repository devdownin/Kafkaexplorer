// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.apache.flink.table.api.TableEnvironment;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ce que le pré-vol examine — et il n'examinait pas la même instruction que celle qui allait
 * s'exécuter.
 *
 * <p>{@code executeSql} et {@code submitJob} appellent ce garde avec le texte préparé
 * ({@code FlinkSqlService.prepareSql} : guillemets doubles ramenés en accents graves, commentaires
 * retirés). {@code POST /api/query/validate}, que le SQL editor appelle avant <em>chaque</em> Run,
 * lui passait le corps de la requête brut. Les trois cas ci-dessous sont les trois façons dont cet
 * écart se voyait, et chacun a été vérifié en échec contre la révision qui les décrit.
 *
 * <p>Le {@code TableEnvironment} est simulé : ce qui est en cause ici est le <em>texte</em> qui lui
 * parvient et les gardes lexicales au-dessus, pas ce que le planner en fait. Le coordinateur est
 * réduit à l'exécution directe de l'action, la sérialisation de l'accès au runtime n'ayant rien à
 * voir avec ces cas (elle a ses propres tests).
 */
class SqlQueryValidatorTest {

    private ExplorerConfig config;
    private TableEnvironment tableEnv;
    private SqlQueryValidator validator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        config = new ExplorerConfig();
        // Les valeurs livrées : les deux gardes sont actives, donc le pré-vol va jusqu'à l'EXPLAIN.
        config.setAllowCrossJoin(false);
        config.setAllowSystemTableAccess(false);
        tableEnv = Mockito.mock(TableEnvironment.class);
        when(tableEnv.explainSql(anyString())).thenReturn("== Optimized Physical Plan ==\nTableSourceScan");
        FlinkRuntimeCoordinator coordinator = Mockito.mock(FlinkRuntimeCoordinator.class);
        when(coordinator.runRead(anyString(), any(Supplier.class)))
            .thenAnswer(call -> ((Supplier<Object>) call.getArgument(1)).get());
        validator = new SqlQueryValidator(config, tableEnv, coordinator);
    }

    /** L'instruction telle qu'elle est parvenue au planner. */
    private String explained() {
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(tableEnv).explainSql(sent.capture());
        return sent.getValue();
    }

    /**
     * Un mot-clé écrit dans un commentaire n'est pas du SQL.
     *
     * <p>{@code outsideLiterals} neutralise les littéraux — d'où la règle « hors littéraux » que
     * tout ce dépôt applique — mais pas les commentaires : c'est l'appelant qui les retirait. Celui
     * qui ne le faisait pas était l'endpoint du pré-vol, si bien qu'une note « pas de CROSS JOIN
     * ici » au-dessus d'une requête faisait refuser la requête.
     */
    @Test
    void aKeywordInACommentIsNotAJoin() {
        assertDoesNotThrow(() ->
            validator.validate("-- surtout pas de CROSS JOIN ici\nSELECT id FROM orders"));
        assertDoesNotThrow(() ->
            validator.validate("/* CROSS JOIN interdit */ SELECT id FROM orders"));
    }

    /** Ce que la garde refuse vraiment continue d'être refusé. */
    @Test
    void aRealCrossJoinIsStillRefused() {
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> validator.validate("SELECT a.id FROM a CROSS JOIN b")).getMessage()
            .contains("Cross joins"));
        // Écrite à l'ancienne : le parseur en fait la même jointure.
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> validator.validate("SELECT a.id FROM a, b")).getMessage().contains("Cross joins"));
        // Et dans une valeur, ce n'en est pas une.
        assertDoesNotThrow(() -> validator.validate("SELECT id FROM t WHERE note = 'CROSS JOIN'"));
    }

    /**
     * Un identifiant entre guillemets doubles atteint le planner sous la forme qu'il comprend.
     *
     * <p>{@code normalizeIdentifierQuotes} existe pour accepter cette écriture, courante chez qui
     * arrive d'un autre client SQL. Le pré-vol la passait telle quelle à {@code explainSql}, qui la
     * rejette avec une erreur de parseur — classée « faute de syntaxe » et renvoyée à l'éditeur,
     * qui refusait donc de lancer une requête que le moteur exécute très bien.
     */
    @Test
    void aDoubleQuotedIdentifierReachesThePlannerAsFlinkWritesIt() {
        validator.validate("SELECT id FROM \"demo.orders\"");
        assertEquals("SELECT id FROM `demo.orders`", explained());
    }

    /**
     * Une instruction dont la première ligne est un commentaire est bien classée comme une lecture.
     *
     * <p>La classification est un {@code startsWith} sur le corps : un commentaire de tête la
     * faisait échouer, et le pré-vol sortait sans rien vérifier — donc en acceptant, en silence.
     * C'est exactement la forme que produit l'aperçu DDL, et celle que colle quiconque commente ses
     * requêtes.
     */
    @Test
    void aLeadingCommentDoesNotSkipTheCheckAltogether() {
        validator.validate("-- combien de commandes\nSELECT COUNT(*) AS metric_value FROM orders");
        assertEquals("SELECT COUNT(*) AS metric_value FROM orders", explained());
    }

    /** Ce que le plan nomme est refusé, et l'instruction vide ne coûte rien. */
    @Test
    void aSystemTableInThePlanIsRefusedAndBlankCostsNothing() {
        when(tableEnv.explainSql(anyString())).thenReturn("SCAN INFORMATION_SCHEMA.COLUMNS");
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> validator.validate("SELECT id FROM t")).getMessage().contains("system tables"));

        Mockito.reset(tableEnv);
        validator.validate("   \n  ");
        validator.validate("-- rien que des commentaires");
        verify(tableEnv, never()).explainSql(anyString());
    }
}
