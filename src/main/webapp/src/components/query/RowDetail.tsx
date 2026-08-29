// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

// Composants de la page SQL Editor, sortis de `QueryWorkbench.tsx`.
//
// La page tenait 2 168 lignes dans un seul composant : onglets, navigateur de schéma, barre
// d'outils, éditeur, grille, détail de ligne, assistant de fenêtrage. Le dépôt a déjà le motif
// inverse — `components/topic/`, `components/processmining/` — et c'est ce qui rend un audit
// lisible. Le découpage arrive *après* les tests de composant (`QueryWorkbench.test.tsx`), pas
// avant : refactoriser sans filet ce qu'on vient de corriger est le meilleur moyen de le recasser.

import React from 'react';
import { cn } from '../ui';
import { detailValue } from '../../pages/queryWorkbenchLogic';

/**
 * Détail d'une ligne de résultat.
 *
 * La grille rend une ligne par enregistrement, tronquée sur une seule ligne dès qu'elle est
 * virtualisée : une valeur longue n'était atteignable que par le `title` au survol, et un JSON
 * imbriqué y restait une chaîne. Ce panneau montre toutes les colonnes de la ligne choisie, met en
 * forme ce qui est structuré (`detailValue`, y compris le JSON arrivé sous forme de texte, ce que
 * le moteur direct renvoie couramment) et porte la copie, valeur par valeur.
 */
export interface RowDetailProps {
  columns: string[];
  row: Record<string, unknown>;
  index: number;
  total: number;
  focusColumn: string | null;
  onClose: () => void;
  onStep: (delta: number) => void;
  onCopy: (value: unknown) => void;
}

export const RowDetail: React.FC<RowDetailProps> = ({
  columns, row, index, total, focusColumn, onClose, onStep, onCopy,
}) => (
  <aside aria-label="Row detail"
    className="w-80 xl:w-96 shrink-0 border-l border-outline-variant/60 bg-surface-container-low/60 flex flex-col overflow-hidden">
    <div className="h-10 px-3 flex items-center gap-1 border-b border-outline-variant/60 shrink-0">
      <span className="text-[11px] font-medium text-on-surface-variant uppercase tracking-[0.05em]">
        Row <span className="tabular-nums text-on-surface">{index + 1}</span> of <span className="tabular-nums">{total.toLocaleString()}</span>
      </span>
      <div className="ml-auto flex items-center">
        <button type="button" onClick={() => onStep(-1)} disabled={index === 0}
          aria-label="Previous row" title="Previous row"
          className="p-1 rounded-md text-on-surface-variant hover:text-on-surface hover:bg-surface-container-high disabled:opacity-30 disabled:pointer-events-none transition-colors">
          <span aria-hidden="true" className="material-symbols-outlined text-[18px]">keyboard_arrow_up</span>
        </button>
        <button type="button" onClick={() => onStep(1)} disabled={index >= total - 1}
          aria-label="Next row" title="Next row"
          className="p-1 rounded-md text-on-surface-variant hover:text-on-surface hover:bg-surface-container-high disabled:opacity-30 disabled:pointer-events-none transition-colors">
          <span aria-hidden="true" className="material-symbols-outlined text-[18px]">keyboard_arrow_down</span>
        </button>
        <button type="button" onClick={onClose} aria-label="Close the row detail" title="Close"
          className="p-1 ml-1 rounded-md text-on-surface-variant hover:text-on-surface hover:bg-surface-container-high transition-colors">
          <span aria-hidden="true" className="material-symbols-outlined text-[18px]">close</span>
        </button>
      </div>
    </div>
    <div className="flex-1 overflow-y-auto custom-scrollbar p-3 space-y-3">
      {columns.map(col => {
        const { text, json, isNull } = detailValue(row[col]);
        return (
          <div key={col} className={cn(
            'rounded-lg border px-2.5 py-2',
            col === focusColumn ? 'border-primary/50 bg-primary/5' : 'border-outline-variant/50',
          )}>
            <div className="flex items-center gap-2">
              <p className="text-[11px] font-medium text-on-surface-variant truncate">{col}</p>
              {json && <span className="text-[9px] uppercase tracking-wide text-primary/70 shrink-0">json</span>}
              <button type="button" onClick={() => onCopy(row[col])}
                aria-label={`Copy ${col}`} title="Copy"
                className="ml-auto shrink-0 text-outline hover:text-primary transition-colors">
                <span aria-hidden="true" className="material-symbols-outlined text-[14px]">content_copy</span>
              </button>
            </div>
            <pre className={cn(
              'mt-1 text-[11px] font-mono whitespace-pre-wrap break-words leading-relaxed',
              isNull ? 'text-outline italic' : 'text-on-surface',
            )}>{text}</pre>
          </div>
        );
      })}
    </div>
  </aside>
);
