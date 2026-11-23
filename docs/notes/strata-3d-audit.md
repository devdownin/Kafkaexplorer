# Kafka Strata 3D — audit technique et UX

**Date de revue :** 22 novembre 2026  
**Périmètre :** PR [#661](https://github.com/devdownin/Kafkaexplorer/pull/661), route `/strata`, fonctionnalités P0–P3, lots UI/UX A–C, intégration Kafka/Flink/Forecast, tests et documentation.  
**Méthode :** revue statique des services Java, contrats TypeScript, calculs de couverture, Three.js, interactions React, navigation, URLs et scénarios CI. Vérifications automatiques déléguées aux workflows GitHub Actions sur la tête de PR.

> **Portée de la conclusion :** ceci est un audit interne du code et de ses parcours. Ce n'est pas un audit de sécurité externe, une certification WCAG ni un essai de charge sur un cluster de production. Les contrôles non exécutés sont signalés explicitement.

## 1. Architecture et responsabilités

| Couche | Flux / responsabilité | Effets secondaires |
|---|---|---|
| Inventaire | `GET /api/dashboard` | Métadonnées lues |
| Activité topic | `GET /api/dashboard/activity`, douze buckets / 30 minutes | Offsets Kafka, bornés par le budget serveur |
| Focus partition | `GET /api/dashboard/partition-activity` | Mesure du seul topic focalisé, limite 24 partitions et budget global |
| Dépendances | `GET /api/lineage` | Liens uniquement issus des chemins Flink INSERT déclarés |
| Consommation | `GET /api/topic/{name}/consumers` | Groupe sélectionné, commit manquant représenté comme inconnu |
| Tracing | `POST /api/stream-flow/stream` | **Lecture demandée explicitement** ; scan de clés exactes, borné et annulable ; aucune écriture Kafka |
| Projection | `GET /api/forecasts`, `GET /api/forecasts/{seriesId}` | Lecture de forecast existant ; aucune inférence déclenchée par l'ouverture |
| UI 3D | Three.js, OrbitControls, scène avec invalidation de frames | Ressources GPU locales, libérées au démontage |

Les séries de production représentent des **différences d'offsets**, pas le nombre exact de messages présents ou transférés. Un lien Flink est une dépendance *déclarée*, non la preuve d'un passage causal d'un enregistrement. La corrélation Pearson est exploratoire, non une alerte ; q10–q90 est une estimation du modèle, non une garantie de couverture.

## 2. Constatations et corrections

| ID | Gravité | Constat | État |
|---|---|---|---|
| S3D-01 | P0 | Une réponse `counts` abrégée pouvait produire un total « complet » et quelques anneaux mesurés malgré une fenêtre incohérente. | **Corrigé** : validation structurelle du temps, de la cardinalité des buckets et des partitions dans `strataTime.ts` et `strataWorkspace.ts`, avec régression Vitest |
| S3D-02 | P0 | Les rubans de partitions, collectés séparément, pouvaient être projetés sur la lentille d'une autre fenêtre temporelle au moment d'un changement de bucket serveur. | **Corrigé** : `partitionFrameMatches`, masquage des rubans et explication du décalage ; test de frontières |
| S3D-03 | P1 | Le budget du service partition divisait par `buckets + 1`, sans compter les appels `earliest` et `latest`. | **Corrigé** : calcul `buckets + 3` dans `KafkaAdminService` |
| S3D-04 | P1 | Certaines tailles et totaux issus de données partielles étaient présentés comme mesurés. | **Corrigé** : chiffres conditionnés à la couverture ; valeurs absentes explicitement « Unknown » ; aucun zéro fictif |
| S3D-05 | P1 | L'interface à trois panneaux s'écrasait sur les écrans intermédiaires ; les commandes occupaient trop de place. | **Corrigé** : scène en premier sur écran étroit, agencement trois colonnes à partir de `2xl`, chronologie et couches intégrées |
| S3D-06 | P1 | La nouvelle interface n'exposait pas de raccourcis propres à la scène ; **Ctrl/⌘+K** appartient déjà à la palette globale. | **Corrigé** : `/`, `?`, `F`, `O`, `C`, espace, Échap, aide accessible ; aucun détournement de la palette globale |
| S3D-07 | P1 | Le popover des couches ne gérait pas Échap ni la fermeture au clic extérieur. | **Corrigé** : fermeture, retour de focus, cases à cocher standard |
| S3D-08 | P2 | Cibles de mini-carte trop petites et statut d'interaction peu explicite. | **Amélioré** : cibles de 24 px, états `aria-pressed`, focus visible, `Shift+Enter` pour le focus, liste de topics alternative |
| S3D-09 | P2 | Les mouvements du pointeur provoquaient des rendus React redondants même pour le même topic. | **Atténué** : déduplication par topic et déplacement minimal du curseur ; performances GPU à mesurer en conditions réelles |
| S3D-10 | P2 | L'ancienne implémentation « une heure » (l’ancien répertoire de composants Strata 3D) subsistait alors que `/strata` utilisait le nouveau module. | **Corrigé** : suppression des composants et tests orphelins ; guide historique conservé comme archive |
| S3D-11 | P2 | Changer le topic sélectionné laissait parfois la caméra focalisée sur l'ancien topic. | **Corrigé** : retour au contexte général lors de la sélection d'un autre topic |
| S3D-12 | P1 | Le lien de partage pouvait transmettre un fragment d'URL sans rapport, potentiellement sensible. | **Corrigé** : `shareStrataUrl` ne conserve que les paramètres Strata autorisés ; test de non-divulgation de requête et fragment |

## 3. Sécurité, confidentialité et maîtrise des ressources

- **API :** aucun endpoint de mutation introduit par Strata. Les traces sont des appels POST de **lecture**, déclenchés par l'utilisateur. Les états de progression, troncation, couverture et annulation sont conservés.
- **Limites :** 48 topics visibles au maximum, 12 intervalles synchronisés, 24 partitions focalisées au maximum ; le nombre de requêtes d'offset tient compte d'`earliest`, `latest` et des frontières temporelles. Le budget serveur s'applique toujours.
- **URL :** le lien de vue Strata ne conserve que le mode, les noms de topics, le filtre, le bucket et la qualité graphique. Il exclut les clés de trace et les contenus des messages. **Attention :** le lien explicite « Full Stream Flow » peut quant à lui contenir la clé saisie ; celle-ci peut apparaître dans l'historique navigateur et ne doit pas être partagée sans vérification.
- **Rendu :** les noms de topics et les sorties serveur passent par l'échappement React ou `canvas.fillText` ; le module n'injecte pas de HTML via `dangerouslySetInnerHTML`. Les textures et géométries Three.js sont libérées et le contexte WebGL perdu dispose d'une voie de repli.
- **Qualité des mesures :** la rétention, les partitions incomplètes, les commits inconnus et les fenêtres désalignées restent des **valeurs inconnues**, jamais un volume nul déduit sans preuve.
- **Forecast :** seule une série TimesFM `READY` explicitement affectée à un topic, avec unité cohérente et observations non imputées, est éligible ; la visualisation reste séparée des offsets Kafka.

## 4. Ergonomie, accessibilité et performances

| Axe | Contrôle | Observation |
|---|---|---|
| Navigateur clavier | Recherche `/`, aide `?`, focus `F`, vue d'ensemble `O`, comparaison `C`, replay espace, Échap | Implémenté sans intercepter **Ctrl/⌘+K** |
| Lecteur d'écran | Liste de topics, boutons et sélection de groupes HTML standards ; dialogue `aria-modal` avec retour de focus | Le canvas WebGL n'est pas l'unique accès aux données |
| Préférences système | Réduction de mouvement, transparence réduite, contraste fort et couleurs forcées | Styles de repli prévus pour les surfaces flottantes |
| Mise en page | Scène prioritaire sur mobile ; panneaux escamotables et redimensionnables sur grand écran | À valider aussi sur terminaux tactiles réels |
| Graphiques | Panneau A/B synchronisé, données manquantes comme des trous, couche anomaly/lineage optionnelle | Aucune corrélation confondue avec une causalité |
| GPU | Rendu à la demande, DPR adaptatif, limite de 48 topics et 24 partitions en détail | Pas encore de profilage GPU matériel |
| Couches visuelles | Masquage local des éléments, sans nouveau scan Kafka | Propriétés de la scène découplées des opérations de lecture |

## 5. Matrice de validation

| Vérification | Source de preuve | Critère de réussite |
|---|---|---|
| Contrats de données et raccourcis | `strataWorkspace.test.ts`, `strataTime.test.ts`, `strataShortcuts.test.ts` | Séries incomplètes rejetées ; bornes et raccourcis vérifiés |
| Diagnostics et projection | `strataDiagnostics.test.ts`, `strataForecast.test.ts`, `strataImmersive.test.ts` | Pas de fabrication de volume, de causalité ou de projection |
| Frontend | `bin/pr-preflight.sh` | TypeScript, Vite, ESLint et Vitest réussis |
| Backend | CI Maven/JDK 25, `KafkaAdminServiceActivityTest` (budget focus) | Compilation, tests d'intégration et budget d'offsets corrigé |
| WebGL réel | `docs/screenshots/strata-webgl-probe.mjs`, Chromium + SwiftShader | Contexte WebGL2, OrbitControls, partage d'URL, focus, clavier, couches et mobile |
| Intégration | CI Docker/Kafka/Forecast, sécurité et documentation | Aucun échec au dernier commit de la PR |

**Les résultats doivent être lus sur la tête de branche de la PR**. Les workflows déclenchés avant une correction et annulés par la concurrence GitHub ne prouvent pas la validation du code le plus récent.

## 6. Risques résiduels et recommandations

1. **Revue GPU matérielle (P1) :** l'exécution Chromium/SwiftShader ne remplace pas un test interactif sur GPU réel, notamment sur iGPU et appareils mobiles à forte densité de pixels.
2. **Accessibilité formelle (P1) :** compléter avec audit outillé WCAG/axe et revue humaine des contrastes, de l'ordre de focus et des cibles dans les deux thèmes. Les contrôles du code ne constituent pas une certification.
3. **Charge et latence réelles (P2) :** mesurer le temps de réponse sur des topics fortement partitionnés et la mémoire GPU avec 48 colonnes ; vérifier les quotas de brokers et les lecteurs concurrents en environnement de préproduction.
4. **Historique de traces (P2) :** lorsque les clés Kafka sont sensibles, éviter de diffuser les liens « Full Stream Flow » contenant la clé dans l'URL. Le partage de **vue Strata** reste volontairement exempt de clé.
5. **Nature des flux (contrainte produit) :** les anneaux reflètent la production d'offsets par intervalle, et non un flux de particules de messages individuellement suivis. Le replay est borné à la dernière fenêtre mesurée ; aucune conservation historique n'est implicite.

## 7. Décision qualité

**État du périmètre initial : PR #661 fusionnée dans main le 23 novembre 2026.** Les correctifs de sûreté des mesures, du budget Kafka et de navigation sont intégrés. La revue GPU matérielle, l'accessibilité formelle et les essais de charge restent ouverts ; aucune certification n'est sous-entendue.

Document à tenir à jour avec la [PR #661](https://github.com/devdownin/Kafkaexplorer/pull/661) et le [guide d'exploitation Strata](../STRATA-3D.md).


## 8. Audit incrémental — lots D et E (23 novembre 2026)

| Contrôle | Implantation | Vérification / limite |
|---|---|---|
| D — FPS et coût du rendu | Télémétrie locale toutes les 3 secondes : FPS seulement avec rendu soutenu, draw calls et objets Three.js, durée de reconstruction | Les compteurs de géométrie/texture ne sont **pas** une mesure de mémoire GPU. Scène immobile = rendu à la demande, pas 0 FPS |
| D — Sûreté de l'instrumentation | Aucune télémétrie envoyée, aucune nouvelle lecture Kafka, timers arrêtés au démontage, pause pendant onglet masqué | Contrôler en conditions réelles la consommation d'un mobile |
| D — Préférences d'accessibilité | Changement dynamique de la préférence système de réduction des animations, panneau natif HTML accessible au clavier | Pas de certification WCAG, test humain nécessaire |
| E — Provenance des signaux | Déviations sur offsets horodatés et couverture complète pour l'intervalle, lag uniquement du groupe sélectionné | Indices d'exploration et non preuve de saturation ou de causalité |
| E — Gestion de l'inconnu | Mesures incomplètes, indisponibles et obsolètes exclues des signaux de production | Aucune conclusion « groupe sain » en l'absence de mesures |
| E — Captures | Export JSON manuel, versionné et allowlisté ; seuls données agrégées, contexte temporel, chemins Flink déclarés et groupe explicitement sélectionné | Pas d'enregistrement, clé Kafka, message ou trace brute ; les noms métier peuvent rester sensibles et requièrent une revue avant partage |
| E — Navigation | Étapes natives clavier, sans scan additionnel, lien vers la trace volontaire | À contrôler sous 200 % de zoom et lecteur d'écran matériel |
| Régressions | Vitest ciblé et extension du probe Chromium/WebGL | La conclusion CI doit être vérifiée sur la tête de la nouvelle PR ; aucune réussite de CI n'est présumée ici |

**Critères de sortie :** lint, types, tests unitaires, docs, CI d'intégration,
CodeQL et sécurité au vert sur le dernier commit. Vérifier le téléchargement
local dans le navigateur, le suivi des changements de réduction de mouvement,
les thèmes clair/sombre, le focus et les tailles tactiles. Les essais GPU sur
matériel et l'audit WCAG externe restent **à réaliser** même après CI réussie.
