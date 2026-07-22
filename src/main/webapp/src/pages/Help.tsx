import React, { useState } from 'react';
import { Link } from 'react-router-dom';

const SHORTCUTS = [
  { key: 'Ctrl + Enter', desc: 'Execute current SQL query', where: 'SQL Editor' },
  { key: 'Ctrl + S', desc: 'Save query (coming soon)', where: 'SQL Editor' },
  { key: 'Esc', desc: 'Close modal / deselect node', where: 'Global' },
  { key: 'Scroll', desc: 'Zoom in / out on graph', where: 'Lineage · Stream Flow' },
  { key: 'Drag', desc: 'Pan the graph canvas', where: 'Lineage · Stream Flow' },
];

const SQL_EXAMPLES = [
  {
    title: 'Simple scan',
    description: 'Read the latest messages from a topic',
    sql: 'SELECT * FROM "orders_raw" LIMIT 50',
  },
  {
    title: 'Tumbling window aggregation',
    description: 'Count events per 5-minute window',
    sql: `SELECT
  window_start, window_end,
  COUNT(*) AS event_count
FROM TABLE(
  TUMBLE(TABLE orders, DESCRIPTOR(event_time), INTERVAL '5' MINUTES)
)
GROUP BY window_start, window_end`,
  },
  {
    title: 'Stream join',
    description: 'Join two Kafka topics on a common key',
    sql: `SELECT o.order_id, o.amount, u.email
FROM orders AS o
JOIN users FOR SYSTEM_TIME AS OF o.proc_time AS u
  ON o.user_id = u.id`,
  },
  {
    title: 'Deduplication',
    description: 'Keep only the latest record per key',
    sql: `SELECT order_id, status, amount
FROM (
  SELECT *, ROW_NUMBER() OVER (
    PARTITION BY order_id ORDER BY event_time DESC
  ) AS rn
  FROM orders
) WHERE rn = 1`,
  },
  {
    title: 'XmlExtract UDF',
    description: 'Extract a value from an XML payload',
    sql: `SELECT
  message_key,
  XmlExtract(raw_value, '/root/order/id') AS order_id
FROM xml_topic`,
  },
];

const FORMATS = [
  { name: 'JSON', badge: 'text-success bg-success/10 border-success/20', desc: 'Auto-detected. Schema inferred from sampled messages.' },
  { name: 'XML', badge: 'text-primary bg-primary/10 border-primary/20', desc: 'Auto-detected. Use XmlExtract UDF to navigate payloads.' },
  { name: 'PLAIN', badge: 'text-on-surface-variant bg-outline/10 border-outline-variant/20', desc: 'Raw string. No schema inference.' },
];

