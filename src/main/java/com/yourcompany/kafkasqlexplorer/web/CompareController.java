package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.service.KafkaAdminService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
public class CompareController {

    private final KafkaAdminService kafkaAdminService;

    public CompareController(KafkaAdminService kafkaAdminService) {
        this.kafkaAdminService = kafkaAdminService;
    }

    @GetMapping("/compare")
    public String compare(Model model) {
        try {
            List<String> allTopics = kafkaAdminService.listTopics();
            Map<String, Long> sizes = kafkaAdminService.getTopicsSize(allTopics);

            List<String> nonEmptyTopics = allTopics.stream()
                    .filter(name -> sizes.getOrDefault(name, 0L) > 0)
                    .collect(Collectors.toList());

            model.addAttribute("topics", nonEmptyTopics);
        } catch (Exception e) {
            model.addAttribute("topics", Collections.emptyList());
        }
        return "compare";
    }
}
