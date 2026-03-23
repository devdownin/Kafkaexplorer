// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.service.LineageService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
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
    public Map<String, Object> getLineage(
            @RequestParam(defaultValue = "false") boolean connectedOnly) {
        return lineageService.getLineage(connectedOnly);
    }

    @GetMapping(value = "/api/lineage/ddl/{name}", produces = "text/plain")
    @ResponseBody
    public String getDdl(@PathVariable String name) {
        return lineageService.getDdlForNode(name);
    }
}
