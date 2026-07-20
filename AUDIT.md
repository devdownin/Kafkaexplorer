# Audit complet — Bugs & Optimisations

Date : 2026-07-20 · Périmètre : backend Java (90 fichiers), frontend React, configuration, build.
Chaque constat référence le fichier et la ligne. Sévérités : 🔴 Critique · 🟠 Majeur · 🟡 Mineur · 🔵 Optimisation.

> **Statut** : les bugs **C1–C4** et **M1–M4** sont **corrigés** sur cette branche :
> - C1 : exécuteur dédié dans `AuditService` (plus d'`@Async` auto-invoqué) ;
> - C2 : annulation du heartbeat dans `KafkaLiveConsumer.stopSession()` ;
> - C3 : filtre WHERE sensible à la casse + chemins imbriqués dans `FlinkSqlService` ;
> - C4 : masquage `DdlGeneratorService.maskSensitiveProperties()` sur tous les endpoints exposant du DDL ;
> - M1 : `getExactCount` accepte tout `Number` de la première ligne (compatible `count_all`/Double du moteur direct) ;
> - M2 : doublons et latence de flux réimplémentés en Java sur les messages Kafka (plus de sous-requête/JOIN non supportés) ;
> - M3 : `findKeyField` renvoie `null` sans champ id-like (suffixes `*_id`/`*Id` acceptés) au lieu d'un champ arbitraire ;
> - M4 : TTL de cache aligné sur 30 s (`explorer.cache-expire-seconds`, `WebConfig` en `TimeUnit.SECONDS`) ;
> - M5 : le consumer live n'est plus fermé depuis le thread HTTP — `stopSession()` signale (flag + `wakeup()`) et
>   la tâche de polling, seule à toucher le consumer (init incluse), effectue la fermeture (`finishSession`) ;
> - M6 : `restoreFromKafka` lit jusqu'aux end offsets (assign + seekToBeginning) au lieu de s'arrêter au premier
>   poll vide ; un enregistrement corrompu est ignoré au lieu d'avorter la restauration ;
> - M7 : `getRecordsWithPredicate` borne le seek au beginning offset (plus de reset `latest` silencieux sur les
>   topics tronqués par la rétention) ;
> - M8 : `kafkaDirectSelect` élargit le fetch (jusqu'à `max(5000, limit×100)`, plafonné à 100 000) quand un WHERE
>   est présent, au lieu de ne lire que `limit + 20` messages avant filtrage.
>
> Les **9 bugs mineurs** sont également corrigés : COUNT renvoyé en entier ; code mort supprimé
> (`stripLimitClause`, `injectLatestOffsetHint`) ; `JsonSchemaInferrer` en `LinkedHashMap` (DDL déterministe) ;
> StreamFlow refuse une recherche sans `messageKey` (plus de NPE avalées) ; parseurs XML/XPath de StreamFlow en
> `ThreadLocal` (thread-safe) + `@PreDestroy` sur son pool ; `AnthropicLlmClient` propage la cause réelle dans son
> message d'erreur ; Dashboard ne touche plus `localStorage` pendant le rendu (tendance « since last visit »
> fonctionnelle) ; ProcessMining affiche le message d'erreur du backend au lieu du générique axios.
>
> Les sections ci-dessous décrivent l'état **avant** correctif.

---

## Résumé exécutif

| Sévérité | Nombre | Points marquants |
|---|---|---|
| 🔴 Critique | 4 | `@Async` inopérant (audit bloquant), fuite de tâches heartbeat, filtre WHERE cassé (casse), secrets Kafka exposés dans les DDL |
| 🟠 Majeur | 8 | Comptage exact / doublons / latence d'audit silencieusement morts, cache 10 min au lieu de 30 s, race sur le consumer live, restauration des métriques fragile |
| 🟡 Mineur | 9 | COUNT affiché en double (`42.0`), code mort, ordre de colonnes non déterministe, NPE avalées dans StreamFlow |
| 🔵 Optimisation | 8 | Consumer/Producer Kafka recréés à chaque appel, `DocumentBuilderFactory` par message, dashboard 5 s très coûteux |

---

## 🔴 Bugs critiques

### C1. `@Async` auto-invoqué : l'audit de cluster bloque la requête HTTP
`AuditService.startAudit()` appelle directement `runAuditAsync()` dans la même classe
(`src/main/java/com/yourcompany/kafkasqlexplorer/service/AuditService.java:68` et `:85`).
Avec le mode proxy par défaut de Spring, l'annotation `@Async` d'une méthode **auto-invoquée est ignorée** :
l'audit complet (inférence de schéma + COUNT Flink sur chaque topic) s'exécute **synchroniquement dans le thread HTTP**.
Sur un cluster de 70+ topics, l'appel `POST /api/audit/start` peut durer plusieurs minutes et le statut `RUNNING`
renvoyé n'est jamais observable (le rapport est déjà `COMPLETED` quand la réponse part).
**Correctif** : déplacer `runAuditAsync` dans un bean séparé, ou injecter un `TaskExecutor` et soumettre explicitement.

### C2. Fuite de tâches heartbeat — jamais annulées
`KafkaLiveConsumer.startSession()` planifie le heartbeat avec `scheduler.scheduleAtFixedRate(...)`
sans conserver le `ScheduledFuture` (`KafkaLiveConsumer.java:156-162`), alors que `stopSession()` n'annule que
`pollingFuture` (`:168-171`). **Chaque session live laisse une tâche périodique de 15 s qui tourne pour toujours**
(elle rappelle `stopSession` à chaque tick mais n'est jamais désarmée). Après N sessions, le pool de 4 threads
est saturé de heartbeats fantômes et les nouvelles sessions ne pollent plus.
**Correctif** : stocker le future du heartbeat (ex. `Map<String, List<ScheduledFuture<?>>>`) et l'annuler dans `stopSession`.

### C3. Filtre `WHERE` du moteur KAFKA_DIRECT cassé pour les champs camelCase / imbriqués
`FlinkSqlService.extractSimpleWhere()` met la clé en minuscules (`FlinkSqlService.java:1041` — `cm.group(1).toLowerCase()`),
mais `matchesWhereConditions()` fait `row.get(cond.getKey())` avec les clés d'origine du message (`:1048`).
Pour `WHERE orderId = 'X'` sur un message `{"orderId": ...}`, la clé `orderid` ne matche jamais → `val == null`
→ **toutes les lignes sont éliminées** et la requête renvoie 0 résultat sans erreur.
De plus, le lookup n'utilise pas `getNestedValue()` : impossible de filtrer sur un champ imbriqué (`customer.name`).
**Correctif** : conserver la casse d'origine (ou comparer en ignorant la casse) et passer par `getNestedValue`.

### C4. Secrets Kafka inlinés dans les DDL générés (exposés via l'UI)
`DdlGeneratorService.generateDdl()` recopie **toutes** les propriétés Kafka dans le `WITH (...)`
(`DdlGeneratorService.java:72-74`), y compris `sasl.jaas.config` qui contient `confluentSecret` en clair
(construit dans `KafkaConfig.getKafkaProperties()`, `KafkaConfig.java:55-59`) et les mots de passe SSL.
Ces DDL sont renvoyés par `GET /api/topic/{name}/ddl`, `GET /api/query/ddl-preview` et par
`LineageService.getDdlForNode()` (`SHOW CREATE TABLE`) → **le secret Confluent Cloud est visible dans le navigateur**.
**Correctif** : liste blanche des propriétés copiées (bootstrap, security.protocol…) et masquage de
`sasl.jaas.config` / `ssl.*.password`, ou ne jamais renvoyer ces propriétés dans les DDL exposés à l'UI.

---

## 🟠 Bugs majeurs

### M1. Comptage exact d'audit : lit une colonne qui n'existe plus → silencieusement inopérant
`AuditService.getExactCount()` lit `rows().get(0).get("EXPR$0")` et n'accepte que `Long`/`Integer`
(`AuditService.java:185-188`). Or tous les SELECT passent par `kafkaDirectSelect`, où `COUNT(*)` sans alias
est nommé **`count_all`** et renvoyé en **`Double`** (`FlinkSqlService.java:803-804`, `:996`).
Résultat : `checkExactCount` retombe toujours sur le comptage approximatif, et le contrôle
« Flink SQL returned 0 rows despite Kafka having messages » (`:162`) ne se déclenche jamais.

### M2. Détection de doublons et latence de flux : SQL non supporté par le moteur direct
- `detectDuplicates()` émet une sous-requête `SELECT COUNT(*) FROM (SELECT 1 ... GROUP BY ... HAVING ...)`
  (`AuditService.java:198`) que `kafkaDirectSelect` ne sait pas exécuter (il compte par groupe et lit ensuite
  `EXPR$0` → toujours 0 doublon détecté).
- `calculateLatency()` émet un `JOIN ... ON t1.id = t2.id` (`AuditService.java:236-238`) — les JOIN ne sont pas
  supportés par le bypass Kafka → latence toujours `null`.
Ces deux fonctionnalités d'audit sont **mortes depuis le bypass Flink** documenté dans CLAUDE.md, sans aucun
message d'erreur pour l'utilisateur. À réimplémenter en Java sur les messages récupérés (comme les agrégats),
ou à signaler comme non disponibles.

### M3. Clé de dédoublonnage par défaut : premier champ arbitraire
`NamingConventionService.findKeyField()` retombe sur `schema.keySet().stream().findFirst()`
(`NamingConventionService.java:33`) quand aucun champ `id`/`order_id` n'existe : le GROUP BY se fait alors sur
un champ quelconque (ex. `status`) → faux positifs massifs de doublons (une fois M2 corrigé).
**Correctif** : renvoyer `null` (pas de détection) plutôt qu'un champ arbitraire.

### M4. TTL de cache réel : 10 minutes, pas 30 secondes
`WebConfig.cacheManager()` définit un `CaffeineCacheManager` avec `cacheExpireMinutes` (défaut **10 min**,
`WebConfig.java:32-34`, `ExplorerConfig.java:22`), ce qui **écrase** le `spring.cache.caffeine.spec: expireAfterWrite=30s`
d'`application.yml:8` (ignoré dès qu'un bean `CacheManager` custom existe). Conséquence : un topic créé
n'apparaît dans `listTopics()` (donc dans le Workbench, l'auto-registration, le lineage) qu'après 10 minutes.
**Correctif** : supprimer le bean custom ou aligner `cache-expire-minutes` sur la valeur voulue (30 s).

