// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';

// Sans `globals: true`, l'auto-cleanup de @testing-library/react n'est pas
// enregistré : le DOM s'accumulerait d'un test à l'autre. On démonte
// explicitement après chaque test.
afterEach(cleanup);

// jsdom n'implémente aucune mise en page, donc pas `scrollIntoView` : tout composant qui garde
// son option surlignée visible (Combobox, CommandPalette, la table de StreamFlow) plante sur un
// `is not a function` qui ne dit rien du test. Un no-op suffit — il n'y a rien à faire défiler.
if (!Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = function scrollIntoView() {};
}

// Même raison, autre API absente de jsdom : `ResizeObserver`. Recharts s'en sert pour tout
// conteneur responsive (les graphes des cartes de la page Métriques), et sans lui la page tombe
// dans sa frontière d'erreur avant d'avoir rendu quoi que ce soit d'observable — un échec qui ne
// dit rien du test. Ici plutôt que dans un fichier de test : le prochain écran qui affiche un
// graphe n'a pas à redécouvrir la panne, et un bouchon par fichier finit par diverger.
if (!('ResizeObserver' in globalThis)) {
  class ResizeObserverStub implements ResizeObserver {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  globalThis.ResizeObserver = ResizeObserverStub;
}
