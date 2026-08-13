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
