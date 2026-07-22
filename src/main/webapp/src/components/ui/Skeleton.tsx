import type { FC } from 'react';
import { cn } from './cn';

export interface SkeletonProps {
  className?: string;
}

/** Bloc de chargement animé (shimmer). Compose des `h-*`/`w-*` via className. */
export const Skeleton: FC<SkeletonProps> = ({ className }) => (
  <div aria-hidden="true" className={cn('skeleton-shimmer', className)} />
);

export interface SkeletonTextProps {
  lines?: number;
  className?: string;
}

/** Plusieurs lignes de texte en chargement (la dernière raccourcie). */
export const SkeletonText: FC<SkeletonTextProps> = ({ lines = 3, className }) => (
  <div className={cn('space-y-2', className)} aria-hidden="true">
    {Array.from({ length: lines }).map((_, i) => (
      <div key={i} className={cn('skeleton-shimmer h-3.5', i === lines - 1 ? 'w-2/3' : 'w-full')} />
    ))}
  </div>
);

export default Skeleton;
