// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.web;

import com.yourcompany.kafkasqlexplorer.domain.AnomalyReport;
import com.yourcompany.kafkasqlexplorer.domain.AuditPrompt;
import com.yourcompany.kafkasqlexplorer.domain.FieldMapping;
import com.yourcompany.kafkasqlexplorer.domain.FieldProfileResult;
import com.yourcompany.kafkasqlexplorer.domain.ProcessMiningResult;
import com.yourcompany.kafkasqlexplorer.domain.SchemaUnificationProposal;
import com.yourcompany.kafkasqlexplorer.domain.SnapshotConfig;
import com.yourcompany.kafkasqlexplorer.domain.UnificationEntry;
import com.yourcompany.kafkasqlexplorer.service.AuditPromptCatalog;
import com.yourcompany.kafkasqlexplorer.service.FieldProfilingService;
import com.yourcompany.kafkasqlexplorer.service.KafkaLiveConsumer;
import com.yourcompany.kafkasqlexplorer.service.LlmAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.yourcompany.kafkasqlexplorer.service.SseEmitterManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/process-mining")
public class ProcessMiningController {

    private static final Logger log = LoggerFactory.getLogger(ProcessMiningController.class);

    private final FieldProfilingService fieldProfilingService;
    private final LlmAnalysisService llmAnalysisService;
    private final KafkaLiveConsumer kafkaLiveConsumer;
    private final SseEmitterManager sseEmitterManager;
    private final AuditPromptCatalog auditPromptCatalog;

    // Simple in-memory cache for FieldMapping (TTL not enforced but acceptable for this use case)
    private final ConcurrentHashMap<String, FieldMapping> fieldMappingCache = new ConcurrentHashMap<>();

    public ProcessMiningController(FieldProfilingService fieldProfilingService,
                                    LlmAnalysisService llmAnalysisService,
                                    KafkaLiveConsumer kafkaLiveConsumer,
                                    SseEmitterManager sseEmitterManager,
                                    AuditPromptCatalog auditPromptCatalog) {
        this.fieldProfilingService = fieldProfilingService;
        this.llmAnalysisService = llmAnalysisService;
        this.kafkaLiveConsumer = kafkaLiveConsumer;
        this.sseEmitterManager = sseEmitterManager;
        this.auditPromptCatalog = auditPromptCatalog;
    }

    // Inner request records
    record ProfilingRequest(List<String> topics, SnapshotConfig depth) {}
    record ValidationRequest(SchemaUnificationProposal proposal, Map<String, Object> userCorrections) {}
    record SnapshotRequest(List<String> topics, SnapshotConfig depth, String fieldMappingId,
                           List<String> auditPromptIds, String customAuditPrompt) {}

    @GetMapping("/audit-templates")
    public List<AuditPrompt> auditTemplates() {
        return auditPromptCatalog.all();
    }

    @PostMapping("/profiling/start")
    public FieldProfileResult startProfiling(@RequestBody ProfilingRequest request) {
        log.info("Starting field profiling for topics: {}", request.topics());
        SnapshotConfig depth = request.depth() != null
            ? request.depth()
            : SnapshotConfig.latestN(500);
        return fieldProfilingService.profile(request.topics(), depth);
    }

    @PostMapping("/profiling/validate")
    public Map<String, String> validateSchema(@RequestBody ValidationRequest request) {
        log.info("Validating schema proposal");

        SchemaUnificationProposal proposal = request.proposal();
        Map<String, Object> corrections = request.userCorrections() != null
            ? request.userCorrections()
            : Map.of();

        // Build FieldMapping from proposal + user corrections
        Map<String, String> correlationIdPaths = new LinkedHashMap<>();
        Map<String, String> timestampPaths = new LinkedHashMap<>();
        Map<String, String> statusPaths = new LinkedHashMap<>();
        Map<String, Map<String, List<String>>> statusEquivalences = new LinkedHashMap<>();

        if (proposal != null) {
            // Extract paths from unification entries
            if (proposal.correlationId() != null && proposal.correlationId().mappings() != null) {
                correlationIdPaths.putAll(proposal.correlationId().mappings());
            }
            if (proposal.timestamp() != null && proposal.timestamp().mappings() != null) {
                timestampPaths.putAll(proposal.timestamp().mappings());
            }
            if (proposal.status() != null && proposal.status().mappings() != null) {
                statusPaths.putAll(proposal.status().mappings());
            }
        }

        // Apply user corrections (override paths)
        applyCorrections(correlationIdPaths, corrections, "correlationId");
        applyCorrections(timestampPaths, corrections, "timestamp");
        applyCorrections(statusPaths, corrections, "status");

        String mappingId = UUID.randomUUID().toString();
        FieldMapping fieldMapping = new FieldMapping(
            mappingId,
            correlationIdPaths,
            timestampPaths,
            statusPaths,
            statusEquivalences
        );

        fieldMappingCache.put(mappingId, fieldMapping);
        log.info("Stored FieldMapping with id: {}", mappingId);

        return Map.of("fieldMappingId", mappingId);
    }

