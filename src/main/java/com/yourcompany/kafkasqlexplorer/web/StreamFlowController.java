// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.domain.StreamFlowRequest;
import com.yourcompany.kafkasqlexplorer.service.StreamFlowService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Stream-flow tracing API.
 *
 * <p>There is deliberately no {@code GET /stream-flow} mapping: {@code /stream-flow} is a
 * client-side route served by {@link SpaController}, and a controller mapping on it returned the
 * view name {@code "stream-flow"} to an application that has no template engine — refreshing the
 * page answered 500 (circular view path) instead of loading the SPA. Same rule as {@code /audit}.
 */
@RestController
@RequestMapping("/api/stream-flow")
public class StreamFlowController {

    private final StreamFlowService streamFlowService;

    public StreamFlowController(StreamFlowService streamFlowService) {
        this.streamFlowService = streamFlowService;
    }

    @PostMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<?> getStreamFlow(@RequestBody StreamFlowRequest request) {
        try {
            return ResponseEntity.ok(streamFlowService.getStreamFlow(request));
        } catch (IllegalArgumentException e) {
            // Invalid regex, malformed search path, missing key: the caller can fix it, so the
            // reason travels in the body. An explicit payload rather than a ResponseStatusException
            // because server.error.include-message defaults to "never" — the framework's own error
            // body would reach the UI as a bare "Bad Request".
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
