// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import type { FC, ReactNode } from 'react';
import { cn } from './cn';

export interface StatProps {
  label: ReactNode;
  value: ReactNode;
  /** Détail secondaire sous la valeur (tendance, unité, lien). */
  hint?: ReactNode;
  /** Icône Material Symbols optionnelle, alignée à droite du label. */
  icon?: string;
  /** Accent de la bordure supérieure. */
  tone?: 'none' | 'primary' | 'secondary' | 'success' | 'warning' | 'error';
  loading?: boolean;
  /** `lg` : la tuile principale d'une composition en bento — valeur plus grande, plus d'air. */
  size?: 'md' | 'lg';
  /** Élément à droite de la valeur (une courbe, par exemple) ; masqué pendant le chargement. */
  aside?: ReactNode;
  className?: string;
}

const TONES = {
  none: '',
  primary: 'border-t-2 border-t-primary/70',
  secondary: 'border-t-2 border-t-secondary/70',
  success: 'border-t-2 border-t-success/70',
  warning: 'border-t-2 border-t-warning/70',
  error: 'border-t-2 border-t-error/70',
} as const;

const ICON_TONES = {
  none: 'text-on-surface-variant',
  primary: 'text-primary',
  secondary: 'text-secondary',
  success: 'text-success',
  warning: 'text-warning',
  error: 'text-error',
} as const;

/** Tuile de métrique : étiquette discrète + valeur en chiffres tabulaires. */
export const Stat: FC<StatProps> = ({ label, value, hint, icon, tone = 'none', loading = false, size = 'md', aside, className }) => (
  <div className={cn('kse-surface kse-stat rounded-[var(--radius-card)]', size === 'lg' ? 'p-5 sm:p-6' : 'p-4 sm:p-5', TONES[tone], className)}>
    <div className="flex items-center justify-between mb-2">
      <p className="text-[11px] font-medium uppercase tracking-[0.05em] text-on-surface-variant">{label}</p>
      {icon && <span aria-hidden="true" className={cn('material-symbols-outlined text-[18px]', ICON_TONES[tone])}>{icon}</span>}
    </div>
    {loading ? (
      <div className="skeleton-shimmer h-8 w-16" />
    ) : (
      <div className="flex items-end justify-between gap-4">
        <p className={cn('font-semibold tracking-tight text-on-surface tabular-nums',
          size === 'lg' ? 'text-[40px] leading-[44px]' : 'text-[28px] leading-8')}>{value}</p>
        {aside}
      </div>
    )}
    {hint && !loading && <div className="text-[12px] text-on-surface-variant mt-1.5">{hint}</div>}
  </div>
);

export default Stat;
