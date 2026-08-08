# Audit du déploiement Docker — démarrage et arrêt des services (2026-08)

Audit ciblé de la **surface de déploiement** : les six stacks `docker-compose*.yml` de la racine,
les quatre `Dockerfile*`, `.dockerignore`, le job `docker` de `release.yml`, et le cycle de vie
applicatif côté JVM (`@PreDestroy`, pools d'exécution, producteurs Kafka, émetteurs SSE).

Ce document décrit l'**état d'avant correction** et la décision prise pour chaque point. Les items
marqués ✅ sont corrigés dans le même commit ; ceux marqués 📋 sont constatés et volontairement
laissés ouverts, avec la raison.

---

## 1. Démarrage

### D1 — L'application attendait la fin du seeding pour démarrer ✅

`docker-compose.yml`, `docker-compose-kafka4.yml`, `docker-compose-llm.yml` et
`docker-compose-spectra.yml` faisaient tous dépendre le service applicatif de :

```yaml
depends_on:
  demo-setup:
    condition: service_completed_successfully
```

`demo-setup` crée 76 topics et y produit ~400 enregistrements : une à deux minutes, et le conteneur
applicatif n'était **même pas créé** avant la fin. L'UI était donc injoignable pendant tout ce
temps, pour des données dont elle n'a aucun besoin pour démarrer — le Dashboard interroge
`/api/dashboard` toutes les 30 s, donc les topics apparaissent au fur et à mesure de leur création.

Le seeding tourne désormais **à côté** de l'application, plus devant elle. Seule la dépendance au
broker (`service_healthy`) est conservée, qui est réelle : `MetricService.restoreFromKafka()` lit
`internal.metrics.config` au démarrage.

Cas le plus spectaculaire, `docker-compose-llm.yml` : l'application attendait aussi
`ollama-pull-model: service_completed_successfully`, c'est-à-dire le téléchargement complet de
`qwen2.5-coder:7b` — plusieurs gigaoctets — avant d'afficher une page. Le LLM n'est sollicité que
lorsqu'on ouvre Process Mining.

### D2 — Le seeding était rejoué à chaque `up` ✅

Les données Kafka vivent dans le volume nommé `kafka_data`, qui survit à `docker compose down`.
`demo-setup` est un service one-shot, et Compose ré-exécute un one-shot à chaque `up` : `setup-demo.sh`
reproduisait donc ses ~400 enregistrements dans des topics qui les contenaient déjà.

Deux conséquences, dont la seconde est la plus gênante :

* la minute de seeding était repayée à chaque démarrage ;
* le jeu de démonstration gagnait **une génération de doublons par redémarrage**, ce qui change
  silencieusement ce que rapportent la détection de doublons de l'audit et les traces Stream Flow —
  or ce jeu de données est calibré (`ORD-103`/`ORD-105` redélivrés, poison records dans
  `demo.orders.3.enriched`) précisément pour que ces fonctionnalités rapportent une valeur connue.

Le nouveau `seed-demo-once.sh` attend le broker, vérifie la présence du topic-marqueur
`internal.demo.seeded`, sème si absent, et ne crée le marqueur qu'**après** un seeding réussi — une
exécution interrompue ressèmera au démarrage suivant. `docker compose down -v` efface le volume donc
le marqueur : la remise à zéro reste la commande attendue.

Le préfixe `internal.` n'est pas décoratif : `StreamFlowService` exclut ces topics d'une trace
cluster-wide, le marqueur ne pollue donc aucune recherche.

Ce script remplace au passage quatre copies de la même ligne d'`entrypoint:` (attente du broker,
`sed` CRLF, `bash setup-demo.sh`) qui divergeaient déjà entre les fichiers.

### D3 — Le healthcheck du broker démarrait une JVM toutes les 5 secondes ✅

```yaml
test: ["CMD-SHELL", "/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092"]
interval: 5s
```

