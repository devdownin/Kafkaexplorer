# Étude : remplacer la lecture directe par une lecture Flink bornée

L'éditeur propose désormais deux choix explicites : Flink SQL (sans repli automatique) et Kafka Direct (exploration bornée). Le second lit les enregistrements depuis l'extrémité choisie et indique combien ont été récupérés. Un `LIMIT` SQL borne les lignes **après** filtrage ; il ne définit pas, à lui seul, une tranche de Kafka.

## Question à résoudre

Pour « les N derniers messages déjà présents », `scan.startup.mode = latest-offset` n'est pas équivalent : le consommateur commence après ces messages. Une lecture Flink équivalente doit prendre un instantané des offsets de fin par partition, choisir des offsets de début, puis terminer exactement à cette frontière. `scan.bounded.mode = latest-offset` borne la fin du scan, mais ne choisit pas à lui seul les N messages de départ. Sur plusieurs partitions, « N derniers » demande aussi une règle de fusion explicite (offset par partition, horodatage Kafka ou temps métier).

Les tables créées par l'utilisateur peuvent porter leurs propres options et watermarks : ne pas modifier leur DDL pour imposer un mode borné. Pour les tables temporaires dérivées automatiquement, un prototype peut créer une table isolée par requête avec des offsets de départ et de fin figés. Vérifier d'abord que la version du connecteur du dépôt (`flink-connector-kafka` 5.0.0-2.2) accepte cette combinaison et la portée des hints `OPTIONS` utilisés.

## Prototype et critères

1. Fixer un topic de 1 puis 8 partitions, avec des ajouts pendant la requête, des messages nuls et des horodatages désordonnés. Capturer les offsets retenus et les inclure dans la réponse.
2. Comparer, sur une même tranche, projection, `WHERE` sélectif, `COUNT`, fenêtre TUMBLE et jointure. Comparer lignes, colonnes, avertissements et comportement au dépassement du délai. Une agrégation ne doit être annoncée comme complète que si la tranche bornée est entièrement lue ; elle ne représente pas forcément tout le topic.
3. Mesurer 30 exécutions après échauffement, à 1 et 8 clients : p50/p95 de `/run-sync`, durée de planification, messages lus, threads, pic de heap et taux d'annulation. Répéter à 10 000 et 100 000 messages et à 100 puis 1 000 topics pour séparer le coût du catalogue de celui du moteur.
4. N'activer un éventuel remplacement que si la sémantique de la tranche est identique et le p95 acceptable. Conserver le mode Kafka Direct explicite tant que ces critères ne sont pas vérifiés.

