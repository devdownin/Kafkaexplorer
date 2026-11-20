// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * Where a metric's thresholds go on a chart whose value axis is fitted to the data.
 *
 * A fitted axis made every distant threshold disappear without a trace, and a threshold forced
 * onto the scale flattened the curve to a line when it lay far away. The rule that serves both:
 * a threshold within one span of the plotted range widens the scale and is drawn; one further off
 * is named beside the chart, at the edge it lies beyond ("↑ crit 7,600 off scale"). It was written
 * on the metric card and the forecast panel needed the same — so it lives here, once.
 */
export interface ThresholdMark { label: string; value: number | null; color: string }
export interface PlacedMark { label: string; value: number; color: string }
export interface OffScaleMark extends PlacedMark { up: boolean }
export interface ThresholdPlacement {
  /** The plotted range, widened to take in every threshold that was drawn. */
  min: number;
  max: number;
  lines: PlacedMark[];
  offScale: OffScaleMark[];
}

export function placeThresholds(min: number, max: number, marks: ThresholdMark[]): ThresholdPlacement {
  let low = min, high = max;
  const lines: PlacedMark[] = [];
  const offScale: OffScaleMark[] = [];
  // One span of the data itself; never less than one unit, so a flat series still has a scale.
  const span = Math.max(max - min, Math.abs(max) * 0.01, 1);
  for (const mark of marks) {
    const value = mark.value;
    if (value === null || !Number.isFinite(value)) continue;
    const distance = value > high ? value - high : value < low ? low - value : 0;
    if (distance <= span) {
      low = Math.min(low, value);
      high = Math.max(high, value);
      lines.push({ label: mark.label, value, color: mark.color });
    } else {
      offScale.push({ label: mark.label, value, color: mark.color, up: value > high });
    }
  }
  return { min: low, max: high, lines, offScale };
}
