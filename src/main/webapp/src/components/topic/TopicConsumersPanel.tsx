import { useCallback, useEffect, useMemo, useState } from 'react';
import type { FC } from 'react';
import axios from 'axios';
import {
  Badge, Button, EmptyState, ErrorPanel, Input, Table, TableSkeleton, Tooltip, HelpTip,
} from '../ui';
import { describeApiError, type QueryErrorInfo } from '../../pages/queryError';
import {
  HEALTH_HELP, HEALTH_LABEL, HEALTH_TONE,
  type ConsumerGroupLag, type GroupSort, type TopicConsumers,
  describeScope, describeSummary, filterGroups, formatCount, healthOf, progressOf, sortGroups,
} from './topicConsumers';

export interface TopicConsumersPanelProps {
  topic: string;
}

/** Le serveur met la réponse en cache 30 s : recharger plus vite ne renverrait que la même. */
const CACHE_SECONDS = 30;

/**
 * Qui lit ce topic, et où en est-il.
 *
 * La page répond à une question opérationnelle précise — « pourquoi ça s'accumule ? » — donc elle
 * ne se contente pas d'un total : elle sépare un groupe simplement en retard d'un groupe sans
 * membre assigné (rien ne résorbera son retard) et d'un groupe qui ne lit qu'une partie des
 * partitions (le total ne compte pas ce qu'il ignore).
 */
const TopicConsumersPanel: FC<TopicConsumersPanelProps> = ({ topic }) => {
  const [data, setData] = useState<TopicConsumers | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<QueryErrorInfo | null>(null);
  const [filter, setFilter] = useState('');
  const [sort, setSort] = useState<GroupSort>('lag');
  const [desc, setDesc] = useState(true);
  const [expanded, setExpanded] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await axios.get<TopicConsumers>(`/api/topic/${encodeURIComponent(topic)}/consumers`);
      setData(res.data);
    } catch (err) {
      setError(describeApiError(err, 'Could not read the consumer groups.'));
    } finally {
      setLoading(false);
    }
  }, [topic]);

  useEffect(() => {
    // Chargement au montage : il finit par poser un état par construction ; seule une
    // bibliothèque de données ou Suspense l'en dispenserait.
    // eslint-disable-next-line react-hooks/set-state-in-effect -- effet de chargement, cf. ci-dessus
    load();
  }, [load]);

  const shown = useMemo(
    () => sortGroups(filterGroups(data?.groups ?? [], filter), sort, desc),
    [data, filter, sort, desc],
  );
  const summary = useMemo(() => describeSummary(data?.groups ?? []), [data]);

  const toggleSort = (next: GroupSort) => {
    if (next === sort) setDesc(d => !d);
    else { setSort(next); setDesc(next === 'lag'); }
  };

  if (loading && !data) return <TableSkeleton rows={4} />;

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <p className="text-[12px] text-on-surface-variant flex-1 min-w-[16rem]">
          {describeScope(data)}
        </p>
        <Input
          value={filter}
          onChange={e => setFilter(e.target.value)}
          placeholder="Filter groups…"
          className="w-48"
          aria-label="Filter consumer groups"
        />
        <Button variant="outline" size="sm" icon="refresh" onClick={load} disabled={loading}>
          Refresh
        </Button>
        <HelpTip
          label="How consumer lag is measured"
          content={`Positions are cached for ${CACHE_SECONDS} seconds, so two refreshes in a row `
            + 'return the same numbers. Lag is the log end offset minus the committed offset — it '
            + 'moves on every produce, not only on every consume.'}
        />
      </div>

      {error && <ErrorPanel error={error} onRetry={load} />}

      {data?.warnings.map(warning => (
        <div key={warning} className="flex items-start gap-2 rounded-lg border border-warning/30 bg-warning/5 p-3">
          <span aria-hidden="true" className="material-symbols-outlined text-warning text-base">warning</span>
          <p className="text-[12px] text-on-surface-variant">{warning}</p>
        </div>
      ))}

      {summary && (
        <p className="text-[13px] text-on-surface">{summary}</p>
      )}

      {!error && shown.length === 0 ? (
        <EmptyState
          icon="groups"
          title={filter ? 'No group matches that filter' : 'No consumer group reads this topic'}
          description={
            filter
              ? 'Clear the filter to see every group that holds a committed offset here.'
              : 'A group appears once it commits an offset on one of this topic\'s partitions. '
                + 'A producer-only topic, or one read by a consumer that never commits, legitimately shows nothing.'
          }
        />
      ) : (
        <Table rowCount={shown.length} className="text-sm">
          <thead>
            <tr className="bg-surface-container-high/60 border-b border-outline-variant/60 text-[10px] uppercase tracking-widest text-on-surface-variant">
              <SortHeader label="Group" column="groupId" sort={sort} desc={desc} onSort={toggleSort} />
              <SortHeader label="State" column="state" sort={sort} desc={desc} onSort={toggleSort} />
              <th className="text-right px-5 py-3">Members</th>
              <SortHeader label="Lag" column="lag" sort={sort} desc={desc} onSort={toggleSort} align="right" />
              <th className="text-left px-5 py-3">Status</th>
              <th className="text-right px-5 py-3">Partitions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-outline-variant/40">
            {shown.flatMap(group => {
              const health = healthOf(group);
              const open = expanded === group.groupId;
              const rows = [(
                <tr
                  key={group.groupId}
                  className="hover:bg-surface-container-high/40 transition-colors"
                >
                  <td className="px-5 py-3">
                    <button
                      type="button"
                      aria-expanded={open}
                      onClick={() => setExpanded(open ? null : group.groupId)}
                      className="flex items-center gap-1.5 text-left"
                    >
                      <span
                        aria-hidden="true"
                        className={`material-symbols-outlined text-[16px] text-on-surface-variant transition-transform ${open ? 'rotate-90' : ''}`}
                      >
                        chevron_right
                      </span>
                      <span className="font-mono text-on-surface">{group.groupId}</span>
                    </button>
                  </td>
                  <td className="px-5 py-3 text-[11px] text-on-surface-variant">
                    {group.state}
                    {group.type !== 'CLASSIC' && ` · ${group.type}`}
                  </td>
                  <td className="px-5 py-3 text-right">
                    <span
                      className="font-mono text-on-surface-variant"
                      title={`${group.assignedMembers} assigned to this topic, ${group.members} in the group`}
                    >
                      {group.assignedMembers}/{group.members}
                    </span>
                  </td>
                  <td className="px-5 py-3 text-right">
                    <span
                      className={`font-mono font-bold ${group.totalLag > 0 ? 'text-on-surface' : 'text-on-surface-variant'}`}
                      title={group.totalLag.toLocaleString()}
                    >
                      {formatCount(group.totalLag)}
                    </span>
                  </td>
                  <td className="px-5 py-3">
                    <Tooltip content={HEALTH_HELP[health]}>
                      <Badge tone={HEALTH_TONE[health]} dot>{HEALTH_LABEL[health]}</Badge>
                    </Tooltip>
                  </td>
                  <td className="px-5 py-3 text-right text-[11px] text-on-surface-variant">
                    {group.partitions.length - group.partitionsWithoutCommit}/{group.partitions.length} read
                  </td>
                </tr>
              )];
              if (open) {
                rows.push(
                  <tr key={`${group.groupId}-detail`} className="bg-surface-container-high/30">
                    <td colSpan={6} className="px-5 py-3">
                      <PartitionTable group={group} />
                    </td>
                  </tr>,
                );
              }
              return rows;
            })}
          </tbody>
        </Table>
      )}
    </div>
  );
};

