// SPDX-License-Identifier: AGPL-3.0-or-later
/** All demo presentation metadata lives here; the generator remains authoritative for values. */
export type Scenario = 'normal' | 'overload' | 'incident' | 'recover' | 'seasonal';
export type DemoScenarioEvent = {
  scenario: Scenario;
  at: number; // First Kafka-verified application (epoch millis), not the latest heartbeat.
  metricIds: string[];
};
export const DEMO_IDS = {
  orders: 'demo-live-unshipped',
  requests: 'demo-live-backlog',
  imports: 'demo-live-creep',
  events: 'demo-live-jitter',
  tickets: 'demo-live-shift',
} as const;
const ALL = Object.values(DEMO_IDS);
export const SCENARIOS: {
  id: Scenario; label: string; detail: string; impact: string; icon: string;
  disruptive: boolean; metricIds: string[]; preview: number[];
}[] = [
  { id: 'normal', label: 'Normal', detail: 'Natural live traffic',
    impact: 'Restore the baseline traffic curves.', icon: 'check_circle',
    disruptive: false, metricIds: ALL,
    preview: [22, 24, 23, 27, 26, 25, 29, 26, 28, 25, 27] },
  { id: 'overload', label: 'Overload', detail: 'Backlog increases',
    impact: 'Orders and requests will exceed their critical thresholds.', icon: 'trending_up',
    disruptive: true, metricIds: [DEMO_IDS.orders, DEMO_IDS.requests],
    preview: [18, 19, 22, 25, 28, 32, 40, 55, 68, 85, 96] },
  { id: 'incident', label: 'Incident', detail: 'Abrupt disruption',
    impact: 'Tickets and unarchived events will increase sharply.', icon: 'warning',
    disruptive: true, metricIds: [DEMO_IDS.tickets, DEMO_IDS.events],
    preview: [21, 22, 20, 21, 23, 22, 87, 90, 91, 78, 80] },
  { id: 'recover', label: 'Recovery', detail: 'Return to nominal',
    impact: 'Return all five traffic counts to a stable baseline.', icon: 'healing',
    disruptive: false, metricIds: ALL,
    preview: [94, 83, 67, 53, 37, 26, 22, 24, 22, 21, 22] },
  { id: 'seasonal', label: 'Seasonality', detail: 'Periodic traffic',
    impact: 'Requests will follow a faster, amplified cycle.', icon: 'waves',
    disruptive: true, metricIds: [DEMO_IDS.requests],
    preview: [20, 46, 81, 42, 19, 48, 86, 45, 18, 47, 83] },
];
export const scenarioFor = (id: Scenario) => SCENARIOS.find(s => s.id === id)!;
/** Illustrative, normalized previews; never present these as measured Kafka observations. */
export function previewPath(values: number[]) {
  return values.map((value, i) => `${i ? 'L' : 'M'} ${(i * 152 / (values.length - 1)).toFixed(1)} ${(38 - value * 0.34).toFixed(1)}`).join(' ');
}
/** Plot annotations only where a timestamp really overlaps measured (non-imputed) history. */
export function eventWithinObserved(event: DemoScenarioEvent | null | undefined,
  metricId: string, firstAt: number | undefined, lastAt: number | undefined) {
  return Boolean(event && event.metricIds.includes(metricId) && Number.isFinite(event.at)
    && firstAt !== undefined && lastAt !== undefined && event.at >= firstAt && event.at <= lastAt);
}
/** Exact timestamp joins, not an interpolation or a fabricated before/after delta. */
export function comparedAfterEvent(
  event: DemoScenarioEvent | null | undefined, metricId: string,
  observed: { at: number; actual?: number | null }[],
  previous: { at: number; q50: number }[],
) {
  if (!event || !event.metricIds.includes(metricId)) return null;
  const byAt = new Map(previous.map(p => [p.at, p.q50]));
  const pairs = observed.filter(p => p.at >= event.at && typeof p.actual === 'number'
    && Number.isFinite(p.actual) && byAt.has(p.at) && Number.isFinite(byAt.get(p.at)));
  if (!pairs.length) return null;
  return { count: pairs.length,
    meanDelta: pairs.reduce((sum, p) => sum + (p.actual as number) - byAt.get(p.at)!, 0) / pairs.length };
}
