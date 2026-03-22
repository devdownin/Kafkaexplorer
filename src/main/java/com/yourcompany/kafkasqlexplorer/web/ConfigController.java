package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.config.KafkaConfig;
import com.yourcompany.kafkasqlexplorer.service.KafkaAdminService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Controller
public class ConfigController {

    private final KafkaConfig kafkaConfig;
    private final KafkaAdminService kafkaAdminService;

    public ConfigController(KafkaConfig kafkaConfig, KafkaAdminService kafkaAdminService) {
        this.kafkaConfig = kafkaConfig;
        this.kafkaAdminService = kafkaAdminService;
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
        return result;
    }

    @PostMapping(value = "/api/config", consumes = "application/json")
    @ResponseBody
    public Map<String, Object> updateConfig(@RequestBody Map<String, String> body) {
        if (body.containsKey("bootstrapServers") && body.get("bootstrapServers") != null) {
            kafkaConfig.setBootstrapServers(body.get("bootstrapServers"));
        }
        if (body.containsKey("mode") && body.get("mode") != null) {
            kafkaConfig.setMode(body.get("mode"));
        }
        if (body.containsKey("truststorePath")) kafkaConfig.setTruststorePath(body.get("truststorePath"));
        if (body.containsKey("truststorePassword")) kafkaConfig.setTruststorePassword(body.get("truststorePassword"));
        if (body.containsKey("keystorePath")) kafkaConfig.setKeystorePath(body.get("keystorePath"));
        if (body.containsKey("keystorePassword")) kafkaConfig.setKeystorePassword(body.get("keystorePassword"));
        if (body.containsKey("keyPassword")) kafkaConfig.setKeyPassword(body.get("keyPassword"));
        if (body.containsKey("confluentKey")) kafkaConfig.setConfluentKey(body.get("confluentKey"));
        if (body.containsKey("confluentSecret")) kafkaConfig.setConfluentSecret(body.get("confluentSecret"));
        kafkaAdminService.init();
        Map<String, Object> result = new HashMap<>();
        result.put("bootstrapServers", kafkaConfig.getBootstrapServers());
        result.put("mode", kafkaConfig.getMode());
        result.put("isConnected", kafkaAdminService.ping());
        return result;
    }
}
