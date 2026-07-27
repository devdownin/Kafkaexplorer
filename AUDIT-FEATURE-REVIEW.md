# Revue de la fonctionnalité « Cluster Audit » (2026-07)

Audit ciblé de la fonctionnalité Audit elle-même : `AuditService`, `AuditController`,
`NamingConventionService`, `MessageFieldExtractorService` et la page `Audit.tsx`.
Recherche de bugs, d'optimisations et de problèmes d'ergonomie / UI.

Ce document décrit l'**état d'avant correction** et la décision prise pour chaque point.
Tous les items marqués ✅ sont corrigés dans le même commit.

---

## 1. Bugs

### B1 — `GET /audit` masquait la route SPA et renvoyait une 500 ✅

`AuditController` était un `@Controller` MVC qui mappait `GET /audit` et retournait le nom de vue
`"audit"`. Or :

* le projet n'a **aucun moteur de template** (pas de Thymeleaf dans le `pom.xml`, pas de
  `src/main/resources/templates/`) ;
* `/audit` est une route **client** déclarée dans `App.tsx` et servie par le catch-all de
  `SpaController` — mais un mapping explicite est plus spécifique et gagne.

Conséquence : ouvrir `http://host:8080/audit` directement (lien, favori, F5 sur la page) résolvait
la vue `audit` via l'`InternalResourceViewResolver` par défaut, qui forwarde vers `/audit` →
**circular view path** → HTTP 500. La page n'était atteignable qu'en navigation interne depuis une
autre route.

Effet de bord aggravant : ce handler **démarrait un audit complet du cluster** (`startAudit`)
quand aucun rapport n'existait — un scan de tous les topics déclenché par une simple requête GET,
donc rejouable par un préchargement de navigateur ou un crawler.

**Correction** : suppression du handler. `AuditController` devient un `@RestController`
`@RequestMapping("/api/audit")` ; `/audit` retourne au `SpaController`.

### B2 — Score de santé des flows affiché à 10000 % ✅

`NamingConventionService.identifyFlows()` stockait dans `overallHealthScore` le **pourcentage**
de débit du dernier step (`count / firstCount * 100`, donc ~100.0). La page faisait
`Math.round(flow.overallHealthScore * 100)` et comparait à `>= 0.8` / `>= 0.5`.

Résultat : un flow sain affichait **« 10000 % »**, et les seuils de couleur étaient toujours
franchis — la valeur était donc systématiquement verte, y compris pour un flow qui perd 99 % de
son volume (score 1.0 → `>= 0.8` vrai → vert).

**Correction** : le backend normalise en ratio 0..1 (borné à 1.0 pour un fan-out). Le libellé UI
devient « End-to-end retention », qui décrit ce que la valeur mesure réellement.

### B3 — Les topics UNHEALTHY s'affichaient en gris ✅

`HEALTH_TONE` mappait `HEALTHY / WARNING / CRITICAL / UNKNOWN`, mais l'enum backend
`HealthStatus` ne contient que `HEALTHY` et **`UNHEALTHY`**. Le seul état d'anomalie que le
backend sait produire n'avait donc pas d'entrée et retombait sur `?? 'neutral'` : badge gris,
identique visuellement à « pas d'info ». Le signal le plus important de la table était le seul
sans couleur.

**Correction** : `UNHEALTHY: 'error'`.

### B4 — Un audit en échec s'affichait comme un cluster parfait ✅

La page rendait le bloc de KPI dès que `status !== 'RUNNING'`, donc aussi pour `FAILED`. Le
rapport d'échec étant construit avec des zéros, l'écran montrait :

* Total Topics : 0
* Unhealthy Topics : 0
* **Health Score : 100 %** — parce que `(0 - 0) / max(0, 1) * 100 = 100`

`globalStats.error` n'était affiché nulle part. Un audit qui ne s'est jamais exécuté ressemblait
donc à un audit parfait.

