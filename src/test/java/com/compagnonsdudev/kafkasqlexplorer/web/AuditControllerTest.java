// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.web;

import com.compagnonsdudev.kafkasqlexplorer.domain.AuditReport;
import com.compagnonsdudev.kafkasqlexplorer.domain.AuditStatus;
import com.compagnonsdudev.kafkasqlexplorer.service.AuditDiffService;
import com.compagnonsdudev.kafkasqlexplorer.service.AuditHistoryService;
import com.compagnonsdudev.kafkasqlexplorer.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The audit endpoints, whose whole contract is in their status codes.
 *
 * <p>Every one of them answers a question the UI acts on differently, and telling them apart is
 * the point: a second run refused because one is in flight is <b>409 carrying that run's id</b>,
 * so the page attaches to it rather than reporting a failure; no run yet is <b>204</b>, not an
 * empty report; an unknown id is 404, not an empty one; and a comparison of two runs recorded
 * before graded severity is <b>409</b>, because the retired binary scale cannot say whether a
 * topic improved or regressed and answering anyway would be a guess dressed as a result.
 *
 * <p>Standalone MockMvc: nothing here needs a Spring context, and registering this controller
 * alone is what makes the last assertion mean something — {@code /audit} is a client-side route,
 * and {@code CLAUDE.md} carries a standing warning not to map a controller onto it. That warning
 * was enforced by nothing until now, and the same class of defect was live in two other
 * controllers when this file was written.
 */
class AuditControllerTest {

