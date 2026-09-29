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

Cette tranche fixe ne résout pas encore « les N derniers messages » en présence de nouvelles écritures ni les divergences possibles pour `WHERE`, fenêtres, jointures et délais. Les mesures et ces cas restent nécessaires avant d'envisager Flink SQL comme mode unique.