### M5. Accès concurrent au `KafkaConsumer` live (non thread-safe)
`KafkaLiveConsumer.stopSession()` appelle `consumer.close()` depuis le thread HTTP pendant qu'un tick de poll
peut être en cours dans le scheduler (`KafkaLiveConsumer.java:173-180` vs `:89`) ; `cancel(false)` n'attend pas la fin
du tick. `KafkaConsumer` jette `ConcurrentModificationException` en accès multi-thread.
**Correctif** : `consumer.wakeup()` + fermeture dans le thread de poll, ou `cancel(true)` + close différé.

### M6. Restauration des métriques : arrêt au premier poll vide
`MetricService.restoreFromKafka()` sort de la boucle dès qu'un `poll` revient vide (`MetricService.java:953`),
or le **premier poll après subscribe est presque toujours vide** (rebalance en cours). Les métriques persistées
ne sont alors pas restaurées et `seedDefaultMetrics()` recrée des exemples → doublons de configuration au fil des redémarrages.
**Correctif** : poller sur toute la fenêtre de 2 s (ne pas `break` sur vide), ou utiliser `endOffsets` pour savoir quand tout est lu.

### M7. Lecture « recent » : seek sous le beginning offset → aucun résultat
`KafkaAdminService.getRecordsWithPredicate()` calcule `startOffset = max(0, endOffset - N)` sans le borner au
**beginning offset** (`KafkaAdminService.java:604`). Sur un topic dont la rétention a supprimé les anciens segments,
le seek tombe hors plage → reset `auto.offset.reset` (défaut `latest`) → **0 message renvoyé** silencieusement.
**Correctif** : `Math.max(beginningOffsets.get(tp), endOffset - N)`.

