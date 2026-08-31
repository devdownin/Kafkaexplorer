// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import java.util.Locale;

/**
 * Reading the shape of a statement without parsing it.
 *
 * <p>Every keyword check in the query path is a {@code startsWith}: the statement whitelist, the
 * SELECT branch, auto-registration, the job-mode classification. That made a **common table
 * expression impossible to run at all** — {@code WITH recent AS (SELECT …) SELECT * FROM recent}
 * begins with {@code WITH}, so it was refused with "Only SELECT, EXPLAIN and CREATE TABLE
 * statements are allowed", a message that reads as a security restriction rather than as a gap in
 * the guard. Flink SQL supports CTEs, and they are ordinary SQL the moment a query outgrows three
 * lines.
 *
 * <p>Removing the leading CTE chain lets all of those checks keep working unchanged on the
 * statement they were meant to classify. The statement actually executed is always the whole one —
 * only the classification looks past the chain.
 */
public final class SqlStatements {

    private SqlStatements() {}

    /**
     * The statement with a leading {@code WITH … AS ( … )} chain removed, trimmed.
     *
     * <p><strong>Fails closed.</strong> Anything that is not exactly the recognised shape comes back
     * unchanged, so it is classified as it was before — refused. A guard that guesses when it is
     * confused is worse than one that is narrow.
     */
    public static String withoutLeadingCte(String sql) {
        if (sql == null) return "";
        String s = sql.trim();
        if (!isWordAt(s, 0, "WITH")) return s;

        int i = skipSpace(s, 4);
        if (isWordAt(s, i, "RECURSIVE")) i = skipSpace(s, i + 9);

        boolean consumedACte = false;
        while (i < s.length()) {
            int afterName = skipIdentifier(s, i);
            if (afterName < 0) return s;
            i = skipSpace(s, afterName);

            // Optional column list: WITH t (a, b) AS ( … )
            if (i < s.length() && s.charAt(i) == '(') {
                int afterCols = skipParens(s, i);
                if (afterCols < 0) return s;
                i = skipSpace(s, afterCols);
            }

            if (!isWordAt(s, i, "AS")) return s;
            i = skipSpace(s, i + 2);

            if (i >= s.length() || s.charAt(i) != '(') return s;
            int afterBody = skipParens(s, i);
            if (afterBody < 0) return s;
            i = skipSpace(s, afterBody);
            consumedACte = true;

            if (i < s.length() && s.charAt(i) == ',') {
                i = skipSpace(s, i + 1);
                continue;
            }
            break;
        }

        // Nothing recognised, or a chain with no statement behind it (`WITH a AS (SELECT 1)` on its
        // own): unchanged, so the caller refuses it exactly as before.
        String body = consumedACte ? s.substring(i).trim() : s;
        return body.isEmpty() ? s : body;
    }

    /** Whether the statement opens with a common table expression we recognised. */
    public static boolean startsWithCte(String sql) {
        if (sql == null) return false;
        String trimmed = sql.trim();
        return isWordAt(trimmed, 0, "WITH") && !withoutLeadingCte(trimmed).equals(trimmed);
    }

    /**
     * Le calcul de fenêtre au-dessus d'une table : {@code TABLE(TUMBLE(TABLE t, …))} et ses trois
     * voisins.
     *
     * <p>Une définition, deux lecteurs, et c'est la raison d'être de cette méthode : la règle était
     * écrite deux fois dans {@code FlinkSqlService}, sous deux formes qui <strong>ne nommaient pas
     * les mêmes fonctions</strong> — celle qui extrait la table source connaît {@code CUMULATE},
     * celle qui décidait d'aiguiller vers le calcul de fenêtre ne la connaissait pas. Un
     * {@code CUMULATE} arrivé au lecteur direct n'était donc pas approximé mais traité comme une
     * agrégation ordinaire : <em>la fenêtre disparaissait sans un mot</em>, là où ses trois voisins
     * repartaient au moins avec l'avertissement disant qu'elles avaient été approximées.
     *
     * <p>Lexical et non parsé, délibérément : les appelants s'en servent pour <em>choisir un
     * moteur</em>, avant qu'aucun catalogue ne soit consulté, et une instruction que le parseur
     * refuserait doit pouvoir être aiguillée quand même.
     */
    public static boolean hasWindowTableCall(String sql) {
        return sql != null && WINDOW_TABLE_CALL.matcher(sql).find();
    }

    private static final java.util.regex.Pattern WINDOW_TABLE_CALL =
        java.util.regex.Pattern.compile("(?i)\\bTABLE\\s*\\(\\s*(TUMBLE|HOP|CUMULATE|SESSION)\\s*\\(");

