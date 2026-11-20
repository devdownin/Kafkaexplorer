// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * The forecast charts' colours, taken from the app's own palette (`tailwind.config.js`).
 *
 * The panel drew with Tailwind's stock green, amber and indigo, which belong to no token of this
 * design system: the observed line was a different green from every success badge beside it.
 * SVG attributes cannot take a Tailwind class, so the hex values are named here once, and the
 * chart in the panel and the history preview in the assistant read the same ones.
 */
export const FORECAST_COLORS = {
  /** What was measured: the success token, as a healthy reading is everywhere else. */
  observed: '#6ee7a0',
  /** A filled gap and a threshold: the warning token — neither is a measurement. */
  warning: '#f5c264',
  /**
   * What the model says: its own colour, deliberately no token of the palette. It was the primary
   * token — which is also the stroke of every healthy metric card, so on a card the forecast and the
   * measurement it continues were the same lavender and only a dash told them apart. Cyan is none
   * of the status colours (success, warning, error, primary) and reads apart from each on the dark
   * surfaces, so a prediction is never mistaken for a measurement of any state.
   */
  forecast: '#56c8e8',
  /** The 80 % interval's band and the chart's quiet furniture: on-surface-variant. */
  band: '#9aa3b2',
} as const;
