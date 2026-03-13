package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.domain.MessageFormat;
import com.yourcompany.kafkasqlexplorer.domain.TopicDescriptor;
import com.yourcompany.kafkasqlexplorer.service.MessageFormatterService;
import com.yourcompany.kafkasqlexplorer.service.DdlGeneratorService;
import com.yourcompany.kafkasqlexplorer.service.KafkaAdminService;
import com.yourcompany.kafkasqlexplorer.service.SchemaInferenceService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;
import java.util.concurrent.ExecutionException;

@Controller
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

    @GetMapping("/topic/{name}")
    public String detail(@PathVariable String name, Model model) throws Exception {
        TopicDescriptor descriptor = kafkaAdminService.getTopicDescriptor(name);
        MessageFormat format = schemaInferenceService.detectFormat(name);
        Map<String, String> schema = schemaInferenceService.inferSchema(name, format);
        String ddl = ddlGeneratorService.generateDdl(name, schema, format);

        model.addAttribute("topic", descriptor);
        model.addAttribute("format", format);
        model.addAttribute("schema", schema);
        model.addAttribute("ddl", ddl);
        model.addAttribute("samples", kafkaAdminService.getSampleMessages(name, 20).stream()
                .map(messageFormatterService::format)
                .toList());

        return "topic-detail";
    }

    @GetMapping(value = "/api/topic/{name}/ddl", produces = "text/plain")
    @ResponseBody
    public String getDdl(@PathVariable String name) throws Exception {
        MessageFormat format = schemaInferenceService.detectFormat(name);
        Map<String, String> schema = schemaInferenceService.inferSchema(name, format);
        return ddlGeneratorService.generateDdl(name, schema, format);
    }
}
