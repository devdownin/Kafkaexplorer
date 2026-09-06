// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import axios from 'axios';
import ErrorBanner from '../components/ErrorBanner';
import {
  PageHeader, Stat, Badge, Button, EmptyState, Select, Tooltip, HelpTip, Input, SortButton,
  Table, TableHead, TableBody, TableRow, Th, Td, StatGridSkeleton, TableSkeleton,
} from '../components/ui';
import Sparkline from '../components/dashboard/Sparkline';
import ShareSparkline from '../components/deadletter/ShareSparkline';
import QueueDetailPanel from '../components/deadletter/QueueDetailPanel';
import type { DashboardResponse, TopicActivity, TopicActivityResponse } from '../api/types';
import { describeApiError } from './queryError';
import {
  ACTIVITY_WINDOWS, formatSpan, readActivityScale, writeActivityScale,
  type ActivityScale, type ActivityWindow,
} from './topicActivity';
import {
  REFRESH_OPTIONS, REFRESH_OFF, activityIntervalMs, describeRefreshStatus, readRefreshChoice,
  writeRefreshChoice, type RefreshChoice,
} from './dashboardRefresh';
import {
  assessQueue, describePairing, escalationTargetOf, queueRequestTopics, shareSeries,
  sourceRequestTopics, summarize, supervisionTopics, type SupervisionTopic,
} from './deadLetterSupervision';
import {
  readScreenState, writeScreenState, type ScreenState, type SortDir, type SortKey,
} from './deadLetterUrl';

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
const DEFAULT_WINDOW_ID = ACTIVITY_WINDOWS[1].id;

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

const PAGE_SIZES = [10, 25, 50, 100];

/**
 * Le sens par défaut de chaque colonne, parce qu'il n'est pas le même partout : sur un nom on
 * cherche l'ordre alphabétique, sur un volume ou un taux on cherche le pire, qui est le plus
 * grand. Prendre `asc` pour tout — ce que fait un tri générique — mettrait en tête les files les
 * plus calmes sur l'écran qui existe pour montrer celles qui ne le sont pas.
 */
