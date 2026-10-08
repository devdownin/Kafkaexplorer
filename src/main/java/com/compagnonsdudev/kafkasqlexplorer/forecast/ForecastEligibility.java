// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import com.compagnonsdudev.kafkasqlexplorer.domain.MetricConfig;
import com.compagnonsdudev.kafkasqlexplorer.service.MetricService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a metric's definition must say before it can be forecast — one rule, read by the collector
 * that enrolls eligible metrics in history and by the assistant that lists candidates.
 *
 * <p>A transient collection error is deliberately absent: it is a reason not to approve a series
 * today, not a reason to stop recording the history that approval will need.
 */
public final class ForecastEligibility {
  private ForecastEligibility() {}

  public static List<String> blockers(MetricConfig metric) {
    MetricConfig m;
    try {
      m = MetricService.normalizeObservationDefinition(metric);
    } catch (IllegalArgumentException e) {
      return List.of("Invalid metric definition; edit and save the metric before enrollment");
    }
    var blockers = new ArrayList<String>();
    String unit = MetricObservation.unit(m);
    if (unit.isBlank() || unit.equals("UNKNOWN"))
      blockers.add("Set a known unit: this template does not fix one, so add a \"unit\" template parameter to the metric");
    String type = m.type() == null ? "GAUGE" : m.type().toUpperCase(Locale.ROOT);
    if (!Set.of("GAUGE", "COUNTER").contains(type)) blockers.add("A scalar GAUGE or COUNTER is required");
    if (m.templateType() == null || m.templateType().equals("RAW_SQL"))
      blockers.add("Raw SQL has unverified source scope; use a supported template");
    if (m.labelFields() != null && !m.labelFields().isEmpty() || m.labelTopic() != null && !m.labelTopic().isBlank())
      blockers.add("Labelled metrics require a separately approved series; the assistant supports unlabelled values");
    if ("FLINK_MANAGED_JOB".equals(m.executionMode())) blockers.add("Managed jobs do not capture scalar observations");
    return List.copyOf(blockers);
  }
}
