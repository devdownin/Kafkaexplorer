// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import axios from 'axios';
import ErrorBanner from '../components/ErrorBanner';
import {
  PageHeader, Stat, Badge, Button, EmptyState, Select, Tooltip,
  Table, TableHead, TableBody, TableRow, Th, Td, StatGridSkeleton, TableSkeleton,
} from '../components/ui';
import Sparkline from '../components/dashboard/Sparkline';
import ShareSparkline from '../components/deadletter/ShareSparkline';
import type { DashboardResponse, TopicActivity, TopicActivityResponse } from '../api/types';
import { describeApiError } from './queryError';
import {
  ACTIVITY_WINDOWS, formatSpan, readActivityScale, writeActivityScale,
  type ActivityScale, type ActivityWindow,
} from './topicActivity';
import {
  activityRequestTopics, assessQueue, shareSeries, summarize, supervisionTopics,
  type SupervisionTopic,
} from './deadLetterSupervision';

/**
 * Le titre de l'erreur, suivi de son message d'origine quand il ajoute quelque chose : le titre
 * seul est parfois une catégorie (« Request failed ») qui n'aide personne à agir.
 */
function explain(error: unknown): string {
  const info = describeApiError(error);
  return info.raw && info.raw !== info.title ? `${info.title}: ${info.raw}` : info.title;
}

/** axios n'a pas de délai par défaut ici non plus : sans ça, un broker muet fige les squelettes. */
const TIMEOUT_MS = 20000;
const WINDOW_KEY = 'kse:dead-letter-window';

/** La fenêtre par défaut est la journée : une file de rebut se lit sur un cycle d'exploitation. */
function readWindow(): ActivityWindow {
  try {
    const stored = localStorage.getItem(WINDOW_KEY);
    const found = stored ? ACTIVITY_WINDOWS.find(w => w.id === stored) : null;
    if (found) return found;
  } catch {
    /* mode privé, quota : le défaut fait l'affaire */
  }
  return ACTIVITY_WINDOWS[1];
}

type SortKey = 'name' | 'volume' | 'share';

/**
 * La supervision des files de rebut et de reprise.
 *
 * Le tableau de bord liste ces topics parmi les autres, triés par nom, avec la même courbe et la
 * même lecture — « ça produit, c'est vivant ». Ici la lecture est inversée : du trafic dans une
 * file de rebut est une perte, et l'écran est construit pour la voir arriver. Ce qu'il ajoute au
 * tableau de bord tient en trois choses : le regroupement (les files d'abord, les reprises
 * ensuite), le verdict par ligne, et surtout **la seconde courbe**, qui répond à la question que
 * la première ne peut pas poser — 40 échecs par heure, sur combien de messages ?
 *
 * Tout vient de deux appels que le serveur sert déjà : `GET /api/dashboard` pour le catalogue et
 * `GET /api/dashboard/activity` pour les séries, la file et sa source demandées ensemble. Rien
 * n'est ajouté côté serveur, et la page ne mesure donc jamais plus que ce que le tableau de bord
 * mesure déjà.
 */
