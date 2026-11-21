// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import type { FC, ReactNode } from 'react';
import { cn } from './cn';

export interface EmptyStateProps {
  /** Nom d'icône Material Symbols. */
  icon?: string;
  title: ReactNode;
  description?: ReactNode;
  /** Action principale (typiquement un <Button>). */
  action?: ReactNode;
  className?: string;
}

/** État vide : guide l'utilisateur vers la prochaine action utile. */
export const EmptyState: FC<EmptyStateProps> = ({ icon = 'inbox', title, description, action, className }) => (
  <div className={cn('kse-empty-state flex flex-col items-center justify-center text-center py-12 sm:py-16 px-5 sm:px-8', className)}>
    <div className="kse-empty-state-icon w-12 h-12 rounded-2xl bg-surface-container-high flex items-center justify-center mb-4">
      <span aria-hidden="true" className="material-symbols-outlined text-[22px] text-on-surface-variant">{icon}</span>
    </div>
    <h3 className="text-[15px] font-semibold text-on-surface">{title}</h3>
    {description && (
      <p className="text-[13px] text-on-surface-variant mt-2 max-w-sm leading-relaxed">{description}</p>
    )}
    {action && <div className="mt-4">{action}</div>}
  </div>
);

export default EmptyState;
