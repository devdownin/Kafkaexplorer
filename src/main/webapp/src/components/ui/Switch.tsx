// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import type { FC } from 'react';
import { cn } from './cn';

export interface SwitchProps {
  checked: boolean;
  onChange: (checked: boolean) => void;
  /** Le nom accessible : l'interrupteur ne porte aucun texte. */
  'aria-label': string;
  disabled?: boolean;
  className?: string;
  id?: string;
  'aria-describedby'?: string;
}

/**
 * L'interrupteur de l'application, dimensionné pour être atteignable.
 *
 * Même histoire que `Checkbox`, un cran plus loin. `layout-probe.mjs` compte les cibles sous les
 * 24 x 24 px CSS de WCAG 2.5.8, et l'interrupteur y ressortait à **36 x 20** — trop court de
 * quatre pixels — sur **quatre écrans à la fois** : Dashboard (« Hide empty », « Hide DLT »),
 * Stream Flow (quatre bascules de critère), Compare et Lineage. Quatre copies écrites à la main
 * du même balisage, aux jetons près : le dessin divergeait déjà (`h-3.5` du pouce partout sauf
 * `h-3` dans Lineage, `bg-surface-container-highest` partout sauf `-high` dans Lineage), ce qui
 * est la trajectoire ordinaire d'un composant recopié. Ce n'était donc pas huit problèmes mais un
 * seul contrôle, et c'est ce que ce fichier corrige d'un coup.
 *
 * **La cible fait 24 px de haut, la piste en garde 20.** C'est la différence avec `Checkbox`, et
 * elle est délibérée : une case à cocher est un contrôle *remplacé*, où le `padding` n'est pas
 * appliqué de façon fiable, donc la case devait vraiment grandir. Un interrupteur est un
 * `<button>` ordinaire — le `padding` vertical y fonctionne, il agrandit la zone cliquable réelle
 * que la sonde mesure comme l'utilisateur la touche, et il ne change rien au dessin. Élargir la
 * pilule à 24 px de haut aurait alourdi quatre écrans pour satisfaire une norme qui ne demande
 * pas cela : elle parle de la cible, pas de l'ornement.
 *
 * `role="switch"` avec `aria-checked` reste ce qu'il était — c'est le rôle juste, et les quatre
 * copies le portaient déjà. Ce qu'aucune ne portait, c'est `type="button"` : trois d'entre elles
 * vivent hors d'un `<form>`, donc le défaut `submit` n'y faisait rien de visible, et c'est
 * exactement le genre de bombe à retardement qu'un composant partagé désamorce une fois pour
 * toutes.
 */
export const Switch: FC<SwitchProps> = ({
  checked, onChange, disabled = false, className, id,
  'aria-label': ariaLabel, 'aria-describedby': describedBy,
}) => (
  <button
    type="button"
    role="switch"
    id={id}
    aria-checked={checked}
    aria-label={ariaLabel}
    aria-describedby={describedBy}
    disabled={disabled}
    onClick={() => onChange(!checked)}
    className={cn(
      // `py-0.5` porte la cible de 20 à 24 px sans toucher à la piste ; `box-content` empêche
      // Tailwind de reprendre ce padding sur la hauteur déclarée.
      'relative inline-flex h-5 w-9 shrink-0 items-center rounded-full box-content py-0.5',
      'transition-colors disabled:cursor-not-allowed disabled:opacity-50',
      'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/50',
      checked ? 'bg-primary' : 'bg-surface-container-highest',
      className,
    )}
  >
    <span
      aria-hidden="true"
      className={cn(
        'inline-block h-3.5 w-3.5 transform rounded-full transition-transform',
        checked ? 'translate-x-[18px] bg-on-primary' : 'translate-x-1 bg-on-surface-variant',
      )}
    />
  </button>
);

export default Switch;
