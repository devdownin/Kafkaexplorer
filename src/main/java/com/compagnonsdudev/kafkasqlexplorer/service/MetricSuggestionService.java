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
import com.compagnonsdudev.kafkasqlexplorer.domain.FlowChainEvidence;
import com.compagnonsdudev.kafkasqlexplorer.domain.HealthStatus;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricSuggestion;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricSuggestionRequest;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricSuggestionSource;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricSuggestions;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricTemplateType;
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
 * <p>Three rules hold this together, and each of them is the reason a line of this class looks the
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
    /** Volume KPIs are proposed for the busiest audited topics only — one per topic is plenty. */
    private static final int MAX_VOLUME_SUGGESTIONS = 3;
    /**
     * Topics whose consumer groups are read live to propose a delay-in-time KPI. Each costs a
     * coordinator round trip (cached 30 s), so the flagged topics are taken a few at a time.
     */
    private static final int MAX_TIME_LAG_TOPICS = 3;
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
    private final ExplorerConfig explorerConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MetricSuggestionService(AuditService auditService,
                                   AuditHistoryService auditHistoryService,
                                   MetricService metricService,
                                   FlinkSqlService flinkSqlService,
                                   KafkaAdminService kafkaAdminService,
                                   ExplorerConfig explorerConfig) {
        this.auditService = auditService;
        this.auditHistoryService = auditHistoryService;
        this.metricService = metricService;
        this.flinkSqlService = flinkSqlService;
        this.kafkaAdminService = kafkaAdminService;
        this.explorerConfig = explorerConfig;
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    public MetricSuggestions suggest(MetricSuggestionRequest request) {
        List<FlowChainEvidence> chains = request == null ? List.of() : request.chains();
        List<String> notes = new ArrayList<>();
        List<MetricSuggestion> suggestions = new ArrayList<>();

        AuditSnapshot audit = readAudit(notes);
        if (audit.report() != null) {
            suggestions.addAll(fromAudit(audit, notes));
        } else {
            notes.add("No cluster audit could be read — run one from the Audit page to unlock the "
                + "KPIs derived from topic volumes, findings and reconstructed flows.");
        }

        if (chains.isEmpty()) {
            notes.add("No Stream Flow trace was recorded in this browser — trace a message key to "
                + "unlock the KPIs derived from the path it actually travelled.");
        } else {
            suggestions.addAll(fromFlowChains(chains));
        }

        List<MetricSuggestion> deduplicated = deduplicate(suggestions);
        if (deduplicated.size() > MAX_SUGGESTIONS) {
            notes.add("Showing " + MAX_SUGGESTIONS + " of " + deduplicated.size()
                + " proposals — the rest are of the same kinds, on other topics.");
            deduplicated = new ArrayList<>(deduplicated.subList(0, MAX_SUGGESTIONS));
        }

        List<MetricSuggestion> marked = markAlreadyConfigured(deduplicated);
        return new MetricSuggestions(
            marked,
            audit.report() != null,
            audit.report() != null ? audit.report().auditId() : null,
            audit.timestamp(),
            audit.source(),
            audit.report() != null ? (int) audit.report().totalTopics() : 0,
            chains.size(),
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

    private List<MetricSuggestion> fromAudit(AuditSnapshot audit, List<String> notes) {
        AuditReport report = audit.report();
        String run = describeRun(audit);
        List<MetricSuggestion> suggestions = new ArrayList<>();

        for (FlowAudit flow : nullSafe(report.flowAudits())) {
            List<FlowAudit.StepInfo> steps = nullSafe(flow.steps());
            for (int i = 1; i < steps.size(); i++) {
                FlowAudit.StepInfo from = steps.get(i - 1);
                FlowAudit.StepInfo to = steps.get(i);
                transitLatencyFromAudit(flow.flowName(), from, to, run).ifPresent(suggestions::add);
                throughputGap(flow.flowName(), from, to, run).ifPresent(suggestions::add);
            }
        }

        for (TopicAudit topic : nullSafe(report.topicAudits())) {
            if (topic.duplicateCount() > 0) {
                duplicateKpi(topic, run).ifPresent(suggestions::add);
            }
        }

        nullSafe(report.topicAudits()).stream()
            .filter(topic -> topic.messageCount() > 0)
            .filter(topic -> !topic.name().startsWith("internal."))
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
                                                              String run) {
        Long measured = to.averageLatencyMs();
        if (measured == null) return Optional.empty();   // the audit could not correlate the pair

        String evidence = run + " measured an average hop of " + formatMillis(measured)
            + " between " + from.topicName() + " and " + to.topicName()
            + " (flow \"" + flowName + "\", correlated on the id field of recent messages).";
        return Optional.of(transitLatency(
            "audit:hop-latency:" + from.topicName() + ">" + to.topicName(),
            MetricSuggestionSource.AUDIT,
            from.topicName(), to.topicName(), measured,
            "Processing latency " + from.topicName() + " → " + to.topicName(),
            "This hop is on a flow the audit reconstructed, and it is where a stalled consumer or a "
                + "slow enrichment shows up first — as time, before it shows up as a backlog.",
            List.of(evidence)));
    }

    /** The same KPI, from a chain the operator traced by hand rather than from a naming convention. */
    private Optional<MetricSuggestion> transitLatencyFromChain(FlowChainEvidence chain,
                                                               FlowChainEvidence.FlowChainHop from,
                                                               FlowChainEvidence.FlowChainHop to) {
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
            from.topic(), to.topic(), basis,
            "Processing latency " + from.topic() + " → " + to.topic(),
            "A key was traced across this hop, so the pair is real rather than inferred from a "
                + "naming convention — measuring it continuously turns one observation into a trend.",
            List.of(evidence.toString())));
    }

    private MetricSuggestion transitLatency(String id, MetricSuggestionSource source,
                                            String sourceTopic, String targetTopic,
                                            long measuredMs, String title, String rationale,
                                            List<String> evidence) {
        KeyColumn sourceKey = keyColumn(sourceTopic);
        KeyColumn targetKey = keyColumn(targetTopic);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sourceSql", correlationSql(sourceTopic, sourceKey.column()));
        params.put("targetSql", correlationSql(targetTopic, targetKey.column()));
        params.put("sourceTopic", sourceTopic);
        params.put("targetTopic", targetTopic);

        // 2× / 4× of what was measured: high enough that ordinary variation does not page anyone,
        // low enough that a hop taking four times as long is not "normal". Both are stated.
        Double warning  = measuredMs > 0 ? Math.ceil(measuredMs * 2.0) : null;
        Double critical = measuredMs > 0 ? Math.ceil(measuredMs * 4.0) : null;
        String basis = measuredMs > 0
            ? "Warning at 2× and critical at 4× the " + formatMillis(measuredMs)
              + " that was measured — a multiple of an observation, not a round number."
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

        MetricConfig metric = new MetricConfig(
            null,
            metricName(source, "latency", sourceTopic, targetTopic),
            "GAUGE",
            null,
            "Average latency between " + sourceTopic + " and " + targetTopic
                + ", correlated on " + sourceKey.column() + ". Proposed from "
                + (source == MetricSuggestionSource.AUDIT ? "a cluster audit." : "a Stream Flow trace."),
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
            List.of("Both sides are bounded scans of the whole topic, so the gap only means "
                + "something while the two topics have comparable retention.",
                    "A legitimate filter between the two steps shows up here as a permanent gap — "
                + "set the thresholds around the level it normally sits at."),
            false, null, metric));
    }

    private Optional<MetricSuggestion> duplicateKpi(TopicAudit topic, String run) {
        KeyColumn key = keyColumn(topic.name());
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

    // ── Stream-Flow-derived proposals ─────────────────────────────────────────

    private List<MetricSuggestion> fromFlowChains(List<FlowChainEvidence> chains) {
        List<MetricSuggestion> suggestions = new ArrayList<>();
        for (FlowChainEvidence chain : chains) {
            List<FlowChainEvidence.FlowChainHop> hops = nullSafe(chain.hops()).stream()
                .filter(hop -> hop != null && hop.topic() != null && !hop.topic().isBlank())
                .toList();
            if (hops.size() < 2) continue;   // a single sighting is not a path

            for (int i = 1; i < hops.size(); i++) {
                transitLatencyFromChain(chain, hops.get(i - 1), hops.get(i)).ifPresent(suggestions::add);
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

    // ── Naming, SQL, dedupe ───────────────────────────────────────────────────

    /** A column that exists in the registered table, or the audit's own convention plus a caveat. */
    private record KeyColumn(String column, boolean inferred) {
        String describe(String topic) {
            return inferred
                ? "`" + column + "` (assumed on " + topic + " — no registered table to read a schema from)"
                : "`" + column + "` (a column of the registered table for " + topic + ")";
        }
    }

    private KeyColumn keyColumn(String topic) {
        Map<String, String> schema;
        try {
            schema = flinkSqlService.getTableSchema(DdlGeneratorService.toTableName(topic));
        } catch (RuntimeException e) {
            log.debug("No schema for {} while suggesting metrics: {}", topic, e.toString());
            schema = Map.of();
        }
        if (schema.isEmpty()) return new KeyColumn(FALLBACK_KEY_COLUMN, true);

        Map<String, String> byLowerCase = new LinkedHashMap<>();
        schema.keySet().forEach(column -> byLowerCase.putIfAbsent(column.toLowerCase(Locale.ROOT), column));
        for (String candidate : KEY_COLUMN_CANDIDATES) {
            String match = byLowerCase.get(candidate);
            if (match != null) return new KeyColumn(match, false);
        }
        // Nothing recognisable: name the convention rather than picking an arbitrary column, which
        // would produce a metric that runs and measures nothing.
        return new KeyColumn(FALLBACK_KEY_COLUMN, true);
    }

    private String correlationSql(String topic, String keyColumn) {
        return "SELECT `" + keyColumn + "` AS match_key, `" + TIME_COLUMN + "` AS event_time\n"
            + "FROM " + DdlGeneratorService.toTableName(topic);
    }

    private String countSql(String topic) {
        return "SELECT COUNT(*) AS metric_value\nFROM " + DdlGeneratorService.toTableName(topic);
    }

    private String metricName(MetricSuggestionSource source, String kind, String from, String to) {
        String prefix = source == MetricSuggestionSource.AUDIT ? "gauge" : "gauge_traced";
        return prefix + "_" + kind + "_" + DdlGeneratorService.toTableName(from)
            + "_to_" + DdlGeneratorService.toTableName(to);
    }

    /**
     * The audit and a trace very often describe the same hop. Keeping both would put two cards on
     * the same pair of topics; the first one wins because the list is built audit-first and the
     * audit's evidence is the broader of the two.
     */
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
                    true, metric.name(), suggestion.metric()))
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
