// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlErrorClassifierTest {

    // ── User errors: the statement is wrong, don't fall back to the direct reader ──────

    @Test
    void aParseFailureIsAUserError() {
        var c = SqlErrorClassifier.classify(
            "SQL parse failed. Encountered \"FROM\" at line 1, column 12.");

        assertTrue(c.isUserError());
        assertTrue(c.message().contains("line 1, column 12"), "the position must survive for the editor marker");
    }

    @Test
    void anUnknownTableIsAUserError() {
        assertTrue(SqlErrorClassifier.classify("Object 'ordrs' not found").isUserError());
        assertTrue(SqlErrorClassifier.classify("Table 'clicks' not found within cluster").isUserError());
    }

    @Test
    void anUnknownColumnIsAUserError() {
        assertTrue(SqlErrorClassifier.classify("Column 'amountt' not found in any table").isUserError());
    }

    @Test
    void aTypeMismatchIsAUserError() {
        assertTrue(SqlErrorClassifier.classify(
            "Cannot apply '>' to arguments of type '<VARCHAR> > <INTEGER>'").isUserError());
    }

    @Test
    void anExceptionTypeAloneIsEnoughToRecogniseAUserError() {
        assertTrue(SqlErrorClassifier.classify(
            "org.apache.flink.table.api.ValidationException: something the patterns don't spell out")
            .isUserError());
    }

    /**
     * Une projection qui ne rentre pas dans le sink, dans les deux formulations de Flink.
     *
     * <p>Seule la seconde était reconnue : la même faute — la requête ne correspond pas à la table
     * cible — répondait 400 par le type et 500 par le nombre de colonnes. C'est le cas mal classé
     * qui est le plus courant, {@code INSERT INTO <sink> SELECT * FROM <source>} sur une table
     * auto-générée ramenant la colonne calculée {@code proc_time}, et l'INSERT est le seul geste
     * de l'éditeur qui n'a aucun repli pour rattraper l'erreur.
     */
    @Test
    void aSinkThatTheProjectionDoesNotFitIsAUserError() {
        assertTrue(SqlErrorClassifier.classify(
            "Column types of query result and sink for 'default_catalog.default_database.sink' do not "
                + "match. Cause: Different number of columns.").isUserError());
        assertTrue(SqlErrorClassifier.classify(
            "Column types of query result and sink for 'default_catalog.default_database.sink' do not "
                + "match. Cause: Incompatible types for sink column 'order_id' at position 1.").isUserError());
    }

    /** Un sink qui ne sait pas écraser, et un hint posé sur une vue : l'instruction, pas le moteur. */
    @Test
    void aSinkThatCannotHonourTheStatementIsAUserError() {
        assertTrue(SqlErrorClassifier.classify(
            "INSERT OVERWRITE requires that the underlying DynamicTableSink of table "
                + "'default_catalog.default_database.sink' implements the SupportsOverwrite interface.")
            .isUserError());
        assertTrue(SqlErrorClassifier.classify(
            "View '`default_catalog`.`default_database`.`orders`' cannot be enriched with new options. "
                + "Hints can only be applied to tables.").isUserError());
    }

    /**
     * Ce que le planner streaming refuse de construire est une faute de la requête.
     *
     * <p>Un `ORDER BY` sans borne et un `EXISTS` corrélé sont du SQL valide qui n'a pas de sens sur
     * un flux. Classés en panne moteur, ils se repliaient sur le lecteur direct, qui ne connaît que
     * des topics et répondait « Table 'x' not found » — sur une table qui existe — tandis que la
     * vraie raison partait dans les warnings.
     */
    @Test
    void whatTheStreamingPlannerCannotBuildIsAUserError() {
        assertTrue(SqlErrorClassifier.classify(
            "Sort on a non-time-attribute field is not supported.").isUserError());
        assertTrue(SqlErrorClassifier.classify(
            "unexpected correlate variable $cor1 in the plan").isUserError());
    }

    @Test
    void aRestrictedStatementIsAUserError() {
        assertTrue(SqlErrorClassifier.classify("Cross joins are not allowed in this environment.").isUserError());
        assertTrue(SqlErrorClassifier.classify("Access to system tables is restricted.").isUserError());
    }

    // ── Engine errors: keep falling back, that is what the direct reader is for ────────

    @Test
    void theHistoricalPlannerNpeStaysAnEngineError() {
        var c = SqlErrorClassifier.classify(
            "java.lang.NullPointerException: Cannot invoke "
                + "\"org.apache.calcite.rel.metadata.RelMetadataQuery.metadataHandlerProvider()\"");

        assertFalse(c.isUserError(), "the NPE the fallback exists for must keep falling back");
    }

    @Test
    void anEngineFaultOutranksValidationWordingInTheSameChain() {
        // A NoClassDefFoundError surfacing through a ValidationException wrapper is an
        // environment problem, not a bad query — misreading it would kill the fallback.
        var c = SqlErrorClassifier.classify(
            "ValidationException: NoClassDefFoundError: org/apache/flink/table/runtime/Foo");

        assertFalse(c.isUserError());
    }

    @Test
    void anUnreachableBrokerIsAnEngineError() {
        assertFalse(SqlErrorClassifier.classify("Timeout expired while fetching topic metadata").isUserError());
    }

    @Test
    void aBlankMessageIsAnEngineErrorWithAReadableText() {
        var c = SqlErrorClassifier.classify("   ");

        assertFalse(c.isUserError());
        assertFalse(c.message().isBlank());
    }

    // ── explain(): never blank, never loses the useful cause ──────────────────────────

    @Test
    void explainFlattensTheCauseChainKeepingTheInnerDetail() {
        Throwable root = new IllegalStateException("SQL parse failed. Encountered \"FORM\" at line 2, column 8.");
        Throwable wrapped = new RuntimeException("Failed to execute query", root);

        String explained = SqlErrorClassifier.explain(wrapped);

        assertTrue(explained.contains("Failed to execute query"));
        assertTrue(explained.contains("line 2, column 8"));
        assertTrue(SqlErrorClassifier.classify(wrapped).isUserError());
    }

    @Test
    void explainNeverReturnsBlankForAMessagelessThrowable() {
        // The bug this guards: e.getMessage() is null for a bare NPE, so QueryResult.error()
        // came back null and the UI read a crash as a successful run of zero rows.
        String explained = SqlErrorClassifier.explain(new NullPointerException());

        assertFalse(explained.isBlank());
        assertTrue(explained.contains("NullPointerException"));
    }

    @Test
    void explainDoesNotRepeatAWrapperThatOnlyEchoesItsCause() {
        Throwable root = new IllegalStateException("Object 'orders' not found");
        Throwable wrapped = new RuntimeException("Object 'orders' not found", root);

        assertEquals("Object 'orders' not found", SqlErrorClassifier.explain(wrapped));
    }

    @Test
    void explainKeepsTheRicherPhrasingWhenTheCauseElaborates() {
        Throwable root = new IllegalStateException("Cannot instantiate user function: missing constructor");
        Throwable wrapped = new RuntimeException("Cannot instantiate user function", root);

        assertEquals("Cannot instantiate user function: missing constructor", SqlErrorClassifier.explain(wrapped));
    }

    @Test
    void explainSurvivesASelfReferencingCauseChain() {
        Throwable a = new RuntimeException("outer");
        Throwable b = new RuntimeException("inner", a);
        a.initCause(b);

        assertEquals("outer: inner", SqlErrorClassifier.explain(a));
    }

    @Test
    void explainOfNullStillReadsAsAnError() {
        assertFalse(SqlErrorClassifier.explain(null).isBlank());
    }

    // ── Le message tel qu'on le montre : la phrase, pas le plan ──────────────────────

    /** Le refus exact que produit une fenêtre sur une colonne sans watermark. */
    private static final String WINDOW_RULE_FAILURE =
        "Error while applying rule StreamPhysicalWindowTableFunctionRule(in:LOGICAL,out:STREAM_PHYSICAL), "
            + "args [rel#27876:FlinkLogicalTableFunctionScan.LOGICAL.any.None: 0.[NONE].[NONE].[NONE]"
            + "(input#0=RelSubset#27875,invocation=TUMBLE(TABLE(#0), DESCRIPTOR(_UTF-16LE'event_time'), "
            + "300000:INTERVAL MINUTE),rowType=RecordType(TIMESTAMP(3) event_time, TIMESTAMP(3) window_start, "
            + "TIMESTAMP(3) window_end, TIMESTAMP(3) window_time))]: The window function requires the "
            + "timecol is a time attribute type, but is TIMESTAMP(3).";

    /**
     * L'enveloppe Calcite est retirée, la phrase et le nom de la règle restent.
     *
     * <p>Trois cents caractères d'état interne du planificateur arrivaient dans l'éditeur devant la
     * seule phrase qui dit quelque chose. La règle est gardée — c'est ce qu'on cherche dans le
     * journal de Flink — mais le plan {@code rel#…}, non.
     */
    @Test
    void readableKeepsTheSentenceAndDropsThePlan() {
        String readable = SqlErrorClassifier.readable(WINDOW_RULE_FAILURE);

        assertTrue(readable.startsWith("The window function requires the timecol is a time attribute"),
            "the cause must lead, got: " + readable);
        assertFalse(readable.contains("rel#"), "the plan dump must go, got: " + readable);
        assertTrue(readable.contains("StreamPhysicalWindowTableFunctionRule"),
            "the rule name is what one greps for in the Flink log, got: " + readable);
    }

    @Test
    void readableLeavesAnOrdinaryMessageAlone() {
        assertEquals("Object 'ordrs' not found", SqlErrorClassifier.readable("Object 'ordrs' not found"));
        assertNull(SqlErrorClassifier.readable(null));
    }

    /** Ce que Flink emboîte par-dessus reste : c'est une phrase, contrairement au plan. */
    @Test
    void readableKeepsWhatWrapsTheRuleFailure() {
        String readable = SqlErrorClassifier.readable(
            "Cannot generate a valid execution plan for the given query: " + WINDOW_RULE_FAILURE);

        assertTrue(readable.startsWith("Cannot generate a valid execution plan"), readable);
        assertTrue(readable.contains("The window function requires the timecol"), readable);
        assertFalse(readable.contains("rel#"), readable);
    }

    // ── « cette colonne ne porte pas de watermark », dans les formulations de Flink ───

    @Test
    void aTimeAttributeComplaintIsRecognised() {
        assertTrue(SqlErrorClassifier.mentionsATimeAttribute(WINDOW_RULE_FAILURE));
        assertTrue(SqlErrorClassifier.mentionsATimeAttribute(
            "OVER windows' ordering in stream mode must be defined on a time attribute."));
        assertFalse(SqlErrorClassifier.mentionsATimeAttribute("Object 'orders' not found"));
        assertFalse(SqlErrorClassifier.mentionsATimeAttribute(null));
    }

    /**
     * Un OVER en temps événement sur une colonne ordinaire est une erreur de l'utilisateur.
     *
     * <p>Contrairement à une fenêtre TVF, que le lecteur direct sait vraiment calculer : lui, il
     * ignorerait l'OVER en silence et rendrait des lignes. C'est la substitution que ce
     * classifieur existe pour empêcher.
     */
    @Test
    void anOverWindowWithoutATimeAttributeIsAUserError() {
        assertTrue(SqlErrorClassifier.classify(
            "OVER windows' ordering in stream mode must be defined on a time attribute.").isUserError());
    }

    // ── isSyntaxError(): pre-flight validation must only surface parse failures ───────

    @Test
    void onlyParseFailuresCountAsSyntaxErrors() {
        assertTrue(SqlErrorClassifier.isSyntaxError(
            new RuntimeException("SQL parse failed. Encountered \"FORM\" at line 1, column 8.")));
        // Pre-flight validation runs before auto-registration, so an unresolved table there is
        // expected and must stay silent — otherwise every plain SELECT on a topic is rejected.
        assertFalse(SqlErrorClassifier.isSyntaxError(new RuntimeException("Object 'orders' not found")));
        assertFalse(SqlErrorClassifier.isSyntaxError(new NullPointerException()));
    }
}
