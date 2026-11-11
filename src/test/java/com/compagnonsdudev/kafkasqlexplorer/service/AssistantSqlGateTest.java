// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The gate a statement the model wrote goes through before the page calls it accepted.
 *
 * <p>Each refusal has a verdict that decides what happens next, and the verdict is the contract:
 * {@code REPAIRABLE} goes back to the model with its message, {@code FORBIDDEN} is never shown,
 * {@code UNCHECKED} is shown as not validated and is not the model's fault.
 */
class AssistantSqlGateTest {

    private FlinkSqlService flink;
    private SqlQueryValidator validator;
    private AssistantSqlGate gate;

    @BeforeEach
    void setUp() {
        flink = mock(FlinkSqlService.class);
        validator = mock(SqlQueryValidator.class);
        when(flink.withTopicTableNames(anyString())).thenAnswer(i -> i.getArgument(0));
        when(flink.planCheck(anyString())).thenReturn(new FlinkSqlService.PlanCheck(true, null, null));
        gate = new AssistantSqlGate(flink, validator);
    }

    @Test
    void acceptsASelectOverADescribedTable() {
        AssistantSqlGate.Result result = gate.check("SELECT status FROM orders LIMIT 100;", List.of("orders"), false);

        assertTrue(result.accepted(), result.message());
        assertEquals("SELECT status FROM orders LIMIT 100", result.sql(), "the trailing semicolon is dropped");
    }

    @Test
    void unwrapsAMarkdownFence() {
        AssistantSqlGate.Result result = gate.check("```sql\nSELECT 1 FROM orders\n```", List.of("orders"), false);

        assertTrue(result.accepted(), result.message());
        assertEquals("SELECT 1 FROM orders", result.sql());
    }

    @Test
    void neverShowsAStatementThatIsNotARead() {
        for (String sql : List.of("INSERT INTO sink SELECT * FROM orders", "DROP TABLE orders",
                "CREATE TABLE x (a INT)")) {
            AssistantSqlGate.Result result = gate.check(sql, List.of("orders"), false);
            assertEquals(AssistantSqlGate.Verdict.FORBIDDEN, result.verdict(), sql);
        }
        verify(flink, never()).planCheck(anyString());
    }

    @Test
    void refusesSeveralStatementsButNotASemicolonInALiteral() {
        assertEquals(AssistantSqlGate.Verdict.REPAIRABLE,
            gate.check("SELECT 1 FROM orders; SELECT 2 FROM orders", List.of("orders"), false).verdict());
        assertTrue(gate.check("SELECT * FROM orders WHERE status = 'a;b'", List.of("orders"), false).accepted());
    }

    @Test
    void namesATableTheModelWasNotShown() {
        AssistantSqlGate.Result result =
            gate.check("SELECT o.id FROM orders o JOIN customers c ON o.cid = c.id", List.of("orders"), false);

        assertEquals(AssistantSqlGate.Verdict.REPAIRABLE, result.verdict());
        assertTrue(result.message().contains("`customers`"), result.message());
        assertTrue(result.message().contains("orders"), "the message lists what may be read: " + result.message());
        verify(flink, never()).planCheck(anyString());
    }

    @Test
    void aCteNameIsTheStatementsOwn() {
        AssistantSqlGate.Result result = gate.check(
            "WITH recent AS (SELECT * FROM orders) SELECT COUNT(*) FROM recent", List.of("orders"), false);

        assertTrue(result.accepted(), result.message());
    }

    @Test
    void theCrossJoinGuardSpeaksThroughTheGate() {
        doThrow(new IllegalArgumentException("Cross joins are not allowed in this environment."))
            .when(validator).validate(anyString());

        AssistantSqlGate.Result result = gate.check("SELECT * FROM orders", List.of("orders"), false);

        assertEquals(AssistantSqlGate.Verdict.REPAIRABLE, result.verdict());
        assertEquals("Cross joins are not allowed in this environment.", result.message());
    }

    @Test
    void aMetricNeedsItsAlias() {
        assertEquals(AssistantSqlGate.Verdict.REPAIRABLE,
            gate.check("SELECT COUNT(*) AS n FROM orders", List.of("orders"), true).verdict());
        assertTrue(gate.check("SELECT COUNT(*) AS metric_value FROM orders", List.of("orders"), true).accepted());
        assertEquals(AssistantSqlGate.Verdict.REPAIRABLE,
            gate.check("SELECT 'AS metric_value' AS n FROM orders", List.of("orders"), true).verdict(),
            "the alias written inside a string literal is not an alias");
    }

    @Test
    void thePlannersRefusalIsRepairableOnlyWhenItIsTheStatementsFault() {
        when(flink.planCheck(anyString())).thenReturn(new FlinkSqlService.PlanCheck(false,
            SqlErrorClassifier.Kind.USER_ERROR, "Column 'customer' not found"));
        AssistantSqlGate.Result user = gate.check("SELECT customer FROM orders", List.of("orders"), false);
        assertEquals(AssistantSqlGate.Verdict.REPAIRABLE, user.verdict());
        assertEquals("Column 'customer' not found", user.message());

        when(flink.planCheck(anyString())).thenReturn(new FlinkSqlService.PlanCheck(false,
            SqlErrorClassifier.Kind.ENGINE_BUSY, "The Flink runtime was busy"));
        AssistantSqlGate.Result busy = gate.check("SELECT status FROM orders", List.of("orders"), false);
        assertEquals(AssistantSqlGate.Verdict.UNCHECKED, busy.verdict());
        assertTrue(busy.message().startsWith("Not checked"), busy.message());
    }

    @Test
    void anEmptyAnswerIsRepairable() {
        assertEquals(AssistantSqlGate.Verdict.REPAIRABLE, gate.check("  ", List.of("orders"), false).verdict());
        assertEquals(AssistantSqlGate.Verdict.REPAIRABLE, gate.check(null, List.of("orders"), false).verdict());
    }
}
