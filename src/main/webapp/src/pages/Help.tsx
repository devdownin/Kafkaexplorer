// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React, { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  PageHeader, Card, Button, Badge, Field, Input, Tooltip,
  Table, TableHead, TableBody, TableRow, Th, Td,
} from '../components/ui';
import { copyText } from '../clipboard';
import {
  LESSONS, ENGINE_MATRIX, ERROR_GUIDE, EDITOR_TIPS, WINDOW_ANATOMY, WINDOW_KINDS,
  FORMATS, SHORTCUTS, topicToTable, editorLink, filterLessons, numberSections,
  type Lesson,
} from './helpContent';

/**
 * Page d'aide.
 *
 * Elle listait cinq exemples SQL sans commentaire, dont trois portaient sur des
 * tables inexistantes (`orders_raw`, `users`, `xml_topic`) : de quoi copier,
 * lancer, et récolter « Object not found » au premier geste. Elle est reprise
 * comme un parcours — modèle mental, puis leçons graduées sur les topics que
 * `setup-demo.sh` sème réellement, puis les références qu'on relit (moteurs,
 * fenêtres, erreurs).
 *
 * Le contenu vit dans `help.ts`, où des tests vérifient que les exemples
 * restent exécutables par l'application.
 */

const SECTIONS = [
  { id: 'model', label: 'From topic to table' },
  { id: 'lessons', label: 'Learn by doing' },
  { id: 'engines', label: 'Which engine runs what' },
  { id: 'windows', label: 'Time windows' },
  { id: 'errors', label: 'When it fails' },
  { id: 'editor', label: 'Working in the editor' },
  { id: 'formats', label: 'Payload formats' },
  { id: 'beyond', label: 'Beyond SQL' },
  { id: 'shortcuts', label: 'Shortcuts' },
];

const SectionTitle: React.FC<{ id: string; icon: string; children: React.ReactNode; lead?: React.ReactNode }> =
  ({ id, icon, children, lead }) => (
    <div className="mb-5">
      <h2 id={id} className="scroll-mt-6 text-[15px] font-semibold text-on-surface flex items-center gap-2">
        <span className="material-symbols-outlined text-primary text-[20px]">{icon}</span>
        {children}
      </h2>
      {lead && <p className="text-[12.5px] text-on-surface-variant leading-relaxed mt-2 max-w-3xl">{lead}</p>}
    </div>
  );

const Code: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <code className="font-mono text-[11.5px] text-primary bg-primary/12 px-1 py-0.5 rounded">{children}</code>
);

interface SqlBlockProps {
  sql: string;
  copied: boolean;
  onCopy: () => void;
  /** Absent pour un statement que le bouton Run refuse — un lien « ouvrir » y serait un piège. */
  openHref?: string;
}

const SqlBlock: React.FC<SqlBlockProps> = ({ sql, copied, onCopy, openHref }) => (
  <div className="rounded-lg overflow-hidden ring-1 ring-outline-variant/60 bg-surface-container-low">
    <div className="flex items-center justify-end gap-1 px-2 py-1.5 bg-surface-container-high/50 border-b border-outline-variant/50">
      <Button variant="ghost" size="sm" icon={copied ? 'check' : 'content_copy'} onClick={onCopy}>
        {copied ? 'Copied' : 'Copy'}
      </Button>
      {openHref && (
        <Link to={openHref}>
          <Button variant="ghost" size="sm" icon="open_in_new">Open in editor</Button>
        </Link>
      )}
    </div>
    <pre className="p-4 font-mono text-[12px] text-on-surface leading-relaxed overflow-x-auto">{sql}</pre>
  </div>
);

interface LessonCardProps {
  lesson: Lesson;
  index: number;
  copied: boolean;
  onCopy: () => void;
}

