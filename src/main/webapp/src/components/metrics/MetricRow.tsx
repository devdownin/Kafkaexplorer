// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
import React from 'react';
import type { ForecastSeriesView, MetricConfig } from '../../api/types';
import { Badge } from '../ui';
import { STATUS_STYLES, getStatus } from '../../pages/metricsEditor';
import { about, type ForecastStageSummary } from './forecastStage';
import { forecastReachFor, predictedPoints } from './forecastBreach';
import { FORECAST_COLORS } from './forecastColors';

/**
 * One metric on one line — the compact view, for a deployment with dozens of metrics.
 *
 * A card draws two Recharts graphs; a line draws its history as a plain SVG polyline, which is the
 * whole point: forty rows of this cost less than four cards. What it keeps is what the list is read
 * for — the verdict, the value, where it is going, and the forecast's predicted threshold, read by
 * the same `forecastReachFor` the card's badge reads. The card is one click away (Edit, Details).
 */
const Sparkline: React.FC<{ values: number[]; forecast?: number[] }> = ({ values, forecast = [] }) => {
  const shown = values.filter(Number.isFinite).slice(-30);
  if (shown.length < 2) return <span className="w-24 shrink-0" aria-hidden="true" />;
  // At most half as many predicted points as measured ones: thirty predicted steps beside nine
  // measured ones squeezed the history into the first quarter of the line — the defect the card
  // fixed. The line shows where the forecast heads; its whole horizon is on the card and the panel.
  const ahead = forecast.filter(Number.isFinite).slice(0, Math.max(1, Math.ceil(shown.length / 2)));
  // A wider line when there is a forecast to carry, so the history keeps its width beside it.
  const w = ahead.length ? 128 : 96, h = 24;
  const all = [...shown, ...ahead];
  const min = Math.min(...all), max = Math.max(...all), span = max - min || 1;
  // One step for both, so a minute of forecast is as wide as a minute of history.
  const stepX = w / (shown.length - 1 + ahead.length);
  const y = (v: number) => h - 2 - ((v - min) / span) * (h - 4);
  const history = shown.map((v, i) => `${i * stepX},${y(v)}`).join(' ');
  // The forecast continues the line from its last measured point, dashed, as on the card.
  const last = shown.length - 1;
  const future = [`${last * stepX},${y(shown[last])}`, ...ahead.map((v, i) => `${(last + 1 + i) * stepX},${y(v)}`)].join(' ');
  return <svg width={w} height={h} className="shrink-0" aria-hidden="true">
    <polyline fill="none" stroke="currentColor" strokeWidth={1.5} points={history} />
    {ahead.length > 0 && <polyline data-testid="sparkline-forecast" fill="none" stroke={FORECAST_COLORS.forecast}
      strokeWidth={1.5} strokeDasharray="3 2" points={future} />}
  </svg>;
};

/**
 * The predicted medians a line may draw after the history: none for a counter, whose forecast is a
 * rate per second while its history is the counter's own reading — the card gives that one its own
 * scale, which a sparkline cannot.
 */
function forecastMedians(series: ForecastSeriesView | undefined): number[] {
  const result = series?.result;
  if (!result?.forecast || result.context?.profile?.transformation === 'COUNTER_RATE') return [];
  return predictedPoints(result.forecast).map(p => p.q50);
}

const MetricRowView: React.FC<{
  metric: MetricConfig;
  forecast?: ForecastStageSummary;
  forecastSeries?: ForecastSeriesView;
  forecastNow?: number;
  refreshing: boolean;
  onEdit: (id: string) => void;
  onRefresh: (id: string) => void;
  onShowForecast?: (id: string) => void;
}> = ({ metric, forecast, forecastSeries, forecastNow, refreshing, onEdit, onRefresh, onShowForecast }) => {
  const status = getStatus(metric);
  const st = STATUS_STYLES[status];
  const reach = forecastReachFor(metric, forecastSeries, forecastNow);
  const name = metric.name || metric.id;
  return (
    <li className="flex items-center gap-3 px-3 py-2 border-b border-outline-variant/40 text-sm min-w-0"
      aria-label={`${name}: ${st.label}`}>
      <span className={`w-2 h-2 rounded-full shrink-0 ${st.dot}`} aria-hidden="true" />
      <span className="font-mono truncate min-w-0 flex-1" title={name}>{name}</span>
      <span className={`font-semibold tabular-nums shrink-0 ${st.text}`}>
        {metric.lastValue === null ? '—' : metric.lastValue.toLocaleString(undefined, { maximumFractionDigits: 2 })}
      </span>
      <span className="hidden sm:inline-flex text-primary"><Sparkline values={metric.history ?? []} forecast={forecastMedians(forecastSeries)} /></span>
      <span className="hidden md:flex items-center gap-1.5 shrink-0 w-56 min-w-0">
        {reach && forecastSeries
          ? <span className={`truncate text-xs font-medium ${reach.level === 'critical' ? 'text-error' : 'text-warning'}`}
              aria-label="Forecast threshold">
              {reach.level === 'critical' ? 'Critical' : 'Warning'} {reach.likelihood} in {about(reach.steps, forecastSeries.stepMillis)}
            </span>
          : forecast ? <Badge tone={forecast.tone}>{forecast.label}</Badge> : null}
      </span>
      <span className="flex items-center shrink-0">
        <button type="button" onClick={() => onRefresh(metric.id)} disabled={refreshing} title="Refresh now"
          aria-label={`Refresh ${name} now`}
          className="inline-flex items-center justify-center w-7 h-7 rounded text-outline hover:text-primary disabled:opacity-40">
          <span className={`material-symbols-outlined text-[16px] ${refreshing ? 'animate-spin' : ''}`} aria-hidden="true">refresh</span>
        </button>
        <button type="button" onClick={() => onEdit(metric.id)} title="Edit" aria-label={`Edit ${name}`}
          className="inline-flex items-center justify-center w-7 h-7 rounded text-outline hover:text-primary">
          <span className="material-symbols-outlined text-[16px]" aria-hidden="true">edit</span>
        </button>
        {onShowForecast && <button type="button" onClick={() => onShowForecast(metric.id)} title="Show the forecast"
          aria-label={`Show the forecast of ${name}`}
          className="inline-flex items-center justify-center w-7 h-7 rounded hover:text-primary" style={{ color: FORECAST_COLORS.forecast }}>
          <span className="material-symbols-outlined text-[16px]" aria-hidden="true">insights</span>
        </button>}
      </span>
    </li>
  );
};

/** Memoised on the same terms as the card: it renders again only when one of its own props moved. */
export const MetricRow = React.memo(MetricRowView);