Second défaut, côté backend : `Map.of("error", e.getMessage())` **lève une `NullPointerException`**
si le message est `null` (`Map.of` refuse les valeurs nulles), ce qui est le cas de la plupart des
`NullPointerException` et `TimeoutException`. Le rapport d'échec pouvait donc lui-même échouer,
laissant le run bloqué en `RUNNING` pour toujours.

**Correction** : `LinkedHashMap` avec repli sur le nom de la classe + `errorType`, et un bandeau
d'erreur dédié côté UI ; le score n'est plus calculé (`—`) quand aucun topic n'est dans le périmètre.

### B5 — `GET /api/audit/status/{id}` renvoyait 200 + corps vide pour un id inconnu ✅

`getAuditReport()` retourne `null` → Spring sérialise un corps vide avec un 200. Côté client,
`res.data` valait `''`, donc `res.data.status !== 'RUNNING'` était vrai : le polling s'arrêtait et
`setReport('')` laissait un écran vide sans message. Aucun moyen de distinguer « terminé » de
« run inconnu ».

**Correction** : 404 explicite, et le client distingue le 404 (« ce run n'est plus disponible ») du
reste.

### B6 — Des checks retournaient silencieusement 0 quand leur prérequis était désactivé ✅

Trois dépendances implicites, aucune signalée :

| Check décoché | Effet caché |
|---|---|
| `checkSchema` | `format` reste `AUTO` → la détection de messages poison ne matche **jamais** → 0 partout |
| `checkSchema` | `schema` vide → `findKeyField()` renvoie `null` → détection de doublons **abandonnée** → 0 partout |
| `checkSchema` | aucune table Flink enregistrée → le `COUNT(*)` échoue → repli silencieux sur l'estimation par offsets |

Un opérateur qui décoche « Schema inference » pour aller plus vite obtenait un rapport « tout est
propre » alors que deux checks sur trois n'avaient rien exécuté.

**Correction** :

* la détection de poison déduit le format dominant de l'échantillon quand l'inférence est coupée ;
* la détection de doublons retombe sur la **clé de l'enregistrement Kafka** — qui est de toute
  façon le signal de duplication le plus direct, et qui était jusqu'ici totalement ignoré ;
* le `COUNT(*)` en échec remonte la raison dans les `issues` du topic au lieu de se taire ;
* la carte « Exact message count » affiche son prérequis dans l'UI.

### B7 — La détection de « poison » ne regardait que le premier caractère ✅

```java
if (format == MessageFormat.JSON && !(sample.trim().startsWith("{") || ...)) poisonCount++;
```

Un payload tronqué `{"id":` — exactement le type de message qu'un check de poison existe pour
attraper — passait pour du JSON valide. Le check ne détectait qu'un changement de format complet.

**Correction** : parsing réel (Jackson pour JSON, `extractLeafFields` pour XML).

### B8 — Portée des scans jamais annoncée ✅

`detectDuplicates` lit **au plus les 10 000 premiers messages** du topic ; `calculateLatency`
corrèle les 1 000 derniers ; le check poison échantillonne 10 messages. Un topic de 50 M de
messages renvoyait « 0 duplicate » sans indiquer que 0,02 % du topic avait été lu. Le message
d'issue disait « Detected N key(s) with duplicate records », sans dire ni sur quelle clé ni sur
quelle fenêtre.

C'est exactement le travers que `AUDIT.md` interdit ailleurs dans le code (« a search that lies is
worse than one that refuses »).

**Correction** : `globalStats.scopeNotes` liste ce que le run n'a pas couvert (affiché dans une
carte « Scope of this run »), et chaque issue de doublon nomme la clé et le nombre de messages
réellement analysés.

### B9 — `auditRuns` grandissait sans limite ✅

`ConcurrentHashMap<String, AuditReport>` sans éviction. Chaque rapport contient une entrée par
topic ; sur une instance longue durée qui audite un cluster de plusieurs milliers de topics, la
map ne se vide jamais.

