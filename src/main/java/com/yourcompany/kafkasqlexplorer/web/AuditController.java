package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.domain.AuditReport;
import com.yourcompany.kafkasqlexplorer.service.AuditService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.concurrent.ExecutionException;

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
            String id = auditService.startAudit();
            model.addAttribute("auditId", id);
        } else {
            model.addAttribute("report", latest);
        }
        return "audit";
    }

    @PostMapping("/api/audit/start")
    @ResponseBody
    public String startAudit() {
        return auditService.startAudit();
    }

    @GetMapping("/api/audit/status/{id}")
    @ResponseBody
    public AuditReport getAuditStatus(@PathVariable String id) {
        return auditService.getAuditReport(id);
    }
        try {
            AuditReport report = auditService.generateAuditReport();
            model.addAttribute("report", report);
        } catch (Exception e) {
            model.addAttribute("error", "Failed to generate audit report: " + e.getMessage());
        }
        return "audit";
    }
}
