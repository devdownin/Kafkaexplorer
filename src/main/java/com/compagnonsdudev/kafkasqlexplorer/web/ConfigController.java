// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.web;

import com.compagnonsdudev.kafkasqlexplorer.config.ClaudeConfig;
import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.service.AuditService;
import com.compagnonsdudev.kafkasqlexplorer.service.FlinkSqlService;
import com.compagnonsdudev.kafkasqlexplorer.service.KafkaAdminService;
import com.compagnonsdudev.kafkasqlexplorer.service.LlmClient;
import com.compagnonsdudev.kafkasqlexplorer.service.LlmClientFactory;
import com.compagnonsdudev.kafkasqlexplorer.service.SseEmitterManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings, under {@code /api} only.
 *
 * <p>There used to be a {@code @GetMapping("/config")} returning the view name {@code "config"}.
 * {@code /config} is a client-side route (the Settings page) and there is no template engine, so
 * that mapping shadowed {@link SpaController} and answered a page refresh with a circular-view-path
 * 500 — the same trap already documented for {@code /stream-flow} and {@code /audit}. The
 * form-encoded {@code POST /config} went with it: the SPA posts JSON to {@code /api/config}.
 */
@RestController
public class ConfigController {

    private final KafkaConfig kafkaConfig;
    private final KafkaAdminService kafkaAdminService;
    private final ClaudeConfig claudeConfig;
    private final AuditService auditService;
    private final FlinkSqlService flinkSqlService;
    private final SseEmitterManager sseEmitterManager;

    public ConfigController(KafkaConfig kafkaConfig,
                            KafkaAdminService kafkaAdminService,
                            ClaudeConfig claudeConfig,
                            AuditService auditService,
                            FlinkSqlService flinkSqlService,
                            SseEmitterManager sseEmitterManager) {
        this.kafkaConfig = kafkaConfig;
        this.kafkaAdminService = kafkaAdminService;
        this.claudeConfig = claudeConfig;
        this.auditService = auditService;
        this.flinkSqlService = flinkSqlService;
        this.sseEmitterManager = sseEmitterManager;
    }

    @GetMapping("/api/config")
    public Map<String, Object> getConfig() {
        Map<String, Object> result = new HashMap<>();
        result.put("bootstrapServers", kafkaConfig.getBootstrapServers());
        result.put("mode", kafkaConfig.getMode());
        result.put("clusters", kafkaConfig.getClusters());
        result.put("isConnected", kafkaAdminService.ping());
        appendLlmConfig(result);
        return result;
    }

    /**
     * Work that a settings change would pull the ground out from under.
     *
     * <p>Repointing Kafka mutates shared singletons and re-initialises the admin client, while an
     * audit keeps scanning, Flink jobs keep streaming and a live Process Mining session keeps
     * polling — all against the cluster that was configured when they started. The report, the job
     * and the session would then describe two different clusters under one heading. Nothing here
     * stops that work: it is bounded and cancellable through its own screens, and killing it from
     * a settings save would be a worse surprise than refusing the save.
     */
    private List<String> workInFlight() {
        List<String> running = new ArrayList<>();
        String auditId = auditService.runningAuditId();
        if (auditId != null) {
            running.add("an audit (" + auditId + ")");
        }
        int jobs = flinkSqlService.getActiveJobsDetails().size();
        if (jobs > 0) {
            running.add(jobs + " Flink job" + (jobs > 1 ? "s" : ""));
        }
        int sessions = sseEmitterManager.activeSessions();
        if (sessions > 0) {
            running.add(sessions + " live Process Mining session" + (sessions > 1 ? "s" : ""));
        }
        return running;
    }

    /**
     * @param body {@code force: true} proceeds anyway — the operator who knows what is running is
     *             not blocked, they are told
     */
    @PostMapping(value = "/api/config", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody Map<String, Object> body) {
        boolean repointsCluster = body.containsKey("bootstrapServers") || body.containsKey("mode");
        if (repointsCluster && !Boolean.parseBoolean(asString(body.get("force")))) {
            List<String> running = workInFlight();
            if (!running.isEmpty()) {
                Map<String, Object> conflict = new HashMap<>();
                conflict.put("message", "Not applied: " + String.join(", ", running)
                    + " still running against the current cluster. Stop it, or send force=true to "
                    + "repoint anyway — what is already running keeps reading the previous cluster.");
                conflict.put("inFlight", running);
                return ResponseEntity.status(HttpStatus.CONFLICT).body(conflict);
            }
        }
        return ResponseEntity.ok(applyConfig(body));
    }

