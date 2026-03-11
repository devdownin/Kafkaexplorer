package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.service.KafkaAdminService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Collections;
import java.util.List;

@Controller
public class CompareController {

    private final KafkaAdminService kafkaAdminService;

    public CompareController(KafkaAdminService kafkaAdminService) {
        this.kafkaAdminService = kafkaAdminService;
    }

    @GetMapping("/compare")
    public String compare(Model model) {
        try {
            List<String> topics = kafkaAdminService.listTopics();
            model.addAttribute("topics", topics);
        } catch (Exception e) {
            model.addAttribute("topics", Collections.emptyList());
        }
        return "compare";
    }
}
