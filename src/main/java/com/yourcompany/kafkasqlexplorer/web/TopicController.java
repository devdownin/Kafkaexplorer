// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.config.ExplorerConfig;
import com.yourcompany.kafkasqlexplorer.domain.MessageFormat;
import com.yourcompany.kafkasqlexplorer.domain.TopicDescriptor;
import com.yourcompany.kafkasqlexplorer.domain.TopicDetailResponse;
import com.yourcompany.kafkasqlexplorer.domain.TopicMessage;
import com.yourcompany.kafkasqlexplorer.domain.TopicSearchRequest;
import com.yourcompany.kafkasqlexplorer.domain.TopicSearchResponse;
import com.yourcompany.kafkasqlexplorer.service.MessageFormatterService;
import com.yourcompany.kafkasqlexplorer.service.DdlGeneratorService;
import com.yourcompany.kafkasqlexplorer.service.KafkaAdminService;
import com.yourcompany.kafkasqlexplorer.service.SchemaInferenceService;
import com.yourcompany.kafkasqlexplorer.service.TopicSearchService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/topic")
public class TopicController {

    /** Messages returned alongside the topic detail. */
    private static final int SAMPLE_SIZE = 20;

    private final KafkaAdminService kafkaAdminService;
    private final SchemaInferenceService schemaInferenceService;
    private final DdlGeneratorService ddlGeneratorService;
    private final MessageFormatterService messageFormatterService;
    private final TopicSearchService topicSearchService;
    private final ExplorerConfig explorerConfig;

    public TopicController(KafkaAdminService kafkaAdminService, SchemaInferenceService schemaInferenceService,
                           DdlGeneratorService ddlGeneratorService, MessageFormatterService messageFormatterService,
                           TopicSearchService topicSearchService, ExplorerConfig explorerConfig) {
        this.kafkaAdminService = kafkaAdminService;
        this.schemaInferenceService = schemaInferenceService;
        this.ddlGeneratorService = ddlGeneratorService;
        this.messageFormatterService = messageFormatterService;
        this.topicSearchService = topicSearchService;
        this.explorerConfig = explorerConfig;
    }

    @GetMapping("/{name}")
    public TopicDetailResponse getTopicDetail(@PathVariable String name,
                                              @RequestParam(defaultValue = "earliest-offset") String readMode) throws Exception {
        TopicDescriptor descriptor = kafkaAdminService.getTopicDescriptor(name);
        MessageFormat format = schemaInferenceService.detectFormat(name);
        Map<String, String> schema = schemaInferenceService.inferSchema(name, format);
        String ddl = DdlGeneratorService.maskSensitiveProperties(
                ddlGeneratorService.generateDdl(name, schema, format, readMode));

        // The read mode drives the sample too, not only the generated DDL: a toggle labelled
        // "Earliest / Latest" sitting next to the message list has to change which messages show up.
        List<ConsumerRecord<String, String>> records = "latest-offset".equals(readMode)
                ? kafkaAdminService.getRecentRecords(name, SAMPLE_SIZE)
                : kafkaAdminService.getEarliestRecords(name, SAMPLE_SIZE);

        return new TopicDetailResponse(descriptor, format, schema, ddl,
                records.stream().map(this::toMessage).toList());
    }

    /**
     * Bounded scan of a topic. Returns what was found plus a cursor, so the UI can show the first
     * hits immediately and resume on demand instead of blocking on an unbounded search.
     */
    @PostMapping("/{name}/search")
    public TopicSearchResponse search(@PathVariable String name,
                                      @RequestBody(required = false) TopicSearchRequest request) {
        TopicSearchRequest criteria = request != null ? request : new TopicSearchRequest(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        try {
            return topicSearchService.search(name, criteria);
        } catch (IllegalArgumentException e) {
            // Invalid regex, or a FIELD search with no path: the user can fix it, so say what's wrong.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @GetMapping(value = "/{name}/ddl", produces = "text/plain")
    public String getDdl(@PathVariable String name,
                         @RequestParam(defaultValue = "earliest-offset") String readMode) throws Exception {
        MessageFormat format = schemaInferenceService.detectFormat(name);
        Map<String, String> schema = schemaInferenceService.inferSchema(name, format);
        return DdlGeneratorService.maskSensitiveProperties(
                ddlGeneratorService.generateDdl(name, schema, format, readMode));
    }

    private TopicMessage toMessage(ConsumerRecord<String, String> record) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Header header : record.headers()) {
            headers.put(header.key(), header.value() == null
                    ? null
                    : new String(header.value(), StandardCharsets.UTF_8));
        }
        return TopicMessage.of(record.partition(), record.offset(), record.timestamp(),
                record.key(), messageFormatterService.format(record.value()), headers,
                explorerConfig.getSearchMaxValueChars());
    }
}
