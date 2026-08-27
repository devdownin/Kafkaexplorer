// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.AuditHistory;
import com.compagnonsdudev.kafkasqlexplorer.domain.AuditReport;
import com.compagnonsdudev.kafkasqlexplorer.domain.AuditRunSummary;
import com.compagnonsdudev.kafkasqlexplorer.domain.AuditStatus;
import com.compagnonsdudev.kafkasqlexplorer.domain.ConsumerGroupLag;
import com.compagnonsdudev.kafkasqlexplorer.domain.FlowAudit;
import com.compagnonsdudev.kafkasqlexplorer.domain.FieldMapping;
import com.compagnonsdudev.kafkasqlexplorer.domain.FlowChainEvidence;
import com.compagnonsdudev.kafkasqlexplorer.domain.HealthStatus;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricDataState;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricSuggestion;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricSuggestionRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricSuggestionSource;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricSuggestions;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricTemplateType;
import com.compagnonsdudev.kafkasqlexplorer.domain.ProcessModelEvidence;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicAudit;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicConsumers;
import com.compagnonsdudev.kafkasqlexplorer.domain.TopicIssue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.Set;

/**
 * Derives contextual KPIs from what this cluster has actually been observed to do.
 *
 * <p>The Metrics page could only ever offer the four generic examples — a COUNT on the first table
 * it found — because nothing in it knew anything about the cluster. Two features do: the cluster
 * audit (per-topic counts, findings, and the flows it reconstructs from naming conventions) and
 * Stream Flow (a key's real path across topics, with the latency of each hop). Both produce
 * measurements; a measurement is what turns "add a gauge" into "watch this hop, it took 812 ms
 * when it was traced".
 *
 * <p>Four rules hold this together, and each of them is the reason a line of this class looks the
 * way it does:
 *
 * <ol>
 *   <li><b>No suggestion without evidence.</b> Every proposal names the run it came from, the
 *       measurement it rests on and the scope of that measurement. A KPI nobody can trace back to
 *       an observation is the literal "99.98 % availability" tile the Metrics guide was stripped
 *       of — invented confidence on the page that teaches measurement.</li>
 *   <li><b>Thresholds are derived, and say from what.</b> Every threshold here is a multiple of
 *       something measured, carried in {@code thresholdBasis}. Where nothing was measured, the
 *       proposal comes with no threshold rather than a round number that looks considered.</li>
 *   <li><b>Nothing is created.</b> A suggestion is a pre-filled form. It is opened, previewed and
 *       saved by hand, because the SQL rests on an inferred key column and on an engine that may
 *       or may not run the aggregate — both stated as caveats.</li>
 *   <li><b>And the evidence has to still hold.</b> Everything above is a past observation — an
 *       audit read back from the history topic is weeks old, a trace and a measured process are
 *       kept in the browser for seven days — so before a card is offered the cluster is asked
 *       whether the topics it reads are still there and still hold anything. A topic that is gone
 *       drops its proposal, an empty one marks it, and a check that could not run changes nothing
 *       and says so. See {@link #verifyDataAvailable}; without it the panel proposed KPIs over
 *       deleted topics, with thresholds derived from counts retention had erased.</li>
 * </ol>
 *
 * <p>The audit is read from this process first and from {@code internal.audit.history} otherwise,
 * so the panel survives a restart. Traces are not stored server-side at all: the browser sends
 * back the chains it kept, so one generator answers for both families rather than the flow rule
 * living once in Java and once in TypeScript.
 */
@Service
public class MetricSuggestionService {

    private static final Logger log = LoggerFactory.getLogger(MetricSuggestionService.class);

    /** Enough to fill the panel; past this the reader stops reading and starts scrolling. */
    private static final int MAX_SUGGESTIONS = 24;

    /**
     * The window a proposed latency metric reads on both sides.
     *
     * <p>Fifteen minutes rather than an hour: the row cap is what actually bounds the read, and a
     * wider window on a busy topic only means the cap cuts inside it, which puts the two sides back
     * on two different stretches of time — the very thing the window is for. Wide enough that an
     * ordinary hop's traffic fits, narrow enough that the trailing edge is a small share of it.
     */
    private static final long SUGGESTED_LATENCY_WINDOW_MS = 900_000L;
    /** Volume KPIs are proposed for the busiest audited topics only — one per topic is plenty. */
    private static final int MAX_VOLUME_SUGGESTIONS = 3;
    /**
     * Topics whose consumer groups are read live to propose a delay-in-time KPI. Each costs a
     * coordinator round trip (cached 30 s), so the flagged topics are taken a few at a time.
     */
    private static final int MAX_TIME_LAG_TOPICS = 3;
    /**
     * Running jobs whose statement is resolved to find the pipeline edge it declares.
     *
     * <p>Resolving one is a Flink parse taken under the runtime's read lock, and this runs on
     * every load of the Metrics page — so it is bounded like every other family here rather than
     * scaling with whatever the cluster happens to be running. The cut is by start time, newest
     * first: a job started recently is the one an operator is most likely to be watching, and the
     * map {@code getActiveJobsDetails} hands back has no order of its own, so without a sort the
     * jobs that got read would vary between two calls.
     */
    private static final int MAX_LINEAGE_JOBS = 12;
    /**
     * Hop-latency KPIs proposed from one measured process. A directly-follows graph can carry
     * dozens of transitions; a panel of dozens of latency cards is a panel nobody reads.
     */
    private static final int MAX_MEASURED_TRANSITIONS = 6;
    /** Rework KPIs proposed from one measured process, worst first. */
    private static final int MAX_MEASURED_REPEATS = 3;
    /**
     * Below this many observations, the 95th percentile of a sample <em>is</em> its maximum.
     *
     * <p>Arithmetic rather than taste, which is why there is a constant here at all where this
     * class refuses round numbers everywhere else: with fewer than twenty values there is no
     * value strictly above the 95th percentile, so calling the figure a p95 would present the
     * worst hop observed as a tail estimate. The figure is used either way; only its name changes.
     */
    private static final int P95_MIN_OBSERVATIONS = 20;
    /** Column names that carry a business key, best first. */
    private static final List<String> KEY_COLUMN_CANDIDATES =
        List.of("id", "order_id", "event_id", "correlation_id", "transaction_id", "key", "uuid");
    /** Where the audit's own latency check gets its key when nothing better is known. */
    private static final String FALLBACK_KEY_COLUMN = "id";
    private static final String TIME_COLUMN = "event_time";

