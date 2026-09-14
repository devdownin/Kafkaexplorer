// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/**
 * Le jeton MCP que l'écran présente pour les gestes qui changent l'état.
 *
 * Depuis que la frontière bearer existe, `/api/mcp/try`, les leviers, la quarantaine, la frappe
 * d'approbation et le replay demandent `Authorization: Bearer …` — et un navigateur n'en détient
 * aucun. Les boutons répondaient donc 401 sur la page qui les porte : le levier le plus utile en
 * incident était injoignable exactement là où on le cherche. L'opérateur colle ici le jeton qu'il
 * détient déjà.
 *
 * **`sessionStorage`, jamais `localStorage`, et c'est le seul arbitrage du fichier.** Un jeton
 * bearer en mémoire seule se retape à chaque rafraîchissement — pendant l'incident, c'est-à-dire au
 * pire moment ; dans `localStorage` il survit à la fermeture de l'onglet et reste sur un poste
 * partagé jusqu'à ce que quelqu'un y pense. `sessionStorage` tient le temps de l'onglet et part
 * avec lui, ce qui est la durée du geste.
 *
 * Ce qu'il ne fait jamais : passer dans une URL (les paramètres de requête finissent dans les logs
 * du proxy), accompagner une lecture (elles ne sont pas authentifiées, par conception), ni être
 * relu par autre chose que l'en-tête qu'il sert.
 */

/** La clé, nommée pour ce qu'elle contient : un secret porteur, pas une préférence. */
export const MCP_CREDENTIAL_KEY = 'kse.mcp.bearer';

/**
 * Le jeton détenu par cet onglet, ou `null`.
 *
 * Tout est sous `try` : un navigateur en navigation privée, un stockage bloqué par une politique
 * ou un quota plein font lever l'accesseur lui-même, et un écran qui casse là-dessus est un écran
 * qui casse chez les déploiements les plus prudents.
 */
export function readCredential(): string | null {
  try {
    const stored = window.sessionStorage.getItem(MCP_CREDENTIAL_KEY);
    return stored === null || stored.trim() === '' ? null : stored;
  } catch {
    return null;
  }
}

/**
 * Retient un jeton, ou l'oublie quand il est vide.
 *
 * Rend `false` quand le stockage a refusé : l'écran le dit alors plutôt que d'afficher un jeton
 * enregistré qui ne l'est pas — la même règle que partout ici, une mesure qui a échoué n'est pas
 * un zéro.
 */
export function writeCredential(token: string): boolean {
  try {
    if (token.trim() === '') {
      window.sessionStorage.removeItem(MCP_CREDENTIAL_KEY);
      return true;
    }
    window.sessionStorage.setItem(MCP_CREDENTIAL_KEY, token.trim());
    return true;
  } catch {
    return false;
  }
}

/** Oublie le jeton. Sans question : il n'y a rien à expliquer pour retirer un secret. */
export function forgetCredential(): void {
  try {
    window.sessionStorage.removeItem(MCP_CREDENTIAL_KEY);
  } catch {
    // Un stockage qui refuse d'effacer n'en contient pas non plus.
  }
}

/**
 * L'en-tête à joindre à un geste privilégié, ou rien.
 *
 * Un objet vide plutôt qu'un en-tête vide : `Authorization: Bearer ` est une tentative
 * d'authentification ratée là où l'absence d'en-tête est une requête anonyme, et les deux ne
 * reçoivent pas la même réponse du serveur.
 */
export function authHeaders(token: string | null): { headers?: { Authorization: string } } {
  return token === null || token.trim() === ''
    ? {}
    : { headers: { Authorization: `Bearer ${token.trim()}` } };
}

/**
 * De quoi le porteur a l'air dans l'interface, sans le révéler.
 *
 * Quatre caractères suffisent à distinguer deux jetons quand on se demande lequel est collé ; le
 * reste ne se réaffiche pas, parce qu'un écran capable de rendre un secret lisible est un écran
 * qu'il suffit d'ouvrir pour le voler.
 */
export function credentialHint(token: string | null): string | null {
  if (token === null || token.trim().length < 4) {
    return null;
  }
  return `…${token.trim().slice(-4)}`;
}
