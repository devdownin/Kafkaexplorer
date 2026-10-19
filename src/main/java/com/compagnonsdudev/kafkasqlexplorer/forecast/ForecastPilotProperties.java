// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import java.time.Duration;
import java.util.List;

/**
 * Explicit operator attestation of semantics and all resources; no source is inferred from a hash.
 */
public class ForecastPilotProperties {
  private boolean enabled = true;
  private Duration interval = Duration.ofMinutes(5);
  private Duration retention = Duration.ofDays(7);
  private int maxSeries = 20;
  private List<Series> series = List.of();
  /**
   * Lets the assistant approve and withdraw series at runtime, kept in PostgreSQL beside the
   * results. Off by default: the export-and-restart path leaves every approval in the deployment's
   * own reviewed configuration, which is the posture for a shared cluster; a local stack turns
   * this on so a forecast starts without editing a file.
   */
  private boolean runtimeApproval;
  private volatile List<Series> runtime = List.of();
  /** Past covariates the shadow is given beside its univariate run; never sent to TimesFM. */
  private List<ShadowCovariate> shadowCovariates = List.of();
  public static final int MAX_COVARIATES = 4;

  /**
   * Another enrolled history series read on the target's grid, such as a topic's produce rate
   * beside a consumer group's lag on that topic. Its topics must be among the target's: the
   * comparison is reported under the target's scope, so the covariate cannot widen it.
   */
  public record ShadowCovariate(
      String targetSeriesId,
      String seriesId,
      String metricId,
      String definitionVersion,
      String unit,
      SeriesPreparationProfile.Transformation transformation,
      List<String> topics) {
    public ShadowCovariate {
      topics = topics == null ? List.of() : List.copyOf(topics);
      if (targetSeriesId == null
          || !targetSeriesId.matches("[a-f0-9]{64}")
          || seriesId == null
          || !seriesId.matches("[a-f0-9]{64}")
          || seriesId.equals(targetSeriesId)
          || metricId == null
          || metricId.isBlank()
          || definitionVersion == null
          || definitionVersion.isBlank()
          || unit == null
          || unit.isBlank()
          || unit.equals("UNKNOWN")
          || transformation == null
          || topics.isEmpty()
          || topics.stream().anyMatch(t -> t == null || t.isBlank()))
        throw new IllegalArgumentException("Invalid shadow covariate");
    }

    /** Read at the target's cadence and context length, with the covariate's own transformation. */
    public SeriesPreparationProfile profile(Series target) {
      return new SeriesPreparationProfile(
          target.profile().stepMillis(), target.profile().contextPoints(), transformation);
    }
  }

  public record Series(
      String seriesId,
      String metricId,
      String environment,
      String definitionVersion,
      String unit,
      List<String> topics,
      List<String> groups,
      SeriesPreparationProfile profile,
      int horizon,
      int seasonLength,
      ForecastThresholdPolicy threshold,
      Double maxMae,
      double minimumCoverage,
      int minimumEvaluatedPoints) {
    public Series {
      topics = topics == null ? List.of() : List.copyOf(topics);
      groups = groups == null ? List.of() : List.copyOf(groups);
      if (seriesId == null
          || !seriesId.matches("[a-f0-9]{64}")
          || metricId == null
          || metricId.isBlank()
          || environment == null
          || environment.isBlank()
          || definitionVersion == null
          || definitionVersion.isBlank()
          || unit == null
          || unit.isBlank()
          || unit.equals("UNKNOWN")
          || topics.isEmpty()
          || topics.stream().anyMatch(t -> t == null || t.isBlank())
          || groups.stream().anyMatch(g -> g == null || g.isBlank())
          || profile == null
          || profile.contextPoints() != 512
          || horizon < 1
          || horizon > 60
          || seasonLength < 1
          || seasonLength > 512
          || minimumEvaluatedPoints < 1
          || minimumCoverage < 0
          || minimumCoverage > 1
          || !Double.isFinite(minimumCoverage)
          || maxMae != null && (!Double.isFinite(maxMae) || maxMae < 0))
        throw new IllegalArgumentException("Invalid explicitly approved forecast series");
      if (threshold != null
          && (!seriesId.equals(threshold.seriesId())
              || !definitionVersion.equals(threshold.definitionVersion())
              || threshold.horizonPoints() > horizon
              || Double.compare(threshold.confidence(), .9) != 0))
        throw new IllegalArgumentException("Threshold provenance does not match series");
    }
  }