    private final AuditService auditService;
    private final AuditHistoryService auditHistoryService;
    private final MetricService metricService;
    private final FlinkSqlService flinkSqlService;
    private final KafkaAdminService kafkaAdminService;
    private final LineageService lineageService;
    private final FieldMappingStore fieldMappingStore;
    private final ExplorerConfig explorerConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MetricSuggestionService(AuditService auditService,
                                   AuditHistoryService auditHistoryService,
                                   MetricService metricService,
                                   FlinkSqlService flinkSqlService,
                                   KafkaAdminService kafkaAdminService,
                                   LineageService lineageService,
                                   FieldMappingStore fieldMappingStore,
                                   ExplorerConfig explorerConfig) {
        this.auditService = auditService;
        this.auditHistoryService = auditHistoryService;
        this.metricService = metricService;
        this.flinkSqlService = flinkSqlService;
        this.kafkaAdminService = kafkaAdminService;
        this.lineageService = lineageService;
        this.fieldMappingStore = fieldMappingStore;
        this.explorerConfig = explorerConfig;
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    public MetricSuggestions suggest(MetricSuggestionRequest request) {
        List<FlowChainEvidence> chains = request == null ? List.of() : request.chains();
        List<String> notes = new ArrayList<>();
        List<MetricSuggestion> suggestions = new ArrayList<>();

        // Resolved before anything else: a validated mapping knows a topic's real business key,
        // which every proposal below would otherwise assume. It improves the cards rather than
        // adding its own.
        FieldMapping mapping = resolveFieldMapping(request, notes);

        // The measured process goes first, and that ordering is the whole of the decision below:
        // `deduplicate` keeps the first proposal for a given (kind, topics), so whichever source
        // is built first wins a pair two sources both describe. It used to be the audit, on the
        // reasoning that its evidence was the broader of the two available. A directly-follows
        // graph is broader still — a p95 over every case the window held, grouped on a correlation
        // id an operator validated by hand, against one average over a flow reconstructed from
        // topic names. Where both have something to say about the same hop, the distribution is
        // the better card; where only the audit does, nothing changes.
        suggestions.addAll(fromMeasuredProcess(request, mapping, notes));

        AuditSnapshot audit = readAudit(notes);
        if (audit.report() != null) {
            suggestions.addAll(fromAudit(audit, mapping, notes));
        } else {
            notes.add("No cluster audit could be read — run one from the Audit page to unlock the "
                + "KPIs derived from topic volumes, findings and reconstructed flows.");
        }

        if (chains.isEmpty()) {
            notes.add("No Stream Flow trace was recorded in this browser — trace a message key to "
                + "unlock the KPIs derived from the path it actually travelled.");
        } else {
            suggestions.addAll(fromFlowChains(chains, mapping));
        }

        suggestions.addAll(fromLineage(notes));
        if (mapping != null) suggestions.addAll(fromFieldMapping(mapping, notes));

        // Deduplicate in source order, which encodes a deliberate precedence (the audit's card
        // wins over a trace describing the same pair). Then ask the cluster whether what is left
        // can still be measured — every proposal above rests on an observation that has already
        // aged, and one naming a deleted topic could only fail at every refresh. Only then mark,
        // rank and cut: both passes have to precede the cap, or a proposal an existing metric
        // already covers, or one over a topic that is gone, could take one of the slots and push
        // out a fresh one — the panel would then say "already measured" on a card that displaced
        // the suggestion the operator did not have.
        List<MetricSuggestion> ranked = new ArrayList<>(
            markAlreadyConfigured(verifyDataAvailable(deduplicate(suggestions), notes)));
        ranked.sort(BY_RELEVANCE);
        if (ranked.size() > MAX_SUGGESTIONS) {
            notes.add(describeTruncation(ranked.subList(MAX_SUGGESTIONS, ranked.size())));
            ranked = new ArrayList<>(ranked.subList(0, MAX_SUGGESTIONS));
        }
        List<MetricSuggestion> marked = ranked;
        return new MetricSuggestions(
            marked,
            audit.report() != null,
            audit.report() != null ? audit.report().auditId() : null,
            audit.timestamp(),
            audit.source(),
            audit.report() != null ? (int) audit.report().totalTopics() : 0,
            chains.size(),
            request != null && request.processModel() != null
                && !request.processModel().measuredTransitions().isEmpty(),
            notes
        );
    }

    // ── Evidence: the audit report ────────────────────────────────────────────

    private record AuditSnapshot(AuditReport report, Long timestamp, String source) {
        static AuditSnapshot none() { return new AuditSnapshot(null, null, null); }
    }

    /**
     * The most recent usable report: this process first, the history topic otherwise. A RUNNING
     * report is not usable — its topic list is whatever the scan has reached so far, and proposals
     * derived from it would change under the reader between two polls.
     */
    private AuditSnapshot readAudit(List<String> notes) {
        AuditReport current = auditService.getLastAuditReport();
        if (current != null && current.status() == AuditStatus.RUNNING) {
            notes.add("An audit is running — proposals are derived from the previous run, if any, "
                + "since a report in flight covers only the topics reached so far.");
            current = null;
        }
        if (current != null && current.status() != AuditStatus.FAILED) {
            return new AuditSnapshot(current, longStat(current, "timestamp"), "CURRENT_RUN");
        }
        return readAuditFromHistory(notes);
    }

    private AuditSnapshot readAuditFromHistory(List<String> notes) {
        try {
            AuditHistory history = auditHistoryService.listHistory();
            Optional<AuditRunSummary> newest = history.runs().stream()
                .filter(run -> !run.legacy())
                .filter(run -> !"FAILED".equals(run.status()))
                .findFirst();   // listHistory() answers newest first
            if (newest.isEmpty()) {
                if (!history.runs().isEmpty()) {
                    notes.add("The stored audit runs all predate graded severity or failed — their "
                        + "findings cannot be mapped onto today's scale, so nothing was derived "
                        + "from them. Run a fresh audit.");
                }
                return AuditSnapshot.none();
            }
            JsonNode stored = auditHistoryService.findReport(newest.get().auditId());
            if (stored == null) return AuditSnapshot.none();
            AuditReport report = objectMapper.treeToValue(stored, AuditReport.class);
            return new AuditSnapshot(report, newest.get().timestamp(), "HISTORY");
        } catch (Exception e) {
            // A report written by an older version can carry a shape this record cannot bind.
            // Saying so beats an empty panel that reads as "this cluster suggests nothing".
            log.debug("Stored audit report could not be read for suggestions: {}", e.toString());
            notes.add("A stored audit report was found but could not be read in the current "
                + "report shape (" + shortReason(e) + ") — run a fresh audit to derive proposals.");
            return AuditSnapshot.none();
        }
    }

    // ── Audit-derived proposals ───────────────────────────────────────────────

    private List<MetricSuggestion> fromAudit(AuditSnapshot audit, FieldMapping mapping, List<String> notes) {
        AuditReport report = audit.report();
        String run = describeRun(audit);
        List<MetricSuggestion> suggestions = new ArrayList<>();

        for (FlowAudit flow : nullSafe(report.flowAudits())) {
            List<FlowAudit.StepInfo> steps = nullSafe(flow.steps());
            for (int i = 1; i < steps.size(); i++) {
                FlowAudit.StepInfo from = steps.get(i - 1);
                FlowAudit.StepInfo to = steps.get(i);
                transitLatencyFromAudit(flow.flowName(), from, to, run, mapping).ifPresent(suggestions::add);
                throughputGap(flow.flowName(), from, to, run).ifPresent(suggestions::add);
            }
        }

        for (TopicAudit topic : nullSafe(report.topicAudits())) {
            if (topic.duplicateCount() > 0) {
                duplicateKpi(topic, run, mapping).ifPresent(suggestions::add);
            }
        }

        nullSafe(report.topicAudits()).stream()
            .filter(topic -> topic.messageCount() > 0)
            // Les topics que l'application s'écrit à elle-même, quel que soit leur préfixe :
            // le littéral « internal. » cessait d'être le marqueur dès qu'on en configurait un.
            .filter(topic -> !explorerConfig.isInternalTopic(topic.name()))
            .sorted(Comparator.comparingLong(TopicAudit::messageCount).reversed())
            .limit(MAX_VOLUME_SUGGESTIONS)
            .forEach(topic -> volumeKpi(topic, run).ifPresent(suggestions::add));

        suggestions.addAll(timeLagKpis(report, run, notes));

        lagNote(report).ifPresent(notes::add);
        poisonNote(report).ifPresent(notes::add);
        return suggestions;
    }

    /**
     * Watch a hop the audit already timed. The audit matches the two topics on their {@code id}
     * field and compares Kafka record timestamps; the metric template does the same thing on a
     * schedule, which is why the proposed SQL projects a key column and {@code event_time}.
     */
    private Optional<MetricSuggestion> transitLatencyFromAudit(String flowName,
                                                              FlowAudit.StepInfo from,
                                                              FlowAudit.StepInfo to,
                                                              String run,
                                                              FieldMapping mapping) {
        Long measured = to.averageLatencyMs();
        if (measured == null) return Optional.empty();   // the audit could not correlate the pair

        String evidence = run + " measured an average hop of " + formatMillis(measured)
            + " between " + from.topicName() + " and " + to.topicName()
            + " (flow \"" + flowName + "\", correlated on the id field of recent messages).";
        return Optional.of(transitLatency(
            "audit:hop-latency:" + from.topicName() + ">" + to.topicName(),
            MetricSuggestionSource.AUDIT,
            from.topicName(), to.topicName(), measured, mapping,
            "Processing latency " + from.topicName() + " → " + to.topicName(),
            "This hop is on a flow the audit reconstructed, and it is where a stalled consumer or a "
                + "slow enrichment shows up first — as time, before it shows up as a backlog.",
            List.of(evidence)));
    }

    /** The same KPI, from a chain the operator traced by hand rather than from a naming convention. */
    private Optional<MetricSuggestion> transitLatencyFromChain(FlowChainEvidence chain,
                                                               FlowChainEvidence.FlowChainHop from,
                                                               FlowChainEvidence.FlowChainHop to,
                                                               FieldMapping mapping) {
        Long measured = to.latencyFromPreviousMs();
        if (measured == null) return Optional.empty();

        StringBuilder evidence = new StringBuilder("The trace of key ")
            .append(chain.messageKey() == null ? "(unnamed)" : chain.messageKey());
        if (chain.tracedAt() != null) evidence.append(" on ").append(formatDate(chain.tracedAt()));
        evidence.append(" travelled ").append(from.topic()).append(" → ").append(to.topic());
        evidence.append(measured < 0
            ? ", with a hop of " + formatMillis(measured) + " — the timestamps go backwards, which "
              + "is producer clock skew rather than a measurement of the hop"
            : " in " + formatMillis(measured));
        evidence.append(" (one key, one trace — a single observation, not a distribution).");

        // A negative hop is evidence of skew, not a latency to threshold against: propose the KPI
        // so it can be watched over many keys, but with no threshold derived from that number.
        long basis = measured < 0 ? -1 : measured;
        return Optional.of(transitLatency(
            "flow:hop-latency:" + from.topic() + ">" + to.topic(),
            MetricSuggestionSource.STREAM_FLOW,
            from.topic(), to.topic(), basis, mapping,
            "Processing latency " + from.topic() + " → " + to.topic(),
            "A key was traced across this hop, so the pair is real rather than inferred from a "
                + "naming convention — measuring it continuously turns one observation into a trend.",
            List.of(evidence.toString())));
    }

    private MetricSuggestion transitLatency(String id, MetricSuggestionSource source,
                                            String sourceTopic, String targetTopic,
                                            long measuredMs, FieldMapping mapping, String title,
                                            String rationale, List<String> evidence) {
        return transitLatency(id, source, sourceTopic, targetTopic, measuredMs,
            "that was measured", mapping, title, rationale, evidence);
    }

    /**
     * @param measuredLabel what the figure the thresholds are multiples of actually is. An average
     *                      over a reconstructed flow and a p95 over four hundred cases are both
     *                      "measured", and a threshold that does not say which of the two it rests
     *                      on is a threshold nobody can argue with.
     */
    private MetricSuggestion transitLatency(String id, MetricSuggestionSource source,
                                            String sourceTopic, String targetTopic,
                                            long measuredMs, String measuredLabel,
                                            FieldMapping mapping, String title,
                                            String rationale, List<String> evidence) {
        KeyColumn sourceKey = keyColumn(sourceTopic, mapping);
        KeyColumn targetKey = keyColumn(targetTopic, mapping);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sourceSql", correlationSql(sourceTopic, sourceKey.column()));
        params.put("targetSql", correlationSql(targetTopic, targetKey.column()));
        params.put("sourceTopic", sourceTopic);
        params.put("targetTopic", targetTopic);
        /*
         * Both sides over the same window, because the number this card carries is a match rate as
         * much as a latency. Bounded by row count alone, two topics of different throughputs are
         * read over two different stretches of time, and the rate is then depressed by that
         * misalignment as much as by a real loss — which is the reading this panel exists to keep
         * honest. The SQL is the explorer's own single-table shape, so the direct reader can honour
         * it; a hand-edited join in the modal is refused at save with the reason.
         */
        params.put("windowMs", SUGGESTED_LATENCY_WINDOW_MS);

        // 2× / 4× of what was measured: high enough that ordinary variation does not page anyone,
        // low enough that a hop taking four times as long is not "normal". Both are stated.
        Double warning  = measuredMs > 0 ? Math.ceil(measuredMs * 2.0) : null;
        Double critical = measuredMs > 0 ? Math.ceil(measuredMs * 4.0) : null;
        String basis = measuredMs > 0
            ? "Warning at 2× and critical at 4× the " + formatMillis(measuredMs)
              + " " + measuredLabel + " — a multiple of an observation, not a round number."
            : null;

        List<String> caveats = new ArrayList<>();
        caveats.add("Correlation matches " + sourceKey.describe(sourceTopic)
            + " against " + targetKey.describe(targetTopic)
            + " — check both in the preview before saving.");
        caveats.add("event_time is the Kafka record timestamp of the table the explorer registers; "
            + "on a table you declared yourself, point it at the column that carries the event's time.");
        if (measuredMs <= 0) {
            caveats.add("No usable latency was measured for this hop, so no threshold is proposed — "
                + "run the metric for a while and set one from what it reports.");
        }
        caveats.add("Both sides are read over the same " + formatMillis(SUGGESTED_LATENCY_WINDOW_MS)
            + ", so the match rate reports real losses rather than two topics read over two "
            + "different stretches of time. A source produced near the end of that window has its "
            + "target after it, so the rate understates by about one hop's worth of traffic.");

        MetricConfig metric = new MetricConfig(
            null,
            metricName(source, "latency", sourceTopic, targetTopic),
            "GAUGE",
            null,
            "Average latency between " + sourceTopic + " and " + targetTopic
                + ", correlated on " + sourceKey.column() + ". Proposed from "
                + switch (source) {
                    case AUDIT -> "a cluster audit.";
                    case PROCESS_MINING -> "a measured process.";
                    case STREAM_FLOW, LINEAGE -> "a Stream Flow trace.";
                },
            warning, critical, null, null, null,
            List.of(), Map.of(), null,
            MetricTemplateType.TOPIC_TRANSIT_LATENCY.name(), params,
            "TEMPLATE_BOUNDED_SCAN", sourceTopic, List.of());

        return new MetricSuggestion(id, source, title, rationale, evidence, basis, caveats,
            false, null, metric);
    }

    /**
     * How much of what entered a step comes out of the next one. The audit reports the ratio once;
     * as a metric it is the "silent drop" alarm — the one thing a count on either topic alone
     * cannot see.
     */
    private Optional<MetricSuggestion> throughputGap(String flowName,
                                                     FlowAudit.StepInfo from,
                                                     FlowAudit.StepInfo to,
                                                     String run) {
        if (from.count() <= 0) return Optional.empty();

        double measuredGap = Math.max(0.0, 100.0 - to.throughputPercentage());
        // Warning at twice the observed gap, critical at four times, with floors so a flow that was
        // lossless when audited still gets a threshold that means something.
        double warning  = Math.min(100.0, Math.max(measuredGap * 2.0, 1.0));
        double critical = Math.min(100.0, Math.max(measuredGap * 4.0, 5.0));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("leftSql", countSql(from.topicName()));
        params.put("rightSql", countSql(to.topicName()));
        params.put("operation", "PERCENT_GAP");
        // Offsets rather than a scan, and the interval rather than the lifetime: see
        // COUNT_MODES and COUNT_WINDOWS in MetricService. Every card this panel builds is a
        // plain whole-topic gap between two named topics, which is the shape offsets answer
        // exactly — and the shape a lifetime total desensitises as history accumulates.
        params.put("countBy", "OFFSETS");
        params.put("window", "SINCE_LAST_REFRESH");
        params.put("leftTopic", from.topicName());
        params.put("rightTopic", to.topicName());

        MetricConfig metric = new MetricConfig(
            null,
            metricName(MetricSuggestionSource.AUDIT, "gap", from.topicName(), to.topicName()),
            "GAUGE",
            null,
            "Percentage gap between the record counts of " + from.topicName() + " and "
                + to.topicName() + " — a silent drop between two steps of the same flow.",
            warning, critical, null, null, null,
            List.of(), Map.of(), null,
            MetricTemplateType.TOPIC_COUNT_DELTA.name(), params,
            "TEMPLATE_BOUNDED_SCAN", from.topicName(), List.of());

        String evidence = run + " counted " + from.count() + " record(s) in " + from.topicName()
            + " and " + to.count() + " in " + to.topicName() + " — "
            + formatPercent(to.throughputPercentage()) + " carried through (flow \"" + flowName + "\").";

        return Optional.of(new MetricSuggestion(
            "audit:flow-gap:" + from.topicName() + ">" + to.topicName(),
            MetricSuggestionSource.AUDIT,
            "Throughput gap " + from.topicName() + " → " + to.topicName(),
            "Both topics are counted anyway; the difference between them is the KPI, and it is the "
                + "one number that says whether the step is losing events rather than merely slow.",
            List.of(evidence),
            "Warning at 2× and critical at 4× the " + formatPercent(measuredGap)
                + " gap observed, floored at 1 % / 5 % so a lossless flow still has a threshold.",
            List.of("Both sides are counted from the log's offsets, so no record is read and no "
                + "scan ceiling applies — but that counts what was produced, so a transaction "
                + "marker counts and a compacted record still counts.",
                    "Compared over each refresh interval rather than over the lifetime totals, "
                + "which is what lets a threshold fire: the first refresh publishes nothing and "
                + "says so.",
                    "A legitimate filter between the two steps shows up here as a permanent gap — "
                + "set the thresholds around the level it normally sits at."),
            false, null, metric));
    }

    private Optional<MetricSuggestion> duplicateKpi(TopicAudit topic, String run, FieldMapping mapping) {
        KeyColumn key = keyColumn(topic.name(), mapping);
        String table = DdlGeneratorService.toTableName(topic.name());
        String sql = "SELECT COUNT(*) - COUNT(DISTINCT `" + key.column() + "`) AS metric_value\n"
            + "FROM " + table;

        double measured = topic.duplicateCount();
        MetricConfig metric = new MetricConfig(
            null,
            "gauge_duplicates_" + DdlGeneratorService.toTableName(topic.name()),
            "GAUGE",
            sql,
            "Records in " + topic.name() + " sharing a " + key.column()
                + " with another record — redeliveries, or a producer retry that is not idempotent.",
            Math.max(1.0, measured), Math.max(2.0, measured * 2.0), null, null, null,
            List.of(), Map.of(), null,
            MetricTemplateType.RAW_SQL.name(), Map.of(),
            "SQL", topic.name(), List.of());

        String evidence = run + " found " + topic.duplicateCount() + " duplicate key(s) in "
            + topic.name() + " over the messages it scanned.";

        return Optional.of(new MetricSuggestion(
            "audit:duplicates:" + topic.name(),
            MetricSuggestionSource.AUDIT,
            "Duplicate keys in " + topic.name(),
            "The audit found duplicates here once. Whether that was a one-off redelivery or a "
                + "producer that retries without idempotence is a question only a continuous "
                + "measurement answers.",
            List.of(evidence),
            "Warning at the " + topic.duplicateCount() + " already observed and critical at twice "
                + "that — the level that was reached once is the level worth hearing about again.",
            List.of("COUNT(DISTINCT …) needs the Flink planner; if the query falls back to the "
                + "direct engine the metric reports the engine's own error rather than a number.",
                    "Counted over the whole bounded scan, where the audit counted over a sample — "
                + "the two figures are not directly comparable, only their movement is."),
            false, null, metric));
    }

    private Optional<MetricSuggestion> volumeKpi(TopicAudit topic, String run) {
        String table = DdlGeneratorService.toTableName(topic.name());
        long measured = topic.messageCount();

        MetricConfig metric = new MetricConfig(
            null,
            "gauge_volume_" + table,
            "GAUGE",
            countSql(topic.name()),
            "Records currently readable in " + topic.name()
                + " — the busiest topic(s) of the last audit.",
            Math.ceil(measured * 1.5), Math.ceil(measured * 2.0), null, null, null,
            List.of(), Map.of(), null,
            MetricTemplateType.RAW_SQL.name(), Map.of(),
            "SQL", topic.name(), List.of());

        String evidence = run + " counted " + measured + " record(s) in " + topic.name()
            + (topic.healthStatus() != null && topic.healthStatus() != HealthStatus.HEALTHY
                ? ", and graded the topic " + topic.healthStatus() + "."
                : ".");

        return Optional.of(new MetricSuggestion(
            "audit:volume:" + topic.name(),
            MetricSuggestionSource.AUDIT,
            "Volume of " + topic.name(),
            "This is where the cluster's traffic actually is. A count that stops moving on the "
                + "busiest topic is the earliest sign of a producer that has gone quiet.",
            List.of(evidence),
            "Warning at 1.5× and critical at 2× the " + measured
                + " counted by that run — growth relative to a measured baseline, not an absolute.",
            List.of("A bounded scan of the whole topic: on a topic with retention this counts what "
                + "is still readable, not what was ever produced."),
            false, null, metric));
    }

    /**
     * The topics the audit reported a consumer-lag finding on. The group ids are deliberately not
     * parsed out of the finding text — a message is prose, and reading a name out of it is the
     * lexical dependency this codebase keeps removing; they are read from the cluster instead.
     */
    private List<String> topicsWithLagFindings(AuditReport report) {
        return nullSafe(report.topicAudits()).stream()
            .filter(topic -> nullSafe(topic.issues()).stream()
                .map(TopicIssue::message)
                .anyMatch(MetricSuggestionService::isConsumerFinding))
            .map(TopicAudit::name)
            .toList();
    }

    /**
     * Whether a finding is about consumers at all.
     *
     * <p>Lexical, because {@link TopicIssue} carries prose and nothing else — but bounded on
     * purpose: it decides only <em>which topics to look at</em>, and everything the proposal
     * asserts (the group, its backlog) is then read from the cluster. Matching on "consumer
     * group", the phrase every one of {@code AuditService.consumerLagIssues}'s messages is built
     * from, rather than on "lag", which three of the four do not contain.
     */
    private static boolean isConsumerFinding(String message) {
        return message != null && message.toLowerCase(Locale.ROOT).contains("consumer group");
    }

    /**
     * A backlog measured in records is not actionable on its own, and this is the KPI that makes
     * it so.
     *
     * <p>The audit says "4 000 messages behind". Whether that is four seconds of traffic or four
     * days of it is the difference between a graph nobody looks at and an incident, and no count
     * can settle it — hence a metric whose unit is time. Its value comes from the committed offset
     * and the timestamp of the record sitting there, which is why it is the one template that runs
     * no SQL: neither number is in the topic's payloads.
     *
     * <p>The group is read from the cluster rather than parsed out of the audit's wording, and it
     * is pinned into the metric: "the worst group of this topic" would move between refreshes, so
     * the series would change subject without saying so.
     */
    private List<MetricSuggestion> timeLagKpis(AuditReport report, String run, List<String> notes) {
        List<String> topics = topicsWithLagFindings(report);
        if (topics.isEmpty()) return List.of();

        List<MetricSuggestion> suggestions = new ArrayList<>();
        List<String> unread = new ArrayList<>();
        for (String topic : topics.stream().limit(MAX_TIME_LAG_TOPICS).toList()) {
            TopicConsumers consumers;
            try {
                consumers = kafkaAdminService.getTopicConsumers(topic, explorerConfig.getConsumerGroupMaxGroups());
            } catch (Exception e) {
                log.debug("Consumers of {} could not be read while suggesting metrics: {}", topic, e.toString());
                unread.add(topic);
                continue;
            }
            if (!consumers.available()) {
                unread.add(topic);
                continue;
            }
            // The group carrying the largest readable backlog: the one the finding is about, and
            // the one whose delay is worth a series. A group with no lag needs no KPI here.
            Optional<ConsumerGroupLag> worst = nullSafe(consumers.groups()).stream()
                .filter(group -> group.error() == null)
                .filter(group -> group.totalLag() > 0)
                .max(Comparator.comparingLong(ConsumerGroupLag::totalLag));
            if (worst.isEmpty()) continue;

            suggestions.add(timeLagKpi(topic, worst.get(), report, run));
        }

        if (!unread.isEmpty()) {
            notes.add("The consumer groups of " + String.join(", ", unread) + " could not be read "
                + "just now, so no delay-in-time KPI is proposed for them — the group a metric "
                + "measures has to be named, and guessing it from the audit's wording is not naming it.");
        }
        return suggestions;
    }

    private MetricSuggestion timeLagKpi(String topic, ConsumerGroupLag group, AuditReport report, String run) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("topic", topic);
        params.put("group", group.groupId());
        params.put("aggregation", "MAX");

        MetricConfig metric = new MetricConfig(
            null,
            "gauge_time_lag_" + DdlGeneratorService.toTableName(topic) + "_" + sanitize(group.groupId()),
            "GAUGE",
            null,
            "Age in milliseconds of the oldest message '" + group.groupId() + "' has not read on "
                + topic + " — the worst of its partitions. A backlog in time, where the exported "
                + "kafka_consumer_group_lag is the same backlog in records.",
            null, null, null, null, null,
            List.of(), Map.of(), null,
            MetricTemplateType.CONSUMER_TIME_LAG.name(), params,
            "TEMPLATE_BOUNDED_SCAN", topic, List.of());

        List<String> evidence = new ArrayList<>();
        auditFinding(report, topic).ifPresent(finding -> evidence.add(run + " reported on " + topic + ": " + finding));
        evidence.add("Read just now: group '" + group.groupId() + "' is " + group.totalLag()
            + " record(s) behind on " + topic
            + (group.partitionsWithoutCommit() > 0
                ? ", and has never committed on " + group.partitionsWithoutCommit()
                  + " partition(s) whose backlog that number does not count."
                : ".")
            + " How long that represents is exactly what this metric measures, and nothing here knows it yet.");

        return new MetricSuggestion(
            "audit:time-lag:" + topic + ">" + group.groupId(),
            MetricSuggestionSource.AUDIT,
            "Delay in time of " + group.groupId() + " on " + topic,
            "A record count says how much is waiting, never for how long — the same 4 000 messages "
                + "are seconds on one topic and days on another. This is the backlog in the unit an "
                + "operator can act on.",
            evidence,
            // Nothing has been measured in time on this topic, so any threshold would be the round
            // number this whole panel exists not to print. It is set from the metric's own first
            // readings — which is precisely the point of running it.
            null,
            List.of("The value is the age of the oldest unread message on the worst partition, "
                + "measured against the moment of the read — a producer that stops leaves its "
                + "backlog ageing, which is the intended reading.",
                    "Each refresh reads one record per lagging partition, so this costs more than a "
                + "SQL count — it is bounded to 64 partitions and an 8 s budget, and a partition "
                + "that could not be read is reported as unknown rather than as zero.",
                    "No threshold is proposed: nothing here has ever measured this topic in time. "
                + "Run it, look at what it reports, then set one."),
            false, null, metric);
    }

