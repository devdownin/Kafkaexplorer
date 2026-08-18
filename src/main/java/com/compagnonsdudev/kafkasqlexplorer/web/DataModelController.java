// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.web;

import com.compagnonsdudev.kafkasqlexplorer.domain.DataModelRequest;
import com.compagnonsdudev.kafkasqlexplorer.service.DataModelService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST endpoint for the deduced data model.
 *
 * <p>No {@code GET /data-model} mapping, deliberately: that path is a client-side route owned
 * by the React router, and a controller mapping there would shadow {@link SpaController} and
 * answer a page refresh with a circular-view-path 500 — the same rule as {@code /audit} and
 * {@code /stream-flow}.
 */
@RestController
public class DataModelController {

    private final DataModelService dataModelService;

    public DataModelController(DataModelService dataModelService) {
        this.dataModelService = dataModelService;
    }

    /**
     * Builds the model for the selected topics. 400 carries an explicit {@code message} body:
     * {@code server.error.include-message} defaults to {@code never}, so a bare
     * ResponseStatusException would reach the UI as "Bad Request" with no reason.
     */
    @PostMapping("/api/data-model")
    public ResponseEntity<?> buildModel(@RequestBody(required = false) DataModelRequest request) {
        List<String> topics = request == null ? null : request.topics();
        if (topics == null || topics.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", "Select at least one topic to build the model from."));
        }
        try {
            return ResponseEntity.ok(dataModelService.buildModel(topics));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", e.getMessage()));
        }
    }
}
