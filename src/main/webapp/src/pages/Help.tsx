import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import {
  PageHeader, Card, Button, Badge, type BadgeTone,
  Table, TableHead, TableBody, TableRow, Th, Td,
} from '../components/ui';

/**
 * Uniquement des raccourcis qui existent : la ligne « Ctrl + S — Save query (coming soon) »
 * annonçait une touche qui ne faisait rien (l'enregistrement se fait par le bouton de l'éditeur).
 */
const SHORTCUTS = [
  { key: '⌘ / Ctrl + K', desc: 'Open the command palette — pages, topics, tables, or trace a key', where: 'Global' },
  { key: '⌘ / Ctrl + Enter', desc: 'Run the current query (or just the selection)', where: 'SQL Editor' },
  { key: 'Esc', desc: 'Close a modal, or deselect the focused node', where: 'Global · Graphs' },
  { key: 'Scroll / drag', desc: 'Zoom and pan — pointer or touch', where: 'Lineage · Stream Flow' },
  { key: '↑ ↓ ← →', desc: 'Pan the graph (hold Shift for larger steps)', where: 'Lineage · Stream Flow' },
  { key: '+ / −', desc: 'Zoom the graph in and out', where: 'Lineage · Stream Flow' },
  { key: '0', desc: 'Fit the graph back into view', where: 'Lineage · Stream Flow' },
  { key: 'Tab, then Enter', desc: 'Move between graph nodes and open the focused one', where: 'Lineage · Stream Flow' },
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

const FORMATS: { name: string; tone: BadgeTone; desc: string }[] = [
  { name: 'JSON', tone: 'success', desc: 'Auto-detected. Schema inferred from sampled messages.' },
  { name: 'XML', tone: 'primary', desc: 'Auto-detected. Use XmlExtract UDF to navigate payloads.' },
  { name: 'PLAIN', tone: 'neutral', desc: 'Raw string. No schema inference.' },
];

const SectionTitle: React.FC<{ icon: string; children: React.ReactNode }> = ({ icon, children }) => (
  <h2 className="text-[13px] font-semibold text-on-surface mb-4 flex items-center gap-2">
    <span className="material-symbols-outlined text-primary text-[18px]">{icon}</span>
    {children}
  </h2>
);

const Help: React.FC = () => {
  const [copiedIdx, setCopiedIdx] = useState<number | null>(null);

  const copy = (sql: string, idx: number) => {
    navigator.clipboard.writeText(sql);
    setCopiedIdx(idx);
    setTimeout(() => setCopiedIdx(null), 1500);
  };

  return (
    <div className="p-4 md:p-6 max-w-4xl mx-auto space-y-10">
      <PageHeader
        title="Help & Reference"
        description="Keyboard shortcuts, SQL examples, and format support."
      />

      {/* Quick links */}
      <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
        {[
          { label: 'SQL Editor', path: '/query', icon: 'terminal' },
          { label: 'Stream Flow', path: '/stream-flow', icon: 'waves' },
          { label: 'Lineage Graph', path: '/lineage', icon: 'account_tree' },
          { label: 'Metrics', path: '/metrics', icon: 'monitoring' },
          { label: 'Audit', path: '/audit', icon: 'fact_check' },
          { label: 'Configuration', path: '/config', icon: 'settings' },
        ].map(link => (
          <Link
            key={link.path}
            to={link.path}
            className="flex items-center gap-3 p-3 rounded-xl bg-surface-container ring-1 ring-white/[0.045] card-hover"
          >
            <span className="material-symbols-outlined text-primary text-[20px]">{link.icon}</span>
            <span className="text-[13px] font-medium text-on-surface">{link.label}</span>
          </Link>
        ))}
      </div>

      {/* Keyboard shortcuts */}
      <section>
        <SectionTitle icon="keyboard">Keyboard Shortcuts</SectionTitle>
        <Table>
          <TableHead>
            <tr><Th>Shortcut</Th><Th>Action</Th><Th>Context</Th></tr>
          </TableHead>
          <TableBody>
            {SHORTCUTS.map(s => (
              <TableRow key={s.key}>
                <Td><kbd className="px-2 py-0.5 rounded-md bg-surface-container-high border border-outline-variant font-mono text-[11px] text-on-surface">{s.key}</kbd></Td>
                <Td className="text-on-surface">{s.desc}</Td>
                <Td className="text-on-surface-variant">{s.where}</Td>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </section>

      {/* Message formats */}
      <section>
        <SectionTitle icon="format_shapes">Supported Message Formats</SectionTitle>
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
          {FORMATS.map(f => (
            <Card key={f.name} padding="md">
              <Badge tone={f.tone} className="mb-2">{f.name}</Badge>
              <p className="text-[12px] text-on-surface-variant leading-relaxed">{f.desc}</p>
            </Card>
          ))}
        </div>
      </section>

      {/* Execution Engines */}
      <section>
        <SectionTitle icon="settings_ethernet">Execution Engines</SectionTitle>
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
          {/* Ce panneau affirmait que *tout* SELECT passait par le scan Kafka, « no Flink SQL
              planner involved » : c'était vrai avant la remise en service du planificateur, et
              cela laissait croire qu'un JOIN était hors de portée. */}
          <Card padding="md" className="space-y-2">
            <div className="flex items-center gap-2">
              <Badge tone="primary">Flink planner</Badge>
              <span className="text-[11px] text-on-surface-variant">SELECT · default</span>
            </div>
            <p className="text-[12px] text-on-surface leading-relaxed">
              <code className="text-primary bg-primary/15 px-1 rounded">SELECT</code> runs through the real Flink SQL planner: multi-topic JOINs, sub-queries,
              windows and the full function library. Each result reports the engine that produced it
              (<code className="text-primary bg-primary/15 px-1 rounded">FLINK</code>).
            </p>
            <p className="text-[12px] text-on-surface-variant leading-relaxed">
              A query the planner rejects — an unknown column, a syntax error — comes back with its own message and
              line, rather than being retried elsewhere.
            </p>
          </Card>
          <Card padding="md" className="space-y-2">
            <div className="flex items-center gap-2">
              <Badge tone="warning">Kafka Direct</Badge>
              <span className="text-[11px] text-on-surface-variant">SELECT · fallback</span>
            </div>
            <p className="text-[12px] text-on-surface leading-relaxed">
              If the planner itself fails, the query falls back to a bounded Kafka scan and the result says so
              (<code className="text-primary bg-primary/15 px-1 rounded">KAFKA_DIRECT</code>). It covers projections,
              simple <code className="text-primary bg-primary/15 px-1 rounded">WHERE</code> equality, aggregates
              (<code className="text-primary bg-primary/15 px-1 rounded">COUNT / SUM / AVG / MAX / MIN</code>),
              <code className="text-primary bg-primary/15 px-1 rounded">GROUP BY</code> and windows.
            </p>
            <p className="text-[12px] text-on-surface-variant leading-relaxed">
              Not JOINs or sub-queries. Predicates it cannot apply are listed in the result's warnings — a filtered
              scan is never passed off as one that filtered. Up to 100 000 messages are read for aggregates.
            </p>
          </Card>
          <Card padding="md" className="space-y-2">
            <div className="flex items-center gap-2">
              <Badge tone="secondary">Flink Streaming</Badge>
              <span className="text-[11px] text-on-surface-variant">INSERT INTO · Continuous jobs</span>
            </div>
            <p className="text-[12px] text-on-surface leading-relaxed">
              <code className="text-secondary bg-secondary/15 px-1 rounded">INSERT INTO</code> statements are submitted as asynchronous Flink jobs via <em>Job mode</em>.
              Jobs are tracked in the Dashboard with their real status, Flink job ID, and history.
            </p>
            <p className="text-[12px] text-on-surface-variant leading-relaxed">
              Use <code className="text-secondary bg-secondary/15 px-1 rounded">EXPLAIN</code> and <code className="text-secondary bg-secondary/15 px-1 rounded">CREATE TABLE</code> to register tables in the Flink catalog before submitting a job.
            </p>
          </Card>
        </div>
      </section>

      {/* Tracer un message — rien, dans l'application, ne disait ce qu'une trace sait faire. */}
      <section>
        <SectionTitle icon="route">Tracing a message across topics</SectionTitle>
        <Card padding="md" className="space-y-3">
          <p className="text-[12px] text-on-surface leading-relaxed">
            <Link to="/stream-flow" className="text-primary hover:underline">Stream Flow</Link> follows one key
            through the cluster and draws the chain it travelled. A trace is a <strong>bounded scan</strong>, and the
            page says what it covered: how many topics were read, how many messages, why it stopped, and which topics
            it never reached.
          </p>
          <ul className="space-y-1.5 text-[12px] text-on-surface-variant leading-relaxed">
            <li>
              <strong className="text-on-surface">Where to look.</strong> Leave the path empty to match the record key,
              the payload and every header. Or target it: <code className="text-primary bg-primary/15 px-1 rounded">order.id</code>{' '}
              (dot path), <code className="text-primary bg-primary/15 px-1 rounded">$..id</code> (JSONPath),{' '}
              <code className="text-primary bg-primary/15 px-1 rounded">/order/id</code> (XPath),{' '}
              <code className="text-primary bg-primary/15 px-1 rounded">header:correlation-id</code> (one Kafka header).
            </li>
            <li>
              <strong className="text-on-surface">Exact record key</strong> compares the whole Kafka key and reads only
              the partition the default partitioner would have chosen — much faster, and it is what &ldquo;find record X&rdquo; means.
            </li>
            <li>
              <strong className="text-on-surface">Target topics</strong> take a pasted list or a pattern such as{' '}
              <code className="text-primary bg-primary/15 px-1 rounded">orders.*</code>. Name them when you can: a
              whole-cluster trace reads the most recently active topics first, but naming them is far quicker.
            </li>
            <li>
              <strong className="text-on-surface">If the budget runs out,</strong> continue the scan on the topics that
              were never read — the hops already found are kept and merged into the same chain.
            </li>
            <li>
              <strong className="text-on-surface">Compare two traces</strong> to answer &ldquo;this key arrived, that one
              vanished — where?&rdquo;. Hop latencies are compared side by side, and a topic only one key reached is marked.
            </li>
            <li>
              <strong className="text-on-surface">Every trace is a link.</strong> The criterion round-trips through the
              URL, so a trace pasted into an incident ticket reruns exactly as it was — and exports to CSV or JSON with
              its coverage and warnings.
            </li>
          </ul>
        </Card>
      </section>

      {/* SQL Examples */}
      <section>
        <SectionTitle icon="code">Flink SQL Examples</SectionTitle>
        <div className="space-y-4">
          {SQL_EXAMPLES.map((ex, idx) => (
            <Card key={idx} padding="none" className="overflow-hidden">
              <div className="px-4 py-3 bg-surface-container-high/60 border-b border-outline-variant/60 flex items-center justify-between gap-3 flex-wrap">
                <div className="min-w-0">
                  <span className="font-semibold text-[13px] text-on-surface">{ex.title}</span>
                  <span className="text-on-surface-variant text-[12px] ml-3">{ex.description}</span>
                </div>
                <div className="flex items-center gap-1">
                  <Button variant="ghost" size="sm" icon={copiedIdx === idx ? 'check' : 'content_copy'} onClick={() => copy(ex.sql, idx)}>
                    {copiedIdx === idx ? 'Copied' : 'Copy'}
                  </Button>
                  <Link to={`/query?sql=${encodeURIComponent(ex.sql)}`}>
                    <Button variant="ghost" size="sm" icon="open_in_new">Open in editor</Button>
                  </Link>
                </div>
              </div>
              <pre className="p-4 font-mono text-[12px] text-on-surface leading-relaxed overflow-x-auto bg-surface-container-low">{ex.sql}</pre>
            </Card>
          ))}
        </div>
      </section>
    </div>
  );
};

export default Help;
