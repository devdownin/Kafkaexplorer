// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * Combien de temps le navigateur attend `POST /api/config/test-llm`.
 *
 * Deux écrans appellent ce point d'accès et attendaient chacun autrement : Process Mining
 * abandonnait au bout de 90 s en dur, Réglages n'avait aucun délai du tout — `axios` n'en pose
 * aucun par défaut, la règle que l'éditeur SQL a déjà servi à écrire. Les deux sont faux, et le
 * premier l'est là où ça se voit : `compose/ollama.yml` et `compose/spectra-hub.yml` règlent tous
 * deux le serveur sur **300 s**, et le champ de Réglages accepte jusqu'à 600. Un modèle 7B
 * quantifié sur CPU — le cas exact pour lequel ces stacks existent — dépasse couramment 90 s à sa
 * première réponse, chargement du modèle compris. Le bouton dont le métier est de distinguer « ça
 * ne répond pas » de « c'est lent » tranchait donc pour le premier sur le déploiement où plus rien
 * d'autre ne diagnostique quoi que ce soit.
 *
 * Le serveur publie son propre budget (`llmRequestTimeoutSeconds`, servi par `GET /api/config`),
 * donc l'attente s'en déduit au lieu d'être inventée : un délai qu'une UI fixe elle-même pour un
 * appel dont elle connaît le budget est un délai faux le jour où ce budget bouge.
 *
 * Trois bornes, et chacune répond à une question différente. La **marge** couvre ce qui n'est pas
 * la génération : connexion, sérialisation, le trajet. Le **plancher** est la valeur d'avant, donc
 * un déploiement au défaut de 60 s attend exactement ce qu'il attendait ; attendre plus longtemps
 * que le serveur ne coûte rien, puisque c'est lui qui répond ou renonce en premier. Le **plafond**
 * borne ce qu'un réglage aberrant peut demander au navigateur.
 */

/** Ce que le trajet et la connexion coûtent, au-delà de la génération elle-même. */
export const TEST_TIMEOUT_MARGIN_MS = 15_000;

/** L'attente d'avant : jamais moins, pour qu'aucun déploiement n'attende moins qu'hier. */
export const TEST_TIMEOUT_FLOOR_MS = 90_000;

/** Le champ de Réglages plafonne à 600 s ; au-delà, c'est une saisie, pas une politique. */
export const TEST_TIMEOUT_CEILING_MS = 630_000;

/**
 * L'attente à poser sur la sonde, déduite du budget que le serveur annonce.
 *
 * Une valeur absente, non finie ou non positive donne le plancher : le budget du serveur n'est pas
 * connu ici, et supposer un serveur plus rapide qu'il ne l'est est l'erreur qu'on corrige.
 */
export const testTimeoutMs = (requestTimeoutSeconds?: number | null): number => {
  if (typeof requestTimeoutSeconds !== 'number'
    || !Number.isFinite(requestTimeoutSeconds)
    || requestTimeoutSeconds <= 0) {
    return TEST_TIMEOUT_FLOOR_MS;
  }
  const derived = Math.round(requestTimeoutSeconds * 1000) + TEST_TIMEOUT_MARGIN_MS;
  return Math.min(TEST_TIMEOUT_CEILING_MS, Math.max(TEST_TIMEOUT_FLOOR_MS, derived));
};

/**
 * Ce qui s'affiche quand c'est *le navigateur* qui a renoncé.
 *
 * La distinction est tout l'intérêt : un appel abandonné côté client ne dit rien sur le point
 * d'accès, qui peut très bien être en train de répondre. Le message d'échec générique de la page
 * conseillait « réessayez avec moins de topics, un échantillon plus petit » — sur un contrôle de
 * santé d'un mot, qui n'a ni topic ni échantillon.
 */
export const describeTestTimeout = (waitedMs: number,
                                    requestTimeoutSeconds?: number | null): string => {
  const waited = Math.round(waitedMs / 1000);
  const budget = typeof requestTimeoutSeconds === 'number'
    && Number.isFinite(requestTimeoutSeconds)
    && requestTimeoutSeconds > 0
    ? `The server's own budget is ${Math.round(requestTimeoutSeconds)}s `
      + '(claude.request-timeout-seconds), so the call may still be running.'
    : 'The server may still be waiting on the model.';
  return `The browser stopped waiting after ${waited}s. ${budget} `
    + 'A local model on CPU can take minutes on its first answer, model load included — raise '
    + 'claude.request-timeout-seconds if the endpoint needs longer than it is allowed.';
};
