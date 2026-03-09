package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.config.KafkaConfig;
import com.yourcompany.kafkasqlexplorer.service.KafkaAdminService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

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
        kafkaAdminService.init(); // Refresh AdminClient
        return "redirect:/config";
    }
}