**Correction** : `LinkedHashMap` bornée (`removeEldestEntry`, 20 runs conservés).

---

## 2. Optimisations

### O1 — Trois consommateurs Kafka par topic pour lire les dix mêmes messages ✅

Pour un topic, avec tous les checks actifs :

1. `schemaInferenceService.detectFormat()` → `getSampleMessages(10)` → `new KafkaConsumer`
2. `schemaInferenceService.inferSchema()` → `getSampleMessages(10)` → `new KafkaConsumer`
3. le check poison → `getSampleMessages(topicName, 10)` → `new KafkaConsumer`

Trois fois le même échantillon, trois créations de consommateur, chacune avec `describeTopics` +
`assign` + `beginningOffsets`/`endOffsets` + `poll(500ms)`. Sur les 70+ topics du jeu de démo, cela
fait plus de 200 allers-retours broker évitables ; le `poll` de 500 ms domine le temps de l'audit.

**Correction** : `SchemaInferenceService` expose des surcharges `detectFormat(topic, samples)` /
`inferSchema(topic, format, samples)`. `AuditService` échantillonne **une fois** et alimente les
trois passes. Vérifié par test (`oneSampleServesFormatDetectionSchemaInferenceAndPoisonCheck`).

### O2 — Chaque topic intermédiaire d'un flow lu deux fois ✅

`calculateLatency(source, target)` refaisait `getRecentRecords()` sur ses deux topics à chaque
paire. Un topic au milieu d'un flow est à la fois cible de la paire précédente et source de la
suivante : il était donc lu deux fois, avec un parsing complet des 1 000 messages à chaque fois.

**Correction** : mémoïsation `topic → Map<id, premier timestamp>` sur la durée du run.

Effet secondaire souhaitable : les deux côtés sont maintenant réduits de la même façon (un delta
par `id`, et non un par enregistrement cible), donc un `id` republié cinq fois en aval ne pèse plus
cinq fois dans la moyenne.

### O3 — `DocumentBuilderFactory` reconstruite à chaque message XML ✅

`MessageFieldExtractorService.extractXmlFields()` construisait une `DocumentBuilderFactory`
(découverte de parser + négociation de 7 features de sécurité) **par message**. Ce service est
appelé une fois par enregistrement sur les chemins de masse : jusqu'à 10 000 fois par topic pour la
détection de doublons.

C'est précisément le motif que `CLAUDE.md` interdit déjà pour `FlinkSqlService`
(« never build a `DocumentBuilderFactory` per message »).

**Correction** : `ThreadLocal<DocumentBuilder>` avec `reset()` avant chaque parse, comme dans
`FlinkSqlService`.

---

## 3. Ergonomie et UI

### E1 — Barre de progression décorative ✅

L'audit tourne plusieurs minutes sur un vrai cluster. L'UI affichait une barre figée à
`width: 60%` avec `animate-pulse` : aucune information, et impossible de distinguer un audit qui
avance d'un audit bloqué. Le rapport `RUNNING` ne contenait d'ailleurs aucune donnée de progression.

**Correction** : le backend republie le rapport `RUNNING` à chaque topic terminé avec
`phase` / `topicsCompleted` / `topicsTotal` ; la barre est réelle, avec `role="progressbar"` et
le libellé « N of M topics audited ».

### E2 — Rapport perdu au rafraîchissement ✅

Le rapport n'existait que dans le state React. Un F5 renvoyait à l'état vide et imposait de
relancer un scan complet. Le service **persiste pourtant chaque rapport** dans
`internal.audit.history`… qui n'est relu par personne : aucun endpoint ne l'expose, et
`getLastAuditReport()` n'était appelé que par le handler MVC mort de B1. Fonctionnalité écrite,
jamais branchée.

**Correction** : `GET /api/audit/last` (204 si aucun run) et restauration au montage de la page —
y compris le rattachement au polling si un audit est encore en cours.
La relecture du topic Kafka au démarrage reste un chantier ouvert (voir « non traité »).

