// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import java.time.Duration;
import java.util.List;

/**
 * Explicit operator attestation of semantics and all resources; no source is inferred from a hash.
 */
public class ForecastPilotProperties {
  private boolean enabled;
  private Duration interval = Duration.ofMinutes(5);
  private Duration retention = Duration.ofDays(7);
  private int maxSeries = 20;
  private List<Series> series = List.of();

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
        || series.isEmpty()
        || series.size() > maxSeries
        || series.stream().map(Series::seriesId).distinct().count() != series.size())
      throw new IllegalArgumentException(
          "Pilot requires 1..max-series unique approved series and bounded interval/retention");
  }

  public Series resolve(String id) {
    return series.stream()
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

  public List<Series> getSeries() {
    return series;
  }

  public void setSeries(List<Series> v) {
    series = List.copyOf(v);
  }
}
