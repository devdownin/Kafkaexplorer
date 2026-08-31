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
            // La formulation de Flink pour la *cible* d'un INSERT qui n'existe pas — une
            // faute de frappe dans un nom de table, donc l'utilisateur, là où la source
            // d'un SELECT donne « Object 'x' not found » déjà couvert au-dessus. Sans
            // elle, le mode Job répondait 500 à une faute de frappe.
            + "|cannot find table '[^']*' in any of the catalogs"
            + "|no match found for function signature"
            + "|unknown identifier"
            + "|cannot apply '"
            + "|is not a valid (?:column|table|identifier)"
            + "|expression '[^']*' is not being grouped"
            + "|non-query expression encountered"
            + "|incompatible types"
            // Une projection qui ne rentre pas dans le sink. Flink le dit de deux façons —
            // « Different number of columns » et « Incompatible types for sink column » — et
            // seule la seconde était reconnue, alors que c'est une seule et même faute : la
            // requête ne correspond pas à la table cible. La première est même la plus courante,
            // `INSERT INTO sink SELECT * FROM source` sur une table auto-générée ramenant la
            // colonne calculée `proc_time` qu'aucun sink n'accepte — et elle répondait 500,
            // c'est-à-dire « panne du serveur », là où l'INSERT est le seul geste de l'éditeur
            // qui n'a aucun repli pour rattraper l'erreur.
            + "|column types of query result and sink"
            // Un sink qui n'implémente pas SupportsOverwrite : c'est l'instruction qui demande à
            // cette table ce qu'elle ne sait pas faire, pas le moteur qui tombe.
            + "|insert overwrite requires"
            // Un hint d'options posé sur une vue plutôt que sur une table.
            + "|cannot be enriched with new options"
            // Ce que le planner *streaming* refuse de construire : la requête est valide en SQL et
            // n'a pas de sens sur un flux. Non reconnues, ces deux-là étaient des pannes moteur,
            // donc la requête se repliait sur le lecteur direct — qui ne connaît que des topics
            // Kafka et répondait « Table 'x' not found » sur une table qui existe, en reléguant la
            // vraie raison dans les warnings. C'est la substitution que ce classifieur existe pour
            // empêcher : l'avis d'un autre moteur sur une requête qu'il n'a jamais su exécuter.
            + "|sort on a non-time-attribute field is not supported"
            // Un OVER en temps événement sur une colonne qui n'est pas un attribut temporel. La
            // *fenêtre* TVF est traitée à part (voir `mentionsATimeAttribute`) parce que le
            // lecteur direct sait vraiment en calculer une ; un OVER, non — il l'ignorerait en
            // silence et rendrait des lignes, ce qui est la substitution que ce classifieur
            // existe pour empêcher.
            + "|over windows' ordering in stream mode must be defined on a time attribute"
            + "|unexpected correlate variable"
            + "|cannot be cast to"
            + "|argument type mismatch"
            + "|not allowed in this environment"
            + "|access to system tables is restricted"
            // La liste des instructions admises s'est allongée (SHOW, DESCRIBE) : le motif
            // lit les deux bouts de la phrase plutôt que son énumération, qui bougera encore.
            + "|only select, explain[^.]{0,60}create table",
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

    /**
     * Ce que le planner dit quand une expression temporelle porte sur une colonne qui n'est pas
     * un <em>attribut temporel</em> — une colonne horodatée sur laquelle aucun watermark n'est
     * déclaré.
     *
     * <p>Volontairement large, parce que le seul appelant la restreint déjà à une forme précise :
     * {@code FlinkSqlService.windowNeedsATimeAttribute} ne la consulte que sur une requête qui
     * contient un {@code TABLE(TUMBLE(…))}. La formulation exacte a déjà changé entre versions de
     * Flink, et une garde de forme vaut mieux qu'une énumération de phrases.
     */
    private static final Pattern TIME_ATTRIBUTE_TEXT = Pattern.compile(
        "requires the timecol is a time attribute"
            + "|(?:must|should) be defined on a time attribute"
            + "|(?:is|are) not a time attribute"
            + "|must be a time attribute"
            + "|can only be defined over a time attribute",
        Pattern.CASE_INSENSITIVE);

    /** Whether this failure is "that column carries no watermark", in any of Flink's wordings. */
    public static boolean mentionsATimeAttribute(String rawMessage) {
        return rawMessage != null && TIME_ATTRIBUTE_TEXT.matcher(rawMessage).find();
    }

    /**
     * L'enveloppe Calcite d'un échec de planification : la règle, ses arguments et le plan.
     *
     * <p>Un refus arrive sous la forme {@code Error while applying rule
     * StreamPhysicalWindowTableFunctionRule(in:LOGICAL,out:STREAM_PHYSICAL), args
     * [rel#27876:FlinkLogicalTableFunctionScan…(…rowType=RecordType(…))]: <la vraie phrase>}. Les
     * quatre cents caractères de tête décrivent l'état interne du planificateur ; la phrase utile
     * est la dernière. Telle quelle, elle arrivait dans l'éditeur au bout d'un pavé que personne
     * ne lit — et qui fait passer une définition de table à corriger pour une panne du serveur.
     */
    private static final String RULE_FAILURE_PREFIX = "Error while applying rule ";

    /** Ce qui ferme la liste d'arguments et ouvre la vraie phrase. */
    private static final String RULE_ARGS_END = "]:";

    /**
     * The same failure, with the planner's internal plan dump taken off the front.
     *
     * <p>Rien n'est perdu qui aide : le nom de la règle est conservé entre parenthèses (c'est ce
     * qu'on cherche dans le journal de Flink), et la phrase de fin est rendue mot pour mot. Le
     * classement, lui, continue de se faire sur le message complet — cette méthode est de la
     * présentation, pas de la logique.
     */
    public static String readable(String rawMessage) {
        if (rawMessage == null) return null;
        String message = rawMessage.trim();
        // Trois recherches littérales plutôt qu'une expression rationnelle : la liste d'arguments
        // contient elle-même des crochets, donc le motif aurait un `.*?` non ancré — la forme que
        // CodeQL signale (java/polynomial-redos) et que ce dépôt a déjà payée une fois.
        int at = message.indexOf(RULE_FAILURE_PREFIX);
        if (at < 0) return rawMessage;
        int nameStart = at + RULE_FAILURE_PREFIX.length();
        int nameEnd = nameStart;
        while (nameEnd < message.length()
            && (Character.isLetterOrDigit(message.charAt(nameEnd)) || message.charAt(nameEnd) == '_')) {
            nameEnd++;
        }
        int argsEnd = message.indexOf(RULE_ARGS_END, nameEnd);
        if (nameEnd == nameStart || argsEnd < 0) return rawMessage;
        String cause = message.substring(argsEnd + RULE_ARGS_END.length()).trim();
        if (cause.isEmpty()) return rawMessage;
        // Ce qui précède l'enveloppe est conservé : Flink emboîte volontiers un « Cannot generate a
        // valid execution plan… » par-dessus, et c'est une phrase qui aide, contrairement au plan.
        return message.substring(0, at) + cause
            + " (Flink planner rule " + message.substring(nameStart, nameEnd) + ")";
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
        // Avant la troncature, jamais après : l'enveloppe Calcite est faite d'un message et de sa
        // cause, donc la phrase utile est *derrière* le plan et c'est elle que la coupe à
        // MAX_EXPLAIN_CHARS emporte en premier sur une table large.
        explained = readable(explained);
        return explained.length() > MAX_EXPLAIN_CHARS
            ? explained.substring(0, MAX_EXPLAIN_CHARS) + "… (truncated)"
            : explained;
    }
}
