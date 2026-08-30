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
import { cellText, rowKindOf } from '../../pages/queryWorkbenchLogic';

/**
 * Grille de résultats, mémoïsée et définie **hors** de la page.
 *
 * Elle était rendue en ligne dans `QueryWorkbench` : chaque frappe dans l'éditeur — donc chaque
 * `setTabs` — reconstruisait jusqu'à deux cents lignes de cellules, avec la sérialisation de
 * chaque valeur, alors que rien du résultat n'avait bougé. Sortie du composant parent et
 * `React.memo`-ée, elle ne re-rend que lorsque les lignes, le tri ou la fenêtre de virtualisation
 * changent réellement.
 */
export interface ResultsGridProps {
  columns: string[];
  rows: Record<string, unknown>[];
  virtualized: boolean;
  window: { start: number; padTop: number; padBottom: number };
  sortCol: string | null;
  sortDir: 'asc' | 'desc';
  onSort: (col: string) => void;
  /** Ouvre le détail de la ligne, cadré sur la colonne cliquée. */
  onOpenRow: (index: number, column: string) => void;
  selectedIndex: number | null;
  measureRow: (tr: HTMLTableRowElement | null) => void;
  /**
   * Marque chaque ligne de son `RowKind`, quand le résultat est un changelog.
   *
   * Une colonne en tête plutôt qu'une entrée dans `columns` : ce n'est pas une valeur du
   * résultat mais une propriété de la ligne, et l'ajouter aux colonnes la ferait trier, exporter
   * et décrire comme une donnée du moteur. Absente quand il n'y a rien à marquer — une colonne
   * qui dirait « + » sur chaque ligne d'un SELECT ordinaire coûterait de la largeur pour rien.
   */
  showRowKind?: boolean;
}

const KIND_TONE: Record<'insert' | 'retract' | 'update', string> = {
  insert: 'text-outline',
  retract: 'text-error',
  update: 'text-warning',
};

export const ResultsGrid = React.memo(function ResultsGrid({
  columns, rows, virtualized, window: vwin, sortCol, sortDir, onSort, onOpenRow, selectedIndex, measureRow,
  showRowKind = false,
}: ResultsGridProps) {
  const span = columns.length + (showRowKind ? 1 : 0);
  return (
    <table className={cn('w-full text-left border-collapse', virtualized && 'table-fixed')}>
      <thead className="sticky top-0 bg-surface-container-high/90 backdrop-blur-sm z-10">
        <tr>
          {showRowKind && (
            <th scope="col" style={{ width: '2.5rem' }}
              className="px-2 py-2.5 border-b border-outline-variant/60 text-[11px] font-medium font-mono text-on-surface-variant">
              <span className="sr-only">Row kind</span>
              <span aria-hidden="true">±</span>
            </th>
          )}
          {columns.map(col => (
            // `aria-sort` sur l'en-tête et un vrai `<button>` dedans : l'en-tête était un `<th>`
            // cliquable, donc un tri inatteignable au clavier et un ordre jamais annoncé.
            // Le nom de colonne n'est **pas un libellé, c'est une donnée** : il vient du moteur, et
            // le passer en majuscules par CSS le déforme. `customerId` s'affichait `CUSTOMERID`,
            // ce qui n'est plus l'identifiant à réécrire dans la requête suivante — et sur un
            // moteur qui distingue la casse, c'est une piste fausse. L'interlettrage part avec :
            // il n'existait que pour rendre les capitales lisibles.
            <th key={col} scope="col"
              aria-sort={sortCol === col ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'}
              className="px-4 py-2.5 border-b border-outline-variant/60 text-[11px] font-medium font-mono text-on-surface-variant whitespace-nowrap">
              <button type="button" onClick={() => onSort(col)}
                className="flex items-center gap-1 hover:text-on-surface select-none transition-colors rounded">
                {col}
                {sortCol === col
                  ? <span aria-hidden="true" className="material-symbols-outlined text-[14px] text-primary">{sortDir === 'asc' ? 'arrow_upward' : 'arrow_downward'}</span>
                  : <span aria-hidden="true" className="material-symbols-outlined text-[14px] text-outline">unfold_more</span>}
              </button>
            </th>
          ))}
        </tr>
      </thead>
      <tbody className="divide-y divide-outline-variant/40">
        {/* Cale supérieure : préserve la hauteur des lignes non montées. */}
        {virtualized && vwin.padTop > 0 && (
          <tr aria-hidden="true" className="border-t-0"><td colSpan={span} style={{ height: vwin.padTop, padding: 0 }} /></tr>
        )}
        {rows.map((row, i) => {
          const absIndex = virtualized ? vwin.start + i : i;
          const selected = selectedIndex === absIndex;
          return (
            <tr key={absIndex} ref={virtualized && i === 0 ? measureRow : undefined}
              aria-selected={selected}
              className={cn('transition-colors', selected ? 'bg-primary/10' : 'hover:bg-surface-container-high/40')}>
              {showRowKind && (() => {
                const kind = rowKindOf(row);
                return (
                  // Le libellé complet est dans le titre *et* dans le texte lu par un lecteur
                  // d'écran : un symbole seul ne dit rien, et c'est précisément la ligne dont il
                  // faut savoir si elle compte.
                  <td title={`${kind.kind} — ${kind.label}`}
                    className={cn('px-2 py-2.5 text-[12px] font-mono select-none', KIND_TONE[kind.tone])}>
                    <span aria-hidden="true">{kind.symbol}</span>
                    <span className="sr-only">{kind.label}</span>
                  </td>
                );
              })()}
              {columns.map(col => {
                const { text, isNull } = cellText(row[col]);
                return (
                  // Le clic ouvre le détail de la ligne au lieu de copier à l'aveugle : une valeur
                  // longue était tronquée avec un `title` pour seul recours, et un JSON imbriqué
                  // restait une ligne de texte. La copie vit dans le panneau, par valeur.
                  <td key={col} onClick={() => onOpenRow(absIndex, col)}
                    className={cn(
                      'px-4 py-2.5 text-[12px] font-mono cursor-pointer hover:text-primary transition-colors',
                      // Un NULL SQL et une chaîne vide se rendaient tous deux par une cellule
                      // vide — c'est pourtant la distinction qui dit si une jointure a trouvé
                      // sa ligne.
                      isNull ? 'text-outline italic' : 'text-on-surface',
                      // En mode virtualisé, les lignes doivent rester à hauteur constante :
                      // on force chaque cellule sur une seule ligne (troncature + tooltip).
                      virtualized && 'whitespace-nowrap max-w-md truncate',
                    )}
                    title={virtualized ? text : 'Open this row'}>
                    {text}
                  </td>
                );
              })}
            </tr>
          );
        })}
        {/* Cale inférieure. */}
        {virtualized && vwin.padBottom > 0 && (
          <tr aria-hidden="true" className="border-t-0"><td colSpan={span} style={{ height: vwin.padBottom, padding: 0 }} /></tr>
        )}
      </tbody>
    </table>
  );
});
