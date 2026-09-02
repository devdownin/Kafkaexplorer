// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useCallback, useInsertionEffect, useRef } from 'react';

/**
 * Un gestionnaire dont l'**identité ne change jamais**, mais qui appelle toujours la dernière
 * version de la fonction qu'on lui passe.
 *
 * `React.memo` compare les props par référence : un composant mémoïsé auquel on passe
 * `onClick={() => doSomething(x)}` se re-rend à chaque rendu du parent, la lambda étant neuve à
 * chaque fois. `useCallback` ne le sauve pas dès que la fonction lit un état qui bouge souvent —
 * et c'est précisément le cas de l'éditeur SQL, dont chaque frappe réécrit le SQL de l'onglet
 * actif, donc réécrit tout gestionnaire qui le lit.
 *
 * D'où ce motif (celui que la documentation React appelle `useEvent`) : la fonction courante vit
 * dans une ref, réécrite en `useInsertionEffect` — avant les effets de mise en page, donc avant
 * qu'un gestionnaire puisse être appelé — et l'appelant reçoit une enveloppe stable. Il n'y a pas
 * de liste de dépendances à tenir à jour, et donc pas de valeur périmée à capturer.
 *
 * À réserver aux gestionnaires d'événements. Ce n'est pas un substitut à `useCallback` pour une
 * valeur lue *pendant* le rendu : la ref n'y est pas encore à jour.
 */
export function useStableCallback<A extends unknown[], R>(fn: (...args: A) => R): (...args: A) => R {
  const ref = useRef(fn);
  useInsertionEffect(() => { ref.current = fn; }, [fn]);
  return useCallback((...args: A) => ref.current(...args), []);
}

export default useStableCallback;
