// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.guard;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which tools {@code explorer.mcp.tools.allowed} and {@code .denied} let through.
 *
 * <p><b>This is enforced by absence, not by refusal, and that distinction is the whole design.</b>
 * The two settings shipped for three phases accepted and acted on by nothing — the startup log said
 * so, which was better than silence but still left an operator able to write a deny-list and
 * believe it was in force. Making the check happen inside each call would have been the easy fix
 * and the wrong one: a denied tool would then be listed by {@code tools/list}, described to the
 * model, chosen by it, and refused — a round trip spent, and a surface that advertises what it will
 * not do.
 *
 * <p>So the specification list itself is filtered, in {@code McpToolSpecificationPostProcessor},
 * before the transport ever sees it. A denied tool is not in {@code tools/list} at all, exactly as
 * a mutating tool under {@code readonly} is not a bean. {@code McpCatalogService} is told anyway, so
 * the console can answer "why does my agent not see this?" with a row and a reason.
 *
 * <p><b>The deny-list always wins.</b> A deny that an allow-list can override is decoration: the
 * two settings are written by different people at different times — one in a base configuration,
 * one in an environment overlay — and the safe resolution of a disagreement between them is the
 * restrictive one.
 */
public class McpToolFilter {

    private final Set<String> allowed;
    private final Set<String> denied;
    private final boolean allowAll;

    public McpToolFilter(McpProperties properties) {
        this.allowed = names(properties.getTools().getAllowed());
        this.denied = names(properties.getTools().getDenied());
        this.allowAll = allowed.isEmpty() || allowed.contains(McpProperties.ANY);
    }

    /** True when this tool may be registered at all. */
    public boolean permits(String tool) {
        if (tool == null) {
            return false;
        }
        if (denied.contains(tool)) {
            return false;
        }
        return allowAll || allowed.contains(tool);
    }

    /**
     * Why a tool is not registered, for the console's row.
     *
     * <p>The two reasons are kept apart because they call for different edits: one setting names
     * this tool, the other fails to. An operator reading "excluded by allowed / denied" has to open
     * both to find out which.
     */
    public String refusalReason(String tool) {
        if (denied.contains(tool)) {
            return "named in explorer.mcp.tools.denied";
        }
        if (!allowAll && !allowed.contains(tool)) {
            return "not named in explorer.mcp.tools.allowed, which is a list rather than \"*\"";
        }
        return null;
    }

    /** True when neither setting restricts anything — the shipped default. */
    public boolean unrestricted() {
        return allowAll && denied.isEmpty();
    }

    /** Names in the deny-list that no registered tool carries — a typo that silences nothing. */
    public Set<String> deniedButUnknown(Set<String> registeredTools) {
        Set<String> unknown = new LinkedHashSet<>(denied);
        unknown.removeAll(registeredTools);
        return unknown;
    }

    /** Names in the allow-list that no registered tool carries. */
    public Set<String> allowedButUnknown(Set<String> registeredTools) {
        if (allowAll) {
            return Set.of();
        }
        Set<String> unknown = new LinkedHashSet<>(allowed);
        unknown.removeAll(registeredTools);
        return unknown;
    }

    private static Set<String> names(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(Arrays.stream(csv.split(",")).map(String::trim)
                .filter(name -> !name.isEmpty()).toList());
    }
}
