// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React, { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';
import { Button, Badge } from '../components/ui';
import {
  summarizeMetrics, describeAge, describeHealth, healthTone, type MetricStatusInput,
} from './metricsHealth';

/**
 * Guide du module Metrics.
 *
 * La page annonçait trois pavés « SLA » (99,98 % de disponibilité, 14 ms de latence, 0,02 %
 * d'erreurs) et un pied de page « Global Status: Operational · Ingestion: 1.2M msg/sec » : des
 * littéraux, jamais mesurés, sur la page qui enseigne comment mesurer. Elle documentait aussi un
 * type de métrique `TIMER` qui n'existe pas (le quatrième est `HISTOGRAM`), une convention de
 * nommage `kafka_sql_[app]_[metric]` que rien n'applique, une « intégration KSQL native » alors
 * que le moteur est Flink SQL, et un endpoint Prometheus « TLS / OAuth2 required » alors que
 * l'application n'a aucune authentification — ce dernier point n'étant pas seulement faux mais
 * dangereux, puisqu'il décrit une protection que l'exploitant croira acquise.
 *
 * Ce qui remplace les pavés vient de `GET /api/metrics`, que la page Metrics interroge déjà.
 */

const MetricsHelp: React.FC = () => {
  const navigate = useNavigate();
  const [metrics, setMetrics] = useState<MetricStatusInput[] | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);

  useEffect(() => {
    axios.get<MetricStatusInput[]>('/api/metrics')
      .then(res => setMetrics(res.data ?? []))
      // Un panneau d'état qui échoue le dit : afficher des zéros serait retomber dans le défaut
      // que cette page vient de corriger.
      .catch(() => setLoadFailed(true));
  }, []);

  const health = useMemo(() => summarizeMetrics(metrics ?? []), [metrics]);
  const refreshedAt = describeAge(health.lastRefresh);

  return (
    <div className="min-h-full bg-background text-on-surface p-4 md:p-8 max-w-[1400px] mx-auto">
      {/* Header */}
      <header className="mb-10 flex flex-wrap items-end justify-between gap-4">
        <div>
          <div className="flex items-center gap-2 mb-2">
            <Badge tone="primary">Documentation</Badge>
          </div>
          <h1 className="text-2xl md:text-3xl font-semibold tracking-tight text-on-surface mb-2">
            Metrics Guide <span className="text-on-surface-variant font-normal">· Module Kafka SQL</span>
          </h1>
          <p className="text-on-surface-variant max-w-3xl text-[13px] leading-relaxed">
            How a SQL query becomes a Prometheus series here: the types available, what the SQL must look like, how the
            endpoint is exposed, and what your own instance is reporting at this moment.
          </p>
        </div>
        <Button variant="secondary" icon="arrow_back" onClick={() => navigate('/metrics')}>Back to Metrics</Button>
      </header>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        {/* 1. Introduction */}
        <section className="lg:col-span-8 p-8 bg-surface-container border border-outline-variant/60 rounded-xl relative overflow-hidden group">
          <div className="absolute top-0 right-0 p-4 opacity-10 group-hover:opacity-20 transition-opacity">
            <span className="material-symbols-outlined text-6xl text-primary" style={{ fontVariationSettings: "'FILL' 1" }}>insights</span>
          </div>
          <h2 className="text-xl font-bold text-primary mb-4 flex items-center gap-2">
            <span className="material-symbols-outlined text-[22px]">info</span> 01. Introduction
          </h2>
          <div className="grid md:grid-cols-2 gap-8 relative z-10">
            <div>
              <p className="text-sm leading-relaxed text-on-surface-variant mb-4">
                The Metrics module turns a Flink SQL query into a Prometheus series: the query runs on a schedule, and
                the column aliased <code className="font-mono text-primary">metric_value</code> becomes the value.
              </p>
              <ul className="text-[12px] space-y-2 text-primary/80 font-mono uppercase tracking-wider">
                <li className="flex items-center gap-2"><span className="w-1 h-1 bg-primary rounded-full"></span> Business Monitoring</li>
                <li className="flex items-center gap-2"><span className="w-1 h-1 bg-primary rounded-full"></span> Technical Performance</li>
                <li className="flex items-center gap-2"><span className="w-1 h-1 bg-primary rounded-full"></span> Advanced Alerting</li>
              </ul>
            </div>
            <div className="border-l border-outline-variant/60 pl-8">
              <p className="text-[11px] font-bold text-primary tracking-widest uppercase mb-2">What it is not</p>
              <p className="text-xs leading-relaxed text-on-surface-variant">
                Not a continuous stream job: each metric is a <strong>bounded</strong> query re-run on an interval, so
                its resolution is that interval — not the message rate.
              </p>
            </div>
          </div>
        </section>

        {/* Live status — measured, not asserted. */}
        <section className="lg:col-span-4 row-span-2 bg-surface-container-high border border-outline-variant/60 p-6 rounded-xl flex flex-col">
          <div className="flex items-baseline justify-between gap-2 mb-1">
            <h3 className="text-xs font-bold text-primary/60 tracking-[0.2em] uppercase">Your metrics, right now</h3>
          </div>
          <p className="text-[10px] text-on-surface-variant mb-6">
            Read from <code className="font-mono">/api/metrics</code> when this page opened
            {refreshedAt && <> · last refresh {refreshedAt}</>}
          </p>

          {loadFailed ? (
            <div className="p-4 bg-error/10 border-l-2 border-error/50 text-[11px] text-on-surface-variant">
              Could not read the metric list. The numbers are unknown — which is why none are shown.
            </div>
          ) : metrics === null ? (
            <div className="p-4 bg-surface-container border-l-2 border-outline-variant text-[11px] text-on-surface-variant">
              Loading…
            </div>
          ) : (
            <div className="space-y-6 flex-1">
              <div className="p-4 bg-surface-container border-l-2 border-primary/50">
                <div className="flex justify-between items-start mb-2">
                  <span className="text-[10px] font-bold text-primary uppercase tracking-widest">Configured</span>
                  <span className="material-symbols-outlined text-[18px] text-primary">functions</span>
                </div>
                <p className="text-[24px] font-bold text-on-surface leading-none mb-2 tabular-nums">{health.total}</p>
                <Badge tone={healthTone(health)}>{describeHealth(health)}</Badge>
              </div>

              <div className={`p-4 bg-surface-container border-l-2 ${health.reporting > 0 ? 'border-success/50' : 'border-outline-variant'}`}>
                <div className="flex justify-between items-start mb-2">
                  <span className="text-[10px] font-bold text-success uppercase tracking-widest">Reporting a value</span>
                  <span className="material-symbols-outlined text-[18px] text-success">check_circle</span>
                </div>
                <p className="text-[24px] font-bold text-on-surface leading-none mb-1 tabular-nums">{health.reporting}</p>
                <p className="text-[10px] text-on-surface-variant">Produced a value on their last run, with no error.</p>
              </div>

              <div className={`p-4 bg-surface-container border-l-2 ${health.failing > 0 ? 'border-error/50' : 'border-outline-variant'}`}>
                <div className="flex justify-between items-start mb-2">
                  <span className="text-[10px] font-bold text-error uppercase tracking-widest">Failing</span>
                  <span className="material-symbols-outlined text-[18px] text-error">error</span>
                </div>
                <p className="text-[24px] font-bold text-on-surface leading-none mb-1 tabular-nums">{health.failing}</p>
                <p className="text-[10px] text-on-surface-variant">
                  Their last run returned an error. A stale value may still be exposed — open Metrics to read the reason.
                </p>
              </div>

              {health.pending > 0 && (
                <div className="p-4 bg-surface-container border-l-2 border-warning/50">
                  <div className="flex justify-between items-start mb-2">
                    <span className="text-[10px] font-bold text-warning uppercase tracking-widest">No value yet</span>
                    <span className="material-symbols-outlined text-[18px] text-warning">pending</span>
                  </div>
                  <p className="text-[24px] font-bold text-on-surface leading-none mb-1 tabular-nums">{health.pending}</p>
                  <p className="text-[10px] text-on-surface-variant">
                    Configured but never evaluated successfully — most often a missing{' '}
                    <code className="font-mono">AS metric_value</code> alias.
                  </p>
                </div>
              )}
            </div>
          )}

          <div className="mt-8 pt-6 border-t border-outline-variant/60">
            <Button variant="primary" className="w-full" icon="arrow_back" onClick={() => navigate('/metrics')}>
              Back to Metrics
            </Button>
          </div>
        </section>

        {/* 3. Configuration templates */}
        <section className="lg:col-span-8 p-8 bg-surface-container border border-outline-variant/60 rounded-xl">
          <div className="flex items-center justify-between mb-8">
            <h2 className="text-xl font-bold text-primary flex items-center gap-3">
              <span className="material-symbols-outlined">dashboard_customize</span> 03. Configuration Templates
            </h2>
            <span className="text-[10px] font-mono bg-primary/20 text-primary px-3 py-1 rounded-full uppercase">2 templates</span>
          </div>
          <div className="grid md:grid-cols-2 gap-8">
            <div className="space-y-4">
              <div className="bg-surface-container-high p-4 rounded-xl border border-outline-variant/60 font-mono text-[13px] text-primary relative">
                <div className="absolute top-2 right-2 flex gap-1">
                  <span className="w-2 h-2 rounded-full bg-error/40"></span>
                  <span className="w-2 h-2 rounded-full bg-warning/40"></span>
                  <span className="w-2 h-2 rounded-full bg-success/40"></span>
                </div>
                <span className="text-primary/50 block mb-2">// RAW_SQL Example</span>
                <span className="text-primary">SELECT</span> COUNT(*) <span className="text-primary">AS</span> metric_value,<br/>
                error_code <span className="text-primary">AS</span> label_code<br/>
                <span className="text-primary">FROM</span> "topic_orders"<br/>
                <span className="text-primary">WHERE</span> status = 'FAILED'<br/>
                <span className="text-primary">GROUP BY</span> error_code;
              </div>
              <p className="text-[11px] text-on-surface-variant italic border-l-2 border-primary/20 pl-4 uppercase font-bold tracking-tight">
                Example of a raw analytical query.
              </p>
            </div>
            <div className="space-y-6">
              <div className="p-4 bg-surface-container rounded-xl border border-outline-variant">
                <h4 className="text-[11px] font-bold text-primary tracking-widest uppercase mb-1">TOPIC_COUNT_DELTA</h4>
                <p className="text-[10px] text-on-surface-variant">Compares throughput between two topics to detect message loss.</p>
              </div>
              <div className="p-4 bg-surface-container rounded-xl border border-outline-variant">
                <h4 className="text-[11px] font-bold text-primary tracking-widest uppercase mb-1">TOPIC_TRANSIT_LATENCY</h4>
                <p className="text-[10px] text-on-surface-variant">Measures the delay (ms) between ingestion on Topic A and output on Topic B.</p>
              </div>
            </div>
          </div>
        </section>

        {/* 2. Metric types */}
        <section className="lg:col-span-12 p-8 bg-surface-container-high border border-outline-variant/60 rounded-xl">
          <h2 className="text-xl font-bold text-primary mb-8 flex items-center gap-3">
            <span className="material-symbols-outlined">analytics</span> 02. Prometheus Metric Types
          </h2>
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-6">
            <div className="bg-surface-container/50 p-6 rounded-xl border border-outline-variant hover:border-primary/40 transition-all">
              <h4 className="text-primary font-bold text-lg tracking-tight mb-2">GAUGE</h4>
              <p className="text-[11px] text-on-surface-variant leading-relaxed mb-4">Point-in-time value that can go up or down (stock levels, queues).</p>
              <span className="px-2 py-0.5 bg-primary/10 text-primary text-[10px] font-bold rounded-xl">UP / DOWN</span>
            </div>
            <div className="bg-surface-container/50 p-6 rounded-xl border border-outline-variant hover:border-primary/40 transition-all">
              <h4 className="text-success font-bold text-lg tracking-tight mb-2">COUNTER</h4>
              <p className="text-[11px] text-on-surface-variant leading-relaxed mb-4">Monotonic cumulative value. Resets to zero on restart.</p>
              <span className="px-2 py-0.5 bg-success/10 text-success text-[10px] font-bold rounded-xl">INCREMENT ONLY</span>
            </div>
            {/* HISTOGRAM, pas TIMER : la page annonçait un quatrième type que le backend n'a jamais
                eu, et taisait celui qu'il a. */}
            <div className="bg-surface-container/50 p-6 rounded-xl border border-outline-variant hover:border-primary/40 transition-all">
              <h4 className="text-on-primary-container font-bold text-lg tracking-tight mb-2">HISTOGRAM</h4>
              <p className="text-[11px] text-on-surface-variant leading-relaxed mb-4">
                Distribution summary with Prometheus-side buckets — aggregate quantiles across instances in PromQL.
              </p>
              <span className="px-2 py-0.5 bg-primary/10 text-primary text-[10px] font-bold rounded-xl">BUCKETS</span>
            </div>
            <div className="bg-surface-container/50 p-6 rounded-xl border border-outline-variant hover:border-primary/40 transition-all">
              <h4 className="text-on-primary-container font-bold text-lg tracking-tight mb-2">SUMMARY</h4>
              <p className="text-[11px] text-on-surface-variant leading-relaxed mb-4">
                Client-side quantiles (p50/p75/p90/p95/p99), computed in the app and exposed ready-made.
              </p>
              <span className="px-2 py-0.5 bg-primary-container text-on-primary-container text-[10px] font-bold rounded-xl">QUANTILES</span>
            </div>
          </div>
        </section>

        {/* 4. SQL constraints & 6. Prometheus integration */}
        <section className="lg:col-span-12 p-8 bg-surface-container border border-outline-variant/60 rounded-xl grid md:grid-cols-2 gap-12">
          <div>
            <h2 className="text-xl font-bold text-primary mb-6 flex items-center gap-3">
              <span className="material-symbols-outlined">gavel</span> 04. SQL Constraints
            </h2>
            <ul className="space-y-4">
              <li className="flex gap-4">
                <span className="material-symbols-outlined text-primary text-[20px]">check_circle</span>
                <div>
                  <span className="font-bold block text-xs">Alias 'metric_value'</span>
                  <p className="text-[11px] text-on-surface-variant leading-relaxed">Required for the main numeric data column.</p>
                </div>
              </li>
              <li className="flex gap-4">
                <span className="material-symbols-outlined text-primary text-[20px]">check_circle</span>
                <div>
                  <span className="font-bold block text-xs">Bounded Scan Limit</span>
                  <p className="text-[11px] text-on-surface-variant leading-relaxed">A safety limit (100k messages) is applied per interval.</p>
                </div>
              </li>
            </ul>
          </div>
          <div>
            <h2 className="text-xl font-bold text-primary mb-6 flex items-center gap-3">
              <span className="material-symbols-outlined">sensors</span> 06. Prometheus Integration
            </h2>
            {/* Les trois lignes d'origine annonçaient une convention de nommage que rien n'applique
                et une sécurité qui n'existe pas — la seconde étant le pire des cas : décrire une
                protection acquise à un exploitant qui n'en a aucune. */}
            <div className="bg-surface-container-high/30 p-4 rounded-xl border border-outline-variant space-y-3">
              <div className="flex justify-between gap-3 text-[11px]">
                <span className="text-on-surface-variant uppercase font-bold shrink-0">Endpoint :</span>
                <span className="text-primary font-mono text-right">GET /actuator/prometheus</span>
              </div>
              <div className="flex justify-between gap-3 text-[11px]">
                <span className="text-on-surface-variant uppercase font-bold shrink-0">Series :</span>
                <span className="text-primary font-mono text-right">explorer_metric_{'{'}gauge|counter|histogram|summary{'}'}</span>
              </div>
              <div className="flex justify-between gap-3 text-[11px]">
                <span className="text-on-surface-variant uppercase font-bold shrink-0">Labels :</span>
                <span className="text-primary font-mono text-right">metric_id, metric_name, metric_type + your columns</span>
              </div>
              <div className="flex justify-between gap-3 text-[11px]">
                <span className="text-on-surface-variant uppercase font-bold shrink-0">Scrape :</span>
                <span className="text-primary font-mono text-right">no faster than the metric&rsquo;s own interval</span>
              </div>
              <p className="text-[11px] text-warning leading-relaxed border-t border-outline-variant/60 pt-3">
                <strong>No authentication.</strong> This app ships without any, and the endpoint is served over plain
                HTTP alongside the UI. Put it behind your own reverse proxy or network policy before exposing it —
                nothing here does it for you.
              </p>
              <p className="text-[10px] text-on-surface-variant font-mono leading-relaxed">
                explorer_metric_gauge{'{'}metric_name=&quot;orders_failed&quot;{'}'}
              </p>
            </div>
          </div>
        </section>

        {/* 5. Step-by-step guide */}
        <section className="lg:col-span-12 p-8 bg-surface-container border border-primary/20 rounded-xl">
          <div className="text-center mb-12">
            <h2 className="text-2xl font-black text-on-surface mb-2">Deployment Guide</h2>
            <p className="text-primary text-[10px] font-bold uppercase tracking-[0.3em]">Quick-Start Integration Guide</p>
          </div>
          <div className="flex flex-col lg:flex-row items-center justify-between gap-4">
            <div className="flex flex-col items-center text-center max-w-[200px] relative">
              <div className="w-12 h-12 rounded-full bg-primary flex items-center justify-center text-on-primary font-bold mb-4">1</div>
              {/* Le chemin annoncé — « Monitoring menu > Kafka Metrics » — n'existe nulle part : le
                  groupe de navigation est « Observe », l'écran « Metrics », le bouton « Add metric ». */}
              <h5 className="text-[12px] font-bold uppercase mb-1">Access & Add</h5>
              <p className="text-[10px] text-on-surface-variant leading-tight">Sidebar &gt; Observe &gt; Metrics, then &ldquo;Add metric&rdquo;.</p>
              <div className="hidden lg:block absolute -right-full top-6 w-full border-t-2 border-dashed border-primary/20"></div>
            </div>
            <div className="flex flex-col items-center text-center max-w-[200px] relative">
              <div className="w-12 h-12 rounded-full bg-surface-container-high border-2 border-primary flex items-center justify-center text-primary font-bold mb-4">2</div>
              <h5 className="text-[12px] font-bold uppercase mb-1">Template & SQL</h5>
              <p className="text-[10px] text-on-surface-variant leading-tight">Choose RAW_SQL and write your aggregation query.</p>
              <div className="hidden lg:block absolute -right-full top-6 w-full border-t-2 border-dashed border-primary/20"></div>
            </div>
            <div className="flex flex-col items-center text-center max-w-[200px] relative">
              <div className="w-12 h-12 rounded-full bg-surface-container-high border-2 border-primary flex items-center justify-center text-primary font-bold mb-4">3</div>
              <h5 className="text-[12px] font-bold uppercase mb-1">Test & Alerts</h5>
              <p className="text-[10px] text-on-surface-variant leading-tight">Check the instant results and set the SLA thresholds.</p>
              <div className="hidden lg:block absolute -right-full top-6 w-full border-t-2 border-dashed border-primary/20"></div>
            </div>
            <div className="flex flex-col items-center text-center max-w-[200px]">
              <div className="w-12 h-12 rounded-full bg-surface-container-high border-2 border-primary flex items-center justify-center text-primary font-bold mb-4">4</div>
              <h5 className="text-[12px] font-bold uppercase mb-1">Save</h5>
              <p className="text-[10px] text-on-surface-variant leading-tight">Expose the metric on the global Prometheus endpoint.</p>
            </div>
          </div>
        </section>

        {/* 8. Troubleshooting */}
        <section className="lg:col-span-12 p-8 border border-error/20 bg-error/5 rounded-lg">
          <h3 className="text-xl font-bold flex items-center gap-3 text-error mb-6">
            <span className="material-symbols-outlined">report</span> 08. Troubleshooting & Errors
          </h3>
          <div className="grid md:grid-cols-3 gap-8">
            <div>
              <span className="font-bold block text-error text-[12px] uppercase tracking-wider mb-2">"No rows returned"</span>
              <p className="text-[11px] text-on-surface-variant leading-relaxed">Check your WHERE filters and make sure the topics contain data within the scan window.</p>
            </div>
            {/* « En attente de ressources » et la normalisation en millisecondes étaient deux causes
                inventées. La vraie raison d'un statut pending est l'alias manquant, et rien dans
                l'application ne convertit d'unité. */}
            <div>
              <span className="font-bold block text-error text-[12px] uppercase tracking-wider mb-2">Stuck on "pending"</span>
              <p className="text-[11px] text-on-surface-variant leading-relaxed">
                The status stays pending while no value has been read, and the usual cause is the alias: the query must
                return a column named <code className="font-mono">metric_value</code>. Run it in the SQL editor to see
                what it actually returns.
              </p>
            </div>
            <div>
              <span className="font-bold block text-error text-[12px] uppercase tracking-wider mb-2">Units</span>
              <p className="text-[11px] text-on-surface-variant leading-relaxed">
                Nothing converts anything: the value exposed is exactly what your SQL returned. Pick a unit, apply it in
                the query, and say so in the metric name — <code className="font-mono">…_ms</code>,{' '}
                <code className="font-mono">…_bytes</code>.
              </p>
            </div>
          </div>
        </section>
      </div>

      {/*
        Le pied de page affichait « Global Status: Operational », « Ingestion: 1.2M msg/sec » et un
        « Updated: <maintenant> » qui n'était que l'horloge du navigateur présentée comme une date
        de document — plus trois icônes qui ne cliquaient sur rien. Ce qui reste est ce que la page
        peut dire sans mentir : où aller ensuite.
      */}
      <footer className="mt-16 flex flex-col md:flex-row justify-between items-center gap-4 border-t border-outline-variant/60 pt-8">
        <p className="text-[11px] text-on-surface-variant leading-relaxed">
          The numbers on this page are read from your own instance. Everything else is a description of how the module
          behaves — see <code className="font-mono text-primary">MetricService</code> if you need the exact rules.
        </p>
        <div className="flex gap-2 shrink-0">
          <Button variant="secondary" icon="monitoring" onClick={() => navigate('/metrics')}>Metrics</Button>
          <Button variant="ghost" icon="help" onClick={() => navigate('/help')}>SQL guide</Button>
        </div>
      </footer>
    </div>
  );
};

export default MetricsHelp;
