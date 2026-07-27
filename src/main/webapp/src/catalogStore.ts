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
}

const EMPTY: Catalog = { topics: [], tables: [] };

let catalog: Catalog = EMPTY;
const listeners = new Set<() => void>();

const sameList = (a: string[], b: string[]) =>
  a.length === b.length && a.every((value, i) => value === b[i]);

/** Alimenté par `Layout` à chaque sondage. No-op quand rien n'a changé. */
export function setCatalog(topics: string[], tables: string[]): void {
  if (sameList(catalog.topics, topics) && sameList(catalog.tables, tables)) return;
  // Nouvelle référence à chaque changement réel : `useSyncExternalStore` compare par identité,
  // muter le tableau en place ne déclencherait aucun rendu.
  catalog = { topics, tables };
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
