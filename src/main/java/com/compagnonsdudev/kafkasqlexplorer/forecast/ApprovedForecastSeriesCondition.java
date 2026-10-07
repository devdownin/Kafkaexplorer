// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * True when the operator approved at least one forecast series.
 *
 * <p>The pilot is on by default with an empty catalogue, and its five MCP tools used to be
 * registered all the same: listed by {@code tools/list}, described to the model, chosen, and
 * answered with an empty list or {@code OUT_OF_SCOPE}. Bound through {@link Binder}, so a list
 * given in YAML and one given as {@code EXPLORER_FORECASTING_PILOT_SERIES_0_SERIESID} both count.
 */
public final class ApprovedForecastSeriesCondition implements Condition {
  private static final String FIRST_SERIES = "explorer.forecasting.pilot.series[0].series-id";

  public static boolean present(Environment environment) {
    return Binder.get(environment).bind(FIRST_SERIES, String.class).isBound();
  }

  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    return present(context.getEnvironment());
  }
}