    private Map<String, Object> applyConfig(Map<String, Object> body) {
        ClaudeConfig.Provider previousProvider = claudeConfig.getProvider();

        if (body.containsKey("bootstrapServers") && body.get("bootstrapServers") != null) {
            kafkaConfig.setBootstrapServers(asString(body.get("bootstrapServers")));
        }
        if (body.containsKey("mode") && body.get("mode") != null) {
            kafkaConfig.setMode(asString(body.get("mode")));
        }
        if (body.containsKey("truststorePath")) kafkaConfig.setTruststorePath(asString(body.get("truststorePath")));
        if (body.containsKey("truststorePassword")) kafkaConfig.setTruststorePassword(asString(body.get("truststorePassword")));
        if (body.containsKey("keystorePath")) kafkaConfig.setKeystorePath(asString(body.get("keystorePath")));
        if (body.containsKey("keystorePassword")) kafkaConfig.setKeystorePassword(asString(body.get("keystorePassword")));
        if (body.containsKey("keyPassword")) kafkaConfig.setKeyPassword(asString(body.get("keyPassword")));
        if (body.containsKey("confluentKey")) kafkaConfig.setConfluentKey(asString(body.get("confluentKey")));
        if (body.containsKey("confluentSecret")) kafkaConfig.setConfluentSecret(asString(body.get("confluentSecret")));

        if (body.containsKey("llmProvider") && body.get("llmProvider") != null) {
            claudeConfig.setProvider(ClaudeConfig.Provider.valueOf(asString(body.get("llmProvider"))));
        }
        if (body.containsKey("llmApiKey")) {
            claudeConfig.setApiKey(asString(body.get("llmApiKey")));
        }
        if (body.containsKey("llmBaseUrl")) {
            claudeConfig.setBaseUrl(asString(body.get("llmBaseUrl")));
        } else if (previousProvider != claudeConfig.getProvider()
            && isBlank(claudeConfig.getBaseUrl())) {
            claudeConfig.setBaseUrl(ClaudeConfig.defaultBaseUrl(claudeConfig.getProvider()));
        }
        if (body.containsKey("llmModel")) {
            claudeConfig.setModel(asString(body.get("llmModel")));
        }
        if (body.containsKey("llmUseRag") && body.get("llmUseRag") != null) {
            claudeConfig.setUseRag(Boolean.parseBoolean(asString(body.get("llmUseRag"))));
        }
        if (body.containsKey("llmCollection")) {
            claudeConfig.setCollection(asString(body.get("llmCollection")));
        }
        if (body.containsKey("llmRequestTimeoutSeconds") && body.get("llmRequestTimeoutSeconds") != null) {
            claudeConfig.setRequestTimeoutSeconds(Integer.parseInt(asString(body.get("llmRequestTimeoutSeconds"))));
        }
        if (body.containsKey("llmMaxTokens") && body.get("llmMaxTokens") != null) {
            claudeConfig.setMaxTokens(Integer.parseInt(asString(body.get("llmMaxTokens"))));
        }
        if (body.containsKey("llmSnapshotWindowSize") && body.get("llmSnapshotWindowSize") != null) {
            claudeConfig.setSnapshotWindowSize(Integer.parseInt(asString(body.get("llmSnapshotWindowSize"))));
        }
        if (body.containsKey("llmSnapshotWindowTimeoutSeconds")
            && body.get("llmSnapshotWindowTimeoutSeconds") != null) {
            claudeConfig.setSnapshotWindowTimeoutSeconds(
                Integer.parseInt(asString(body.get("llmSnapshotWindowTimeoutSeconds")))
            );
        }

        kafkaAdminService.init();
        Map<String, Object> result = new HashMap<>();
        result.put("bootstrapServers", kafkaConfig.getBootstrapServers());
        result.put("mode", kafkaConfig.getMode());
        result.put("isConnected", kafkaAdminService.ping());
        appendLlmConfig(result);
        return result;
    }

    @PostMapping("/api/config/test-llm")
    public Map<String, Object> testLlm() {
        Map<String, Object> result = new HashMap<>();
        result.put("provider", claudeConfig.getProviderLabel());
        result.put("model", claudeConfig.getModel());

        if (claudeConfig.isApiKeyRequired() && !claudeConfig.isApiKeyConfigured()) {
            result.put("ok", false);
            result.put("message", "An API key is required for " + claudeConfig.getProviderLabel()
                + " but none is configured.");
            return result;
        }

        try {
            LlmClient client = LlmClientFactory.create(claudeConfig);
            String reply = client.generate(
                "You are a connectivity health check. Answer in one short word.",
                "Reply with the word OK.");
            result.put("ok", true);
            result.put("message", "LLM reachable via " + claudeConfig.getResolvedBaseUrl()
                + ". Sample reply: " + summarize(reply));
        } catch (Exception e) {
            result.put("ok", false);
            result.put("message", e.getMessage() != null ? e.getMessage() : e.toString());
        }
        return result;
    }

    private String summarize(String reply) {
        if (reply == null || reply.isBlank()) {
            return "(empty)";
        }
        String trimmed = reply.strip();
        return trimmed.length() > 80 ? trimmed.substring(0, 80) + "…" : trimmed;
    }

    private void appendLlmConfig(Map<String, Object> result) {
        result.put("llmProvider", claudeConfig.getProvider().name());
        result.put("llmProviderLabel", claudeConfig.getProviderLabel());
        result.put("llmApiKeyConfigured", claudeConfig.isApiKeyConfigured());
        result.put("llmApiKeyRequired", claudeConfig.isApiKeyRequired());
        result.put("llmBaseUrl", claudeConfig.getResolvedBaseUrl());
        result.put("llmModel", claudeConfig.getModel());
        result.put("llmUseRag", claudeConfig.isUseRag());
        result.put("llmCollection", claudeConfig.getCollection());
        result.put("llmRequestTimeoutSeconds", claudeConfig.getRequestTimeoutSeconds());
        result.put("llmMaxTokens", claudeConfig.getMaxTokens());
        result.put("llmSnapshotWindowSize", claudeConfig.getSnapshotWindowSize());
        result.put("llmSnapshotWindowTimeoutSeconds", claudeConfig.getSnapshotWindowTimeoutSeconds());
        result.put("llmLocalDeployment", claudeConfig.isLocalDeployment());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