`kafka-broker-api-versions.sh` démarre une JVM complète. Docker exécute un healthcheck pendant
**toute la vie** du conteneur, pas seulement au démarrage : cette stack coûtait donc en permanence
un lancement de JVM toutes les cinq secondes — de l'ordre d'un cinquième de cœur, indéfiniment, pour
re-répondre à une question tranchée dans la première minute.

`interval: 30s` décrit désormais le régime établi et `start_period: 30s` couvre le démarrage (les
échecs y sont gratuits, ils ne consomment pas `retries`). Compromis assumé : le broker est déclaré
sain vers t≈30 s au lieu de t≈15 s, ce qui est très largement compensé par D1 (l'app ne suit plus le
seeding). Sur Docker 25+/Compose 2.20+, ajouter `start_interval: 2s` récupérerait les deux — non
retenu ici pour ne pas relever le socle requis par les stacks principales, `include:` (Compose 2.20)
n'étant exigé que par la stack Spectra.

### D4 — Le broker n'avait pas de politique de redémarrage ✅

Seul le service applicatif portait `restart: always`. Après un reboot de l'hôte ou un redémarrage du
démon Docker, l'application revenait donc **seule** : un explorateur pointé sur un broker éteint, en
échec de connexion permanent, avec des logs d'AdminClient en boucle.

`restart: unless-stopped` est posé sur les services longue durée (broker, Schema Registry, Ollama,
application), ce qui est aussi ce qu'utilise `deploy/kraft-platform/docker-compose.yml`. Le passage
de `always` à `unless-stopped` côté application est volontaire : après un `docker compose stop`
explicite, un `always` fait revenir le conteneur au redémarrage du démon, ce que personne n'attend.

Le stack de dev (`docker-compose-dev.yml`) reste sans politique de redémarrage sur `backend` : un
backend qui ne compile pas doit rester à terre et le dire, pas boucler.

### D5 — `schema-registry` était attendu « créé », pas « prêt » ✅

`docker-compose-kafka4.yml` faisait dépendre l'app de `schema-registry: condition: service_started`,
qui est satisfait dès que le conteneur démarre. Les premières inférences de schéma Avro pouvaient
donc courir contre le démarrage du registre. Un healthcheck HTTP (`GET /subjects`, le motif utilisé
par les exemples Confluent eux-mêmes) et `service_healthy` remplacent cette approximation.

---

## 2. Arrêt

### A1 — Aucun arrêt gracieux HTTP ✅

`server.shutdown` valait le défaut `immediate` : au SIGTERM — c'est-à-dire à chaque
`docker compose down`, chaque remplacement de conteneur, chaque `update.ps1` — Tomcat ferme les
sockets et les requêtes en vol sont tranchées.

Or cette application a des requêtes qui durent légitimement des dizaines de secondes : un audit de
cluster, une trace Stream Flow sur tout le cluster (budget 60 s), un SELECT Flink (10 s). Les couper
au niveau socket ne fait pas qu'échouer la requête : un audit interrompu ainsi ne passe pas par sa
propre voie d'annulation coopérative et n'écrit donc pas son rapport partiel.

`server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase` (20 s à ce
stade, ramené à 15 s par S4).

### A2 — `stop_grace_period` n'existait nulle part, et le budget d'arrêt le dépasse ✅

Le défaut Docker est de 10 secondes, après quoi le conteneur est SIGKILLé. Ce délai est plus court
que ce que l'arrêt propre demande, des deux côtés de la stack.

**Côté application.** La destruction des beans est séquentielle et chaque pool attend jusqu'à 5 s :

| Bean | Attente |
|---|---|
| `FlinkSqlService.queryExecutor` | 5 s |
| `StreamFlowService.executorService` | 5 s |
| `FlinkRuntimeCoordinator.mutationExecutor` | 5 s |
| `AuditService.auditExecutor` + `topicAuditExecutor` | 5 s + 5 s |
| `KafkaLiveConsumer.scheduler` + `analysisExecutor` | 5 s + 5 s |

