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

/* ── Blocs de squelette calqués sur les primitives du design system ──
   Utilisés à la place des spinners pleine zone : la page apparaît dans sa
   forme finale dès le chargement (meilleure performance perçue). */

/** Tuile de métrique en chargement (même gabarit que <Stat>). */
export const StatSkeleton: FC = () => (
  <div className="bg-surface-container rounded-xl ring-1 ring-white/[0.045] p-5" aria-hidden="true">
    <div className="skeleton-shimmer h-3 w-20 mb-3" />
    <div className="skeleton-shimmer h-7 w-16" />
  </div>
);

export interface SkeletonGridProps {
  count?: number;
  columns?: string;
  className?: string;
}

/** Rangée de tuiles de métrique. */
export const StatGridSkeleton: FC<SkeletonGridProps> = ({ count = 4, columns = 'grid-cols-2 lg:grid-cols-4', className }) => (
  <div className={cn('grid gap-4', columns, className)} aria-hidden="true">
    {Array.from({ length: count }).map((_, i) => <StatSkeleton key={i} />)}
  </div>
);

export interface TableSkeletonProps {
  rows?: number;
  columns?: number;
  className?: string;
}

/** Table en chargement (carte + en-tête + lignes). */
export const TableSkeleton: FC<TableSkeletonProps> = ({ rows = 8, columns = 5, className }) => (
  <div className={cn('bg-surface-container rounded-xl ring-1 ring-white/[0.045] overflow-hidden', className)} aria-hidden="true" role="status" aria-label="Loading">
    <div className="bg-surface-container-high/60 px-4 py-3 flex gap-4">
      {Array.from({ length: columns }).map((_, i) => (
        <div key={i} className={cn('skeleton-shimmer h-3', i === 0 ? 'w-40' : 'w-20')} />
      ))}
    </div>
    <div className="divide-y divide-outline-variant/40">
      {Array.from({ length: rows }).map((_, r) => (
        <div key={r} className="px-4 py-3.5 flex gap-4 items-center">
          {Array.from({ length: columns }).map((_, c) => (
            <div key={c} className={cn('skeleton-shimmer h-3.5', c === 0 ? 'w-48' : c === columns - 1 ? 'w-10 ml-auto' : 'w-16')} />
          ))}
        </div>
      ))}
    </div>
  </div>
);

/** Carte de contenu en chargement. */
export const CardSkeleton: FC<{ className?: string; lines?: number }> = ({ className, lines = 3 }) => (
  <div className={cn('bg-surface-container rounded-xl ring-1 ring-white/[0.045] p-5', className)} aria-hidden="true">
    <div className="skeleton-shimmer h-4 w-1/3 mb-4" />
    <SkeletonText lines={lines} />
  </div>
);

export default Skeleton;