    /** The audit's own wording about a topic, when it said something about lag. */
    private Optional<String> auditFinding(AuditReport report, String topic) {
        return nullSafe(report.topicAudits()).stream()
            .filter(audit -> topic.equals(audit.name()))
            .flatMap(audit -> nullSafe(audit.issues()).stream())
            .map(TopicIssue::message)
            .filter(MetricSuggestionService::isConsumerFinding)
            .findFirst();
    }

    /**
     * Count-lag findings stay a note beside the KPI above: the count is already exported by
     * {@link ConsumerLagMetrics} straight from committed offsets, and a SQL metric for it would be
     * a second, worse answer to a question that already has one.
     */
    private Optional<String> lagNote(AuditReport report) {
        List<String> topics = topicsWithLagFindings(report).stream().limit(5).toList();
        if (topics.isEmpty()) return Optional.empty();
        return Optional.of("The audit reported consumer-lag findings on " + String.join(", ", topics)
            + ". The backlog in *records* is not proposed as a SQL metric — it is exported directly "
            + "from committed offsets: name those topics in explorer.lag-metrics-topics and "
            + "Prometheus gets kafka_consumer_group_lag for them. The delay in *time* is what the "
            + "proposed KPI above adds, since no count can be read as a duration.");
    }

    /** A Prometheus-safe fragment of a group id, for a metric name. */
    private String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private Optional<String> poisonNote(AuditReport report) {
        long topics = nullSafe(report.topicAudits()).stream()
            .filter(topic -> topic.poisonMessageCount() > 0)
            .count();
        if (topics == 0) return Optional.empty();
        return Optional.of(topics + " topic(s) carry unparseable payloads. No KPI is proposed for "
            + "that: a parse failure is not something SQL can count — the query engine skips or "
            + "fails on the record rather than reporting it. The audit is the measurement here.");
    }

