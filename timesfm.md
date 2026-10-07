# Plan d’intégration TimesFM dans KafkaExplorer

## Objectif et périmètre

Prévoir les métriques explicitement sélectionnées à partir de leur historique PostgreSQL, puis
rendre les résultats consultables dans Metrics Forecast et le serveur MCP. Le pilote utilise
TimesFM 2.5 200M CPU, checkpoint `1d952420fba87f3c6dee4f240de0f1a0fbc790e3`, contexte de
512 points et horizon maximal de 60 points. Les collecteurs de métriques restent indépendants
de l’inférence. La collecte, la préparation et le client borné préexistent ; cette livraison
ajoute les dix priorités du pilote de production.

Les prévisions démarrent en SHADOW. ACTIVE exige une décision opérateur appuyée par des valeurs
réalisées et des comparaisons identiques. Un franchissement prédit reste une information ; aucun
envoi d’alerte, changement Kafka ou lancement d’inférence par MCP n’est ajouté.

## Plan détaillé et critères de succès

| Priorité | Réalisation | Critère de succès et preuve attendue |
|---|---|---|
| 1. Contrats et CI | Cinq noms MCP canoniques, enregistrement conditionnel du pilote ; types UI vérifiés contre les records Java, y compris les records imbriqués | Désactivé : aucun bean pilote et aucun outil de prévision. Les cinq annotations sont en lecture seule. Compilation, suites ciblées, vérifications documentaires et `./mvnw verify` ; CI requise avant fusion |
| 2. Persistance | Résultats, contexte, provenance, scores et contrôles opérateur dans des tables PostgreSQL additives | Réouverture conserve résultat et activation ; clé de déduplication distingue spécification, définition, entrée, profil, horizon et révision ; publication sans bail valide refusée |
| 3. Planification | Cadence configurable, boucle série, verrou transactionnel global et bail propriétaire ; plafond global des séries retenues | Deux instances ne lancent pas de travail sous le même verrou ; entrée identique sans réinférence ; propriétaire expiré ne publie pas ; limites 1–100 séries et purge bornée |
| 4. Évaluation rétrospective | Horizons arrivés à maturité, watermark sans chevauchement ; dernière valeur, moyenne mobile, saisonnier naïf et tendance linéaire | Même entraînement et mêmes valeurs réalisées pour toutes les stratégies ; MAE, MASE avec échelle d’entraînement, pinball, couverture et largeur corrects sur cas déterministes |
| 5. Franchissements MCP | Politiques explicites ABOVE/BELOW, bornes conservatrices, provenance complète | Retour limité aux séries autorisées et prévisions courantes TimesFM ; identifiant résultat, révision, fingerprints, direction, seuil et SHADOW/ACTIVE présents ; aucun effet de bord |
| 6. Interface | Panneau Metrics Forecast, historique, Q10/Q50/Q90, qualité et baselines ; activation avec confirmation | Désactivation et panne distinctes ; aucune activation automatique ; lectures annulées à la sortie ; activation refusée sans données réalisées suffisantes ou pendant rafraîchissement |
| 7. Dérive | Évaluation réalisée, seuils MAE/couverture et comparaison aux baselines ; DEGRADED persistant dans la lignée | Dérive retire ACTIVE, conserve les données et empêche réactivation ; révision opérateur puis nouvelle évaluation nécessaires à la reprise |
| 8. Fallback | Prévision ponctuelle saisonnière ou dernière valeur ; stratégie et cause explicites | BUSY, timeout, indisponibilité et sortie invalide restent visibles ; fallback sans bande de confiance ni franchissement ; historique invalide sans prévision ; compteurs par stratégie |
| 9. Autorisation | Série approuvée, métrique enrôlée, environnement exact, tous topics/groupes vérifiés avant lecture | Hash inconnu ou ressource hors périmètre : OUT_OF_SCOPE avant tout accès ; environnement non approuvé refuse ; DLP, audit et limites du catalogue existants conservés |
| 10. Ressources | Benchmark réel isolé à 1/4/8 threads, démarrage froid, RSS, p50/p95 ; entrée et concurrence bornées | Rapport avec révision et environnement, échecs UNMEASURED ; batch 1 et 4, contexte 512/horizon 60 ; décision GPU seulement après besoin démontré par charge représentative |

## Déploiement progressif

1. Enrôler les métriques dans le journal ; vérifier unités, sémantique gauge/counter, définition,
   fréquence, ressources SQL et labels. Désigner la série exacte et un environnement approuvé.
2. Précharger et vérifier le checkpoint CPU dans le volume persistant. Vérifier les métriques
   de pertes du journal et obtenir 512 points admissibles. Ne pas convertir un manque en zéro.
3. Activer le pilote avec 1 à 20 séries, cadence de cinq minutes et rétention de sept jours.
   Toutes les répliques partagent la même configuration et la même base dédiée. Tester redémarrage,
   contention, bail expiré et quota avec PostgreSQL réel.
4. Maintenir SHADOW jusqu’à plusieurs horizons réalisés et le minimum configuré de points évalués.
   Observer les quatre baselines sur les mêmes cohortes, MAE, MASE, pinball et couverture ; ajuster
   les exigences métier avant tout usage ACTIVE. Les seuils d’exemple ne sont pas universels.
5. Examiner les résultats dans Metrics Forecast et les outils MCP ; confirmer manuellement
   l’activation d’une série éligible. Réduire le périmètre si la qualité se dégrade.
