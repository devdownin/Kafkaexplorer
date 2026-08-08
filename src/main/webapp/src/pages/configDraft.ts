/**
 * Brouillon de la page Réglages — sans les secrets.
 *
 * Un brouillon vit dans `localStorage`, c'est-à-dire en clair, lisible par tout script de la page
 * et persistant bien au-delà de la session. Le dépôt applique déjà cette règle côté serveur : tout
 * ce qui ressemble à `*password*`, `*secret*` ou `sasl.jaas.config` est masqué avant de quitter le
 * backend (`DdlGeneratorService.maskSensitiveProperties`). La même liste vaut ici : un mot de passe
 * de keystore retapé est un moindre mal comparé à un mot de passe de keystore stocké sur le disque
 * du poste par confort d'ergonomie.
 */

/** Champs jamais écrits dans un brouillon. */
export const SECRET_FIELDS = [
  'truststorePassword',
  'keystorePassword',
  'keyPassword',
  'confluentSecret',
  'llmApiKey',
] as const;

export type SecretField = (typeof SECRET_FIELDS)[number];

/**
 * Ce qu'un brouillon a le droit de porter : la saisie, et rien d'autre.
 *
 * Une liste blanche plutôt qu'une liste noire — un champ ajouté plus tard à `ClusterConfig` reste
 * hors brouillon tant qu'il n'est pas inscrit ici, ce qui est le bon défaut quand la liste noire
 * qu'on oublierait de compléter s'appelle « secrets ». Les champs d'état renvoyés par le serveur
 * (`isConnected`, `llmApiKeyConfigured`, `llmProviderLabel`…) en sont exclus pour une autre raison :
 * ils décrivent le cluster, pas la saisie, et les restaurer ferait afficher « connecté » sur un
 * formulaire modifié depuis.
 */
export const DRAFTABLE_FIELDS = [
  'bootstrapServers',
  'mode',
  'truststorePath',
  'keystorePath',
  'confluentKey',
  'llmProvider',
  'llmBaseUrl',
  'llmModel',
  'llmUseRag',
  'llmCollection',
  'llmRequestTimeoutSeconds',
  'llmMaxTokens',
  'llmSnapshotWindowSize',
  'llmSnapshotWindowTimeoutSeconds',
] as const;

const DRAFTABLE = new Set<string>(DRAFTABLE_FIELDS);

/** Copie ne portant que les champs de saisie — ni secrets, ni état serveur. */
export function draftableOnly<T extends object>(config: T): Partial<T> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(config)) {
    if (!DRAFTABLE.has(k) || v === undefined) continue;
    out[k] = v;
  }
  return out as Partial<T>;
}

/** `true` si l'objet porte au moins un champ secret — le filet des tests. */
export function containsSecret(value: unknown): boolean {
  return (
    !!value &&
    typeof value === 'object' &&
    Object.keys(value as object).some((k) => (SECRET_FIELDS as readonly string[]).includes(k))
  );
}

/**
 * Applique un brouillon par-dessus la configuration renvoyée par le serveur.
 *
 * L'ordre compte : le serveur fournit la base (y compris les secrets, que le brouillon n'a pas),
 * la saisie non enregistrée passe au-dessus. Un brouillon écrit par une version antérieure peut
 * porter des clés retirées depuis ; le même filtre les écarte.
 */
export function mergeDraft<T extends object>(server: T, draft: Partial<T> | null): T {
  if (!draft) return server;
  return { ...server, ...draftableOnly(draft) };
}
