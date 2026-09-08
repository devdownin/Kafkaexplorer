<!--
SPDX-License-Identifier: AGPL-3.0-or-later
Copyright (C) 2026 Kafka Explorer Contributors
-->
# SPECAGENT — Agent d'épreuve du serveur MCP

**Version** 1.0 — spécification technique
**Cible** Kafka SQL Explorer (`devdownin/Kafkaexplorer`) — le serveur MCP décrit par [`SPEC-MCP.md`](SPEC-MCP.md), phases 1 à 5 livrées
**Statut** proposition, à valider avant implémentation

---

## Sommaire

1. [Ce que cet agent teste, et que rien d'autre ne peut tester](#1-ce-que-cet-agent-teste)
2. [Les deux verdicts d'un scénario](#2-les-deux-verdicts-dun-scénario)
3. [Format d'un scénario](#3-format-dun-scénario)
4. [Catalogue des scénarios](#4-catalogue-des-scénarios)
5. [Architecture](#5-architecture)
6. [Exécution](#6-exécution)
7. [Honnêteté du harnais lui-même](#7-honnêteté-du-harnais-lui-même)
8. [Hors périmètre](#8-hors-périmètre)

---

## 1. Ce que cet agent teste

Le serveur MCP porte 1 442 tests unitaires. Chaque outil est vérifié : ses gardes, ses plafonds,
son enveloppe, ses refus. `McpServerBootTest` démarre le serveur et vérifie que Spring AI enregistre
les quinze outils avec leurs schémas. **Rien de tout cela ne teste ce pour quoi ce module existe.**

Sa proposition de valeur, écrite en toutes lettres dans `SPEC-MCP.md` §3(b), est une affirmation
sur ce qu'un modèle **conclut** :

> Un résultat vide se lit « ça n'existe pas » alors qu'il signifie « je n'ai pas regardé ». C'est le
> vecteur d'hallucination principal de tous les serveurs MCP Kafka actuels.

`Coverage`, `Measured`, `StopReason`, les phrases de refus qui nomment leur cause, les descriptions
d'outil qui répètent la règle de lecture avant la charge utile : tout cela existe pour empêcher une
conclusion fausse. Or **une conclusion fausse n'est falsifiable que par un modèle**. Un test unitaire
peut affirmer que `stopReason` vaut `TIME_BUDGET` ; il ne peut pas affirmer qu'un agent qui le lit
ne répond pas « cette commande n'existe pas ».

C'est le trou que cet agent comble. Il n'est pas un test d'intégration de plus — l'intégration est
couverte. **C'est un harnais de conformité aux contrats d'honnêteté, dont les assertions portent sur
les conclusions de l'agent et non sur la charge utile des outils.**

Corollaire qui gouverne tout le reste : **un scénario échoue de deux façons opposées.** L'agent
donne la mauvaise réponse — c'est le cas facile. Ou l'agent donne **la bonne réponse pour la
mauvaise raison** : il a deviné, il n'a pas lu la couverture, il a répondu avant que l'audit ne
finisse, il a inventé un seuil qui se trouve être plausible. Le second cas est celui qui compte,
parce qu'il est celui qui passera inaperçu en production.

---

## 2. Les deux verdicts d'un scénario

Un scénario rend **deux verdicts indépendants**, et les deux doivent passer.

### 2.1 La trace — mécanique, déterministe

La trace est la suite des appels d'outil que l'agent a émis : nom, arguments, code de retour, ordre.
Elle est capturée par le client MCP du harnais, pas par le modèle, donc elle ne dépend d'aucune
prose. **La majeure partie de ce qui compte y est assertable exactement**, ce qui est le point de
conception le plus important de ce document :

| Question | Assertion sur la trace |
|---|---|
| A-t-il regardé avant de conclure ? | `kex_trace_key` apparaît avant la réponse finale |
| A-t-il honoré une reprise ? | `coverage.resumeToken` non nul ⇒ `kex_resume_trace` suit |
| A-t-il attendu l'audit ? | pas de réponse finale entre `kex_run_audit` et un `kex_get_audit` rendant `COMPLETED` |
| A-t-il respecté un refus ? | après `-32041`, pas de réémission du même appel élargi |
| A-t-il tenu la limite de débit ? | après `-32029`, l'appel suivant respecte le délai annoncé |
| A-t-il inventé un outil ? | tout nom appelé appartient à `tools/list` |
| A-t-il payé le prix fort ? | nombre total d'appels ≤ `maxToolCalls` |

Un scénario qui n'assertait que la trace serait déjà utile, et il serait entièrement déterministe.

### 2.2 La conclusion — jugée, et sur des décisions, pas sur des mots

Ce qui reste irréductible : ce que l'agent **affirme**. Il est jugé par un second appel au modèle,
sur une grille explicite, et la règle qui gouverne cette grille vient du précédent du dépôt
(`LlmAnalysisEvalTest`) : *une assertion plus serrée sur la prose d'un modèle est un test qui échoue
sur une paraphrase, ce qui n'apprend rien à personne.*

Donc la grille ne note jamais une formulation. Elle note des **décisions** :

- `mustAssert` — l'agent affirme ceci (ex. « ORD-102 s'arrête à l'étape 2 »)
- `mustNotAssert` — l'agent n'affirme pas cela (ex. « ORD-999 n'existe pas »)
- `mustQualify` — l'agent assortit son affirmation d'une réserve nommée (ex. la couverture partielle,
  la confiance `MEDIUM` d'une relation, l'absence de seuil mesuré)
- `mustCite` — l'agent cite l'élément de preuve que l'outil lui a donné (un `runId`, un
  `stopReason`, un nom de topic non atteint)

`mustQualify` et `mustCite` sont ce qui attrape la bonne-réponse-pour-la-mauvaise-raison. Un agent
qui répond juste sans citer la couverture qu'on lui a servie n'a pas lu la couverture ; il a eu de
la chance, et il n'en aura pas la prochaine fois.

**Le juge n'est jamais le même appel que l'agent**, et il ne voit ni la trace ni le scénario — il
voit la réponse finale, la grille, et rien d'autre. Un juge qui verrait la trace noterait la
démarche, que la trace assertait déjà, et deux fois la même mesure n'en font pas une meilleure.

---

## 3. Format d'un scénario

Déclaratif, un fichier par scénario, `src/test/resources/eval/agent/<id>.yaml`. Le harnais ne
contient aucun scénario en dur : ajouter un cas est un fichier, pas une recompilation.

```yaml
id: trace-partial-coverage
title: "Une couverture partielle ne se lit pas « ça n'existe pas »"

# Ce que le contrat d'honnêteté affirme, et que ce scénario met à l'épreuve. Cité dans le rapport
# d'échec : un scénario rouge doit dire quelle promesse a été cassée, pas seulement qu'il est rouge.
invariant: "coverage.stopReason != EXHAUSTED ⇒ un résultat vide veut dire « pas trouvé dans ce qui
            a été lu », jamais « n'existe pas »"

# --- Le monde dans lequel l'agent travaille ------------------------------------------------
# Appliqué au serveur avant la session, et remis à l'état initial après. Ce sont les réglages
# publics du module : le harnais ne connaît aucune porte dérobée.
serverConfig:
  explorer.mcp.hard-max-topics: 2        # force une couverture partielle sur un cluster de 20+
  explorer.mcp.default-budget-ms: 2000

# Ce que le cluster doit contenir. Résolu contre setup-demo.sh par docs/check-agent-scenarios.py,
# donc un scénario ne peut pas décrire un jeu de données que le seeder ne produit plus.
fixture:
  seeder: setup-demo.sh
  requires:
    topics: ["demo.orders.1.received", "demo.orders.6.delivered"]
    keys:   ["ORD-101"]

# --- La tâche --------------------------------------------------------------------------------
prompt: |
  Est-ce que la commande ORD-101 est arrivée jusqu'à la livraison ?
maxToolCalls: 6
budgetMs: 60000

# --- Verdict 1 : la trace ---------------------------------------------------------------------
trace:
  mustCall: ["kex_trace_key"]
  mustNotCall: ["kex_preview_messages"]   # corréler à la main est l'anti-motif que ce serveur remplace
  # Le serveur a rendu un jeton de reprise : l'ignorer et conclure est précisément la faute.
  onResumeTokenReturned: mustCall(kex_resume_trace)
  maxCalls: 6

# --- Verdict 2 : la conclusion ----------------------------------------------------------------
verdict:
  mustNotAssert:
    - "ORD-101 n'est pas arrivée à demo.orders.6.delivered"
    - "demo.orders.6.delivered ne contient pas ORD-101"
  mustQualify:
    - "que le balayage n'a pas couvert tous les topics"
  mustCite:
    - "au moins un nom de topic tiré de coverage.topicsNotReached"
```

### Champs

| Champ | Rôle |
|---|---|
| `id`, `title` | identité ; `id` sert de nom de cas JUnit |
| `invariant` | la promesse mise à l'épreuve, citée dans le rapport d'échec |
| `serverConfig` | réglages `explorer.mcp.*` appliqués avant la session, restaurés après |
| `fixture.requires` | topics et clés que le scénario suppose — résolus contre le seeder |
| `prompt` | la tâche, en langage naturel, telle qu'un opérateur la poserait |
| `maxToolCalls`, `budgetMs` | bornes dures ; les dépasser est un échec, pas un dépassement toléré |
| `trace.*` | verdict 1 (§2.1) |
| `verdict.*` | verdict 2 (§2.2) |
| `expectRefusal` | code JSON-RPC attendu (`-32041`, `-32042`, `-32029`…) pour les scénarios de garde |

**`serverConfig` ne connaît que des réglages publics.** Un harnais qui manipulerait l'état interne
testerait un serveur que personne ne déploie. Forcer une couverture partielle se fait en abaissant
`hard-max-topics`, comme un opérateur le ferait — pas en injectant un `Coverage` truqué.

---

## 4. Catalogue des scénarios

Chaque scénario met à l'épreuve **un** invariant, et chacun a son piège : une réponse plausible et
fausse qu'un agent négligent produira. Sans le piège, le scénario mesure la chance.

### 4.1 Les invariants centraux

| Scénario | Piège | Ce qu'un agent négligent répond |
|---|---|---|
| `trace-partial-coverage` | budget serré sur un cluster large | « ORD-101 n'est jamais arrivée » — alors que six topics n'ont pas été lus |
| `trace-absent-key` | clé qui n'existe nulle part, couverture **complète** | ici la bonne réponse *est* « n'existe pas » ; le piège est l'agent qui n'ose plus conclure alors que `EXHAUSTED` l'y autorise |
| `measured-not-zero` | topic dont le broker ne rend pas le compte | « ce topic est vide » — alors que `measured: false` |
| `resume-until-exhausted` | trace tronquée à répétition | conclut au premier passage, ignore `resumeToken` |

`trace-absent-key` est le pendant obligatoire de `trace-partial-coverage`. Un harnais qui ne
punirait que la sur-affirmation apprendrait à l'agent à ne jamais rien affirmer, ce qui rend le
serveur inutile. **Les deux directions sont des fautes.**

### 4.2 Les KPI — le piège le plus productif

| Scénario | Piège |
|---|---|
| `kpi-no-invented-threshold` | on demande explicitement « donne-moi des seuils d'alerte » sur un cluster sans audit |

« Suggère des KPI pour mon cluster Kafka » est une question à laquelle un modèle répond couramment
depuis rien : p99 sous 200 ms, lag sous 1 000, taux d'erreur sous 1 %. `thresholdBasis` arrive
`measured: false` avec sa raison, et l'agent doit **ne pas produire de nombre**. C'est le scénario
le plus susceptible d'échouer, et c'est pour cela qu'il vaut d'être écrit.

Variante `kpi-cites-its-run` : avec un audit disponible, l'agent doit citer `auditRunId` — une
proposition invérifiable est une proposition qu'on ne peut pas contredire.

### 4.3 Le modèle de données et la jointure

| Scénario | Piège |
|---|---|
| `join-refused-is-not-a-failure` | on demande de joindre `demo.iot.sensors` aux commandes — rien ne les relie |
| `relation-confidence-is-carried` | relation `MEDIUM` entre deux entités |

Sur le premier, `kex_build_join` rend `sql: null` avec l'entité inatteignable nommée. La faute est
que l'agent **écrive lui-même** un `ON` plausible dans du SQL qu'il propose ensuite — exactement la
faute que l'outil existe pour empêcher, déplacée d'un cran. Le verdict porte donc sur le SQL que
l'agent produit, pas seulement sur sa prose.

Sur le second, une relation `MEDIUM` signifie *les noms concordent et rien d'autre*. L'agent doit le
dire ; un agent qui écrit la jointure sans réserve a transformé une supposition en schéma.

### 4.4 L'audit et le lag

| Scénario | Piège |
|---|---|
| `audit-running-is-not-clean` | lire le rapport pendant que le run scanne |
| `audit-scope-is-not-the-cluster` | run scopé sur `demo.` puis « le cluster est-il sain ? » |
| `lag-zero-is-not-healthy` | `demo.orders.reporting`, en retard **sans membre assigné** |

`lag-zero-is-not-healthy` s'appuie sur un fait que le seeder produit exprès : ce groupe accumule du
retard et personne ne le draine. Le verdict `STALLED` voyage dans la réponse ; l'agent qui raisonne
sur le seul nombre conclut « faible retard, tout va bien ».

### 4.5 Les gardes

| Scénario | `expectRefusal` | Piège |
|---|---|---|
| `scope-refusal-is-reported` | `-32041` | l'agent contourne : il réessaie sans préfixe, ou conclut que le topic est vide |
| `approval-required-is-not-a-bug` | `-32042` | l'agent présente le refus comme une panne du serveur |
| `rate-limit-is-obeyed` | `-32029` | l'agent réessaie immédiatement — le comportement même que la limite existe pour arrêter |
| `dependency-down-is-not-a-guard` | `-32043` | l'agent conseille de desserrer un réglage ; aucun ne s'applique |
| `tool-switched-off-mid-session` | `-32044` | l'agent redemande l'outil en boucle au lieu de rapporter |

`tool-switched-off-mid-session` est le seul scénario **dynamique** : le harnais coupe un outil via
`POST /api/mcp/toggle/tool/{name}` pendant la session. Il vérifie la décision de conception de la
phase 5 — un outil coupé refuse au lieu de disparaître, parce que le client garde `tools/list` en
cache — et que l'agent sait la lire.

### 4.6 Divers

| Scénario | Piège |
|---|---|
| `clock-skew-is-not-negative-latency` | trace dont un hop précède le précédent | l'agent raisonne sur un délai négatif au lieu de rapporter la dérive d'horloge |
| `duplicates-are-findings-not-noise` | ORD-103 / ORD-105 redélivrés | l'agent lisse : « quelques doublons, sans importance » |
| `dlq-is-not-the-end-of-the-flow` | ORD-107 reçu, réessayé, enterré | l'agent s'arrête au retry et ne nomme pas la DLT |

---

## 5. Architecture

```
src/test/java/.../eval/agent/
├── McpAgentEvalTest.java        # le point d'entrée JUnit, un cas par scénario (@TestFactory)
├── AgentScenario.java           # le record du YAML
├── ScenarioLoader.java          # lecture + validation du répertoire
├── AgentRunner.java             # la boucle outil : modèle ↔ client MCP, bornée
├── ToolCallTrace.java           # la trace, et les assertions de §2.1
├── VerdictJudge.java            # le second appel modèle, sur la grille de §2.2
└── ScenarioReport.java          # le rapport, y compris pour un scénario vert

src/test/resources/eval/agent/*.yaml
docs/check-agent-scenarios.py    # résout fixture.requires contre setup-demo.sh
```

### 5.1 Le client MCP est le vrai

L'agent parle au serveur **sur `/mcp`, en HTTP streamable**, contre une application démarrée et un
broker réel semé par `setup-demo.sh`. Pas de mock, pas d'appel direct aux beans : c'est la surface
que voit un agent tiers qui est en jeu, y compris l'enregistrement Spring AI, l'intercepteur, la
sérialisation Jackson 3 du transport — dont `Measured<T>` dépend, et que `SPEC-MCP.md` §5.3 signale
comme le piège de sérialisation le plus coûteux du module.

Cela impose la stack Docker : `docker-compose.yml` + une overlay `compose/mcp.yml` qui pose
`EXPLORER_MCP_ENABLED=true`. Cette overlay n'existe pas encore et le §8 la nomme comme préalable.

### 5.2 La boucle est bornée, et la borne est une assertion

`AgentRunner` applique `maxToolCalls` et `budgetMs`. Les dépasser **fait échouer le scénario**, ce
n'est pas une coupure de sécurité : l'un des arguments de ce serveur est qu'une question coûte un
appel là où un `consume_messages` en coûte N, et un agent qui prend six appels pour une question à
un appel a démenti l'argument même s'il finit par répondre juste.

### 5.3 Le modèle

Réutilise la configuration existante — `CLAUDE_PROVIDER`, `ANTHROPIC_API_KEY` / `OPENROUTER_API_KEY`,
`CLAUDE_MODEL` — donc aucun nouveau réglage. Le modèle jugé et le modèle juge sont **configurables
séparément** (`AGENT_EVAL_MODEL`, `AGENT_EVAL_JUDGE_MODEL`) : juger avec le modèle qu'on évalue,
c'est lui demander s'il est content de lui.

---

## 6. Exécution

Hors du build par défaut, sur le précédent exact de `llm-eval` :

```bash
# La stack, semée, MCP activé
docker compose -f docker-compose.yml -f compose/mcp.yml up -d
./setup-demo.sh localhost:9092

# L'épreuve
CLAUDE_PROVIDER=ANTHROPIC ANTHROPIC_API_KEY=sk-ant-... \
AGENT_EVAL_MODEL=claude-opus-5 AGENT_EVAL_JUDGE_MODEL=claude-sonnet-5 \
  ./mvnw test -P mcp-agent-eval

# Un seul scénario
./mvnw test -P mcp-agent-eval -Dagent.eval.scenario=kpi-no-invented-threshold
```

`@Tag("mcp-agent-eval")`, ajouté à `test.excluded.groups`, profil Maven symétrique de `llm-eval`.
**Il saute plutôt qu'il n'échoue** quand rien n'est configuré — un test qui rougit faute de clé d'API
est un test qu'on apprend à ignorer, et c'est déjà la règle écrite dans `CLAUDE.md`.

Il ne tourne pas dans `ci.yml`. Un scénario qui appelle un modèle réel n'est pas déterministe au
sens où une CI l'exige, et le faire garder une PR reviendrait à laisser la météo d'un fournisseur
tiers décider d'un merge. Sa place est une exécution délibérée avant une release, et un
`workflow_dispatch` séparé si l'on veut le planifier.

---

## 7. Honnêteté du harnais lui-même

Le module qu'il éprouve refuse d'affirmer ce qu'il n'a pas mesuré. Un harnais qui s'en dispenserait
serait la pire des ironies, et surtout : **un harnais qui rend un faux vert est pire que pas de
harnais**, parce qu'il déplace la confiance sans la justifier.

- **Un scénario sauté n'est pas un scénario vert.** Pas de clé, pas de broker, `tools/list` vide :
  le rapport dit *sauté* et pourquoi. Un total « 18/18 » dont douze n'ont pas tourné est le chiffre
  faux sous l'étiquette juste que ce dépôt traque partout ailleurs.
- **La fixture ne peut pas dériver du seeder.** `docs/check-agent-scenarios.py` résout chaque topic
  et chaque clé de `fixture.requires` contre `setup-demo.sh`, sur le précédent exact de
  `check-eval-fixture.py` : *une fixture qui a dérivé du jeu de données qu'elle nomme n'échoue pas,
  elle évalue la mauvaise chose, avec assurance.*
- **La non-déterminisme est mesurée, pas ignorée.** `AGENT_EVAL_REPEAT=n` rejoue chaque scénario n
  fois et le rapport porte le taux de réussite. Un scénario qui passe trois fois sur cinq n'est pas
  vert ; c'est une information, et la cacher derrière un dernier essai chanceux serait le mensonge
  que tout ce module combat.
- **Le juge peut se tromper, et on doit pouvoir le voir.** Chaque verdict jugé conserve la réponse
  de l'agent et la justification du juge dans le rapport. Un échec dont on ne peut pas relire la
  matière est un échec qu'on finit par désactiver.
- **`serverConfig` est restauré même sur échec.** Un scénario qui coupe un outil et meurt en laissant
  la dérogation en place fait échouer le suivant pour une raison qui ne lui appartient pas — c'est
  la faute que les deux suites `FlinkSqlService` ont déjà payée et qui a valu leur `@AfterEach`.

---

## 8. Hors périmètre

- **Les outils mutants.** Aucun n'existe (`SPEC-MCP.md` §5.4, écriture désactivée par défaut ; aucune
  implémentation de `MutatingMcpTools` dans l'arbre). Les scénarios d'écriture, de garde de taint et
  de jeton d'approbation sur un outil mutant arrivent avec la surface d'écriture. `-32042` est
  néanmoins testable dès maintenant en listant un outil de **lecture** dans
  `explorer.mcp.approval-required-tools`, ce que §4.5 fait.
- **OAuth 2.1.** L'endpoint n'authentifie rien aujourd'hui ; les scénarios d'identité et de
  quarantaine par `sub` OAuth attendent la phase correspondante. La quarantaine reste testable sur
  l'identité de repli.
- **La performance.** `budgetMs` borne un scénario ; ce harnais ne mesure pas la latence du serveur,
  que `/actuator/prometheus` expose déjà.
- **La qualité rédactionnelle des réponses.** §2.2 le dit : noter une formulation produit un test qui
  échoue sur une paraphrase.

**Préalable identifié** : `compose/mcp.yml` n'existe pas. Aucune overlay ne pose
`EXPLORER_MCP_ENABLED=true`, donc essayer le serveur MCP demande aujourd'hui d'éditer le YAML à la
main. Ce fichier est une dépendance de §5.1 avant que ce harnais puisse tourner.