const DeadLetter: React.FC = () => {
  const [catalogue, setCatalogue] = useState<{ topics: string[]; sizes: Record<string, number> } | null>(null);
  const [activity, setActivity] = useState<TopicActivityResponse | null>(null);
  const [activityKey, setActivityKey] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [activityError, setActivityError] = useState<string | null>(null);
  const [window_, setWindow] = useState<ActivityWindow>(readWindow);
  const [scale, setScale] = useState<ActivityScale>(readActivityScale);
  const [sortKey, setSortKey] = useState<SortKey>('volume');
  const [reloadToken, setReloadToken] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    axios.get<DashboardResponse>('/api/dashboard', { signal: controller.signal, timeout: TIMEOUT_MS })
      .then(response => {
        setCatalogue({ topics: response.data.topics, sizes: response.data.topicSizes });
        setError(null);
      })
      .catch(e => {
        if (axios.isCancel(e)) return;
        // `describeApiError` rend un titre et un brut, jamais un `message` : lire un champ
        // inexistant posait `undefined` et la bannière disparaissait avec l'erreur.
        setError(explain(e));
      });
    return () => controller.abort();
  }, [reloadToken]);

  const rows = useMemo(
    () => (catalogue ? supervisionTopics(catalogue.topics) : []),
    [catalogue],
  );

  /*
   * Les séries. La liste part *par paires* — voir `activityRequestTopics` : le serveur coupe à
   * `explorer.activity-max-topics` en gardant le début, et une liste groupée par nature perdrait
   * toutes les sources d'un coup, c'est-à-dire la moitié de l'écran.
   */
  const requested = useMemo(() => activityRequestTopics(rows), [rows]);
  const key = `${window_.id}|${requested.join(',')}`;

  useEffect(() => {
    if (requested.length === 0) return;
    const controller = new AbortController();
    axios.get<TopicActivityResponse>('/api/dashboard/activity', {
      params: { topics: requested.join(','), windowMs: window_.windowMs, buckets: window_.buckets },
      signal: controller.signal,
      timeout: TIMEOUT_MS,
    })
      .then(response => {
        setActivity(response.data);
        setActivityKey(key);
        setActivityError(null);
      })
      .catch(e => {
        if (axios.isCancel(e)) return;
        // La raison remplace les courbes : des courbes plates diraient « aucune perte », qui est
        // précisément l'affirmation qu'on n'a pas les moyens de faire.
        setActivityError(explain(e));
        setActivityKey(key);
      });
    return () => controller.abort();
  }, [key, requested, window_.windowMs, window_.buckets]);

  const series = useMemo<Record<string, TopicActivity>>(
    () => (activityKey === key && activity ? activity.topics : {}),
    [activity, activityKey, key],
  );
  const seriesLoading = requested.length > 0 && activityKey !== key;

  const summary = useMemo(() => summarize(rows, series), [rows, series]);

  const shares = useMemo(() => {
    const out: Record<string, ReturnType<typeof shareSeries>> = {};
    for (const row of rows) {
      out[row.topic] = shareSeries(series[row.topic], row.source ? series[row.source] : null);
    }
    return out;
  }, [rows, series]);

  /*
   * Le tri par défaut est le volume, pas le nom : sur cet écran on cherche ce qui se remplit, et
   * un tri alphabétique met en tête celui dont le nom commence par « a ». Les lignes non mesurées
   * tombent en bas plutôt qu'en haut — une absence de mesure n'est pas un zéro, mais ce n'est pas
   * non plus un motif d'ouvrir la page.
   */
  const sorted = useMemo(() => {
    const copy = [...rows];
    if (sortKey === 'name') return copy.sort((a, b) => a.topic.localeCompare(b.topic));
    if (sortKey === 'share') {
      return copy.sort((a, b) => (shares[b.topic]?.overall ?? -1) - (shares[a.topic]?.overall ?? -1));
    }
    return copy.sort((a, b) => (series[b.topic]?.total ?? -1) - (series[a.topic]?.total ?? -1));
  }, [rows, series, shares, sortKey]);

  const changeWindow = useCallback((id: string) => {
    const found = ACTIVITY_WINDOWS.find(w => w.id === id);
    if (!found) return;
    setWindow(found);
    try {
      localStorage.setItem(WINDOW_KEY, id);
    } catch {
      /* l'écriture est un confort, jamais une condition */
    }
  }, []);

  const toggleScale = useCallback(() => {
    setScale(current => {
      const next = current === 'log' ? 'linear' : 'log';
      writeActivityScale(next);
      return next;
    });
  }, []);

  const loading = catalogue === null && error === null;

  return (
    <div className="space-y-6">
      <PageHeader
        kicker="Observe"
        title="Dead letter & retry"
        description={
          'Every dead-letter and retry topic the cluster names, with what landed in it and what share '
          + 'of its source that represents. Traffic here is loss, so a quiet queue is the good news.'
        }
        actions={
          <>
            <Select
              aria-label="Window"
              value={window_.id}
              onChange={e => changeWindow(e.target.value)}
              className="w-[9.5rem]"
            >
              {ACTIVITY_WINDOWS.map(w => <option key={w.id} value={w.id}>{w.label}</option>)}
            </Select>
            <Button variant="secondary" onClick={toggleScale} title="Vertical scale of the arrivals curve">
              {scale === 'log' ? 'Log scale' : 'Linear scale'}
            </Button>
            <Button variant="secondary" icon="refresh" onClick={() => setReloadToken(n => n + 1)}>
              Refresh
            </Button>
          </>
        }
      />

      {error && <ErrorBanner message={error} onRetry={() => setReloadToken(n => n + 1)} />}

      {loading ? (
        <StatGridSkeleton count={4} />
      ) : (
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
          <Stat
            label="Queues watched"
            value={summary.topics}
            icon="report"
            hint={`${summary.deadLetter} dead letter · ${summary.retry} retry`}
          />
          <Stat
            label="Receiving"
            value={summary.receiving}
            icon="trending_up"
            tone={summary.receiving > 0 ? 'warning' : 'success'}
            hint={
              summary.measured < summary.topics
                ? `${summary.topics - summary.measured} not measured`
                : `over the last ${formatSpan(window_.windowMs)}`
            }
          />
          <Stat
            label="Surging"
            value={summary.surging}
            icon="priority_high"
            tone={summary.surging > 0 ? 'error' : 'none'}
            hint="filling faster than their own median"
          />
          <Stat
            label="Messages"
            value={summary.messages.toLocaleString()}
            icon="inbox"
            hint={summary.unpaired > 0 ? `${summary.unpaired} queue(s) without a paired source` : 'across every queue'}
          />
        </div>
      )}

      {activityError && (
        <ErrorBanner
          message={`The series could not be read: ${activityError}`}
          onRetry={() => setReloadToken(n => n + 1)}
        />
      )}

      {activity?.warnings?.map((warning, i) => (
        <p key={i} className="text-[12px] text-warning bg-warning/10 border border-warning/30 rounded-lg px-3 py-2">
          {warning}
        </p>
      ))}

      {loading ? (
        <TableSkeleton rows={6} columns={5} />
      ) : rows.length === 0 ? (
        <EmptyState
          icon="check_circle"
          title="No dead-letter or retry topic on this cluster"
          description={
            'Nothing here is named .DLQ, .DLT or retry. That is either a pipeline with no failure '
            + 'path, or one whose queues follow another convention — this screen reads names, not payloads.'
          }
          action={<Link to="/"><Button variant="secondary">Back to the dashboard</Button></Link>}
        />
      ) : (
        <div className="bg-surface-container rounded-xl ring-1 ring-white/[0.045] overflow-hidden">
          <Table>
            <TableHead>
              <TableRow>
                <Th>
                  <button onClick={() => setSortKey('name')} className="hover:text-on-surface transition-colors">
                    Topic
                  </button>
                </Th>
                <Th>Status</Th>
                <Th>
                  <span className="flex items-center gap-1">
                    <button onClick={() => setSortKey('volume')} className="hover:text-on-surface transition-colors">
                      Arrivals
                    </button>
                    <Tooltip content={`What landed in the queue, one point per ${formatSpan(window_.bucketMs)}, counted from offsets. Click a point to open those messages.`}>
                      <span aria-hidden="true" className="material-symbols-outlined text-[14px] text-outline">info</span>
                    </Tooltip>
                  </span>
                </Th>
                <Th>
                  <span className="flex items-center gap-1">
                    <button onClick={() => setSortKey('share')} className="hover:text-on-surface transition-colors">
                      Share of source
                    </button>
                    <Tooltip content="The same buckets divided by what the paired source topic produced — the failure rate. Broken where the source produced nothing: a share of no traffic is not zero.">
                      <span aria-hidden="true" className="material-symbols-outlined text-[14px] text-outline">info</span>
                    </Tooltip>
                  </span>
                </Th>
                <Th className="text-right">Backlog</Th>
              </TableRow>
            </TableHead>
            <TableBody>
              {sorted.map(row => (
                <QueueRow
                  key={row.topic}
                  row={row}
                  activity={series[row.topic]}
                  share={shares[row.topic]}
                  size={catalogue?.sizes[row.topic]}
                  loading={seriesLoading}
                  scale={scale}
                />
              ))}
            </TableBody>
          </Table>
        </div>
      )}

      <p className="text-[12px] text-on-surface-variant">
        Both curves are counted from offsets over the last {formatSpan(window_.windowMs)}, one point per{' '}
        {formatSpan(window_.bucketMs)}. A queue is paired with its source by name, and only when that topic
        really exists on the cluster — the share is left out rather than computed against a guess.
      </p>
    </div>
  );
};

