// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Set;

/**
 * Everything {@code explorer.mcp.*} controls.
 *
 * <p>Two defaults are the security posture and are not conveniences to be relaxed: {@code enabled}
 * is <b>false</b>, so an upgrade never turns an agent loose on a cluster that did not ask for one,
 * and {@code readonly} is <b>true</b>, so opting in still does not open the write surface. Both
 * have to be written to be had.
 *
 * <p>A mutable class rather than a record because Spring Boot's relaxed binding for a nested
 * {@code @ConfigurationProperties} tree is the boring, well-trodden path here, and the rest of
 * this application's configuration is written the same way ({@code ExplorerConfig}).
 */
@ConfigurationProperties(prefix = "explorer.mcp")
public class McpProperties {

    /** Wildcard accepted in the prefix lists, meaning "no restriction". */
    public static final String ANY = "*";

    private boolean enabled = false;
    private boolean readonly = true;

    private Tools tools = new Tools();
    private RateLimit rateLimit = new RateLimit();
    private Console console = new Console();
    private Dlp dlp = new Dlp();

    private List<String> allowedTopicPrefixes = List.of(ANY);
    private List<String> allowedGroupPrefixes = List.of(ANY);
    private Set<String> approvalRequiredTools =
            Set.of("kex_produce_message", "kex_set_cluster_target", "kex_create_metric");

    /**
     * Ceilings a tool cannot be argued out of. Hard rather than default because the caller here is
     * a model that will ask for a million rows to be safe, and the cost of honouring that lands on
     * the cluster the UI shares.
     */
    private int hardMaxRows = 1000;
    private int hardMaxRecords = 50;
    private int hardMaxOutputBytes = 1_048_576;
    private int hardMaxTopics = 200;
    private int hardMaxGroups = 50;
    private long defaultBudgetMs = 20_000L;
    private long hardMaxBudgetMs = 60_000L;

    private String auditTopic = "internal.mcp.audit";

    public static class Tools {
        /** {@code *} or a comma-separated list of tool names. */
        private String allowed = ANY;
        /** Always wins over {@link #allowed} — a deny-list that can be overridden is decoration. */
        private String denied = "";

        public String getAllowed() { return allowed; }
        public void setAllowed(String allowed) { this.allowed = allowed; }
        public String getDenied() { return denied; }
        public void setDenied(String denied) { this.denied = denied; }
    }

    /**
     * How many calls one identity may make. An operator clicks; a model loops — and a tool that
     * answers "not found in what was scanned" invites another pass, so a model with a budget and
     * no rate limit spends it on the cluster the UI shares.
     */
    public static class RateLimit {
        /** Zero or less turns the limiter off, as {@code explorer.max-concurrent-jobs} already reads. */
        private int callsPerMinute = 120;
        /**
         * How many calls may arrive at once before the sustained rate applies. An agent's opening
         * moves are a burst by nature — list, describe, infer — and a limiter with no burst turns
         * that ordinary sequence into a refusal.
         */
        private int burst = 20;

        public int getCallsPerMinute() { return callsPerMinute; }
        public void setCallsPerMinute(int callsPerMinute) { this.callsPerMinute = callsPerMinute; }
        public int getBurst() { return burst; }
        public void setBurst(int burst) { this.burst = burst; }
    }

    public static class Console {
        private boolean enabled = true;
        /**
         * How many calls the live feed keeps in memory. The ring is not a history store — the
         * audit topic is — and the console says so rather than truncating in silence.
         */
        private int ringBufferSize = 2000;
        private boolean allowRuntimeToggle = true;

        /**
         * Whether the console may run a tool.
         *
         * <p><b>False, and that is the security posture rather than caution.</b> "Try it" executes
         * the real tool through the real guard, which is what makes it useful — and it does so over
         * {@code POST /api/mcp/try/{tool}}, an application endpoint. This application ships with no
         * authentication (see {@code SECURITY.md}), while the specification puts OAuth 2.1 in front
         * of {@code /mcp}. An operator who wires that up in phase 5 would reasonably believe the
         * tool surface is closed, and this endpoint would be a complete bypass of it — same JVM,
         * same guard, no bearer token, and with {@code readonly=false} the mutating tools too.
         *
         * <p>The console is fully usable without it: the catalogue, the feed and the cards are all
         * reads. Turning this on is a deliberate act, and the refusal names the property so nobody
         * mistakes an off switch for a broken button.
         */
        private boolean allowTryIt = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getRingBufferSize() { return ringBufferSize; }
        public void setRingBufferSize(int ringBufferSize) { this.ringBufferSize = ringBufferSize; }
        public boolean isAllowRuntimeToggle() { return allowRuntimeToggle; }
        public void setAllowRuntimeToggle(boolean allowRuntimeToggle) { this.allowRuntimeToggle = allowRuntimeToggle; }
        public boolean isAllowTryIt() { return allowTryIt; }
        public void setAllowTryIt(boolean allowTryIt) { this.allowTryIt = allowTryIt; }
    }