    // ── Measured-process proposals ────────────────────────────────────────────

    /**
     * KPIs derived from the directly-follows graph a Process Mining run measured.
     *
     * <p>This is the best evidence the application has about a pipeline, and until now nothing
     * read it. The audit groups topics by their names; a trace follows one key; here every record
     * of the window was grouped by a correlation id an operator validated, and each transition
     * carries a distribution rather than a single figure. A latency threshold wants exactly that.
     *
     * <p>Two families come out of it, and only two. A transition between two <em>topics</em>
     * becomes a hop-latency KPI; a transition inside one topic (which is what a mapped status
     * produces) has no pair to correlate and is counted in a note instead of being turned into a
     * query that would compare a topic with itself. An activity a case visited twice becomes a
     * rework KPI — deliberately with no threshold, see {@link #reworkKpi}.
     */
    private List<MetricSuggestion> fromMeasuredProcess(MetricSuggestionRequest request,
                                                       FieldMapping mapping,
                                                       List<String> notes) {
        ProcessModelEvidence measured = request == null ? null : request.processModel();
        if (measured == null || measured.measuredTransitions().isEmpty()) {
            notes.add("No measured process was recorded in this browser — run a Process Mining "
                + "analysis to unlock the KPIs derived from the transitions it counts, which are "
                + "the only ones here whose thresholds rest on a distribution rather than on a "
                + "single observation.");
            return List.of();
        }

        List<MetricSuggestion> suggestions = new ArrayList<>();
        List<ProcessModelEvidence.MeasuredTransition> crossingTopics = new ArrayList<>();
        int withinTopic = 0;
        for (ProcessModelEvidence.MeasuredTransition edge : measured.measuredTransitions()) {
            if (edge == null || edge.from() == null || edge.to() == null) continue;
            String from = ProcessModelBuilder.topicOf(edge.from());
            String to = ProcessModelBuilder.topicOf(edge.to());
            if (from == null || from.isBlank() || to == null || to.isBlank()) continue;
            if (from.equals(to)) {
                withinTopic++;
                continue;
            }
            if (edge.p95Ms() == null || edge.cases() == null || edge.cases() < 1) continue;
            crossingTopics.add(edge);
        }

        // Cut by what is worst rather than by what is most frequent: the transitions arrive most
        // frequent first, which is the right order for reading a graph and the wrong one for
        // choosing what to watch. Ties fall back to the case count and then to the labels, so two
        // identical measurements produce the same cards in the same order — a browser-side
        // dismissal is keyed on the id.
        crossingTopics.sort(Comparator
            .comparingLong((ProcessModelEvidence.MeasuredTransition e) -> e.p95Ms()).reversed()
            .thenComparing(Comparator.comparingInt(
                (ProcessModelEvidence.MeasuredTransition e) -> e.cases()).reversed())
            .thenComparing(ProcessModelEvidence.MeasuredTransition::from)
            .thenComparing(ProcessModelEvidence.MeasuredTransition::to));

        if (crossingTopics.size() > MAX_MEASURED_TRANSITIONS) {
            notes.add("The measured process has " + crossingTopics.size() + " transitions between "
                + "topics; the " + MAX_MEASURED_TRANSITIONS + " slowest are proposed here. The rest "
                + "are on the Process Mining page, with their latencies.");
            crossingTopics = new ArrayList<>(crossingTopics.subList(0, MAX_MEASURED_TRANSITIONS));
        }
        for (ProcessModelEvidence.MeasuredTransition edge : crossingTopics) {
            suggestions.add(measuredHopLatency(measured, edge, mapping));
        }
        if (withinTopic > 0) {
            notes.add(withinTopic + " measured transition(s) stay inside one topic — a status "
                + "moving, rather than a record travelling. No KPI is proposed for those: the "
                + "latency template correlates two topics, and pointing it at one would compare a "
                + "topic with itself.");
        }

        suggestions.addAll(fromMeasuredRepeats(measured, mapping));
        return suggestions;
    }

    /** One hop of the measured process, with thresholds taken from its own distribution. */
    private MetricSuggestion measuredHopLatency(ProcessModelEvidence measured,
                                                ProcessModelEvidence.MeasuredTransition edge,
                                                FieldMapping mapping) {
        String from = ProcessModelBuilder.topicOf(edge.from());
        String to = ProcessModelBuilder.topicOf(edge.to());
        int cases = edge.cases();

        // Below twenty observations the 95th percentile *is* the maximum — that is arithmetic, not
        // a judgement, and it is the one line that stops "p95" being read as a tail estimate when
        // it is the worst of nine hops. The figure is unchanged; only what it is called.
        boolean quantile = cases >= P95_MIN_OBSERVATIONS;
        String measuredLabel = quantile
            ? "measured at the 95th percentile of " + cases + " cases"
            : "which is the worst of the " + cases + " case(s) observed rather than a percentile — "
              + "below " + P95_MIN_OBSERVATIONS + " observations the two are the same number";

        StringBuilder evidence = new StringBuilder("A Process Mining run");
        if (measured.measuredAt() != null) {
            evidence.append(" on ").append(formatDate(measured.measuredAt()));
        }
        evidence.append(" counted ").append(cases).append(" case(s) through ")
            .append(edge.from()).append(" → ").append(edge.to()).append(": ");
        if (edge.p50Ms() != null) evidence.append("median ").append(formatMillis(edge.p50Ms())).append(", ");
        evidence.append(quantile ? "p95 " : "worst ").append(formatMillis(edge.p95Ms()));
        if (edge.maxMs() != null && quantile) {
            evidence.append(", worst ").append(formatMillis(edge.maxMs()));
        }
        evidence.append(" — counted over every record the window held, not over a sample.");

        List<String> lines = new ArrayList<>();
        lines.add(evidence.toString());
        String clock = describeMeasuredClock(measured);
        if (clock != null) lines.add(clock);

        return transitLatency(
            MetricCandidates.hopLatencyId(from, to),
            MetricSuggestionSource.PROCESS_MINING,
            from, to, edge.p95Ms(), measuredLabel, mapping,
            "Processing latency " + from + " → " + to,
            "This transition was measured across every case in the window, so the threshold below "
                + "rests on a distribution rather than on one observation — which is what makes it "
                + "arguable rather than merely plausible.",
            lines);
    }

    /**
     * What the latencies were measured on. A business timestamp says when the process happened; the
     * Kafka record timestamp says when the message was produced, and they differ by exactly what
     * makes a latency finding interesting or meaningless. Stated, never assumed — the same rule the
     * Process Mining panel follows.
     */
    private String describeMeasuredClock(ProcessModelEvidence measured) {
        String source = measured.eventTimeSource();
        if (source == null) return null;
        return switch (source) {
            case "MAPPED_FIELD" -> null;   // the nominal case says nothing worth a line
            case "MIXED" -> "Some events had no resolvable business timestamp and fell back to the "
                + "Kafka record timestamp, so a few of the measured latencies mix event time with "
                + "produce time.";
            case "RECORD_TIMESTAMP" -> "The log was ordered by the Kafka record timestamp rather "
                + "than a business one, so what was measured is transport delay between stages and "
                + "not necessarily the process's own duration.";
            default -> null;
        };
    }

