// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * Les chaînes réellement tracées, gardées par le navigateur.
 *
 * Une trace Stream Flow n'existe nulle part côté serveur : elle se lance depuis une page, son
 * résultat vit dans la mémoire du composant et meurt avec lui. C'est pourtant l'observation la
 * plus précise que cette application produise sur un pipeline — le chemin qu'une clé a vraiment
 * suivi, avec la latence de chaque saut — et la page Métriques n'avait aucun moyen de s'en servir
 * pour proposer un KPI.
 *
 * Ce module est ce chaînon : Stream Flow y dépose la chaîne quand une trace aboutit, la page
 * Métriques la relit et l'envoie à `POST /api/metrics/suggestions`, où la même dérivation traite
 * les chaînes tracées et les flux reconstruits par l'audit. La règle vit donc à un seul endroit —
 * la dupliquer en TypeScript pour les unes et en Java pour les autres, c'est deux réponses à une
 * question, qui finissent par diverger.
 *
 * Trois garde-fous, les mêmes que pour les brouillons de `draftStore` : une enveloppe versionnée
 * (une forme inconnue est effacée, jamais devinée), une péremption (une chaîne d'il y a trois
 * semaines décrit des topics qui n'existent peut-être plus), et un nombre borné d'entrées.
 * L'écriture est au mieux : quota plein ou mode privé, on renvoie la liste calculée sans lever.
 */

import type { FlowChainEvidence, FlowChainHop } from '../api/types';
import type { FlowHit } from './streamFlow';

export const FLOW_CHAINS_KEY = 'kse:flow-chains';
/** Au-delà, la page Métriques proposerait plus de cartes que personne n'en lit. */
export const MAX_FLOW_CHAINS = 5;
/** Même durée que les brouillons : sept jours. */
export const MAX_FLOW_CHAIN_AGE_MS = 7 * 24 * 60 * 60 * 1000;
/** Une seule occurrence n'est pas un chemin : il faut au moins deux topics pour un saut. */
export const MIN_CHAIN_HOPS = 2;

const ENVELOPE_VERSION = 1;

interface ChainEnvelope {
  v: number;
  chains: FlowChainEvidence[];
}

function isHop(value: unknown): value is FlowChainHop {
  return !!value && typeof value === 'object' && typeof (value as FlowChainHop).topic === 'string';
}

function isChain(value: unknown): value is FlowChainEvidence {
  return !!value && typeof value === 'object' && Array.isArray((value as FlowChainEvidence).hops);
}

/**
 * Les chaînes encore valides, plus récente en tête.
 *
 * Une enveloppe d'une autre version est effacée plutôt qu'interprétée : la relire au jugé, c'est
 * exactement ce qui produit une proposition fondée sur des données dont on ne sait plus la forme.
 */
export function readFlowChains(now: number = Date.now()): FlowChainEvidence[] {
  let raw: string | null;
  try {
    raw = localStorage.getItem(FLOW_CHAINS_KEY);
  } catch {
    return [];
  }
  if (!raw) return [];

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    clearFlowChains();
    return [];
  }

  const envelope = parsed as ChainEnvelope;
  if (!envelope || typeof envelope !== 'object' || envelope.v !== ENVELOPE_VERSION
      || !Array.isArray(envelope.chains)) {
    clearFlowChains();
    return [];
  }

  const fresh = envelope.chains
    .filter(isChain)
    .map(chain => ({ ...chain, hops: chain.hops.filter(isHop) }))
    .filter(chain => chain.hops.length >= MIN_CHAIN_HOPS)
    .filter(chain => chain.tracedAt == null || now - chain.tracedAt <= MAX_FLOW_CHAIN_AGE_MS);

  if (fresh.length !== envelope.chains.length) writeFlowChains(fresh);
  return fresh;
}

export function writeFlowChains(chains: FlowChainEvidence[]): void {
  try {
    localStorage.setItem(FLOW_CHAINS_KEY, JSON.stringify({ v: ENVELOPE_VERSION, chains } satisfies ChainEnvelope));
  } catch {
    // Le stockage est un confort : une proposition en moins, jamais une page cassée.
  }
}

export function clearFlowChains(): void {
  try {
    localStorage.removeItem(FLOW_CHAINS_KEY);
  } catch {
    /* idem */
  }
}

/** La route d'une chaîne, qui est aussi ce sur quoi deux traces se dédoublonnent. */
export function chainRoute(chain: FlowChainEvidence): string {
  return chain.hops.map(hop => hop.topic).join(' → ');
}

/**
 * Convertit le résultat d'une trace en observation stockable.
 *
 * Les hits arrivent déjà chaînés par première apparition (c'est la règle de chaînage du serveur),
 * donc l'ordre est conservé tel quel : le réordonner ici serait une seconde règle de chaînage.
 */
export function chainFromHits(
  hits: FlowHit[],
  criterion: { messageKey?: string; searchPath?: string },
  now: number = Date.now(),
): FlowChainEvidence | null {
  const hops: FlowChainHop[] = hits
    .filter(hit => hit && typeof hit.topic === 'string' && hit.topic.length > 0)
    .map(hit => ({
      topic: hit.topic,
      firstTimestamp: hit.firstTimestamp ?? null,
      latencyFromPreviousMs: hit.latencyFromPreviousMs ?? null,
      occurrences: hit.occurrences ?? null,
    }));
  if (hops.length < MIN_CHAIN_HOPS) return null;

  return {
    messageKey: criterion.messageKey?.trim() || null,
    searchPath: criterion.searchPath?.trim() || null,
    tracedAt: now,
    hops,
  };
}

/**
 * Empile une chaîne en tête, dédoublonnée sur la **route** : retracer la même clé une heure plus
 * tard décrit le même pipeline, et garder les deux ne donnerait pas deux propositions mais deux
 * fois la même. La plus récente gagne — ses latences sont les plus fraîches.
 */
export function pushFlowChain(chain: FlowChainEvidence, now: number = Date.now()): FlowChainEvidence[] {
  const route = chainRoute(chain);
  const next = [chain, ...readFlowChains(now).filter(existing => chainRoute(existing) !== route)]
    .slice(0, MAX_FLOW_CHAINS);
  writeFlowChains(next);
  return next;
}

/**
 * Enregistre le résultat d'une trace, s'il décrit un chemin. Renvoie la chaîne retenue, ou `null`
 * quand la trace n'a touché qu'un topic — auquel cas rien n'est écrit : une occurrence isolée
 * n'apprend rien sur un pipeline.
 */
export function recordTracedChain(
  hits: FlowHit[],
  criterion: { messageKey?: string; searchPath?: string },
  now: number = Date.now(),
): FlowChainEvidence | null {
  const chain = chainFromHits(hits, criterion, now);
  if (!chain) return null;
  pushFlowChain(chain, now);
  return chain;
}
