// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.service.LineageService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

/**
 * REST endpoints for the lineage graph.
 *
 * <p>Note: there is deliberately no {@code GET /lineage} handler here. That path is a
 * client-side route owned by the React router; {@link SpaController} forwards it to
 * {@code index.html}. A literal {@code @GetMapping("/lineage")} would take precedence over
 * SpaController's pattern and — with no template engine on the classpath — resolve the view
 * name back to {@code /lineage}, which Spring rejects as a circular view path.
 */
@Controller
public class LineageController {

    private final LineageService lineageService;

    public LineageController(LineageService lineageService) {
        this.lineageService = lineageService;
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
