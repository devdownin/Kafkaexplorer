// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import { toTableName } from './pages/sqlScope';

/**
 * « Est-ce un topic que l'application s'écrit à elle-même ? », côté navigateur.
 *
 * La question se posait déjà à quatre endroits — la sélection en masse du modèle de données, les
 * requêtes de démarrage de l'éditeur SQL, le choix d'une table de destination — et chacun y
 * répondait par un littéral `'internal.'` écrit sur place. C'était juste tant que ce préfixe était
 * une constante ; `explorer.internal-topic-prefix` en a fait un réglage, et ces quatre littéraux
 * ont cessé d'être le marqueur au moment même où quelqu'un pouvait le configurer. Un déploiement
 * qui pose `acme.` verrait ses propres topics reproposés comme s'ils appartenaient au cluster
 * exploré, ce que le serveur, lui, ne fait plus (`ExplorerConfig.isInternalTopic`).
 *
 * **Le préfixe est un paramètre, pas un état de module.** Ces prédicats sont appelés depuis des
 * modules purs et testés ; y lire une variable mutable les rendrait impurs et leurs tests
 * dépendants d'un ordre d'initialisation. Il vaut `''` par défaut, donc un appelant qui ne le
 * passe pas obtient exactement le comportement d'avant — c'est ce qui rend le changement sûr sur
 * les points d'appel qu'on n'a pas touchés.
 */

/** Un nom de **topic** : `internal.audit.history`, ou `acme.internal.audit.history`. */
export function isInternalTopic(name: string, prefix = ''): boolean {
  return name.startsWith(`${prefix}internal.`);
}

/**
 * Un nom de **table Flink**, où `toTableName` a remplacé les points par des underscores :
 * `internal_audit_history`, ou `acme_internal_audit_history`.
 *
 * Le préfixe passe par la même transformation plutôt que d'être concaténé tel quel — sans ça,
 * `acme.` produirait `acme.internal_` et ne correspondrait à aucune table. Et la comparaison
 * s'arrête à `internal` sans séparateur final, ce que faisaient déjà les littéraux qu'elle
 * remplace : une table générée peut s'appeler `internal_audit_history` comme `internalaudit`
 * selon ce que le topic portait.
 */
export function isInternalTable(name: string, prefix = ''): boolean {
  return name.toLowerCase().startsWith(toTableName(`${prefix}internal`).toLowerCase());
}
