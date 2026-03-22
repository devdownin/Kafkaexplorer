package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.service.KafkaAdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/cluster")
public class ClusterController {

    private final KafkaAdminService kafkaAdminService;

    public ClusterController(KafkaAdminService kafkaAdminService) {
        this.kafkaAdminService = kafkaAdminService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getClusterDetails() {
        return ResponseEntity.ok(kafkaAdminService.getClusterDetails());
    }

    @GetMapping("/configs")
    public ResponseEntity<Map<String, String>> getBrokerConfigs() {
        return ResponseEntity.ok(kafkaAdminService.getBrokerConfigs());
    }
}
