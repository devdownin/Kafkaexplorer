// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * True when the shadow comparison can answer: the pilot on, a series approved or approvable, and
 * the shadow client present, which needs inference on as well. A tool registered without one of
 * these would be listed, chosen and answer "disabled" every time.
 */
public final class ShadowComparisonCondition implements Condition {

  /** Why the comparison tool is withheld, in the setting to change; null when it is registered. */
  public static String withheldReason(Environment environment) {
    if (!environment.getProperty("explorer.forecasting.pilot.enabled", Boolean.class, true))
      return "forecast pilot disabled (explorer.forecasting.pilot.enabled is false)";
    if (!ApprovedForecastSeriesCondition.present(environment))
      return "no approved forecast series (explorer.forecasting.pilot.series is empty and runtime-approval is off)";
    if (!environment.getProperty("explorer.forecasting.inference.enabled", Boolean.class, false)
        || !environment.getProperty("explorer.forecasting.inference.shadow.enabled", Boolean.class, false))
      return "shadow comparison disabled (explorer.forecasting.inference.shadow.enabled and"
          + " explorer.forecasting.inference.enabled must both be true)";
    return null;
  }

  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    return withheldReason(context.getEnvironment()) == null;
  }
}
