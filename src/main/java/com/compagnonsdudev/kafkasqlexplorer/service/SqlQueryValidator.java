// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import org.apache.flink.table.api.TableEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SqlQueryValidator {

    private static final Logger log = LoggerFactory.getLogger(SqlQueryValidator.class);
    private final ExplorerConfig explorerConfig;
    private final TableEnvironment tableEnv;
    private final FlinkRuntimeCoordinator runtimeCoordinator;

    @Autowired
    public SqlQueryValidator(ExplorerConfig explorerConfig, TableEnvironment tableEnv, FlinkRuntimeCoordinator runtimeCoordinator) {
        this.explorerConfig = explorerConfig;
        this.tableEnv = tableEnv;
        this.runtimeCoordinator = runtimeCoordinator;
    }

    public void validate(String sql) {
        if (sql == null || sql.trim().isEmpty()) return;
        // Hors littéraux, comme toute lecture lexicale de ce dépôt : `WHERE msg = 'CROSS JOIN'`
        // est une valeur, pas une jointure, et cette instruction était refusée pour le contenu
        // d'une chaîne — un faux positif sur une requête que rien n'interdisait.
        String upperSql = SqlStatements.outsideLiterals(sql).toUpperCase();

        if (!explorerConfig.isAllowCrossJoin()) {
            if (upperSql.contains("CROSS JOIN")) {
                throw new IllegalArgumentException("Cross joins are not allowed in this environment.");
            }
            /*
             * `FROM a, b` est une jointure croisée écrite à l'ancienne, et aucun garde ne la voyait
             * passer : le texte ne contient pas « CROSS JOIN », et l'heuristique sur le plan ne
             * nomme que ce que Flink y écrit explicitement. Le parseur, lui, en fait la même
             * jointure que celle écrite en toutes lettres, et il répond avant toute résolution de
             * table — donc y compris sur un topic pas encore enregistré, là où l'EXPLAIN plus bas
             * ne répond pas.
             */
            if (SqlAst.read(sql).map(SqlAst.Read::crossJoin).orElse(false)) {
                throw new IllegalArgumentException("Cross joins are not allowed in this environment.");
            }
        }

        if (explorerConfig.isAllowCrossJoin() && explorerConfig.isAllowSystemTableAccess()) {
            return;
        }

        /*
         * EXPLAIN ne s'applique pas à du DDL (CREATE TABLE, ALTER, DROP) : Flink répond
         * « Unsupported operation: CreateTableOperation ». Il s'applique en revanche à un INSERT,
         * et c'est ce que ce pré-vol ne faisait pas.
         *
         * L'éditeur appelle `/api/query/validate` avant *chaque* Run, mode Job compris — mais
         * cette méthode sortait ici pour tout ce qui n'est pas SELECT/EXPLAIN, si bien qu'un
         * INSERT n'était vérifié par rien : sa faute de frappe n'était trouvée qu'en le
         * soumettant, ce qui écrit un enregistrement FAILED dans le magasin de jobs et consomme
         * un des emplacements retenus. Mesuré sur ce runtime, `explainSql` sur un INSERT
         * distingue exactement ce qu'il faut : une faute de syntaxe remonte en erreur de parseur
         * (rejetée ci-dessous avec sa ligne et sa colonne), tandis qu'une table non résolue reste
         * une erreur de résolution — avalée, comme pour un SELECT, puisque ce contrôle passe
         * *avant* l'auto-enregistrement des sources.
         *
         * La classification lit au-delà d'une chaîne CTE de tête, comme partout ailleurs : sans
         * cela un `WITH … SELECT` n'était pas validé non plus.
         */
        String upperTrimmed = SqlStatements.classifiableBody(sql.trim());
        if (!upperTrimmed.startsWith("SELECT") && !upperTrimmed.startsWith("EXPLAIN")
                && !upperTrimmed.startsWith("INSERT")) {
            return;
        }

        try {
            // We use EXPLAIN to get the execution plan and check for forbidden patterns
            String plan = runtimeCoordinator.runRead("sql-validator-explain", () -> tableEnv.explainSql(sql).toUpperCase());

            if (!explorerConfig.isAllowCrossJoin() && isCrossJoinInPlan(plan)) {
                throw new IllegalArgumentException("Cross joins are not allowed in this environment.");
            }

            if (!explorerConfig.isAllowSystemTableAccess() && isSystemTableInPlan(plan)) {
                throw new IllegalArgumentException("Access to system tables is restricted.");
            }
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException) throw (IllegalArgumentException) e;
            // A parse error never depends on the catalog, so reporting it here costs no Kafka round
            // trip and gives the editor its line/column immediately.
            if (SqlErrorClassifier.isSyntaxError(e)) {
                throw new IllegalArgumentException(SqlErrorClassifier.explain(e), e);
            }
            // EXPLAIN fails for tables not registered in Flink (e.g. Kafka topics with dotted names
            // or tables referenced before auto-registration). This is expected — actual execution handles it.
            log.debug("SQL validation via EXPLAIN skipped (table not resolvable): {}", e.getMessage());
        }
    }

    /**
     * Une jointure croisée <em>dans le plan</em>, sur les formulations que Flink y écrit.
     *
     * <p>La dernière condition a disparu : {@code plan.contains("JOIN") && !plan.contains("ON")}
     * portait sur le texte entier du plan, où « ON » apparaît dans quantité de mots et de noms de
     * colonnes — elle refusait donc des jointures ordinaires et laissait passer des croisées, sans
     * qu'on puisse dire laquelle des deux se produisait. Ce que le plan nomme explicitement est
     * gardé ; ce qui se lit dans l'instruction est demandé au parseur, qui répond exactement et
     * plus tôt.
     */
    private boolean isCrossJoinInPlan(String plan) {
        // Hors littéraux, ici aussi : un plan recopie les valeurs de la requête, donc
        // `WHERE state = 'CROSS JOIN'` écrit ces deux mots dans le plan et faisait refuser une
        // requête qui ne joint rien. C'est le même faux positif qu'au niveau du texte, un étage
        // plus bas — et il est resté seul debout quand l'autre a été corrigé.
        String outsideValues = SqlStatements.outsideLiterals(plan);
        return outsideValues.contains("JOIN_TYPE: CROSS") ||
               outsideValues.contains("CROSS JOIN") ||
               outsideValues.contains("CARTESIAN");
    }

    private boolean isSystemTableInPlan(String plan) {
        return plan.contains("INFORMATION_SCHEMA") ||
               plan.contains("SYS.") ||
               plan.contains("SYSTEM.");
    }
}
