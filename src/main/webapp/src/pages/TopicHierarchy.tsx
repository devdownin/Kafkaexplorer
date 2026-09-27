// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useEffect, useLayoutEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import axios from 'axios';
import { EmptyState, ErrorPanel, Input, PageHeader, Select, TableSkeleton, useVirtualRows } from '../components/ui';
import { isDeadLetterTopic } from './topicKinds';
import { buildTopicHierarchy, exclusionReason, topicSeparator, visibleBranches, type Separator, type VisibleBranch } from './topicHierarchy';

const STORAGE_KEY = 'topicHierarchy.view';
const ROW_HEIGHT = 40;
const EXCLUDED_PAGE_SIZE = 100;
const choices: { separator: Separator; label: string }[] = [
  { separator: '.', label: 'Dot (.)' }, { separator: '-', label: 'Hyphen (-)' }, { separator: '_', label: 'Underscore (_)' },
];
interface SavedView { separator: Separator; query: string; expanded: string[]; collapsedRoots: string[] }

function readView(): SavedView {
  const fallback: SavedView = { separator: '.', query: '', expanded: [], collapsedRoots: [] };
  try {
    const value = JSON.parse(sessionStorage.getItem(STORAGE_KEY) ?? 'null');
    if (!value || !choices.some(choice => choice.separator === value.separator)) return fallback;
    return {
      separator: value.separator,
      query: typeof value.query === 'string' ? value.query : '',
      expanded: Array.isArray(value.expanded) ? value.expanded.filter((path: unknown) => typeof path === 'string') : [],
      collapsedRoots: Array.isArray(value.collapsedRoots) ? value.collapsedRoots.filter((path: unknown) => typeof path === 'string') : [],
    };
  } catch { return fallback; }
}

