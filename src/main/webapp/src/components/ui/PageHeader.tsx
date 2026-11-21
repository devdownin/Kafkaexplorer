// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import type { FC, ReactNode } from 'react';
import { cn } from './cn';

export interface PageHeaderProps {
  /** Overline optionnelle au-dessus du titre (contexte, catégorie). */
  kicker?: ReactNode;
  title: ReactNode;
  description?: ReactNode;
  /** Actions principales de la page, alignées à droite. */
  actions?: ReactNode;
  className?: string;
}

/** En-tête standard de page : hiérarchie unique pour toutes les vues. */
export const PageHeader: FC<PageHeaderProps> = ({ kicker, title, description, actions, className }) => (
  <header className={cn('kse-page-header flex flex-wrap items-start justify-between gap-4 md:gap-5', className)}>
    <div className="min-w-0 flex-1 basis-60">
      {kicker && (
        <p className="text-[11px] font-medium uppercase tracking-[0.05em] text-on-surface-variant mb-1">{kicker}</p>
      )}
      <h1 className="text-[23px] leading-tight sm:text-[26px] font-semibold tracking-tight text-on-surface">{title}</h1>
      {description && <p className="text-[13px] text-on-surface-variant mt-1.5 max-w-2xl leading-relaxed">{description}</p>}
    </div>
    {actions && <div className="kse-page-actions flex flex-wrap items-center gap-2 min-w-0">{actions}</div>}
  </header>
);

export default PageHeader;