  public void validate() {
    if (!enabled) return;
    if (getInterval() == null
        || getInterval().compareTo(Duration.ofMinutes(1)) < 0
        || getInterval().compareTo(Duration.ofDays(1)) > 0
        || retention == null
        || retention.compareTo(Duration.ofDays(1)) < 0
        || retention.compareTo(Duration.ofDays(90)) > 0
        || maxSeries < 1
        || maxSeries > 100
        || approved().size() > maxSeries
        || series.stream().map(Series::seriesId).distinct().count() != series.size())
      throw new IllegalArgumentException(
          "Pilot requires 0..max-series unique approved series and bounded interval/retention");
    var perTarget =
        shadowCovariates.stream()
            .collect(java.util.stream.Collectors.groupingBy(ShadowCovariate::targetSeriesId));
    for (var covariates : perTarget.values()) {
      if (covariates.size() > MAX_COVARIATES
          || covariates.stream().map(ShadowCovariate::seriesId).distinct().count() != covariates.size())
        throw new IllegalArgumentException(
            "A shadow target takes at most " + MAX_COVARIATES + " distinct covariates");
    }
    for (var c : shadowCovariates) {
      var target = series.stream().filter(s -> s.seriesId().equals(c.targetSeriesId())).findFirst();
      // A runtime-approved target is checked when the shadow runs; a configured one fails here.
      if (target.isPresent() && !target.get().topics().containsAll(c.topics()))
        throw new IllegalArgumentException(
            "A shadow covariate's topics must be among its target's declared topics");
    }
  }

  /** In declaration order, which is the row order of the covariate tensor. */
  public List<ShadowCovariate> covariatesFor(String targetSeriesId) {
    return shadowCovariates.stream().filter(c -> c.targetSeriesId().equals(targetSeriesId)).toList();
  }

  public List<ShadowCovariate> getShadowCovariates() {
    return shadowCovariates;
  }

  public void setShadowCovariates(List<ShadowCovariate> v) {
    shadowCovariates = v == null ? List.of() : List.copyOf(v);
  }

  /** The configured series, then the runtime ones; a configured series wins over its runtime copy. */
  public List<Series> approved() {
    var configured = series.stream().map(Series::seriesId).collect(java.util.stream.Collectors.toSet());
    return java.util.stream.Stream.concat(
            series.stream(), runtime.stream().filter(s -> !configured.contains(s.seriesId())))
        .toList();
  }

  /** Approved at runtime, so withdrawable at runtime; a configured series is not. */
  public boolean isRuntime(String id) {
    return series.stream().noneMatch(s -> s.seriesId().equals(id))
        && runtime.stream().anyMatch(s -> s.seriesId().equals(id));
  }

  public List<Series> runtime() {
    return runtime;
  }

  public void replaceRuntime(List<Series> v) {
    runtime = List.copyOf(v);
  }

  public Series resolve(String id) {
    return approved().stream()
        .filter(s -> s.seriesId().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Series is not configured for this pilot"));
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean v) {
    enabled = v;
  }

  public Duration getInterval() {
    return interval;
  }

  public void setInterval(Duration v) {
    interval = v;
  }

  public Duration getRetention() {
    return retention;
  }

  public void setRetention(Duration v) {
    retention = v;
  }

  public int getMaxSeries() {
    return maxSeries;
  }

  public void setMaxSeries(int v) {
    maxSeries = v;
  }

  public boolean isRuntimeApproval() {
    return runtimeApproval;
  }

  public void setRuntimeApproval(boolean v) {
    runtimeApproval = v;
  }

  public List<Series> getSeries() {
    return series;
  }

  public void setSeries(List<Series> v) {
    series = List.copyOf(v);
  }
}
