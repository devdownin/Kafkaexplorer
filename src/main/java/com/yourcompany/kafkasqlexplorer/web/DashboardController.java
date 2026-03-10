package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.service.FlinkSqlService;
import com.yourcompany.kafkasqlexplorer.service.KafkaAdminService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.concurrent.ExecutionException;

@Controller
public class DashboardController {

    private final KafkaAdminService kafkaAdminService;
    private final FlinkSqlService flinkSqlService;

    public DashboardController(KafkaAdminService kafkaAdminService, FlinkSqlService flinkSqlService) {
        this.kafkaAdminService = kafkaAdminService;
        this.flinkSqlService = flinkSqlService;
    }

    @GetMapping("/")
    public String index(Model model) {
        try {
            java.util.List<String> topics = kafkaAdminService.listTopics();
            java.util.Map<String, Long> topicSizes = kafkaAdminService.getTopicsSize(topics);

            model.addAttribute("topics", topics);
            model.addAttribute("topicSizes", topicSizes);
            model.addAttribute("tables", flinkSqlService.listTables());
            model.addAttribute("jobs", flinkSqlService.getActiveJobs());
        } catch (Exception e) {
            model.addAttribute("topics", java.util.Collections.emptyList());
            model.addAttribute("topicSizes", java.util.Collections.emptyMap());
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