### M8. Fetch non-agrégat borné à `limit + 20` avant filtrage WHERE
`kafkaDirectSelect` ne lit que `limit + 20` messages (`FlinkSqlService.java:723`) puis applique le WHERE :
une requête filtrante sur un topic volumineux renvoie bien moins de lignes que `LIMIT` alors que des
correspondances existent plus loin — sans avertissement. Augmenter le fetch quand un WHERE est présent,
ou continuer à consommer jusqu'à `limit` correspondances / borne haute.

---

## 🟡 Bugs mineurs

1. **COUNT affiché en flottant** — `evalAggregate` renvoie `(double) rows.size()` (`FlinkSqlService.java:996`) :
   l'UI affiche `42.0` pour un COUNT. Renvoyer `long` pour COUNT.
2. **Code mort** — `stripLimitClause()` (`FlinkSqlService.java:1120`) et `injectLatestOffsetHint()` (`:1160`)
   ne sont plus appelés. À supprimer.
3. **`JsonSchemaInferrer` : `HashMap`** (`JsonSchemaInferrer.java:21`) → ordre de colonnes non déterministe dans
   les DDL générés (diffs de lineage instables). Utiliser `LinkedHashMap`.
4. **StreamFlow sans `messageKey`** : `checkMatch` fait `content.contains(null)` → NPE avalée par message
   (`StreamFlowService.java:184`, catch à `:150`) ; la recherche renvoie 0 résultat en brûlant des exceptions.
   Valider la requête en amont.
