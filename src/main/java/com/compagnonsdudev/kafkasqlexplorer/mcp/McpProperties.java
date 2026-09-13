// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;
import java.util.Set;
@ConfigurationProperties(prefix = "explorer.mcp")
public class McpProperties {
    public static final String ANY = "*";
    private boolean enabled = false;
    private boolean readonly = true;
    private String authToken;
    private boolean requireTls = true;
    private Tools tools = new Tools();
    private RateLimit rateLimit = new RateLimit();
    private Console console = new Console();
    private Dlp dlp = new Dlp();
    private List<String> allowedTopicPrefixes = List.of(ANY);
    private List<String> allowedGroupPrefixes = List.of(ANY);
    private Set<String> approvalRequiredTools = Set.of("kex_produce_message", "kex_set_cluster_target", "kex_create_metric");
    private int hardMaxRows = 1000, hardMaxRecords = 50, hardMaxOutputBytes = 1_048_576, hardMaxTopics = 200, hardMaxGroups = 50;
    private long defaultBudgetMs = 20_000L, hardMaxBudgetMs = 60_000L;
    private String auditTopic = "internal.mcp.audit";
    public static class Tools { private String allowed = ANY, denied = ""; public String getAllowed(){return allowed;} public void setAllowed(String v){allowed=v;} public String getDenied(){return denied;} public void setDenied(String v){denied=v;} }
    public static class RateLimit { private int callsPerMinute=120, burst=20; public int getCallsPerMinute(){return callsPerMinute;} public void setCallsPerMinute(int v){callsPerMinute=v;} public int getBurst(){return burst;} public void setBurst(int v){burst=v;} }
    public static class Console { private boolean enabled=true,allowRuntimeToggle=true,allowTryIt=false; private int ringBufferSize=2000; public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;} public int getRingBufferSize(){return ringBufferSize;} public void setRingBufferSize(int v){ringBufferSize=v;} public boolean isAllowRuntimeToggle(){return allowRuntimeToggle;} public void setAllowRuntimeToggle(boolean v){allowRuntimeToggle=v;} public boolean isAllowTryIt(){return allowTryIt;} public void setAllowTryIt(boolean v){allowTryIt=v;} }
    public static class Dlp { public enum Mode {REDACT,BLOCK,OFF} private Mode mode=Mode.REDACT; public Mode getMode(){return mode;} public void setMode(Mode v){mode=v;} }
    public static boolean unrestricted(List<String> prefixes){return prefixes==null||prefixes.isEmpty()||prefixes.contains(ANY);}
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;} public boolean isReadonly(){return readonly;} public void setReadonly(boolean v){readonly=v;} public String getAuthToken(){return authToken;} public void setAuthToken(String v){authToken=v;} public boolean isRequireTls(){return requireTls;} public void setRequireTls(boolean v){requireTls=v;} public Tools getTools(){return tools;} public void setTools(Tools v){tools=v;} public RateLimit getRateLimit(){return rateLimit;} public void setRateLimit(RateLimit v){rateLimit=v;} public Console getConsole(){return console;} public void setConsole(Console v){console=v;} public Dlp getDlp(){return dlp;} public void setDlp(Dlp v){dlp=v;} public List<String> getAllowedTopicPrefixes(){return allowedTopicPrefixes;} public void setAllowedTopicPrefixes(List<String> v){allowedTopicPrefixes=v;} public List<String> getAllowedGroupPrefixes(){return allowedGroupPrefixes;} public void setAllowedGroupPrefixes(List<String> v){allowedGroupPrefixes=v;} public Set<String> getApprovalRequiredTools(){return approvalRequiredTools;} public void setApprovalRequiredTools(Set<String> v){approvalRequiredTools=v;} public int getHardMaxRows(){return hardMaxRows;} public void setHardMaxRows(int v){hardMaxRows=v;} public int getHardMaxRecords(){return hardMaxRecords;} public void setHardMaxRecords(int v){hardMaxRecords=v;} public int getHardMaxOutputBytes(){return hardMaxOutputBytes;} public void setHardMaxOutputBytes(int v){hardMaxOutputBytes=v;} public int getHardMaxTopics(){return hardMaxTopics;} public void setHardMaxTopics(int v){hardMaxTopics=v;} public int getHardMaxGroups(){return hardMaxGroups;} public void setHardMaxGroups(int v){hardMaxGroups=v;} public long getDefaultBudgetMs(){return defaultBudgetMs;} public void setDefaultBudgetMs(long v){defaultBudgetMs=v;} public long getHardMaxBudgetMs(){return hardMaxBudgetMs;} public void setHardMaxBudgetMs(long v){hardMaxBudgetMs=v;} public String getAuditTopic(){return auditTopic;} public void setAuditTopic(String v){auditTopic=v;}
}
