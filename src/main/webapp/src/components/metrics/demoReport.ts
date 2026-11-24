// SPDX-License-Identifier: AGPL-3.0-or-later
import type { ForecastStatus, MetricConfig } from '../../api/types';

export interface DemoReport {
  schemaVersion: 1;
  generatedAt: string;
  scenario: string;
  note: string;
  summary: { metrics: number; forecastsReady: number; forecastsTotal: number };
  metrics: {
    id: string; name: string; value: number | null; observedAt: number | null;
    warningThreshold: number | null; criticalThreshold: number | null;
    history: number[]; error: string | null;
  }[];
  forecasts: {
    seriesId: string; metricId: string; state: string; strategy: string | null;
    modelId: string | null; generatedAt: number | null;
    predictedPoints: { at: number; central: number; q10: number; q90: number }[];
    evaluatedPoints: number | null; quality: unknown;
  }[];
}

/** An operator-facing snapshot. No tokens, configuration paths or full DB history are exported. */
export function demoSnapshot(scenario: string, metrics: MetricConfig[], forecast: ForecastStatus | null,
                             now = new Date()): DemoReport {
  const selected = metrics.filter(m => m.id.startsWith('demo-live-'));
  const series = (forecast?.series ?? []).filter(s => s.metricId.startsWith('demo-live-'));
  return {
    schemaVersion: 1, generatedAt: now.toISOString(), scenario,
    note: 'Local demo snapshot; health indicators are observational, not Docker container statuses.',
    summary: {
      metrics: selected.length,
      forecastsReady: series.filter(s => s.result?.state === 'READY' && s.result?.strategy === 'TIMESFM').length,
      forecastsTotal: series.length,
    },
    metrics: selected.map(m => ({
      id: m.id, name: m.name, value: m.lastValue, observedAt: m.lastUpdateTime,
      warningThreshold: m.warningThreshold, criticalThreshold: m.criticalThreshold,
      history: (m.history ?? []).slice(-60), error: m.errorMessage,
    })),
    forecasts: series.map(s => ({
      seriesId: s.seriesId, metricId: s.metricId,
      state: s.result?.state ?? 'NOT_AVAILABLE',
      strategy: s.result?.strategy ?? null,
      modelId: s.result?.forecast?.modelId ?? null,
      generatedAt: s.result?.generatedAt ?? null,
      predictedPoints: (s.result?.forecast?.points ?? []).map(p => ({
        at: p.at, central: p.central, q10: p.q10, q90: p.q90,
      })),
      evaluatedPoints: s.result?.evaluatedPoints ?? null,
      quality: s.result?.quality ?? null,
    })),
  };
}

const escapeHtml = (value: string) => value.replace(/[&<>"']/g, character =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character] ?? character);

export function reportHtml(snapshot: DemoReport): string {
  const rows = snapshot.metrics.map(m => `<tr><td>${escapeHtml(m.name)}</td><td>${m.value ?? '—'}</td><td>${m.warningThreshold ?? '—'}</td><td>${m.criticalThreshold ?? '—'}</td></tr>`).join('');
  const forecastRows = snapshot.forecasts.map(f =>
    `<tr><td>${escapeHtml(f.metricId)}</td><td>${escapeHtml(f.state)}</td><td>${escapeHtml(f.strategy ?? '—')}</td><td>${f.predictedPoints.length}</td></tr>`).join('');
  return `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>KafkaExplorer Forecast Demo report</title>
<style>body{font:15px system-ui,sans-serif;max-width:1000px;margin:40px auto;padding:0 20px;color:#263142;background:#f5f7fb}
h1{font-size:30px}h2{margin-top:32px}p{color:#53617a}strong{color:#1c3155}
table{border-collapse:collapse;width:100%;background:white;border-radius:12px;overflow:hidden}
th,td{text-align:left;padding:12px;border-bottom:1px solid #e2e8f0}th{background:#e8edf6}
.summary{display:flex;gap:16px;flex-wrap:wrap}.summary div{background:white;padding:18px;border-radius:12px;flex:1}
small{color:#68748a}</style>
<h1>KafkaExplorer · Forecast Demo</h1><p>Snapshot generated ${escapeHtml(snapshot.generatedAt)} · Scenario: <strong>${escapeHtml(snapshot.scenario)}</strong></p>
<div class="summary"><div><strong>${snapshot.summary.metrics}</strong><p>Live metrics</p></div>
<div><strong>${snapshot.summary.forecastsReady}</strong><p>Ready TimesFM forecasts</p></div>
<div><strong>${snapshot.summary.forecastsTotal}</strong><p>Forecast series</p></div></div>
<h2>Observed metrics</h2><table><thead><tr><th>Metric</th><th>Last value</th><th>Warning</th><th>Critical</th></tr></thead><tbody>${rows}</tbody></table>
<h2>Forecast status</h2><table><thead><tr><th>Metric</th><th>State</th><th>Strategy</th><th>Predicted points</th></tr></thead><tbody>${forecastRows}</tbody></table>
<p><small>${escapeHtml(snapshot.note)}. Download JSON for observations, predicted points and quality statistics.</small></p></html>`;
}

export function downloadDemoReport(report: DemoReport, format: 'json' | 'html'): void {
  const content = format === 'json' ? JSON.stringify(report, null, 2) : reportHtml(report);
  const blob = new Blob([content], { type: format === 'json' ? 'application/json' : 'text/html' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `kafkaexplorer-demo-${report.scenario}-${report.generatedAt.slice(0, 10)}.${format}`;
  document.body.append(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 0);
}
