/**
 * Ce que la pastille de connexion du header a le droit d'affirmer.
 *
 * Elle affichait `explorer.cluster-name`, dont le défaut livré était « KRAFT 4.2 » : une version
 * de Kafka annoncée à chaque déploiement sans que rien ne l'ait demandée au broker, et qui ne
 * bouge pas après un repointage à chaud par `POST /api/config`. Or c'est précisément l'élément
 * dont le métier est de dire à quoi on est connecté.
 *
 * La règle appliquée ici : le libellé est un **nom** choisi au déploiement, et ce qui est
 * **vérifié** — l'adresse d'amorçage effective — l'accompagne dans l'infobulle. Quand rien n'a
 * encore répondu, on le dit plutôt que d'afficher un nom en dur.
 *
 * Fonctions pures → testables sans monter le header.
 */

export interface ConnectionInfo {
  isHealthy: boolean;
  /** `explorer.cluster-name` tel que renvoyé par `/api/dashboard`. Vide avant la première réponse. */
  clusterName?: string;
  /** Adresse d'amorçage effective côté serveur. Absente avant la première réponse. */
  bootstrapServers?: string;
}

/** Texte affiché dans la pastille. */
export function connectionLabel({ isHealthy, clusterName }: ConnectionInfo): string {
  if (!isHealthy) return 'Disconnected';
  const name = (clusterName ?? '').trim();
  // Avant la première réponse, personne ne sait comment s'appelle ce cluster. « Connecting… »
  // est la seule chose vraie à ce moment-là ; le libellé en dur qui tenait cette place se
  // trouvait être faux dans toute installation qui n'était pas celle du développeur.
  return name || 'Connecting…';
}

/**
 * Infobulle : le nom, puis l'adresse réellement utilisée.
 *
 * Déconnecté, l'adresse est la seule information utile — savoir *laquelle* ne répond pas est le
 * début du diagnostic, et « Disconnected » tout court n'a jamais aidé personne.
 */
export function connectionTitle({ isHealthy, clusterName, bootstrapServers }: ConnectionInfo): string {
  const name = (clusterName ?? '').trim();
  const bootstrap = (bootstrapServers ?? '').trim();

  if (!isHealthy) {
    return bootstrap ? `Disconnected — no answer from ${bootstrap}` : 'Disconnected';
  }
  if (!name && !bootstrap) return 'Checking the connection…';

  const target = [name, bootstrap].filter(Boolean).join(' · ');
  return `Connected — ${target}`;
}
