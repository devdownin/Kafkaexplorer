// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.domain.AuditOptions;
import com.yourcompany.kafkasqlexplorer.domain.AuditReport;
import com.yourcompany.kafkasqlexplorer.service.AuditService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/audit")
    public String audit(Model model) {
        AuditReport latest = auditService.getLastAuditReport();
        if (latest == null) {
            String id = auditService.startAudit(AuditOptions.all());
            model.addAttribute("auditId", id);
        } else {
            model.addAttribute("report", latest);
        }
        return "audit";
    }

    @PostMapping("/api/audit/start")
    @ResponseBody
    public String startAudit(@RequestBody(required = false) AuditOptions options) {
        return auditService.startAudit(options != null ? options : AuditOptions.all());
    }

    @GetMapping("/api/audit/status/{id}")
    @ResponseBody
    public AuditReport getAuditStatus(@PathVariable String id) {
        return auditService.getAuditReport(id);
    }
}