### E3 — Aucun contexte sur le rapport affiché ✅

Ni date, ni durée, ni rappel des checks exécutés, ni du préfixe utilisé. Deux rapports successifs
avec des options différentes étaient indiscernables à l'écran, et un rapport lu le lendemain ne
disait pas de quand il datait.

**Correction** : ligne de récapitulatif (horodatage, durée, préfixe, checks actifs), alimentée par
`globalStats.startedAt` / `durationMs` / `options`.

### E4 — Table de topics sans filtre ni tri ✅

70+ lignes dans le jeu de démo, plusieurs centaines en production. Aucun champ de recherche, aucun
tri, aucun filtre — la seule façon de trouver les topics en défaut était de faire défiler toute la
table en cherchant les badges. Le nom du topic n'était pas cliquable non plus, alors que
`/topic/:name` existe.

**Correction** : filtre texte, filtre « Unhealthy only », tri sur messages / poison / doublons
(avec `aria-sort`), compteur « N of M », et lien vers le Topic Explorer depuis le nom du topic
(table et étapes de flow).

### E5 — Comptages exacts affichés en « 1.2K » ✅

`formatNum()` s'appliquait à la colonne Messages, y compris quand l'option « Exact message count »
était cochée : la valeur exacte, obtenue au prix d'un `COUNT(*)` Flink par topic, était arrondie à
l'affichage et la précision perdue.

**Correction** : la valeur exacte est en `title` sur toutes les valeurs compactées.

### E6 — `laggingFeatures` calculé mais invisible ✅

Le backend remonte toutes les features KRaft en retard ; l'UI n'affichait que le texte de
`metadataVersionWarning`, donc uniquement `metadata.version`. Les autres features finalisées en
dessous du support des brokers n'apparaissaient nulle part.

**Correction** : la liste est affichée sous le bandeau d'avertissement.

### E7 — Onglets non accessibles ✅

Les onglets Topics / Flows étaient de simples `<button>` sans `role="tab"`, sans `aria-selected`,
sans `tabpanel` associé : invisibles comme onglets pour un lecteur d'écran.

**Correction** : `role="tablist"` / `tab` / `tabpanel` + `aria-controls` / `aria-labelledby`.

### E8 — Pas d'export ✅

Aucun moyen de sortir un rapport de l'écran (pour un ticket, une revue, un diff entre deux runs).

**Correction** : bouton « Export JSON ».

---

## 4. Second lot — sévérité graduée

### S1 — `HealthStatus` n'est plus binaire ✅

Un topic avec 1 doublon sur 10 000 messages et un topic dont l'audit a complètement échoué
portaient le même `UNHEALTHY`. Sur un cluster réel, le KPI « Unhealthy Topics » et le score de
santé devenaient donc du bruit : tout est rouge, rien ne hiérarchise. L'UI prévoyait déjà
`WARNING` et `CRITICAL` — constantes mortes dans `HEALTH_TONE`.

`HealthStatus` devient `HEALTHY < WARNING < CRITICAL`, et chaque constat porte sa propre sévérité
via un nouveau record `TopicIssue(message, severity)` — les issues étaient de simples chaînes, donc
l'UI les peignait toutes en rouge. Un topic prend la **pire** sévérité de ses constats.

| Constat | Sévérité | Pourquoi |
|---|---|---|
| L'audit du topic a échoué | `CRITICAL` | Aucun verdict du tout, le pire résultat possible |
| Messages illisibles dans l'échantillon | `CRITICAL` | Données malformées, les consommateurs casseront dessus |
| `COUNT(*)` renvoie 0 alors que Kafka a des messages | `CRITICAL` | Incohérence réelle |
| Doublons détectés | `WARNING` | Souvent légitime (mises à jour clefées par entité) |
| Comptage exact indisponible | `WARNING` | C'est la mesure qui est dégradée, pas la donnée |

