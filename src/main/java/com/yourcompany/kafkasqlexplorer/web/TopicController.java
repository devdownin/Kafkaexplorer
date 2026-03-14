package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.domain.MessageFormat;
import com.yourcompany.kafkasqlexplorer.domain.TopicDescriptor;
import com.yourcompany.kafkasqlexplorer.domain.TopicDetailResponse;
import com.yourcompany.kafkasqlexplorer.service.MessageFormatterService;
import com.yourcompany.kafkasqlexplorer.service.DdlGeneratorService;
import com.yourcompany.kafkasqlexplorer.service.KafkaAdminService;
import com.yourcompany.kafkasqlexplorer.service.SchemaInferenceService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/topic")
public class TopicController {

    private final KafkaAdminService kafkaAdminService;
    private final SchemaInferenceService schemaInferenceService;
    private final DdlGeneratorService ddlGeneratorService;
    private final MessageFormatterService messageFormatterService;

    public TopicController(KafkaAdminService kafkaAdminService, SchemaInferenceService schemaInferenceService, DdlGeneratorService ddlGeneratorService, MessageFormatterService messageFormatterService) {
        this.kafkaAdminService = kafkaAdminService;
        this.schemaInferenceService = schemaInferenceService;
        this.ddlGeneratorService = ddlGeneratorService;
        this.messageFormatterService = messageFormatterService;
    }

    @GetMapping("/{name}")
    public TopicDetailResponse getTopicDetail(@PathVariable String name,
                                              @RequestParam(defaultValue = "earliest-offset") String readMode) throws Exception {
        TopicDescriptor descriptor = kafkaAdminService.getTopicDescriptor(name);
        MessageFormat format = schemaInferenceService.detectFormat(name);
        Map<String, String> schema = schemaInferenceService.inferSchema(name, format);
        String ddl = ddlGeneratorService.generateDdl(name, schema, format, readMode);

        return new TopicDetailResponse(
                descriptor,
                format,
                schema,
                ddl,
                kafkaAdminService.getSampleMessages(name, 20).stream()
                        .map(messageFormatterService::format)
                        .toList()
        );
    }

    @GetMapping(value = "/{name}/ddl", produces = "text/plain")
    public String getDdl(@PathVariable String name,
                         @RequestParam(defaultValue = "earliest-offset") String readMode) throws Exception {
        MessageFormat format = schemaInferenceService.detectFormat(name);
        Map<String, String> schema = schemaInferenceService.inferSchema(name, format);
        return ddlGeneratorService.generateDdl(name, schema, format, readMode);
    }
}
