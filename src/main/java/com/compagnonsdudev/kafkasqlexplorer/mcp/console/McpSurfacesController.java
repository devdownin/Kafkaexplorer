// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.console;

import io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceTemplateSpecification;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The catalogue's resource/prompt side, introspected from Spring AI's actual registered
 * specifications. No parallel hard-coded inventory and no credentials in the response.
 * Unlike /mcp this application read endpoint is intentionally available while MCP is off,
 * when it returns an empty list.
 */
@RestController
@RequestMapping("/api/mcp/surfaces")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "explorer.mcp.console", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpSurfacesController {
    public record Surface(String kind, String name, String uri, String description) {}

    private final ObjectProvider<List<SyncResourceSpecification>> resources;
    private final ObjectProvider<List<SyncResourceTemplateSpecification>> templates;
    private final ObjectProvider<List<SyncPromptSpecification>> prompts;

    public McpSurfacesController(ObjectProvider<List<SyncResourceSpecification>> resources,
                                 ObjectProvider<List<SyncResourceTemplateSpecification>> templates,
                                 ObjectProvider<List<SyncPromptSpecification>> prompts) {
        this.resources = resources;
        this.templates = templates;
        this.prompts = prompts;
    }

    @GetMapping
    public List<Surface> list() {
        List<Surface> surfaces = new ArrayList<>();
        List<SyncResourceSpecification> r = resources.getIfAvailable();
        if (r != null) for (var spec : r) {
            var resource = spec.resource();
            surfaces.add(new Surface("RESOURCE", resource.name(), resource.uri(),
                    resource.description()));
        }
        List<SyncResourceTemplateSpecification> t = templates.getIfAvailable();
        if (t != null) for (var spec : t) {
            var resource = spec.resourceTemplate();
            surfaces.add(new Surface("RESOURCE_TEMPLATE", resource.name(),
                    resource.uriTemplate(), resource.description()));
        }
        List<SyncPromptSpecification> p = prompts.getIfAvailable();
        if (p != null) for (var spec : p) {
            var prompt = spec.prompt();
            surfaces.add(new Surface("PROMPT", prompt.name(), null, prompt.description()));
        }
        surfaces.sort(Comparator.comparing(Surface::kind).thenComparing(Surface::name));
        return List.copyOf(surfaces);
    }
}