    /**
     * There is deliberately no {@code scrub-all-outputs} flag beside {@link Dlp#mode}.
     *
     * <p>One shipped, read by nothing, and it could not have meant anything: redaction already
     * applies to every output whenever the mode is not {@code off}, so the flag's two values
     * described the same behaviour. A knob that cannot change what happens is worse than a missing
     * one — it invites an operator to believe they have narrowed something.
     */
    public static class Dlp {
        public enum Mode {
            /** Mask credentials and obvious personal data on the way out. */
            REDACT,
            /** Refuse to return a payload that carries them at all — {@code -32045}. */
            BLOCK,
            /** Return payloads untouched, for a cluster whose contents need no protection. */
            OFF
        }

        private Mode mode = Mode.REDACT;

        public Mode getMode() { return mode; }
        public void setMode(Mode mode) { this.mode = mode; }
    }

    /** True when the list places no restriction at all — empty, or containing the wildcard. */
    public static boolean unrestricted(List<String> prefixes) {
        return prefixes == null || prefixes.isEmpty() || prefixes.contains(ANY);
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isReadonly() { return readonly; }
    public void setReadonly(boolean readonly) { this.readonly = readonly; }
    public Tools getTools() { return tools; }
    public void setTools(Tools tools) { this.tools = tools; }
    public Console getConsole() { return console; }
    public void setConsole(Console console) { this.console = console; }
    public Dlp getDlp() { return dlp; }
    public void setDlp(Dlp dlp) { this.dlp = dlp; }
    public List<String> getAllowedTopicPrefixes() { return allowedTopicPrefixes; }
    public void setAllowedTopicPrefixes(List<String> allowedTopicPrefixes) { this.allowedTopicPrefixes = allowedTopicPrefixes; }
    public List<String> getAllowedGroupPrefixes() { return allowedGroupPrefixes; }
    public void setAllowedGroupPrefixes(List<String> allowedGroupPrefixes) { this.allowedGroupPrefixes = allowedGroupPrefixes; }
    public Set<String> getApprovalRequiredTools() { return approvalRequiredTools; }
    public void setApprovalRequiredTools(Set<String> approvalRequiredTools) { this.approvalRequiredTools = approvalRequiredTools; }
    public int getHardMaxRows() { return hardMaxRows; }
    public void setHardMaxRows(int hardMaxRows) { this.hardMaxRows = hardMaxRows; }
    public int getHardMaxRecords() { return hardMaxRecords; }
    public void setHardMaxRecords(int hardMaxRecords) { this.hardMaxRecords = hardMaxRecords; }
    public int getHardMaxOutputBytes() { return hardMaxOutputBytes; }
    public void setHardMaxOutputBytes(int hardMaxOutputBytes) { this.hardMaxOutputBytes = hardMaxOutputBytes; }
    public int getHardMaxTopics() { return hardMaxTopics; }
    public void setHardMaxTopics(int hardMaxTopics) { this.hardMaxTopics = hardMaxTopics; }
    public int getHardMaxGroups() { return hardMaxGroups; }
    public void setHardMaxGroups(int hardMaxGroups) { this.hardMaxGroups = hardMaxGroups; }
    public long getDefaultBudgetMs() { return defaultBudgetMs; }
    public void setDefaultBudgetMs(long defaultBudgetMs) { this.defaultBudgetMs = defaultBudgetMs; }
    public long getHardMaxBudgetMs() { return hardMaxBudgetMs; }
    public void setHardMaxBudgetMs(long hardMaxBudgetMs) { this.hardMaxBudgetMs = hardMaxBudgetMs; }
    public RateLimit getRateLimit() { return rateLimit; }
    public void setRateLimit(RateLimit rateLimit) { this.rateLimit = rateLimit; }

    public String getAuditTopic() { return auditTopic; }
    public void setAuditTopic(String auditTopic) { this.auditTopic = auditTopic; }
}
