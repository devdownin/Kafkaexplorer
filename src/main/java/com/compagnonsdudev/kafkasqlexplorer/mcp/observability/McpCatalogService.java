// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.observability;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.McpToolset;
import com.compagnonsdudev.kafkasqlexplorer.mcp.tools.MutatingMcpTools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * What this server offers an agent right now — <b>including what it does not</b>.
 *
 * <p>The catalogue is published once, from the same enumeration that decides registration, so the
 * console and the protocol cannot disagree about the surface. Hidden tools are kept with the
 * reason they are hidden, because the row is the answer to the only question an operator asks
 * about a tool their agent cannot see.
 */
public class McpCatalogService {

    private final McpProperties properties;

    /** Written once at startup, read on every console request; volatile is the whole synchronisation. */
    private volatile List<ToolDescriptor> descriptors = List.of();

    public McpCatalogService(McpProperties properties) {
        this.properties = properties;
    }

    /**
     * Records the full surface and which part of it was registered.
     *
     * <p>Both lists are taken rather than just the exposed one: the difference between them is the
     * catalogue's most useful column, and it cannot be reconstructed afterwards from the beans
     * that survived.
     */
    public void publish(List<? extends McpToolset> allToolsets, List<? extends McpToolset> exposedToolsets) {
        Set<McpToolset> exposed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        exposed.addAll(exposedToolsets);

        List<ToolDescriptor> published = new ArrayList<>();
        for (McpToolset toolset : allToolsets) {
            for (ToolDescriptor descriptor : ToolIntrospector.describe(toolset, properties)) {
                published.add(descriptor.withVisibility(visibilityOf(toolset, descriptor, exposed)));
            }
        }
        published.sort(Comparator.comparing(ToolDescriptor::category).thenComparing(ToolDescriptor::name));
        this.descriptors = List.copyOf(published);
    }

    private Visibility visibilityOf(McpToolset toolset, ToolDescriptor descriptor, Set<McpToolset> exposed) {
        if (!exposed.contains(toolset)) {
            return toolset instanceof MutatingMcpTools && properties.isReadonly()
                    ? Visibility.hiddenBy("read-only mode (explorer.mcp.readonly=true)")
                    : Visibility.hiddenBy("excluded by explorer.mcp.tools.allowed / .denied");
        }
        return properties.getApprovalRequiredTools().contains(descriptor.name())
                ? Visibility.exposedWithApproval()
                : Visibility.exposed();
    }

    /** The console reads this. Never a constant, never a copy kept in the frontend. */
    public List<ToolDescriptor> catalog() {
        return descriptors;
    }

    public List<ToolDescriptor> exposed() {
        return descriptors.stream().filter(d -> d.visibility().visibleToAgents()).toList();
    }

    /** True when at least one mutating tool is actually registered — the console's amber badge. */
    public boolean writeSurfaceOpen() {
        return descriptors.stream()
                .anyMatch(d -> d.category() == ToolCategory.WRITE && d.visibility().visibleToAgents());
    }
}
