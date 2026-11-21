// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * The forecast charts' colours, taken from the app's own palette (`tailwind.config.js`).
 *
 * The panel drew with Tailwind's stock green, amber and indigo, which belong to no token of this
 * design system: the observed line was a different green from every success badge beside it.
 * SVG presentation attributes accept CSS custom properties: the actual contrast now follows
 * the document theme without remounting Recharts. The semantic chart values are named once, and the
 * chart in the panel and the history preview in the assistant read the same ones.
 */
export const FORECAST_COLORS = {
  /** What was measured: the success token, as a healthy reading is everywhere else. */
  observed: 'var(--viz-observed)',
  /** A filled gap and a threshold: the warning token — neither is a measurement. */
  warning: 'var(--viz-warning)',
  /**
   * What the model says: its own colour, deliberately no token of the palette. It was the primary
   * token — which is also the stroke of every healthy metric card, so on a card the forecast and the
   * measurement it continues were the same lavender and only a dash told them apart. Cyan is none
   * of the status colours (success, warning, error, primary) and reads apart from each on the dark
   * surfaces, so a prediction is never mistaken for a measurement of any state.
   */
  forecast: 'var(--viz-forecast)',
  /**
   * A past forecast set beside what happened: the secondary token, so it reads as a forecast of
   * another time rather than as the current one or as a measurement.
   */
  previous: 'var(--viz-previous)',
  /** The 80 % interval's band and the chart's quiet furniture: on-surface-variant. */
  band: 'var(--viz-axis)',
} as const;

/** A metric's own thresholds, wherever they are drawn: the card's chart and the forecast panel's. */
export const THRESHOLD_COLORS = { warn: 'var(--viz-warning)', crit: 'var(--viz-critical)' } as const;
