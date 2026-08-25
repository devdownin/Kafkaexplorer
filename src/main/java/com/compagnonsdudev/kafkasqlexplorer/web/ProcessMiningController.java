// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.web;

import com.compagnonsdudev.kafkasqlexplorer.domain.FieldMappingValidation;
import com.compagnonsdudev.kafkasqlexplorer.domain.AnomalyReport;
import com.compagnonsdudev.kafkasqlexplorer.domain.AuditPrompt;
import com.compagnonsdudev.kafkasqlexplorer.domain.FieldMapping;
import com.compagnonsdudev.kafkasqlexplorer.domain.FieldProfileResult;
import com.compagnonsdudev.kafkasqlexplorer.domain.ProcessMiningResult;
import com.compagnonsdudev.kafkasqlexplorer.domain.SchemaUnificationProposal;
import com.compagnonsdudev.kafkasqlexplorer.domain.SnapshotConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.UnificationEntry;
import com.compagnonsdudev.kafkasqlexplorer.service.AuditPromptCatalog;
import com.compagnonsdudev.kafkasqlexplorer.service.FieldMappingStore;
import com.compagnonsdudev.kafkasqlexplorer.service.FieldProfilingService;
import com.compagnonsdudev.kafkasqlexplorer.service.KafkaLiveConsumer;
import com.compagnonsdudev.kafkasqlexplorer.service.LlmAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.compagnonsdudev.kafkasqlexplorer.service.SseEmitterManager;
import com.compagnonsdudev.kafkasqlexplorer.util.LogSafe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/process-mining")
public class ProcessMiningController {

    private static final Logger log = LoggerFactory.getLogger(ProcessMiningController.class);

    private final FieldProfilingService fieldProfilingService;
    private final LlmAnalysisService llmAnalysisService;
    private final KafkaLiveConsumer kafkaLiveConsumer;
    private final SseEmitterManager sseEmitterManager;
    private final AuditPromptCatalog auditPromptCatalog;

    /** Session ids are {@code UUID.randomUUID().toString()}; nothing else names a live session. */
    private static final Pattern SESSION_ID =
        Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * Validated field mappings. Held by a component rather than by this controller so the Metrics
     * suggestions can read one too — the mapping is where a topic's real correlation key lives,
     * and a controller field made it reachable from nowhere else. Bounded, which the map it
     * replaces was not.
     */
    private final FieldMappingStore fieldMappingStore;

