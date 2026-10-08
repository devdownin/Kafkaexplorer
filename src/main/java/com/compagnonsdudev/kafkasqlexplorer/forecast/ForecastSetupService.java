// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricConfig;
import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpCatalogService;
import com.compagnonsdudev.kafkasqlexplorer.service.MetricService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Operator REST preparation. Previewing never executes SQL, inference or configuration writes. */
@Service
@EnableConfigurationProperties(McpProperties.class)
public class ForecastSetupService {
  private static final List<String> TOOLS = List.of("kex_list_forecastable_metrics", "kex_metric_history",
      "kex_forecast_metric", "kex_get_forecast_quality", "kex_list_predicted_threshold_breaches");
  private static final ObjectMapper JSON = new ObjectMapper()
      .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
  private final ForecastingProperties root;
  private final McpProperties mcp;
  private final KafkaConfig kafka;
  private final MetricService metrics;
  private final ObjectProvider<McpCatalogService> catalog;
  private final ObjectProvider<MetricObservationStore> history;
  private final ObjectProvider<TimesFmClient> inference;
  private final ObjectProvider<ForecastPilotService> pilot;
  private final ObjectProvider<MetricSeriesPreparationService> preparation;
  private volatile Readiness probeCache;

  public ForecastSetupService(ForecastingProperties root, McpProperties mcp, KafkaConfig kafka,
      MetricService metrics, ObjectProvider<McpCatalogService> catalog,
      ObjectProvider<MetricObservationStore> history, ObjectProvider<TimesFmClient> inference,
      ObjectProvider<ForecastPilotService> pilot, ObjectProvider<MetricSeriesPreparationService> preparation) {
    this.root = root; this.mcp = mcp; this.kafka = kafka; this.metrics = metrics;
    this.catalog = catalog; this.history = history; this.inference = inference;
    this.pilot = pilot; this.preparation = preparation;
  }

  public record Check(String id, String state, String detail, String action) {}
  public record Readiness(long checkedAt, boolean probed, List<Check> checks) {}
  public record Candidate(String metricId, String name, String definitionVersion, String unit,
      String transformation, List<String> topics, List<String> groups, boolean eligible,
      boolean enrolled, List<String> blockers) {}
  public record Candidates(List<Candidate> metrics, int total, boolean truncated, String clusterId, String collectorId) {}
  public record DraftRequest(String metricId, String definitionVersion, String environment, String unit,
      String clusterId, String collectorId, List<String> topics, List<String> groups, long stepMillis,
      int horizon, Double threshold, String direction, boolean confirmed, String seasonality) {}
  public record Draft(String configuration, String seriesId, List<String> instructions) {}
  public record Progress(String seriesId, String state, String reason, Integer observedPoints,
      int requiredPoints, Integer missingPoints, Integer imputedPoints, long checkedAt, Long nextScheduledAt,
      long stepMillis, int horizon, List<String> topics, List<String> groups,
      ForecastPilotService.PredictedBreach breach, String breachState) {}

  /**
   * Samples per cycle at this sampling interval; 1 means no seasonality.
   *
   * <p>The assistant used to write 1 for every series, which made {@code SEASONAL_NAIVE} the same
   * estimator as {@code LAST_VALUE} — four baselines compared, three distinct — and left the
   * fallback a flat line for metrics that follow the hour or the day. A cycle must be a whole
   * number of samples, at least two and no more than the 512-point context.
   */
  static int seasonLength(String seasonality, long stepMillis) {
    String cycle = seasonality == null ? "NONE" : seasonality;
    long period = switch (cycle) {
      case "NONE" -> 0L;
      case "HOURLY" -> 3_600_000L;
      case "DAILY" -> 86_400_000L;
      default -> throw new IllegalArgumentException("Seasonality must be NONE, HOURLY or DAILY");
    };
    if (period == 0) return 1;
    if (stepMillis <= 0 || period % stepMillis != 0 || period / stepMillis < 2 || period / stepMillis > 512)
      throw new IllegalArgumentException(
          "A " + cycle.toLowerCase(java.util.Locale.ROOT) + " cycle is not 2 to 512 whole samples at this sampling interval");
    return (int) (period / stepMillis);
  }

