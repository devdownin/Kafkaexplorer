# Audit — gestion des groupes de consommateurs

Revue complète du traitement des groupes de consommateurs par l'application : les groupes qu'elle
**crée** sur le cluster de l'utilisateur, et ceux qu'elle **lit** pour répondre à « qui consomme ce
topic, et où en est-il ». Périmètre : `KafkaAdminService.getTopicConsumers`, `ExplorerConsumerGroups`,
`ConsumerGroupLag` / `TopicConsumers` / `PartitionLag`, `ConsumerLagMetrics`,
`AuditService.consumerLagIssues`, `DdlGeneratorService`, `TopicConsumersPanel` / `topicConsumers.ts`,
`Cluster.tsx`.

Tout ce qui est listé ici est **corrigé sur cette branche**, avec un test qui échoue sans le correctif.
La section finale liste ce qui a été constaté et délibérément laissé ouvert.

## Le fil conducteur

Un seul défaut se répète sous six formes : **une mesure qui n'a pas pu être prise revient à zéro, et
zéro se lit comme une réponse.** Zéro membre, zéro retard, zéro groupe. Chaque fois, une valeur par
défaut anodine s'est retrouvée présentée comme un constat — jusqu'à devenir un constat *critique*
dans l'audit, une jauge « à jour » dans Prometheus, ou une phrase affirmative dans l'UI.

C'est exactement le défaut que le dépôt a déjà corrigé ailleurs (`PartitionLag.committedOffset` à
`null` plutôt qu'à 0, `TopicConsumers.unavailable` plutôt qu'une liste vide). La correction n'était
simplement pas allée jusqu'au bout de ses propres conséquences.

## Bugs

### B1 — Un groupe non décrit était noté « bloqué », et l'audit en faisait un constat critique

`health()` renvoyait `STALLED` dès que `assignedMembers == 0 && totalLag > 0`. Or un groupe dont
`describeConsumerGroups` a échoué arrive avec zéro membre **faute de réponse, pas faute de membre** —
le service prend soin d'avertir « leur retard est reporté, pas leurs assignations », mais `health()`
n'en savait rien et concluait « rien ne draine ce topic ». `AuditService` remonte `STALLED` en
**CRITICAL**.

Le cas n'est pas théorique : `describeConsumerGroups` (API consumer classique) ne répond pas pour un
groupe de type **STREAMS**, dont les offsets se lisent pourtant parfaitement. Une application Kafka
Streams en parfaite santé était donc rapportée comme un backlog abandonné, sur chacun de ses topics.

*Correctif* : `ConsumerGroupLag.membersKnown`. `STALLED` l'exige désormais ; sans membres connus, la
question ne peut pas être tranchée, donc elle ne l'est pas. Même règle côté front (`healthOf`), qui
duplique volontairement ce classement.

### B2 — Une lecture échouée était indiscernable d'un cluster sans groupe

`TopicConsumers.unavailable(...)` rendait `groupsExamined = 0`, `groupsInCluster = 0` — les mêmes
compteurs qu'un cluster réellement vide. Conséquences en cascade :

- le panneau affichait **« The cluster has no client group at all »** surmontant un état vide
  **« No consumer group reads this topic »** : deux affirmations catégoriques sur une question qui
  n'avait pas pu être posée ;
- `ConsumerLagMetrics` ne pouvait pas savoir s'il fallait conserver la dernière valeur d'une jauge ou
  l'oublier ;
- `AuditService` ne produisait **aucun** constat, ce qui se lit « tous les groupes de ce topic vont
  bien ».

*Correctif* : `TopicConsumers.available`. Le panneau rend un état d'erreur portant la raison du
serveur, l'export garde ses valeurs, l'audit émet un WARNING nommant la cause.

### B3 — Prometheus publiait « retard nul » pour un groupe dont le retard n'avait pas pu être lu

