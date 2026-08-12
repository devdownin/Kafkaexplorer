// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.service;

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
