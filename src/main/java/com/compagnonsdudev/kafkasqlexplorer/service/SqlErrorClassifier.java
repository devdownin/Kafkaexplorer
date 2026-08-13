// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tells apart "the query is wrong" from "the engine broke".
 *
 * <p>The SELECT path falls back to the direct Kafka reader whenever the Flink planner fails. That
 * is right for an engine fault, and wrong for a typo: the direct reader only regex-matches a table
 * name out of the FROM clause, so a malformed or semantically invalid query comes back as rows —
 * a broken query that appears to succeed — and the planner's precise complaint (with its line and
 * column) is thrown away. User errors must therefore stop at the planner and be reported as-is.
 *
 * <p>The same distinction protects the SELECT circuit breaker: three typos in a row must not
 * disable the Flink planner for the rest of the process lifetime.
 */
public final class SqlErrorClassifier {

    /** A planner stack can chain a dozen wrappers; the UI shows the raw text verbatim. */
    private static final int MAX_EXPLAIN_CHARS = 2_000;

    private SqlErrorClassifier() {
    }

    public enum Kind {
        /** The statement itself is invalid — syntax, unknown object, bad types. Report, don't retry. */
        USER_ERROR,
        /** The engine failed on a plausible statement — fall back and keep the query working. */
        ENGINE_ERROR
    }

    public record Classification(Kind kind, String message) {
        public boolean isUserError() {
            return kind == Kind.USER_ERROR;
        }
    }

    /**
     * Exception types that mean the statement was rejected before anything ran. Matched on the
     * simple class name so the classifier stays usable on a message string alone (the planner path
     * only carries the text back) and does not pin Flink/Calcite internals into the signature.
     */
    private static final Pattern USER_ERROR_TYPES = Pattern.compile(
        "SqlParserException|SqlParseException|SqlValidateException|ValidationException"
            + "|CalciteContextException|SqlValidatorException",
        Pattern.CASE_INSENSITIVE);

    /** Wording the planner uses when it is the statement, not the runtime, that is at fault. */
    private static final Pattern USER_ERROR_TEXT = Pattern.compile(
        "SQL parse failed"
            + "|SQL validation failed"
            + "|encountered \"[^\"]*\" at line"
            + "|was expecting"
            + "|(?:object|table|column|view|function) '[^']*' not found"
            + "|(?:object|table|column|view|function) \"[^\"]*\" not found"
            + "|no match found for function signature"
            + "|unknown identifier"
            + "|cannot apply '"
            + "|is not a valid (?:column|table|identifier)"
            + "|expression '[^']*' is not being grouped"
            + "|non-query expression encountered"
            + "|incompatible types"
            + "|cannot be cast to"
            + "|argument type mismatch"
            + "|not allowed in this environment"
            + "|access to system tables is restricted"
            + "|only select, explain and create table",
        Pattern.CASE_INSENSITIVE);

    /**
     * Wording that outranks {@link #USER_ERROR_TEXT}: a genuine engine fault whose stack trace may
     * still mention a validation class. The historical {@code FlinkRelMetadataQuery} NPE is the
     * reason the fallback exists at all — it must keep falling back.
     */
    private static final Pattern ENGINE_ERROR_TEXT = Pattern.compile(
        "NullPointerException"
            + "|metadataHandlerProvider"
            + "|RelMetadataQuery"
            + "|NoSuchMethodError"
            + "|NoClassDefFoundError"
            + "|ClassNotFoundException"
            + "|StackOverflowError"
            + "|OutOfMemoryError"
            + "|could not (?:be resolved|instantiate|initialize)"
            + "|failed to (?:deserialize|submit) the job"
            // Le runtime était occupé : l'instruction est bonne, le moteur n'était pas libre.
            // C'est un défaut moteur, donc un SELECT doit se replier sur le lecteur direct plutôt
            // que d'être renvoyé à l'utilisateur comme une requête invalide.
            + "|Flink runtime was busy",
        Pattern.CASE_INSENSITIVE);

    /** Purely syntactic rejections — the parser never reached name resolution. */
    private static final Pattern SYNTAX_ERROR_TEXT = Pattern.compile(
        "SqlParserException|SqlParseException|SQL parse failed|encountered \"[^\"]*\" at line|was expecting",
        Pattern.CASE_INSENSITIVE);

    /**
     * True when the statement failed to parse, as opposed to failing to resolve a name.
     *
     * <p>Pre-flight validation runs before tables are auto-registered, so an unresolved table there
     * is expected and must stay silent. A parse error never depends on the catalog, so it is safe —
     * and much faster — to report it before the query touches Kafka at all.
     */
    public static boolean isSyntaxError(Throwable error) {
        String message = explain(error);
        return !ENGINE_ERROR_TEXT.matcher(message).find() && SYNTAX_ERROR_TEXT.matcher(message).find();
    }

    /** Classifies a raw error message (the planner path carries text, not the exception). */
    public static Classification classify(String rawMessage) {
        String message = rawMessage == null ? "" : rawMessage.trim();
        if (message.isEmpty()) {
            return new Classification(Kind.ENGINE_ERROR, "The query engine failed without reporting a reason.");
        }
        if (ENGINE_ERROR_TEXT.matcher(message).find()) {
            return new Classification(Kind.ENGINE_ERROR, message);
        }
        Kind kind = USER_ERROR_TYPES.matcher(message).find() || USER_ERROR_TEXT.matcher(message).find()
            ? Kind.USER_ERROR
            : Kind.ENGINE_ERROR;
        return new Classification(kind, message);
    }

    /** Classifies a throwable, flattening its cause chain into one explicit message first. */
    public static Classification classify(Throwable error) {
        return classify(explain(error));
    }

    /**
     * Builds the most explicit single-line-ish message a throwable can give.
     *
     * <p>Never returns null or blank: a {@code NullPointerException} carries no message at all, and
     * handing that straight to {@code QueryResult.error()} used to null out the error field, so the
     * UI reported a successful run of zero rows for a query that had in fact crashed.
     *
     * <p>Causes are appended rather than replaced — Flink habitually wraps the useful Calcite text
     * ("SQL parse failed. Encountered ... at line 2, column 8") inside a generic outer exception,
     * and the line/column the editor highlights lives in the inner one.
     */
    public static String explain(Throwable error) {
        if (error == null) {
            return "The query engine failed without reporting a reason.";
        }
        List<String> parts = new ArrayList<>();
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = error; t != null && seen.add(t); t = t.getCause()) {
            String message = t.getMessage();
            String part = message == null || message.isBlank()
                ? t.getClass().getSimpleName()
                : message.trim();
            // Flink habitually re-wraps the same text. Keep whichever phrasing says more, so a
            // cause that elaborates on its wrapper replaces it rather than being dropped.
            int subsumed = -1;
            boolean redundant = false;
            for (int i = 0; i < parts.size(); i++) {
                String existing = parts.get(i);
                if (existing.contains(part)) {
                    redundant = true;
                    break;
                }
                if (part.contains(existing)) {
                    subsumed = i;
                    break;
                }
            }
            if (redundant) continue;
            if (subsumed >= 0) parts.set(subsumed, part);
            else parts.add(part);
        }
        String explained = parts.isEmpty()
            ? error.getClass().getSimpleName()
            : String.join(": ", parts);
        return explained.length() > MAX_EXPLAIN_CHARS
            ? explained.substring(0, MAX_EXPLAIN_CHARS) + "… (truncated)"
            : explained;
    }
}