export default function TopicHierarchy() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [saved] = useState(readView);
  const [topics, setTopics] = useState<string[] | null>(null);
  const [topicSizes, setTopicSizes] = useState<Record<string, number>>({});
  const [error, setError] = useState(false);
  const [separator, setSeparator] = useState<Separator>(saved.separator);
  const [query, setQuery] = useState(() => params.get('q') ?? saved.query);
  const [hideEmpty, setHideEmpty] = useState(() => params.get('empty') === 'true');
  const [hideDlt, setHideDlt] = useState(() => params.get('dlt') === 'true');
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set(saved.expanded));
  const [collapsedRoots, setCollapsedRoots] = useState<Set<string>>(() => new Set(saved.collapsedRoots));
  const [activePath, setActivePath] = useState<string | null>(null);
  const [excludedLimit, setExcludedLimit] = useState(EXCLUDED_PAGE_SIZE);
  const [showExcluded, setShowExcluded] = useState(false);
  const scrollRef = useRef<HTMLUListElement>(null);
  const pendingFocus = useRef(false);
  const listUrl = `/?${new URLSearchParams({ q: query, empty: String(hideEmpty), dlt: String(hideDlt) })}#topics`;

  useEffect(() => {
    const controller = new AbortController();
    axios.get<{ topics: string[]; topicSizes?: Record<string, number> }>('/api/dashboard', { signal: controller.signal })
      .then(response => { setTopics(response.data.topics ?? []); setTopicSizes(response.data.topicSizes ?? {}); })
      .catch(() => { if (!controller.signal.aborted) setError(true); });
    return () => controller.abort();
  }, []);
  useEffect(() => {
    try { sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ separator, query, expanded: [...expanded], collapsedRoots: [...collapsedRoots] })); }
    catch { /* Storage may be unavailable; navigation still works. */ }
  }, [separator, query, expanded, collapsedRoots]);

  const matching = useMemo(() => (topics ?? []).filter(topic => topic.toLowerCase().includes(query.trim().toLowerCase()))
    .filter(topic => !hideDlt || !isDeadLetterTopic(topic))
    .filter(topic => !hideEmpty || (topicSizes[topic] ?? 0) > 0), [topics, topicSizes, query, hideDlt, hideEmpty]);
  const counts = useMemo(() => Object.fromEntries(choices.map(choice => [choice.separator,
    matching.filter(topic => topicSeparator(topic) === choice.separator).length,
  ])) as Record<Separator, number>, [matching]);
  const excluded = useMemo(() => matching.map(name => ({ name, reason: exclusionReason(name) ?? `Uses ${topicSeparator(name)} separator` }))
    .filter(entry => topicSeparator(entry.name) !== separator)
    .sort((a, b) => a.name.localeCompare(b.name)), [matching, separator]);
  const tree = useMemo(() => buildTopicHierarchy(matching, separator), [matching, separator]);
  const openPaths = useMemo(() => new Set([...expanded, ...tree.filter(root => !collapsedRoots.has(root.path)).map(root => root.path)]), [tree, expanded, collapsedRoots]);
  const expandAll = Boolean(query.trim());
  const rows = useMemo(() => visibleBranches(tree, openPaths, expandAll), [tree, openPaths, expandAll]);
  const window = useVirtualRows(scrollRef, rows.length, ROW_HEIGHT, 10);
  const selectedIndex = rows.findIndex(row => row.branch.path === activePath);
  const tabIndex = selectedIndex < window.start || selectedIndex >= window.end ? window.start : selectedIndex;

  useLayoutEffect(() => {
    if (!pendingFocus.current || selectedIndex < 0) return;
    const list = scrollRef.current;
    if (!list) return;
    const top = selectedIndex * ROW_HEIGHT;
    if (top < list.scrollTop) list.scrollTop = top;
    else if (top + ROW_HEIGHT > list.scrollTop + list.clientHeight) list.scrollTop = top + ROW_HEIGHT - list.clientHeight;
    // Keep the window in sync even when focus jumps beyond the mounted rows.
    list.dispatchEvent(new Event('scroll'));
    requestAnimationFrame(() => {
      const item = [...list.querySelectorAll<HTMLElement>('[role="treeitem"]')].find(element => element.dataset.path === activePath);
      if (item) { item.focus(); pendingFocus.current = false; }
    });
  }, [activePath, selectedIndex, window.start, window.end]);

  const toggle = (row: VisibleBranch) => {
    if (!row.branch.children.length || expandAll) return;
    const path = row.branch.path;
    const setter = row.depth === 1 ? setCollapsedRoots : setExpanded;
    setter(current => {
      const next = new Set(current);
      if (next.has(path)) next.delete(path); else next.add(path);
      return next;
    });
  };
  const moveTo = (path: string) => { pendingFocus.current = true; setActivePath(path); };
  const onTreeKeyDown = (event: KeyboardEvent<HTMLUListElement>) => {
    const item = (event.target as HTMLElement).closest<HTMLElement>('[role="treeitem"]');
    if (!item || !event.currentTarget.contains(item)) return;
    const index = rows.findIndex(row => row.branch.path === item.dataset.path);
    if (index < 0) return;
    const row = rows[index];
    const open = expandAll || openPaths.has(row.branch.path);
    let next: string | null = null;
    switch (event.key) {
      case 'ArrowDown': next = rows[Math.min(index + 1, rows.length - 1)].branch.path; break;
      case 'ArrowUp': next = rows[Math.max(index - 1, 0)].branch.path; break;
      case 'Home': next = rows[0].branch.path; break;
      case 'End': next = rows[rows.length - 1].branch.path; break;
      case 'ArrowRight':
        if (row.branch.children.length && !open) toggle(row);
        else if (row.branch.children.length) next = rows[index + 1]?.branch.path ?? null;
        break;
      case 'ArrowLeft':
        if (row.branch.children.length && open && !expandAll) toggle(row);
        else next = row.parent;
        break;
      case ' ':
        if (row.branch.children.length) toggle(row);
        else if (row.branch.topic) navigate(`/topic/${encodeURIComponent(row.branch.topic)}`);
        break;
      case 'Enter':
        if (row.branch.topic) navigate(`/topic/${encodeURIComponent(row.branch.topic)}`);
        else toggle(row);
        break;
      default: return;
    }
    event.preventDefault();
    if (next) moveTo(next);
  };

  return (
    <div className="space-y-6">
      <PageHeader title="Topic hierarchy" description="Explore topics grouped by the separator used in their names." />
      {error ? <ErrorPanel error={{ title: 'Could not load topics', hint: 'The dashboard topic list is unavailable.', raw: '' }} /> : topics === null ? <TableSkeleton /> : (
        <section className="rounded-xl border border-outline-variant/60 bg-surface-container-low p-4 sm:p-6 space-y-4">
          <div role="group" aria-label="Topic view" className="inline-flex rounded-lg border border-outline-variant/60 text-[12px] font-medium">
            <Link to={listUrl} className="inline-flex items-center px-3 h-9 rounded-l-lg text-primary hover:bg-primary/10">List</Link>
            <span aria-current="page" className="inline-flex items-center gap-1 px-3 h-9 rounded-r-lg bg-primary/10 text-primary"><span aria-hidden="true" className="material-symbols-outlined text-[18px]">account_tree</span>Tree</span>
          </div>
          <div className="flex flex-wrap items-end gap-3 justify-between">
            <div>
              <label htmlFor="topic-hierarchy-separator" className="block mb-1 text-[12px] font-medium text-on-surface-variant">Naming separator</label>
              <Select id="topic-hierarchy-separator" value={separator} onChange={event => { setSeparator(event.target.value as Separator); setActivePath(null); if (scrollRef.current) scrollRef.current.scrollTop = 0; }} className="h-9 w-auto">
                {choices.map(choice => <option key={choice.separator} value={choice.separator}>{choice.label} · {counts[choice.separator]} topics</option>)}
              </Select>
            </div>
            <Input aria-label="Filter hierarchy" placeholder="Filter topic names…" value={query} onChange={event => { setQuery(event.target.value); setActivePath(null); if (scrollRef.current) scrollRef.current.scrollTop = 0; }} className="h-9 w-full sm:w-64" />
            <label className="text-[12px]"><input type="checkbox" checked={hideDlt} onChange={event => setHideDlt(event.target.checked)} /> Hide dead letter</label>
            <label className="text-[12px]"><input type="checkbox" checked={hideEmpty} onChange={event => setHideEmpty(event.target.checked)} /> Hide empty</label>
          </div>
          <p className="text-[12px] text-on-surface-variant">
            {counts[separator]} topics use this separator. {excluded.length} other topics remain accessible.
            {query && ' Matching paths are expanded to show results.'}
          </p>
          {!counts[separator] && <p role="status" className="text-[12px] text-on-surface-variant">No hierarchy for {separator}: names need two nonempty levels separated only by {separator}. For example, orders{separator}eu{separator}created. Choose another separator or browse Other topics below.</p>}
          {excluded.length > 0 && (
            <div>
              <button type="button" aria-expanded={showExcluded} onClick={() => setShowExcluded(value => !value)} className="text-[12px] font-medium text-primary hover:underline">
                {showExcluded ? 'Hide' : 'Show'} Other topics ({excluded.length})
              </button>
              {showExcluded && (
                <div className="mt-2 max-h-64 overflow-auto rounded-lg border border-outline-variant/60 bg-surface-container" aria-label="Other topics">
                  <ul className="divide-y divide-outline-variant/30">
                    {excluded.slice(0, excludedLimit).map(({ name, reason }) => (
                      <li key={name} className="flex flex-wrap items-center justify-between gap-2 px-3 py-2 text-[12px]">
                        <Link to={`/topic/${encodeURIComponent(name)}`} className="font-mono text-primary hover:underline break-all">{name}</Link>
                        <span className="text-on-surface-variant">{reason}</span>
                      </li>
                    ))}
                  </ul>
                  {excludedLimit < excluded.length && <button type="button" onClick={() => setExcludedLimit(limit => limit + EXCLUDED_PAGE_SIZE)} className="px-3 py-2 text-[12px] text-primary hover:underline">Show next {Math.min(EXCLUDED_PAGE_SIZE, excluded.length - excludedLimit)} names</button>}
                </div>
              )}
            </div>
          )}
          {rows.length ? (
            <ul ref={scrollRef} role="tree" aria-label="Topic hierarchy" onKeyDown={onTreeKeyDown} className="h-[min(70vh,32rem)] overflow-auto rounded-lg border border-outline-variant/60 bg-surface-container">
              <li role="none" aria-hidden="true" style={{ height: window.padTop }} />
              {rows.slice(window.start, window.end).map((row, index) => {
                const { branch, depth } = row;
                const open = expandAll || openPaths.has(branch.path);
                const hasChildren = branch.children.length > 0;
                return (
                  <li key={branch.path} data-path={branch.path} role="treeitem" aria-level={depth} aria-posinset={row.position} aria-setsize={row.siblings} aria-expanded={hasChildren ? open : undefined} aria-label={`${branch.path}${hasChildren ? `, ${branch.count} topics` : ''}`} tabIndex={window.start + index === tabIndex ? 0 : -1} onFocus={() => setActivePath(branch.path)} className="group flex items-center gap-2 h-10 min-w-0 rounded-lg px-2 hover:bg-surface-container-high/70 focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary" style={{ paddingLeft: `${(depth - 1) * 18 + 8}px` }}>
                    {hasChildren ? (
                      <button type="button" tabIndex={-1} disabled={expandAll} onClick={() => toggle(row)} aria-label={`${open ? 'Collapse' : 'Expand'} ${branch.path}`} className="inline-flex items-center justify-center size-7 shrink-0 rounded hover:bg-surface-container-high text-on-surface-variant disabled:opacity-50">
                        <span aria-hidden="true" className="material-symbols-outlined text-[18px]">{open ? 'expand_more' : 'chevron_right'}</span>
                      </button>
                    ) : <span className="w-7 shrink-0" />}
                    <span aria-hidden="true" className="material-symbols-outlined text-[18px] text-primary/80">{hasChildren ? 'folder' : 'topic'}</span>
                    {branch.topic ? <Link tabIndex={-1} to={`/topic/${encodeURIComponent(branch.topic)}`} title={branch.topic} className="min-w-0 truncate font-mono text-[13px] text-primary hover:underline">{branch.label}</Link>
                      : <span className="min-w-0 truncate font-mono text-[13px] text-on-surface" title={branch.path}>{branch.label}</span>}
                    {hasChildren && <span className="ml-auto shrink-0 text-[11px] tabular-nums text-on-surface-variant">{branch.count}</span>}
                    {branch.topic && hasChildren && <span className="text-[11px] text-on-surface-variant">topic</span>}
                  </li>
                );
              })}
              <li role="none" aria-hidden="true" style={{ height: window.padBottom }} />
            </ul>
          ) : <EmptyState icon="account_tree" title="No hierarchical topics" description={query ? 'No matching topics use this separator.' : 'Choose another separator or use names with at least two nonempty levels.'} />}
          <Link to={listUrl} className="inline-block text-[12px] text-primary hover:underline">Back to dashboard topics</Link>
        </section>
      )}
    </div>
  );
}
