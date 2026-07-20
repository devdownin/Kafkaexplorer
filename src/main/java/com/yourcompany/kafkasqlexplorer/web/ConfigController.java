// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.config.ClaudeConfig;
import com.yourcompany.kafkasqlexplorer.config.KafkaConfig;
import com.yourcompany.kafkasqlexplorer.service.KafkaAdminService;
import com.yourcompany.kafkasqlexplorer.service.LlmClient;
import com.yourcompany.kafkasqlexplorer.service.LlmClientFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Controller
public class ConfigController {

    private final KafkaConfig kafkaConfig;
    private final KafkaAdminService kafkaAdminService;
    private final ClaudeConfig claudeConfig;

    public ConfigController(KafkaConfig kafkaConfig,
                            KafkaAdminService kafkaAdminService,
                            ClaudeConfig claudeConfig) {
        this.kafkaConfig = kafkaConfig;
        this.kafkaAdminService = kafkaAdminService;
        this.claudeConfig = claudeConfig;
    }

    @GetMapping("/config")
    public String index(Model model) {
        model.addAttribute("bootstrapServers", kafkaConfig.getBootstrapServers());
        model.addAttribute("clusters", kafkaConfig.getClusters());
        model.addAttribute("isConnected", kafkaAdminService.ping());
        return "config";
    }

    @PostMapping("/config")
    public String update(@RequestParam String bootstrapServers) {
        kafkaConfig.setBootstrapServers(bootstrapServers);
        kafkaAdminService.init();
        return "redirect:/config";
    }

    @GetMapping("/api/config")
    @ResponseBody
    public Map<String, Object> getConfig() {
        Map<String, Object> result = new HashMap<>();
        result.put("bootstrapServers", kafkaConfig.getBootstrapServers());
        result.put("mode", kafkaConfig.getMode());
        result.put("clusters", kafkaConfig.getClusters());
        result.put("isConnected", kafkaAdminService.ping());
        appendLlmConfig(result);
        return result;
    }

    @PostMapping(value = "/api/config", consumes = "application/json")
    @ResponseBody
    public Map<String, Object> updateConfig(@RequestBody Map<String, Object> body) {
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
    @ResponseBody
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