/** `role="article"` : chaque leçon se tient seule, et se cible d'un bloc — au clavier comme en test. */
const LessonCard: React.FC<LessonCardProps> = ({ lesson, index, copied, onCopy }) => (
  <Card padding="md" className="space-y-4" role="article" aria-labelledby={`help-step-${lesson.id}`}>
    <div className="flex items-start gap-3">
      <span
        aria-hidden="true"
        className="shrink-0 w-6 h-6 rounded-full bg-primary/15 text-primary text-[11px] font-semibold flex items-center justify-center mt-0.5"
      >
        {index}
      </span>
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2 flex-wrap">
          <h3 id={`help-step-${lesson.id}`} className="text-[13.5px] font-semibold text-on-surface">
            <span className="sr-only">{`Step ${index}:`}</span>{' '}
            {lesson.title}
          </h3>
          {lesson.engine === 'FLINK' && (
            <Tooltip content="The fallback Kafka reader cannot run this. If the Flink planner is down, the query is refused rather than answered approximately.">
              <Badge tone="primary">Flink engine</Badge>
            </Tooltip>
          )}
        </div>
        <p className="text-[12.5px] text-on-surface-variant leading-relaxed mt-1">{lesson.goal}</p>
      </div>
    </div>

    <SqlBlock
      sql={lesson.sql}
      copied={copied}
      onCopy={onCopy}
      openHref={editorLink(lesson.sql)}
    />

    <div className="grid gap-4 md:grid-cols-[1fr_auto] md:items-start">
      <ul className="space-y-1.5">
        {lesson.reading.map((point) => (
          <li key={point} className="text-[12px] text-on-surface-variant leading-relaxed flex gap-2">
            <span className="material-symbols-outlined text-primary/70 text-[15px] mt-px shrink-0">chevron_right</span>
            <span>{point}</span>
          </li>
        ))}
      </ul>
    </div>

    {lesson.pitfall && (
      <p className="text-[12px] text-on-surface-variant leading-relaxed flex gap-2 rounded-lg bg-warning/[0.07] ring-1 ring-warning/20 p-3">
        <span className="material-symbols-outlined text-warning text-[16px] shrink-0">warning</span>
        <span><strong className="text-on-surface font-medium">Watch out.</strong> {lesson.pitfall}</span>
      </p>
    )}
  </Card>
);

/** ✓ / ✕ / ≈ — l'icône seule ne dit rien à un lecteur d'écran, d'où le libellé. */
const SupportCell: React.FC<{ value: boolean | 'partial' }> = ({ value }) => {
  const map = {
    true: { icon: 'check_circle', tone: 'text-success', label: 'Supported' },
    partial: { icon: 'change_circle', tone: 'text-warning', label: 'Approximated' },
    false: { icon: 'cancel', tone: 'text-on-surface-variant/60', label: 'Not supported' },
  } as const;
  const entry = value === 'partial' ? map.partial : value ? map.true : map.false;
  return (
    <span className="inline-flex items-center gap-1.5">
      <span className={`material-symbols-outlined text-[17px] ${entry.tone}`} aria-hidden="true">{entry.icon}</span>
      <span className="sr-only">{entry.label}</span>
    </span>
  );
};

const QUICK_LINKS = [
  { label: 'SQL Editor', path: '/query', icon: 'terminal' },
  { label: 'Stream Flow', path: '/stream-flow', icon: 'waves' },
  { label: 'Lineage Graph', path: '/lineage', icon: 'account_tree' },
  { label: 'Metrics', path: '/metrics', icon: 'monitoring' },
  { label: 'Audit', path: '/audit', icon: 'fact_check' },
  { label: 'Configuration', path: '/config', icon: 'settings' },
];

