// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React, { useMemo, useState } from 'react';
import type { TopicActivity } from '../../api/types';
import { bucketLabel } from '../../pages/topicActivity';
import {
  describeShare, formatPercent, shareShape, SHARE_SCALE_FLOOR, type ShareSeries,
} from '../../pages/deadLetterSupervision';

const WIDTH = 108;
const HEIGHT = 26;
const PADDING = 2;

export interface ShareSparklineProps {
  /** La file d'échec — c'est elle que la courbe décrit. */
  topic: string;
  /** Le topic source apparié, ou `null` : la courbe n'existe alors pas, et le dit. */
  source: string | null;
  series: ShareSeries;
  /** La série de la file, pour dater un bucket au survol. */
  activity?: TopicActivity | null;
  loading?: boolean;
}

/**
 * La seconde courbe d'une ligne : la part du trafic de la source qui a fini ici.
 *
 * Trois choses la distinguent de la sparkline du tableau de bord, et chacune vient de ce qu'elle
 * trace un **rapport** et non un compte :
 *
 * 1. **Elle est cassée là où le rapport n'existe pas.** Un bucket où la source n'a rien produit
 *    n'a pas de taux d'échec ; tracé à zéro il affirmerait « rien n'a échoué », qui est l'inverse
 *    de « on ne sait pas ». Le trou est grisé, comme la zone que la rétention a vidée l'est sur
 *    l'autre courbe — un vocabulaire visuel déjà en place.
 * 2. **Son échelle a un plancher** (`SHARE_SCALE_FLOOR`). Cadrer sur la pointe est juste pour des
 *    comptes, qui n'ont pas d'unité ; pour un pourcentage, cadrer sur un pic de 0,3 % dessinerait
 *    une montagne là où il n'y en a pas.
 * 3. **Elle vire au rouge, pas au bleu.** Sur cet écran une courbe qui monte est une mauvaise
 *    nouvelle, et la couleur primaire — celle de l'activité normale partout ailleurs — dirait le
 *    contraire.
 *
 * Elle ne mène nulle part au clic : le geste « voir les messages de ce pic » appartient à la
 * courbe des arrivées, qui est juste à côté et qui le fait déjà. Deux boutons par ligne pour la
 * même destination coûteraient un arrêt de tabulation sans rien ajouter.
 */
const ShareSparkline: React.FC<ShareSparklineProps> = ({ topic, source, series, activity, loading }) => {
  const [hovered, setHovered] = useState<number | null>(null);
  const shape = useMemo(
    () => (series.available ? shareShape(series.points, WIDTH, HEIGHT, PADDING) : null),
    [series],
  );

  if (loading) {
    return <div className="skeleton-shimmer h-[26px] w-[108px] rounded" aria-hidden="true" />;
  }

  const label = describeShare(series, topic, source);

  /*
   * Deux absences, deux mots. Une source absente est déjà nommée dans la cellule du topic, à un
   * centimètre d'ici : la répéter mettrait la même phrase deux fois sur la ligne, et c'est ainsi
   * qu'on apprend à ne plus la lire — le tiret suffit, l'énoncé accessible et l'infobulle portent
   * la raison. Une source présente mais aucun bucket comparable est l'autre cas, et celui-là n'est
   * dit nulle part ailleurs.
   */
  if (!shape) {
    return (
      <span
        className="inline-flex items-center h-[26px] w-[108px] text-[11px] text-outline"
        role="img"
        aria-label={label}
        title={label}
      >
        {source ? 'not comparable' : '—'}
      </span>
    );
  }

  const step = series.points.length > 1 ? (WIDTH - PADDING * 2) / (series.points.length - 1) : 0;
  const marked = hovered !== null ? shape.points[hovered] : null;
  const hoveredValue = hovered !== null ? series.points[hovered] : null;

  const bucketAt = (clientX: number, target: Element): number => {
    const box = target.getBoundingClientRect();
    if (box.width <= 0 || step <= 0) return series.peakIndex;
    const ratio = (clientX - box.left) / box.width;
    return Math.min(series.points.length - 1, Math.max(0, Math.round(ratio * (series.points.length - 1))));
  };

  return (
    <div className="flex items-center gap-2">
      <span
        role="img"
        aria-label={label}
        title={label}
        onPointerMove={e => setHovered(bucketAt(e.clientX, e.currentTarget))}
        onPointerLeave={() => setHovered(null)}
      >
        <svg
          width={WIDTH}
          height={HEIGHT}
          viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
          aria-hidden="true"
          className="block align-middle"
        >
          {shape.gaps.map((gap, i) => (
            <rect key={i} x={gap.x} y={0} width={gap.width} height={HEIGHT} className="fill-outline-variant/40" />
          ))}
          {/* Le haut de la boîte, quand il est le plancher : ça dit « sous 1 %, la courbe est
              écrasée exprès » sans écrire une légende par ligne. */}
          {shape.top === SHARE_SCALE_FLOOR && (
            <line
              x1={0} y1={PADDING} x2={WIDTH} y2={PADDING}
              className="stroke-outline-variant/60" strokeWidth={0.75} strokeDasharray="2 3"
            />
          )}
          {shape.segments.map((d, i) => (
            <path
              key={i}
              d={d}
              className="stroke-error"
              strokeWidth={1.25}
              strokeLinejoin="round"
              strokeLinecap="round"
              fill="none"
            />
          ))}
          {shape.dots.map((dot, i) => (
            <circle key={i} cx={dot.x} cy={dot.y} r={1.5} className="fill-error" />
          ))}
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
      </span>
      {/* Largeur fixe, même raison que sur la courbe voisine : le libellé change au survol. */}
      <span className="w-[7rem] shrink-0 text-[11px] tabular-nums text-on-surface-variant" aria-hidden="true">
        {hovered !== null ? (
          <span title={activity ? bucketLabel(activity, hovered) : undefined}>
            {hoveredValue === null ? <span className="text-outline">no traffic</span> : formatPercent(hoveredValue)}
            {activity && (
              <span className="text-outline"> · {bucketLabel(activity, hovered).split('–')[0]}</span>
            )}
          </span>
        ) : series.overall === null ? (
          <span className="text-outline">—</span>
        ) : (
          <>
            <span title={`Over the whole window: ${formatPercent(series.overall)} of what ${source} produced.`}>
              {formatPercent(series.overall)}
            </span>
            {series.peak !== null && series.peak > series.overall && (
              <span className="ml-1.5 text-outline" title={`Worst bucket: ${formatPercent(series.peak)}.`}>
                ▲ {formatPercent(series.peak)}
              </span>
            )}
          </>
        )}
      </span>
    </div>
  );
};

export default React.memo(ShareSparkline);
