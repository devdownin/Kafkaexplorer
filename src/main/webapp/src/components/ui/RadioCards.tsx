// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import type { ReactNode } from 'react';
import { cn } from './cn';

export interface RadioCardOption<T extends string> {
  value: T;
  label: string;
  /** La ligne sous le libellé — ce que ce choix implique, pas une paraphrase du libellé. */
  description?: ReactNode;
}

export interface RadioCardsProps<T extends string> {
  /** Le nom du groupe, rendu en `<legend>` : c'est ce qu'une aide technique annonce d'abord. */
  legend: string;
  /**
   * Le `name` partagé des boutons radio. Il fait le groupe pour le navigateur : c'est lui qui
   * donne les flèches et le tabulateur unique, pas le `<fieldset>`.
   */
  name: string;
  value: T;
  onChange: (value: T) => void;
  options: readonly RadioCardOption<T>[];
  /** Les classes de grille du point d'appel — trois modes ne se disposent pas comme cinq. */
  columns?: string;
  className?: string;
}

/**
 * Un choix unique présenté en cartes — et ce sont de vrais boutons radio.
 *
 * Ce que ça remplace : des `<button type="button" aria-pressed>` dans un `<fieldset>`. Ils se
 * *comportaient* en cases à cocher indépendantes tout en signifiant un choix exclusif, ce que
 * `aria-pressed` ne dit pas ; concrètement, un groupe de cinq fournisseurs coûtait cinq arrêts de
 * tabulation, les flèches n'y faisaient rien, et rien n'annonçait « 2 sur 5 ». Un `radiogroup`
 * natif donne les trois d'un coup, sans qu'une ligne de JavaScript ait à les réimplémenter — la
 * même règle que `Checkbox`, qui est restée un `<input>` natif pour la même raison.
 *
 * **L'entrée est `sr-only`, pas `hidden`** : elle reste focalisable, et c'est elle qui reçoit
 * réellement le focus. Le contour visible est donc porté par le `<span>` qui la suit, via `peer-*`
 * — un sélecteur de frère, pas un `:has()` — pour que le focus clavier reste visible même là où
 * `:has()` n'est pas disponible. Un état de sélection calculé en JS plutôt qu'en `peer-checked:`
 * pour la même raison : ce composant existe pour l'accessibilité, il ne doit pas la faire dépendre
 * d'une variante CSS.
 *
 * La carte porte `p-3`, donc la cible dépasse largement les 24 x 24 px de WCAG 2.5.8 — c'est le
 * point que `Checkbox` et `Switch` ont déjà coûté ailleurs.
 */
export function RadioCards<T extends string>({
  legend, name, value, onChange, options, columns = 'grid-cols-2 sm:grid-cols-3', className,
}: RadioCardsProps<T>) {
  return (
    <fieldset className={className}>
      <legend className="block text-[12px] font-medium text-on-surface-variant mb-1.5">
        {legend}
      </legend>
      <div className={cn('grid gap-3', columns)}>
        {options.map(option => {
          const selected = option.value === value;
          return (
            <label key={option.value} className="block cursor-pointer">
              <input
                type="radio"
                name={name}
                value={option.value}
                checked={selected}
                onChange={() => onChange(option.value)}
                className="peer sr-only"
              />
              <span
                className={cn(
                  'block h-full p-3 rounded-lg border text-left transition-all',
                  'peer-focus-visible:ring-2 peer-focus-visible:ring-primary peer-focus-visible:ring-offset-1',
                  'peer-focus-visible:ring-offset-surface-container',
                  selected
                    ? 'border-primary bg-primary/10 text-on-surface'
                    : 'border-outline-variant bg-surface-container-low text-on-surface-variant hover:border-outline',
                )}
              >
                <span className="block text-xs font-bold">{option.label}</span>
                {option.description && (
                  <span className="block text-[10px] text-on-surface-variant mt-0.5">
                    {option.description}
                  </span>
                )}
              </span>
            </label>
          );
        })}
      </div>
    </fieldset>
  );
}

export default RadioCards;
