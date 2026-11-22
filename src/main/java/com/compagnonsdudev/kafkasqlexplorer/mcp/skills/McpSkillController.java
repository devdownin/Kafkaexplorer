// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.skills;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Operator-facing catalogue, backed by exactly the same immutable packaged skills as MCP.
 * No execution, installation, remote download or arbitrary file path is supported.
 */
@RestController
@RequestMapping("/api/mcp/skills")
@ConditionalOnProperty(prefix = "explorer.mcp.console", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public final class McpSkillController {
    private final ObjectProvider<McpSkillCatalog> catalogue;

    public McpSkillController(ObjectProvider<McpSkillCatalog> catalogue) {
        this.catalogue = catalogue;
    }

    @GetMapping
    public List<McpSkillCatalog.Summary> list() {
        McpSkillCatalog current = catalogue.getIfAvailable();
        return current == null ? List.of() : current.summaries();
    }

    @GetMapping("/{name}")
    public McpSkillCatalog.Detail read(@PathVariable String name) {
        McpSkillCatalog current = catalogue.getIfAvailable();
        if (current == null) throw new SkillNotFound();
        return current.find(name).orElseThrow(SkillNotFound::new);
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    private static final class SkillNotFound extends RuntimeException {}
}
