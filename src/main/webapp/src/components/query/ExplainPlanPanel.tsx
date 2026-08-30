// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React from 'react';

/**
 * Le plan d'un `EXPLAIN`, rendu comme du texte plutôt que comme une cellule.
 *
 * Flink rend un plan en **une ligne d'une colonne** dont le contenu fait vingt lignes indentées.
 * La grille le recevait tel quel : une cellule géante, tronquée sur une seule ligne dès qu'elle est
 * virtualisée, et lisible uniquement en ouvrant le panneau de détail. C'est la faute que ce dépôt a
 * corrigée partout ailleurs — un contenu qui existe et demande un geste de plus pour être atteint —
 * sur la seule requête dont le résultat *est* du texte à lire.
 *
 * `whitespace-pre` et non `pre-wrap` : l'indentation d'un plan porte sa structure, et replier les
 * lignes longues la détruit. Le débordement défile horizontalement, comme partout ici pour un
 * contenu large.
 */
export interface ExplainPlanPanelProps {
  plan: string;
  onCopy: () => void;
}

export const ExplainPlanPanel: React.FC<ExplainPlanPanelProps> = ({ plan, onCopy }) => (
  <div className="p-4">
    <div className="rounded-lg border border-outline-variant/60 bg-surface-container-low/60 overflow-hidden">
      <div className="h-9 px-3 flex items-center gap-2 border-b border-outline-variant/60">
        <span aria-hidden="true" className="material-symbols-outlined text-[16px] text-on-surface-variant">account_tree</span>
        <span className="text-[11px] font-medium text-on-surface-variant uppercase tracking-[0.05em]">
          Execution plan
        </span>
        <button type="button" onClick={onCopy}
          aria-label="Copy the execution plan" title="Copy"
          className="ml-auto flex items-center gap-1 text-[11px] text-on-surface-variant hover:text-on-surface transition-colors rounded">
          <span aria-hidden="true" className="material-symbols-outlined text-[14px]">content_copy</span>
          Copy
        </button>
      </div>
      <pre className="p-3 text-[11px] font-mono leading-relaxed text-on-surface whitespace-pre overflow-x-auto custom-scrollbar">
        {plan}
      </pre>
    </div>
  </div>
);
