// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Set;

/** Everything {@code explorer.mcp.*} controls. */
@ConfigurationProperties(prefix = "explorer.mcp")
public class McpProperties {
    public static final String ANY = "*";
    private boolean enabled = false;
    private boolean readonly = true;
    private String authToken;
    /** Production HTTP MCP requires TLS; local development may explicitly opt out. */
    private boolean requireTls = true;
    private Tools tools = new Tools();
    private RateLimit rateLimit = new RateLimit();
    private Console console = new Console();
    private Dlp dlp = new Dlp();
    private List<String> allowedTopicPrefixes = List.of(ANY);
    private List<String> allowedGroupPrefixes = List.of(ANY);
    private Set<String> approvalRequiredTools = Set.of("kex_produce_message", "kex_set_cluster_target", "kex_create_metric");
    private int hardMaxRows = 1000;
    private int hardMaxRecords = 50;
    private int hardMaxOutputBytes = 1_048_576;
    private int hardMaxTopics = 200;
    private int hardMaxGroups = 50;
    private long defaultBudgetMs = 20_000L;
    private long hardMaxBudgetMs = 60_000L;
    private String auditTopic = "internal.mcp.audit";

    public static class Tools {
        private String allowed = ANY;
        private String denied = "";
        public String getAllowed() { return allowed; }
        public void setAllowed(String allowed) { this.allowed = allowed; }
        public String getDenied() { return denied; }
        public void setDenied(String denied) { this.denied = denied; }
    }
    public static class RateLimit {
        private int callsPerMinute = 120;
        private int burst = 20;
        public int getCallsPerMinute() { return callsPerMinute; }
        public void setCallsPerMinute(int callsPerMinute) { this.callsPerMinute = callsPerMinute; }
        public int getBurst() { return burst; }
        public void setBurst(int burst) { this.burst = burst; }
    }
    public static class Console {
        private boolean enabled = true;
        private int ringBufferSize = 2000;
        private boolean allowRuntimeToggle = true;
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
    public static class Dlp {
        public enum Mode { REDACT, BLOCK, OFF }
        private Mode mode = Mode.REDACT;
        public Mode getMode() { return mode; }
        public void setMode(Mode mode) { this.mode = mode; }
    }
    public static boolean unrestricted(List<String> prefixes) { return prefixes == null || prefixes.isEmpty() || prefixes.contains(ANY); }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isReadonly() { return readonly; }
    public void setReadonly(boolean readonly) { this.readonly = readonly; }
    public String getAuthToken() { return authToken; }
    public void setAuthToken(String authToken) { this.authToken = authToken; }
    public boolean isRequireTls() { return requireTls; }
    public void setRequireTls(boolean requireTls) { this.requireTls = requireTls; }
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
