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
export const Stat: FC<StatProps> = ({ label, value, hint, icon, tone = 'none', loading = false, className }) => (
  <div className={cn('bg-surface-container rounded-xl ring-1 ring-white/[0.045] p-5', TONES[tone], className)}>
    <div className="flex items-center justify-between mb-2">
      <p className="text-[11px] font-medium uppercase tracking-[0.05em] text-on-surface-variant">{label}</p>
      {icon && <span aria-hidden="true" className={cn('material-symbols-outlined text-[18px]', ICON_TONES[tone])}>{icon}</span>}
    </div>
    {loading ? (
      <div className="skeleton-shimmer h-8 w-16" />
    ) : (
      <p className="text-[28px] leading-8 font-semibold tracking-tight text-on-surface tabular-nums">{value}</p>
    )}
    {hint && !loading && <div className="text-[12px] text-on-surface-variant mt-1.5">{hint}</div>}
  </div>
);

export default Stat;