soit jusqu'à ~35 s, **après** les 20 s d'arrêt gracieux HTTP. Personne n'avait borné le total, et
personne n'avait accordé au conteneur le temps correspondant. En pratique le cas courant (rien en
vol) est instantané, mais le cas qui compte — on arrête la stack pendant un audit — est exactement
celui qui atteignait le SIGKILL, au milieu de l'écriture du rapport dans `internal.audit.history`.

`stop_grace_period: 45s` sur le service applicatif de toutes les stacks — une accommodation,
pas une correction : **S4 supprime le cumul lui-même** et ramène ce délai à 35 s.

**Côté broker.** Un nœud KRaft qui s'arrête vide ses logs et referme proprement les coordinateurs de
groupes et de share-state. SIGKILLé à 10 s en plein flush, il laisse des segments à rejouer : le
démarrage suivant, sur un volume de démo alimenté, passe d'une dizaine de secondes à nettement plus.
`stop_grace_period: 30s` sur le broker.

### A3 — `AdminClient.close()` sans borne ✅

`KafkaAdminService.close()` appelait `adminClient.close()`, dont la surcharge sans argument attend
les appels en cours **sans échéance**. Un `describe` en cours de retry contre un broker injoignable
— c'est-à-dire le cas ordinaire quand toute la stack s'arrête, ou quand l'app a été repointée sur un
cluster disparu — bloquait donc l'arrêt jusqu'au SIGKILL, en plein milieu de la destruction des
beans, avec les producteurs pas encore fermés.

`adminClient.close(Duration.ofSeconds(5))`. L'effet se voit dans les logs d'`ApplicationContextTest`,
qui démarre le contexte complet sans broker : `Timed out 74 remaining operation(s) during close`,
puis l'arrêt se poursuit.

### A4 — `KafkaProducer.close()` sans borne, sur les deux producteurs persistants ✅

Même défaut, même conséquence, pour `AuditService.closeHistoryProducer()` et
`MetricService.closeConfigProducer()` : `close()` sans argument attend l'acquittement de tous les
enregistrements en tampon, indéfiniment. Le cas d'`AuditService` est doublement piégeux, la méthode
étant aussi appelée depuis la **voie d'échec** de `persistAuditHistory()` — donc précisément quand le
broker vient de ne pas répondre. `close(Duration.ofSeconds(5))` des deux côtés.

### A5 — Les sessions SSE survivantes n'étaient pas closes ✅

`KafkaLiveConsumer.shutdown()` signale chaque session, arrête les pools, puis ferme les consumers
restants — ceux dont la tâche de polling n'a jamais eu l'occasion d'exécuter `finishSession()`.
C'est `finishSession()` qui appelle `sseEmitterManager.complete()` : ces sessions-là laissaient donc
le navigateur avec un flux SSE qui meurt avec la socket, ce que la page lit comme un incident réseau
plutôt que comme un serveur qui s'arrête. `complete()` est ajouté dans cette boucle, et le
`consumer.close()` y est borné à 5 s pour la même raison qu'en A3.

---

## 3. Images et publication

### I1 — Le montage du fichier de log était cassé, et maintenait l'image en root ✅

`docker-compose.yml` et `docker-compose-llm.yml` portaient :

```yaml
volumes:
  - ./Kafkaexplorer.log:/app/logs/kafkaexplorer.log
```

Ce fichier **n'existe pas** dans le dépôt (il est gitignoré). Docker, à qui l'on demande de monter un
chemin hôte absent, crée un **répertoire**. Logback ouvre alors `logs/kafkaexplorer.log` et trouve un
répertoire : la journalisation dans un fichier était morte dans ces deux stacks, silencieusement, et
la racine du dépôt gagnait un répertoire parasite au premier `up`.

Le commentaire du `Dockerfile` invoquait précisément ce montage pour justifier de rester en `root` —
il justifiait donc une image privilégiée par une fonctionnalité qui ne marchait pas.

Remplacé par deux volumes nommés, `explorer_logs:/app/logs` et `explorer_data:/app/data`. Le second
corrige un oubli distinct : `explorer.flink-job-store-path` vaut `data/flink-jobs.json`, donc
l'historique des jobs Flink était perdu à chaque recréation du conteneur. Les logs partent aussi sur
stdout, où `docker compose logs` les trouve — c'est la convention Docker et le premier endroit où
l'on regarde.

