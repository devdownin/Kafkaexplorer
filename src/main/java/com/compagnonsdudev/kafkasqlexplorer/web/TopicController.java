// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.web;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.MessageFormat;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicConsumers;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicDescriptor;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicDetailResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicMessage;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicSearchRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicSearchResponse;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicTimeLag;
import com.compagnonsdudev.kafkasqlexplorer.service.MessageFormatterService;
import com.compagnonsdudev.kafkasqlexplorer.service.DdlGeneratorService;
import com.compagnonsdudev.kafkasqlexplorer.service.KafkaAdminService;
import com.compagnonsdudev.kafkasqlexplorer.service.SchemaInferenceService;
import com.compagnonsdudev.kafkasqlexplorer.service.TopicSearchService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    /**
     * Everything the Topic Explorer opens with, from <b>one</b> read of the topic.
     *
     * <p>It used to take three, each opening a KafkaConsumer of its own: {@code detectFormat}
     * sampled the topic, {@code inferSchema} sampled it again for the identical ten records, and
     * the displayed messages were a third read. The audit had already learned this — it fetches
     * one sample and feeds the {@code detectFormat(topic, samples)} / {@code inferSchema(topic,
     * format, samples)} overloads, which exist for exactly this — and the one endpoint a user
     * actually waits on was left on the un-shared path.
     *
     * <p>Inference now reads the records the page displays, capped at {@code
     * explorer.inference-sample-size} so that property still governs how much of a payload budget
     * inference spends. That also makes the two agree: the schema and the DDL used to be inferred
     * from the <em>newest</em> records while the panel below showed the oldest, so the column list
     * could describe records nobody was looking at. The read mode already claims to drive the
     * sample and the DDL together; now it drives the schema between them.
     */
    @GetMapping("/{name}")
    public TopicDetailResponse getTopicDetail(@PathVariable("name") String name,
                                              @RequestParam(name = "readMode", defaultValue = "earliest-offset") String readMode) throws Exception {
        TopicDescriptor descriptor = kafkaAdminService.getTopicDescriptor(name);

        // The read mode drives the sample too, not only the generated DDL: a toggle labelled
        // "Earliest / Latest" sitting next to the message list has to change which messages show up.
        List<ConsumerRecord<String, String>> records = "latest-offset".equals(readMode)
                ? kafkaAdminService.getRecentRecords(name, SAMPLE_SIZE)
                : kafkaAdminService.getEarliestRecords(name, SAMPLE_SIZE);

        List<String> samples = records.stream()
                .map(ConsumerRecord::value)
                .filter(Objects::nonNull)
                .limit(explorerConfig.getInferenceSampleSize())
                .toList();

        MessageFormat format = schemaInferenceService.detectFormat(name, samples);
        Map<String, String> schema = schemaInferenceService.inferSchema(name, format, samples);
        String ddl = DdlGeneratorService.maskSensitiveProperties(
                ddlGeneratorService.generateDdl(name, schema, format, readMode));

        return new TopicDetailResponse(descriptor, format, schema, ddl,
                records.stream().map(this::toMessage).toList());
    }

    /**
     * Bounded scan of a topic. Returns what was found plus a cursor, so the UI can show the first
     * hits immediately and resume on demand instead of blocking on an unbounded search.
     */
    @PostMapping("/{name}/search")
    public TopicSearchResponse search(@PathVariable("name") String name,
                                      @RequestBody(required = false) TopicSearchRequest request) {
        TopicSearchRequest criteria = request != null ? request : new TopicSearchRequest(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        try {
            return topicSearchService.search(name, criteria);
        } catch (IllegalArgumentException e) {
            // Invalid regex, or a FIELD search with no path: the user can fix it, so say what's wrong.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    /**
     * One record, read by its coordinates and returned whole — what a search hit's "truncated"
     * badge could otherwise only point at without ever letting anyone read the rest.
     */
    @GetMapping("/{name}/record")
    public TopicMessage getRecord(@PathVariable("name") String name,
                                  @RequestParam("partition") int partition,
                                  @RequestParam("offset") long offset) {
        TopicMessage record = topicSearchService.readRecord(name, partition, offset);
        if (record == null) {
            // Out of range or compacted away: a caller must be able to tell that from a failure.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "No record at offset " + offset + " of partition " + partition
                    + " — it may have been compacted or aged out.");
        }
        return record;
    }

    /**
     * Who reads this topic and how far behind they are. Never 404s on "no consumer": an empty
     * list is a legitimate answer, and the payload carries the scope of the read so it cannot be
     * confused with a failed one.
     */
    @GetMapping("/{name}/consumers")
    public TopicConsumers getConsumers(@PathVariable("name") String name) {
        return kafkaAdminService.getTopicConsumers(name, explorerConfig.getConsumerGroupMaxGroups());
    }

    /**
     * How far behind one group is <em>in time</em> on this topic — the age of the oldest message
     * it has not read.
     *
     * <p>Deliberately not folded into {@code /consumers}: that endpoint reads metadata for every
     * group of the cluster, while this one opens a consumer and reads a record per lagging
     * partition of one group. Charging every visit to the Consumers tab for a measurement nobody
     * asked for is how a panel becomes expensive; here the operator asks for it, one group at a
     * time.
     *
     * <p>200 even when nothing could be measured: the body carries {@code available} and the
     * reason. A 404 would say the topic or the group does not exist, which is a different answer
     * from "this group's delay could not be read".
     */
    @GetMapping("/{name}/time-lag")
    public TopicTimeLag getTimeLag(@PathVariable("name") String name, @RequestParam("group") String group) {
        return kafkaAdminService.getConsumerTimeLag(name, group);
    }

    @GetMapping(value = "/{name}/ddl", produces = "text/plain")
    public String getDdl(@PathVariable("name") String name,
                         @RequestParam(name = "readMode", defaultValue = "earliest-offset") String readMode) throws Exception {
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
