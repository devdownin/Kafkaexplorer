// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.web;

import com.compagnonsdudev.kafkasqlexplorer.service.KafkaAdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The topic list the Compare page reads, and nothing else.
 *
 * <p>It used to also map {@code GET /compare} returning the view name {@code "compare"}. There is
 * no template engine here, so that shadowed {@link SpaController} and answered a page refresh with
 * a circular-view-path 500 — the same defect {@code StreamFlowController} and
 * {@code ConfigController} were each fixed for, and the reason {@code CLAUDE.md} says never to map
 * a controller onto a client-side route. It survived because nothing tested it;
 * {@code SpaRoutingTest} does now.
 */
@RestController
public class CompareController {

    private final KafkaAdminService kafkaAdminService;

    public CompareController(KafkaAdminService kafkaAdminService) {
        this.kafkaAdminService = kafkaAdminService;
    }

    @GetMapping("/api/compare/topics")
    public List<String> getTopicsApi() {
        try {
            List<String> allTopics = kafkaAdminService.listTopics();
            Map<String, Long> sizes = kafkaAdminService.getTopicsSize(allTopics);
            return allTopics.stream()
                    .filter(name -> sizes.getOrDefault(name, 0L) > 0)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
