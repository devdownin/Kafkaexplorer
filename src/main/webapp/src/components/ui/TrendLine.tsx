// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import type { FC } from 'react';
import { cn } from './cn';

export interface TrendLineProps {
  values: number[];
  /** Le nom accessible : ce que la courbe montre, en une phrase. */
  label: string;
  width?: number;
  height?: number;
  className?: string;
}

/**
 * Courbe sans axes pour une tuile : la forme, pas la lecture. Moins de deux points ne dessinent
 * rien — une ligne d'un point serait un tiret qui prétend être une tendance. Une série constante
 * est tracée à mi-hauteur plutôt que d'être étirée en bruit sur toute la plage.
 */
export const TrendLine: FC<TrendLineProps> = ({ values, label, width = 160, height = 36, className }) => {
  if (values.length < 2) return null;
  const min = Math.min(...values);
  const span = Math.max(...values) - min;
  const pad = 3;
  const x = (i: number) => pad + (i * (width - 2 * pad)) / (values.length - 1);
  const y = (v: number) => span === 0 ? height / 2 : pad + (1 - (v - min) / span) * (height - 2 * pad);
  const line = values.map((v, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)} ${y(v).toFixed(1)}`).join(' ');
  const area = `${line} L${x(values.length - 1).toFixed(1)} ${height} L${x(0).toFixed(1)} ${height} Z`;
  return (
    <svg role="img" aria-label={label} viewBox={`0 0 ${width} ${height}`} width={width} height={height}
      className={cn('shrink-0 max-w-full', className)} preserveAspectRatio="none">
      {span !== 0 && <path d={area} fill="var(--viz-primary)" fillOpacity={0.1} />}
      <path d={line} fill="none" stroke="var(--viz-primary)" strokeWidth={1.6}
        strokeLinecap="round" strokeLinejoin="round" vectorEffect="non-scaling-stroke" />
      <circle cx={x(values.length - 1)} cy={y(values[values.length - 1])} r={2.4} fill="var(--viz-primary)" />
    </svg>
  );
};

export default TrendLine;
