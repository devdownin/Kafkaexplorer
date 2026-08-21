// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useEffect, useMemo, useState } from 'react';
import type { FC } from 'react';
import axios from 'axios';
import { Badge, EmptyState, ErrorPanel, Select, Skeleton } from '../ui';
import { describeApiError, type QueryErrorInfo } from '../../pages/queryError';
import type { TopicActivity, TopicActivityResponse } from '../../api/types';
import {
  ACTIVITY_WINDOWS, axisTicks, bucketLabel, describeActivity, describeRate, describeScale,
  describeSilence, describeTrend, detectSilence, detectTrend, explainTrend, formatSpan,
  readActivityScale, sparkline, unmeasuredLeadingBuckets, windowById, writeActivityScale,
  type ActivityScale, type ActivityWindow,
} from '../../pages/topicActivity';

/** Le graphe est tracé dans ce repère puis étiré horizontalement : voir `preserveAspectRatio`. */
const VIEW_W = 1000;
const VIEW_H = 120;
const PADDING = 4;
const TIMEOUT_MS = 20000;
const WINDOW_KEY = 'kse:topic-activity-window';

export interface TopicActivityPanelProps {
  topic: string;
  /**
   * Appelé avec le début du bucket choisi. Le panneau ne sait pas ce que la page en fera — ici,
   * régler le départ de la recherche et basculer sur les messages.
   */
  onPickInstant?: (ms: number) => void;
}

function readWindow(): ActivityWindow {
  try {
    const stored = localStorage.getItem(WINDOW_KEY);
    if (stored) return windowById(stored) ?? ACTIVITY_WINDOWS[1];
  } catch {
    /* mode privé, quota : le défaut fait l'affaire */
  }
  return ACTIVITY_WINDOWS[1];
}

/**
 * Ce que ce topic a produit, dans le temps.
 *
 * La page savait ce qu'il y a dans le topic, qui le lit et à quel retard — pas la forme de sa
 * production, qui est pourtant la première chose qu'on regarde en arrivant sur un incident. Le
 * tableau de bord porte la même mesure dans une cellule ; ici il y a la place d'un axe, d'un
 * survol lisible et d'un clic qui **amorce la recherche** à l'instant choisi : voir un pic et lire
 * ce qu'il contenait redevient un geste au lieu de deux écrans.
 *
 * Monté seulement quand l'onglet est ouvert, comme le panneau des consommateurs et pour la même
 * raison : la mesure coûte des allers-retours au broker, et personne n'arrive ici pour la payer
 * sans l'avoir demandée.
 */
