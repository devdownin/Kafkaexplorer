package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.service.KafkaAdminService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.concurrent.ExecutionException;

@Controller
public class DashboardController {

    private final KafkaAdminService kafkaAdminService;

    public DashboardController(KafkaAdminService kafkaAdminService) {
        this.kafkaAdminService = kafkaAdminService;
    }

    @GetMapping("/")
    public String index(Model model) {
        try {
            model.addAttribute("topics", kafkaAdminService.listTopics());
        } catch (Exception e) {
            model.addAttribute("topics", java.util.Collections.emptyList());
            model.addAttribute("error", "Could not connect to Kafka: " + e.getMessage());
        }
        return "dashboard";
    }

    @GetMapping("/api/topics")
    @org.springframework.web.bind.annotation.ResponseBody
    public java.util.List<String> getTopics() throws ExecutionException, InterruptedException {
        return kafkaAdminService.listTopics();
    }
}