    /**
     * L'instruction porte-t-elle ses propres options de connecteur ?
     *
     * <p>{@code /*+ OPTIONS('scan.bounded.mode'='latest-offset') *}{@code /} est un <em>hint</em>
     * Calcite, pas un commentaire (voir {@code FlinkSqlService.stripSqlComments}, qui le
     * distingue au {@code +}), et c'est l'auteur qui dit ce qu'il veut du connecteur. Deux
     * appelants en dépendent et pour la même raison : {@code MetricService} n'injecte pas le sien
     * par-dessus, et l'aiguillage d'une fenêtre laisse une telle instruction au planner plutôt que
     * de l'envoyer à un lecteur qui n'a aucune option à honorer.
     *
     * <p>{@code OPTIONS(} nu compte aussi, prudemment : une occurrence dans une chaîne de
     * caractères n'y changerait qu'une chose, laisser la requête au moteur qui la lisait déjà.
     */
    public static boolean carriesAnOptionsHint(String sql) {
        return sql != null
            && (sql.contains("/*+") || sql.toUpperCase(java.util.Locale.ROOT).contains("OPTIONS("));
    }

    /**
     * Le texte de l'instruction, le <em>contenu</em> de chaque littéral et de chaque identifiant
     * échappé remplacé par des espaces — la longueur et toutes les positions sont conservées.
     *
     * <p>C'est la base de toute lecture lexicale de ce dépôt, et elle manquait à presque toutes.
     * Un motif appliqué au texte brut ne sait pas où commence une chaîne, donc il trouve ses
     * mots-clés dedans : {@code WHERE note = 'voir -- plus bas'} perdait tout ce qui suit le
     * {@code --} au retrait des commentaires, {@code WHERE msg = 'CROSS JOIN'} était refusé par le
     * garde, {@code WHERE note = 'select * from autre'} faisait enregistrer une table fantôme, et
     * un {@code LIMIT} cité dans une valeur tronquait la lecture. Aucun de ces cas ne rend une
     * erreur : ils rendent une <em>autre</em> requête que celle qui a été écrite.
     *
     * <p>Deux règles, celles de Calcite. Une quote doublée à l'intérieur d'un littéral l'échappe et
     * ne le ferme pas ({@code 'It''s'}), et un accent grave ouvre un <em>identifiant</em> qui peut
     * contenir n'importe quoi — d'où {@code CREATE TABLE `weird as select`}, faux positif déjà payé
     * une fois ici. Les délimiteurs eux-mêmes sont conservés : ce qui est neutralisé, c'est ce
     * qu'ils entourent, pour qu'un motif qui les cite continue de fonctionner.
     *
     * <p>Les positions étant préservées, un appelant qui a besoin de la <em>valeur</em> d'un
     * littéral trouve ses bornes ici et découpe le texte d'origine.
     */
    public static String outsideLiterals(String sql) {
        if (sql == null) return null;
        StringBuilder out = new StringBuilder(sql.length());
        char delimiter = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (delimiter == 0) {
                if (c == '\'' || c == '`') delimiter = c;
                out.append(c);
                continue;
            }
            if (c == delimiter) {
                // Doublé, il échappe et ne ferme pas — vrai des deux délimiteurs.
                if (i + 1 < sql.length() && sql.charAt(i + 1) == delimiter) {
                    out.append("  ");
                    i++;
                    continue;
                }
                delimiter = 0;
                out.append(c);
                continue;
            }
            out.append(' ');
        }
        return out.toString();
    }

    /** True when `word` sits at `from` as a whole word, case-insensitively. */
    private static boolean isWordAt(String s, int from, String word) {
        if (from < 0 || from + word.length() > s.length()) return false;
        if (!s.regionMatches(true, from, word, 0, word.length())) return false;
        int after = from + word.length();
        return after == s.length() || !isIdentifierChar(s.charAt(after));
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static int skipSpace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    /** Past an identifier — bare, or quoted with backticks or double quotes. -1 if there is none. */
    private static int skipIdentifier(String s, int i) {
        if (i >= s.length()) return -1;
        char c = s.charAt(i);
        if (c == '`' || c == '"') {
            int end = s.indexOf(c, i + 1);
            return end < 0 ? -1 : end + 1;
        }
        if (!Character.isLetter(c) && c != '_') return -1;
        int j = i;
        while (j < s.length() && isIdentifierChar(s.charAt(j))) j++;
        return j;
    }

    /**
     * Past a balanced parenthesised block starting at {@code i}. -1 when it never closes.
     *
     * <p>String literals and quoted identifiers are skipped whole: a {@code (} inside {@code 'a(b'}
     * closes nothing, and a CTE body very often contains one.
     */
    private static int skipParens(String s, int i) {
        if (i >= s.length() || s.charAt(i) != '(') return -1;
        int depth = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'') {
                i++;
                while (i < s.length()) {
                    if (s.charAt(i) == '\'') {
                        if (i + 1 < s.length() && s.charAt(i + 1) == '\'') { i += 2; continue; }
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '`' || c == '"') {
                int end = s.indexOf(c, i + 1);
                if (end < 0) return -1;
                i = end + 1;
                continue;
            }
            if (c == '(') depth++;
            if (c == ')') {
                depth--;
                if (depth == 0) return i + 1;
            }
            i++;
        }
        return -1;
    }

    /** Upper-cased convenience for the callers that only compare a prefix. */
    public static String classifiableBody(String sql) {
        return withoutLeadingCte(sql).toUpperCase(Locale.ROOT);
    }
}