  public Readiness readiness() {
    var checks = new ArrayList<Check>();
    checks.add(new Check("pilot", root.getPilot().isEnabled() ? "READY" : "CONFIG_REQUIRED",
        root.getPilot().isEnabled() ? "Forecast feature enabled" : "Forecast feature disabled",
        "Set explorer.forecasting.pilot.enabled=true"));
    checks.add(new Check("mcp", mcp.isEnabled() ? "READY" : "CONFIG_REQUIRED",
        mcp.isEnabled() ? "Server MCP enabled; the agent connection must be verified from the agent"
          : "Server MCP disabled", "Enable explorer.mcp.enabled, configure agent URL and authentication, then reconnect"));
    var c = catalog.getIfAvailable();
    var exposed = c == null ? Set.<String>of() : c.exposed().stream()
        .map(d -> d.name()).collect(java.util.stream.Collectors.toSet());
    var missing = TOOLS.stream().filter(t -> !exposed.contains(t)).toList();
    checks.add(new Check("tools", missing.isEmpty() ? "READY" : "CONFIG_REQUIRED",
        missing.isEmpty() ? "All five forecast tools published" : "Missing tools: " + String.join(", ", missing),
        "Check pilot.enabled and explorer.mcp.tools allow/deny lists; reconnect the agent"));
    checks.add(new Check("postgres", root.getHistory().isEnabled() ? "NOT_CHECKED" : "CONFIG_REQUIRED",
        root.getHistory().isEnabled() ? "History configured; connection not probed" : "Durable history disabled",
        "Configure PostgreSQL and explicitly enrolled metric IDs, then test dependencies"));
    checks.add(new Check("timesfm", root.getInference().isEnabled() ? "NOT_CHECKED" : "CONFIG_REQUIRED",
        root.getInference().isEnabled() ? "Inference configured; readiness not probed" : "TimesFM disabled",
        "Start the forecasts Docker stack or configure TimesFM origin and token, then test dependencies"));
    int approved = root.getPilot().getSeries().size();
    checks.add(new Check("series", approved > 0 ? "READY" : "CONFIG_REQUIRED",
        approved + " approved series", "Use the configuration assistant, review the file and restart with it"));
    boolean scope = approved > 0 && root.getPilot().getSeries().stream().allMatch(this::configuredScopePermits);
    checks.add(new Check("scope", scope ? "READY" : "CONFIG_REQUIRED",
        scope ? "Configured MCP environment/topic/group scope permits every approved source"
          : "Configured MCP scope blocks approved sources or no sources are approved",
        "Review allowed-forecast-environments and existing topic/group scope policies"));
    checks.add(new Check("history", "NOT_CHECKED", "History sufficiency is evaluated per approved series",
        "Select a series to inspect its 512-point context and rejection reason"));
    return new Readiness(System.currentTimeMillis(), false, List.copyOf(checks));
  }

  private boolean configuredScopePermits(ForecastPilotProperties.Series s) {
    var guard = new com.compagnonsdudev.kafkasqlexplorer.mcp.guard.ToolGuard(mcp,
        new com.compagnonsdudev.kafkasqlexplorer.mcp.guard.DlpScrubber(mcp));
    try {
      guard.checkForecastEnvironment(s.environment()); guard.checkTopicScope(s.topics()); guard.checkGroupScope(s.groups());
      return true;
    } catch (com.compagnonsdudev.kafkasqlexplorer.mcp.guard.McpToolException e) { return false; }
  }

