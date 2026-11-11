// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.eval;

import com.compagnonsdudev.kafkasqlexplorer.service.AssistantSqlGate;
import com.compagnonsdudev.kafkasqlexplorer.service.FlinkSqlService;
import com.compagnonsdudev.kafkasqlexplorer.service.SqlErrorClassifier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The SQL assistant eval's dataset, held true without a model — this runs in {@code mvn verify}.
 *
 * <p>A wrong reference would score a right answer as wrong, and nothing in the model run could
 * tell the two apart. So every reference must pass the very gate a model's answer goes through,
 * run on the fixture, and return rows; every statement a {@code FIX} case hands the model must be
 * one the planner refuses <em>as the statement's fault</em>, since that is the only refusal the
 * assistant offers to fix; and the comparison rule is checked on itself.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssistantEvalDatasetTest {

    private AssistantEvalFixture fixture;
    private AssistantSqlGate gate;
    private List<AssistantEvalFixture.Case> cases;

    @BeforeAll
    void setUp() {
        fixture = AssistantEvalFixture.load();
        gate = new AssistantSqlGate(fixture.flinkSqlService, fixture.validator);
        cases = AssistantEvalFixture.cases();
    }

    @AfterAll
    void tearDown() {
        fixture.close();
    }

    @Test
    void theFixtureIsShapedAsTheApplicationRegistersIt() {
        assertEquals(8, fixture.tableNames.size());
        // The payload's own event_time wins: epoch seconds, no watermark — the case the product
        // really meets on the demo topics, and the reason the window questions are about buckets.
        assertEquals("BIGINT", type("demo_iot_sensors", "event_time"));
        assertTrue(type("demo_customers", "event_time").startsWith("TIMESTAMP(3) *ROWTIME*"),
            "a topic without event_time gets the generator's watermarked column: "
                + type("demo_customers", "event_time"));
        assertTrue(type("demo_orders_1_received", "proc_time").contains("PROCTIME"),
            type("demo_orders_1_received", "proc_time"));
        assertEquals("DOUBLE", type("demo_orders_1_received", "amount"));
    }

    private String type(String table, String column) {
        return fixture.flinkSqlService.getTableSchema(table).get(column);
    }

    @Test
    void theCasesAreWellFormed() {
        assertEquals(50, cases.size());
        Set<String> ids = new HashSet<>();
        for (AssistantEvalFixture.Case c : cases) {
            assertTrue(ids.add(c.id()), "duplicate id " + c.id());
            switch (c.kind()) {
                case "GENERATE" -> assertNotNull(c.question(), c.id());
                case "METRIC" -> {
                    assertNotNull(c.question(), c.id());
                    assertNotNull(c.topic(), c.id());
                }
                case "FIX" -> assertNotNull(c.broken(), c.id());
                default -> fail("unknown kind " + c.kind());
            }
        }
    }

    @Test
    void everyReferencePassesTheGateAndReturnsRows() throws Exception {
        List<String> failures = new ArrayList<>();
        for (AssistantEvalFixture.Case c : cases) {
            AssistantSqlGate.Result verdict = gate.check(c.reference(), fixture.tableNames, "METRIC".equals(c.kind()));
            if (!verdict.accepted()) {
                failures.add(c.id() + ": gate " + verdict.verdict() + " — " + verdict.message());
                continue;
            }
            try {
                List<List<String>> rows = fixture.execute(c.reference(), 120);
                if (rows.isEmpty()) failures.add(c.id() + ": the reference returns no row");
                if (!AssistantEvalFixture.answers(rows, rows)) failures.add(c.id() + ": does not answer itself");
            } catch (Exception e) {
                failures.add(c.id() + ": " + SqlErrorClassifier.explain(e));
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void everyBrokenStatementIsRefusedAsTheStatementsFault() {
        List<String> failures = new ArrayList<>();
        for (AssistantEvalFixture.Case c : cases) {
            if (!"FIX".equals(c.kind())) continue;
            FlinkSqlService.PlanCheck check = fixture.flinkSqlService.planCheck(c.broken());
            if (check.planned() || check.kind() != SqlErrorClassifier.Kind.USER_ERROR) {
                failures.add(c.id() + ": " + (check.planned() ? "planned" : check.kind() + " — " + check.message()));
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void theComparisonIgnoresNamesAndOrderButNotRowsOrRepresentation() {
        List<List<String>> reference = List.of(List.of("FOOD", "3"), List.of("FURNITURE", "3"));
        assertTrue(AssistantEvalFixture.answers(reference,
            List.of(List.of("3", "FURNITURE"), List.of("3", "FOOD"))), "column order does not count");
        assertTrue(AssistantEvalFixture.answers(reference,
            List.of(List.of("FOOD", "3", "x"), List.of("FURNITURE", "3", "y"))), "an extra column is allowed");
        assertFalse(AssistantEvalFixture.answers(reference, List.of(List.of("FOOD", "3"))), "a missing row is not");
        assertFalse(AssistantEvalFixture.answers(List.of(List.of("1767225600")),
            List.of(List.of("2026-01-01T00:00"))), "an epoch second is not a timestamp");
        assertEquals("2.27", AssistantEvalFixture.normalise(2.2666666));
        assertEquals("3", AssistantEvalFixture.normalise(3L));
        assertEquals("3", AssistantEvalFixture.normalise(3.0));
    }
}
