// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading past a leading common table expression.
 *
 * <p>Every keyword check in the query path is a {@code startsWith}, so a statement opening with
 * {@code WITH} was refused outright — "Only SELECT, EXPLAIN and CREATE TABLE statements are
 * allowed" — although Flink SQL supports CTEs and they are ordinary SQL past three lines.
 *
 * <p>The behaviour that matters most here is the one on the *unrecognised* shapes: this helper
 * feeds a security guard, so anything it does not understand must come back unchanged and stay
 * refused. A guard that guesses when confused is worse than one that is narrow.
 */
class SqlStatementsTest {

    @Test
    void leavesAPlainStatementAlone() {
        assertEquals("SELECT * FROM orders", SqlStatements.withoutLeadingCte("SELECT * FROM orders"));
        assertEquals("CREATE TABLE t (a INT)", SqlStatements.withoutLeadingCte("  CREATE TABLE t (a INT)  "));
        assertFalse(SqlStatements.startsWithCte("SELECT 1"));
    }

    @Test
    void seesPastASingleCte() {
        String sql = "WITH recent AS (SELECT * FROM orders) SELECT * FROM recent";
        assertEquals("SELECT * FROM recent", SqlStatements.withoutLeadingCte(sql));
        assertTrue(SqlStatements.startsWithCte(sql));
    }

    @Test
    void seesPastSeveralChainedCtes() {
        String sql = "WITH a AS (SELECT 1), b AS (SELECT 2) SELECT * FROM a JOIN b ON true";
        assertEquals("SELECT * FROM a JOIN b ON true", SqlStatements.withoutLeadingCte(sql));
    }

    @Test
    void handlesNestedParenthesesInTheBody() {
        String sql = "WITH a AS (SELECT COALESCE(x, (SELECT MAX(y) FROM t)) FROM u) SELECT * FROM a";
        assertEquals("SELECT * FROM a", SqlStatements.withoutLeadingCte(sql));
    }

    @Test
    void isNotFooledByAParenthesisInsideAStringLiteral() {
        String sql = "WITH a AS (SELECT * FROM t WHERE label = 'oops )') SELECT * FROM a";
        assertEquals("SELECT * FROM a", SqlStatements.withoutLeadingCte(sql));
    }

    @Test
    void handlesAQuotedCteNameAndAColumnList() {
        String sql = "WITH `my cte` (a, b) AS (SELECT 1, 2) SELECT a FROM `my cte`";
        assertEquals("SELECT a FROM `my cte`", SqlStatements.withoutLeadingCte(sql));
    }

    @Test
    void isCaseInsensitive() {
        assertEquals("select * from a", SqlStatements.withoutLeadingCte("with a as (select 1) select * from a"));
    }

    /** A CTE wrapping an INSERT must still be classified as an INSERT, not smuggled through as a read. */
    @Test
    void classifiesWhatFollowsTheChain() {
        assertEquals("INSERT INTO sink SELECT * FROM a",
            SqlStatements.withoutLeadingCte("WITH a AS (SELECT 1) INSERT INTO sink SELECT * FROM a"));
        assertTrue(SqlStatements.classifiableBody("WITH a AS (SELECT 1) INSERT INTO sink SELECT * FROM a")
            .startsWith("INSERT INTO"));
    }

    @Test
    void failsClosedOnAnUnbalancedBody() {
        String sql = "WITH a AS (SELECT * FROM t SELECT * FROM a";
        assertEquals(sql, SqlStatements.withoutLeadingCte(sql));
        assertFalse(SqlStatements.startsWithCte(sql));
    }

    @Test
    void failsClosedWhenTheShapeIsNotACte() {
        // `WITH` is also an option clause keyword; anything not matching name-AS-(body) is left alone.
        for (String sql : new String[] { "WITH", "WITH (", "WITH a (", "WITH a AS SELECT 1", "WITH 42 AS (SELECT 1) SELECT 1" }) {
            assertEquals(sql, SqlStatements.withoutLeadingCte(sql), sql);
            assertFalse(SqlStatements.startsWithCte(sql), sql);
        }
    }

    @Test
    void toleratesNullAndBlank() {
        assertEquals("", SqlStatements.withoutLeadingCte(null));
        assertEquals("", SqlStatements.withoutLeadingCte("   "));
        assertFalse(SqlStatements.startsWithCte(null));
    }

    /** A word merely starting with "with" is not the keyword. */
    @Test
    void doesNotMatchAnIdentifierBeginningWithWith() {
        String sql = "SELECT withdrawal FROM t";
        assertEquals(sql, SqlStatements.withoutLeadingCte(sql));
    }
}