### I2 — Les images d'exécution tournaient en root ✅

Une fois I1 réglé, plus rien ne s'y opposait. `Dockerfile` et `Dockerfile.release` créent l'uid/gid
10001, créent et possèdent `/app/logs` et `/app/data`, copient le JAR avec `--chown`, et déclarent
`USER 10001:10001` — en numérique, pour qu'un contrôle d'admission Kubernetes `runAsNonRoot` puisse
le lire. Un volume nommé monté sur ces chemins hérite de la propriété fixée dans l'image, ce qu'un
fichier bind-monté depuis l'hôte ne fait pas : c'est ce qui rend I1 et I2 indissociables.

### I3 — `latest` bougeait sur n'importe quel tag ✅

`release.yml` déclarait `type=raw,value=latest` sans condition. Pousser `v1.3.0-rc1` faisait donc
d'une pré-version ce que `docker run ghcr.io/devdownin/kafkaexplorer` télécharge. Gardé par
`enable=${{ !contains(github.ref_name, '-') }}` — le tiret est le marqueur de pré-version semver.

### I4 — Image mono-architecture ✅

Le job `docker` ne construisait que `linux/amd64`. Sur un hôte Apple Silicon ou Graviton, toute la
JVM tournait sous émulation QEMU. `platforms: linux/amd64,linux/arm64` : il n'y a ici aucune
compilation croisée à payer, `Dockerfile.release` étant une base JRE plus un `COPY` d'un JAR
indépendant de l'architecture.

### I5 — Contexte de build ✅

`.dockerignore` laissait passer `src/test/`, `docs/`, `deploy/`, tous les `.md` (dont
`CLAUDE.md` ~90 Ko et `flink-sql-2.2.md` ~72 Ko), les `.ps1` (dont `install-skills.ps1` ~37 Ko) et
les `.skill`. Le contexte entier est transmis au démon à **chaque** build ; l'étape backend ne copie
que `src/main/java` et `src/main/resources`. Exclus.

---

## 4. Second lot — la stack est exécutée, plus seulement construite

Les cinq points ci-dessous ont été traités dans un deuxième temps, sur la base des « non traités »
du premier lot.

### S1 — Rien n'exécutait jamais le déploiement ✅

C'est le manque structurant, et l'explication commune des trois bugs les plus bêtes de la section 1 :
`ci.yml` **construisait** l'image et s'arrêtait là. Un déploiement peut se construire parfaitement et
être cassé — un montage de log qui devient un répertoire, une app séquestrée derrière le seeding, un
délai de grâce plus court que l'arrêt qu'il doit couvrir : rien de tout cela n'apparaît à la
construction.

Le job `docker` démarre désormais la stack (`docker-compose.ci.yml` fournit l'image construite au lieu
de la reconstruire) et vérifie, chaque assertion correspondant à un bug de la section 1 :

| Assertion | Régression couverte |
|---|---|
| le conteneur devient `healthy` | D1, D3 (et le `HEALTHCHECK` lui-même) |
| `/actuator/health/{liveness,readiness}` répondent `UP` | S3 |
| `GET /api/dashboard` répond 200 | joignabilité réelle du broker |
| `id -u` = 10001 | I2 |
| `/app/logs/kafkaexplorer.log` est non vide | **I1** |
| le seeder ressort « already present » au second passage | **D2** |
| le code de sortie n'est pas 137 | **A1, A2** — 137 = SIGKILL, exactement ce que le couple arrêt gracieux / `stop_grace_period` existe pour éviter |

Un second job, `release-image`, construit `Dockerfile.release` à partir du JAR du job `build` et le
démarre **sans broker** : jusqu'ici ce fichier n'était bâti que par `release.yml`, donc toute
modification y était étrennée par la publication elle-même — précisément ce que `CLAUDE.md` interdit
en demandant que les deux surfaces d'exécution ne divergent pas.

