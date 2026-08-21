// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React, { useMemo } from 'react';
import type { TopicActivity } from '../../api/types';
import { sparkline, unmeasuredLeadingBuckets, isFloor, describeActivity } from '../../pages/topicActivity';

const WIDTH = 108;
const HEIGHT = 26;

interface SparklineProps {
  topic: string;
  /** `undefined` : pas encore chargé. `null` : le serveur n'a rien renvoyé pour ce topic. */
  activity?: TopicActivity | null;
  /** Vrai tant que la passe est en vol — un squelette, jamais une courbe plate provisoire. */
  loading?: boolean;
}

/**
 * La courbe d'activité d'un topic, dans une cellule de tableau.
 *
 * Trois choix méritent d'être écrits :
 *
 * 1. **`role="img"` + `aria-label`, pas une `Tooltip`.** Le texte complet est indispensable — une
 *    image sans énoncé n'apprend rien à qui ne la voit pas — mais `Tooltip` ajoute un arrêt de
 *    tabulation par cellule, soit vingt-cinq sur une page, ce que cette base de code refuse
 *    explicitement dans les tableaux. Le `<title>` donne le survol, `aria-label` donne l'énoncé,
 *    et la tabulation reste celle du tableau.
 * 2. **L'échelle est propre à chaque ligne** (`sparkline`), donc deux courbes ne se comparent pas
 *    en hauteur. La pointe est chiffrée dans l'énoncé pour cette raison précise.
 * 3. **Rien n'est dessiné là où rien n'a été mesuré.** La zone que la rétention a vidée est
 *    grisée plutôt que tracée à zéro, et une série qui est un plancher (une partition muette) est
 *    pointillée : une courbe pleine est une affirmation.
 */
const Sparkline: React.FC<SparklineProps> = ({ topic, activity, loading }) => {
  const shape = useMemo(
    () => (activity?.available ? sparkline(activity.counts, WIDTH, HEIGHT, 2) : null),
    [activity],
  );

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

  const unmeasured = unmeasuredLeadingBuckets(activity);
  const floor = isFloor(activity);
  const step = activity.counts.length > 1 ? (WIDTH - 4) / (activity.counts.length - 1) : 0;
  const unmeasuredWidth = unmeasured > 0 ? Math.min(WIDTH, 2 + unmeasured * step) : 0;
  const last = shape.points[shape.points.length - 1];

  return (
    <svg
      width={WIDTH}
      height={HEIGHT}
      viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
      role="img"
      aria-label={label}
      className="overflow-visible align-middle"
    >
      <title>{label}</title>
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
    </svg>
  );
};

export default React.memo(Sparkline);