/** En-tête triable — hissé hors du composant : redéfini au rendu, ce serait un type neuf à chaque fois. */
const SortHeader: FC<{
  label: string;
  column: GroupSort;
  sort: GroupSort;
  desc: boolean;
  onSort: (column: GroupSort) => void;
  align?: 'left' | 'right';
}> = ({ label, column, sort, desc, onSort, align = 'left' }) => (
  <th
    className={`px-5 py-3 ${align === 'right' ? 'text-right' : 'text-left'}`}
    aria-sort={sort === column ? (desc ? 'descending' : 'ascending') : 'none'}
  >
    <button
      type="button"
      onClick={() => onSort(column)}
      className={`inline-flex items-center gap-1 hover:text-on-surface transition-colors ${sort === column ? 'text-on-surface' : ''}`}
    >
      {label}
      {sort === column && (
        <span aria-hidden="true" className="material-symbols-outlined text-[14px]">
          {desc ? 'arrow_downward' : 'arrow_upward'}
        </span>
      )}
    </button>
  </th>
);

/** Détail partition par partition d'un groupe : c'est là que se voit un déséquilibre. */
const PartitionTable: FC<{ group: ConsumerGroupLag }> = ({ group }) => (
  <table className="w-full text-[12px]">
    <thead>
      <tr className="text-[10px] uppercase tracking-widest text-on-surface-variant">
        <th className="text-left py-1.5">Partition</th>
        <th className="text-right py-1.5">Committed</th>
        <th className="text-right py-1.5">End</th>
        <th className="text-right py-1.5">Lag</th>
        <th className="text-left py-1.5 pl-4">Progress</th>
        <th className="text-left py-1.5 pl-4">Assigned to</th>
      </tr>
    </thead>
    <tbody className="divide-y divide-outline-variant/30">
      {group.partitions.map(partition => {
        const progress = progressOf(partition);
        return (
          <tr key={partition.partition}>
            <td className="py-1.5 font-mono text-on-surface">P{partition.partition}</td>
            <td className="py-1.5 text-right font-mono text-on-surface-variant">
              {partition.committedOffset === null
                // Jamais 0 : « pas de position » n'est pas « au début ».
                ? <span className="italic">never committed</span>
                : partition.committedOffset.toLocaleString()}
            </td>
            <td className="py-1.5 text-right font-mono text-on-surface-variant">
              {partition.endOffset.toLocaleString()}
            </td>
            <td className="py-1.5 text-right font-mono">
              {partition.lag === null ? (
                <span className="text-on-surface-variant">—</span>
              ) : (
                <span className={partition.lag < 0 ? 'text-error font-bold' : partition.lag > 0 ? 'text-on-surface' : 'text-on-surface-variant'}>
                  {partition.lag.toLocaleString()}
                </span>
              )}
            </td>
            <td className="py-1.5 pl-4 w-32">
              {progress === null ? (
                <span className="text-on-surface-variant">—</span>
              ) : (
                <span className="block h-1.5 rounded-full bg-surface-container-highest overflow-hidden" title={`${Math.round(progress * 100)}%`}>
                  <span
                    className="block h-full rounded-full bg-primary"
                    style={{ width: `${progress * 100}%` }}
                  />
                </span>
              )}
            </td>
            <td className="py-1.5 pl-4 font-mono text-on-surface-variant">
              {partition.memberId
                ? <span title={`${partition.clientId ?? ''} @ ${partition.host ?? ''}`}>{partition.clientId ?? partition.memberId}</span>
                : <span className="italic">unassigned</span>}
            </td>
          </tr>
        );
      })}
    </tbody>
  </table>
);

export default TopicConsumersPanel;
