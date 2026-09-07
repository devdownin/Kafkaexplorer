// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import type { FC } from 'react';
import type { Measured } from '../../api/types';
import { Tooltip } from './Tooltip';

export interface MeasuredValueProps {
  value: Measured<number>;
  /** Unité affichée après la valeur (`ms`, `enreg.`, …). Vide quand le nombre se suffit. */
  unit?: string;
  /** Mise en forme du nombre. Par défaut, séparateurs de milliers. */
  format?: (value: number) => string;
  className?: string;
}

/**
 * Rend une valeur mesurée — ou **dit qu'elle ne l'est pas, et pourquoi**.
 *
 * Un composant partagé et non un ternaire recopié, parce que la règle est la même partout et que
 * c'est celle que tout le module existe pour tenir : une mesure qui a échoué n'est jamais zéro. Le
 * tiret que rend d'ordinaire un tableau ne dit rien — ni « nul », ni « pas lu », ni « cet outil ne
 * compte pas » — et c'est exactement la distinction qu'un opérateur vient chercher ici.
 *
 * La raison voyage dans une infobulle plutôt qu'en toutes lettres : elle est longue, la colonne est
 * étroite, et la cacher entièrement reviendrait à rendre le tiret qu'on remplace.
 */
export const MeasuredValue: FC<MeasuredValueProps> = ({
  value,
  unit,
  format = (n) => n.toLocaleString('fr-FR'),
  className,
}) => {
  if (value.measured && value.value !== null) {
    return (
      <span className={className}>
        {format(value.value)}
        {unit ? <span className="text-on-surface-variant"> {unit}</span> : null}
      </span>
    );
  }
  return (
    <Tooltip content={value.reason ?? 'aucune raison fournie par le serveur'}>
      <span className={`italic text-on-surface-variant ${className ?? ''}`}>non mesuré</span>
    </Tooltip>
  );
};