const NATURAL_DIR: Record<SortKey, SortDir> = {
  name: 'asc', volume: 'desc', share: 'desc', size: 'desc',
};

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
  /*
   * L'état de l'écran vit dans l'URL, comme partout ailleurs dans cette application. Ce n'était pas
   * le cas ici, et le coût tombait au pire moment : pendant un incident, « regarde `orders.DLQ`
   * sur sept jours » ne pouvait pas s'envoyer en lien, il fallait décrire le chemin. Les réglages
   * de *lecture* (l'échelle, la cadence) restent dans le stockage local — ce sont des préférences
   * de la personne, pas de la situation qu'on partage.
   */
  const [searchParams, setSearchParams] = useSearchParams();
  const fromUrl = useMemo(() => readScreenState(searchParams), [searchParams]);

  const [window_, setWindow] = useState<ActivityWindow>(() => fromUrl.window ?? readWindow());
  const [scale, setScale] = useState<ActivityScale>(readActivityScale);
  const [sortKey, setSortKey] = useState<SortKey>(fromUrl.sortKey ?? 'volume');
  const [sortDir, setSortDir] = useState<SortDir>(
    fromUrl.sortDir ?? NATURAL_DIR[fromUrl.sortKey ?? 'volume']);
  const [filter, setFilter] = useState(fromUrl.filter ?? '');
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(25);
  const [refresh, setRefresh] = useState<RefreshChoice>(readRefreshChoice);
  /*
   * La file ouverte, une seule à la fois. Ses deux lectures — un balayage des groupes du cluster,
   * un échantillon de la file — coûtent autre chose que les offsets des courbes, et deux panneaux
   * ouverts doubleraient ce coût pour une comparaison que personne n'a demandée.
   */
  const [opened, setOpened] = useState<string | null>(fromUrl.opened ?? null);
  /** L'instant de la dernière réponse : c'est lui qui date les chiffres, pas le rendu. */
  const [fetchedAt, setFetchedAt] = useState(() => Date.now());
  const [now, setNow] = useState(() => Date.now());
  const [reloadToken, setReloadToken] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    axios.get<DashboardResponse>('/api/dashboard', { signal: controller.signal, timeout: TIMEOUT_MS })
      .then(response => {
        setCatalogue({ topics: response.data.topics, sizes: response.data.topicSizes });
        setFetchedAt(Date.now());
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
   * **Deux demandes, et c'est le correctif d'un défaut d'échelle.** Files et sources partaient
   * entrelacées dans un seul appel, ce qui doublait la liste ; le contrôleur coupe à
   * `explorer.activity-max-topics` (100) en gardant le début, donc au-delà d'une cinquantaine de
   * files la coupe mordait sur des lignes réelles. Elles n'avaient jamais de courbe, le tri par
   * volume les classait au fond faute de mesure, et l'écran affirmait un classement qu'il n'avait
   * pas mesuré.
   *
   * Les files seules tiennent maintenant jusqu'à cent, ce qui couvre les tuiles et le classement
   * pour tout cluster réaliste ; les sources suivent dans un second appel, pour les lignes
   * affichées seulement, puisque la seconde courbe n'est tracée que là.
   */
  const requested = useMemo(() => queueRequestTopics(rows), [rows]);
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

  /*
   * Les séries de sources, accumulées et **jamais oubliées** tant que la fenêtre ne change pas.
   * C'est ce cliquet qui rend le procédé stable : trier par taux change la page affichée, la page
   * demande de nouvelles sources, et les sources changent le taux donc le tri. Comme la
   * connaissance ne fait que croître, la boucle converge au lieu d'osciller entre deux pages.
   */
  const [sourceCache, setSourceCache] = useState<{ key: string; topics: Record<string, TopicActivity> }>(
    () => ({ key: '', topics: {} }));
  /*
   * Le cache porte la question à laquelle il répond. Le vider dans un effet quand la fenêtre change
   * marcherait aussi, mais poserait un état depuis un effet — ce que `react-hooks/set-state-in-effect`
   * interdit ici à raison : une clé comparée au rendu dit la même chose sans rendu supplémentaire,
   * et sans la fenêtre où le cache et la fenêtre se contredisent.
   */
  const sourceKey = `${window_.id}|${reloadToken}`;
  const sourceSeries = useMemo(
    () => (sourceCache.key === sourceKey ? sourceCache.topics : {}),
    [sourceCache, sourceKey],
  );

  const summary = useMemo(() => summarize(rows, series), [rows, series]);
  const sizes = useMemo(() => catalogue?.sizes ?? {}, [catalogue]);

  const shares = useMemo(() => {
    const out: Record<string, ReturnType<typeof shareSeries>> = {};
    for (const row of rows) {
      const source = row.pairing.source;
      out[row.topic] = shareSeries(series[row.topic], source ? sourceSeries[source] : null);
    }
    return out;
  }, [rows, series, sourceSeries]);

  /*
   * Le tri par défaut est le volume, pas le nom : sur cet écran on cherche ce qui se remplit, et
   * un tri alphabétique met en tête celui dont le nom commence par « a ». Les lignes non mesurées
   * tombent en bas plutôt qu'en haut — une absence de mesure n'est pas un zéro, mais ce n'est pas
   * non plus un motif d'ouvrir la page.
   */
  const sorted = useMemo(() => {
    /*
     * Une ligne non mesurée vaut -1 et tombe donc au bout en descendant : une absence de mesure
     * n'est pas un zéro, mais ce n'est pas non plus un motif d'ouvrir la page. En ascendant elle
     * remonte en tête, ce qui est le bon résultat de l'autre question — « qu'est-ce que je n'ai
     * pas pu lire ? ».
     */
    const rank = (row: SupervisionTopic): number | string => {
      if (sortKey === 'name') return row.topic;
      if (sortKey === 'share') return shares[row.topic]?.overall ?? -1;
      if (sortKey === 'size') return sizes[row.topic] ?? -1;
      return series[row.topic]?.total ?? -1;
    };
    const sign = sortDir === 'asc' ? 1 : -1;
    return [...rows].sort((a, b) => {
      const x = rank(a);
      const y = rank(b);
      const order = typeof x === 'string' ? x.localeCompare(y as string) : x - (y as number);
      // Le nom départage : deux files à égalité de volume ne doivent pas changer de place d'un
      // rafraîchissement à l'autre, ce qui rend un tableau qui se rafraîchit tout seul illisible.
      return order !== 0 ? order * sign : a.topic.localeCompare(b.topic);
    });
  }, [rows, series, shares, sizes, sortKey, sortDir]);

  /*
   * Le filtre ne porte que sur le **tableau**. Les tuiles du haut et la demande de séries couvrent
   * toutes les files, bornées par le serveur qui le dit dans ses `warnings` : un écran de
   * supervision dont le compteur « Surging » suivrait la page affichée annoncerait zéro incident
   * dès qu'on tape trois lettres dans une zone de recherche.
   */
  const matching = useMemo(() => {
    const needle = filter.trim().toLowerCase();
    if (!needle) return sorted;
    return sorted.filter(row =>
      row.topic.toLowerCase().includes(needle)
      || (row.pairing.source?.toLowerCase().includes(needle) ?? false));
  }, [sorted, filter]);

  const totalPages = Math.max(1, Math.ceil(matching.length / pageSize));
  const pageRows = useMemo(
    () => matching.slice(page * pageSize, (page + 1) * pageSize),
    [matching, page, pageSize],
  );

  /*
   * Le second appel : les sources des lignes **affichées**, et seulement celles qu'on n'a pas déjà.
   * La seconde courbe n'est tracée que sur les lignes visibles, donc mesurer la source des autres
   * paierait un aller-retour au broker pour un dessin que personne ne regarde.
   */
  const missingSources = useMemo(
    () => sourceRequestTopics(pageRows, new Set(Object.keys(sourceSeries))),
    [pageRows, sourceSeries],
  );

  useEffect(() => {
    if (missingSources.length === 0) return;
    const controller = new AbortController();
    axios.get<TopicActivityResponse>('/api/dashboard/activity', {
      params: {
        topics: missingSources.join(','),
        windowMs: window_.windowMs,
        buckets: window_.buckets,
      },
      signal: controller.signal,
      timeout: TIMEOUT_MS,
    })
      .then(response => setSourceCache(current => ({
        key: sourceKey,
        topics: current.key === sourceKey
          ? { ...current.topics, ...response.data.topics }
          : response.data.topics,
      })))
      .catch(e => {
        if (axios.isCancel(e)) return;
        /*
         * Silencieux à dessein, et c'est le seul endroit de cet écran qui le soit : une source
         * qu'on n'a pas pu lire est déjà dite par la seconde courbe elle-même, qui affiche « not
         * comparable » avec la raison. Une bannière en plus ferait de l'échec d'un dessin
         * secondaire un incident de page.
         */
      });
    return () => controller.abort();
  }, [missingSources, sourceKey, window_.windowMs, window_.buckets]);

  /*
   * L'escalade d'une reprise : la file morte qu'elle alimente, si le cluster en a une. Elle décide
   * du ton du verdict — une reprise qui se remplit et dont la file morte reste vide fait son
   * travail, l'annoncer en orange comme un rebut apprend à ignorer la couleur.
   */
  const escalationOf = useCallback(
    (row: SupervisionTopic) => {
      const target = escalationTargetOf(row, rows);
      return target ? series[target] ?? null : null;
    },
    [rows, series],
  );

  /** La bascule : la colonne active s'inverse, une autre prend son sens naturel. */
  const toggleSort = useCallback((key: SortKey) => {
    setSortKey(current => {
      if (current === key) setSortDir(d => (d === 'asc' ? 'desc' : 'asc'));
      else setSortDir(NATURAL_DIR[key]);
      return key;
    });
    setPage(0);
  }, []);

  /*
   * L'URL suit l'écran, en `replace` : chaque frappe dans le filtre pousserait sinon une entrée
   * d'historique, et « Précédent » deviendrait un correcteur de saisie au lieu de ramener à la
   * page d'où l'on vient.
   */
  useEffect(() => {
    const wanted: ScreenState = {
      window: window_,
      filter,
      sortKey,
      sortDir,
      opened: opened ?? undefined,
    };
    setSearchParams(
      current => writeScreenState(current, wanted, {
        window: DEFAULT_WINDOW_ID, sortKey: 'volume', sortDir: NATURAL_DIR.volume,
      }),
      { replace: true },
    );
  }, [window_, filter, sortKey, sortDir, opened, setSearchParams]);

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

  /*
   * Le sondage, et les deux règles qu'il reprend du tableau de bord plutôt que d'en inventer
   * d'autres : il ne tourne que quand l'onglet est **visible** — un écran de supervision laissé
   * ouvert sur un second moniteur en veille n'a personne pour le lire, et continuerait pourtant à
   * interroger le broker — et sa période passe par `activityIntervalMs`, qui plafonne le choix au
   * cache serveur de 30 s. Les deux lectures de cette page sont de la classe « activité » (le
   * catalogue est caché lui aussi), donc une seule cadence les gouverne, contrairement au tableau
   * de bord où le sondage court à 5 s et la colonne d'activité à 30.
   */
  useEffect(() => {
    const period = activityIntervalMs(refresh);
    if (period === null) return;
    const timer = setInterval(() => {
      if (document.visibilityState === 'visible') setReloadToken(n => n + 1);
    }, period);
    return () => clearInterval(timer);
  }, [refresh]);

  /* L'âge affiché doit vieillir tout seul : sans cette horloge il resterait sur « just now »
     jusqu'au prochain rendu, c'est-à-dire jusqu'au prochain rafraîchissement. */
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 10_000);
    return () => clearInterval(timer);
  }, []);

  const changeRefresh = useCallback((choice: RefreshChoice) => {
    setRefresh(choice);
    writeRefreshChoice(choice);
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
            <Select
              aria-label="Auto-refresh"
              value={refresh}
              onChange={e => changeRefresh(e.target.value)}
              className="w-[9.5rem]"
            >
              {REFRESH_OPTIONS.map(o => <option key={o.id} value={o.id}>{o.label}</option>)}
              <option value={REFRESH_OFF}>Off</option>
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

      {/* Quand les chiffres ont été lus, et ce que la page fera ensuite. L'un sans l'autre ne
          répond pas : « il y a 4 min » sans la cadence laisse croire à une panne. */}
      <p className="text-[12px] text-on-surface-variant -mt-3">
        {describeRefreshStatus(refresh, fetchedAt, now)}
      </p>

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
        <div className="space-y-3">
          {/* Le filtre porte sur le nom de la file **et** sur celui de sa source : quand un
              incident touche un flux, on tape le nom du flux, pas celui de ses files. */}
          <div className="flex items-center justify-between gap-3 flex-wrap">
            <Input
              type="search"
              aria-label="Filter queues"
              placeholder="Filter by queue or source…"
              value={filter}
              onChange={e => { setFilter(e.target.value); setPage(0); }}
              className="w-72 max-w-full"
            />
            <p className="text-[12px] text-on-surface-variant tabular-nums">
              {matching.length === rows.length
                ? `${rows.length} queue${rows.length === 1 ? '' : 's'}`
                : `${matching.length} of ${rows.length} queues`}
            </p>
          </div>

          <div className="bg-surface-container rounded-xl ring-1 ring-white/[0.045] overflow-hidden">
            <Table>
              <TableHead>
                <TableRow>
                  <Th><SortButton k="name" sortKey={sortKey} sortDir={sortDir} onToggle={toggleSort}>Topic</SortButton></Th>
                  <Th>Status</Th>
                  <Th>
                    <span className="flex items-center gap-1">
                      <SortButton k="volume" sortKey={sortKey} sortDir={sortDir} onToggle={toggleSort}>Arrivals</SortButton>
                      <Tooltip content={`What landed in the queue, one point per ${formatSpan(window_.bucketMs)}, counted from offsets. Click a point to open those messages.`}>
                        <span aria-hidden="true" className="material-symbols-outlined text-[14px] text-outline">info</span>
                      </Tooltip>
                    </span>
                  </Th>
                  <Th>
                    <span className="flex items-center gap-1">
                      <SortButton k="share" sortKey={sortKey} sortDir={sortDir} onToggle={toggleSort}>Share of source</SortButton>
                      <Tooltip content="The same buckets divided by what the paired source topic produced — the failure rate. Broken where the source produced nothing: a share of no traffic is not zero.">
                        <span aria-hidden="true" className="material-symbols-outlined text-[14px] text-outline">info</span>
                      </Tooltip>
                    </span>
                  </Th>
                  <Th className="text-right">
                    {/*
                      * Nommée « Backlog » jusqu'ici, et c'était faux par son nom : la valeur est
                      * `endOffsets - beginningOffsets`, donc ce que le topic contient, pas ce qui
                      * reste à traiter. Sur une file de rebut « backlog » se lit « en attente de
                      * reprise », et les deux ne coïncident que si personne n'a jamais consommé —
                      * un mot qui répond à côté sur la seule colonne chiffrée de la ligne.
                      */}
                    <span className="inline-flex items-center gap-1">
                      <SortButton k="size" sortKey={sortKey} sortDir={sortDir} onToggle={toggleSort}>Size</SortButton>
                      <HelpTip
                        label="What Size counts"
                        content={
                          'Records the topic holds — the log end minus its start. That is what is in '
                          + 'the queue, not what is left to reprocess: a consumer that has drained it '
                          + 'leaves the records in place until retention removes them. Offsets a '
                          + 'transaction marker took count too.'
                        }
                      />
                    </span>
                  </Th>
                </TableRow>
              </TableHead>
              <TableBody>
                {pageRows.map(row => (
                  <QueueRow
                    key={row.topic}
                    row={row}
                    activity={series[row.topic]}
                    escalation={escalationOf(row)}
                    share={shares[row.topic]}
                    size={sizes[row.topic]}
                    loading={seriesLoading}
                    scale={scale}
                    opened={opened === row.topic}
                    onToggle={() => setOpened(current => (current === row.topic ? null : row.topic))}
                  />
                ))}
              </TableBody>
            </Table>
          </div>

          {matching.length === 0 && (
            <p className="text-[13px] text-on-surface-variant px-1">
              No queue matches “{filter}”. {rows.length} are watched on this cluster.
            </p>
          )}

          {/* Le pager n'apparaît qu'au-delà d'une page : sur les trois files d'un cluster
              ordinaire, c'est un contrôle qui ne fait rien et qu'il faut quand même lire. */}
          {totalPages > 1 && (
            <div className="flex items-center justify-between gap-3 flex-wrap px-1">
              <p className="text-[12px] text-on-surface-variant tabular-nums">
                {`${page * pageSize + 1}–${Math.min((page + 1) * pageSize, matching.length)} of ${matching.length}`}
              </p>
              <div className="flex items-center gap-2">
                <Select
                  aria-label="Rows per page"
                  value={String(pageSize)}
                  onChange={e => { setPageSize(Number(e.target.value)); setPage(0); }}
                  className="w-[6.5rem]"
                >
                  {PAGE_SIZES.map(n => <option key={n} value={n}>{n} rows</option>)}
                </Select>
                <Button
                  variant="secondary"
                  onClick={() => setPage(p => Math.max(0, p - 1))}
                  disabled={page === 0}
                >
                  Previous
                </Button>
                <Button
                  variant="secondary"
                  onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}
                  disabled={page >= totalPages - 1}
                >
                  Next
                </Button>
              </div>
            </div>
          )}
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
  opened: boolean;
  onToggle: () => void;
  /** La série de la file morte que cette reprise alimente, quand il y en a une. */
  escalation?: TopicActivity | null;
}