### S2 — JAR découpé en couches ✅ (ex-N1)

`COPY app.jar` d'un fat-jar : chaque version publiée poussait une couche unique de plusieurs centaines
de mégaoctets sans aucune réutilisation, alors que ~95 % de son contenu (Flink, Kafka, Spring) est
identique d'une version à l'autre. Les deux images extraient maintenant les quatre couches standard —
`dependencies`, `spring-boot-loader`, `snapshot-dependencies`, `application` — de la plus stable à la
moins stable, en quatre `COPY` donc quatre couches : une version corrective ne republie que la
dernière.

`-Djarmode=tools` est l'entrée Spring Boot 3.3+ (`layertools` n'existe plus), `--launcher` produit la
disposition exécutable, et l'`ENTRYPOINT` devient
`java org.springframework.boot.loader.launch.JarLauncher` — il n'y a plus de fat-jar dans l'image.
La commande, la disposition produite et le démarrage par `JarLauncher` ont été vérifiés localement
contre un JAR Spring Boot 4.1.0 réel avant d'être écrits ici ; S1 les vérifie désormais à chaque
exécution de CI.

Dans `Dockerfile.release`, l'étape d'extraction est épinglée à `--platform=$BUILDPLATFORM` : elle ne
fait que réarranger du bytecode indépendant de l'architecture, donc la variante arm64 du build
multi-arch n'a aucune raison de la rejouer sous QEMU.

### S3 — `liveness` et `readiness` séparés ✅

Le projet n'utilise pas `spring-kafka` mais `kafka-clients` directement : Spring Boot
n'auto-configure donc **aucun** indicateur de santé Kafka, et `/actuator/health` répondait `UP` tant
que le contexte était debout, quoi que fasse le broker. Le healthcheck du conteneur qualifiait de
« saine » une application incapable de joindre le moindre cluster.

`KafkaHealthIndicator` (bean `kafka`, sonde bornée à 2 s sur `describeCluster().clusterId()`) est
versé au groupe **readiness** uniquement, et le `HEALTHCHECK` des deux images vise désormais
`/actuator/health/liveness`.

La séparation n'est pas cosmétique : un broker injoignable ne veut pas dire que ce processus doit
être redémarré ou retiré du service — l'UI sert toujours, et la page Settings permet justement de
repointer l'application ailleurs, ce dont on a précisément besoin à cet instant. Cela veut dire
« pas prêt à répondre à des requêtes », ce qui est la définition de readiness. `HealthProbesTest`
vérifie sur le contexte réel que les deux groupes existent, que readiness contient `kafka` et que
liveness ne le contient pas — une faute de frappe dans le `include` laisserait sinon le groupe
silencieusement absent et le `HEALTHCHECK` lirait un 404 comme un conteneur mort.

### S4 — Budget d'arrêt partagé, au lieu de cumulé ✅

A2 constatait ~35 s d'attentes additives (six pools à 5 s, destruction séquentielle) et y répondait
par un `stop_grace_period: 45s` — accommoder le problème, pas le corriger. `ShutdownBudget` donne
maintenant **une seule échéance de 10 s partagée** par tous les pools : le premier détruit démarre
l'horloge, les suivants héritent de ce qu'il en reste, avec un plancher de 500 ms chacun pour que le
dernier ne soit pas systématiquement interrompu à la seconde où le budget s'épuise. Le cas courant
(rien en vol) est inchangé et instantané.

Conséquence en cascade : `timeout-per-shutdown-phase` passe de 20 s à 15 s (une requête au timeout
par défaut de 10 s plus sa fin), et `stop_grace_period` de 45 s à **35 s** — 15 + 10 + la sortie de
la JVM. Et surtout le budget cesse de grandir de cinq secondes à chaque service qui gagne un pool.

### S5 — Ports publiés sur la loopback ✅

