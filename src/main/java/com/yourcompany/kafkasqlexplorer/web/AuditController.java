package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.domain.AuditReport;
import com.yourcompany.kafkasqlexplorer.service.AuditService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.concurrent.ExecutionException;

@Controller
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/audit")
    public String audit(Model model) {
        try {
            AuditReport report = auditService.generateAuditReport();
            model.addAttribute("report", report);
        } catch (Exception e) {
            model.addAttribute("error", "Failed to generate audit report: " + e.getMessage());
        }
        return "audit";
    }
}