    private AuditService auditService;
    private AuditHistoryService auditHistoryService;
    private AuditDiffService auditDiffService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        auditService = Mockito.mock(AuditService.class);
        auditHistoryService = Mockito.mock(AuditHistoryService.class);
        auditDiffService = Mockito.mock(AuditDiffService.class);
        mockMvc = MockMvcBuilders
            .standaloneSetup(new AuditController(auditService, auditHistoryService, auditDiffService))
            .build();
    }

    private static AuditReport report(String id, AuditStatus status) {
        return new AuditReport(id, status, 12, 340, 1, 2, List.of(), List.of(), Map.of());
    }

    @Test
    void startingARunReturnsItsId() throws Exception {
        when(auditService.startAudit(any())).thenReturn(new AuditService.AuditStart("run-1", true));

        mockMvc.perform(post("/api/audit/start").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andExpect(content().string("run-1"));
    }

    /**
     * The executor is single-threaded, so accepting would queue a second full-cluster scan behind
     * the first. The id in the body is what lets the page attach to the run already going.
     */
    @Test
    void asecondRunIsRefusedWithTheIdOfTheOneInFlight() throws Exception {
        when(auditService.startAudit(any())).thenReturn(new AuditService.AuditStart("run-in-flight", false));

        mockMvc.perform(post("/api/audit/start").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isConflict())
            .andExpect(content().string("run-in-flight"));
    }

    /** The page posts no body when the operator kept every default. */
    @Test
    void startingWithNoBodyAtAllRunsEveryCheck() throws Exception {
        when(auditService.startAudit(any())).thenReturn(new AuditService.AuditStart("run-1", true));

        mockMvc.perform(post("/api/audit/start")).andExpect(status().isOk());
    }

    /**
     * A body that names only some of the checks. Jackson binds a record through its canonical
     * constructor, so before {@code AuditOptions} grew a boxed creator every absent flag failed the
     * whole request with a 400 naming a Jackson internal — on an endpoint that accepts <em>no</em>
     * body at all, which made refusing <code>{}</code> incoherent. An absent flag means the check
     * runs, so <code>{}</code> means exactly what sending nothing means.
     */
    @Test
    void aBodyThatNamesOnlySomeOfTheChecksStillBinds() throws Exception {
        when(auditService.startAudit(any())).thenReturn(new AuditService.AuditStart("run-1", true));

        mockMvc.perform(post("/api/audit/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"topicPrefix\":\"demo.orders.\",\"checkExactCount\":false}"))
            .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<com.compagnonsdudev.kafkasqlexplorer.domain.AuditOptions> captor =
            org.mockito.ArgumentCaptor.forClass(com.compagnonsdudev.kafkasqlexplorer.domain.AuditOptions.class);
        org.mockito.Mockito.verify(auditService).startAudit(captor.capture());
        var bound = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("demo.orders.", bound.topicPrefix());
        // Nommé faux : il l'est. Absents : ils tournent, comme sans corps du tout.
        org.junit.jupiter.api.Assertions.assertFalse(bound.checkExactCount());
        org.junit.jupiter.api.Assertions.assertTrue(bound.checkSchema());
        org.junit.jupiter.api.Assertions.assertTrue(bound.checkConsumerLag());
    }

    @Test
    void anUnknownRunIsNotFound() throws Exception {
        when(auditService.getAuditReport("nope")).thenReturn(null);

        mockMvc.perform(get("/api/audit/status/nope")).andExpect(status().isNotFound());
    }

    /**
     * 204, not an empty report: "no audit has ever run here" and "the last run found nothing" are
     * different answers, and the page renders a different screen for each.
     */
    @Test
    void noRunYetIsNoContentRatherThanAnEmptyReport() throws Exception {
        when(auditService.getLastAuditReport()).thenReturn(null);

        mockMvc.perform(get("/api/audit/last")).andExpect(status().isNoContent());
    }

    @Test
    void theLastRunComesBackWhenThereIsOne() throws Exception {
        when(auditService.getLastAuditReport()).thenReturn(report("run-1", AuditStatus.COMPLETED));

        mockMvc.perform(get("/api/audit/last"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("run-1")));
    }

    @Test
    void cancellingSaysWhichOfTheThreeThingsHappened() throws Exception {
        when(auditService.cancelAudit("run-1")).thenReturn(AuditService.CancelResult.CANCELLING);
        mockMvc.perform(post("/api/audit/run-1/cancel")).andExpect(status().isAccepted());

        when(auditService.cancelAudit("run-2")).thenReturn(AuditService.CancelResult.ALREADY_FINISHED);
        mockMvc.perform(post("/api/audit/run-2/cancel")).andExpect(status().isConflict());

        when(auditService.cancelAudit("run-3")).thenReturn(AuditService.CancelResult.NOT_FOUND);
        mockMvc.perform(post("/api/audit/run-3/cancel")).andExpect(status().isNotFound());
    }

    /**
     * A run recorded before graded severity carries the retired {@code UNHEALTHY} value, so no
     * mapping onto today's scale is anything but a guess. 409 says "this cannot be answered", where
     * 404 would say "these runs do not exist" — which is false and sends the reader looking.
     */
    @Test
    void comparingTwoLegacyRunsIsRefusedRatherThanGuessed() throws Exception {
        when(auditDiffService.compare(anyString(), anyString())).thenReturn(
            AuditDiffService.DiffResult.class.cast(legacy()));

        mockMvc.perform(get("/api/audit/compare").param("from", "old-1").param("to", "old-2"))
            .andExpect(status().isConflict())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("graded severity")));
    }

    @Test
    void comparingARunThatDoesNotExistIsNotFound() throws Exception {
        when(auditDiffService.compare(anyString(), anyString())).thenReturn(missing());

        mockMvc.perform(get("/api/audit/compare").param("from", "run-1").param("to", "nope"))
            .andExpect(status().isNotFound());
    }

    /**
     * {@code /audit} is a client-side route. A mapping here would shadow {@code SpaController} and
     * answer a page refresh with a circular-view-path 500 — there is no template engine. Asserted
     * with this controller registered alone, so the 404 means precisely "this controller does not
     * map it"; {@code SpaRoutingTest} asserts the other half, that the SPA does.
     */
    @Test
    void thereIsNoServerSideAuditPage() throws Exception {
        mockMvc.perform(get("/audit")).andExpect(status().isNotFound());
    }

    // ── Fixtures the service's package-private factories cannot build from here ──────────────

    private static AuditDiffService.DiffResult legacy() {
        return failure(AuditDiffService.DiffError.LEGACY_SHAPE,
            "One of the runs predates graded severity and cannot be compared.");
    }

    private static AuditDiffService.DiffResult missing() {
        return failure(AuditDiffService.DiffError.TO_NOT_FOUND, "No such run.");
    }

    private static AuditDiffService.DiffResult failure(AuditDiffService.DiffError error, String message) {
        return new AuditDiffService.DiffResult(null, error, message);
    }
}