`- "8080:8080"` publie sur `0.0.0.0` : une application **sans authentification**, dont
`POST /api/config` peut repointer le cluster Kafka à chaud, était offerte à tout le réseau local dès
un `docker compose up`. Tous les ports publiés (8080, 9092, 8081, 11434, 5173, 8090) passent à
`${BIND_ADDR:-127.0.0.1}` — la loopback par défaut, une seule variable pour exposer délibérément :
`BIND_ADDR=0.0.0.0 docker compose up -d`.

---

## 5. Troisième lot — reproductibilité et ergonomie

### T1 — Ports paramétrables, `.env.example` ✅

8080 et 9092 sont les deux ports les plus disputés d'un poste de développeur, et en changer voulait
dire éditer six fichiers. Ils passent tous par des variables — `EXPLORER_PORT`, `KAFKA_PORT`,
`SCHEMA_REGISTRY_PORT`, `OLLAMA_PORT`, `VITE_PORT` — avec les mêmes valeurs par défaut qu'avant, et
`.env.example` documente l'ensemble (Compose lit `.env` à la racine automatiquement ; un
`EXPLORER_PORT=9080 docker compose up -d` suffit pour un cas ponctuel).

Un détail qui aurait rendu le paramétrage trompeur : `KAFKA_ADVERTISED_LISTENERS` réécrit lui aussi
`PLAINTEXT_HOST://localhost:${KAFKA_PORT}`. Sans cela, un client hôte à qui l'on dit de se connecter
sur le nouveau port se serait fait renvoyer vers 9092 par le broker lui-même — un port publié qui ne
sert à rien, et un diagnostic pénible. Le listener interne (`kafka:29092`), lui, ne bouge pas : c'est
celui que l'application utilise.

### T2 — Les dépendances Maven sont une couche d'image ✅ (ex-N2)

L'étape backend copiait `pom.xml` puis les sources, puis lançait un unique `mvn package` sous
`RUN --mount=type=cache,target=/root/.m2`. Ce montage rend un rebuild **local** rapide et ne fait
**rien** pour la CI : les caches de montage BuildKit ne sont pas exportés avec les couches, donc
`cache-to: type=gha` n'en transportait rien et le job `docker` retéléchargeait tout l'arbre
Flink/Kafka/Spring à chaque exécution.

`dependency:go-offline` est désormais une étape à part, dont la couche ne dépend que de `pom.xml`, et
le dépôt Maven vit **dans l'image** (`-Dmaven.repo.local`) et non dans un montage — une couche est la
seule forme de cache qui survive d'un runner à l'autre. L'étape étant jetée, sa taille ne coûte rien
dans l'image publiée.

