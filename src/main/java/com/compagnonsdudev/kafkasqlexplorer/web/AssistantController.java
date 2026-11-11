// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.web;

import com.compagnonsdudev.kafkasqlexplorer.domain.ApiError;
import com.compagnonsdudev.kafkasqlexplorer.domain.AssistantMetricRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.AssistantSqlRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.AssistantStatus;
import com.compagnonsdudev.kafkasqlexplorer.domain.LlmTestResponse;
import com.compagnonsdudev.kafkasqlexplorer.service.AssistantService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The SQL and metric assistant — {@code EMBEDDED-LLM-SCOPE.md}.
 *
 * <p>Under {@code /api/}, with its own mapping, and on no client route: the SPA routing rules in
 * {@code docs/notes/configuration-and-routing.md} are why. Nothing here executes or saves a
 * statement; a proposal is returned and the page decides what the user does with it.
 *
 * <p>A request the assistant cannot serve is a 200 carrying a status ({@code DISABLED},
 * {@code BUSY}, {@code ERROR}) and the reason, because the page renders those as states of the
 * assistant rather than as failures of the page. A malformed request is a 400.
 */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private final AssistantService assistantService;

    public AssistantController(AssistantService assistantService) {
        this.assistantService = assistantService;
    }

    @GetMapping("/status")
    public AssistantStatus status() {
        return assistantService.status();
    }

    /** One call to the assistant's own model — Settings' test for the second consumer. */
    @PostMapping("/test")
    public LlmTestResponse test() {
        return assistantService.test();
    }

    @PostMapping("/sql")
    public ResponseEntity<?> sql(@RequestBody(required = false) AssistantSqlRequest request) {
        try {
            return ResponseEntity.ok(assistantService.sql(request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiError.of(e));
        }
    }

    @PostMapping("/metric")
    public ResponseEntity<?> metric(@RequestBody(required = false) AssistantMetricRequest request) {
        try {
            return ResponseEntity.ok(assistantService.metric(request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiError.of(e));
        }
    }
}