    /** One rework KPI per topic a case was seen visiting twice, worst first. */
    private List<MetricSuggestion> fromMeasuredRepeats(ProcessModelEvidence measured,
                                                       FieldMapping mapping) {
        // A topic with several mapped statuses can repeat on more than one of them; they describe
        // one topic, and the query below is per topic, so the worst of them is what is carried.
        Map<String, ProcessModelEvidence.MeasuredRepeat> worstByTopic = new LinkedHashMap<>();
        for (ProcessModelEvidence.MeasuredRepeat repeat : measured.measuredRepeats()) {
            if (repeat == null || repeat.activity() == null) continue;
            if (repeat.casesAffected() == null || repeat.casesAffected() < 1) continue;
            String topic = ProcessModelBuilder.topicOf(repeat.activity());
            if (topic == null || topic.isBlank()) continue;
            worstByTopic.merge(topic, repeat,
                (a, b) -> a.casesAffected() >= b.casesAffected() ? a : b);
        }

        return worstByTopic.entrySet().stream()
            .sorted(Comparator
                .comparingInt((Map.Entry<String, ProcessModelEvidence.MeasuredRepeat> e)
                    -> e.getValue().casesAffected()).reversed()
                .thenComparing(Map.Entry::getKey))
            .limit(MAX_MEASURED_REPEATS)
            .map(e -> reworkKpi(measured, e.getKey(), e.getValue(), mapping))
            .toList();
    }

    /**
     * A case that visited one activity twice: a redelivery, a producer retry that is not
     * idempotent, or a legitimate rework loop. Which of the three it is depends on the business,
     * so the card measures it rather than naming it.
     *
     * <p><b>Deliberately without a threshold</b>, and that is the only interesting decision here.
     * The measurement counts <em>cases</em> that revisited an activity inside one window; the query
     * counts <em>keys</em> sharing the topic over a bounded scan. The two are related and are not
     * the same number, so a warning derived from one and applied to the other would be a figure
     * that looks derived and is not — which is exactly what this panel refuses to print.
     */
    private MetricSuggestion reworkKpi(ProcessModelEvidence measured, String topic,
                                       ProcessModelEvidence.MeasuredRepeat repeat,
                                       FieldMapping mapping) {
        KeyColumn key = keyColumn(topic, mapping);
        String table = DdlGeneratorService.toTableName(topic);
        String sql = "SELECT COUNT(*) - COUNT(DISTINCT `" + key.column() + "`) AS metric_value\n"
            + "FROM " + table;

        MetricConfig metric = new MetricConfig(
            null,
            "gauge_measured_rework_" + table,
            "GAUGE",
            sql,
            "Records of " + topic + " sharing a " + key.column() + " with another record. Proposed "
                + "from a measured process, where " + repeat.casesAffected()
                + " case(s) passed through this step more than once.",
            null, null, null, null, null,
            List.of(), Map.of(), null,
            MetricTemplateType.RAW_SQL.name(), Map.of(),
            "SQL", topic, List.of());

        StringBuilder evidence = new StringBuilder("A Process Mining run");
        if (measured.measuredAt() != null) {
            evidence.append(" on ").append(formatDate(measured.measuredAt()));
        }
        evidence.append(" saw ").append(repeat.casesAffected())
            .append(" case(s) visit ").append(repeat.activity()).append(" more than once");
        if (repeat.maxOccurrencesInOneCase() != null && repeat.maxOccurrencesInOneCase() > 1) {
            evidence.append(", up to ").append(repeat.maxOccurrencesInOneCase())
                .append(" times for a single case");
        }
        evidence.append(", out of ")
            .append(measured.cases() == null ? "the cases it read" : measured.cases() + " cases")
            .append(".");

        return new MetricSuggestion(
            MetricCandidates.reworkId(topic),
            MetricSuggestionSource.PROCESS_MINING,
            "Repeated records in " + topic,
            "A case came back through this step. Whether that is a redelivery, a retry without "
                + "idempotence or a rework loop the business expects is a question only a "
                + "continuous measurement answers — the run saw it once.",
            List.of(evidence.toString()),
            null,   // see the javadoc: the two counts have different scopes
            List.of("No threshold is proposed. The run counted cases revisiting a step inside its "
                + "window; this query counts keys sharing the topic over a bounded scan — the two "
                + "move together but are not the same number, so a threshold taken from one would "
                + "only look derived. Set it from what the metric reports.",
                    "COUNT(DISTINCT …) needs the Flink planner; if the query falls back to the "
                + "direct engine the metric reports the engine's own error rather than a number.",
                    "Correlation uses " + key.describe(topic) + " — check it in the preview."),
            false, null, metric);
    }

    // ── Stream-Flow-derived proposals ─────────────────────────────────────────

    private List<MetricSuggestion> fromFlowChains(List<FlowChainEvidence> chains, FieldMapping mapping) {
        List<MetricSuggestion> suggestions = new ArrayList<>();
        for (FlowChainEvidence chain : chains) {
            List<FlowChainEvidence.FlowChainHop> hops = nullSafe(chain.hops()).stream()
                .filter(hop -> hop != null && hop.topic() != null && !hop.topic().isBlank())
                .toList();
            if (hops.size() < 2) continue;   // a single sighting is not a path

            for (int i = 1; i < hops.size(); i++) {
                transitLatencyFromChain(chain, hops.get(i - 1), hops.get(i), mapping).ifPresent(suggestions::add);
            }
            endToEndCompleteness(chain, hops).ifPresent(suggestions::add);
        }
        return suggestions;
    }

    /**
     * Does everything that enters the chain come out of it? The trace answers that for one key;
     * as a metric it answers it for the traffic.
     */
    private Optional<MetricSuggestion> endToEndCompleteness(FlowChainEvidence chain,
                                                            List<FlowChainEvidence.FlowChainHop> hops) {
        FlowChainEvidence.FlowChainHop first = hops.get(0);
        FlowChainEvidence.FlowChainHop last = hops.get(hops.size() - 1);
        if (first.topic().equals(last.topic())) return Optional.empty();

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("leftSql", countSql(first.topic()));
        params.put("rightSql", countSql(last.topic()));
        params.put("operation", "PERCENT_GAP");
        // Offsets rather than a scan, and the interval rather than the lifetime: see
        // COUNT_MODES and COUNT_WINDOWS in MetricService. Every card this panel builds is a
        // plain whole-topic gap between two named topics, which is the shape offsets answer
        // exactly — and the shape a lifetime total desensitises as history accumulates.
        params.put("countBy", "OFFSETS");
        params.put("window", "SINCE_LAST_REFRESH");
        params.put("leftTopic", first.topic());
        params.put("rightTopic", last.topic());

        MetricConfig metric = new MetricConfig(
            null,
            metricName(MetricSuggestionSource.STREAM_FLOW, "completeness", first.topic(), last.topic()),
            "GAUGE",
            null,
            "Percentage gap between the entry and the exit of the chain traced through "
                + hops.size() + " topics.",
            5.0, 20.0, null, null, null,
            List.of(), Map.of(), null,
            MetricTemplateType.TOPIC_COUNT_DELTA.name(), params,
            "TEMPLATE_BOUNDED_SCAN", first.topic(), List.of());

        String path = String.join(" → ", hops.stream().map(FlowChainEvidence.FlowChainHop::topic).toList());
        String evidence = "The trace of key "
            + (chain.messageKey() == null ? "(unnamed)" : chain.messageKey())
            + (chain.tracedAt() != null ? " on " + formatDate(chain.tracedAt()) : "")
            + " followed " + path + ".";

        return Optional.of(new MetricSuggestion(
            "flow:completeness:" + first.topic() + ">" + last.topic(),
            MetricSuggestionSource.STREAM_FLOW,
            "End-to-end completeness " + first.topic() + " → " + last.topic(),
            "One key made it all the way through. This measures whether the rest of the traffic "
                + "does — the gap between what enters the chain and what leaves it.",
            List.of(evidence),
            null,   // nothing was measured about *volume*: the trace followed a single key
            List.of("The 5 % / 20 % thresholds are placeholders, not derived: a trace measures one "
                + "key's path, never a ratio of volumes. Set them from what the metric reports.",
                    "Intermediate topics are ignored — the KPI compares the two ends. A drop in the "
                + "middle shows up here without saying where; the per-hop KPIs say where."),
            false, null, metric));
    }

    // ── Lineage-derived proposals ─────────────────────────────────────────────

    /**
     * A KPI on a pipeline edge the user <em>declared</em> rather than one a convention guessed.
     *
     * <p>Every other source here infers: the audit groups topics by their names, a trace follows
     * one key. A running {@code INSERT INTO sink SELECT … FROM source} states the edge outright,
     * and Flink's own parser resolves it — the same path the Lineage page draws, which is why
     * `dependenciesOf` is shared rather than re-implemented with a regex.
     *
     * <p>Only single-source statements are proposed. "Everything that entered should come out"
     * is a sentence about one source; on a join, the counts of two inputs and one output have no
     * ratio anybody can threshold, and a card claiming otherwise would be worse than no card.
     */
    private List<MetricSuggestion> fromLineage(List<String> notes) {
        Map<String, FlinkSqlService.JobInfo> jobs;
        try {
            jobs = flinkSqlService.getActiveJobsDetails();
        } catch (Exception e) {
            log.debug("Active jobs could not be read while suggesting metrics: {}", e.toString());
            return List.of();
        }
        if (jobs == null || jobs.isEmpty()) return List.of();

        // Only INSERT statements declare an edge, and that test is a string comparison — so it
        // runs on every job, and the cap applies to the parses, which are what actually cost.
        List<Map.Entry<String, FlinkSqlService.JobInfo>> inserting = jobs.entrySet().stream()
            .filter(entry -> {
                String sql = entry.getValue().sql();
                return sql != null && sql.toUpperCase(Locale.ROOT).contains("INSERT INTO");
            })
            .sorted(Comparator.comparingLong((Map.Entry<String, FlinkSqlService.JobInfo> e) ->
                e.getValue().startedAt()).reversed())
            .toList();

        int unread = Math.max(0, inserting.size() - MAX_LINEAGE_JOBS);
        if (unread > 0) {
            notes.add(unread + " further running job(s) were not resolved: the "
                + MAX_LINEAGE_JOBS + " most recently started are read, since resolving a statement "
                + "costs a Flink parse on every load of this page.");
        }

        List<MetricSuggestion> suggestions = new ArrayList<>();
        int joins = 0;
        for (Map.Entry<String, FlinkSqlService.JobInfo> entry : inserting.stream().limit(MAX_LINEAGE_JOBS).toList()) {
            LineageService.SqlDependencies dependencies;
            try {
                dependencies = lineageService.dependenciesOf(entry.getValue().sql());
            } catch (Exception e) {
                log.debug("Job {} could not be resolved while suggesting metrics: {}", entry.getKey(), e.toString());
                continue;
            }
            String target = dependencies.target();
            if (target == null || dependencies.sources().isEmpty()) continue;
            if (dependencies.sources().size() > 1) { joins++; continue; }

            String source = dependencies.sources().iterator().next();
            if (source.equals(target)) continue;
            suggestions.add(declaredGap(source, target, entry.getValue(), dependencies.parsed()));
        }
        if (joins > 0) {
            notes.add(joins + " running job(s) read several sources at once. No gap KPI is proposed "
                + "for them: \"everything that entered came out\" is a statement about one source, "
                + "and on a join the counts have no ratio worth a threshold.");
        }
        return suggestions;
    }