5. **`DocumentBuilderFactory` partagée entre threads** — `StreamFlowService` réutilise une factory non thread-safe
   depuis un pool de 10 threads (`StreamFlowService.java:42`, `:200`). Utiliser un `ThreadLocal` ou créer par appel.
6. **`AnthropicLlmClient` masque la cause** — `throw new RuntimeException("LLM call failed", e)`
   (`AnthropicLlmClient.java:56`) : les appelants affichent `e.getMessage()` → l'utilisateur voit « LLM call failed »
   au lieu de l'erreur réelle (contraire à la règle CLAUDE.md « propagate real error »). Inclure `e.getMessage()`.
7. **Dashboard : `localStorage` écrit pendant le rendu** (`Dashboard.tsx:178-184`) — le compteur « since last visit »
   est réécrit à chaque re-render (toutes les 5 s), donc la tendance est toujours « No change ». Déplacer dans un `useEffect`.
8. **`ExecutorService` de StreamFlow jamais arrêté** — pas de `@PreDestroy` (`StreamFlowService.java:54`),
   contrairement à `FlinkSqlService`/`FlinkRuntimeCoordinator`.
9. **Erreurs axios peu parlantes** — `err instanceof Error ? err.message` (`ProcessMining.tsx:210`, etc.)
   affiche « Request failed with status code 500 » au lieu du message du backend (`err.response.data`).

---

## 🔵 Optimisations

1. **Un `KafkaConsumer` neuf par appel de métadonnées** — `getTopicsSize`, `getTopicsLastMessageTimestamps`,
   `getTopicDescriptor`, `getLatestMessage`, `getEarliestRecords`, `getRecordsWithPredicate` créent chacun un
   consumer complet (connexion TCP + metadata). Le dashboard (poll frontend **toutes les 5 s**, `Dashboard.tsx:9`)
   déclenche donc en continu : `listTopics` + `describeTopics` + **2 consumers** + `ping`. Sur un gros cluster,
   c'est la principale charge de l'application. Pistes : consumer partagé (avec verrou), cache court sur
   `getTopicsSize`/timestamps (le TTL Caffeine ne couvre que `listTopics`/`topicDescriptor`), et/ou allonger
   l'intervalle de poll côté UI.
2. **Un `KafkaProducer` neuf par persistance** — `MetricService.persistToKafka()` (`MetricService.java:931`)
   et `AuditService.persistAuditHistory()` (`AuditService.java:257`) : un producer singleton suffit.