    public ProcessMiningController(FieldProfilingService fieldProfilingService,
                                    LlmAnalysisService llmAnalysisService,
                                    KafkaLiveConsumer kafkaLiveConsumer,
                                    SseEmitterManager sseEmitterManager,
                                    AuditPromptCatalog auditPromptCatalog,
                                    FieldMappingStore fieldMappingStore) {
        this.fieldMappingStore = fieldMappingStore;
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

    /**
     * A request that names no topic is refused here rather than a model call later.
     *
     * <p>It used to reach {@code profile(null, …)}, where the read NPEs and the 500 that comes back
     * says nothing about the missing field. The refusal is served in the record's own shape — with
     * {@code error} set, which is exactly what the page already reads on both a 200 and a 4xx — so
     * a validation failure and a provider failure reach the same panel with the same wiring.
     */
    private static boolean namesNoTopic(List<String> topics) {
        return topics == null || topics.stream().noneMatch(t -> t != null && !t.isBlank());
    }

    private static final String NO_TOPIC = "Select at least one topic to analyse.";

    @PostMapping("/profiling/start")
    public ResponseEntity<FieldProfileResult> startProfiling(@RequestBody ProfilingRequest request) {
        if (namesNoTopic(request.topics())) {
            return ResponseEntity.badRequest().body(FieldProfileResult.failed(NO_TOPIC));
        }
        // Sanitised like every other topic name that reaches this log — a topic name arrives from
        // the request body, and a %0A in one forges whatever line the caller likes.
        log.info("Starting field profiling for topics: {}", LogSafe.names(request.topics()));
        SnapshotConfig depth = request.depth() != null
            ? request.depth()
            : SnapshotConfig.latestN(500);
        return ResponseEntity.ok(fieldProfilingService.profile(request.topics(), depth));
    }

    @PostMapping("/profiling/validate")
    public FieldMappingValidation validateSchema(@RequestBody ValidationRequest request) {
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

        fieldMappingStore.put(fieldMapping);
        log.info("Stored FieldMapping with id: {}", mappingId);

        return new FieldMappingValidation(mappingId);
    }

    @PostMapping("/snapshot")
    public ResponseEntity<ProcessMiningResult> analyzeSnapshot(@RequestBody SnapshotRequest request) {
        if (namesNoTopic(request.topics())) {
            return ResponseEntity.badRequest().body(ProcessMiningResult.failed(NO_TOPIC));
        }
        log.info("Starting snapshot analysis for topics: {}", LogSafe.names(request.topics()));

        FieldMapping fieldMapping = null;
        boolean mappingLost = false;
        if (request.fieldMappingId() != null) {
            fieldMapping = fieldMappingStore.find(request.fieldMappingId()).orElse(null);
            mappingLost = fieldMapping == null;
            if (mappingLost) {
                log.warn("FieldMapping not found for id: {}",
                    LogSafe.name(request.fieldMappingId()));
            }
        }

        SnapshotConfig depth = request.depth() != null
            ? request.depth()
            : SnapshotConfig.latestN(500);

        String auditFocus = buildAuditFocus(request.auditPromptIds(), request.customAuditPrompt());

        ProcessMiningResult result =
            llmAnalysisService.analyzeSnapshot(request.topics(), depth, fieldMapping, auditFocus);

        // The mapping is the whole point of the step before this one: it is what says which field
        // correlates a record across topics. Losing it — the store is bounded, and it is restored
        // from a topic that may not have been readable at boot — leaves the analysis running on
        // whatever the model infers from the payloads instead. That was said to the log and to
        // nobody else, so an operator who had just validated a mapping by hand had no way to know
        // it had not been applied.
        if (mappingLost) {
            result = result.withCoverageWarning(
                "The validated field mapping is no longer held by this server, so the analysis ran "
                + "without it — correlation across topics is the model's own inference. Re-run the "
                + "profiling step to rebuild it.");
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping(value = "/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter startLive(@RequestParam List<String> topics,
                                 @RequestParam String fieldMappingId,
                                 @RequestParam(required = false) List<String> auditPromptIds,
                                 @RequestParam(required = false) String customAuditPrompt) {
        String sessionId = UUID.randomUUID().toString();
        log.info("Starting live session {} for topics: {}",
            LogSafe.name(sessionId), LogSafe.names(topics));

        FieldMapping fieldMapping = fieldMappingStore.find(fieldMappingId).orElse(null);
        if (fieldMapping == null) {
            log.warn("FieldMapping not found for id: {} — proceeding without mapping",
                LogSafe.name(fieldMappingId));
        }

        SseEmitter emitter = sseEmitterManager.create(sessionId);

        final FieldMapping fm = fieldMapping;
        final String auditFocus = buildAuditFocus(auditPromptIds, customAuditPrompt);
        CompletableFuture.runAsync(() -> {
            try {
                // Send initial connected event. It carries whether the validated mapping was
                // actually applied: the store is bounded and restored best-effort at boot, so a
                // session can legitimately start without the mapping the operator validated — and
                // that changes what "correlated across topics" means for every window it will
                // produce. Said here rather than only to the log, which is where it used to stop.
                sseEmitterManager.send(sessionId, "CONNECTED", Map.of(
                    "sessionId", sessionId,
                    "topics", topics,
                    "fieldMappingApplied", fm != null
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
     * Ends a live session on request.
     *
     * <p>Stopping used to be a purely client-side gesture — the page closed its EventSource and the
     * server found out on its next heartbeat, up to fifteen seconds later, during which a Kafka
     * consumer kept polling and a window could still be sent to the model. The browser knows the
     * session id (the {@code CONNECTED} event carries it), so it can say so directly. Idempotent:
     * an unknown id is a session that already ended, which is the outcome asked for.
     *
     * <p>The id is validated before it is used or logged. Every session id is a server-minted
     * {@link UUID}, so anything else cannot name a live session and is refused rather than
     * sanitised — which also keeps an attacker-controlled string out of the log file entirely.
     * Logging it raw was a log-injection sink (CodeQL): a {@code %0A} in the path forges whatever
     * log line the caller likes, in a file that is meant to be the record of what happened.
     */
    @DeleteMapping("/live/{sessionId}")
    public ResponseEntity<Map<String, Object>> stopLive(
            // Named explicitly, like QueryController's params: the offline harness compiles with
            // plain javac, so without this the binding cannot be resolved by reflection.
            @PathVariable("sessionId") String sessionId) {
        if (!SESSION_ID.matcher(sessionId).matches()) {
            // Deliberately not echoed, neither to the log nor to the response body.
            log.warn("Ignoring a stop request carrying a malformed session id ({} chars)",
                sessionId.length());
            return ResponseEntity.badRequest()
                .body(Map.of("stopped", false, "message", "Malformed session id."));
        }

        // Reconstructed, not merely checked. What reaches the logger and the session map is a
        // canonical UUID rendered by the JDK — a value that cannot carry a line break by
        // construction — rather than the caller's string that happened to pass a guard. It costs
        // one parse and it means neither a reader nor a static analyser has to prove that the
        // branch above is airtight. It also normalises case, so an id that differs from the minted
        // one only by case now resolves instead of silently matching no session.
        String canonicalId = UUID.fromString(sessionId).toString();

        log.info("Stop requested for live session {}", canonicalId);
        kafkaLiveConsumer.stopSession(canonicalId);
        return ResponseEntity.ok(Map.of("sessionId", canonicalId, "stopped", true));
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
