// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import axios from 'axios';
import { EmptyState, ErrorPanel, Input, PageHeader, Select, TableSkeleton } from '../components/ui';
import { buildTopicHierarchy, topicSeparator, type Separator, type TopicBranch } from './topicHierarchy';

const choices: { separator: Separator; label: string }[] = [
  { separator: '.', label: 'Dot (.)' },
  { separator: '-', label: 'Hyphen (-)' },
  { separator: '_', label: 'Underscore (_)' },
];

function Branch({ branch, depth = 0, expandAll }: { branch: TopicBranch; depth?: number; expandAll: boolean }) {
  const [expanded, setExpanded] = useState(depth === 0);
  const hasChildren = branch.children.length > 0;
  const open = expandAll || expanded;
  return (
    <li className="min-w-0" role="treeitem" aria-expanded={hasChildren ? open : undefined}>
      <div className="group flex items-center gap-2 min-h-10 rounded-lg px-2 hover:bg-surface-container-high/70" style={{ paddingLeft: `${depth * 18 + 8}px` }}>
        {hasChildren ? (
          <button type="button" onClick={() => setExpanded(value => !value)} disabled={expandAll} aria-label={`${open ? 'Collapse' : 'Expand'} ${branch.path}`} className="inline-flex items-center justify-center size-7 rounded hover:bg-surface-container-high text-on-surface-variant disabled:opacity-50">
            <span aria-hidden="true" className="material-symbols-outlined text-[18px]">{open ? 'expand_more' : 'chevron_right'}</span>
          </button>
        ) : <span className="w-7 shrink-0" />}
        <span aria-hidden="true" className="material-symbols-outlined text-[18px] text-primary/80">{hasChildren ? 'folder' : 'topic'}</span>
        {branch.topic ? (
          <Link to={`/topic/${encodeURIComponent(branch.topic)}`} title={branch.topic} className="min-w-0 truncate font-mono text-[13px] text-primary hover:underline focus-visible:underline">{branch.label}</Link>
        ) : <span className="min-w-0 truncate font-mono text-[13px] text-on-surface" title={branch.path}>{branch.label}</span>}
        {hasChildren && <span className="ml-auto shrink-0 text-[11px] tabular-nums text-on-surface-variant" aria-label={`${branch.count} topics`}>{branch.count}</span>}
        {branch.topic && hasChildren && <span className="text-[11px] text-on-surface-variant">topic</span>}
      </div>
      {hasChildren && open && (
        <ul role="group">
          {branch.children.map(child => <Branch key={child.path} branch={child} depth={depth + 1} expandAll={expandAll} />)}
        </ul>
      )}
    </li>
  );
}

export default function TopicHierarchy() {
  const [topics, setTopics] = useState<string[] | null>(null);
  const [error, setError] = useState(false);
  const [separator, setSeparator] = useState<Separator>('.');
  const [query, setQuery] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    axios.get<{ topics: string[] }>('/api/dashboard', { signal: controller.signal })
      .then(response => setTopics(response.data.topics ?? []))
      .catch(() => { if (!controller.signal.aborted) setError(true); });
    return () => controller.abort();
  }, []);

  const counts = useMemo(() => Object.fromEntries(choices.map(choice => [choice.separator,
    (topics ?? []).filter(topic => topicSeparator(topic) === choice.separator).length,
  ])) as Record<Separator, number>, [topics]);
  const excluded = (topics ?? []).length - Object.values(counts).reduce((sum, count) => sum + count, 0);
  const matching = useMemo(() => (topics ?? []).filter(topic => topic.toLowerCase().includes(query.trim().toLowerCase())), [topics, query]);
  const tree = useMemo(() => buildTopicHierarchy(matching, separator), [matching, separator]);

  return (
    <div className="space-y-6">
      <PageHeader title="Topic hierarchy" description="Explore topics grouped by the separator used in their names." />
      {error ? <ErrorPanel error={{ title: 'Could not load topics', hint: 'The dashboard topic list is unavailable.', raw: '' }} /> : topics === null ? <TableSkeleton /> : (
        <section className="rounded-xl border border-outline-variant/60 bg-surface-container-low p-4 sm:p-6 space-y-4">
          <div className="flex flex-wrap items-end gap-3 justify-between">
            <div>
              <label htmlFor="topic-hierarchy-separator" className="block mb-1 text-[12px] font-medium text-on-surface-variant">Naming separator</label>
              <Select id="topic-hierarchy-separator" value={separator} onChange={event => setSeparator(event.target.value as Separator)} className="h-9 w-auto">
                {choices.map(choice => <option key={choice.separator} value={choice.separator}>{choice.label} · {counts[choice.separator]} topics</option>)}
              </Select>
            </div>
            <Input aria-label="Filter hierarchy" placeholder="Filter topic names…" value={query} onChange={event => setQuery(event.target.value)} className="h-9 w-full sm:w-64" />
          </div>
          <p className="text-[12px] text-on-surface-variant">
            {counts[separator]} topics use this separator. {excluded} names have no valid hierarchy (no separator, mixed separators, or empty levels).
            {query && ' Matching paths are expanded to show results.'}
          </p>
          {tree.length ? (
            <ul role="tree" aria-label="Topic hierarchy" className="max-h-[70vh] overflow-auto rounded-lg border border-outline-variant/60 bg-surface-container divide-y divide-outline-variant/30">
              {tree.map(branch => <Branch key={branch.path} branch={branch} expandAll={Boolean(query.trim())} />)}
            </ul>
          ) : <EmptyState icon="account_tree" title="No hierarchical topics" description={query ? 'No matching topics use this separator.' : 'Choose another separator or use names with at least two nonempty levels.'} />}
          <Link to="/" className="inline-block text-[12px] text-primary hover:underline">Back to dashboard topics</Link>
        </section>
      )}
    </div>
  );
}