  /** On-demand, cached and serialised; configuration checks do not claim remote connectivity. */
  public synchronized Readiness probe() {
    if (probeCache != null && System.currentTimeMillis() - probeCache.checkedAt() < 30000) return probeCache;
    var checks = new ArrayList<>(readiness().checks());
    if (root.getHistory().isEnabled()) {
      boolean ready;
      try { var h = history.getIfAvailable(); ready = h != null && h.checkConnection(); }
      catch (Exception e) { ready = false; }
      checks.set(3, new Check("postgres", ready ? "READY" : "UNAVAILABLE",
          ready ? "PostgreSQL connection verified (no schema changes)" : "PostgreSQL connection failed",
          "Check database health, credentials and JDBC connectivity; no credentials are returned here"));
    }
    if (root.getInference().isEnabled()) {
      boolean ready;
      try { var i = inference.getIfAvailable(); ready = i != null && i.isReady(); }
      catch (Exception e) { ready = false; }
      checks.set(4, new Check("timesfm", ready ? "READY" : "UNAVAILABLE",
          ready ? "TimesFM /health/ready verified; no forecast requested" : "TimesFM is not ready",
          "Check model download, service health and network connectivity"));
    }
    probeCache = new Readiness(System.currentTimeMillis(), true, List.copyOf(checks));
    return probeCache;
  }

  public Candidates candidates() {
    var all = metrics.getAllMetrics();
    return new Candidates(all.stream().sorted(java.util.Comparator.comparing(MetricConfig::id))
        .limit(100).map(this::candidate).toList(), all.size(), all.size() > 100, root.getHistory().getClusterId(), root.getHistory().getCollectorId());
  }

  private Candidate candidate(MetricConfig m) {
    try { m = MetricService.normalizeObservationDefinition(m); }
    catch (IllegalArgumentException e) {
      return new Candidate(m.id(), m.name(), MetricObservation.definitionVersion(m), "UNKNOWN", "GAUGE_MEAN",
          List.of(), List.of(), false, root.getHistory().getMetricIds().contains(m.id()),
          List.of("Invalid metric definition; edit and save the metric before enrollment"));
    }
    var p = m.templateParams() == null ? Map.<String, Object>of() : m.templateParams();
    String unit = p.get("unit") instanceof String u ? u : "UNKNOWN";
    if (Set.of("CONSUMER_TIME_LAG", "TOPIC_TRANSIT_LATENCY").contains(String.valueOf(m.templateType())))
      unit = "milliseconds";
    var blockers = new ArrayList<String>();
    if (unit.isBlank() || unit.equals("UNKNOWN")) blockers.add("Set a known unit in the metric template parameters");
    String type = m.type() == null ? "GAUGE" : m.type().toUpperCase(java.util.Locale.ROOT);
    if (!Set.of("GAUGE", "COUNTER").contains(type)) blockers.add("A scalar GAUGE or COUNTER is required");
    if (m.templateType() == null || m.templateType().equals("RAW_SQL"))
      blockers.add("Raw SQL has unverified source scope; use a supported template");
    if (m.labelFields() != null && !m.labelFields().isEmpty() || m.labelTopic() != null && !m.labelTopic().isBlank())
      blockers.add("Labelled metrics require a separately approved series; the assistant supports unlabelled values");
    if ("FLINK_MANAGED_JOB".equals(m.executionMode())) blockers.add("Managed jobs do not capture scalar observations");
    if (m.errorMessage() != null && !m.errorMessage().isBlank()) blockers.add("Resolve the metric collection error first");
    var topics = strings(p, "topic", "leftTopic", "rightTopic", "sourceTopic", "targetTopic");
    var groups = strings(p, "group");
    return new Candidate(m.id(), m.name(), MetricObservation.definitionVersion(m), unit,
        type.equals("COUNTER") ? "COUNTER_RATE" : "GAUGE_MEAN", topics, groups, blockers.isEmpty(),
        root.getHistory().getMetricIds().contains(m.id()), List.copyOf(blockers));
  }

  private static List<String> strings(Map<String, Object> p, String... keys) {
    var values = new java.util.LinkedHashSet<String>();
    for (String key : keys) if (p.get(key) instanceof String s && !s.isBlank()) values.add(s);
    return List.copyOf(values);
  }

