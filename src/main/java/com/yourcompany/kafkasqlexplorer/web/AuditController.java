// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.domain.AuditOptions;
import com.yourcompany.kafkasqlexplorer.domain.AuditReport;
import com.yourcompany.kafkasqlexplorer.service.AuditService;
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

    @PostMapping("/start")
    public String startAudit(@RequestBody(required = false) AuditOptions options) {
        return auditService.startAudit(options != null ? options : AuditOptions.all());
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
     * Most recent report of this process, so reloading the Audit page restores the last run
     * instead of forcing a fresh full-cluster scan. 204 when no audit has run yet.
     */
    @GetMapping("/last")
    public ResponseEntity<AuditReport> getLastAudit() {
        AuditReport report = auditService.getLastAuditReport();
        return report != null ? ResponseEntity.ok(report) : ResponseEntity.noContent().build();
    }
}
