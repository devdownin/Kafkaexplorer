package com.yourcompany.kafkasqlexplorer.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yourcompany.kafkasqlexplorer.domain.DashboardResponse;
import com.yourcompany.kafkasqlexplorer.service.FlinkSqlService;
import com.yourcompany.kafkasqlexplorer.service.KafkaAdminService;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final KafkaAdminService kafkaAdminService;
    private final FlinkSqlService flinkSqlService;

    public DashboardController(KafkaAdminService kafkaAdminService, FlinkSqlService flinkSqlService) {
        this.kafkaAdminService = kafkaAdminService;
        this.flinkSqlService = flinkSqlService;
    }

    @GetMapping
    public DashboardResponse getDashboardData() throws Exception {
        List<String> topics = kafkaAdminService.listTopics();
        Map<String, Long> topicSizes = kafkaAdminService.getTopicsSize(topics);
        long totalMessages = topicSizes.values().stream().mapToLong(Long::longValue).sum();

        return new DashboardResponse(
                topics,
                topicSizes,
                totalMessages,
                flinkSqlService.listTables(),
                flinkSqlService.getActiveJobs(),
                kafkaAdminService.ping()
        );
    }
}
