// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useEffect, useState } from 'react';

/**
 * Le seuil « desktop » de l'application, en un seul endroit.
 *
 * `Layout` porte déjà la règle : les classes `lg:` de `Sidebar`, `Header` et du conteneur de
 * contenu décrivent un seul et même seuil et doivent bouger ensemble, sinon le rail et le bouton
 * qui l'ouvre se contredisent. Une page qui réécrit `'(min-width: 1024px)'` chez elle est
 * exactement la manière dont cette règle se perd — d'où ce module, importé par les deux.
 *
 * Le seuil est à 1024 px et non à 768 : c'est la correction mesurée dans
 * `MOBILE-LAYOUT-SCOPE.md`, où deux décisions de largeur indépendantes se rencontraient et
 * laissaient l'éditeur SQL à 5 px de large sur une tablette en paysage.
 */
export const DESKTOP_QUERY = '(min-width: 1024px)';

/**
 * `true` au-dessus du seuil desktop. Pour un écran dont la *structure* change — un panneau qui
 * passe de la colonne au tiroir superposé — là où une simple classe `lg:` suffit quand seul
 * l'habillage change (voir `NarrowWindowNotice`, qui s'en tient délibérément à `lg:hidden`).
 */
export function useIsDesktop(): boolean {
  const [isDesktop, setIsDesktop] = useState(
    () => typeof window !== 'undefined' && window.matchMedia(DESKTOP_QUERY).matches,
  );

  useEffect(() => {
    const query = window.matchMedia(DESKTOP_QUERY);
    const handler = (event: MediaQueryListEvent) => setIsDesktop(event.matches);
    query.addEventListener('change', handler);
    return () => query.removeEventListener('change', handler);
  }, []);

  return isDesktop;
}