const QueueRow: React.FC<QueueRowProps> = ({
  row, activity, share, size, loading, scale, opened, onToggle, escalation,
}) => {
  const verdict = assessQueue(activity, row.kind, escalation);
  const pairing = describePairing(row.pairing);
  const source = row.pairing.source;
  const panelId = `queue-detail-${row.topic}`;
  return (
    <>
    <TableRow>
      <Td>
        <div className="flex items-start gap-1.5">
          {/*
            * Le dépliant est un bouton propre plutôt qu'un clic sur la ligne : la ligne porte déjà
            * trois destinations (le topic, sa source, un bucket de chaque courbe), et une ligne
            * cliquable par-dessus tout ça rend imprévisible ce que fait un clic.
            */}
          <button
            type="button"
            onClick={onToggle}
            aria-expanded={opened}
            aria-controls={panelId}
            aria-label={`${opened ? 'Hide' : 'Show'} what is arriving in ${row.topic} and who drains it`}
            className="inline-flex items-center justify-center min-w-6 min-h-6 -ml-1 rounded text-outline hover:text-on-surface transition-colors"
          >
            <span aria-hidden="true" className="material-symbols-outlined text-[18px]">
              {opened ? 'expand_more' : 'chevron_right'}
            </span>
          </button>
          <div className="min-w-0">
        <Link to={`/topic/${encodeURIComponent(row.topic)}`} className="text-on-surface hover:text-primary transition-colors">
          {row.topic}
        </Link>
        <div className="flex items-center gap-1.5 mt-1">
          <Badge tone={row.kind === 'RETRY' ? 'secondary' : 'primary'}>{row.kind}</Badge>
          {/*
            * L'appariement est toujours expliqué, y compris quand il a réussi : une source
            * *inférée* du voisinage porte tout le taux d'échec de la ligne, et la présenter comme
            * un fait déduit du nom serait la seule chose que cet écran ne doit pas faire.
            */}
          <Tooltip content={pairing.detail}>
            {source ? (
              <span className="text-[11px] text-outline">
                <Link to={`/topic/${encodeURIComponent(source)}`} className="hover:text-primary transition-colors">
                  {pairing.label}
                </Link>
              </span>
            ) : (
              <span className="text-[11px] text-outline">{pairing.label}</span>
            )}
          </Tooltip>
        </div>
          </div>
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
          source={source}
          series={share}
          activity={activity ?? null}
          loading={loading}
        />
      </Td>
      <Td className="text-right tabular-nums text-on-surface-variant">
        {size === undefined ? '—' : size.toLocaleString()}
      </Td>
    </TableRow>
    {opened && (
      <TableRow>
        {/* Monté seulement à l'ouverture : c'est le montage qui déclenche les deux lectures, donc
            un panneau simplement masqué les paierait quand même. */}
        <Td colSpan={5} className="p-0" id={panelId}>
          <QueueDetailPanel topic={row.topic} source={source} />
        </Td>
      </TableRow>
    )}
    </>
  );
};

export default DeadLetter;
