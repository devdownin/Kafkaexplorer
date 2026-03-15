package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.domain.AuditReport;
import com.yourcompany.kafkasqlexplorer.service.AuditService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/latest")
    public AuditReport getLatestAudit() {
        return auditService.getLastAuditReport();
    }

    @PostMapping("/start")
    public String startAudit() {
        return auditService.startAudit();
    }

    @GetMapping("/status/{id}")
    public AuditReport getAuditStatus(@PathVariable String id) {
        return auditService.getAuditReport(id);
    }
}