const TopicActivityPanel: FC<TopicActivityPanelProps> = ({ topic, onPickInstant }) => {
  const [window_, setWindow] = useState<ActivityWindow>(readWindow);
  // L'échelle est une préférence de *lecture*, partagée avec le tableau de bord : c'est la même
  // question posée sur la même courbe, et deux réglages pour un geste dérivent.
  const [scale, setScale] = useState<ActivityScale>(readActivityScale);
  /**
   * La réponse *et* la question qu'elle répond. Sans la clé, changer de fenêtre laisserait le
   * graphe précédent sous le nouveau libellé le temps d'un aller-retour — et « en cours de
   * chargement » se déduit de la même comparaison, au lieu d'être un état posé dans l'effet.
   */
  const [result, setResult] = useState<{
    key: string;
    data: TopicActivityResponse | null;
    error: QueryErrorInfo | null;
  } | null>(null);
  const [hovered, setHovered] = useState<number | null>(null);

  const key = `${topic}|${window_.id}`;
  const shown = result?.key === key ? result : null;
  const loading = !shown;

  useEffect(() => {
    const controller = new AbortController();
    let dropped = false;
    axios.get<TopicActivityResponse>('/api/dashboard/activity', {
      params: { topics: topic, windowMs: window_.windowMs, buckets: window_.buckets },
      signal: controller.signal,
      timeout: TIMEOUT_MS,
    })
      .then(response => {
        if (!dropped) setResult({ key, data: response.data, error: null });
      })
      .catch(e => {
        if (dropped || axios.isCancel(e)) return;
        // La raison prend la place du graphe : une courbe plate dirait « aucun trafic », qui est
        // une réponse, sur une question à laquelle rien n'a répondu.
        setResult({ key, data: null, error: describeApiError(e, 'Activity could not be read') });
      });
    return () => {
      dropped = true;
      controller.abort();
    };
  }, [topic, window_, key]);

  const data = shown?.data ?? null;
  const error = shown?.error ?? null;
  const activity: TopicActivity | null = data?.topics[topic] ?? null;
  const shape = useMemo(
    () => (activity?.available ? sparkline(activity.counts, VIEW_W, VIEW_H, PADDING, scale) : null),
    [activity, scale],
  );
  const silence = useMemo(() => detectSilence(activity), [activity]);
  const trend = useMemo(() => (silence ? null : detectTrend(activity)), [activity, silence]);

  const controls = (
    <div className="flex flex-wrap items-center gap-3 text-[12px] text-on-surface-variant">
      <div className="flex items-center gap-1.5">
        <label htmlFor="topic-activity-window">Window</label>
        <Select
          id="topic-activity-window"
          value={window_.id}
          onChange={e => {
            const next = windowById(e.target.value) ?? ACTIVITY_WINDOWS[1];
            setWindow(next);
            try { localStorage.setItem(WINDOW_KEY, next.id); } catch { /* confort, pas condition */ }
          }}
          className="h-9 w-auto"
        >
          {ACTIVITY_WINDOWS.map(w => <option key={w.id} value={w.id}>{w.label}</option>)}
        </Select>
      </div>
      <div className="flex items-center gap-1.5">
        <label htmlFor="topic-activity-scale">Scale</label>
        <Select
          id="topic-activity-scale"
          value={scale}
          onChange={e => {
            const next = e.target.value as ActivityScale;
            setScale(next);
            writeActivityScale(next);
          }}
          className="h-9 w-auto"
        >
          <option value="linear">Linear</option>
          <option value="log">Log</option>
        </Select>
      </div>
    </div>
  );

  if (loading) {
    return (
      <div className="space-y-3">
        {controls}
        <Skeleton className="h-[120px] w-full rounded" />
      </div>
    );
  }

  if (error) {
    return (
      <div className="space-y-3">
        {controls}
        <ErrorPanel error={error} />
      </div>
    );
  }

  if (!activity || !activity.available || !shape) {
    return (
      <div className="space-y-3">
        {controls}
        <EmptyState
          icon="timeline"
          title="Activity could not be measured"
          description={activity?.note
            ?? data?.warnings[0]
            ?? 'The broker did not answer for this topic.'}
        />
      </div>
    );
  }

  const counts = activity.counts;
  const unmeasured = unmeasuredLeadingBuckets(activity);
  const step = counts.length > 1 ? (VIEW_W - PADDING * 2) / (counts.length - 1) : 0;
  const unmeasuredWidth = unmeasured > 0 ? Math.min(VIEW_W, PADDING + unmeasured * step) : 0;
  const ticks = axisTicks(activity, activity.windowEndMs - activity.windowStartMs > 24 * 3_600_000 ? 4 : 5);
  const target = hovered ?? shape.peakIndex;
  const marked = shape.points[target];

  const bucketAt = (clientX: number, element: Element): number => {
    const box = element.getBoundingClientRect();
    if (box.width <= 0 || counts.length < 2) return shape.peakIndex;
    const ratio = (clientX - box.left) / box.width;
    return Math.min(counts.length - 1, Math.max(0, Math.round(ratio * (counts.length - 1))));
  };

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        {controls}
        <div className="flex flex-wrap items-center gap-2 text-[12px] text-on-surface-variant tabular-nums">
          <span>{activity.total.toLocaleString()} messages</span>
          <span className="text-outline">·</span>
          <span>peak {describeRate(shape.peak, activity.bucketMs)}</span>
          {silence && <Badge tone="warning">{describeSilence(silence)}</Badge>}
          {trend && (
            <Badge tone={trend.direction === 'up' ? 'primary' : 'neutral'}>
              {describeTrend(trend)} vs median
            </Badge>
          )}
        </div>
      </div>

      {/* Ce que le survol désigne, au-dessus du graphe : à cette taille il y a la place de
          l'écrire, là où la cellule du tableau doit se contenter d'un chiffre. */}
      <p className="text-[12px] text-on-surface-variant tabular-nums h-4">
        {hovered !== null && (
          <>
            <span className="text-on-surface font-medium">{counts[hovered].toLocaleString()}</span>
            {' '}messages · {bucketLabel(activity, hovered)}
            {onPickInstant && <span className="text-outline"> · click to search from there</span>}
          </>
        )}
      </p>

      <button
        type="button"
        className="block w-full rounded-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
        onPointerMove={e => setHovered(bucketAt(e.clientX, e.currentTarget))}
        onPointerLeave={() => setHovered(null)}
        onBlur={() => setHovered(null)}
        onClick={() => onPickInstant?.(activity.windowStartMs + target * activity.bucketMs)}
        aria-label={`${describeActivity(activity, topic)}${onPickInstant
          ? ` Opens the search at ${bucketLabel(activity, target)}.` : ''}`}
      >
        <svg
          width="100%"
          height={VIEW_H}
          viewBox={`0 0 ${VIEW_W} ${VIEW_H}`}
          /*
           * Étiré horizontalement pour occuper la largeur disponible : c'est l'axe du temps, donc
           * l'étirement est ce qu'on veut. `non-scaling-stroke` garde le trait à son épaisseur
           * réelle malgré la déformation, et c'est aussi pourquoi rien n'est dessiné ici avec un
           * cercle — il deviendrait une ellipse.
           */
          preserveAspectRatio="none"
          aria-hidden="true"
          className="block"
        >
          {unmeasuredWidth > 0 && (
            <rect x={0} y={0} width={unmeasuredWidth} height={VIEW_H} className="fill-outline-variant/40" />
          )}
          {shape.flat ? (
            <path d={shape.line} className="stroke-outline-variant" strokeWidth={1.5}
                  vectorEffect="non-scaling-stroke" fill="none" />
          ) : (
            <>
              <path d={shape.area} className="fill-primary/15" />
              <path
                d={shape.line}
                className="stroke-primary"
                strokeWidth={1.5}
                strokeLinejoin="round"
                vectorEffect="non-scaling-stroke"
                fill="none"
              />
            </>
          )}
          {/*
            * Seulement au survol : un trait permanent sur le pic n'est étiqueté par rien — à cette
            * largeur, écrire dans ce SVG le déformerait — et se lirait comme « maintenant ». Le
            * pic est dit en toutes lettres dans la pastille, et l'énoncé accessible nomme le
            * bucket que la touche Entrée ouvrirait.
            */}
          {hovered !== null && (
            <line
              x1={marked.x} y1={0} x2={marked.x} y2={VIEW_H}
              className="stroke-on-surface-variant" strokeWidth={1} vectorEffect="non-scaling-stroke"
            />
          )}
        </svg>
      </button>

      <div className="relative h-4 text-[11px] text-outline tabular-nums">
        {ticks.map((tick, i) => (
          <span
            key={tick.percent}
            className="absolute whitespace-nowrap"
            style={{
              left: `${tick.percent}%`,
              transform: i === 0 ? 'none' : i === ticks.length - 1 ? 'translateX(-100%)' : 'translateX(-50%)',
            }}
          >
            {tick.label}
          </span>
        ))}
      </div>

      <p className="text-[11px] text-on-surface-variant">
        One point per {formatSpan(activity.bucketMs)}
        {describeScale(scale) && ` · ${describeScale(scale)}`}
        {' '}· counted from offsets, so a bucket counts what was produced, not what the topic holds now.
        {trend && ` ${explainTrend(trend, activity.bucketMs)}`}
      </p>
      {(data?.warnings ?? []).map(warning => (
        <p key={warning} className="text-[11px] text-warning">{warning}</p>
      ))}
      {activity.note && <p className="text-[11px] text-warning">{activity.note}</p>}
    </div>
  );
};

export default TopicActivityPanel;
