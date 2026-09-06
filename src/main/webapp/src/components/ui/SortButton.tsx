// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import type { ReactNode } from 'react';
import { cn } from './cn';

export interface SortButtonProps<K extends string> {
  /** La clé que cet en-tête trie. */
  k: K;
  sortKey: K;
  sortDir: 'asc' | 'desc';
  /** Bascule le sens quand la clé est déjà active, sinon prend la clé. */
  onToggle: (k: K) => void;
  className?: string;
  children: ReactNode;
}

/**
 * En-tête de colonne triable : le libellé, et une flèche qui dit quelle colonne trie et dans quel
 * sens.
 *
 * **Il vivait dans `Dashboard.tsx` et l'écran suivant qui a eu besoin de trier a écrit un bouton
 * nu à la place** — clic qui pose la clé, jamais de sens, aucun indicateur. C'est le motif que ce
 * dépôt a déjà payé plusieurs fois (les quatre littéraux `internal.`, les trois copies du viewport
 * de graphe) : la seconde écriture est toujours plus pauvre que la première, et l'écart ne se voit
 * pas tant qu'on ne met pas les deux pages côte à côte.
 *
 * Deux choses sont dans le composant plutôt que chez l'appelant. La **bascule** est une règle et
 * pas un état — cliquer la colonne active inverse, cliquer une autre la prend en ascendant — donc
 * `onToggle` reçoit la clé et l'appelant applique cette règle une fois pour toutes. Et
 * `min-w-6 min-h-6` : à `text-[11px]` un en-tête mesure 18 px de haut, sous les 24 x 24 de la
 * WCAG 2.5.8, et une cible trop petite corrigée page par page est une cible qui redevient trop
 * petite à la page suivante.
 *
 * Générique sur la clé pour que chaque page garde son propre type de tri : un `SortKey` commun
 * réunirait des colonnes que rien ne partage.
 */
export function SortButton<K extends string>({
  k, sortKey, sortDir, onToggle, className, children,
}: SortButtonProps<K>) {
  const active = sortKey === k;
  return (
    <button
      onClick={() => onToggle(k)}
      /*
       * `aria-sort` appartient au `<th>` et non au bouton, et la table de ce design system ne le
       * porte pas : l'état est donc énoncé ici, sur le contrôle qui le change, plutôt que nulle
       * part. Sans ça, un lecteur d'écran annonce trois fois « bouton Topic » sans jamais dire
       * lequel trie.
       */
      aria-label={active
        ? `Sorted by this column, ${sortDir === 'asc' ? 'ascending' : 'descending'}. Activate to reverse.`
        : undefined}
      className={cn('inline-flex items-center gap-1 min-w-6 min-h-6 hover:text-on-surface transition-colors', className)}
    >
      {children}
      {!active ? (
        <span aria-hidden="true" className="material-symbols-outlined text-[15px] opacity-30">unfold_more</span>
      ) : (
        <span aria-hidden="true" className="material-symbols-outlined text-[15px] text-primary">
          {sortDir === 'asc' ? 'arrow_upward' : 'arrow_downward'}
        </span>
      )}
    </button>
  );
}

export default SortButton;
