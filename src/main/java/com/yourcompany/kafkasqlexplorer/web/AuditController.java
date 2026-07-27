// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.domain.AuditOptions;
import com.yourcompany.kafkasqlexplorer.domain.AuditReport;
import com.yourcompany.kafkasqlexplorer.service.AuditService;
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

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
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
}
