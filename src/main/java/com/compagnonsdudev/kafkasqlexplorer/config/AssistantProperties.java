// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything {@code explorer.assistant.*} controls — the SQL and metric assistant of the editor
 * and the Metrics page. {@code EMBEDDED-LLM-SCOPE.md} carries the design and the reasons.
 *
 * <p><b>Off by default</b>, which is the posture rather than a convenience: the shipped
 * {@code claude.provider} is a hosted gateway, so turning the assistant on by default would send
 * topic and column names to a third party nobody chose. The local stack
 * ({@code compose/ollama.yml}) turns it on, because there nothing leaves the host.
 *
 * <p>The three endpoint fields are <b>overrides</b>: blank means "the same as {@code claude.*}",
 * so a deployment with one model has nothing more to set, and one that wants a small fast model
 * here and a larger one for Process Mining names it.
 */
@ConfigurationProperties(prefix = "explorer.assistant")
public class AssistantProperties {

    private boolean enabled = false;
    /** Blank inherits {@code claude.provider}. */
    private String provider = "";
    /** Blank inherits {@code claude.base-url}. */
    private String baseUrl = "";
    /** Blank inherits {@code claude.model}. */
    private String model = "";
    /**
     * 0 (the default) inherits {@code claude.request-timeout-seconds}, so one setting governs how
     * long any call to the model may take — the Ollama stack's 300 s reaches both consumers.
     * Terminal either way: a request that runs out is not retried, since replaying the prompt on a
     * model that has just shown it needs longer triples the wait for the same outcome.
     */
    private int requestTimeoutSeconds = 0;
    private int maxTables = 5;
    private int maxColumnsPerTable = 40;
    /** Capped by {@code claude.context-window-tokens} when the endpoint's window is declared. */
    private int promptCharBudget = 12_000;
    /** How many times a statement the planner refused is sent back with the planner's sentence. */
    private int repairRounds = 1;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider == null ? "" : provider.strip();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip();
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model == null ? "" : model.strip();
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = Math.max(0, requestTimeoutSeconds);
    }

    public int getMaxTables() {
        return maxTables;
    }

    public void setMaxTables(int maxTables) {
        this.maxTables = Math.max(1, maxTables);
    }

    public int getMaxColumnsPerTable() {
        return maxColumnsPerTable;
    }

    public void setMaxColumnsPerTable(int maxColumnsPerTable) {
        this.maxColumnsPerTable = Math.max(1, maxColumnsPerTable);
    }

    public int getPromptCharBudget() {
        return promptCharBudget;
    }

    public void setPromptCharBudget(int promptCharBudget) {
        this.promptCharBudget = Math.max(1_000, promptCharBudget);
    }

    public int getRepairRounds() {
        return repairRounds;
    }

    public void setRepairRounds(int repairRounds) {
        this.repairRounds = Math.max(0, Math.min(3, repairRounds));
    }
}