    private MetricSuggestion declaredGap(String source, String target,
                                         FlinkSqlService.JobInfo job, boolean parsed) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("leftSql", "SELECT COUNT(*) AS metric_value\nFROM " + source);
        params.put("rightSql", "SELECT COUNT(*) AS metric_value\nFROM " + target);
        params.put("operation", "PERCENT_GAP");
        // Offsets rather than a scan, and the interval rather than the lifetime: see
        // COUNT_MODES and COUNT_WINDOWS in MetricService. Every card this panel builds is a
        // plain whole-topic gap between two named topics, which is the shape offsets answer
        // exactly — and the shape a lifetime total desensitises as history accumulates.
        params.put("countBy", "OFFSETS");
        params.put("window", "SINCE_LAST_REFRESH");
        params.put("leftTopic", source);
        params.put("rightTopic", target);

        MetricConfig metric = new MetricConfig(
            null,
            "gauge_declared_gap_" + DdlGeneratorService.toTableName(source)
                + "_to_" + DdlGeneratorService.toTableName(target),
            "GAUGE",
            null,
            "Percentage gap between " + source + " and " + target + ", the two ends of a running "
                + "Flink job.",
            // No threshold: this edge has never been measured. The audit's gap KPI can multiply
            // an observed drop; here there is nothing to multiply, and a round number would be
            // exactly the invention this panel refuses.
            null, null, null, null, null,
            List.of(), Map.of(), null,
            MetricTemplateType.TOPIC_COUNT_DELTA.name(), params,
            "TEMPLATE_BOUNDED_SCAN", source, List.of());

        String evidence = "A Flink job started "
            + formatDate(job.startedAt()) + " (query " + job.queryId() + ") inserts into " + target
            + " reading from " + source + (parsed
                ? ", as Flink's parser resolves the statement."
                : ". Flink's parser could not resolve the statement, so the pair was read off the "
                  + "SQL text and may be incomplete.");

        List<String> caveats = new ArrayList<>();
        caveats.add("A job that filters or aggregates shows a permanent gap, which is correct and "
            + "not a fault — set the thresholds around the level it normally sits at.");
        caveats.add("No threshold is proposed: nothing has measured this pair. Run it, look at "
            + "what it reports, then set one.");
        if (!parsed) {
            caveats.add("The dependency was guessed from the SQL text; check the two queries name "
                + "the tables you expect before saving.");
        }