3. **`parseMessageToRow` recrée une `DocumentBuilderFactory` par message XML** (`FlinkSqlService.java:589`) —
   pour un agrégat sur 100 000 messages c'est extrêmement coûteux. Mutualiser la factory (thread-local).
4. **`refreshMetrics` : jusqu'à 100 000 messages relus par métrique toutes les 30 s**
   (`MetricService.java:686`, fetch dans `kafkaDirectSelect`). Avec 4 métriques par défaut sur la même table,
   le même topic est relu 4× par cycle. Mutualiser la lecture par table/fenêtre, ou espacer le refresh.
5. **`autoRegisterTableIfNeeded` appelle `listTables()` + `listTopics()` à chaque SELECT**
   (`FlinkSqlService.java:227-231`) — `listTables` passe par le lock Flink. Cache court possible.
6. **Audit : explosion de parallélisme** — `runAuditAsync` lance un `CompletableFuture` par topic sur le
   commonPool (`AuditService.java:91-93`), chacun ouvrant ses propres consumers (inférence + sampling).
   Limiter avec un executor dédié borné (ex. 4-8 threads).
7. **`KafkaLiveConsumer` : pool de 4 threads partagé poll + analyses LLM** (`KafkaLiveConsumer.java:44`, `:111`) —
   une analyse LLM peut durer 60 s (timeout config) et affamer le polling des autres sessions. Séparer le pool
   d'analyse du pool de polling.
8. **Logging `DEBUG` global du package en production** (`application.yml:78`) + logs `INFO` par requête dans
   `executeSql` (schéma complet, première ligne, chaque colonne — `FlinkSqlService.java:501-520`). À passer en DEBUG.

---

## Sécurité

- **Pas d'authentification** (assumé, documenté) — mais `POST /api/config` permet de **changer le bootstrap Kafka,
  les secrets SSL/Confluent et la clé LLM à chaud** (`ConfigController.java:59-115`) et `POST /config` (form) idem.
  En environnement partagé, c'est un pivot réseau facile (pointer l'app vers un broker arbitraire). À protéger en priorité si l'app sort du poste local.
- **C4 (secrets dans les DDL)** est le point le plus urgent côté fuite d'information.
- `ConfigController.updateConfig` appelle `kafkaAdminService.init()` qui ferme l'`AdminClient` pendant que
  d'autres requêtes l'utilisent → erreurs transitoires (pas de synchronisation).
- XXE : correctement désactivé partout (parsers, UDF, StreamFlow, extracteurs). ✔️
- Injection SQL : whitelist `SELECT/EXPLAIN/CREATE TABLE` effective (`FlinkSqlService.java:442`). ✔️
  Nuance : les noms de champs inférés sont injectés tels quels entre backticks dans les DDL
  (`DdlGeneratorService.java:59`) — un nom de champ contenant un backtick casse/injecte le DDL. Échapper ou filtrer.

## Dérives documentation / build

- `pom.xml` : **Flink 1.18.1** et `java.version` **25**, alors que CLAUDE.md/README annoncent Flink 2.2.x et Java 21.
  Aligner la doc ou les versions (le commentaire de `FlinkSqlService` cite d'ailleurs des tests en 1.18/1.20/2.0).
- CLAUDE.md annonce un TTL de cache de 30 s — le TTL effectif est 10 min (voir M4).
- `LineageService` est documenté comme parsant les jobs INSERT via `getActiveJobsDetails()`, mais cette map ne
  contient que les jobs de la session en cours (perdus au restart) alors que `FlinkJobStore` persiste — le lineage
  oublie les jobs après redémarrage.

## Recommandations priorisées

1. Corriger C1–C4 (faible effort, fort impact : deux fuites de ressources, un moteur de filtre cassé, une fuite de secrets).
2. Rendre honnête l'audit de cluster (M1–M3) : soit réimplémenter count/doublons/latence côté Java, soit les désactiver avec un message clair.
3. Aligner le TTL de cache (M4) — une ligne.
4. Mutualiser producteurs/consommateurs Kafka et alléger le poll du dashboard (Opt. 1-2) — plus gros gain de charge.
5. Nettoyage : code mort, COUNT en entier, logs DEBUG, versions pom vs doc.