  public Draft draft(DraftRequest r) {
    if (r == null || !r.confirmed()) throw new IllegalArgumentException("Review and confirm all sources before exporting");
    var m = metrics.getAllMetrics().stream().filter(v -> v.id().equals(r.metricId())).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown metric"));
    m = MetricService.normalizeObservationDefinition(m);
    var c = candidate(m);
    if (!c.eligible()) throw new IllegalArgumentException(String.join("; ", c.blockers()));
    if (!c.definitionVersion().equals(r.definitionVersion())) throw new IllegalArgumentException("Metric changed; reload candidates and review again");
    if (!c.unit().equals(r.unit())) throw new IllegalArgumentException("Unit must match the captured metric; edit metric metadata first");
    bounded(r.environment(), "environment"); bounded(r.clusterId(), "cluster ID"); bounded(r.collectorId(), "collector ID");
    bounded(m.id(), "metric ID"); bounded(r.unit(), "unit");
    validateSources(r.topics(), false); validateSources(r.groups(), true);
    if (!r.topics().containsAll(c.topics()) || !r.groups().containsAll(c.groups()))
      throw new IllegalArgumentException("Declared sources must include every template topic and group");
    var h = root.getHistory();
    if (h.isEnabled() && (!h.getClusterId().equals(r.clusterId()) || !h.getCollectorId().equals(r.collectorId())))
      throw new IllegalArgumentException("Use the existing history cluster and collector identities");
    var profile = new SeriesPreparationProfile(r.stepMillis(), 512, SeriesPreparationProfile.Transformation.valueOf(c.transformation()));
    // The durable history must cover the entire requested context, even at the largest cadence.
    if (Math.multiplyExact(r.stepMillis(), 513L) > h.getRetention().toMillis())
      throw new IllegalArgumentException("Context exceeds history retention; choose a shorter cadence or increase retention first");
    String version = MetricObservation.collectedVersion(m, kafka.getBootstrapServers(), r.collectorId());
    // This template emits its pinned source identifiers as labels even without labelFields.
    // Include them so the approved series resolves the observations captured by MetricService.
    Map<String, String> labels = "CONSUMER_TIME_LAG".equals(m.templateType())
        ? Map.of("topic", String.valueOf(m.templateParams().get("topic")),
                 "group", String.valueOf(m.templateParams().get("group"))) : Map.of();
    String id = MetricObservation.seriesId(r.clusterId(), version, m.id(), "value", labels);
    if (r.threshold() != null && !Set.of("ABOVE", "BELOW").contains(String.valueOf(r.direction())))
      throw new IllegalArgumentException("Threshold direction must be ABOVE or BELOW");
    var threshold = r.threshold() == null ? null : new ForecastThresholdPolicy(id, version, r.threshold(),
        ForecastThresholdPolicy.Direction.valueOf(r.direction()), r.horizon(), .9, "READY", ForecastThresholdPolicy.Visibility.SHADOW);
    var spec = new ForecastPilotProperties.Series(id, m.id(), r.environment(), version, r.unit(),
        r.topics(), r.groups(), profile, r.horizon(), seasonLength(r.seasonality(), r.stepMillis()), threshold,
        // No verdict exists before a full quality block, so a smaller minimum read as a promise the
        // gate could not keep.
        null, .8, ForecastQualityWindow.BLOCK_POINTS);
    var approved = new ArrayList<>(root.getPilot().getSeries());
    if (approved.stream().anyMatch(existing -> existing.seriesId().equals(id)))
      throw new IllegalArgumentException("This series is already approved; edit its deployment configuration explicitly");
    approved.add(spec);
    var validation = new ForecastPilotProperties();
    validation.setMaxSeries(root.getPilot().getMaxSeries());
    validation.setInterval(root.getPilot().getInterval()); validation.setRetention(root.getPilot().getRetention());
    validation.setSeries(approved); validation.validate();
    var enrolled = new java.util.TreeSet<>(h.getMetricIds()); enrolled.add(m.id());
    if (enrolled.size() > 100) throw new IllegalArgumentException("History enrollment exceeds 100 metrics");
    String yaml = "# Reviewed forecast configuration. Existing approvals are preserved; export does not change runtime.\nexplorer:\n  forecasting:\n"
        + "    history:\n      enabled: true\n      cluster-id: " + quote(r.clusterId()) + "\n      collector-id: " + quote(r.collectorId())
        + "\n      metric-ids: " + quote(enrolled)
        + "\n    pilot:\n      enabled: true\n      interval: " + quote(root.getPilot().getInterval().toString())
        + "\n      retention: " + quote(root.getPilot().getRetention().toString())
        + "\n      max-series: " + root.getPilot().getMaxSeries()
        + "\n      series: " + seriesYaml(approved) + "\n";
    return new Draft(yaml, id, List.of("Review every SQL dependency, topic and group; structured suggestions may be incomplete",
        "Merge with your deployment configuration; existing runtime-approved series and enrollment are preserved",
        "Configure PostgreSQL credentials and TimesFM separately; secrets are never exported",
        "For the Docker stack: save as .forecast-stack/config/forecasts.yml and rerun bin/forecast-stack.sh",
        "Review explorer.mcp.allowed-forecast-environments for " + r.environment() + " and existing topic/group policies",
        "Restart with this file, collect 512 buckets, then review realised quality before activation"));
  }

