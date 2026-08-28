// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/*
 * Une carte de métrique : son verdict, ce qu'elle a mesuré, la règle qui décide, et les deux
 * séries qu'elle trace.
 *
 * Sortie de `Metrics.tsx`, qui était la plus grosse page du dépôt, sur le précédent de
 * `components/query/` — et dans le même ordre : le test de la page existait avant le découpage.
 * Rien n'est réécrit ici, c'est le composant tel qu'il était, avec ses imports rendus explicites.
 *
 * Ce qu'il ne fait pas est aussi important que ce qu'il fait : il ne calcule aucun verdict. La
 * graduation vit dans `metricAlert.ts` et la lecture d'une mesure dans `metricScope.ts`, parce
 * qu'une règle écrite des deux côtés est une règle qui diverge — ce que `keyBase` a déjà coûté au
 * modèle de données.
 */

import React from 'react';
import { AreaChart, Area, Line, LineChart, ResponsiveContainer, ReferenceLine, Tooltip, YAxis } from 'recharts';
// Recharts exporte lui aussi un `Tooltip` : le nôtre est aliasé, comme dans la page.
import { Tooltip as InfoTooltip } from '../ui';
import { useToast } from '../Toast';
import { copyText } from '../../clipboard';
import { describeQueryError } from '../../pages/queryError';
import type { MetricConfig } from '../../api/types';
import { buildAlertRule, thresholdDirection } from '../../pages/metricAlert';
import { componentSeries, describeMeasurement, describeMetricScope, scopeNoteOf } from '../../pages/metricScope';
import {
  PLACEHOLDER_BARS, STATUS_STYLES, TYPE_META, describeTemplate, getStatus, relativeTime,
} from '../../pages/metricsEditor';

