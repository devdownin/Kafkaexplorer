// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.guard;

import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The kill switch: what an operator can change without a restart, and what that costs.
 *
 * <p>An incident is the whole reason this exists. "An agent is hammering the cluster" and "this
 * tool is returning something it should not" are answered by a redeploy in minutes at best, and the
 * minutes are the problem. Every switch here takes effect on the next call.
 *
 * <p><b>Every override carries who set it, when, and why — and none of them expires.</b> That
 * pairing is deliberate and it is the mitigation for this feature's own worst failure mode: a
 * runtime derogation that outlives the incident it answered and becomes the permanent
 * configuration nobody remembers choosing. An expiry would be worse, not better — it would restore
 * a wider surface at an arbitrary moment, quietly. So they persist until lifted, and the console
 * carries a banner naming each live one with its author and its age, which is the pressure that
 * gets them lifted.
 *
 * <p><b>In memory, per process, and deliberately not persisted.</b> A kill switch has to take
 * effect now; one that has to be written somewhere durable first can fail to. A restart therefore
 * returns the deployment to its configured posture, which is the safe direction: the YAML is the
 * source of truth and an override is an exception to it, never a replacement.
 *
 * <p><b>Only one direction is allowed, and this is the rule that keeps the switch a safety
 * control.</b> Runtime {@code readonly} can be turned <em>on</em> when the configuration has it
 * off, never off when the configuration has it on — the write surface is decided at bean
 * registration, so a switch could not open it anyway, and offering a control that appears to open
 * it and does not is worse than not offering it. A tool can be disabled, never enabled past a
 * deny-list. Every switch narrows.
 */
public class McpRuntimeSwitches {

    /**
     * One live override.
     *
     * @param kind   READONLY / TOOL / QUARANTINE
     * @param target the tool or identity it applies to, {@code null} for the global read-only lock
     * @param actor  who set it — free text from the console, since this application authenticates
     *               nobody; recorded as claimed rather than as verified, and the console says so
     * @param reason why, in the operator's own words
     * @param since  when it was set
     */
    public record Override(Kind kind, String target, String actor, String reason, Instant since) {

        public enum Kind { READONLY, TOOL, QUARANTINE }

        /** How long this override has been in force — the number that makes a stale one visible. */
        public long ageMs() {
            return Math.max(0L, Instant.now().toEpochMilli() - since.toEpochMilli());
        }
    }

    private final McpProperties properties;

    private volatile Override readonlyLock;
    private final Map<String, Override> disabledTools = new ConcurrentHashMap<>();
    private final Map<String, Override> quarantined = new ConcurrentHashMap<>();

    public McpRuntimeSwitches(McpProperties properties) {
        this.properties = properties;
    }

    /** True when the runtime toggles are usable at all — {@code console.allow-runtime-toggle}. */
    public boolean togglesAllowed() {
        return properties.getConsole().isAllowRuntimeToggle();
    }

    /** Read-only as it actually applies now: the configured value, or the lock on top of it. */
    public boolean readonly() {
        return properties.isReadonly() || readonlyLock != null;
    }

    /**
     * Locks the surface read-only until an operator lifts it.
     *
     * <p>Idempotent on purpose: locking an already-locked surface keeps the <em>first</em> actor
     * and reason. During an incident the same switch gets thrown twice, and the second throw
     * overwriting the first's reason would lose the one sentence explaining why.
     */
    public Override lockReadonly(String actor, String reason) {
        requireToggles();
        Override existing = readonlyLock;
        if (existing != null) {
            return existing;
        }
        Override lock = new Override(Override.Kind.READONLY, null, actor, reason, Instant.now());
        readonlyLock = lock;
        return lock;
    }

    /**
     * Lifts the read-only lock.
     *
     * <p>It cannot open a surface the configuration keeps closed: {@code explorer.mcp.readonly=true}
     * withholds the mutating beans at registration, so lifting the lock there restores nothing and
     * must not read as though it had.
     */
    public boolean unlockReadonly() {
        requireToggles();
        boolean wasLocked = readonlyLock != null;
        readonlyLock = null;
        return wasLocked;
    }

    /** True when the configured posture is already read-only, so the lock changes nothing. */
    public boolean readonlyIsConfigured() {
        return properties.isReadonly();
    }

    public Override disableTool(String tool, String actor, String reason) {
        requireToggles();
        return disabledTools.computeIfAbsent(tool, name ->
                new Override(Override.Kind.TOOL, name, actor, reason, Instant.now()));
    }

    public boolean enableTool(String tool) {
        requireToggles();
        return disabledTools.remove(tool) != null;
    }

    /** The override disabling this tool, or empty. Read on every call, so it must stay cheap. */
    public Optional<Override> toolDisabled(String tool) {
        return Optional.ofNullable(disabledTools.get(tool));
    }

    public Override quarantine(String identity, String actor, String reason) {
        requireToggles();
        return quarantined.computeIfAbsent(identity, name ->
                new Override(Override.Kind.QUARANTINE, name, actor, reason, Instant.now()));
    }

    public boolean releaseQuarantine(String identity) {
        requireToggles();
        return quarantined.remove(identity) != null;
    }

    public Optional<Override> quarantineOf(String identity) {
        return identity == null ? Optional.empty() : Optional.ofNullable(quarantined.get(identity));
    }

    /**
     * Every live override, oldest first.
     *
     * <p>Oldest first rather than newest: the one that has been in force longest is the one most
     * likely to have outlived its incident, and it belongs at the top of the banner rather than
     * scrolled past.
     */
    public List<Override> active() {
        List<Override> all = new ArrayList<>();
        Override lock = readonlyLock;
        if (lock != null) {
            all.add(lock);
        }
        all.addAll(disabledTools.values());
        all.addAll(quarantined.values());
        all.sort(Comparator.comparing(Override::since));
        return List.copyOf(all);
    }

    private void requireToggles() {
        if (!togglesAllowed()) {
            throw new McpToolException(McpErrorCode.POLICY_DENIED, McpGuard.POLICY,
                    "the runtime switches are turned off on this deployment "
                            + "(explorer.mcp.console.allow-runtime-toggle=false). Change the "
                            + "configuration and restart, or turn the setting on.");
        }
    }
}