Référence : [options du connecteur Kafka Flink](https://nightlies.apache.org/flink/flink-docs-release-1.19/docs/connectors/table/kafka/), notamment `scan.startup.mode` et `scan.bounded.mode`. Le prototype doit confirmer leur comportement avec la version effectivement embarquée par ce dépôt.

## Premier essai reproductible

`KafkaClusterIntegrationTest.boundedFlinkOffsetPrototypeMatchesDirectSnapshot` déclare une table Flink isolée sur trois partitions dont les débuts sont à l'offset 2 et les fins à l'offset 4. Il compare les couples `(partition, offset)` rendus par Flink aux messages lus directement, puis leur nombre à `COUNT(*)`. Ce test constitue une vérification du connecteur effectif avec Docker et n'active aucun changement de moteur dans l'éditeur.

Le premier passage CI a confirmé les six offsets mais a coupé le changelog de `COUNT(*)` au plafond de dix lignes : la dernière correction affichée valait 5. Six messages peuvent produire plus de six lignes de corrections. Le test collecte donc vingt lignes au maximum et exige que ce plafond ne soit pas atteint avant de comparer la valeur finale.

Cette tranche fixe ne résout pas encore « les N derniers messages » en présence de nouvelles écritures ni les divergences possibles pour `WHERE`, fenêtres, jointures et délais. Les mesures et ces cas restent nécessaires avant d'envisager Flink SQL comme mode unique.

## Tranche figée sur huit partitions

`boundedFlinkSnapshotKeepsSelectiveFilterStableAfterAppends` capture les offsets de début et de fin de huit partitions, lit la tranche avec Kafka Direct, puis ajoute des messages qui correspondent au filtre **avant** de lancer Flink. Il compare les couples partition/offset sélectionnés par `WHERE status = 'KEEP'` et la valeur finale de `COUNT(*)` avec la tranche initiale. L'objectif est de prouver que les nouveaux messages n'entrent pas dans le résultat malgré un filtre qui les sélectionnerait.

Ce cas n'établit ni l'ordre global des « N derniers » entre partitions, ni l'équivalence des fenêtres et jointures, ni le coût du démarrage de Flink. Ces points restent des critères de décision, pas des hypothèses validées par les deux tests.

## Définition de « Latest » aujourd'hui

Kafka Direct capture l'offset de fin de chaque partition et démarre à `max(beginning, end - (N / partitions + 1))`. Il collecte ensuite **au plus N messages au total**, dans l'ordre où les polls les livrent. « Latest » veut donc dire *échantillon borné des queues de partitions*, ni les N plus grands horodatages Kafka ni les N derniers selon un ordre global. Les bornes de début et de fin affichées dans le résultat rendent cette définition vérifiable. Une partition très active peut dépasser son quota tandis qu'une autre est vide ; le résultat peut alors contenir moins de N messages bien qu'il reste des messages plus anciens sur la première.

Une véritable option « N derniers globaux » exigerait une décision de produit distincte : horodatage Kafka décroissant, puis partition et offset pour départager les égalités, avec un coût de lecture potentiellement supérieur à N. Elle n'est pas introduite par ce prototype. La capture d'offsets de Flink reproduit une **tranche choisie**, pas automatiquement la sélection des N derniers globaux.

## Fenêtres et jointures

Les tests d'intégration supplémentaires utilisent une source bornée avec un watermark et des horodatages désordonnés pour comparer `TUMBLE` à la fenêtre du lecteur direct ; un message nul est inclus. Une autre paire de topics bornés vérifie une jointure interne. Le lecteur direct doit refuser une jointure ou une sous-requête au lieu de lire seulement la première table. Les fenêtres `HOP`, `SESSION`, les événements plus tardifs que le watermark et les jointures externes restent hors de la comparaison de ce lot.

## Banc de mesure reproductible

`scripts/benchmark-sql-engines.py` appelle le même endpoint `/api/query/run-sync` pour les deux moteurs. Préparer des données et une table Flink **bornée aux mêmes offsets** que la tranche directe ; fournir les deux SQL dans des fichiers. Exemple, après avoir vérifié que la tranche et le catalogue contiennent réellement 10 000 messages et 100 topics :

```bash
python3 scripts/benchmark-sql-engines.py \
  --url http://localhost:8080 \
  --flink-sql-file /tmp/flink-bounded.sql \
  --direct-sql-file /tmp/kafka-direct.sql \
  --flink-explain-sql-file /tmp/flink-explain.sql \
  --dataset-records 10000 --catalog-topics 100 \
  --runs 30 --warmup 3 --concurrency 1 8 \
  --output /tmp/sql-benchmark-10k-100.json
```

Répéter pour 100 000 messages, puis 1 000 topics. Le JSON contient p50/p95 client et serveur, nombre de messages et état de couverture directe, taux de réponses annulées, et pic de heap et de threads échantillonnés via `/actuator/prometheus` lorsqu'il est accessible. `EXPLAIN` donne un **proxy de planification** incluant HTTP et prise du runtime, pas le temps interne exact du planner. `--cancel-after-ms` lance un essai d'annulation séparé ; ne pas mélanger ces mesures aux latences normales. Le jeton facultatif est lu depuis `KEX_BENCH_TOKEN`. Le script ne crée pas les topics : ses tailles sont des étiquettes fournies par l'appelant, à vérifier avant publication d'un résultat. Aucun chiffre p95 n'est revendiqué tant qu'une campagne sur les quatre configurations n'a pas été exécutée.