6. Surveiller pertes, erreurs, contention, stratégie de secours, dérive, rétention et latence.
   Le retour à SHADOW est immédiat via le contrôle opérateur. Désactiver le pilote arrête sa
   planification et son exposition MCP ; les tables restent disponibles pour inspection.

La configuration complète et les contrats figurent dans [le runbook](docs/notes/timesfm-pilot.md).
Les changements de nom MCP imposent une adaptation des clients utilisant les cinq anciens outils
snapshot. L’API opérateur respecte la frontière d’accès des autres endpoints de gestion existants.

## Dimensionnement CPU et mémoire mesuré

Mesure du 3 octobre 2026 sur AMD EPYC 9V74, quota de 8 CPU et limite de 8 Gio, Python 3.12.14,
PyTorch 2.11.0+cpu / TimesFM 2.0.2. Un processus neuf par réglage ; checkpoint déjà en cache ;
20 inférences chaudes sur données synthétiques, une série, contexte 512 et horizon 60. Le froid
inclut construction du modèle et première inférence ; il exclut le téléchargement du checkpoint.

| Threads | Froid modèle + première inférence | Pic RSS processus | p50 inférence | p95 inférence |
|---|---:|---:|---:|---:|
| 1 | 3,409 s | 2 045 Mio | 0,802 s | 0,823 s |
| 4 | 2,742 s | 2 032 Mio | 0,369 s | 0,432 s |
| 8 | 2,688 s | 2 032 Mio | 0,261 s | 0,299 s |

Pour quatre séries par appel, le même protocole mesure :

| Threads | Froid | Pic RSS | p50 appel batch | p95 appel batch |
|---|---:|---:|---:|---:|
| 1 | 3,469 s | 2 031 Mio | 0,824 s | 0,912 s |
| 4 | 2,786 s | 2 032 Mio | 0,365 s | 0,409 s |
| 8 | 2,655 s | 2 031 Mio | 0,273 s | 0,310 s |

Le backend est configuré avec une taille de batch interne de quatre ; un appel d’une seule
série peut donc déjà payer ce coût. Les latences sont celles du batch complet, sans division
artificielle par le nombre de séries. [Rapport brut batch 4](docs/benchmarks/timesfm-cpu-batch4.json).

[Rapport brut batch 1](docs/benchmarks/timesfm-cpu-batch1.json). Le benchmark est disponible dans
[benchmark.py](services/timesfm/benchmark.py). Le budget de l’overlay reste 4 CPU / 8 Gio ; il
inclut une marge plutôt qu’une exigence minimale mesurée. Le RSS ci-dessus concerne le processus
modèle, sans HTTP, JVM, PostgreSQL ni autres applications. Les résultats synthétiques ne prouvent
ni la qualité métier ni un SLA de production. Le pilote n’a qu’une inférence en vol ; le client
et le worker appliquent la backpressure, sans file d’inférence illimitée. Aucun GPU n’est requis
pour ce profil mesuré ; reconsidérer uniquement si une charge réelle ne satisfait pas le budget.

## Vérification et limites de validation

Les tests déterministes couvrent baselines, MASE, quantiles, validation du pilote, les cinq noms
MCP et le refus de périmètre avant lecture. Les tests UI couvrent état désactivé, panne et annulation
HTTP ; les tests du service Python couvrent contrat, backpressure et worker. Les tests PostgreSQL
exercent stockage, concurrence de verrous, expiration et contrôles persistants dans une base réelle
via Docker en CI ou une URL de base isolée. La suite Maven complète reste nécessaire.

Contrôles ciblés exécutés : 44 tests Java, 34 tests Python, 6 tests UI, build frontend,
smoke avec le checkpoint réel et vérifications des contrats/configurations/liens.

Le premier essai Maven a été bloqué par le proxy réseau et la confiance TLS du JDK téléchargé.
Après configuration réseau temporaire utilisant le magasin de confiance système, la validation
Maven complète a réussi : 1 729 tests Java sans erreur (26 sauts liés aux infrastructures absentes)
et 1 762 tests frontend. Les validations TLS sont conservées ; aucun réglage du dépôt n’a été
modifié pour contourner les tests. Les benchmarks et 34 tests Python sont également validés.

PostgreSQL réel n’a pas été exécuté localement, faute de Docker. La CI du commit
`172915a7d31c8cf0c59e14dca55131684185fa80` a exécuté le test PostgreSQL du pilote sans saut,
et ses 1 729 tests Java n’ont aucune erreur (deux sauts). Cette campagne a ensuite révélé une
régression des mocks de la page Metrics : la nouvelle route Forecast recevait une réponse vide.
La PR corrige le mock et refuse proprement les réponses API incomplètes ; la suite frontend
complète est désormais verte localement. La CI du dernier commit reste exigée avant fusion.

La qualité affichée et le contrôle de dérive portent sur des blocs disjoints d’au moins 120 points
réalisés, jamais sur une cohorte isolée ; deux blocs en échec consécutifs verrouillent DEGRADED
(voir [le runbook](docs/notes/timesfm-pilot.md) et `TIMESFM-FORECAST-AUDIT.md`, F2). Il s’agit d’un
seuil statistique simple, pas d’une calibration. Q10–Q90 est un intervalle
nominal à 80 % ; les bornes unilatérales portent 0,9 nominal. Le pilote ne garantit aucune
probabilité de franchissement jointe sur l’horizon. Les résultats non mesurés restent explicites.
