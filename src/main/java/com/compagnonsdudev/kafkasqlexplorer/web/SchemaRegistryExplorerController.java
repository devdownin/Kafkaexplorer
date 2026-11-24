// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.web;

import com.compagnonsdudev.kafkasqlexplorer.domain.RegistryCompatibility;
import com.compagnonsdudev.kafkasqlexplorer.domain.RegistrySchemaVersion;
import com.compagnonsdudev.kafkasqlexplorer.domain.RegistrySubjects;
import com.compagnonsdudev.kafkasqlexplorer.service.SchemaRegistryExplorerService;
import com.compagnonsdudev.kafkasqlexplorer.service.SchemaRegistryExplorerService.RegistryFailure;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Schema Registry viewer, always under /api (never shadows the SPA's /schema-registry route).
 * The sole POST operation calls Confluent's read-only compatibility check; it registers nothing.
 */
@RestController
@RequestMapping("/api/schema-registry")
public class SchemaRegistryExplorerController {
    private final SchemaRegistryExplorerService service;

    public SchemaRegistryExplorerController(SchemaRegistryExplorerService service) {
        this.service = service;
    }

    @GetMapping("/subjects")
    public RegistrySubjects subjects() {
        return service.subjects();
    }

    @GetMapping("/subjects/{subject}/versions")
    public List<Integer> versions(@PathVariable("subject") String subject) {
        return service.versions(subject);
    }

    @GetMapping("/subjects/{subject}/versions/{version}")
    public RegistrySchemaVersion schema(@PathVariable("subject") String subject,
                                        @PathVariable("version") int version) {
        return service.version(subject, version);
    }

    public record CheckRequest(int baselineVersion, int candidateVersion) {}

    @PostMapping("/subjects/{subject}/compatibility")
    public RegistryCompatibility compatibility(@PathVariable("subject") String subject,
                                                @RequestBody(required = false) CheckRequest request) {
        if (request == null) {
            throw new RegistryFailure(HttpStatus.BAD_REQUEST, "Select baseline and candidate versions.");
        }
        return service.compatibility(subject, request.baselineVersion(), request.candidateVersion());
    }

    @ExceptionHandler(RegistryFailure.class)
    public ResponseEntity<Map<String, String>> registryError(RegistryFailure failure) {
        return ResponseEntity.status(failure.status()).body(Map.of("message", failure.getMessage()));
    }
}