interface QueueRowProps {
  row: SupervisionTopic;
  activity?: TopicActivity;
  share: ReturnType<typeof shareSeries>;
  size?: number;
  loading: boolean;
  scale: ActivityScale;
}

const QueueRow: React.FC<QueueRowProps> = ({ row, activity, share, size, loading, scale }) => {
  const verdict = assessQueue(activity, row.kind);
  return (
    <TableRow>
      <Td>
        <Link to={`/topic/${encodeURIComponent(row.topic)}`} className="text-on-surface hover:text-primary transition-colors">
          {row.topic}
        </Link>
        <div className="flex items-center gap-1.5 mt-1">
          <Badge tone={row.kind === 'RETRY' ? 'secondary' : 'primary'}>{row.kind}</Badge>
          {row.source ? (
            <span className="text-[11px] text-outline">
              from{' '}
              <Link to={`/topic/${encodeURIComponent(row.source)}`} className="hover:text-primary transition-colors">
                {row.source}
              </Link>
            </span>
          ) : (
            <Tooltip
              content={
                row.triedSource
                  ? `No topic named ${row.triedSource} exists on this cluster, so there is nothing to compute a share against.`
                  : 'The name carries no source to derive, so there is nothing to compute a share against.'
              }
            >
              <span className="text-[11px] text-outline">no source paired</span>
            </Tooltip>
          )}
        </div>
      </Td>
      <Td>
        <Tooltip content={verdict.detail}>
          <Badge tone={verdict.tone} dot>{verdict.label}</Badge>
        </Tooltip>
      </Td>
      <Td>
        <Sparkline topic={row.topic} activity={activity ?? null} loading={loading} scale={scale} />
      </Td>
      <Td>
        <ShareSparkline
          topic={row.topic}
          source={row.source}
          series={share}
          activity={activity ?? null}
          loading={loading}
        />
      </Td>
      <Td className="text-right tabular-nums text-on-surface-variant">
        {size === undefined ? '—' : size.toLocaleString()}
      </Td>
    </TableRow>
  );
};

export default DeadLetter;