`AuditReport.unhealthyTopicsCount` est remplacé par `criticalTopicsCount` + `warningTopicsCount`, et
`globalStats.healthScore` (ratio 0..1) est désormais calculé côté serveur — donc figé dans le
rapport persisté — avec une pondération : un CRITICAL coûte 1 point, un WARNING un demi.

Côté UI : la tuile devient « Needs Attention » avec le détail critique/warning, les chips de constat
sont colorées par sévérité, et le filtre santé propose *Needs attention / Critical only /
Warning only / Healthy only*.

> ⚠️ Les rapports déjà écrits dans `internal.audit.history` contiennent `"UNHEALTHY"`, qui n'existe
> plus dans l'enum. Sans conséquence aujourd'hui — rien ne relit ce topic — mais le futur lecteur
> d'historique (voir ci-dessous) devra tolérer cette valeur héritée.

### S2 — Un seul audit à la fois ✅

`startAudit` empilait les runs sur un exécuteur mono-thread : cliquer cinq fois enchaînait cinq
scans complets du cluster, sans annulation possible. Un `AtomicReference` porte désormais le run en
vol ; `POST /api/audit/start` répond **409 avec l'id du run en cours**, et l'UI s'y rattache au lieu
d'afficher une erreur ou de mettre un second scan en file. Le créneau est libéré dans un `finally`,
sinon un run en échec aurait bloqué tous les suivants pour la durée du processus.

### S3 — `totalMessages` cohérent avec la colonne ✅

Le KPI sommait `topicSizes` (estimation par offsets) pendant que la colonne par topic affichait les
comptages exacts Flink. Il somme maintenant les `TopicAudit.messageCount` réellement rapportés, donc
les deux chiffres de l'écran viennent de la même source.

Au passage, un bug de test latent : le mock Flink de `testAuditProcess` filtrait sur `demo.test.1`
alors que le SQL généré porte le nom de table `demo_test_1` — les deux topics recevaient donc le
même comptage et l'assertion passait pour de mauvaises raisons.

---

## 5. Constaté, non traité

Ces points sont réels mais dépassent le périmètre d'une correction de la fonctionnalité Audit ;
ils sont listés pour décision.

* **`internal.audit.history` reste en écriture seule.** `GET /api/audit/last` ne sert que les runs
  du processus courant ; après un redémarrage, l'historique persisté dans Kafka n'est toujours pas
  relu. Un vrai écran d'historique (liste des runs, comparaison de deux rapports) demanderait un
  lecteur du topic au démarrage, sur le modèle de `MetricService`.
* **Toujours aucune annulation.** S2 empêche d'empiler des runs, mais il n'existe pas de moyen
  d'interrompre celui qui tourne — il faut attendre la fin du scan.
* **La détection de doublons lit depuis EARLIEST** alors que tout le reste échantillonne les
  messages récents. Sur un topic avec rétention, elle juge les 10 000 plus vieux messages
  survivants, pas forcément ce qu'un opérateur attend.
* **Pas de budget global sur l'audit.** Un `COUNT(*)` Flink par topic à 5 s de timeout, sur
  4 threads : un cluster de 2 000 topics peut occuper 40 minutes.
* **`KafkaAdminService.getEarliestRecords` / `getRecordsWithPredicate` s'arrêtent au premier
  `poll()` vide.** Sur un broker lent ou une partition dont les métadonnées ne sont pas encore
  résolues, le scan peut se terminer prématurément et sous-estimer les doublons. Le correctif
  (compter les polls vides consécutifs, ou boucler jusqu'aux offsets de fin) touche un service
  partagé par le Topic Explorer, le Stream Flow et le Process Mining.
* **Pas d'authentification.** `POST /api/audit/start` reste déclenchable par n'importe qui sur le
  réseau, et un audit est une opération coûteuse pour le cluster. C'est la posture assumée du
  projet (cf. « Security Notes » dans `CLAUDE.md`), rappelée ici parce que la suppression du
  déclenchement par GET (B1) réduit la surface sans la supprimer.
