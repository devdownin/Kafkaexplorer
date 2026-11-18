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
  /** What the model says: the primary token. */
  forecast: '#a3adff',
  /** The 80 % interval's band and the chart's quiet furniture: on-surface-variant. */
  band: '#9aa3b2',
} as const;
