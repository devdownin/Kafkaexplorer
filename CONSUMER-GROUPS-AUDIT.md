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

**Ce que ça coûte, dit explicitement** : les positions commitées datent du début du run, les end
offsets de l'instant où chaque topic est audité. Un retard ne peut donc être que **surestimé**, jamais
sous-estimé — et aucun des constats remontés ne repose sur un retard surestimé (`STALLED` exige zéro
membre assigné, `AHEAD` un retard négatif). La note de portée du rapport l'énonce.

## Contrat de types

`ConsumerGroupLag`, `PartitionLag` et `TopicConsumers` étaient déclarés à la main dans
`components/topic/topicConsumers.ts`, hors de `api/types.ts` — donc hors de `docs/check-api-types.py`,
qui résout chaque interface contre son record Java. C'est précisément le trou que ce script existe
pour fermer, et qui a déjà coûté la page Compare une fois. Les trois formes ont été déplacées dans
`api/types.ts` avec leur marqueur `@java` (10 types vérifiés au lieu de 7) et sont ré-exportées depuis
`topicConsumers.ts`, qui garde sa logique pure.

## Constaté, non traité

- **La photo du run d'audit ne se rafraîchit pas.** Un run très long travaille sur des positions
  commitées vieilles de plusieurs minutes. Un TTL sur la photo rendrait un peu de fraîcheur contre une
  partie du gain de O1, et introduirait de la concurrence sur le pool à quatre threads. La note de
  portée dit ce qui est mesuré ; c'est le compromis retenu.
- **`getTopicConsumers` redécrit le topic** alors que `getTopicDescriptor` est en cache. Un appel
  admin par topic, négligeable devant ce que O1 vient de retirer.
- **Une réponse `unavailable` est mise en cache 30 s** comme les autres. Un incident réseau bref fige
  donc l'erreur une demi-minute. Défendable (le bouton Refresh existe), mais c'est un choix qui n'a
  jamais été fait explicitement.
- **Les groupes SHARE (KIP-932) restent hors périmètre.** Leur position vit dans le coordinateur de
  share groups ; les afficher demanderait `describeShareGroups`, pas un contournement.
- **Aucune limite sur la taille du snapshot non restreint.** Un cluster où un groupe suit des dizaines
  de milliers de partitions produit une réponse `OffsetFetch` volumineuse. Toujours plus petit que N
  lectures restreintes, mais en un seul objet.
- **`ConsumerLagMetrics` ne publie rien pour un groupe illisible**, pas même une série
  `..._read_failed`. Une jauge figée reste muette sur la raison de son immobilité ; la trace de log en
  DEBUG est aujourd'hui le seul signal.
