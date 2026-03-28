// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.service.MessageProducerService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Endpoint for data injection, restricted to 'test' profile for security.
 */
@RestController
@RequestMapping("/api/test")
@Profile("test")
public class TestController {

    private final MessageProducerService messageProducerService;

    public TestController(MessageProducerService messageProducerService) {
        this.messageProducerService = messageProducerService;
    }

    @PostMapping("/produce/{topic}")
    public ResponseEntity<?> produce(@PathVariable String topic,
                                     @RequestParam(required = false) String key,
                                     @RequestBody String body) {
        try {
            messageProducerService.produce(topic, key, body);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
