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
    // Le rappel est **accepté**, pas ignoré : le vrai `ResizeObserver` en prend un, et un bouchon
    // dont la signature diffère de l'API qu'il remplace laisse passer un appel que rien ne
    // vérifie — CodeQL a signalé exactement ça sur `new ResizeObserver(measure)`, que TypeScript
    // ne pouvait pas voir (`implements` ne contrôle pas le constructeur).
    //
    // Il n'est jamais invoqué, et c'est délibéré : jsdom n'a aucune mise en page, donc aucune
    // taille à rapporter. Fabriquer un faux événement de redimensionnement ferait croire à une
    // mesure là où il n'y en a pas — même règle que partout ici, une mesure qu'on n'a pas prise
    // ne se rend pas comme un zéro. D'où le paramètre non retenu plutôt qu'un champ jamais lu.
    constructor(_callback: ResizeObserverCallback) {}

    observe() {}
    unobserve() {}
    disconnect() {}
  }
  globalThis.ResizeObserver = ResizeObserverStub;
}

// Troisième API absente de jsdom, et la plus trompeuse des trois : `matchMedia`. Tout écran qui
// choisit sa mise en page au seuil desktop (`useIsDesktop`) plante sur un `is not a function`
// avant d'avoir rendu quoi que ce soit. Le bouchon répond **false** — jsdom n'a aucune mise en
// page, donc aucune largeur à interroger, et « pas desktop » est la seule réponse honnête : c'est
// la disposition étroite qui est alors testée, et une page qui ne tient pas dans cette
// disposition-là doit échouer ici plutôt que sur l'écran de quelqu'un.
if (!window.matchMedia) {
  window.matchMedia = (query: string): MediaQueryList => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  });
}

// La locale par défaut du poste ne décide pas du verdict d'un test.
//
// Une dizaine d'assertions comparent des nombres passés par `toLocaleString()` sans argument.
// Node prend alors la locale du système : sur une machine française, « 1 200 » avec une espace
// fine insécable (U+202F) là où le test attend « 1,200 ». La suite passait donc en CI (Linux,
// locale C) et échouait chez qui développe, ce qui est la seule chose qu'un test ne doit jamais
// faire — on ne peut plus distinguer « j'ai cassé quelque chose » de « je ne suis pas sur le bon
// système ». `LANG` / `LC_ALL` ne suffisent pas : ICU fixe la locale par défaut au démarrage du
// processus, avant que la configuration de Vitest ne s'applique (mesuré, pas supposé).
//
// Seuls les appels *sans* locale explicite sont réécrits : un test qui en nomme une la garde.
// Ce que ce bouchon ne corrige pas est délibéré : en production, une interface entièrement en
// anglais formate ses nombres dans la locale du navigateur. C'est une question de produit, et
// elle est nommée dans la PR plutôt que réglée ici par un effet de bord de la suite de tests.
const DISPLAY_LOCALE = 'en-US';
for (const proto of [Number.prototype, Date.prototype] as const) {
  for (const method of ['toLocaleString', 'toLocaleDateString', 'toLocaleTimeString'] as const) {
    const original = (proto as Record<string, unknown>)[method];
    if (typeof original !== 'function') continue;
    (proto as Record<string, unknown>)[method] = function localeFixed(
      this: unknown,
      locales?: Intl.LocalesArgument,
      options?: unknown,
    ) {
      return (original as (...args: unknown[]) => string).call(
        this, locales ?? DISPLAY_LOCALE, options);
    };
  }
}
