package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.service.LineageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/lineage")
public class LineageRestController {

    private final LineageService lineageService;

    public LineageRestController(LineageService lineageService) {
        this.lineageService = lineageService;
    }

    @GetMapping
    public Map<String, Object> getLineage() {
        return lineageService.getLineage();
    }
}
