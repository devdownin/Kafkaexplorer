// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React, { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { TopicActivity } from '../../api/types';
import {
  bucketLabel, bucketLink, compact, describeActivity, describeBucketLink, describeRate,
  describeSilence, describeTrend, detectSilence, detectTrend, explainTrend, isFloor, sparkline,
  unmeasuredLeadingBuckets, type ActivityScale,
} from '../../pages/topicActivity';

const WIDTH = 108;
const HEIGHT = 26;
const PADDING = 2;

interface SparklineProps {
  topic: string;
  /** `undefined` : pas encore chargé. `null` : le serveur n'a rien renvoyé pour ce topic. */
  activity?: TopicActivity | null;
  /** Vrai tant que la passe est en vol — un squelette, jamais une courbe plate provisoire. */
  loading?: boolean;
  /** L'échelle verticale. Le pic et l'énoncé accessible restent des valeurs brutes. */
  scale?: ActivityScale;
}

/**
 * La courbe d'activité d'un topic, dans une cellule de tableau.
 *
 * Quatre choix méritent d'être écrits :
 *
 * 1. **La courbe est un bouton, et le clic mène aux messages.** Voir un pic et lire ce qu'il y
 *    avait dedans sont la même question posée deux fois ; jusqu'ici la seconde moitié se faisait
 *    à la main. Cliquer un bucket ouvre l'explorateur de ce topic avec la recherche amorcée à
 *    cette heure-là, au clavier comme à la souris — un bouton, donc `Enter` fonctionne, et il
 *    ouvre alors le **pic**, qui est justement ce que l'énoncé accessible nomme. Ça ajoute un
 *    arrêt de tabulation par ligne, ce que cette base de code refuse pour une *infobulle* : la
 *    différence est qu'il y a une action derrière, comme le lien « Explore » de la même ligne.
 * 2. **Le chiffre est à côté de la courbe, pas seulement au survol.** L'échelle est propre à
 *    chaque ligne, donc deux courbes de même hauteur peuvent valoir 40/h et 40 000/h : le pic
 *    n'était lisible qu'en survolant, c'est-à-dire à la souris — exactement ce qu'on reproche
 *    ailleurs à `title=""`. Il est écrit, et le survol d'un bucket remplace ce chiffre par la
 *    valeur de ce bucket plutôt que d'ouvrir une infobulle de plus.
 * 3. **Un topic qui s'est tu le dit** (`detectSilence`) à la place du pic : « silent 4 h+ » est
 *    plus actionnable que « pic 620 » sur une courbe retombée à zéro, et le pic reste dans
 *    l'énoncé accessible.
 * 4. **Rien n'est dessiné là où rien n'a été mesuré.** La zone que la rétention a vidée est grisée
 *    plutôt que tracée à zéro, et une série qui est un plancher (une partition muette) est
 *    pointillée : une courbe pleine est une affirmation.
 */
