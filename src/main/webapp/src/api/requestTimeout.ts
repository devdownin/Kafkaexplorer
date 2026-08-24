// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/*
 * Le délai d'attente par défaut des requêtes du navigateur.
 *
 * `axios` n'en a aucun : une requête dont le serveur ne répond jamais reste pendante pour
 * toujours, et l'écran qui l'attend reste dans son état de chargement pour toujours avec elle.
 * Ce dépôt s'est fait prendre **trois fois** par ce même manque — le bouton Run de l'éditeur SQL
 * qui tournait sans fin, la colonne d'activité du tableau de bord restée en squelette, les deux
 * appels au modèle de Process Mining — et les trois correctifs ont posé une constante au point
 * d'appel concerné. Ce qui laisse tous les autres : au moment d'écrire ces lignes, **69 appels
 * axios répartis sur 17 fichiers, dont une douzaine portent un `timeout`**.
 *
 * Un défaut global les couvre tous, et surtout couvre le prochain appel écrit — qui héritait
 * jusqu'ici du problème et attendait son propre incident pour être corrigé.
 *
 * **Il est délibérément large.** Ce n'est pas un budget, c'est un filet : sa seule fonction est
 * qu'une requête finisse par échouer plutôt que de pendre indéfiniment. Les appels qui ont une
 * vraie raison d'attendre plus longtemps — l'inférence du modèle de données, une analyse LLM —
 * portent déjà leur propre `timeout` et le surchargent, dans les deux sens ; le défaut ne leur
 * retire rien. Le poser plus serré reviendrait à décider ici du budget d'appels dont ce fichier
 * ne sait rien, ce qui est exactement l'inverse de ce qu'il fait.
 */
export const DEFAULT_REQUEST_TIMEOUT_MS = 60_000;

/**
 * Pose le défaut sur l'instance axios partagée. Appelé une fois depuis `main.tsx`, sur le modèle
 * d'`installStaleChunkRecovery` — et **pas** au chargement d'un module importé par les pages :
 * la suite de tests remplace `axios` par un mock qui n'a pas de `defaults`, et une écriture à
 * l'import ferait tomber tout fichier de test qui touche au réseau.
 *
 * Rend l'instance pour être vérifiable sans toucher au singleton global.
 */
export interface TimeoutConfigurable {
  defaults: { timeout?: number };
}

export function installRequestTimeout(
  client: TimeoutConfigurable,
  timeoutMs: number = DEFAULT_REQUEST_TIMEOUT_MS,
): void {
  client.defaults.timeout = timeoutMs;
}
