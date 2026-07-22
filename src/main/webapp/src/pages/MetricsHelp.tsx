import React from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, Badge } from '../components/ui';

const MetricsHelp: React.FC = () => {
  const navigate = useNavigate();

  return (
    <div className="min-h-full bg-background text-on-surface p-4 md:p-8 max-w-[1400px] mx-auto">
      {/* Header */}
      <header className="mb-10 flex flex-wrap items-end justify-between gap-4">
        <div>
          <div className="flex items-center gap-2 mb-2">
            <Badge tone="primary">Documentation</Badge>
            <Badge tone="success">v1.2</Badge>
          </div>
          <h1 className="text-2xl md:text-3xl font-semibold tracking-tight text-on-surface mb-2">
            Metrics Guide <span className="text-on-surface-variant font-normal">· Module Kafka SQL</span>
          </h1>
          <p className="text-on-surface-variant max-w-3xl text-[13px] leading-relaxed">
            Configure, monitor and scale your data streams with high-precision telemetry. This guide provides the technical specifications for engineers.
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
                The Metrics module orchestrates stream monitoring by turning analytical SQL queries into real-time, actionable Prometheus indicators.
              </p>
              <ul className="text-[12px] space-y-2 text-primary/80 font-mono uppercase tracking-wider">
                <li className="flex items-center gap-2"><span className="w-1 h-1 bg-primary rounded-full"></span> Business Monitoring</li>
                <li className="flex items-center gap-2"><span className="w-1 h-1 bg-primary rounded-full"></span> Technical Performance</li>
                <li className="flex items-center gap-2"><span className="w-1 h-1 bg-primary rounded-full"></span> Advanced Alerting</li>
              </ul>
            </div>
            <div className="border-l border-outline-variant/60 pl-8">
              <p className="text-[11px] font-bold text-primary tracking-widest uppercase mb-2">Key Objectives</p>
              <p className="text-xs leading-relaxed text-on-surface-variant">
                Detect throughput anomalies, measure sub-millisecond latency and ensure data integrity through native KSQL integration.
              </p>
            </div>
          </div>
        </section>

        {/* 7. Use cases / KPIs */}
        <section className="lg:col-span-4 row-span-2 bg-surface-container-high border border-outline-variant/60 p-6 rounded-xl flex flex-col">
          <h3 className="text-xs font-bold text-primary/60 tracking-[0.2em] uppercase mb-6">Performance Indicators (SLA)</h3>
          <div className="space-y-6 flex-1">
            <div className="p-4 bg-surface-container border-l-2 border-success/50">
              <div className="flex justify-between items-start mb-2">
                <span className="text-[10px] font-bold text-success uppercase tracking-widest">SQL Availability</span>
                <span className="material-symbols-outlined text-[18px] text-success">check_circle</span>
              </div>
              <p className="text-[24px] font-bold text-on-surface leading-none mb-1">99.98%</p>
              <p className="text-[10px] text-on-surface-variant">Success/failure ratio of the rules engine.</p>
            </div>
            <div className="p-4 bg-surface-container border-l-2 border-warning/50">
              <div className="flex justify-between items-start mb-2">
                <span className="text-[10px] font-bold text-warning uppercase tracking-widest">Average Latency</span>
                <span className="material-symbols-outlined text-[18px] text-warning">timer</span>
              </div>
              <p className="text-[24px] font-bold text-on-surface leading-none mb-1">14 ms</p>
              <p className="text-[10px] text-on-surface-variant">Prometheus Actuator scrape time.</p>
            </div>
            <div className="p-4 bg-surface-container border-l-2 border-error/50">
              <div className="flex justify-between items-start mb-2">
                <span className="text-[10px] font-bold text-error uppercase tracking-widest">Technical Error Rate</span>
                <span className="material-symbols-outlined text-[18px] text-error">error</span>
              </div>
              <p className="text-[24px] font-bold text-on-surface leading-none mb-1">0.02 %</p>
              <p className="text-[10px] text-on-surface-variant">Messages rejected for SQL non-compliance.</p>
            </div>
          </div>
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
            <span className="text-[10px] font-mono bg-primary/20 text-primary px-3 py-1 rounded-full uppercase">Engine v2.1</span>
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
            <div className="bg-surface-container/50 p-6 rounded-xl border border-outline-variant hover:border-primary/40 transition-all">
              <h4 className="text-on-primary-container font-bold text-lg tracking-tight mb-2">SUMMARY</h4>
              <p className="text-[11px] text-on-surface-variant leading-relaxed mb-4">Computes quantiles over a sliding window.</p>
              <span className="px-2 py-0.5 bg-primary-container text-on-primary-container text-[10px] font-bold rounded-xl">STATISTICAL</span>
            </div>
            <div className="bg-surface-container/50 p-6 rounded-xl border border-outline-variant hover:border-primary/40 transition-all">
              <h4 className="text-on-primary-container font-bold text-lg tracking-tight mb-2">TIMER</h4>
              <p className="text-[11px] text-on-surface-variant leading-relaxed mb-4">Measures event duration and the associated throughput.</p>
              <span className="px-2 py-0.5 bg-primary/10 text-primary text-[10px] font-bold rounded-xl">LATENCY / MS</span>
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
            <div className="bg-surface-container-high/30 p-4 rounded-xl border border-outline-variant space-y-3">
              <div className="flex justify-between text-[11px]">
                <span className="text-on-surface-variant uppercase font-bold">Scrape Interval :</span>
                <span className="text-primary font-mono">15s - 60s recommended</span>
              </div>
              <div className="flex justify-between text-[11px]">
                <span className="text-on-surface-variant uppercase font-bold">Naming Convention :</span>
                <span className="text-primary font-mono">kafka_sql_[app]_[metric]</span>
              </div>
              <div className="flex justify-between text-[11px]">
                <span className="text-on-surface-variant uppercase font-bold">Endpoint Security :</span>
                <span className="text-success font-mono">TLS / OAuth2 required</span>
              </div>
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
              <h5 className="text-[12px] font-bold uppercase mb-1">Access & Add</h5>
              <p className="text-[10px] text-on-surface-variant leading-tight">Monitoring menu &gt; Kafka Metrics. Click "Add".</p>
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
            <div>
              <span className="font-bold block text-error text-[12px] uppercase tracking-wider mb-2">"Pending Status"</span>
              <p className="text-[11px] text-on-surface-variant leading-relaxed">The SQL processor is waiting for resources. Increase the interval if the query is heavy.</p>
            </div>
            <div>
              <span className="font-bold block text-error text-[12px] uppercase tracking-wider mb-2">Latency Units</span>
              <p className="text-[11px] text-on-surface-variant leading-relaxed">Time metrics must be normalized to milliseconds for native Grafana aggregation.</p>
            </div>
          </div>
        </section>
      </div>

      {/* System Status Footer */}
      <footer className="mt-16 flex flex-col md:flex-row justify-between items-center gap-6 border-t border-outline-variant/60 pt-8">
        <div className="flex gap-4">
          <div className="flex items-center gap-2">
            <span className="w-2 h-2 rounded-full bg-success animate-pulse"></span>
            <span className="text-[10px] font-bold text-on-surface-variant uppercase tracking-widest">Global Status: Operational</span>
          </div>
          <div className="flex items-center gap-2">
            <span className="text-[10px] font-bold text-primary uppercase tracking-widest">Ingestion: 1.2M msg/sec</span>
          </div>
        </div>
        <div className="flex gap-8 items-center">
          <span className="text-[10px] text-on-surface-variant uppercase font-mono">Updated: {new Date().toISOString().replace('T', ' ').substring(0, 19)} UTC</span>
          <div className="flex gap-4">
            <span className="material-symbols-outlined text-[18px] text-primary/40 cursor-pointer hover:text-primary">terminal</span>
            <span className="material-symbols-outlined text-[18px] text-primary/40 cursor-pointer hover:text-primary">help</span>
            <span className="material-symbols-outlined text-[18px] text-primary/40 cursor-pointer hover:text-primary">share</span>
          </div>
        </div>
      </footer>
    </div>
  );
};

export default MetricsHelp;