  private static void bounded(String s, String label) {
    if (s == null || s.isBlank() || s.length() > 128 || s.contains("${") || s.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Invalid " + label + " (1..128 printable characters required)");
  }
  private static void validateSources(List<String> sources, boolean emptyAllowed) {
    if (sources == null || sources.size() > 100 || !emptyAllowed && sources.isEmpty()
        || sources.stream().distinct().count() != sources.size()) throw new IllegalArgumentException("Invalid source list");
    for (String s : sources) bounded(s, "source");
  }
  private static String seriesYaml(List<ForecastPilotProperties.Series> approved) {
    try { return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(approved).replace("\n", "\n      "); }
    catch (Exception e) { throw new IllegalArgumentException("Cannot export approved series"); }
  }
  private static String quote(Object value) {
    try { return JSON.writeValueAsString(value); }
    catch (Exception e) { throw new IllegalArgumentException("Cannot export configuration"); }
  }

  public Progress progress(String id) throws Exception {
    var p = pilot.getIfAvailable();
    if (p == null) throw new IllegalArgumentException("Pilot disabled");
    var s = p.resolve(id); // Authorize the exact declared source before any history read.
    long now = System.currentTimeMillis();
    var prep = preparation.getIfAvailable();
    if (prep == null) return progress(s, "UNAVAILABLE", "History preparation unavailable", null, null, null, now, p, null, "UNAVAILABLE");
    PreparedMetricSeries context;
    try { context = prep.prepare(id, s.definitionVersion(), s.unit(), now, s.profile()); }
    catch (Exception e) { return progress(s, "UNAVAILABLE", "History read failed; check PostgreSQL connectivity", null, null, null, now, p, null, "UNAVAILABLE"); }
    ForecastPilotService.PredictedBreach breach = null;
    String breachState = s.threshold() == null ? "NOT_CONFIGURED" : "NOT_EVALUATED";
    if (s.threshold() != null) {
      try { breach = p.breach(id); if (breach != null) breachState = "EVALUATED"; }
      catch (Exception e) { breachState = "UNAVAILABLE"; }
    }
    return progress(s, context.status().name(), context.reason(), context.observedPoints(),
        context.missingPoints(), context.imputedPoints(), now, p, breach, breachState);
  }

  private static Progress progress(ForecastPilotProperties.Series s, String state, String reason,
      Integer observed, Integer missing, Integer imputed, long now, ForecastPilotService p,
      ForecastPilotService.PredictedBreach breach, String breachState) {
    return new Progress(s.seriesId(), state, reason, observed, 512, missing, imputed, now,
        p.nextScheduledAt(), s.profile().stepMillis(), s.horizon(), s.topics(), s.groups(), breach, breachState);
  }
}