Deux choix explicites. `|| true` : `go-offline` est purement du préchauffage, il rate des
dépendances de plugins qui ne se résolvent que plus tard et échoue franchement sur certaines
combinaisons — le `package` qui suit tourne en ligne et récupère ce qui manque, donc une étape de
cache ne doit jamais pouvoir casser un build. Et la contrepartie assumée : un build local qui
**modifie le pom** retélécharge l'arbre, là où le montage l'évitait. C'est le cas rare ; le cas
fréquent (CI, et tout build dont le pom n'a pas bougé) y gagne.

### T3 — Images de base épinglées par digest + Dependabot ✅ (ex-N5)

`maven:3.9-eclipse-temurin-21` et `eclipse-temurin:21-jre-alpine` flottaient : deux builds du même
commit pouvaient embarquer des JRE différents. Les cinq `FROM` des deux Dockerfiles portent
maintenant `tag@sha256:…` — le tag reste devant, il est la seule chose qui dise *ce que* le digest
est.

Épingler sans automatiser serait un mauvais échange : on gagne la reproductibilité et on gèle les
correctifs de sécurité du JRE. `.github/dependabot.yml` gagne donc l'écosystème `docker`, groupé —
les trois images bougent indépendamment mais un build marche sur l'ensemble ou pas du tout, et les
deux Dockerfiles doivent être bumpés ensemble puisque leurs étages d'exécution sont volontairement
identiques.

`ollama/ollama:latest` est passé à `0.32.6` au passage : c'était le dernier tag réellement flottant
de l'arbre, dans une stack censée être reproductible.

Les images de service des stacks Compose (`apache/kafka:4.2.0`, `cp-schema-registry:7.6.0`) gardent
leur tag de version, déjà exact. Elles pourraient l'être aussi par digest via l'écosystème
`docker-compose` de Dependabot — non fait ici pour ne pas ajouter une configuration dont la prise en
charge dépend de l'instance.

---

## 6. Constaté, non traité

### 📋 N3 — Noms de conteneurs fixes, projet Compose implicite

Toutes les stacks racine posent `container_name: kafka` et `container_name: kafka-sql-explorer`, qui
sont globaux au démon : deux stacks ne peuvent pas tourner en parallèle. Aucune ne déclare de `name:`
au niveau racine (sauf Spectra), elles partagent donc le nom de projet dérivé du répertoire, et donc
le volume `kafka_data`.

Laissé tel quel volontairement : ces fichiers sont des **alternatives** (on lance l'un *ou* l'autre),
la configuration du broker y est identique, et le partage du volume est ce qui permet de passer de
`docker-compose.yml` à `docker-compose-kafka4.yml` en conservant ses topics `internal.*`. Leur donner
des `name:` distincts scinderait ce volume et ferait « disparaître » les données des utilisateurs
existants. La contrepartie — pas de stacks simultanées — est documentée ici plutôt que corrigée.

### 📋 N4 — Pas de limites de ressources

Aucune stack ne pose de `mem_limit`/`cpus`. `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0` est bien
présent dans les deux images, mais sans limite mémoire de conteneur les 75 % portent sur la RAM de
**l'hôte** : sur un poste à 32 Go, la JVM se croit autorisée à 24 Go. Ce sont des stacks de
démonstration sur poste de travail, où une limite arbitraire gênerait plus qu'elle n'aiderait ; en
déploiement réel, la limite doit venir de l'orchestrateur, et `MaxRAMPercentage` fera alors ce pour
quoi il est là.

### 📋 N6 — `deploy/kraft-platform/` sans `stop_grace_period`

Cette stack (broker + ksqlDB + AKHQ + REST + Prometheus/Grafana) porte bien `restart: unless-stopped`
et des healthchecks partout, mais aucun `stop_grace_period` : son broker Confluent est exposé au même
SIGKILL à 10 s que A2 décrit, avec des volumes plus gros. Non modifiée ici : elle a sa propre
documentation, son propre cycle de migration (elle vient d'être portée en KRaft) et ne fait pas
partie du déploiement de Kafka Explorer lui-même. À traiter avec elle.

---

## 7. Validation

Pas de démon Docker dans l'environnement de cet audit. Ce qui a pu être vérifié ici l'a été :

* parse YAML de tous les fichiers Compose et des deux workflows, `sh -n` sur `seed-demo-once.sh` ;
* modifications Java par le harnais hors-ligne — `./verify-offline.sh`, **329 tests, 0 échec**
  (4 ignorés, préexistants), dont `ShutdownBudgetTest`, `KafkaHealthIndicatorTest` et
  `HealthProbesTest`, ce dernier montant le contexte Spring réel pour vérifier les groupes de santé ;
* l'extraction en couches (S2) : commande `-Djarmode=tools … extract --layers --launcher`,
  disposition produite et démarrage par `JarLauncher`, joués localement contre un JAR Spring Boot
  4.1.0 réel — le point le plus risqué du lot, et celui qui touche le chemin de publication ;
* la borne d'`AdminClient.close()` (A3) est observable dans les logs d'`ApplicationContextTest`, qui
  démarre sans broker : `Timed out 74 remaining operation(s) during close`, puis l'arrêt se poursuit.

Ce qui demande un vrai démon — construction des images, non-root, multi-arch, temps de démarrage,
code de sortie à l'arrêt — est désormais couvert par la CI elle-même (S1) : le job `docker` démarre
la stack à chaque exécution et le job `release-image` construit et démarre `Dockerfile.release`. Ces
deux jobs sont la validation de ce document autant que sa conséquence.
