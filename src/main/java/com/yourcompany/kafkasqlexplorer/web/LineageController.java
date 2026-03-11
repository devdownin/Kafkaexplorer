package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.service.LineageService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

@Controller
public class LineageController {

    private final LineageService lineageService;

    public LineageController(LineageService lineageService) {
        this.lineageService = lineageService;
    }

    @GetMapping("/lineage")
    public String lineage(Model model) {
        return "lineage";
    }

    @GetMapping(value = "/api/lineage", produces = "application/json")
    @ResponseBody
    public Map<String, Object> getLineage() {
        return lineageService.getLineage();
    }
}