    @PostMapping("/snapshot")
    public ProcessMiningResult analyzeSnapshot(@RequestBody SnapshotRequest request) {
        log.info("Starting snapshot analysis for topics: {}", request.topics());

        FieldMapping fieldMapping = null;
        if (request.fieldMappingId() != null) {
            fieldMapping = fieldMappingCache.get(request.fieldMappingId());
            if (fieldMapping == null) {
                log.warn("FieldMapping not found for id: {}", request.fieldMappingId());
            }
        }

        SnapshotConfig depth = request.depth() != null
            ? request.depth()
            : SnapshotConfig.latestN(500);

        String auditFocus = buildAuditFocus(request.auditPromptIds(), request.customAuditPrompt());

        return llmAnalysisService.analyzeSnapshot(request.topics(), depth, fieldMapping, auditFocus);
    }

    @GetMapping(value = "/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter startLive(@RequestParam List<String> topics,
                                 @RequestParam String fieldMappingId,
                                 @RequestParam(required = false) List<String> auditPromptIds,
                                 @RequestParam(required = false) String customAuditPrompt) {
        String sessionId = UUID.randomUUID().toString();
        log.info("Starting live session {} for topics: {}", sessionId, topics);

        FieldMapping fieldMapping = fieldMappingCache.get(fieldMappingId);
        if (fieldMapping == null) {
            log.warn("FieldMapping not found for id: {} — proceeding without mapping", fieldMappingId);
        }

        SseEmitter emitter = sseEmitterManager.create(sessionId);

        final FieldMapping fm = fieldMapping;
        final String auditFocus = buildAuditFocus(auditPromptIds, customAuditPrompt);
        CompletableFuture.runAsync(() -> {
            try {
                // Send initial connected event
                sseEmitterManager.send(sessionId, "CONNECTED", Map.of(
                    "sessionId", sessionId,
                    "topics", topics
                ));
                kafkaLiveConsumer.startSession(sessionId, topics, fm, auditFocus);
            } catch (Exception e) {
                log.error("Error in live session {}: {}", sessionId, e.getMessage(), e);
                sseEmitterManager.complete(sessionId);
            }
        });

        return emitter;
    }

    /**
     * Renders the operator's audit selection into a prompt block: each chosen catalog
     * prompt as a bullet, followed by any free-form custom instruction. Returns {@code null}
     * when nothing was selected, so the analysis falls back to the generic prompt.
     */
    private String buildAuditFocus(List<String> auditPromptIds, String customAuditPrompt) {
        StringBuilder sb = new StringBuilder();
        for (AuditPrompt prompt : auditPromptCatalog.findByIds(auditPromptIds)) {
            sb.append("- [").append(prompt.name()).append("] ").append(prompt.prompt()).append("\n");
        }
        if (customAuditPrompt != null && !customAuditPrompt.isBlank()) {
            sb.append("- [Custom] ").append(customAuditPrompt.strip()).append("\n");
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void applyCorrections(Map<String, String> target, Map<String, Object> corrections,
                                   String fieldType) {
        Object correction = corrections.get(fieldType);
        if (correction instanceof Map<?, ?> correctionMap) {
            correctionMap.forEach((topic, path) -> {
                if (topic instanceof String t && path instanceof String p) {
                    target.put(t, p);
                }
            });
        }
    }
}
