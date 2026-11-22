---
name: kafka-forecast-analyst
description: Explain KafkaExplorer metric forecasts, predicted threshold breaches, model quality and uncertainty without generating new forecasts.
---

# Kafka forecast analyst

Use for metric trend interpretation, capacity risks, projected threshold events or deciding whether a forecast is operationally usable.

## Procedure

1. Discover exposed forecast tools. An enabled pilot does not imply that any series is approved. Start with `kex_list_forecastable_metrics`; if hidden or empty, explain why forecasting cannot be assessed.
2. Locate the exact `metricId` and approved `seriesId`, including its environment, topic/group scope and sampling profile. Do not infer identity by matching labels alone.
3. Obtain `kex_metric_history` and the existing `kex_forecast_metric` output. This tool reads stored results: it must not be used to trigger fresh inference. Identify model revision, input timestamp, granularity, horizon and stale/unavailable status.
4. Obtain `kex_get_forecast_quality`, `kex_list_predicted_threshold_breaches` and `kex_list_forecast_threshold_reaches` if available. Distinguish nominal quantile bands from empirically calibrated coverage and from observed error.
5. State observed values and forecast values separately. Describe predicted threshold crossing with its direction, level and interval; never say a future breach has already occurred.
6. Conclude **actionable**, **watch**, or **insufficient evidence**, with explicit justification and next observation needed. Propose Prometheus alert configuration for human review, not autonomous production changes.

## Output contract

Metric and series identity; observed window and coverage; prediction horizon and freshness; forecast interval; threshold outcomes; quality evidence; limitations and a recommended next operator action.

## Safety / failure modes

- Do not auto-approve a series, switch SHADOW to active, create an alert, change a threshold or start inference.
- Treat stale results, short histories, imputation, missing data, counter resets and model drift as blocking limitations when relevant.
- Never equate a confidence interval with a guaranteed event or score a model from its own projections.

## Acceptance

Every numerical conclusion identifies whether it is observed, projected or unmeasured. Forecast quality is not claimed when no realized quality has been measured.
