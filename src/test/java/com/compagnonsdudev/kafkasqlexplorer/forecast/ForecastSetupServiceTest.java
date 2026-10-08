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
    assertTrue(bound.getHistory().getMetricIds().contains("lag"));
    assertFalse(draft.configuration().contains("credential")); verifyNoInteractions(history, client, pilot, preparation);
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
}