const Sparkline: React.FC<SparklineProps> = ({ topic, activity, loading, scale = 'linear' }) => {
  const navigate = useNavigate();
  const [hovered, setHovered] = useState<number | null>(null);
  const shape = useMemo(
    () => (activity?.available ? sparkline(activity.counts, WIDTH, HEIGHT, PADDING, scale) : null),
    [activity, scale],
  );
  const silence = useMemo(() => detectSilence(activity), [activity]);
  /*
   * Le régime courant, et seulement quand il s'écarte du sien. Il répond à une question que ni la
   * forme ni le pic ne posent — « est-ce que ça tourne au-dessus de son ordinaire, là ? » — et il
   * s'efface devant le silence, qui décrit le même dernier bucket en plus actionnable.
   */
  const trend = useMemo(() => (detectSilence(activity) ? null : detectTrend(activity)), [activity]);

  if (loading && !activity) {
    return <div className="skeleton-shimmer h-[26px] w-[108px] rounded" aria-hidden="true" />;
  }

  const label = describeActivity(activity, topic);

  if (!activity || !activity.available || !shape) {
    return (
      <span
        className="inline-flex items-center h-[26px] w-[108px] text-[11px] text-outline"
        role="img"
        aria-label={label}
        title={label}
      >
        {activity && !activity.available ? 'not measured' : '—'}
      </span>
    );
  }

  const counts = activity.counts;
  const unmeasured = unmeasuredLeadingBuckets(activity);
  const floor = isFloor(activity);
  const step = counts.length > 1 ? (WIDTH - PADDING * 2) / (counts.length - 1) : 0;
  const unmeasuredWidth = unmeasured > 0 ? Math.min(WIDTH, PADDING + unmeasured * step) : 0;
  const last = shape.points[shape.points.length - 1];
  const marked = hovered !== null ? shape.points[hovered] : null;

  /** Le bucket sous le pointeur. Un clic au clavier n'en désigne aucun : c'est le pic qui s'ouvre. */
  const bucketAt = (clientX: number, target: Element): number => {
    const box = target.getBoundingClientRect();
    if (box.width <= 0 || step <= 0) return shape.peakIndex;
    const ratio = (clientX - box.left) / box.width;
    return Math.min(counts.length - 1, Math.max(0, Math.round(ratio * (counts.length - 1))));
  };

  const target = hovered ?? shape.peakIndex;
  const open = () => navigate(bucketLink(topic, activity, target));

  return (
    <div className="flex items-center gap-2">
      <button
        type="button"
        onClick={open}
        onPointerMove={e => setHovered(bucketAt(e.clientX, e.currentTarget))}
        onPointerLeave={() => setHovered(null)}
        onBlur={() => setHovered(null)}
        aria-label={`${label} ${describeBucketLink(activity, target)}`}
        title={label}
        className="rounded-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
      >
        <svg
          width={WIDTH}
          height={HEIGHT}
          viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
          aria-hidden="true"
          className="block align-middle"
        >
          {unmeasuredWidth > 0 && (
            <rect x={0} y={0} width={unmeasuredWidth} height={HEIGHT} className="fill-outline-variant/50" />
          )}
          {shape.flat ? (
            // Une série entièrement nulle : la ligne de base, en neutre. Tracée en primaire elle se
            // lirait comme une activité constante et faible, ce qu'elle n'est pas.
            <path d={shape.line} className="stroke-outline-variant" strokeWidth={1.25} fill="none" />
          ) : (
            <>
              <path d={shape.area} className="fill-primary/15" />
              <path
                d={shape.line}
                className="stroke-primary"
                strokeWidth={1.25}
                strokeLinejoin="round"
                strokeLinecap="round"
                strokeDasharray={floor ? '3 2' : undefined}
                fill="none"
              />
              <circle cx={last.x} cy={last.y} r={1.75} className="fill-primary" />
            </>
          )}
          {marked && (
            <>
              <line
                x1={marked.x} y1={0} x2={marked.x} y2={HEIGHT}
                className="stroke-on-surface-variant/40" strokeWidth={1}
              />
              <circle cx={marked.x} cy={marked.y} r={2.25} className="fill-on-surface" />
            </>
          )}
        </svg>
      </button>
      {/*
        * Largeur fixe : le libellé change au survol, et une cellule qui se réajuste sous le
        * pointeur ferait bouger la colonne entière ligne par ligne.
        */}
      <span className="w-[7rem] shrink-0 text-[11px] tabular-nums text-on-surface-variant" aria-hidden="true">
        {hovered !== null ? (
          <span title={`${bucketLabel(activity, hovered)} — ${counts[hovered].toLocaleString()}`}>
            {compact(counts[hovered])}
            <span className="text-outline"> · {bucketLabel(activity, hovered).split('–')[0]}</span>
          </span>
        ) : silence ? (
          <span className="text-warning">{describeSilence(silence)}</span>
        ) : shape.flat ? (
          <span className="text-outline">no traffic</span>
        ) : (
          <>
            <span title={`Peak: ${shape.peak.toLocaleString()}`}>{describeRate(shape.peak, activity.bucketMs)}</span>
            {trend && (
              <span
                className={`ml-1.5 ${trend.direction === 'up' ? 'text-primary' : 'text-outline'}`}
                title={explainTrend(trend, activity.bucketMs)}
              >
                {describeTrend(trend)}
              </span>
            )}
          </>
        )}
      </span>
    </div>
  );
};

export default React.memo(Sparkline);
