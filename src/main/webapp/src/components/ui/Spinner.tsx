// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import type { FC } from 'react';
import { cn } from './cn';

export interface SpinnerProps {
  size?: number;
  className?: string;
  /** Libellé accessible (annoncé aux lecteurs d'écran). */
  label?: string;
}

/** Indicateur de chargement indéterminé (icône rotative). */
export const Spinner: FC<SpinnerProps> = ({ size = 20, className, label = 'Loading' }) => (
  <span role="status" aria-live="polite" className={cn('inline-flex items-center text-primary', className)}>
    <span
      aria-hidden="true"
      className="material-symbols-outlined animate-spin leading-none"
      style={{ fontSize: size }}
    >
      progress_activity
    </span>
    <span className="sr-only">{label}</span>
  </span>
);

/** Barre de progression indéterminée horizontale (chargements de page). */
export const ProgressBar: FC<{ className?: string; label?: string }> = ({ className, label = 'Loading' }) => (
  <div className={cn('flex flex-col items-center justify-center gap-3', className)} role="status" aria-live="polite">
    <div className="w-16 h-1 rounded-full bg-primary/15 relative overflow-hidden">
      <div className="absolute inset-0 rounded-full bg-primary animate-progress-fast" />
    </div>
    <span className="text-[13px] text-on-surface-variant">{label}…</span>
  </div>
);

export default Spinner;
