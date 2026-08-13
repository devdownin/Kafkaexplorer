// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.compagnonsdudev.kafkasqlexplorer.domain.AuditDiff;
import com.compagnonsdudev.kafkasqlexplorer.domain.AuditHistory;
import com.compagnonsdudev.kafkasqlexplorer.domain.AuditOptions;
import com.compagnonsdudev.kafkasqlexplorer.domain.AuditReport;
import com.compagnonsdudev.kafkasqlexplorer.service.AuditDiffService;
import com.compagnonsdudev.kafkasqlexplorer.service.AuditHistoryService;
import com.compagnonsdudev.kafkasqlexplorer.service.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Audit API. There is deliberately no {@code GET /audit} handler here: {@code /audit} is a
 * client-side route owned by the SPA, and a controller mapping on it shadowed
 * {@code SpaController}'s catch-all — a page refresh or a bookmarked link returned a
 * "circular view path" 500 (there is no server-side template engine) and, as a side effect,
 * kicked off a full cluster scan from a GET.
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService auditService;
    private final AuditHistoryService auditHistoryService;
    private final AuditDiffService auditDiffService;

    public AuditController(AuditService auditService,
                           AuditHistoryService auditHistoryService,
                           AuditDiffService auditDiffService) {
        this.auditService = auditService;
        this.auditHistoryService = auditHistoryService;
        this.auditDiffService = auditDiffService;
    }

    /**
     * Starts a run and returns its id. When one is already in flight, answers 409 with **that**
     * run's id so the caller attaches to it: the audit executor is single-threaded, so accepting
     * the request would only queue another full cluster scan behind the current one.
     */
    @PostMapping("/start")
    public ResponseEntity<String> startAudit(@RequestBody(required = false) AuditOptions options) {
        AuditService.AuditStart start = auditService.startAudit(options != null ? options : AuditOptions.all());
        return start.started()
            ? ResponseEntity.ok(start.auditId())
            : ResponseEntity.status(HttpStatus.CONFLICT).body(start.auditId());
    }

    /**
     * 404 for an unknown id rather than an empty 200 body: the UI polls this, and a blank
     * response deserialized to "not RUNNING", so it stopped polling and showed nothing.
     */
    @GetMapping("/status/{id}")
    public ResponseEntity<AuditReport> getAuditStatus(@PathVariable String id) {
        AuditReport report = auditService.getAuditReport(id);
        return report != null ? ResponseEntity.ok(report) : ResponseEntity.notFound().build();
    }

    /**
     * Asks the run to stop. 404 for an unknown id, 409 when it already finished — cancelling a
     * report that is on screen and complete is a no-op the caller should hear about, not a
     * silent success. Cancellation is cooperative: 202 means "asked", not "stopped".
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<String> cancelAudit(@PathVariable String id) {
        return switch (auditService.cancelAudit(id)) {
            case CANCELLING -> ResponseEntity.accepted().body(id);
            case ALREADY_FINISHED -> ResponseEntity.status(HttpStatus.CONFLICT).body("Audit already finished");
            case NOT_FOUND -> ResponseEntity.notFound().build();
        };
    }

    /**
     * Most recent report of this process, so reloading the Audit page restores the last run
     * instead of forcing a fresh full-cluster scan. 204 when no audit has run yet.
     */
    @GetMapping("/last")
    public ResponseEntity<AuditReport> getLastAudit() {
        AuditReport report = auditService.getLastAuditReport();
        return report != null ? ResponseEntity.ok(report) : ResponseEntity.noContent().build();
    }

    /**
     * Past runs read back from {@code internal.audit.history}, newest first. Unlike {@code /last}
     * this survives a restart. The response carries the scan bounds — the read is capped, so
     * "these are the runs" would otherwise be a claim it cannot make.
     */
    @GetMapping("/history")
    public AuditHistory getHistory() {
        return auditHistoryService.listHistory();
    }

    /**
     * The stored report for one past run, as recorded. Returned as raw JSON rather than an
     * {@code AuditReport}: records written before graded severity have a shape the current type
     * cannot deserialize, and handing back what was stored beats failing or guessing.
     */
    @GetMapping("/history/{id}")
    public ResponseEntity<JsonNode> getHistoricalReport(@PathVariable String id) {
        JsonNode report = auditHistoryService.findReport(id);
        return report != null ? ResponseEntity.ok(report) : ResponseEntity.notFound().build();
    }

    /**
     * Topic-by-topic comparison of two runs. 404 when either is out of reach, 409 when one of them
     * predates graded severity — the retired binary scale cannot say whether a topic improved or
     * regressed, and answering anyway would be a guess dressed as a result.
     */
    @GetMapping("/compare")
    public ResponseEntity<?> compare(@RequestParam String from, @RequestParam String to) {
        AuditDiffService.DiffResult result = auditDiffService.compare(from, to);
        if (result.diff() != null) return ResponseEntity.ok(result.diff());
        HttpStatus status = result.error() == AuditDiffService.DiffError.LEGACY_SHAPE
            ? HttpStatus.CONFLICT
            : HttpStatus.NOT_FOUND;
        return ResponseEntity.status(status).body(result.message());
    }
}