export const MetricCard: React.FC<{
  metric: MetricConfig;
  onEdit: () => void;
  onDelete: () => void;
  onRefresh: () => void;
  refreshing: boolean;
}> = ({ metric, onEdit, onDelete, onRefresh, refreshing }) => {
  const { toast } = useToast();
  const status = getStatus(metric);
  const st = STATUS_STYLES[status];
  const tm = TYPE_META[metric.type] ?? TYPE_META.GAUGE;
  const scopeChips = describeMetricScope(metric.lastSummary, metric.templateParams);
  const measurement = describeMeasurement(metric.lastSummary);
  const series = componentSeries(metric.componentHistory, metric.history?.length ?? 0);
  /*
   * Un graphe à part, avec son échelle à lui — délibérément, et pas deux axes sur le premier.
   *
   * Sur un écart, la valeur vaut 5 pendant que les deux côtés valent douze mille : une échelle
   * commune écrase la valeur sur la ligne du bas, et un double axe fait croire à un croisement qui
   * n'existe pas. Deux boîtes, deux échelles, chacune lisible pour ce qu'elle montre.
   */
  const seriesData = series.length > 0
    ? (metric.history ?? []).map((_, i) => {
        const point: Record<string, number | null> = { i };
        series.forEach(s => { point[s.key] = s.values[i] ?? null; });
        return point;
      })
    : [];
  // Le sens du seuil est déduit de l'ordre des deux, donc le signe affiché doit suivre — sans quoi
  // la carte annoncerait « ≥ 0.95 » sur une métrique qui se déclenche en descendant.
  const thresholdSign =
    thresholdDirection(metric.warningThreshold, metric.criticalThreshold) === 'below' ? '≤' : '≥';
  const alert = buildAlertRule(metric);

  const chartData = (metric.history?.length === 1
    ? [metric.history[0], metric.history[0]]
    : metric.history ?? []
  ).map((v, i) => ({ i, v }));

  const strokeColor = status === 'critical' ? '#f58c8c' : status === 'warning' ? '#f5c264' : '#a3adff';

  return (
    <div className="flex flex-col rounded-xl bg-surface-container ring-1 ring-white/[0.045] overflow-hidden card-hover">
      {/* Header */}
      <div className="flex items-center justify-between px-4 pt-4 pb-2 gap-2">
        <div className="flex items-center gap-2 min-w-0">
          <span className={`w-2 h-2 rounded-full shrink-0 ${st.dot} ${status === 'ok' ? 'animate-pulse' : ''}`} />
          {/* `truncate` sans `title` coupe une valeur que plus rien ne rend : la sonde de mise en
              page (W7 de MOBILE-LAYOUT-SCOPE.md) a trouvé ici un nom de métrique tronqué à 147 px
              sur 280, inatteignable. La convention du dépôt est que ce qui est compacté garde sa
              valeur exacte dans un `title`. */}
          <h3 title={metric.name} className="font-semibold text-on-surface truncate font-mono text-[13px]">{metric.name}</h3>
          <span className={`px-1.5 py-0.5 rounded text-[9px] font-bold uppercase shrink-0 ${tm.badge}`}>
            {metric.type}
          </span>
          {metric.createTableSql && (
            <InfoTooltip content="This metric carries a CREATE TABLE statement, run before its own SQL.">
              <span tabIndex={0}
                className="px-1.5 py-0.5 rounded text-[9px] font-bold uppercase shrink-0 bg-surface-container-high text-on-surface-variant">
                DDL
              </span>
            </InfoTooltip>
          )}
          {(metric.labelFields?.length ?? 0) > 0 && (
            <InfoTooltip content="Prometheus labels taken from the latest message, so the metric can be split by one of its fields.">
              <span tabIndex={0}
                className="px-1.5 py-0.5 rounded text-[9px] font-bold uppercase shrink-0 bg-primary/10 text-primary">
                {metric.labelFields!.length} labels
              </span>
            </InfoTooltip>
          )}
        </div>
        <div className="flex items-center gap-1 shrink-0">
          <button onClick={onRefresh} disabled={refreshing} title="Refresh now" aria-label="Refresh this metric now"
            className="p-1 text-on-surface-variant hover:text-primary transition-colors disabled:opacity-40">
            <span className={`material-symbols-outlined text-base ${refreshing ? 'animate-spin' : ''}`}>refresh</span>
          </button>
          <button onClick={onEdit} title="Edit" aria-label="Edit this metric"
            className="p-1 text-on-surface-variant hover:text-primary transition-colors">
            <span className="material-symbols-outlined text-base">edit</span>
          </button>
          <button onClick={onDelete} title="Delete" aria-label="Delete this metric"
            className="p-1 text-on-surface-variant hover:text-error transition-colors">
            <span className="material-symbols-outlined text-base">delete</span>
          </button>
        </div>
      </div>

      {/* Value */}
      <div className="px-4 pb-2">
        <div className={`text-3xl font-bold tabular-nums ${st.text}`}>
          {metric.lastValue !== null
            ? metric.lastValue.toLocaleString(undefined, { maximumFractionDigits: 2 })
            : '—'}
        </div>
        {/* Les composantes du nombre ci-dessus. Sur un écart, « 5 » ne dit rien et « 12 contre 7 »
            est le diagnostic — et les deux étaient dans `lastSummary`, montrés à personne. */}
        {measurement.length > 0 && (
          <div className="flex items-center gap-3 mt-1 flex-wrap font-mono text-[11px]">
            {measurement.map(part => (
              <InfoTooltip key={part.label} content={part.detail}>
                <span tabIndex={0} className="flex items-baseline gap-1 rounded">
                  <span className="text-outline">{part.label}</span>
                  <span className="text-on-surface-variant font-semibold">{part.value}</span>
                </span>
              </InfoTooltip>
            ))}
          </div>
        )}
        <div className="flex items-center gap-3 mt-0.5 flex-wrap">
          {metric.description && <p title={metric.description} className="text-xs text-on-surface-variant truncate">{metric.description}</p>}
          {metric.warningThreshold !== null && (
            <span className="flex items-center gap-0.5 text-[10px] text-warning font-mono shrink-0">
              <span className="material-symbols-outlined text-[11px]">warning</span>{thresholdSign} {metric.warningThreshold.toLocaleString()}
            </span>
          )}
          {metric.criticalThreshold !== null && (
            <span className="flex items-center gap-0.5 text-[10px] text-error font-mono shrink-0">
              <span className="material-symbols-outlined text-[11px]">emergency</span>{thresholdSign} {metric.criticalThreshold.toLocaleString()}
            </span>
          )}
        </div>
        {metric.errorMessage && (
          // Titre lisible (voir queryError.ts) ; le message brut du serveur, qui dit *quelle*
          // colonne ou quelle table pose problème, se lit dans l'infobulle — au survol comme au
          // focus, puisque c'est souvent la seule information exploitable.
          <InfoTooltip content={metric.errorMessage}>
            <p tabIndex={0} className="text-[10px] text-error mt-1 line-clamp-2 rounded">
              <span className="material-symbols-outlined text-[11px] align-middle">error</span>{' '}{describeQueryError(metric.errorMessage).title}
            </p>
          </InfoTooltip>
        )}
      </div>

      {/* Sparkline */}
      <div className="px-3 pb-2">
        {chartData.length > 0 ? (() => {
          const vals = chartData.map(d => d.v);
          const minV = Math.min(...vals);
          const maxV = Math.max(...vals);
          const pad = (maxV - minV) * 0.15 || 1;
          return (
            <div className="rounded-lg overflow-hidden border border-outline-variant/60 bg-background-dark/60">
              <ResponsiveContainer width="100%" height={80}>
                <AreaChart data={chartData} margin={{ top: 8, right: 8, left: 0, bottom: 4 }}>
                  <defs>
                    <linearGradient id={`grad-${metric.id}`} x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%"   stopColor={strokeColor} stopOpacity={0.35} />
                      <stop offset="100%" stopColor={strokeColor} stopOpacity={0.02} />
                    </linearGradient>
                  </defs>
                  <YAxis domain={[minV - pad, maxV + pad]} hide />
                  {metric.warningThreshold !== null && (
                    <ReferenceLine y={metric.warningThreshold} stroke="#f5c264" strokeDasharray="4 3" strokeWidth={1}
                      label={{ value: 'warn', position: 'insideTopRight', fontSize: 8, fill: '#f5c264', dy: -2 }} />
                  )}
                  {metric.criticalThreshold !== null && (
                    <ReferenceLine y={metric.criticalThreshold} stroke="#f58c8c" strokeDasharray="4 3" strokeWidth={1}
                      label={{ value: 'crit', position: 'insideTopRight', fontSize: 8, fill: '#f58c8c', dy: -2 }} />
                  )}
                  <Area type="monotone" dataKey="v" stroke={strokeColor} strokeWidth={2}
                    fill={`url(#grad-${metric.id})`} dot={false}
                    activeDot={{ r: 3, fill: strokeColor, strokeWidth: 0 }} isAnimationActive={false} />
                  <Tooltip cursor={{ stroke: strokeColor, strokeWidth: 1, strokeOpacity: 0.3 }}
                    content={({ active, payload }) =>
                      active && payload?.length ? (
                        <div className="bg-surface-container-low border border-outline-variant px-2 py-1 rounded-lg text-[10px] font-mono" style={{ color: strokeColor }}>
                          {Number(payload[0].value).toLocaleString(undefined, { maximumFractionDigits: 2 })}
                        </div>
                      ) : null
                    }
                  />
                </AreaChart>
              </ResponsiveContainer>
              <div className="flex items-center justify-between px-3 py-1.5 border-t border-primary/5 text-[10px] font-mono">
                <span className="text-outline">min <span className="text-on-surface-variant">{minV.toLocaleString(undefined, { maximumFractionDigits: 1 })}</span></span>
                <span className="text-outline">{chartData.length} pts</span>
                <span className="text-outline">max <span className="text-on-surface-variant">{maxV.toLocaleString(undefined, { maximumFractionDigits: 1 })}</span></span>
              </div>
            </div>
          );
        })() : (
          <div className="rounded-lg border border-outline-variant/60 bg-background-dark/60 h-24 flex flex-col items-center justify-center gap-1.5">
            <div className="flex items-end gap-0.5 h-6 opacity-20">
              {/* Ornement « en attente » : des hauteurs fixes plutôt qu'un tirage au sort à
                  chaque rendu, qui rendait le rendu impur et faisait frémir les barres. */}
              {PLACEHOLDER_BARS.map((height, i) => (
                <div key={i} className="w-1 bg-primary rounded-sm" style={{ height: `${height}%` }} />
              ))}
            </div>
            <span className="text-[10px] text-outline uppercase tracking-wider">Waiting for data…</span>
          </div>
        )}
      </div>

      {/* Les composantes dans le temps.

          `history` porte la valeur, qui pour un gabarit à deux requêtes est la *comparaison* : sur
          un écart c'est la différence, et ce qu'un opérateur a besoin de voir bouger, ce sont les
          deux comptes. Un trou reste un trou (`connectNulls={false}`) : un rafraîchissement qui n'a
          rien mesuré ne doit pas être relié comme s'il avait mesuré. */}
      {series.length > 0 && (
        <div className="px-3 pb-2">
          <div className="rounded-lg overflow-hidden border border-outline-variant/60 bg-background-dark/60">
            <ResponsiveContainer width="100%" height={56}>
              <LineChart data={seriesData} margin={{ top: 6, right: 8, left: 0, bottom: 2 }}>
                <YAxis hide domain={['dataMin', 'dataMax']} />
                {series.map(s => (
                  <Line key={s.key} type="monotone" dataKey={s.key} stroke={s.color} strokeWidth={1.5}
                    dot={false} connectNulls={false} isAnimationActive={false} />
                ))}
                <Tooltip cursor={{ stroke: '#8f93a3', strokeWidth: 1, strokeOpacity: 0.3 }}
                  content={({ active, payload }) =>
                    active && payload?.length ? (
                      <div className="bg-surface-container-low border border-outline-variant px-2 py-1 rounded-lg text-[10px] font-mono space-y-0.5">
                        {payload.map(entry => {
                          const s = series.find(x => x.key === entry.dataKey);
                          return s && typeof entry.value === 'number' ? (
                            <div key={s.key} style={{ color: s.color }}>{s.label} {s.format(entry.value)}</div>
                          ) : null;
                        })}
                      </div>
                    ) : null
                  }
                />
              </LineChart>
            </ResponsiveContainer>
            <div className="flex items-center gap-3 px-3 py-1.5 border-t border-primary/5 text-[10px] font-mono">
              {series.map(s => (
                <span key={s.key} className="flex items-center gap-1 text-outline">
                  <span aria-hidden="true" className="w-2 h-0.5 rounded-full" style={{ background: s.color }} />
                  {s.label}
                </span>
              ))}
            </div>
          </div>
        </div>
      )}

      {/* Ce que la mesure a couvert.

          `lastSummary` était calculé, persisté, et rendu nulle part hors de l'aperçu du modal :
          une métrique en service ne disait rien de sa portée. Pour la latence de transit c'est ce
          qui empêche de lire la moyenne comme un verdict — voir METRICS-TWO-QUERY-AUDIT.md, D6. */}
      {scopeChips.length > 0 && (
        <div
          className="border-t border-outline-variant/60 px-4 py-2 flex flex-wrap gap-1.5"
          title={scopeNoteOf(metric.lastSummary) ?? undefined}
        >
          {scopeChips.map(chip => (
            <span
              key={chip.label}
              title={chip.detail}
              className={`text-[10px] font-mono px-1.5 py-0.5 rounded border ${
                chip.tone === 'warning'
                  ? 'text-warning border-warning/30 bg-warning/5'
                  : 'text-on-surface-variant border-outline-variant/60'
              }`}
            >
              {chip.label}
            </span>
          ))}
        </div>
      )}

      {/* SQL footer — ou ce qui en tient lieu.

          Une métrique de gabarit n'a pas de SQL : ses paramètres SONT la requête, et le champ
          arrive à `null` du serveur. La carte appelait `metric.sql.replace(…)` dessus, ce qui
          faisait tomber toute la page — pas seulement la carte — dès qu'une métrique de gabarit
          était enregistrée. Le type l'annonçait `string`, ce qu'il n'a jamais été. */}
      <div className="group border-t border-outline-variant/60 bg-background-dark/40 px-4 py-2.5 flex items-start gap-2">
        <span className="material-symbols-outlined text-primary/40 text-base shrink-0 mt-0.5">code</span>
        <pre
          title={metric.sql ? metric.sql.replace(/\s+/g, ' ') : describeTemplate(metric)}
          className="flex-1 text-[10px] font-mono text-on-surface-variant truncate leading-relaxed whitespace-nowrap overflow-hidden"
        >
          {metric.sql
            ? metric.sql.replace(/\s+/g, ' ')
            : describeTemplate(metric)}
        </pre>
        {metric.sql && (
          <button onClick={() => void copyText(metric.sql ?? '').then(ok =>
              toast(ok ? 'SQL copied' : 'Could not copy to the clipboard', ok ? 'success' : 'error'))}
            title="Copy SQL" aria-label="Copy the metric SQL" className="shrink-0 inline-flex items-center justify-center w-6 h-6 text-outline hover:text-primary transition-colors opacity-0 group-hover:opacity-100">
            <span className="material-symbols-outlined text-base">content_copy</span>
          </button>
        )}
      </div>

      {/* La règle Prometheus.

          Tout ceci existe pour qu'une alerte se déclenche, et la page s'arrêtait une marche avant :
          la PromQL n'était écrite nulle part, et depuis les séries compagnes la règle correcte n'est
          plus évidente — une valeur gelée se déclenche exactement comme une vraie. */}
      <div className="border-t border-outline-variant/60 px-4 py-2 flex items-center justify-between gap-2">
        {alert.unavailable ? (
          <InfoTooltip content={alert.unavailable}>
            <span tabIndex={0} className="text-[10px] text-outline flex items-center gap-1 rounded min-w-0">
              <span className="material-symbols-outlined text-[11px] shrink-0">notifications_off</span>
              <span className="truncate">No alert rule from this card</span>
            </span>
          </InfoTooltip>
        ) : (
          <>
            <InfoTooltip content={alert.rules.map(r => `${r.title} — ${r.note}`).join('\n\n')}>
              <span tabIndex={0} className="text-[10px] text-on-surface-variant flex items-center gap-1 rounded min-w-0">
                <span className="material-symbols-outlined text-[11px] shrink-0">notifications_active</span>
                <span className="truncate font-mono">{alert.rules[0].promql.split('\n')[0]}</span>
              </span>
            </InfoTooltip>
            <button
              onClick={() => void copyText(
                alert.rules.map(r => `# ${r.title}\n# ${r.note}\n${r.promql}`).join('\n\n'),
              ).then(ok => toast(
                ok ? `Alert rule copied${alert.rules.length > 1 ? ' (2 variants)' : ''}`
                   : 'Could not copy to the clipboard',
                ok ? 'success' : 'error'))}
              title="Copy the Prometheus alert rule"
              aria-label="Copy the Prometheus alert rule for this metric"
              className="shrink-0 inline-flex items-center justify-center w-6 h-6 text-outline hover:text-primary transition-colors">
              <span className="material-symbols-outlined text-base">content_copy</span>
            </button>
          </>
        )}
      </div>

      {/* Footer */}
      <div className="px-4 py-1.5 flex items-center justify-between border-t border-primary/5">
        <span className="text-[10px] text-outline flex items-center gap-1">
          <span className="material-symbols-outlined text-[11px]">schedule</span>
          {relativeTime(metric.lastUpdateTime)}
        </span>
        <span className={`text-[10px] font-bold ${st.text}`}>{st.label}</span>
      </div>
    </div>
  );
};