        return new MetricSuggestion(
            "lineage:flow-gap:" + source + ">" + target,
            MetricSuggestionSource.LINEAGE,
            "Throughput gap " + source + " → " + target,
            "This edge is not inferred from a naming convention: a job you started is moving data "
                + "along it right now, so the two ends are supposed to agree.",
            List.of(evidence),
            null,
            caveats,
            false, null, metric);
    }

    // ── Process-Mining-derived proposals ──────────────────────────────────────

    /**
     * The mapping the operator validated, or nothing — never a guess about which one they meant.
     *
     * <p>The id lives in the browser's Process Mining draft and travels in the request, the way
     * traces do. A mapping the server no longer holds (it is in-memory, so a restart loses it) is
     * reported rather than silently ignored: the cards it would have produced are missing, and the
     * panel would otherwise look as though Process Mining had nothing to say.
     */
    private FieldMapping resolveFieldMapping(MetricSuggestionRequest request, List<String> notes) {
        String id = request == null ? null : request.fieldMappingId();
        if (id == null || id.isBlank()) {
            notes.add("No Process Mining field mapping was validated in this browser — validating "
                + "one names each topic's real correlation key and its status field, which sharpens "
                + "the proposals above and adds a KPI per status.");
            return null;
        }
        Optional<FieldMapping> mapping = fieldMappingStore.find(id);
        if (mapping.isEmpty()) {
            notes.add("The Process Mining field mapping this browser refers to is no longer held by "
                + "the server (mappings live in memory and a restart loses them) — re-run the "
                + "profiling step to get its proposals back.");
            return null;
        }
        return mapping.get();
    }

    /**
     * One KPI per topic whose status field an operator validated.
     *
     * <p>The status is the one business dimension this application can measure without asking
     * anybody what the business is: the query groups by it, and every non-{@code metric_value}
     * column becomes a Prometheus label, so a single metric yields one series per status value —
     * "how many are FAILED right now" without naming FAILED anywhere.
     */
    private List<MetricSuggestion> fromFieldMapping(FieldMapping mapping, List<String> notes) {
        Map<String, String> statusPaths = mapping.statusPaths();
        if (statusPaths == null || statusPaths.isEmpty()) return List.of();

        List<MetricSuggestion> suggestions = new ArrayList<>();
        List<String> nested = new ArrayList<>();
        statusPaths.forEach((topic, path) -> {
            String column = simpleColumn(path);
            if (column == null) {
                nested.add(topic + " (" + path + ")");
                return;
            }
            suggestions.add(statusBreakdown(topic, column, path));
        });
        if (!nested.isEmpty()) {
            notes.add("The status field of " + String.join(", ", nested) + " is a nested path, which "
                + "a metric query cannot select as a column — no KPI is proposed for it rather than "
                + "SQL that would fail on its first refresh.");
        }
        return suggestions;
    }

    private MetricSuggestion statusBreakdown(String topic, String column, String path) {
        String table = DdlGeneratorService.toTableName(topic);
        String sql = "SELECT `" + column + "` AS " + column + ", COUNT(*) AS metric_value\n"
            + "FROM " + table + "\n"
            + "GROUP BY `" + column + "`";

        MetricConfig metric = new MetricConfig(
            null,
            "gauge_status_" + table,
            "GAUGE",
            sql,
            "Records of " + topic + " by " + column + " — one Prometheus series per status value.",
            // Which value matters, and above what count, is a business question nobody here has
            // been told the answer to. The series is the KPI; the threshold belongs to whoever
            // knows what FAILED costs.
            null, null, null, null, null,
            List.of(), Map.of(), null,
            MetricTemplateType.RAW_SQL.name(), Map.of(),
            "SQL", topic, List.of());

        return new MetricSuggestion(
            MetricCandidates.statusId(topic),
            MetricSuggestionSource.PROCESS_MINING,
            "Status breakdown of " + topic,
            "Process Mining identified which field carries the status of these records. Counting "
                + "them by it is the one business KPI that needs no further knowledge of the "
                + "business — and it is what turns \"how many are failing?\" into a graph.",
            List.of("The Process Mining field mapping validated in this browser names `" + path
                + "` as the status field of " + topic + "."),
            null,
            List.of("One Prometheus series per distinct value: fine for a handful of statuses, "
                + "expensive if that field turns out to be near-unique.",
                    "No threshold is proposed — which status matters, and above what count, is the "
                + "one thing this application cannot know for you.",
                    "The field must be a column of the registered table; the preview is what "
                + "settles it."),
            false, null, metric);
    }

    // ── Does the evidence still hold? ─────────────────────────────────────────

    /**
     * Topics whose absence is named individually in a note; past that they are counted.
     *
     * <p>Same reasoning as {@code StreamFlowService.stats.skippedTopics}: the names are the
     * actionable part — they say <em>which</em> proposals went — and a note listing forty of them
     * is a note nobody reads.
     */
    private static final int MAX_NAMED_MISSING_TOPICS = 5;

    /**
     * What the cluster says right now about the topics the proposals read.
     *
     * @param byTopic     the state of each name a proposal asked about, keyed by the name as the
     *                    proposal writes it (a topic, or the Flink table it is registered under)
     * @param listRead    whether the topic list could be read at all. False means every entry is
     *                    {@link MetricDataState#UNKNOWN} because the question was never put, which
     *                    is not the same as every topic being fine — and the notes say so
     */
    private record TopicReadiness(Map<String, MetricDataState> byTopic, boolean listRead) {
        MetricDataState of(String name) {
            return byTopic.getOrDefault(name, MetricDataState.UNKNOWN);
        }

        static TopicReadiness unavailable() {
            return new TopicReadiness(Map.of(), false);
        }
    }

    /**
     * Asks the cluster whether the proposals can still be measured, and acts on the answer.
     *
     * <p>Every other source this class reads has already aged by the time it is read: an audit
     * comes back from the history topic and can be weeks old, a Stream Flow chain and a measured
     * process are kept in the browser for seven days. In that interval a topic is deleted, or
     * retention empties it. Nothing asked — so a card was offered naming a topic that no longer
     * existed, with thresholds that are multiples of a count nothing could produce any more, and
     * the operator found out once the metric had been refreshing against it every thirty seconds.
     * That is the same defect this panel was built to avoid one step earlier: a figure on screen
     * that no measurement stands behind.
     *
     * <p>Three rules, and the third is the one that makes the other two safe to apply:
     *
     * <ol>
     *   <li><b>A topic that is gone drops its proposal</b>, named in a note. The metric could only
     *       fail at every refresh, and offering it is worse than saying why it is not offered —
     *       the same rule that makes a nested status path a note rather than SQL that would fail
     *       on its first run.</li>
     *   <li><b>A topic that is empty marks its proposal, and keeps it.</b> Empty now is not empty
     *       in five minutes: retention refills, a topic created for a pipeline being built is
     *       legitimately empty, and on a gap KPI an empty target beside a populated source is
     *       precisely the alarm the card exists to raise. What the operator needs is to know it
     *       before setting a threshold on it.</li>
     *   <li><b>A check that could not run changes nothing.</b> An unreachable broker, an
     *       unreadable topic list, a name that is a Flink table over another connector — each
     *       leaves the proposal exactly as its family built it, and says so once. "We asked and
     *       the answer is no" and "we could not ask" are different answers, and rendering the
     *       second as the first would empty the panel on a blip.</li>
     * </ol>
     *
     * <p>It costs two cached metadata reads for the whole request — the topic list on the entry
     * every other screen already warms, and one batched offsets read — where the panel already
     * pays a coordinator round trip per lag finding and a Flink parse per running job.
     */
    private List<MetricSuggestion> verifyDataAvailable(List<MetricSuggestion> suggestions,
                                                       List<String> notes) {
        if (suggestions.isEmpty()) return suggestions;

        TopicReadiness readiness = readTopicReadiness(suggestions, notes);
        List<MetricSuggestion> kept = new ArrayList<>(suggestions.size());
        Map<String, Integer> missingByTopic = new LinkedHashMap<>();
        int emptyCards = 0;

        for (MetricSuggestion suggestion : suggestions) {
            List<String> topics = topicsOf(suggestion);
            MetricDataState state = MetricDataState.UNKNOWN;
            List<String> absent = new ArrayList<>();
            List<String> empty = new ArrayList<>();
            for (String topic : topics) {
                switch (readiness.of(topic)) {
                    case ABSENT -> absent.add(topic);
                    case EMPTY -> empty.add(topic);
                    case POPULATED, UNKNOWN -> { }
                }
            }
            if (!absent.isEmpty()) {
                // Dropped, and recorded against the topic rather than against the card: several
                // proposals usually name one deleted topic, and "3 proposals named demo.orders.x,
                // which no longer exists" is the sentence that sends the reader somewhere.
                absent.forEach(topic -> missingByTopic.merge(topic, 1, Integer::sum));
                continue;
            }
            if (!empty.isEmpty()) {
                state = MetricDataState.EMPTY;
                emptyCards++;
            } else if (!topics.isEmpty()
                && topics.stream().allMatch(t -> readiness.of(t) == MetricDataState.POPULATED)) {
                state = MetricDataState.POPULATED;
            }
            kept.add(withDataState(suggestion, state, empty));
        }

        if (!missingByTopic.isEmpty()) notes.add(describeMissingTopics(missingByTopic));
        if (emptyCards > 0) {
            notes.add(emptyCards + " proposal(s) read a topic that holds no record right now. They "
                + "are kept and marked rather than dropped — a topic emptied by retention fills "
                + "again, and an empty target beside a populated source is what a gap KPI is for — "
                + "but a threshold set on one measures nothing until records arrive.");
        }
        if (!readiness.listRead()) {
            notes.add("The cluster's topic list could not be read just now, so the proposals below "
                + "were not checked against it: one naming a topic that has since been deleted "
                + "would still be offered here. Re-derive once the broker answers.");
        }
        return kept;
    }

    /**
     * The topic list and the record counts, taken once for the whole request.
     *
     * <p>The list is what separates "deleted" from "we could not ask", so it is read first and a
     * failure there ends the check outright — deriving emptiness from counts alone would report a
     * topic that no longer exists as one that is merely empty, which is a different repair.
     */
    private TopicReadiness readTopicReadiness(List<MetricSuggestion> suggestions, List<String> notes) {
        List<String> topics;
        try {
            topics = kafkaAdminService.listTopics();
        } catch (Exception e) {
            log.debug("Topic list unreadable while suggesting metrics: {}", e.toString());
            return TopicReadiness.unavailable();
        }
        if (topics == null) return TopicReadiness.unavailable();

        // A proposal names a Kafka topic, except the lineage family, whose two ends come from
        // Flink's parser and are therefore *table* names. `toTableName` is not injective, so the
        // map is built forwards from the topics that exist rather than by trying to invert it.
        Set<String> existing = new LinkedHashSet<>(topics);
        Map<String, String> byTableName = new LinkedHashMap<>();
        topics.forEach(topic -> byTableName.putIfAbsent(DdlGeneratorService.toTableName(topic), topic));

        Map<String, String> resolved = new LinkedHashMap<>();   // name as written → topic
        Set<String> unresolved = new LinkedHashSet<>();
        for (MetricSuggestion suggestion : suggestions) {
            for (String name : topicsOf(suggestion)) {
                if (resolved.containsKey(name) || unresolved.contains(name)) continue;
                if (existing.contains(name)) resolved.put(name, name);
                else if (byTableName.containsKey(name)) resolved.put(name, byTableName.get(name));
                else unresolved.add(name);
            }
        }

        Map<String, Long> counts = Map.of();
        if (!resolved.isEmpty()) {
            // Sorted so two requests over the same topics hit one cache entry rather than two.
            List<String> wanted = resolved.values().stream().distinct().sorted().toList();
            try {
                Map<String, Long> read = kafkaAdminService.getTopicRecordCounts(wanted);
                if (read != null) counts = read;
            } catch (Exception e) {
                log.debug("Record counts unreadable while suggesting metrics: {}", e.toString());
            }
            if (counts.isEmpty()) {
                notes.add("The record counts of the topics these proposals read could not be taken "
                    + "just now — a topic that has been emptied since it was observed is therefore "
                    + "not marked as such below. Whether each topic still exists was checked.");
            }
        }

        Map<String, MetricDataState> byTopic = new LinkedHashMap<>();
        Map<String, Long> measured = counts;
        resolved.forEach((name, topic) -> {
            Long records = measured.get(topic);
            // Absent from the count map means the read failed or covered no partition of it — the
            // topic exists, so it is not ABSENT, and nothing here knows whether it holds anything.
            byTopic.put(name, records == null
                ? MetricDataState.UNKNOWN
                : records > 0 ? MetricDataState.POPULATED : MetricDataState.EMPTY);
        });
        // A name the cluster does not carry under either spelling: the lineage family can name a
        // Flink table over another connector, or a view, and calling that a deleted topic would
        // drop a perfectly measurable proposal. Unknown is the honest reading, and it drops
        // nothing — the offsets template will say so itself if the name is wrong.
        unresolved.forEach(name -> byTopic.put(name, unresolvedState(name, existing)));
        return new TopicReadiness(byTopic, true);
    }

    /**
     * What to make of a name the topic list does not carry.
     *
     * <p>{@code ABSENT} for a name written as a Kafka topic — every family but lineage writes one,
     * and the list was read successfully, so its absence is a fact. {@code UNKNOWN} for a name
     * that could only ever have been a Flink identifier: {@code toTableName} maps dots and hyphens
     * to underscores, so a name carrying neither is indistinguishable from a table this
     * application did not register, and dropping on it would penalise the one family whose
     * evidence is the strongest — a job the operator is running right now.
     */
    private MetricDataState unresolvedState(String name, Set<String> existing) {
        if (existing.isEmpty()) return MetricDataState.UNKNOWN;   // an empty cluster proves nothing
        return name.indexOf('.') >= 0 || name.indexOf('-') >= 0
            ? MetricDataState.ABSENT
            : MetricDataState.UNKNOWN;
    }

    /**
     * The names a proposal reads, from the configuration it carries rather than from its prose.
     *
     * <p>Mirrors {@code suggestionTopics} in {@code pages/metricSuggestions.ts}, which builds the
     * card's chips from the same fields: the template parameters name the topics for the three
     * templates, and {@code labelTopic} carries it for the raw-SQL cards, every one of which sets
     * it to the topic its query reads.
     */
    private static final List<String> TOPIC_PARAM_KEYS =
        List.of("sourceTopic", "targetTopic", "leftTopic", "rightTopic", "topic");

    private List<String> topicsOf(MetricSuggestion suggestion) {
        MetricConfig metric = suggestion.metric();
        if (metric == null) return List.of();
        Map<String, Object> params = metric.templateParams() == null ? Map.of() : metric.templateParams();
        Set<String> topics = new LinkedHashSet<>();
        for (String key : TOPIC_PARAM_KEYS) {
            Object value = params.get(key);
            if (value instanceof String name && !name.isBlank()) topics.add(name);
        }
        if (topics.isEmpty() && metric.labelTopic() != null && !metric.labelTopic().isBlank()) {
            topics.add(metric.labelTopic());
        }
        return List.copyOf(topics);
    }

    /**
     * The proposal with its measured state, and — when it is EMPTY — a caveat naming which topic.
     *
     * <p>The caveat rather than the badge alone, because a badge says a card is affected and only
     * the sentence says which of its two topics is, which is the whole of the reading on a gap or
     * a latency KPI.
     */
    private MetricSuggestion withDataState(MetricSuggestion suggestion, MetricDataState state,
                                           List<String> emptyTopics) {
        if (state == suggestion.dataState() && emptyTopics.isEmpty()) return suggestion;
        List<String> caveats = new ArrayList<>(nullSafe(suggestion.caveats()));
        if (!emptyTopics.isEmpty()) {
            caveats.add(String.join(", ", emptyTopics)
                + (emptyTopics.size() == 1 ? " holds" : " hold") + " no record right now, read from "
                + "the log's offsets just now. The KPI is proposed anyway — the topic may fill "
                + "again, and its being empty may itself be the finding — but it measures nothing "
                + "until it does, and any threshold below rests on a count taken when it was not "
                + "empty.");
        }
        return new MetricSuggestion(suggestion.id(), suggestion.source(), suggestion.title(),
            suggestion.rationale(), suggestion.evidence(), suggestion.thresholdBasis(),
            List.copyOf(caveats), suggestion.alreadyConfigured(), suggestion.existingMetricName(),
            state, suggestion.metric());
    }

    private String describeMissingTopics(Map<String, Integer> missingByTopic) {
        int proposals = missingByTopic.values().stream().mapToInt(Integer::intValue).sum();
        List<String> names = missingByTopic.keySet().stream().limit(MAX_NAMED_MISSING_TOPICS).toList();
        String tail = missingByTopic.size() > names.size()
            ? " and " + (missingByTopic.size() - names.size()) + " other(s)"
            : "";
        return proposals + " proposal(s) were dropped: they read " + String.join(", ", names) + tail
            + ", which the cluster no longer carries. What was observed about "
            + (missingByTopic.size() == 1 ? "it" : "them")
            + " is still in the run they came from; the metric would only fail at every refresh.";
    }

    // ── Naming, SQL, dedupe ───────────────────────────────────────────────────

    /** Where a proposal's correlation key comes from — and the card says which. */
    private enum KeySource {
        /** An operator validated it in the Process Mining pipeline: the best answer available. */
        FIELD_MAPPING,
        /** A column of the table registered in Flink, matched against the usual key names. */
        REGISTERED_SCHEMA,
        /** Nothing knew: the audit's own `id` convention, flagged as an assumption. */
        CONVENTION
    }

    private record KeyColumn(String column, KeySource origin) {
        boolean inferred() {
            return origin == KeySource.CONVENTION;
        }

        String describe(String topic) {
            return switch (origin) {
                case FIELD_MAPPING -> "`" + column + "` (the correlation key validated for " + topic
                    + " in Process Mining)";
                case REGISTERED_SCHEMA -> "`" + column + "` (a column of the registered table for " + topic + ")";
                case CONVENTION -> "`" + column + "` (assumed on " + topic
                    + " — nothing registered or profiled says otherwise)";
            };
        }
    }

    /**
     * The correlation key for a topic, best source first.
     *
     * <p>A validated Process Mining mapping beats a schema guess, and a schema column beats the
     * `id` convention — because the first is somebody's answer, the second is a fact about the
     * table, and only the third is this code assuming. Which one it was travels to the card, so
     * the operator knows whether the preview is a formality or the thing to check.
     */
    private KeyColumn keyColumn(String topic, FieldMapping mapping) {
        String mapped = mappedKeyColumn(topic, mapping);
        if (mapped != null) return new KeyColumn(mapped, KeySource.FIELD_MAPPING);

        Map<String, String> schema;
        try {
            schema = flinkSqlService.getTableSchema(DdlGeneratorService.toTableName(topic));
        } catch (RuntimeException e) {
            log.debug("No schema for {} while suggesting metrics: {}", topic, e.toString());
            schema = Map.of();
        }
        if (schema.isEmpty()) return new KeyColumn(FALLBACK_KEY_COLUMN, KeySource.CONVENTION);

        Map<String, String> byLowerCase = new LinkedHashMap<>();
        schema.keySet().forEach(column -> byLowerCase.putIfAbsent(column.toLowerCase(Locale.ROOT), column));
        for (String candidate : KEY_COLUMN_CANDIDATES) {
            String match = byLowerCase.get(candidate);
            if (match != null) return new KeyColumn(match, KeySource.REGISTERED_SCHEMA);
        }
        // Nothing recognisable: name the convention rather than picking an arbitrary column, which
        // would produce a metric that runs and measures nothing.
        return new KeyColumn(FALLBACK_KEY_COLUMN, KeySource.CONVENTION);
    }

    /**
     * The mapping's correlation path for a topic, when it can be a SQL column.
     *
     * <p>A mapping stores a path — `$.orderId`, `order.reference`, `header:x`. A metric query
     * selects a *column*, so a nested path is not usable and is dropped rather than turned into
     * SQL that cannot run: `$.` is stripped, anything still carrying a dot or a bracket is not a
     * column of the registered table and would fail at the first refresh.
     */
    private String mappedKeyColumn(String topic, FieldMapping mapping) {
        if (mapping == null || mapping.correlationIdPaths() == null) return null;
        String path = mapping.correlationIdPaths().get(topic);
        return simpleColumn(path);
    }

    /** A path reduced to a column name, or null when it does not designate one. */
    private static String simpleColumn(String path) {
        if (path == null || path.isBlank()) return null;
        String cleaned = path.trim();
        if (cleaned.startsWith("$.")) cleaned = cleaned.substring(2);
        if (cleaned.startsWith("$")) cleaned = cleaned.substring(1);
        if (cleaned.isEmpty()) return null;
        // A nested path, an array access or a header reference is not a column of the table.
        return cleaned.matches("[A-Za-z_][A-Za-z0-9_]*") ? cleaned : null;
    }

    private String correlationSql(String topic, String keyColumn) {
        return "SELECT `" + keyColumn + "` AS match_key, `" + TIME_COLUMN + "` AS event_time\n"
            + "FROM " + DdlGeneratorService.toTableName(topic);
    }

    private String countSql(String topic) {
        return "SELECT COUNT(*) AS metric_value\nFROM " + DdlGeneratorService.toTableName(topic);
    }

    /**
     * The Prometheus series name. The prefix says where the proposal came from, which matters on a
     * dashboard: two metrics on one pair of topics, one derived from a measured distribution and
     * one from a single traced key, are not the same measurement and must not collide.
     */
    private String metricName(MetricSuggestionSource source, String kind, String from, String to) {
        String prefix = switch (source) {
            case AUDIT -> "gauge";
            case PROCESS_MINING -> "gauge_measured";
            case STREAM_FLOW, LINEAGE -> "gauge_traced";
        };
        return prefix + "_" + kind + "_" + DdlGeneratorService.toTableName(from)
            + "_to_" + DdlGeneratorService.toTableName(to);
    }

    /**
     * The audit and a trace very often describe the same hop. Keeping both would put two cards on
     * the same pair of topics; the first one wins because the list is built audit-first and the
     * audit's evidence is the broader of the two.
     */
    /**
     * The kinds a proposal can be, most diagnostic first. Read out of the id (`source:kind:target`),
     * which {@link #deduplicate} already parses — structured data, never the prose of a rationale.
     *
     * <p>The order is an argument about what an operator should see first, not a preference:
     * {@code time-lag} is only ever proposed for a topic the audit flagged with a consumer
     * finding, and measures the one thing no record count can express; {@code duplicates} and
     * {@code hop-latency} rest on a number the audit actually measured; {@code volume} is the
     * routine one — worth having, worth losing first.
     */
    private static final List<String> KIND_URGENCY = List.of(
        "time-lag", "duplicates", "hop-latency", "flow-gap", "completeness", "status", "volume");

    private static int kindRank(MetricSuggestion suggestion) {
        String[] parts = suggestion.id().split(":", 3);
        int rank = parts.length > 1 ? KIND_URGENCY.indexOf(parts[1]) : -1;
        // An unlisted kind sorts last rather than throwing: a new kind must not be able to break
        // the panel, and ranking it below the known ones is the conservative reading.
        return rank < 0 ? KIND_URGENCY.size() : rank;
    }

    /**
     * Tiebreak only: how directly the evidence establishes the thing being measured. A running
     * INSERT job *declares* its source and target; a validated field mapping is a human saying
     * where the key lives; the audit measured this cluster but groups flows by topic name; a
     * trace followed one key. Deliberately weaker than what the KPI is about — an operator cares
     * about the problem before its provenance.
     */
    private static int sourceRank(MetricSuggestion suggestion) {
        return switch (suggestion.source()) {
            case LINEAGE -> 0;
            case PROCESS_MINING -> 1;
            case AUDIT -> 2;
            case STREAM_FLOW -> 3;
        };
    }

    /**
     * Relevance order, applied before the cap so that what is dropped is what matters least.
     * Every term is a field the proposal already carries, and the last one is the id, so the
     * order is deterministic across runs — cards must not shuffle between two identical audits,
     * and a browser-side dismissal is keyed on that id.
     */
    private static final Comparator<MetricSuggestion> BY_RELEVANCE =
        Comparator.comparing(MetricSuggestion::alreadyConfigured)          // fresh before covered
            // A KPI over a topic holding nothing measures nothing today, so it is what the cap
            // drops first. Below `alreadyConfigured` and above everything else: what a card is
            // about only matters among cards that have something to report on.
            .thenComparing(s -> s.dataState() == MetricDataState.EMPTY)
            .thenComparingInt(MetricSuggestionService::kindRank)           // what it is about
            .thenComparing(s -> s.thresholdBasis() == null)                // derived thresholds first
            .thenComparingInt(s -> s.caveats() == null ? 0 : s.caveats().size())  // fewer assumptions
            .thenComparingInt(MetricSuggestionService::sourceRank)
            .thenComparing(MetricSuggestion::id);

    /**
     * Says what was actually dropped. The previous wording claimed the remainder were "of the same
     * kinds, on other topics" — an assertion nothing checked, and false as soon as the list mixed
     * sources. Counting the kinds costs nothing and cannot be wrong.
     */
    private String describeTruncation(List<MetricSuggestion> dropped) {
        Map<String, Long> byKind = dropped.stream().collect(Collectors.groupingBy(
            s -> {
                String[] parts = s.id().split(":", 3);
                return parts.length > 1 ? parts[1] : "other";
            }, LinkedHashMap::new, Collectors.counting()));
        String detail = byKind.entrySet().stream()
            .map(e -> e.getValue() + " " + e.getKey())
            .collect(Collectors.joining(", "));
        return "Showing the " + MAX_SUGGESTIONS + " most relevant of "
            + (MAX_SUGGESTIONS + dropped.size()) + " proposals. Left out, as least actionable: "
            + detail + ".";
    }

    private List<MetricSuggestion> deduplicate(List<MetricSuggestion> suggestions) {
        Set<String> seenIds = new LinkedHashSet<>();
        Set<String> seenTargets = new LinkedHashSet<>();
        List<MetricSuggestion> kept = new ArrayList<>();
        for (MetricSuggestion suggestion : suggestions) {
            if (!seenIds.add(suggestion.id())) continue;
            String target = suggestion.id().substring(suggestion.id().indexOf(':') + 1);
            if (!seenTargets.add(target)) continue;
            kept.add(suggestion);
        }
        return kept;
    }

    /**
     * Marks proposals an existing metric already covers, rather than dropping them: "you already
     * measure this" is an answer the panel should be able to give, and hiding the card silently
     * would leave the operator wondering why the audit's finding produced nothing.
     */
    private List<MetricSuggestion> markAlreadyConfigured(List<MetricSuggestion> suggestions) {
        List<MetricConfig> existing = metricService.getAllMetrics();
        List<MetricSuggestion> marked = new ArrayList<>(suggestions.size());
        for (MetricSuggestion suggestion : suggestions) {
            Optional<MetricConfig> covering = existing.stream()
                .filter(metric -> covers(metric, suggestion.metric()))
                .findFirst();
            marked.add(covering
                .map(metric -> new MetricSuggestion(
                    suggestion.id(), suggestion.source(), suggestion.title(), suggestion.rationale(),
                    suggestion.evidence(), suggestion.thresholdBasis(), suggestion.caveats(),
                    true, metric.name(), suggestion.dataState(), suggestion.metric()))
                .orElse(suggestion));
        }
        return marked;
    }

    /** Same template on the same pair of topics, or the same SQL modulo whitespace and case. */
    private boolean covers(MetricConfig existing, MetricConfig proposed) {
        String existingTemplate = MetricTemplateType.fromValue(existing.templateType()).name();
        String proposedTemplate = MetricTemplateType.fromValue(proposed.templateType()).name();
        if (!existingTemplate.equals(proposedTemplate)) return false;

        if (MetricTemplateType.RAW_SQL.name().equals(proposedTemplate)) {
            return normalizeSql(existing.sql()).equals(normalizeSql(proposed.sql()));
        }
        Map<String, Object> existingParams = existing.templateParams() == null ? Map.of() : existing.templateParams();
        Map<String, Object> proposedParams = proposed.templateParams() == null ? Map.of() : proposed.templateParams();
        return switch (MetricTemplateType.fromValue(proposedTemplate)) {
            case TOPIC_TRANSIT_LATENCY -> sameParams(existingParams, proposedParams, "sourceTopic", "targetTopic");
            case TOPIC_COUNT_DELTA -> sameParams(existingParams, proposedParams, "leftTopic", "rightTopic");
            // Same group on same topic: a second aggregation over the same pair measures the same
            // delay, so the proposal is marked as covered rather than offered again.
            case CONSUMER_TIME_LAG -> sameParams(existingParams, proposedParams, "topic", "group");
            case RAW_SQL -> false;
        };
    }

    private boolean sameParams(Map<String, Object> a, Map<String, Object> b, String... keys) {
        for (String key : keys) {
            Object left = a.get(key);
            Object right = b.get(key);
            if (left == null || right == null || !String.valueOf(left).equals(String.valueOf(right))) {
                return false;
            }
        }
        return true;
    }

    private String normalizeSql(String sql) {
        return sql == null ? "" : sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    // ── Formatting ────────────────────────────────────────────────────────────

    private String describeRun(AuditSnapshot audit) {
        String when = audit.timestamp() != null ? " of " + formatDate(audit.timestamp()) : "";
        String where = "HISTORY".equals(audit.source()) ? " (read back from the history topic)" : "";
        return "The cluster audit" + when + where;
    }

    private String formatDate(long epochMillis) {
        return java.time.Instant.ofEpochMilli(epochMillis)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDateTime()
            .withNano(0)
            .toString()
            .replace('T', ' ');
    }

    private String formatMillis(long millis) {
        if (Math.abs(millis) < 1000) return millis + " ms";
        double seconds = millis / 1000.0;
        if (Math.abs(seconds) < 60) return String.format(Locale.ROOT, "%.1f s", seconds);
        return String.format(Locale.ROOT, "%.1f min", seconds / 60.0);
    }

    private String formatPercent(double value) {
        return String.format(Locale.ROOT, "%.1f %%", value);
    }

    private String shortReason(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) return e.getClass().getSimpleName();
        return message.length() > 160 ? message.substring(0, 157) + "…" : message;
    }

    private Long longStat(AuditReport report, String key) {
        Map<String, Object> stats = report.globalStats();
        if (stats == null) return null;
        Object value = stats.get(key);
        return value instanceof Number number ? number.longValue() : null;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
