// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useCallback, useEffect, useMemo, useState } from 'react';
import type { FC } from 'react';
import axios from 'axios';
import {
  Badge, Button, EmptyState, ErrorPanel, Input, Table, TableSkeleton, Tooltip, HelpTip,
} from '../ui';
import { describeApiError, type QueryErrorInfo } from '../../pages/queryError';
import {
  HEALTH_HELP, HEALTH_LABEL, HEALTH_TONE,
  type ConsumerGroupLag, type GroupSort, type TopicConsumers, type TopicTimeLag,
  describeDelay, describeScope, describeSummary, filterGroups, formatCount, formatDelay, healthOf,
  progressOf, sortDelayPartitions, sortGroups,
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
          icon={data && !data.available ? 'error' : 'groups'}
          title={
            // « Personne ne lit ce topic » est une affirmation ; elle n'a pas sa place quand la
            // question n'a pas pu être posée. Le drapeau `available` sépare les deux.
            data && !data.available ? 'Consumer groups could not be read'
              : filter ? 'No group matches that filter'
                : 'No consumer group reads this topic'
          }
          description={
            data && !data.available
              ? (data.warnings[0] ?? 'The broker did not answer. Retry, or check the connection '
                + 'on the Settings page.')
              : filter
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
                    {group.membersKnown ? (
                      <span
                        className="font-mono text-on-surface-variant"
                        title={`${group.assignedMembers} assigned to this topic, ${group.members} in the group`}
                      >
                        {group.assignedMembers}/{group.members}
                      </span>
                    ) : (
                      // Zéro membre affiché pour un groupe qu'on n'a pas su décrire se lirait
                      // « personne ne le consomme » — la conclusion exacte que le serveur refuse
                      // désormais de tirer (cf. `membersKnown`).
                      <Tooltip content="This group could not be described, so its membership is unknown — not zero.">
                        <span className="font-mono text-on-surface-variant italic">—</span>
                      </Tooltip>
                    )}
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
                    {/* Le serveur porte la raison par groupe ; elle n'était affichée nulle part,
                        et « Unreadable » sans le motif ne se diagnostique pas. */}
                    <Tooltip content={group.error ? `${HEALTH_HELP[health]} — ${group.error}` : HEALTH_HELP[health]}>
                      <Badge tone={HEALTH_TONE[health]} dot>{HEALTH_LABEL[health]}</Badge>
                    </Tooltip>
                  </td>
                  <td className="px-5 py-3 text-right text-[11px] text-on-surface-variant">
                    {group.error
                      ? <span className="italic">—</span>
                      : `${group.partitions.length - group.partitionsWithoutCommit}/${group.partitions.length} read`}
                  </td>
                </tr>
              )];
              if (open) {
                rows.push(
                  <tr key={`${group.groupId}-detail`} className="bg-surface-container-high/30">
                    <td colSpan={6} className="px-5 py-3">
                      {group.error
                        ? (
                          <p className="text-[12px] text-on-surface-variant">
                            This group's positions could not be read, so it has no per-partition
                            detail: <span className="text-on-surface">{group.error}</span>
                          </p>
                        )
                        : (
                          <>
                            <PartitionTable group={group} />
                            <DelayMeasurement topic={topic} group={group} />
                          </>
                        )}
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

/**
 * Le retard du groupe **en temps**, à la demande.
 *
 * Sur un bouton, pas au chargement : cette mesure ouvre un consommateur et lit un record par
 * partition en retard, là où tout le reste de ce panneau lit des métadonnées. Faire payer ça à
 * chaque ouverture de l'onglet, c'est rendre coûteuse une page qu'on ouvre pour regarder.
 *
 * Le nombre de records dit combien attend ; celui-ci dit depuis quand — et c'est la seconde
 * question qui décide s'il faut agir : quatre mille messages, c'est quatre secondes de trafic sur
 * un topic et quatre jours sur un autre.
 */
const DelayMeasurement: FC<{ topic: string; group: ConsumerGroupLag }> = ({ topic, group }) => {
  const [delay, setDelay] = useState<TopicTimeLag | null>(null);
  const [measuring, setMeasuring] = useState(false);
  const [failure, setFailure] = useState<QueryErrorInfo | null>(null);

  const measure = async () => {
    setMeasuring(true);
    setFailure(null);
    try {
      const res = await axios.get<TopicTimeLag>(
        `/api/topic/${encodeURIComponent(topic)}/time-lag?group=${encodeURIComponent(group.groupId)}`,
      );
      setDelay(res.data);
    } catch (err) {
      setFailure(describeApiError(err, 'The delay of this group could not be measured.'));
    } finally {
      setMeasuring(false);
    }
  };

  return (
    <div className="mt-4 pt-3 border-t border-outline-variant/40">
      <div className="flex items-center gap-3 flex-wrap">
        <Button variant="ghost" size="sm" icon="schedule" onClick={measure} loading={measuring}>
          {delay ? 'Measure again' : 'How long has it been waiting?'}
        </Button>
        <HelpTip
          label="What this measurement costs"
          content="Reads the record sitting at each lagging partition's committed offset, so this costs more than the counts above — hence the button. Bounded to 64 partitions and 8 s."
        />
        {delay && (
          <span className={`text-[12px] ${delay.available ? 'text-on-surface-variant' : 'text-warning'}`}>
            {describeDelay(delay)}
          </span>
        )}
      </div>

      {failure && <div className="mt-3"><ErrorPanel error={failure} /></div>}

      {delay?.warnings?.map((warning, index) => (
        <p key={index} className="mt-2 text-[11px] text-warning">{warning}</p>
      ))}

      {delay && delay.partitions.length > 0 && (
        <table className="w-full text-[12px] mt-3">
          <thead>
            <tr className="text-[10px] uppercase tracking-widest text-on-surface-variant">
              <th className="text-left py-1.5">Partition</th>
              <th className="text-right py-1.5">Waiting since</th>
              <th className="text-right py-1.5">Records behind</th>
              <th className="text-left py-1.5 pl-4">Note</th>
            </tr>
          </thead>
          <tbody>
            {sortDelayPartitions(delay.partitions).map(partition => (
              <tr key={partition.partition} className="border-t border-outline-variant/30">
                <td className="py-1.5 font-mono text-on-surface-variant">{partition.partition}</td>
                <td className="py-1.5 text-right font-mono">
                  {/* Jamais un zéro à la place d'une mesure absente : « à jour » et « pas mesuré »
                      sont deux réponses opposées, et c'est tout l'enjeu de cette colonne. */}
                  {partition.lagMs === null
                    ? <span className="text-on-surface-variant italic">not measured</span>
                    : partition.lagMs === 0
                      ? <span className="text-success">caught up</span>
                      : <span className="text-on-surface">{formatDelay(partition.lagMs)}</span>}
                </td>
                <td className="py-1.5 text-right font-mono text-on-surface-variant">
                  {partition.recordLag === null ? '—' : formatCount(partition.recordLag)}
                </td>
                <td className="py-1.5 pl-4 text-[11px] text-on-surface-variant">{partition.note ?? ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
};