const Help: React.FC = () => {
  const [copiedIdx, setCopiedIdx] = useState<number | null>(null);

  const copy = (sql: string, idx: number) => {
    navigator.clipboard.writeText(sql);
    setCopiedIdx(idx);
    setTimeout(() => setCopiedIdx(null), 1500);
  };

  return (
    <div className="p-6 max-w-4xl mx-auto space-y-10">
      <div>
        <h1 className="text-2xl font-bold">Help & Reference</h1>
        <p className="text-on-surface0 text-sm mt-1">Keyboard shortcuts, SQL examples, and format support.</p>
      </div>

      {/* Quick links */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        {[
          { label: 'SQL Editor', path: '/query', icon: 'terminal' },
          { label: 'Lineage Graph', path: '/lineage', icon: 'account_tree' },
          { label: 'Audit', path: '/audit', icon: 'assignment' },
          { label: 'Configuration', path: '/config', icon: 'settings' },
        ].map(link => (
          <Link
            key={link.path}
            to={link.path}
            className="flex items-center gap-3 p-3 rounded-xl border border-primary/10 bg-primary/5 hover:border-primary/30 hover:bg-primary/10 transition-all"
          >
            <span className="material-symbols-outlined text-primary">{link.icon}</span>
            <span className="text-sm font-medium">{link.label}</span>
          </Link>
        ))}
      </div>

      {/* Keyboard shortcuts */}
      <section>
        <h2 className="text-sm font-bold text-on-surface0 uppercase tracking-widest mb-4 flex items-center gap-2">
          <span className="material-symbols-outlined text-primary">keyboard</span>
          Keyboard Shortcuts
        </h2>
        <div className="rounded-xl border border-primary/10 overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-primary/5 border-b border-primary/10 text-[10px] uppercase tracking-widest text-on-surface0">
                <th className="text-left px-5 py-3">Shortcut</th>
                <th className="text-left px-5 py-3">Action</th>
                <th className="text-left px-5 py-3">Context</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-primary/5">
              {SHORTCUTS.map(s => (
                <tr key={s.key} className="hover:bg-primary/5 transition-colors">
                  <td className="px-5 py-3">
                    <kbd className="px-2 py-0.5 rounded bg-surface-container border border-outline-variant font-mono text-xs text-on-surface">{s.key}</kbd>
                  </td>
                  <td className="px-5 py-3 text-on-surface">{s.desc}</td>
                  <td className="px-5 py-3 text-xs text-on-surface0">{s.where}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      {/* Message formats */}
      <section>
        <h2 className="text-sm font-bold text-on-surface0 uppercase tracking-widest mb-4 flex items-center gap-2">
          <span className="material-symbols-outlined text-primary">format_shapes</span>
          Supported Message Formats
        </h2>
        <div className="grid grid-cols-3 gap-3">
          {FORMATS.map(f => (
            <div key={f.name} className="rounded-xl border border-primary/10 bg-primary/5 p-4">
              <span className={`inline-block px-2 py-0.5 rounded border text-[10px] font-bold uppercase mb-2 ${f.badge}`}>{f.name}</span>
              <p className="text-xs text-on-surface-variant leading-relaxed">{f.desc}</p>
            </div>
          ))}
        </div>
      </section>

      {/* Execution Engines */}
      <section>
        <h2 className="text-sm font-bold text-on-surface0 uppercase tracking-widest mb-4 flex items-center gap-2">
          <span className="material-symbols-outlined text-primary">settings_ethernet</span>
          Execution Engines
        </h2>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div className="rounded-xl border border-primary/20 bg-primary/5 p-4 space-y-2">
            <div className="flex items-center gap-2">
              <span className="px-2 py-0.5 rounded border text-[10px] font-bold uppercase text-primary bg-primary/10 border-primary/20">Kafka Direct</span>
              <span className="text-[10px] text-on-surface0">SELECT · Exploration</span>
            </div>
            <p className="text-xs text-on-surface leading-relaxed">
              All <code className="text-primary bg-primary/30 px-1 rounded">SELECT</code> queries run through a bounded Kafka scan — no Flink SQL planner involved.
              Supported: projections, <code className="text-primary bg-primary/30 px-1 rounded">WHERE</code>, aggregates (<code className="text-primary bg-primary/30 px-1 rounded">COUNT / SUM / AVG / MAX / MIN</code>), <code className="text-primary bg-primary/30 px-1 rounded">GROUP BY</code>, and <code className="text-primary bg-primary/30 px-1 rounded">TUMBLE</code> windows.
            </p>
            <p className="text-xs text-on-surface0 leading-relaxed">
              Not supported: multi-topic JOINs, sub-queries, arbitrary SQL functions. Up to 100 000 messages are scanned for aggregates.
            </p>
          </div>
          <div className="rounded-xl border border-secondary/20 bg-secondary/5 p-4 space-y-2">
            <div className="flex items-center gap-2">
              <span className="px-2 py-0.5 rounded border text-[10px] font-bold uppercase text-secondary bg-secondary/10 border-secondary/20">Flink Streaming</span>
              <span className="text-[10px] text-on-surface0">INSERT INTO · Continuous jobs</span>
            </div>
            <p className="text-xs text-on-surface leading-relaxed">
              <code className="text-secondary bg-secondary/30 px-1 rounded">INSERT INTO</code> statements are submitted as asynchronous Flink jobs via <em>Job mode</em>.
              Jobs are tracked in the Dashboard with their real status, Flink job ID, and history.
            </p>
            <p className="text-xs text-on-surface0 leading-relaxed">
              Use <code className="text-secondary bg-secondary/30 px-1 rounded">EXPLAIN</code> and <code className="text-secondary bg-secondary/30 px-1 rounded">CREATE TABLE</code> to register tables in the Flink catalog before submitting a job.
            </p>
          </div>
        </div>
      </section>

      {/* SQL Examples */}
      <section>
        <h2 className="text-sm font-bold text-on-surface0 uppercase tracking-widest mb-4 flex items-center gap-2">
          <span className="material-symbols-outlined text-primary">code</span>
          Flink SQL Examples
        </h2>
        <div className="space-y-4">
          {SQL_EXAMPLES.map((ex, idx) => (
            <div key={idx} className="rounded-xl border border-primary/10 overflow-hidden">
              <div className="px-4 py-3 bg-primary/5 border-b border-primary/10 flex items-center justify-between">
                <div>
                  <span className="font-bold text-sm text-on-surface">{ex.title}</span>
                  <span className="text-on-surface0 text-xs ml-3">{ex.description}</span>
                </div>
                <div className="flex items-center gap-2">
                  <button
                    onClick={() => copy(ex.sql, idx)}
                    className="flex items-center gap-1.5 text-xs text-on-surface0 hover:text-primary transition-colors"
                  >
                    <span className="material-symbols-outlined text-base">
                      {copiedIdx === idx ? 'check' : 'content_copy'}
                    </span>
                    {copiedIdx === idx ? 'Copied' : 'Copy'}
                  </button>
                  <Link
                    to={`/query?sql=${encodeURIComponent(ex.sql)}`}
                    className="flex items-center gap-1.5 text-xs text-primary hover:underline"
                  >
                    <span className="material-symbols-outlined text-base">open_in_new</span>
                    Open in editor
                  </Link>
                </div>
              </div>
              <pre className="p-4 font-mono text-xs text-on-surface leading-relaxed overflow-x-auto bg-background-dark/50">{ex.sql}</pre>
            </div>
          ))}
        </div>
      </section>
    </div>
  );
};

export default Help;
