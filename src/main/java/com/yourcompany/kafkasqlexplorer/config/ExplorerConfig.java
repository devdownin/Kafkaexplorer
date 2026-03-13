package com.yourcompany.kafkasqlexplorer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "explorer")
public class ExplorerConfig {

    private String auditHistoryTopic = "internal.audit.history";
    private int defaultMaxRows = 50;
    private long defaultQueryTimeoutMs = 10000;
    private long auditQueryTimeoutMs = 5000;
    private int inferenceSampleSize = 10;
    private long inferencePollTimeoutMs = 2000;
    private boolean allowCrossJoin = false;
    private boolean allowSystemTableAccess = false;
    private int cacheExpireMinutes = 10;

    public String getAuditHistoryTopic() {
        return auditHistoryTopic;
    }

    public void setAuditHistoryTopic(String auditHistoryTopic) {
        this.auditHistoryTopic = auditHistoryTopic;
    }

    public int getDefaultMaxRows() {
        return defaultMaxRows;
    }

    public void setDefaultMaxRows(int defaultMaxRows) {
        this.defaultMaxRows = defaultMaxRows;
    }

    public long getDefaultQueryTimeoutMs() {
        return defaultQueryTimeoutMs;
    }

    public void setDefaultQueryTimeoutMs(long defaultQueryTimeoutMs) {
        this.defaultQueryTimeoutMs = defaultQueryTimeoutMs;
    }

    public long getAuditQueryTimeoutMs() {
        return auditQueryTimeoutMs;
    }

    public void setAuditQueryTimeoutMs(long auditQueryTimeoutMs) {
        this.auditQueryTimeoutMs = auditQueryTimeoutMs;
    }

    public int getInferenceSampleSize() {
        return inferenceSampleSize;
    }

    public void setInferenceSampleSize(int inferenceSampleSize) {
        this.inferenceSampleSize = inferenceSampleSize;
    }

    public long getInferencePollTimeoutMs() {
        return inferencePollTimeoutMs;
    }

    public void setInferencePollTimeoutMs(long inferencePollTimeoutMs) {
        this.inferencePollTimeoutMs = inferencePollTimeoutMs;
    }

    public boolean isAllowCrossJoin() {
        return allowCrossJoin;
    }

    public void setAllowCrossJoin(boolean allowCrossJoin) {
        this.allowCrossJoin = allowCrossJoin;
    }

    public boolean isAllowSystemTableAccess() {
        return allowSystemTableAccess;
    }

    public void setAllowSystemTableAccess(boolean allowSystemTableAccess) {
        this.allowSystemTableAccess = allowSystemTableAccess;
    }

    public int getCacheExpireMinutes() {
        return cacheExpireMinutes;
    }

    public void setCacheExpireMinutes(int cacheExpireMinutes) {
        this.cacheExpireMinutes = cacheExpireMinutes;
    }
}