const Help: React.FC = () => {
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const [topicSample, setTopicSample] = useState('demo.orders.1.received');

  const sections = useMemo(() => numberSections(filterLessons(LESSONS, query)), [query]);
  const matchCount = useMemo(() => sections.reduce((n, s) => n + s.items.length, 0), [sections]);

  const copy = (id: string, sql: string) => {
    void copyText(sql).then((ok) => {
      if (!ok) return;
      setCopiedId(id);
      window.setTimeout(() => setCopiedId((current) => (current === id ? null : current)), 1500);
    });
  };

  return (
    <div className="p-4 md:p-6 max-w-4xl mx-auto space-y-12">
      <PageHeader
        kicker="Help"
        title="Learning Flink SQL here"
        description="How a Kafka topic becomes a SQL table, what each engine can run, and a graded set of queries you can open in the editor and execute against the demo data."
        actions={
          <Link to="/query">
            <Button variant="primary" icon="terminal">Open the editor</Button>
          </Link>
        }
      />

      {/* Sommaire — la page est longue, et une aide qu'on ne peut pas parcourir n'aide pas. */}
      <nav aria-label="On this page" className="flex flex-wrap gap-2">
        {SECTIONS.map((section) => (
          <a
            key={section.id}
            href={`#${section.id}`}
            className="px-3 py-1.5 rounded-full text-[11.5px] font-medium text-on-surface-variant bg-surface-container ring-1 ring-white/[0.045] hover:text-on-surface hover:ring-primary/30 transition-colors"
          >
            {section.label}
          </a>
        ))}
      </nav>

      {/* ───────────────────────── 1. Modèle mental ───────────────────────── */}
      <section aria-labelledby="model">
        <SectionTitle
          id="model"
          icon="schema"
          lead="Four things happen between the moment you press Run and the moment rows appear. Knowing them explains almost every surprise below."
        >
          From topic to table
        </SectionTitle>

        <div className="space-y-3">
          {[
            {
              title: 'You name a topic in FROM',
              body: (
                <>
                  A topic name is not a legal SQL identifier: dots and dashes become underscores.{' '}
                  <Code>demo.orders.1.received</Code> is read as <Code>demo_orders_1_received</Code>. The Schema
                  Browser and the sidebar always show the name to type.
                </>
              ),
            },
            {
              title: 'The table is created for you',
              body: (
                <>
                  The app samples a few messages, infers a schema, and runs the <Code>CREATE TABLE</Code> itself. This
                  happens once per table, on a plain <Code>SELECT</Code> — and for the <em>first</em> table named after{' '}
                  <Code>FROM</Code> only, which is why a join needs both sides read once beforehand.
                </>
              ),
            },
            {
              title: 'Two columns you never declared',
              body: (
                <>
                  Every generated table carries <Code>event_time</Code> — the Kafka record timestamp, read as metadata —
                  and <Code>proc_time</Code>, the moment the query reads the row. Windows group by the first.
                </>
              ),
            },
            {
              title: 'One of two engines answers',
              body: (
                <>
                  The Flink planner runs the query. If the planner itself fails, a bounded Kafka scan takes over and the
                  result says <Code>KAFKA_DIRECT</Code> instead of <Code>FLINK</Code>. A query <em>you</em> got wrong —
                  a typo, an unknown column — is returned with the planner&rsquo;s own message instead, never retried
                  elsewhere.
                </>
              ),
            },
          ].map((item, idx) => (
            <Card key={item.title} padding="md" className="flex gap-3">
              <span
                aria-hidden="true"
                className="shrink-0 w-6 h-6 rounded-full bg-primary/15 text-primary text-[11px] font-semibold flex items-center justify-center"
              >
                {idx + 1}
              </span>
              <div className="min-w-0">
                <h3 className="text-[13px] font-semibold text-on-surface">{item.title}</h3>
                <p className="text-[12px] text-on-surface-variant leading-relaxed mt-1">{item.body}</p>
              </div>
            </Card>
          ))}
        </div>

        {/* La règle de nommage explique plus d'échecs que toutes les autres : autant la rendre manipulable. */}
        <Card padding="md" className="mt-3">
          <h3 className="text-[13px] font-semibold text-on-surface mb-1">Try the naming rule</h3>
          <p className="text-[12px] text-on-surface-variant leading-relaxed mb-3">
            Paste any topic name to see the identifier to write in your query.
          </p>
          <div className="grid gap-3 sm:grid-cols-2 items-end">
            <Field label="Kafka topic" id="help-topic-name">
              {(props) => (
                <Input
                  {...props}
                  value={topicSample}
                  onChange={(e) => setTopicSample(e.target.value)}
                  placeholder="orders.v2-raw"
                  spellCheck={false}
                />
              )}
            </Field>
            <div>
              <p className="text-[11px] text-on-surface-variant mb-1.5">Write this in your SQL</p>
              <p className="font-mono text-[13px] text-primary bg-primary/10 rounded-lg px-3 h-9 flex items-center overflow-x-auto">
                {topicSample.trim() ? `SELECT * FROM ${topicToTable(topicSample)}` : '—'}
              </p>
            </div>
          </div>
        </Card>
      </section>

      {/* ───────────────────────── 2. Parcours ───────────────────────── */}
      <section aria-labelledby="lessons">
        <SectionTitle
          id="lessons"
          icon="school"
          lead={
            <>
              Each step adds one idea to the one before. The queries run against the topics <Code>setup-demo.sh</Code>{' '}
              seeds — swap in your own table names and they work the same way.
            </>
          }
        >
          Learn by doing
        </SectionTitle>

        <div className="mb-5 max-w-sm">
          <Field label="Filter the steps" id="help-lesson-filter" description="Matches titles, keywords and the SQL itself.">
            {(props) => (
              <Input
                {...props}
                type="search"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="window, join, json…"
              />
            )}
          </Field>
        </div>

        {matchCount === 0 ? (
          <Card padding="md">
            <p className="text-[12.5px] text-on-surface-variant">
              No step matches &ldquo;{query}&rdquo;.{' '}
              <button type="button" className="text-primary hover:underline" onClick={() => setQuery('')}>
                Clear the filter
              </button>{' '}
              to see all {LESSONS.length}.
            </p>
          </Card>
        ) : (
          <div className="space-y-8">
            {sections.map((section) => (
              <div key={section.group}>
                <h3 className="text-[11px] font-semibold uppercase tracking-[0.08em] text-on-surface-variant mb-3">
                  {section.group}
                </h3>
                <div className="space-y-4">
                  {section.items.map(({ lesson, step }) => (
                    <LessonCard
                      key={lesson.id}
                      lesson={lesson}
                      index={step}
                      copied={copiedId === lesson.id}
                      onCopy={() => copy(lesson.id, lesson.sql)}
                    />
                  ))}
                </div>
              </div>
            ))}
          </div>
        )}
      </section>

      {/* ───────────────────────── 3. Moteurs ───────────────────────── */}
      <section aria-labelledby="engines">
        <SectionTitle
          id="engines"
          icon="settings_ethernet"
          lead="A result always reports the engine that produced it. The difference is not academic: the fallback reader answers a narrower SQL, and says what it could not apply."
        >
          Which engine runs what
        </SectionTitle>

        <div className="grid gap-3 md:grid-cols-3 mb-5">
          <Card padding="md" className="space-y-2">
            <Badge tone="primary">FLINK</Badge>
            <p className="text-[12px] text-on-surface leading-relaxed">
              The real Flink SQL planner: joins, sub-queries, <Code>WITH</Code>, exact windows, the full function
              library. This is the default for every <Code>SELECT</Code>, and the only path for{' '}
              <Code>CREATE TABLE</Code> and <Code>EXPLAIN</Code>.
            </p>
          </Card>
          <Card padding="md" className="space-y-2">
            <Badge tone="warning">KAFKA_DIRECT</Badge>
            <p className="text-[12px] text-on-surface leading-relaxed">
              A bounded, in-process Kafka scan used only when the planner itself fails. Projections, equality filters,
              aggregates and tumbling windows — up to 100 000 messages read per aggregate query.
            </p>
          </Card>
        </div>

        <Table>
          <TableHead>
            <tr>
              <Th>Feature</Th>
              <Th>Flink</Th>
              <Th>Direct</Th>
              <Th>What that means</Th>
            </tr>
          </TableHead>
          <TableBody>
            {ENGINE_MATRIX.map((row) => (
              <TableRow key={row.feature}>
                <Td className="text-on-surface font-medium whitespace-nowrap">{row.feature}</Td>
                <Td><SupportCell value={row.flink} /></Td>
                <Td><SupportCell value={row.direct} /></Td>
                <Td className="text-on-surface-variant">{row.note}</Td>
              </TableRow>
            ))}
          </TableBody>
        </Table>

        <Card padding="md" className="mt-4 space-y-2">
          <h3 className="text-[13px] font-semibold text-on-surface">Why a bad query is not retried elsewhere</h3>
          <p className="text-[12px] text-on-surface-variant leading-relaxed">
            The fallback reader matches a table name out of <Code>FROM</Code> and reads it. Handed{' '}
            <Code>SELECT id, FROM orders</Code> or a misspelled column, it would answer with <em>rows</em> — and the
            planner&rsquo;s precise complaint, with its line and column, would be thrown away. So a failure caused by
            the statement is returned as it stands, and only an engine failure falls back.
          </p>
        </Card>
      </section>

      {/* ───────────────────────── 4. Fenêtres ───────────────────────── */}
      <section aria-labelledby="windows">
        <SectionTitle
          id="windows"
          icon="schedule"
          lead="A window is what turns an endless stream into rows you can chart. The syntax is the part people get wrong, so here it is one fragment at a time."
        >
          Time windows
        </SectionTitle>

        <Card padding="none" className="overflow-hidden mb-4">
          <pre className="p-4 font-mono text-[12px] text-on-surface leading-relaxed overflow-x-auto bg-surface-container-low">
{`SELECT window_start, window_end, COUNT(*) AS events
FROM TABLE(
  TUMBLE(TABLE demo_iot_sensors, DESCRIPTOR(event_time), INTERVAL '5' MINUTES)
)
GROUP BY window_start, window_end`}
          </pre>
          <div className="divide-y divide-outline-variant/40">
            {WINDOW_ANATOMY.map((part) => (
              <div key={part.fragment} className="px-4 py-2.5 flex flex-col sm:flex-row sm:items-baseline gap-1 sm:gap-4">
                <code className="font-mono text-[11.5px] text-primary shrink-0 sm:w-[280px]">{part.fragment}</code>
                <span className="text-[12px] text-on-surface-variant leading-relaxed">{part.meaning}</span>
              </div>
            ))}
          </div>
        </Card>

        <div className="grid gap-3 sm:grid-cols-2">
          {WINDOW_KINDS.map((kind) => (
            <Card key={kind.name} padding="md" className="space-y-2">
              <Badge tone="primary">{kind.name}</Badge>
              {/* Wrap plutôt que défiler : une signature d'une ligne coupée en plein milieu ne
                  s'enseigne pas, et personne ne pense à faire défiler une carte. */}
              <pre className="font-mono text-[11px] text-on-surface-variant leading-relaxed whitespace-pre-wrap break-words">{kind.signature}</pre>
              <p className="text-[12px] text-on-surface leading-relaxed">{kind.use}</p>
              {kind.caveat && (
                <p className="text-[11.5px] text-warning/90 leading-relaxed">{kind.caveat}</p>
              )}
            </Card>
          ))}
        </div>

        <p className="text-[12px] text-on-surface-variant leading-relaxed mt-3">
          The Window Assistant in the editor writes any of these for you: it reads the time column from the loaded
          schema, states the caveat that applies, and inserts at the cursor rather than replacing your query.
        </p>
      </section>

      {/* ───────────────────────── 5. Erreurs ───────────────────────── */}
      <section aria-labelledby="errors">
        <SectionTitle
          id="errors"
          icon="report"
          lead="Every failure lands in one panel: a readable title, a hint, and the engine's raw message one click away — with a marker on the offending line when the engine cites one."
        >
          When it fails
        </SectionTitle>

        <div className="space-y-3">
          {ERROR_GUIDE.map((entry) => (
            <Card key={entry.message} padding="md">
              <p className="font-mono text-[11.5px] text-error mb-2 break-words">{entry.message}</p>
              <p className="text-[12px] text-on-surface leading-relaxed">{entry.meaning}</p>
              <p className="text-[12px] text-on-surface-variant leading-relaxed mt-1.5 flex gap-2">
                <span className="material-symbols-outlined text-success text-[15px] mt-px shrink-0">lightbulb</span>
                <span>{entry.fix}</span>
              </p>
            </Card>
          ))}
        </div>
      </section>

      {/* ───────────────────────── 6. Éditeur ───────────────────────── */}
      <section aria-labelledby="editor">
        <SectionTitle
          id="editor"
          icon="terminal"
          lead="What the editor does that you would not guess by looking at it."
        >
          Working in the editor
        </SectionTitle>
        <div className="grid gap-3 sm:grid-cols-2">
          {EDITOR_TIPS.map((tip) => (
            <Card key={tip.title} padding="md">
              <h3 className="text-[12.5px] font-semibold text-on-surface mb-1">{tip.title}</h3>
              <p className="text-[12px] text-on-surface-variant leading-relaxed">{tip.body}</p>
            </Card>
          ))}
        </div>
      </section>

      {/* ───────────────────────── 7. Formats ───────────────────────── */}
      <section aria-labelledby="formats">
        <SectionTitle
          id="formats"
          icon="format_shapes"
          lead="The payload format decides what columns you get — and, for XML, whether you use SQL columns at all."
        >
          Payload formats
        </SectionTitle>
        <div className="grid gap-3 sm:grid-cols-2">
          {FORMATS.map((format) => (
            <Card key={format.name} padding="md" className="space-y-2">
              <Badge tone={format.tone}>{format.name}</Badge>
              <p className="text-[12px] text-on-surface leading-relaxed">
                <strong className="font-medium">Detected by:</strong> {format.detection}
              </p>
              <p className="text-[12px] text-on-surface-variant leading-relaxed">{format.columns}</p>
            </Card>
          ))}
        </div>
      </section>

      {/* ───────────────────────── 8. Au-delà du SQL ───────────────────────── */}
      <section aria-labelledby="beyond">
        <SectionTitle
          id="beyond"
          icon="route"
          lead="Some questions are not queries. Two screens answer what SQL here cannot."
        >
          Beyond SQL
        </SectionTitle>

        <div className="space-y-3">
          <Card padding="md" className="space-y-3">
            <h3 className="text-[13px] font-semibold text-on-surface flex items-center gap-2">
              <span className="material-symbols-outlined text-primary text-[18px]">waves</span>
              &ldquo;Where did this message go?&rdquo; — <Link to="/stream-flow" className="text-primary hover:underline">Stream Flow</Link>
            </h3>
            <p className="text-[12px] text-on-surface leading-relaxed">
              Follows one key through the cluster and draws the chain it travelled. A trace is a <strong>bounded
              scan</strong>, and the page says what it covered: topics read, messages scanned, why it stopped, and which
              topics it never reached.
            </p>
            <ul className="space-y-1.5 text-[12px] text-on-surface-variant leading-relaxed">
              <li>
                <strong className="text-on-surface">Where to look.</strong> Leave the path empty to match the record key,
                the payload and every header. Or target it: <Code>order.id</Code> (dot path), <Code>$..id</Code>{' '}
                (JSONPath), <Code>/order/id</Code> (XPath), <Code>header:correlation-id</Code> (one Kafka header).
              </li>
              <li>
                <strong className="text-on-surface">Exact record key</strong> compares the whole Kafka key and reads only
                the partition the default partitioner would have chosen — much faster, and it is what &ldquo;find record
                X&rdquo; means.
              </li>
              <li>
                <strong className="text-on-surface">If the budget runs out,</strong> continue the scan on the topics that
                were never read — the hops already found are kept and merged into the same chain.
              </li>
              <li>
                <strong className="text-on-surface">Compare two traces</strong> to answer &ldquo;this key arrived, that
                one vanished — where?&rdquo;. Hop latencies sit side by side, and a topic only one key reached is marked.
              </li>
            </ul>
          </Card>

          <Card padding="md" className="space-y-3">
            <h3 className="text-[13px] font-semibold text-on-surface flex items-center gap-2">
              <span className="material-symbols-outlined text-primary text-[18px]">search</span>
              &ldquo;Is this value anywhere in the topic?&rdquo; — Topic Explorer
            </h3>
            <p className="text-[12px] text-on-surface leading-relaxed">
              A <Code>WHERE</Code> clause reads a bounded sample; the topic search walks the partitions server-side with
              its own budgets, and reports what it covered. It searches text, regex, a field path, a Kafka header or the
              record key — and headers are where correlation ids usually live, which no SQL column will show you.
            </p>
            <p className="text-[12px] text-on-surface-variant leading-relaxed">
              Turn a metric into an alert on <Link to="/metrics" className="text-primary hover:underline">Metrics</Link>{' '}
              (a query whose result column is named <Code>metric_value</Code> becomes a Prometheus series), and check a
              whole cluster at once from <Link to="/audit" className="text-primary hover:underline">Audit</Link>.
            </p>
          </Card>
        </div>
      </section>

      {/* ───────────────────────── 9. Raccourcis ───────────────────────── */}
      <section aria-labelledby="shortcuts">
        <SectionTitle id="shortcuts" icon="keyboard">Keyboard shortcuts</SectionTitle>
        <Table>
          <TableHead>
            <tr><Th>Shortcut</Th><Th>Action</Th><Th>Context</Th></tr>
          </TableHead>
          <TableBody>
            {SHORTCUTS.map((shortcut) => (
              <TableRow key={`${shortcut.key}-${shortcut.where}`}>
                <Td>
                  <kbd className="px-2 py-0.5 rounded-md bg-surface-container-high border border-outline-variant font-mono text-[11px] text-on-surface">
                    {shortcut.key}
                  </kbd>
                </Td>
                <Td className="text-on-surface">{shortcut.desc}</Td>
                <Td className="text-on-surface-variant">{shortcut.where}</Td>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </section>

      {/* Raccourcis de navigation — en pied de page : on y va après avoir lu, pas avant. */}
      <section aria-label="Go to a page">
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
          {QUICK_LINKS.map((link) => (
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
      </section>
    </div>
  );
};

export default Help;