`ConsumerGroupLag.failed(...)` porte `totalLag = 0` (il n'y a rien à porter). La boucle d'export ne
regardait pas `error` et publiait ce zéro. En jauge, un zéro se lit **« à jour »** : la panne d'un
coordinateur éteignait précisément l'alerte qui devait sonner. Le contrat annoncé — « une lecture
ratée garde sa dernière valeur » — était tenu pour un échec global et violé pour un échec par groupe.

*Correctif* : une ligne en `error` est ignorée ; sa jauge garde sa dernière valeur.

### B4 — Une jauge n'était jamais retirée, donc une alerte ne pouvait plus retomber

Aucun code ne supprimait de série. Un consommateur décommissionné exportait à vie le retard de son
dernier jour, sous une alerte qu'aucun événement ne pouvait plus faire redescendre — et sa place
restait comptée sous `explorer.lag-metrics-max-series`, au détriment des groupes vivants.

*Correctif* : `dropVanished` retire les séries des groupes qu'une lecture **réussie** ne renvoie plus
(la distinction avec B2/B3 est tout l'intérêt), et rend leur place au plafond. Il tourne **avant**
l'export, sans quoi la place libérée n'est pas disponible pour le remplaçant du même tour.

### B5 — Le panneau perdait la raison portée par chaque groupe

Le serveur construit une ligne `ConsumerGroupLag.failed(groupId, type, reason)` plutôt que de laisser
tomber le groupe — « n'a pas pu être lu » et « ne lit pas ce topic » étant deux réponses. La `reason`
n'était affichée **nulle part** : la pastille disait « Unreadable », le détail déplié était un tableau
de partitions vide, et la colonne Partitions annonçait `0/0 read`. Un verdict sans diagnostic.

*Correctif* : la raison est dans l'infobulle de la pastille et dans le détail déplié ; `0/0 read`
devient `—` ; une appartenance inconnue s'affiche `—` et non `0/0`.

### B6 — Le dénominateur affiché n'était pas celui du numérateur

`describeScope` annonçait « all N of the cluster's groups read » avec `N = groupsExamined`, c'est-à-dire
*après* exclusion des share groups et des groupes de l'application. Sur un cluster de cinquante groupes
dont quarante-sept sont écartés : « all 3 of the cluster's groups read ». Et en cas de troncature,
« 200 of the cluster's 3000 » mélangeait un numérateur d'après exclusions avec un total d'avant.

*Correctif* : `TopicConsumers.groupsEligible` (après exclusions, avant plafond) est le dénominateur ;
le total du cluster reste dit, comme contexte. Le cas « zéro examiné mais des groupes au compteur »
ne dit plus « n'ont pas pu être lus » — ils l'ont été, ils ont été écartés.

### B7 — `flink_table_*` n'était pas reconnu comme un groupe de l'application

`DdlGeneratorService` écrit `'properties.group.id' = 'flink_table_<table>'` dans chaque table Flink
générée — un groupe que l'application fait exister sur le cluster de l'utilisateur, et que
`isExplorerGroup()` ne connaissait pas. Il occupait donc une place sous le plafond de 200, apparaissait
comme un tiers dans la liste du cluster, et aurait été noté `STALLED` s'il avait acquis des offsets
commités. Le risque réel est faible (le connecteur ne commite qu'au checkpoint, qu'un SELECT local
borné ne prend jamais) mais la protection ne coûte rien.

*Correctif* : le préfixe est reconnu, et le DDL généré fixe explicitement
`'properties.enable.auto.commit' = 'false'` — la même règle que `ExplorerConsumerGroups.configure`
applique à tous les autres lecteurs.

### B8 — La page Cluster promettait le contraire de ce qu'elle affichait

Son état vide affirmait « the explorer's own sampling consumers use manual partition assignment and
never register ». C'est vrai des lecteurs par `assign()`, mais **`KafkaLiveConsumer` fait `subscribe()`** :
une session Process Mining vivante enregistre bien un groupe, qui s'affichait ici sans distinction.

*Correctif* : le champ `explorer` sur chaque groupe, une pastille « this app » dans le tableau, et un
texte qui dit ce qui se passe réellement.

### B9 — Une ligne illisible était triée parmi les groupes à jour

Le tri se faisait sur `totalLag` décroissant. Une ligne en erreur porte zéro, donc atterrissait tout en
bas, au milieu des groupes sans retard — le seul endroit où elle ne doit pas être.

*Correctif* : les lignes illisibles passent en tête.

### B10 — Le cache ignorait `maxGroups`

`@Cacheable(key = "#topic")` : un appelant demandant une lecture plus large recevait la réponse
tronquée d'un appelant précédent, `truncated` compris. Sans conséquence aujourd'hui (une seule valeur
de configuration alimente les trois appelants), mais c'est le genre d'hypothèse qui se périme.

*Correctif* : la clé porte `maxGroups`.

### B11 — `KafkaSnapshotReader` reposait le nom du groupe et l'interdiction de commiter à la main

`ExplorerConsumerGroups.configure()` existe pour une raison énoncée dans sa javadoc : poser le
`group.id` et `enable.auto.commit=false` **ensemble**, « pour que les deux ne puissent plus diverger ».
Ce lecteur les posait séparément — le nom fabriqué dans `consume()`, l'auto-commit dans
`buildConsumerProperties()` — donc correct aujourd'hui, et exactement la structure qui a produit les
groupes fantômes hier.

*Correctif* : un seul appel à `configure(props, "snapshot")`, le paramètre `groupId` disparaît.

### B12 — Une jauge figée était indiscernable d'une jauge fraîche

B3 et B4 posent la bonne règle — une lecture ratée garde sa dernière valeur, un groupe disparu perd
la sienne — mais laissent l'opérateur sans moyen de savoir laquelle des deux il regarde. Une alerte
`kafka_consumer_group_lag > 10000` se déclenche exactement pareil que le retard soit réel et bloqué
ou simplement plus mesuré depuis une heure, et la personne qu'elle réveille à 3 h du matin n'a que la
trace DEBUG du serveur pour trancher. Une valeur qu'on ne peut pas dater est une valeur sur laquelle
on ne devrait pas agir, ce qui annule l'intérêt de l'exporter.

*Correctif* : `kafka_consumer_group_lag_last_success_timestamp_seconds{group,topic}`, posée
uniquement sur une ligne réellement mesurée. L'alerte peut alors exiger les deux :

```promql
kafka_consumer_group_lag > 10000
  and time() - kafka_consumer_group_lag_last_success_timestamp_seconds < 120
```

Un horodatage plutôt qu'un booléen : même cardinalité, et il porte *à quel point* c'est périmé, ce
dont un seuil a besoin. **Le coût est assumé** : une quatrième série par groupe×topic, soit un tiers
de plus, sur une fonctionnalité construite autour de sa parcimonie. Le plafond
`explorer.lag-metrics-max-series` compte des paires groupe×topic (`admits` n'indexe que la série de
retard), donc ce qu'il borne ne change pas.

## Optimisation

### O1 — L'audit relisait tous les groupes du cluster, une fois par topic

`consumerLagIssues` appelle `getTopicConsumers(topic, …)`, qui **liste tous les groupes du cluster,
en décrit jusqu'à deux cents et lit leurs offsets**. Cet appel était fait pour chaque topic audité.
Sur trois cents topics et deux cents groupes : trois cents `ListGroups`, soixante mille descriptions
et autant d'`OffsetFetch` — pour une réponse qui ne varie pas d'un topic à l'autre. Le cache Caffeine
de 30 s n'aidait pas : il est indexé par topic, et une passe d'audit dure des minutes.

*Correctif* : `KafkaAdminService.groupSnapshot(maxGroups, restrictTo)` prend la photo une fois par
run ; `getTopicConsumers(topic, snapshot)` en dérive la vue d'un topic en ne lisant que ses end
offsets. `restrictTo = null` récupère les offsets de tous les topics dans le même appel, ce qui est
ce qui rend la photo réutilisable. L'endpoint HTTP, lui, garde la restriction à un topic.

**Ce dernier point a failli être perdu dans la refonte** : la première version résolvait les
partitions du topic *après* avoir pris la photo, donc n'avait plus rien avec quoi la restreindre et
passait `null` — une lecture mono-topic rapatriait les offsets de tous les groupes sur tous les
topics. `resolvePartitions` est appelé en premier, et
`restrictsTheOffsetFetchToTheTopicItWasAskedAbout` épingle la restriction, faute de quoi rien dans la
structure du code ne l'impose.

### O2 — Une lecture échouée était mise en cache 30 s

`@Cacheable` mémorisait aussi les réponses `unavailable`. Un incident réseau bref figeait donc
l'erreur une demi-minute, et le bouton **Refresh** du panneau — le seul geste qui existe pour
réessayer — rejouait l'erreur en cache au lieu de reposer la question. Mettre une réponse en cache,
c'est parier qu'elle est encore vraie ; ce pari n'a aucun sens sur « on n'a pas pu demander ».

*Correctif* : `unless = "!#result.available()"`.

**Ce que ça coûte, dit explicitement** : les positions commitées datent de la prise de la photo, les
end offsets de l'instant où chaque topic est audité. Un retard ne peut donc être que **surestimé**,
jamais sous-estimé — et aucun des constats remontés ne repose sur un retard surestimé (`STALLED` exige
zéro membre assigné, `AHEAD` un retard négatif). La note de portée du rapport l'énonce.

Une photo prise une fois et gardée pour tout le run était toutefois l'autre moitié du piège : sur une
demi-heure, le retard est surestimé d'une demi-heure de trafic — sûr dans sa direction, inexploitable
dans son ordre de grandeur. `explorer.audit-group-snapshot-ttl-ms` (60 s par défaut, `0` pour ne la
prendre qu'une fois) borne cette péremption sans rendre le gain : un run de trente minutes paie une
trentaine de lectures au lieu d'une par topic. `GroupSnapshotHolder` rafraîchit sous verrou, appel
réseau compris — laisser trois des quatre workers lire une photo périmée pendant que le quatrième la
reprend achèterait quelques secondes de parallélisme au prix d'un rapport dont les lignes auraient été
mesurées à des instants différents.

## Contrat de types

`ConsumerGroupLag`, `PartitionLag` et `TopicConsumers` étaient déclarés à la main dans
`components/topic/topicConsumers.ts`, hors de `api/types.ts` — donc hors de `docs/check-api-types.py`,
qui résout chaque interface contre son record Java. C'est précisément le trou que ce script existe
pour fermer, et qui a déjà coûté la page Compare une fois. Les trois formes ont été déplacées dans
`api/types.ts` avec leur marqueur `@java` (10 types vérifiés au lieu de 7) et sont ré-exportées depuis
`topicConsumers.ts`, qui garde sa logique pure.

## Constaté, non traité

- **`getTopicConsumers` redécrit le topic** alors que `getTopicDescriptor` est en cache. Un appel
  admin par topic, négligeable devant ce que O1 vient de retirer.
- **Les groupes SHARE (KIP-932) restent hors périmètre.** Leur position vit dans le coordinateur de
  share groups ; les afficher demanderait `describeShareGroups`, pas un contournement.
- **Aucune limite sur la taille du snapshot non restreint.** Un cluster où un groupe suit des dizaines
  de milliers de partitions produit une réponse `OffsetFetch` volumineuse. Toujours plus petit que N
  lectures restreintes, mais en un seul objet.
