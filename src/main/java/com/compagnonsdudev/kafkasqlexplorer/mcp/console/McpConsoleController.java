// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.console;

import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpApprovalStore;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpRuntimeSwitches;
import com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpToolException;
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
    private final ObjectProvider<McpRuntimeSwitches> switches;
    private final ObjectProvider<McpApprovalStore> approvals;
    private final ObjectProvider<McpAuditReplayService> replay;
    private final org.springframework.core.env.Environment environment;

    public McpConsoleController(ObjectProvider<McpConsoleService> console,
                                ObjectProvider<McpToolInvoker> invoker,
                                ObjectProvider<McpRuntimeSwitches> switches,
                                ObjectProvider<McpApprovalStore> approvals,
                                ObjectProvider<McpAuditReplayService> replay,
                                org.springframework.core.env.Environment environment) {
        this.console = console;
        this.invoker = invoker;
        this.switches = switches;
        this.approvals = approvals;
        this.replay = replay;
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
     * Replay from the append-only audit topic.
     *
     * <p>The live feed is a bounded ring and answers "what is happening"; this answers "what
     * happened". The window defaults to the last 24 hours, and the response says whether the scan
     * actually reached its start — an empty window the scan never reached is the opposite
     * conclusion from an empty window it did.
     */
    @GetMapping("/calls/replay")
    public ResponseEntity<McpAuditReplayService.Replay> replay(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to) {

        // The window is checked first, before whether this process has a trail to read: it is a
        // fault in the request either way, and answering "nothing was appended" to an impossible
        // window would let a caller read the wrong reason for the empty list.
        Instant end = parseInstant(to, Instant.now());
        Instant start = parseInstant(from, end.minus(DEFAULT_WINDOW));
        if (!start.isBefore(end)) {
            return ResponseEntity.badRequest().body(new McpAuditReplayService.Replay(
                    List.of(), 0, false, true,
                    List.of("`from` must be before `to`; nothing can have happened in a window that "
                            + "ends before it starts.")));
        }

        McpAuditReplayService service = replay.getIfAvailable();
        if (service == null) {
            return ResponseEntity.ok(new McpAuditReplayService.Replay(List.of(), 0, false, false,
                    List.of("The MCP server is disabled (explorer.mcp.enabled=false), so nothing "
                            + "has been appended to the audit topic by this process.")));
        }
        return ResponseEntity.ok(service.replay(start, end));
    }

    /**
     * The kill switch, and the one direction it turns.
     *
     * <p>Every switch here narrows. Read-only can be turned on when the configuration has it off,
     * never off when the configuration has it on: the write surface is decided at bean
     * registration, so a toggle could not open it, and a control that appears to and does not is
     * worse than none. The console sends who is throwing the switch and why — as claimed, not as
     * verified, since this application authenticates nobody — and the banner names both, which is
     * the pressure that gets a derogation lifted once its incident is over.
     */
    @PostMapping("/toggle/readonly")
    public ResponseEntity<McpSwitchResult> toggleReadonly(@RequestBody McpSwitchRequest request) {
        return withSwitches(runtime -> {
            if (request.enable()) {
                if (runtime.readonlyIsConfigured()) {
                    return McpSwitchResult.noop("this deployment is already read-only by "
                            + "configuration (explorer.mcp.readonly=true); there is nothing to lock");
                }
                McpRuntimeSwitches.Override lock =
                        runtime.lockReadonly(request.actorOrAnonymous(), request.reasonOrNone());
                return McpSwitchResult.applied("the MCP surface is read-only until this is lifted",
                        lock);
            }
            boolean lifted = runtime.unlockReadonly();
            return lifted
                    ? McpSwitchResult.applied("the read-only lock is lifted; the configured posture "
                            + "applies again", null)
                    : McpSwitchResult.noop(runtime.readonlyIsConfigured()
                            ? "the surface stays read-only: that is the configured posture "
                                    + "(explorer.mcp.readonly=true), which no runtime switch can open"
                            : "no read-only lock was in force");
        });
    }

    @PostMapping("/toggle/tool/{name}")
    public ResponseEntity<McpSwitchResult> toggleTool(@PathVariable("name") String name,
                                                      @RequestBody McpSwitchRequest request) {
        return withSwitches(runtime -> {
            if (request.enable()) {
                return runtime.enableTool(name)
                        ? McpSwitchResult.applied(name + " is callable again", null)
                        : McpSwitchResult.noop(name + " was not switched off");
            }
            McpRuntimeSwitches.Override off =
                    runtime.disableTool(name, request.actorOrAnonymous(), request.reasonOrNone());
            // Still listed, deliberately: a client caches tools/list from its initialize, so a tool
            // that vanished mid-session is one the model keeps calling with nothing to read.
            return McpSwitchResult.applied(name + " now refuses every call with -32044. It stays in "
                    + "tools/list, because a client caches that list and a tool that vanished "
                    + "mid-session could not tell the model why.", off);
        });
    }

    @PostMapping("/quarantine/{identity}")
    public ResponseEntity<McpSwitchResult> quarantine(@PathVariable("identity") String identity,
                                                      @RequestBody McpSwitchRequest request) {
        return withSwitches(runtime -> {
            if (request.enable()) {
                McpRuntimeSwitches.Override held =
                        runtime.quarantine(identity, request.actorOrAnonymous(), request.reasonOrNone());
                return McpSwitchResult.applied(identity + " is refused with -32047 on every call", held);
            }
            return runtime.releaseQuarantine(identity)
                    ? McpSwitchResult.applied(identity + " may call again", null)
                    : McpSwitchResult.noop(identity + " was not quarantined");
        });
    }

    /**
     * Mints an approval token for one call of one tool.
     *
     * <p>Behind {@code allow-runtime-toggle} like the switches, and for the same reason: on a
     * deployment where this application is reachable by more people than may approve, an open
     * minting endpoint is the approval control defeating itself.
     */
    @PostMapping("/approve/{tool}")
    public ResponseEntity<McpApprovalResult> approve(@PathVariable("tool") String tool,
                                                     @RequestBody McpSwitchRequest request) {
        McpApprovalStore store = approvals.getIfAvailable();
        McpRuntimeSwitches runtime = switches.getIfAvailable();
        if (store == null || runtime == null) {
            return ResponseEntity.ok(McpApprovalResult.refused(
                    "the MCP server is disabled (explorer.mcp.enabled=false)"));
        }
        if (!runtime.togglesAllowed()) {
            return ResponseEntity.status(403).body(McpApprovalResult.refused(
                    "minting approvals from the console is off "
                            + "(explorer.mcp.console.allow-runtime-toggle=false)"));
        }
        if (!store.requiresApproval(tool)) {
            return ResponseEntity.ok(McpApprovalResult.refused(tool + " does not require approval: "
                    + "it is not named in explorer.mcp.approval-required-tools, so a token would "
                    + "grant nothing that is not already allowed"));
        }
        return ResponseEntity.ok(McpApprovalResult.minted(
                store.mint(tool, request.actorOrAnonymous()), tool,
                McpApprovalStore.TTL.toMinutes()));
    }

    /** Every live override, so the console can keep a banner up while any of them is in force. */
    @GetMapping("/overrides")
    public List<McpOverrideView> overrides() {
        McpRuntimeSwitches runtime = switches.getIfAvailable();
        return runtime == null ? List.of()
                : runtime.active().stream().map(McpOverrideView::of).toList();
    }

    private ResponseEntity<McpSwitchResult> withSwitches(
            java.util.function.Function<McpRuntimeSwitches, McpSwitchResult> action) {
        McpRuntimeSwitches runtime = switches.getIfAvailable();
        if (runtime == null) {
            return ResponseEntity.ok(McpSwitchResult.noop(
                    "the MCP server is disabled (explorer.mcp.enabled=false), so there is nothing "
                            + "to switch"));
        }
        try {
            return ResponseEntity.ok(action.apply(runtime));
        } catch (McpToolException e) {
            // The only thing thrown here is the toggles-are-off refusal, which is a 403 rather
            // than a 500: the caller did nothing wrong, the deployment says no.
            return ResponseEntity.status(403).body(McpSwitchResult.noop(e.getMessage()));
        }
    }

    /** An ISO-8601 instant, or the fallback. A malformed one falls back rather than 400s. */
    private static Instant parseInstant(String value, Instant fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(value.trim());
        } catch (java.time.format.DateTimeParseException e) {
            return fallback;
        }
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
