// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.console;

import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpCallFilter;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpCallRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * What the MCP screen reads. Application endpoints, not MCP ones — they answer to this
 * application's own access control, never to the agent's.
 *
 * <p><b>It exists even when the MCP server is off</b>, and that is deliberate. The whole module is
 * conditional on {@code explorer.mcp.enabled}, so with the server disabled there is no catalogue
 * and no recorder — but a screen that gets a 404 cannot tell "the server is off" from "this build
 * is too old" from "the endpoint moved". {@code /api/mcp/status} therefore always answers, saying
 * {@code enabled: false}, and the other endpoints return empty rather than failing. An empty state
 * that explains itself is the point of the screen; an error page is the opposite of it.
 */
@RestController
@RequestMapping("/api/mcp")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "explorer.mcp.console", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpConsoleController {

    /** The console's default window, and the one the head cards are labelled with. */
    private static final Duration DEFAULT_WINDOW = Duration.ofHours(24);

    /**
     * Absent whenever {@code explorer.mcp.enabled} is false — which is the shipped default, so this
     * is the ordinary case rather than a degraded one.
     */
    private final ObjectProvider<McpConsoleService> console;
    private final ObjectProvider<McpToolInvoker> invoker;
    private final org.springframework.core.env.Environment environment;

    public McpConsoleController(ObjectProvider<McpConsoleService> console,
                                ObjectProvider<McpToolInvoker> invoker,
                                org.springframework.core.env.Environment environment) {
        this.console = console;
        this.invoker = invoker;
        this.environment = environment;
    }

    @GetMapping("/status")
    public McpStatusView status() {
        McpConsoleService service = console.getIfAvailable();
        return service == null ? McpStatusView.disabled() : service.status();
    }

    @GetMapping("/catalog")
    public McpCatalogView catalog(@RequestParam(value = "window", required = false) String window) {
        McpConsoleService service = console.getIfAvailable();
        return service == null
                ? new McpCatalogView(List.of(), offline(window))
                : service.catalog(parseWindow(window));
    }

    @GetMapping("/catalog/client-config")
    public McpClientConfig clientConfig(@RequestParam(value = "client", required = false) String client) {
        McpConsoleService service = console.getIfAvailable();
        return McpClientConfigFactory.forClient(client,
                service == null ? null : service.status().endpoint());
    }

    @GetMapping("/calls")
    public List<McpCallView> calls(
            @RequestParam(value = "since", required = false) String since,
            @RequestParam(value = "tool", required = false) String tool,
            @RequestParam(value = "outcome", required = false) String outcome,
            @RequestParam(value = "identity", required = false) String identity,
            @RequestParam(value = "origin", required = false) String origin,
            @RequestParam(value = "limit", required = false) Integer limit) {

        McpConsoleService service = console.getIfAvailable();
        if (service == null) {
            return List.of();
        }
        McpCallFilter filter = new McpCallFilter(
                parseInstant(since),
                blankToNull(tool),
                parseEnum(outcome, McpCallRecord.Outcome.class),
                blankToNull(identity),
                parseEnum(origin, McpCallRecord.Origin.class));
        // Capped rather than unbounded: the feed polls, and a caller asking for everything would
        // serialise the whole ring every five seconds.
        return service.calls(filter, limit == null ? 200 : Math.max(1, Math.min(limit, 2000)));
    }

    @GetMapping("/stats")
    public McpStatsView stats(@RequestParam(value = "window", required = false) String window) {
        McpConsoleService service = console.getIfAvailable();
        return service == null
                ? emptyStats(offline(window))
                : service.stats(parseWindow(window));
    }

    @GetMapping("/clients")
    public List<McpClientRow> clients(@RequestParam(value = "window", required = false) String window) {
        McpConsoleService service = console.getIfAvailable();
        return service == null ? List.of() : service.clients(parseWindow(window));
    }

    /**
     * Runs one tool from the console, down the same path an agent's call takes.
     *
     * <p>Refused when {@code explorer.mcp.console.allow-try-it} is false, and the refusal names the
     * setting: an operator who cannot find the button needs to know it was turned off, not wonder
     * whether the page is broken.
     */
    @PostMapping("/try/{tool}")
    public ResponseEntity<McpTryResult> tryTool(@PathVariable("tool") String tool,
                                                @RequestBody(required = false) java.util.Map<String, Object> arguments) {
        // Read from the Environment rather than McpProperties because that bean does not exist
        // when the module is off — and the default here must match McpProperties.Console, which is
        // false. Defaulting to true would open the bypass this setting exists to keep shut,
        // silently, for any deployment that never wrote the property.
        if (!environment.getProperty("explorer.mcp.console.allow-try-it", Boolean.class, false)) {
            return ResponseEntity.status(403).body(McpTryResult.notInvocable(tool,
                    "running a tool from the console is off by default: this endpoint executes the "
                            + "real tool over an application URL that carries no authentication, so "
                            + "leaving it open would bypass whatever guards the MCP endpoint itself. "
                            + "Set explorer.mcp.console.allow-try-it=true once the application is "
                            + "reachable only by people who may run these tools."));
        }
        McpToolInvoker tools = invoker.getIfAvailable();
        if (tools == null) {
            return ResponseEntity.ok(McpTryResult.notInvocable(tool,
                    "the MCP server is disabled (explorer.mcp.enabled=false), so no tool is "
                            + "registered to run"));
        }
        return ResponseEntity.ok(tools.invoke(tool, arguments == null ? java.util.Map.of() : arguments));
    }

    /**
     * Replay from the append-only audit topic — phase 5.
     *
     * <p>Answered with 501 and a sentence rather than left unmapped. The console offers this button
     * the moment the live ring has evicted anything, so an operator who presses it has to learn
     * that the history is not kept yet — a 404 would read as a broken page and send them looking
     * for a bug instead of at the setting.
     */
    @GetMapping("/calls/replay")
    public ResponseEntity<String> replay() {
        return ResponseEntity.status(501).body(
                "Replay is not implemented yet (phase 5). Nothing is written to "
                        + "explorer.mcp.audit-topic, so there is no history to replay: what the "
                        + "live feed holds is all there is.");
    }

    /** The window a console request covers, defaulting to 24 h and refusing nothing. */
    static Duration parseWindow(String window) {
        if (window == null || window.isBlank()) {
            return DEFAULT_WINDOW;
        }
        String value = window.trim().toLowerCase(Locale.ROOT);
        try {
            if (value.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(value.substring(0, value.length() - 1)));
            }
            if (value.endsWith("d")) {
                return Duration.ofDays(Long.parseLong(value.substring(0, value.length() - 1)));
            }
            if (value.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(value.substring(0, value.length() - 1)));
            }
        } catch (NumberFormatException e) {
            return DEFAULT_WINDOW;
        }
        // An unparseable window falls back rather than 400s: the value comes from a URL an operator
        // may have edited by hand, and the response says what was actually covered anyway.
        return DEFAULT_WINDOW;
    }

    private static ObservedWindow offline(String window) {
        return new ObservedWindow(parseWindow(window).toMillis(), null, 0, 0, 0L, false);
    }

    private static McpStatsView emptyStats(ObservedWindow window) {
        String why = "the MCP server is disabled (explorer.mcp.enabled=false)";
        return new McpStatsView(0, 0, 0,
                com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Measured.unmeasured(why),
                com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Measured.unmeasured(why),
                com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Measured.unmeasured(why),
                null,
                com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Measured.unmeasured(why),
                com.compagnonsdudev.kafkasqlexplorer.mcp.contract.Measured.unmeasured(why),
                0, List.of(), List.of(), window);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static <E extends Enum<E>> E parseEnum(String value, Class<E> type) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
