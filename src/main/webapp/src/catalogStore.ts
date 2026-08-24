// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { useSyncExternalStore } from 'react';

/**
 * Catalogue partagé des topics Kafka et des tables Flink.
 *
 * `Layout` interroge déjà `/api/dashboard` toutes les 30 s pour l'état de connexion et la
 * palette de commandes ; il pousse le résultat ici plutôt que de le garder pour lui, ce qui
 * donne l'autocomplétion à tous les formulaires sans une seule requête supplémentaire.
 */
export interface Catalog {
  topics: string[];
  tables: string[];
  /**
   * Le préfixe des topics que l'application s'écrit à elle-même
   * (`explorer.internal-topic-prefix`), résolu par le serveur — vide sur un déploiement qui n'en
   * pose pas.
   *
   * Il vit ici et non dans un module à lui parce que c'est la même réponse qui l'apporte : ce
   * store est le catalogue que `Layout` alimente depuis son sondage de `/api/dashboard`, et
   * « lesquels de ces topics sont les nôtres » est une propriété de ce catalogue-là. Une seconde
   * source finirait par diverger de la première.
   */
  internalPrefix: string;
  /**
   * L'adresse du cluster réellement utilisée, y compris après un repointage à chaud.
   *
   * Elle vit ici pour la raison qui a mis `internalPrefix` ici : c'est la même réponse qui
   * l'apporte. La page Metrics la lisait en appelant `/api/config` pour ce seul champ, avec une
   * forme déclarée à la main au point d'appel (`{ bootstrapServers: string }`) — le motif que
   * `check-api-types.py` existe pour supprimer, et une requête de plus au montage pour une valeur
   * que `Layout` avait déjà. Vide tant que le premier sondage n'a pas répondu.
   */
  bootstrapServers: string;
}

const EMPTY: Catalog = { topics: [], tables: [], internalPrefix: '', bootstrapServers: '' };

let catalog: Catalog = EMPTY;
const listeners = new Set<() => void>();

const sameList = (a: string[], b: string[]) =>
  a.length === b.length && a.every((value, i) => value === b[i]);

/** Alimenté par `Layout` à chaque sondage. No-op quand rien n'a changé. */
export function setCatalog(topics: string[], tables: string[], internalPrefix = '',
                           bootstrapServers = ''): void {
  if (sameList(catalog.topics, topics) && sameList(catalog.tables, tables)
      && catalog.internalPrefix === internalPrefix
      && catalog.bootstrapServers === bootstrapServers) return;
  // Nouvelle référence à chaque changement réel : `useSyncExternalStore` compare par identité,
  // muter le tableau en place ne déclencherait aucun rendu.
  catalog = { topics, tables, internalPrefix, bootstrapServers };
  listeners.forEach(listener => listener());
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

const getSnapshot = () => catalog;

export function useCatalog(): Catalog {
  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
}

/** Réinitialise le store — réservé aux tests. */
export function resetCatalog(): void {
  catalog = EMPTY;
  listeners.forEach(listener => listener());
}
