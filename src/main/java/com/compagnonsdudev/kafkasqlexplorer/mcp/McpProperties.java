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
    /** Mount the same writable directory on every instance to share lag baselines. */
    private String lagHistoryDirectory;
    private long lagHistoryTtlMs = 172_800_000L;
    private List<TopicPolicy> topicPolicies = List.of();
    private List<DlqRoute> dlqRoutes = List.of();

    /** Explicit operator requirements, selected by environment, never universal Kafka defaults. */
    public static class TopicPolicy {
        private String environment;
        private Integer minReplicas;
        private Integer minInSyncReplicas;
        private Long minRetentionMs;
        private Long maxRetentionMs;
        private String cleanupPolicy;
        public String getEnvironment() { return environment; }
        public void setEnvironment(String environment) { this.environment = environment; }
        public Integer getMinReplicas() { return minReplicas; }
        public void setMinReplicas(Integer minReplicas) { this.minReplicas = minReplicas; }
        public Integer getMinInSyncReplicas() { return minInSyncReplicas; }
        public void setMinInSyncReplicas(Integer minInSyncReplicas) { this.minInSyncReplicas = minInSyncReplicas; }
        public Long getMinRetentionMs() { return minRetentionMs; }
        public void setMinRetentionMs(Long minRetentionMs) { this.minRetentionMs = minRetentionMs; }
        public Long getMaxRetentionMs() { return maxRetentionMs; }
        public void setMaxRetentionMs(Long maxRetentionMs) { this.maxRetentionMs = maxRetentionMs; }
        public String getCleanupPolicy() { return cleanupPolicy; }
        public void setCleanupPolicy(String cleanupPolicy) { this.cleanupPolicy = cleanupPolicy; }
    }

    /** Links come from the operator; the tool never guesses a source or replay path from a name. */
    public static class DlqRoute {
        private String queueTopic;
        private String sourceTopic;
        private List<String> retryTopics = List.of();
        private String connectorName;
        private String monitoringReference;
        private String replayRunbook;
        public String getQueueTopic() { return queueTopic; }
        public void setQueueTopic(String queueTopic) { this.queueTopic = queueTopic; }
        public String getSourceTopic() { return sourceTopic; }
        public void setSourceTopic(String sourceTopic) { this.sourceTopic = sourceTopic; }
        public List<String> getRetryTopics() { return retryTopics; }
        public void setRetryTopics(List<String> retryTopics) { this.retryTopics = retryTopics; }
        public String getConnectorName() { return connectorName; }
        public void setConnectorName(String connectorName) { this.connectorName = connectorName; }
        public String getMonitoringReference() { return monitoringReference; }
        public void setMonitoringReference(String monitoringReference) { this.monitoringReference = monitoringReference; }
        public String getReplayRunbook() { return replayRunbook; }
        public void setReplayRunbook(String replayRunbook) { this.replayRunbook = replayRunbook; }
    }

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
    public String getLagHistoryDirectory() { return lagHistoryDirectory; }
    public void setLagHistoryDirectory(String lagHistoryDirectory) { this.lagHistoryDirectory = lagHistoryDirectory; }
    public long getLagHistoryTtlMs() { return lagHistoryTtlMs; }
    public void setLagHistoryTtlMs(long lagHistoryTtlMs) { this.lagHistoryTtlMs = lagHistoryTtlMs; }
    public List<TopicPolicy> getTopicPolicies() { return topicPolicies; }
    public void setTopicPolicies(List<TopicPolicy> topicPolicies) { this.topicPolicies = topicPolicies; }
    public List<DlqRoute> getDlqRoutes() { return dlqRoutes; }
    public void setDlqRoutes(List<DlqRoute> dlqRoutes) { this.dlqRoutes = dlqRoutes; }
}
