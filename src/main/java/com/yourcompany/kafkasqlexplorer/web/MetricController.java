package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.domain.MetricConfig;
import com.yourcompany.kafkasqlexplorer.service.MetricService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/metrics")
public class MetricController {

    private final MetricService metricService;

    public MetricController(MetricService metricService) {
        this.metricService = metricService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("metrics", metricService.getAllMetrics());
        return "metrics";
    }

    @PostMapping("/save")
    public String save(@ModelAttribute MetricConfig metric) {
        metricService.save(metric);
        return "redirect:/metrics";
    }

    @PostMapping("/delete/{id}")
    public String delete(@PathVariable String id) {
        metricService.delete(id);
        return "redirect:/metrics";
    }
}
