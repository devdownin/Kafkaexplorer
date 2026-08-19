// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import type { FC } from 'react';
import { cn } from './cn';

export interface CheckboxProps {
  checked: boolean;
  onChange: (checked: boolean) => void;
  disabled?: boolean;
  /** Pour l'alignement au point d'appel (`mt-0.5` face à un libellé qui passe à la ligne). */
  className?: string;
  id?: string;
  'aria-label'?: string;
  'aria-describedby'?: string;
}

/**
 * La case à cocher de l'application, dimensionnée pour être atteignable.
 *
 * Elle existe pour une raison mesurée, pas supposée. `layout-probe.mjs` compte les cibles sous
 * les 24 x 24 px CSS de WCAG 2.5.8, et sur la page Modèle de données — le plus mauvais ratio
 * après l'éditeur SQL — **40 des 42 cibles trop petites sont hors du graphe**, presque toutes des
 * `<input type="checkbox">` natives rendues à **13 x 13**, la taille par défaut du navigateur : une
 * par topic dans le sélecteur. Ce n'était donc pas quarante problèmes mais un seul contrôle, et
 * c'est ce que ce fichier corrige — sur les six écrans qui en portent, d'un coup.
 *
 * **Une vraie cible de 24 x 24**, pas un artifice. La tentation est de garder une case de 16 px et
 * d'élargir la zone cliquable ; sur un contrôle remplacé le `padding` n'est pas appliqué de façon
 * fiable, et une mise à l'échelle par `transform` réduirait la zone réelle en même temps que le
 * dessin — elle tromperait la sonde exactement autant que l'utilisateur. La case est donc plus
 * visible qu'avant dans les listes denses : c'est le prix que la norme demande, et il est payé
 * volontairement.
 *
 * L'élément reste un `<input type="checkbox">` natif : la sémantique, le clavier, l'état
 * indéterminé et le comportement en formulaire sont ceux du navigateur, et rien ici n'a à les
 * réimplémenter. `accent-primary` vient du jeton Tailwind — trois des points d'appel écrivaient
 * la même couleur en dur (`#a3adff`), qui *est* `primary`, et une couleur recopiée finit par
 * diverger du thème.
 */
export const Checkbox: FC<CheckboxProps> = ({
  checked, onChange, disabled, className, id, ...aria
}) => (
  <input
    type="checkbox"
    id={id}
    checked={checked}
    disabled={disabled}
    onChange={e => onChange(e.target.checked)}
    className={cn(
      'h-6 w-6 shrink-0 cursor-pointer accent-primary',
      'disabled:cursor-not-allowed disabled:opacity-50',
      className,
    )}
    {...aria}
  />
);

export default Checkbox;
