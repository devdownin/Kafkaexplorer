// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import com.compagnonsdudev.kafkasqlexplorer.config.KafkaConfig;
import com.compagnonsdudev.kafkasqlexplorer.domain.MetricConfig;
import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.compagnonsdudev.kafkasqlexplorer.mcp.observability.McpCatalogService;
import com.compagnonsdudev.kafkasqlexplorer.service.MetricService;
import com.compagnonsdudev.kafkasqlexplorer.web.ForecastSetupController;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.ByteArrayResource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ForecastSetupServiceTest {
  private final ForecastingProperties root = new ForecastingProperties();
  private final MetricService metrics = mock(MetricService.class);
  private final MetricObservationStore history = mock(MetricObservationStore.class);
  private final TimesFmClient client = mock(TimesFmClient.class);
  private final ForecastPilotService pilot = mock(ForecastPilotService.class);
  private final MetricSeriesPreparationService preparation = mock(MetricSeriesPreparationService.class);
  private final McpProperties mcp = new McpProperties();
  private ForecastSetupService setup;
  private MetricConfig metric;

  private static <T> ObjectProvider<T> provider(T instance) {
    var factory = new org.springframework.beans.factory.support.StaticListableBeanFactory();
    if (instance != null) factory.addBean("instance", instance);
    Class<?> type = instance == null ? Object.class : instance.getClass();
    return (ObjectProvider<T>) factory.getBeanProvider(type);
  }
  private MetricConfig metric(String template, Map<String, Object> params, List<String> labels) {
    return new MetricConfig("lag", "Consumer lag", "GAUGE", "private SQL credential", "description",
        null, null, 4d, 1L, null, List.of(), Map.of(), "private DDL credential", template, params,
        "TEMPLATE_BOUNDED_SCAN", null, labels);
  }
  @BeforeEach void configure() {
    metric = metric("CONSUMER_TIME_LAG", Map.of("topic", "orders", "group", "worker"), List.of());
    when(metrics.getAllMetrics()).thenAnswer(i -> List.of(metric));
    var kafka = new KafkaConfig(); kafka.setBootstrapServers("kafka:29092");
    setup = new ForecastSetupService(root, mcp, kafka, metrics,
        provider((McpCatalogService) null), provider(history), provider(client), provider(pilot), provider(preparation));
  }
  private ForecastSetupService.DraftRequest request(boolean confirmed, String definition, String unit,
      List<String> topics, List<String> groups, long cadence, int horizon) {
    return new ForecastSetupService.DraftRequest("lag", definition, "local", unit, "cluster", "collector",
        topics, groups, cadence, horizon, 100d, "ABOVE", confirmed, "NONE");
  }
  private ForecastSetupService.DraftRequest valid() {
    return request(true, MetricObservation.definitionVersion(metric), "milliseconds", List.of("orders"), List.of("worker"), 60000, 30);
  }
  @Test void setupBootsWithMcpHistoryAndInferenceDisabled() {
    new org.springframework.boot.test.context.runner.ApplicationContextRunner()
        .withUserConfiguration(ForecastSetupService.class, ForecastingProperties.class, ForecastPilotConfiguration.class)
        .withBean(KafkaConfig.class, KafkaConfig::new)
        .withBean(MetricService.class, () -> metrics)
        .withBean(io.micrometer.core.instrument.MeterRegistry.class, io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
        .withPropertyValues("explorer.mcp.enabled=false")
        .run(context -> {
          assertNull(context.getStartupFailure());
          assertEquals("CONFIG_REQUIRED", context.getBean(ForecastSetupService.class).readiness().checks().get(1).state());
          assertEquals(0, context.getBeansOfType(TimesFmClient.class).size());
          assertEquals(0, context.getBeansOfType(MetricObservationStore.class).size());
        });
  }
  @Test void defaultDiagnosticDoesNotProbeOrReadAnything() {
    var result = setup.readiness();
    assertFalse(result.probed());
    assertEquals("READY", result.checks().getFirst().state());
    assertEquals("CONFIG_REQUIRED", result.checks().get(3).state());
    verifyNoInteractions(history, client, pilot, preparation, metrics);
  }
  @Test void diagnosticUsesTheSameEnvironmentTopicAndGroupScopeAsMcp() {
    var spec = new ForecastPilotProperties.Series("a".repeat(64), "lag", "local", "v1", "milliseconds",
        List.of("orders"), List.of("worker"), SeriesPreparationProfile.initial(SeriesPreparationProfile.Transformation.GAUGE_MEAN), 30, 1, null, null, .8, 30);
    root.getPilot().setSeries(List.of(spec)); mcp.setAllowedForecastEnvironments(List.of("local"));
    mcp.setAllowedTopicPrefixes(List.of("other"));
    assertEquals("CONFIG_REQUIRED", setup.readiness().checks().get(6).state());
    mcp.setAllowedTopicPrefixes(List.of("orders")); mcp.setAllowedGroupPrefixes(List.of("other"));
    assertEquals("CONFIG_REQUIRED", setup.readiness().checks().get(6).state());
    mcp.setAllowedGroupPrefixes(List.of("worker"));
    assertEquals("READY", setup.readiness().checks().get(6).state());
    verifyNoInteractions(history, client, pilot, preparation);
  }
  @Test void probeIsCachedSuppressesSensitiveExceptionsAndNeverInfers() throws Exception {
    root.getHistory().setEnabled(true); root.getInference().setEnabled(true);
    when(history.checkConnection()).thenThrow(new IllegalStateException("SECRET-PASSWORD"));
    when(client.isReady()).thenReturn(true);
    var first = setup.probe(); var second = setup.probe();
    assertSame(first, second);
    assertEquals("UNAVAILABLE", first.checks().get(3).state());
    assertEquals("READY", first.checks().get(4).state());
    assertFalse(first.toString().contains("SECRET-PASSWORD"));
    verify(history, times(1)).checkConnection(); verify(client, times(1)).isReady();
    verifyNoMoreInteractions(history, client); verifyNoInteractions(preparation, pilot);
  }
  @Test void candidateCatalogueDoesNotExposeSqlOrDdlAndSeparatesMetadataFromEnrollment() {
    var c = setup.candidates().metrics().getFirst();
    assertTrue(c.eligible()); assertFalse(c.enrolled()); assertEquals("milliseconds", c.unit());
    assertEquals(List.of("orders"), c.topics()); assertEquals(List.of("worker"), c.groups());
    assertFalse(setup.candidates().toString().contains("credential"));
    // The floor a first forecast waits for, so the assistant can say how long that is.
    assertEquals(SeriesPreparationProfile.MIN_CONTEXT_POINTS, setup.candidates().minimumContextPoints());
    metric = metric("RAW_SQL", Map.of(), List.of("tenant"));
    c = setup.candidates().metrics().getFirst(); assertFalse(c.eligible()); assertEquals(3, c.blockers().size());
    verifyNoInteractions(history, client, pilot, preparation);
  }
  @Test void aCountTemplateIsEligibleWithoutAUnitParameterNobodyCanSet() {
    metric = metric("TOPIC_COUNT_DELTA", Map.of("leftTopic", "orders", "rightTopic", "orders.dlq",
        "countMode", "OFFSETS", "operation", "PERCENT_GAP"), List.of());
    var c = setup.candidates().metrics().getFirst();
    assertTrue(c.eligible(), c.blockers()::toString); assertEquals("percent", c.unit());
    assertEquals(List.of("orders", "orders.dlq"), c.topics());
  }
  @Test void restoredLegacyMetricUsesTheSameNormalizedDefinitionAsCollection() {
    metric = new MetricConfig("lag", "Legacy lag", "gauge", "sql", "description", null, null, 4d, 1L, null,
        null, null, null, "consumer_time_lag", Map.of("topic", "orders", "group", "worker"), null, null, null);
    var normalized = MetricService.normalizeObservationDefinition(metric);
    var candidate = setup.candidates().metrics().getFirst();
    assertTrue(candidate.eligible()); assertEquals(MetricObservation.definitionVersion(normalized), candidate.definitionVersion());
    var draft = setup.draft(request(true, candidate.definitionVersion(), candidate.unit(), candidate.topics(), candidate.groups(), 60000, 30));
    String version = MetricObservation.collectedVersion(normalized, "kafka:29092", "collector");
    assertEquals(MetricObservation.seriesId("cluster", version, "lag", "value", Map.of("topic", "orders", "group", "worker")), draft.seriesId());
    verifyNoInteractions(history, client, pilot, preparation);
  }
  @Test void catalogueIsDeterministicallyBounded() {
    when(metrics.getAllMetrics()).thenReturn(java.util.Collections.nCopies(101, metric));
    var c = setup.candidates(); assertEquals(100, c.metrics().size()); assertEquals(101, c.total()); assertTrue(c.truncated());
  }
  @Test void exportedYamlBindsToValidatedPilotAndTheRealCollectorIdentity() {
    var draft = setup.draft(valid());
    var yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new ByteArrayResource(draft.configuration().getBytes(StandardCharsets.UTF_8)));
    var bound = new Binder(ConfigurationPropertySources.from(new PropertiesPropertySource("draft", yaml.getObject())))
        .bind("explorer.forecasting", Bindable.of(ForecastingProperties.class)).get();
    bound.getPilot().validate();
    var spec = bound.getPilot().getSeries().getFirst();
    String version = MetricObservation.collectedVersion(metric, "kafka:29092", "collector");
    assertEquals(version, spec.definitionVersion());
    assertEquals(MetricObservation.seriesId("cluster", version, "lag", "value", Map.of("topic", "orders", "group", "worker")), spec.seriesId());
    assertEquals(draft.seriesId(), spec.seriesId()); assertEquals("milliseconds", spec.unit());
    assertEquals(ForecastThresholdPolicy.Visibility.SHADOW, spec.threshold().visibility());
    assertEquals(1, spec.seasonLength());
    // Horizon 30: six cohorts are 180 points, more than the 120-point floor.
    assertEquals(180, spec.minimumEvaluatedPoints());
    // Eligible metrics are recorded without being listed, so the export does not list this one.
    assertTrue(bound.getHistory().isEnrollEligible()); assertFalse(bound.getHistory().getMetricIds().contains("lag"));
    assertFalse(draft.configuration().contains("credential")); verifyNoInteractions(history, client, pilot, preparation);
  }
  @Test void aProduceRateCovariateIsDeclaredWithTheSeriesIdentityHistoryRecords() {
    var target = setup.draft(valid());
    var yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new ByteArrayResource(target.configuration().getBytes(StandardCharsets.UTF_8)));
    var spec = new Binder(ConfigurationPropertySources.from(new PropertiesPropertySource("draft", yaml.getObject())))
        .bind("explorer.forecasting", Bindable.of(ForecastingProperties.class)).get().getPilot().getSeries().getFirst();
    root.getPilot().setSeries(List.of(spec));
    var produced = new MetricConfig("produced", "Orders produced", "GAUGE", null, "description",
        null, null, null, null, null, List.of(), Map.of(), null, "KAFKA_CLUSTER_COUNT",
        Map.of("measurement", "PRODUCED_OFFSETS", "topic", "orders"), "TEMPLATE_BOUNDED_SCAN", null, List.of());
    when(metrics.getAllMetrics()).thenReturn(List.of(metric, produced));
    var draft = setup.covariateDraft(new ForecastSetupService.CovariateRequest(spec.seriesId(), "produced",
        MetricObservation.definitionVersion(produced), "cluster", "collector", true));
    var c = draft.covariate();
    String version = MetricObservation.collectedVersion(produced, "kafka:29092", "collector");
    assertEquals(MetricObservation.seriesId("cluster", version, "produced", "value", Map.of()), c.seriesId());
    assertEquals(SeriesPreparationProfile.Transformation.COUNTER_RATE, c.transformation());
    assertEquals("messages", c.unit());
    assertEquals(List.of("orders"), c.topics());
    var exported = new YamlPropertiesFactoryBean();
    exported.setResources(new ByteArrayResource(draft.configuration().getBytes(StandardCharsets.UTF_8)));
    var bound = new Binder(ConfigurationPropertySources.from(new PropertiesPropertySource("draft", exported.getObject())))
        .bind("explorer.forecasting", Bindable.of(ForecastingProperties.class)).get();
    assertEquals(List.of(c), bound.getPilot().getShadowCovariates());
    assertTrue(bound.getInference().getShadow().isEnabled());
    verifyNoInteractions(history, client, pilot, preparation);
  }
  @Test void aCovariateOnAnotherTopicIsRefused() {
    var target = setup.draft(valid());
    var yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new ByteArrayResource(target.configuration().getBytes(StandardCharsets.UTF_8)));
    var spec = new Binder(ConfigurationPropertySources.from(new PropertiesPropertySource("draft", yaml.getObject())))
        .bind("explorer.forecasting", Bindable.of(ForecastingProperties.class)).get().getPilot().getSeries().getFirst();
    root.getPilot().setSeries(List.of(spec));
    var produced = new MetricConfig("produced", "Payments produced", "GAUGE", null, "description",
        null, null, null, null, null, List.of(), Map.of(), null, "KAFKA_CLUSTER_COUNT",
        Map.of("measurement", "PRODUCED_OFFSETS", "topic", "payments"), "TEMPLATE_BOUNDED_SCAN", null, List.of());
    when(metrics.getAllMetrics()).thenReturn(List.of(metric, produced));
    var refused = assertThrows(IllegalArgumentException.class, () -> setup.covariateDraft(
        new ForecastSetupService.CovariateRequest(spec.seriesId(), "produced",
            MetricObservation.definitionVersion(produced), "cluster", "collector", true)));
    assertTrue(refused.getMessage().contains("topics its target declares"));
  }
  @Test void withEligibleEnrollmentOffTheExportListsTheMetric() {
    root.getHistory().setEnrollEligible(false);
    var yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new ByteArrayResource(setup.draft(valid()).configuration().getBytes(StandardCharsets.UTF_8)));
    var bound = new Binder(ConfigurationPropertySources.from(new PropertiesPropertySource("draft", yaml.getObject())))
        .bind("explorer.forecasting", Bindable.of(ForecastingProperties.class)).get();
    assertFalse(bound.getHistory().isEnrollEligible()); assertEquals(java.util.Set.of("lag"), bound.getHistory().getMetricIds());
  }
  @Test void refusalCoversApprovalStaleDefinitionsUnitScopeAndBounds() {
    assertThrows(IllegalArgumentException.class, () -> setup.draft(request(false, MetricObservation.definitionVersion(metric), "milliseconds", List.of("orders"), List.of("worker"), 60000, 30)));
    assertThrows(IllegalArgumentException.class, () -> setup.draft(request(true, "stale", "milliseconds", List.of("orders"), List.of("worker"), 60000, 30)));
    assertThrows(IllegalArgumentException.class, () -> setup.draft(request(true, MetricObservation.definitionVersion(metric), "seconds", List.of("orders"), List.of("worker"), 60000, 30)));
    assertThrows(IllegalArgumentException.class, () -> setup.draft(request(true, MetricObservation.definitionVersion(metric), "milliseconds", List.of("other"), List.of(), 60000, 30)));
    assertThrows(IllegalArgumentException.class, () -> setup.draft(request(true, MetricObservation.definitionVersion(metric), "milliseconds", List.of("orders"), List.of("worker"), 60000, 61)));
    assertThrows(IllegalArgumentException.class, () -> setup.draft(request(true, MetricObservation.definitionVersion(metric), "milliseconds", List.of("orders"), List.of("worker"), 86400000, 30)));
    verifyNoInteractions(history, client, pilot, preparation);
  }
  /** METRICS-FORECAST-UX-AUDIT.md B6: a series started from the assistant is stopped, not edited in a file. */
  @Test void aMetricAlreadyForecastFromTheAssistantIsReferredToTheStopButton() {
    var id = setup.draft(valid()).seriesId();
    var started = new ForecastPilotProperties.Series(id, "lag", "local", "v1", "milliseconds",
        List.of("orders"), List.of(), SeriesPreparationProfile.initial(SeriesPreparationProfile.Transformation.GAUGE_MEAN), 30, 1, null, null, .8, 30);
    root.getPilot().replaceRuntime(List.of(started));
    var refusal = assertThrows(IllegalArgumentException.class, () -> setup.draft(valid()));
    assertTrue(refusal.getMessage().contains("stop that series from Metrics Forecast"), refusal.getMessage());
  }
  @Test void existingIdentityCannotBeChangedAndApprovedSeriesArePreserved() {
    root.getHistory().setEnabled(true); root.getHistory().setClusterId("existing");
    assertThrows(IllegalArgumentException.class, () -> setup.draft(valid()));
    root.getHistory().setEnabled(false);
    var bound = new ForecastPilotProperties.Series("a".repeat(64), "lag", "local", "v1", "milliseconds",
        List.of("orders"), List.of(), SeriesPreparationProfile.initial(SeriesPreparationProfile.Transformation.GAUGE_MEAN), 30, 1, null, null, .8, 30);
    root.getPilot().setSeries(List.of(bound));
    var yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new ByteArrayResource(setup.draft(valid()).configuration().getBytes(StandardCharsets.UTF_8)));
    var result = new Binder(ConfigurationPropertySources.from(new PropertiesPropertySource("draft", yaml.getObject())))
        .bind("explorer.forecasting", Bindable.of(ForecastingProperties.class)).get();
    assertEquals(bound, result.getPilot().getSeries().getFirst());
    assertEquals(2, result.getPilot().getSeries().size());
    root.getPilot().setMaxSeries(1);
    assertThrows(IllegalArgumentException.class, () -> setup.draft(valid()));
  }
  @Test void unknownSourceIsRefusedBeforePersistenceAndControllerReturns404() throws Exception {
    when(pilot.resolve("unknown")).thenThrow(new IllegalArgumentException());
    assertEquals(404, new ForecastSetupController(setup).progress("unknown").getStatusCode().value());
    verifyNoInteractions(history, client, preparation);
  }
  @Test void progressPreservesRejectedHistoryReasonAndNeverCallsInference() throws Exception {
    var spec = new ForecastPilotProperties.Series("a".repeat(64), "lag", "local", "v1", "milliseconds",
        List.of("orders"), List.of("worker"), SeriesPreparationProfile.initial(SeriesPreparationProfile.Transformation.GAUGE_MEAN), 30, 1, null, null, .8, 30);
    when(pilot.resolve(spec.seriesId())).thenReturn(spec); when(pilot.nextScheduledAt()).thenReturn(123L);
    when(preparation.prepare(eq(spec.seriesId()), eq("v1"), eq("milliseconds"), anyLong(), eq(spec.profile())))
        .thenReturn(new PreparedMetricSeries(PreparedMetricSeries.Status.SCOPE_CHANGED, "unverified scope", spec.seriesId(),
            "v1", "milliseconds", "milliseconds", 0, 1, spec.profile(), "p", "i", 12, 500, 0, List.of()));
    var progress = setup.progress(spec.seriesId());
    assertEquals("SCOPE_CHANGED", progress.state()); assertEquals("unverified scope", progress.reason());
    assertEquals(12, progress.observedPoints()); assertEquals(512, progress.requiredPoints());
    assertEquals(123L, progress.nextScheduledAt()); assertEquals("NOT_CONFIGURED", progress.breachState());
    verifyNoInteractions(client, history); verify(pilot, never()).breach(anyString());
  }
  /** METRICS-FORECAST-UX-AUDIT.md P2: the history is read once per step, not once per poll. */
  @Test void progressReadsTheHistoryOncePerStep() throws Exception {
    // A day-long step, so two calls a few milliseconds apart cannot straddle a boundary.
    var profile = new SeriesPreparationProfile(86_400_000L, 512, SeriesPreparationProfile.Transformation.GAUGE_MEAN);
    var spec = new ForecastPilotProperties.Series("b".repeat(64), "lag", "local", "v1", "milliseconds",
        List.of("orders"), List.of(), profile, 30, 1, null, null, .8, 30);
    when(pilot.resolve(spec.seriesId())).thenReturn(spec);
    when(preparation.prepare(eq(spec.seriesId()), eq("v1"), eq("milliseconds"), anyLong(), eq(profile)))
        .thenReturn(new PreparedMetricSeries(PreparedMetricSeries.Status.WARMING_UP, "collecting", spec.seriesId(),
            "v1", "milliseconds", "milliseconds", 0, 1, profile, "p", "i", 9, 503, 0, List.of()));
    assertEquals(9, setup.progress(spec.seriesId()).observedPoints());
    assertEquals(9, setup.progress(spec.seriesId()).observedPoints());
    verify(preparation, times(1)).prepare(anyString(), anyString(), anyString(), anyLong(), any());
  }
  @Test void progressOutageIsUnavailableRatherThanEmptyHistory() throws Exception {
    var spec = new ForecastPilotProperties.Series("a".repeat(64), "lag", "local", "v1", "milliseconds",
        List.of("orders"), List.of(), SeriesPreparationProfile.initial(SeriesPreparationProfile.Transformation.GAUGE_MEAN), 30, 1, null, null, .8, 30);
    when(pilot.resolve(spec.seriesId())).thenReturn(spec);
    when(preparation.prepare(anyString(), anyString(), anyString(), anyLong(), any())).thenThrow(new Exception("private detail"));
    var result = setup.progress(spec.seriesId()); assertEquals("UNAVAILABLE", result.state());
    assertFalse(result.reason().contains("private detail")); verifyNoInteractions(client);
  }

  @Test
  void seasonalityIsCountedInSamplesAndRefusedWhenItCannotBe() {
    assertEquals(1, ForecastSetupService.seasonLength("NONE", 60000));
    assertEquals(1, ForecastSetupService.seasonLength(null, 60000));
    assertEquals(60, ForecastSetupService.seasonLength("HOURLY", 60000));
    assertEquals(288, ForecastSetupService.seasonLength("DAILY", 300000));
    assertThrows(IllegalArgumentException.class, () -> ForecastSetupService.seasonLength("DAILY", 60000));
    assertThrows(IllegalArgumentException.class, () -> ForecastSetupService.seasonLength("HOURLY", 7000));
    assertThrows(IllegalArgumentException.class, () -> ForecastSetupService.seasonLength("WEEKLY", 60000));
  }

  @Test
  void anHourlyCycleReachesTheExportedSeries() throws Exception {
    var hourly = valid();
    hourly = new ForecastSetupService.DraftRequest(hourly.metricId(), hourly.definitionVersion(), hourly.environment(),
        hourly.unit(), hourly.clusterId(), hourly.collectorId(), hourly.topics(), hourly.groups(), hourly.stepMillis(),
        hourly.horizon(), hourly.threshold(), hourly.direction(), true, "HOURLY");
    var yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new ByteArrayResource(setup.draft(hourly).configuration().getBytes(StandardCharsets.UTF_8)));
    var bound = new Binder(ConfigurationPropertySources.from(new PropertiesPropertySource("draft", yaml.getObject())))
        .bind("explorer.forecasting", Bindable.of(ForecastingProperties.class)).get();
    assertEquals(60, bound.getPilot().getSeries().getFirst().seasonLength());
  }
  @Test void theSuggestedIntervalHoldsTwoCollectionsOfTheMetric() {
    when(metrics.collectionIntervalMs(any())).thenReturn(30_000L);
    assertEquals(60_000L, setup.candidates().metrics().getFirst().suggestedStepMillis());
    when(metrics.collectionIntervalMs(any())).thenReturn(300_000L);
    assertEquals(900_000L, setup.candidates().metrics().getFirst().suggestedStepMillis());
    when(metrics.collectionIntervalMs(any())).thenReturn(3_600_000L);
    assertEquals(900_000L, setup.candidates().metrics().getFirst().suggestedStepMillis(), "Never beyond the largest offered");
  }
  @Test void runtimeApprovalSaysWhyItIsUnavailableAndWritesNothing() throws Exception {
    assertTrue(setup.candidates().applyUnavailable().startsWith("Runtime approval is off"));
    assertThrows(IllegalStateException.class, () -> setup.apply(valid()));
    root.getPilot().setRuntimeApproval(true);
    assertEquals("Runtime approval needs durable history and TimesFM inference enabled", setup.applyUnavailable());
    verifyNoInteractions(pilot);
  }
  @Test void runtimeApprovalValidatesLikeTheExportThenApproves() throws Exception {
    root.getPilot().setRuntimeApproval(true);
    root.getHistory().setEnabled(true); root.getHistory().setClusterId("cluster"); root.getHistory().setCollectorId("collector");
    root.getInference().setEnabled(true);
    assertNull(setup.applyUnavailable());
    assertThrows(IllegalArgumentException.class, () -> setup.apply(request(true, "stale", "milliseconds", List.of("orders"), List.of("worker"), 60000, 30)));
    verify(pilot, never()).approve(any());
    var applied = setup.apply(valid());
    var approved = org.mockito.ArgumentCaptor.forClass(ForecastPilotProperties.Series.class);
    verify(pilot).approve(approved.capture());
    assertEquals(applied.seriesId(), approved.getValue().seriesId());
    assertEquals(setup.draft(valid()).seriesId(), applied.seriesId(), "Approving and exporting name the same series");
    root.getHistory().setEnrollEligible(false);
    assertThrows(IllegalArgumentException.class, () -> setup.apply(valid()), "A metric nobody records cannot be forecast");
  }

  @Test void proposesTheLongestCycleTheHistoryShows() {
    // Five-minute points over two and a half days with a daily sine: 288 per day.
    var daily = new ArrayList<Double>();
    for (int i = 0; i < 512; i++) daily.add(100 + 40 * Math.sin(2 * Math.PI * i / 288));
    var found = ForecastSetupService.detectCycle(daily, 300_000L);
    assertEquals("DAILY", found[0]); assertTrue(Double.parseDouble(found[1]) > 0.5);
    // The same values a minute apart repeat every 288 minutes, not every hour: no cycle claimed.
    assertEquals("NONE", ForecastSetupService.detectCycle(daily, 60_000L)[0]);
    // A short history covers no whole cycle at all, so nothing is said.
    assertNull(ForecastSetupService.detectCycle(daily.subList(0, 90), 30_000L));
  }

  @Test void claimsNoCycleForNoise() {
    var noise = new ArrayList<Double>(); var random = new java.util.Random(7);
    for (int i = 0; i < 512; i++) noise.add(random.nextDouble());
    assertEquals("NONE", ForecastSetupService.detectCycle(noise, 300_000L)[0]);
    // A flat series has no variance to correlate: zero, not NaN.
    assertEquals(0.0, ForecastSetupService.autocorrelation(java.util.Collections.nCopies(400, 5.0), 12));
  }

  /** METRICS-FORECAST-AUDIT-2.md B1: a drift is not a cycle. */
  @Test void aSeriesThatMerelyDriftsHasNoCycle() {
    var random = new java.util.Random(42); int claimed = 0;
    for (int run = 0; run < 200; run++) {
      var walk = new ArrayList<Double>(); double x = 100;
      for (int i = 0; i < 300; i++) { x += random.nextGaussian(); walk.add(x); }
      var found = ForecastSetupService.detectCycle(walk, 300_000L);
      if (found != null && !"NONE".equals(found[0])) claimed++;
    }
    // 94.5 % of 400 draws claimed an hourly cycle before the peak test.
    assertTrue(claimed <= 4, claimed + " of 200 random walks were given a cycle");
  }

  @Test void aTrendUnderAnHourlyCycleStillShowsTheCycle() {
    var random = new java.util.Random(3); var found = new ArrayList<String>();
    for (int run = 0; run < 50; run++) {
      var v = new ArrayList<Double>();
      for (int i = 0; i < 300; i++) v.add(0.3 * i + 30 * Math.sin(2 * Math.PI * i / 12) + 3 * random.nextGaussian());
      found.add(ForecastSetupService.detectCycle(v, 300_000L)[0]);
    }
    assertEquals(List.of("HOURLY"), found.stream().distinct().toList());
  }

  /** METRICS-FORECAST-AUDIT-2.md B2: the preview reads the series the approval would start. */
  @Test void theHistoryPreviewNormalisesTheMetricLikeTheApproval() throws Exception {
    // As stored by an older version: no type and no execution mode, which normalisation fills.
    metric = new MetricConfig("lag", "Consumer lag", null, "private SQL", "description", null, null, 4d, 1L, null,
        List.of(), Map.of(), null, "CONSUMER_TIME_LAG", Map.of("topic", "orders", "group", "worker"), null, null, List.of());
    root.getHistory().setClusterId("cluster"); root.getHistory().setCollectorId("collector");
    var canonical = MetricService.normalizeObservationDefinition(metric);
    String version = MetricObservation.collectedVersion(canonical, "kafka:29092", "collector");
    String id = MetricObservation.seriesId("cluster", version, "lag", "value", MetricService.pinnedLabels(canonical));
    assertNotEquals(version, MetricObservation.collectedVersion(metric, "kafka:29092", "collector"),
        "the premise: the stored form and the canonical one do not share an identity");
    var points = new ArrayList<PreparedMetricSeries.Point>();
    for (int i = 0; i < 5; i++) points.add(new PreparedMetricSeries.Point(60_000L * (i + 1), 10.0 + i, false, 1));
    when(preparation.prepare(eq(id), eq(version), eq("milliseconds"), anyLong(), any())).thenReturn(new PreparedMetricSeries(
        PreparedMetricSeries.Status.WARMING_UP, "collecting", id, version, "milliseconds", "milliseconds", 0, 1,
        new SeriesPreparationProfile(60_000L, 512, SeriesPreparationProfile.Transformation.GAUGE_MEAN), "p", "i", 5, 0, 0, points));
    var preview = setup.historyPreview("lag", 60_000L);
    assertEquals(5, preview.points().size());
    assertEquals("WARMING_UP", preview.status());
  }
}
