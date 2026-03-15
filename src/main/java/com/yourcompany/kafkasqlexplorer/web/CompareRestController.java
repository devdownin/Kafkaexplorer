package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.domain.TopicDetailResponse;
import com.yourcompany.kafkasqlexplorer.service.KafkaAdminService;
import com.yourcompany.kafkasqlexplorer.service.MessageFormatterService;
import com.yourcompany.kafkasqlexplorer.service.SchemaInferenceService;
import com.yourcompany.kafkasqlexplorer.service.DdlGeneratorService;
import com.yourcompany.kafkasqlexplorer.domain.MessageFormat;
import com.yourcompany.kafkasqlexplorer.domain.TopicDescriptor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/api/compare")
public class CompareRestController {

    private final KafkaAdminService kafkaAdminService;
    private final SchemaInferenceService schemaInferenceService;
    private final MessageFormatterService messageFormatterService;

    public CompareRestController(KafkaAdminService kafkaAdminService,
                                 SchemaInferenceService schemaInferenceService,
                                 MessageFormatterService messageFormatterService) {
        this.kafkaAdminService = kafkaAdminService;
        this.schemaInferenceService = schemaInferenceService;
        this.messageFormatterService = messageFormatterService;
    }

    @GetMapping("/samples")
    public Map<String, List<String>> getSamples(@RequestParam String topicA, @RequestParam String topicB) {
        return Map.of(
            "topicA", kafkaAdminService.getSampleMessages(topicA, 50).stream()
                        .map(messageFormatterService::format).toList(),
            "topicB", kafkaAdminService.getSampleMessages(topicB, 50).stream()
                        .map(messageFormatterService::format).toList()
        );
    }
}
