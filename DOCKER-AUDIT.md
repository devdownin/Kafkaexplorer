# Audit du déploiement Docker — démarrage et arrêt des services (2026-08)

Audit ciblé de la **surface de déploiement** : les stacks `docker-compose*.yml` de la racine (six
au moment de l'audit, douze aujourd'hui — cf. § 8), les quatre `Dockerfile*`, `.dockerignore`, les
deux workflows GitHub, la plateforme `deploy/kraft-platform/` (supprimée du dépôt depuis, cf. § 6)
et le cycle de vie applicatif côté JVM (`@PreDestroy`, pools d'exécution, producteurs Kafka,
émetteurs SSE).

Ce document décrit l'**état d'avant correction** et la décision prise pour chaque point. Il a été
étendu par lots successifs (§ 4 à 7) ; tous les points relevés sont désormais traités, chacun avec
la raison du choix retenu — y compris les deux que j'avais d'abord recommandé de laisser ouverts,
et où c'est la solution envisagée, non le constat, qui posait problème (voir Q1 et Q2).

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

Les images de service des stacks Compose (`apache/kafka:4.3.1`, `cp-schema-registry:7.6.0`) gardent
leur tag de version, déjà exact. Elles pourraient l'être aussi par digest via l'écosystème
`docker-compose` de Dependabot — non fait ici pour ne pas ajouter une configuration dont la prise en
charge dépend de l'instance.

---

## 6. Quatrième lot — la plateforme `deploy/kraft-platform`

### P1 — Arrêt et coût du healthcheck ✅

Cette stack (broker + ksqlDB + AKHQ + REST + Prometheus/Grafana) est par ailleurs bien tenue —
`restart: unless-stopped` et healthchecks partout, versions épinglées, profils — mais elle portait
les deux mêmes défauts que les stacks principales.

`stop_grace_period` sur les **trois services qui ont un état** : `kafka-00` (flush des logs et des
index), `ksqldb-server` (validation des offsets Kafka Streams, fermeture de RocksDB) et `prometheus`
(écriture du head block ; interrompu, il rejoue le WAL sur 15 jours de rétention au démarrage
suivant). 30 s chacun. `kafka-rest`, `akhq`, `schema-registry` et `grafana` gardent le défaut : ce
sont des façades dont l'état vit ailleurs, il n'y a rien à vider.

Healthcheck du broker : `interval: 10s` sur `kafka-broker-api-versions`, donc une JVM toutes les dix
secondes pour la vie du conteneur → 30 s, `start_period` inchangé. Les autres healthchecks sont des
`curl` locaux et restent à 15 s.

Le détail est repris dans le README de la stack, qui est là que ses opérateurs regardent.

**Non touché, et délibérément** : les ports publiés sur `0.0.0.0`. Contrairement aux stacks
principales, celle-ci est une plateforme *partagée* — `KAFKA_EXTERNAL_HOST=kafkadev` et le listener
`OUTSIDE` annoncé existent pour que d'autres machines s'y connectent ; la lier à la loopback lui
retirerait sa raison d'être. Le README assume déjà explicitement l'absence d'authentification
(PLAINTEXT partout, Grafana en anonyme Admin, AKHQ et le REST Proxy ouverts) comme un choix « à
réserver à un réseau fermé ». C'est une décision d'exploitation, pas un défaut de configuration.

---

---

## 7. Cinquième lot — les deux derniers points ouverts

### Q1 — `container_name` retiré, service applicatif renommé ✅ (ex-N3)

`container_name` est un nom **global au démon** : les stacks racine posaient toutes
`kafka` et `kafka-sql-explorer`, donc deux d'entre elles ne pouvaient jamais tourner
ensemble, et passer de `docker-compose.yml` à `docker-compose-kafka4.yml` sans `down`
préalable échouait sur une collision de nom.

L'objection que j'avais formulée contre ce point tenait à la solution envisagée, pas au
constat : donner un `name:` distinct à chaque fichier aurait scindé le volume `kafka_data`
et fait « disparaître » les topics des utilisateurs existants. Retirer `container_name`
n'a pas cet effet — le nom de projet reste celui du répertoire, donc le volume garde son
nom — et suffit : Compose dérive `<projet>-<service>-<n>`, et `docker compose -p autre …
up` donne une seconde stack entièrement indépendante.

Contrepartie : on s'adresse aux services par leur nom de service, `docker compose logs
kafka` plutôt que `docker logs kafka`. C'est de toute façon la bonne habitude, et le
smoke test de la CI résout désormais le conteneur par `docker compose ps -q explorer`.

Le service applicatif s'appelait `app` dans deux fichiers et `explorer` dans trois : il
est `explorer` partout. Outre l'incohérence, un overlay ne peut pas viser un service dont
le nom change d'une stack à l'autre — Compose créerait l'autre nom comme un nouveau
service sans image et ferait échouer le `up` entier. C'est ce qui rend Q2 possible.

`deploy/kraft-platform/` garde ses `container_name` : c'est une plateforme partagée dont
il n'existe qu'une instance par hôte, et sa documentation s'adresse déjà aux services
(`docker compose exec kafka-00 …`).

### Q2 — Limites de ressources, en overlay ✅ (ex-N4)

Là encore, le constat était juste et c'est la manière qui posait problème : une limite
trop basse est pire que pas de limite du tout — la JVM est tuée par l'OOM killer au lieu
de déclencher un GC — et personne ne peut choisir le bon chiffre d'avance pour une stack
de démonstration qui lit le cluster d'un inconnu.

`docker-compose.limits.yml` est donc un overlay explicite :

```bash
docker compose -f docker-compose.yml -f docker-compose.limits.yml up -d
```

Ce qu'il corrige réellement : les deux images posent
`JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0`, et ce drapeau lit la limite mémoire **du
conteneur** — en son absence, celle de l'hôte. Sur un poste à 32 Go, la JVM se croit donc
autorisée à 24 Go, soit exactement le contraire de ce pour quoi le drapeau est là. C'est
`mem_limit` qui lui donne un sens.

`mem_limit` / `cpus`, et non un bloc `deploy:` : ce dernier est de la syntaxe Swarm,
ignorée en silence par `docker compose up` — la plateforme KRaft en a porté un pendant des
années, qui donnait l'illusion d'une politique jamais appliquée.

Les valeurs sont dans `.env.example` (`EXPLORER_MEM_LIMIT`, `KAFKA_MEM_LIMIT`, et les CPU),
avec la règle d'usage : un conteneur tué en 137 sous charge signifie que la limite a été
atteinte — c'est une information, on augmente la valeur plutôt que de retirer l'overlay.

---

## 8. Sixième lot — ce que personne n'analysait

Ce lot n'est pas sorti d'une relecture : il est sorti d'un contrôle ajouté à la CI, qui a trouvé
en quelques secondes ce que douze fichiers Compose non analysés cachaient depuis des jours.

### V1 — Aucun fichier Compose n'était analysé par le build ✅

Douze fichiers, aucun `docker compose config` nulle part. C'est exactement la forme de
pourrissement que ce dépôt a déjà payée deux fois (une stack que personne n'exécute, une image que
personne ne construit), et le contrôle est le moins cher qui soit : quelques secondes, pas de
démon, pas de réseau, aucune image tirée.

Le job `compose-lint` résout les 18 combinaisons — chaque stack, chaque overlay **superposé à sa
base** puisqu'un overlay seul est un ensemble de services sans image, donc invalide par
construction. Il **échoue aussi sur un fichier Compose qu'aucune combinaison ne nomme** : une
stack ajoutée demain ne peut pas échapper à la relecture. L'`include:` de
`docker-compose-spectra.yml` est résolu contre un stub de trois lignes — ce qui est sous test,
c'est la syntaxe de *notre* fichier, pas la disponibilité d'un autre dépôt.

### V2 — `docker-compose-kafka4.yml` ne démarrait plus du tout ✅

Trouvé par V1, au premier essai. La stack que le dépôt **recommande** refusait de démarrer depuis
le 13 août (`c0aaf41`) : deux montages de volumes ajoutés au service `explorer` sans leurs
déclarations de premier niveau.

```
service "explorer" refers to undefined volume explorer_logs: invalid compose project
```

Avant la création d'un seul conteneur. Neuf jours, sur le chemin le plus emprunté de la
documentation, sans que rien ne le signale — parce que rien n'analysait ces fichiers.

### V3 — Un tag flottant, sous le commentaire qui affirmait le contraire ✅

`docker-compose-llm.yml` épinglait `ollama/ollama` avec ce commentaire : « le seul tag flottant
restant dans l'arbre ». Deux services plus bas, `ollama-pull-model` tournait sur
`curlimages/curl:latest`. Une affirmation sur l'épinglage est précisément le genre d'affirmation
qui se périme sans être relue.

`docs/check-image-pins.py` la vérifie désormais, avec deux autres propriétés qu'aucun contrôle ne
couvrait : les images llama.cpp CPU et CUDA doivent nommer **le même build** (l'overlay GPU doit
changer le matériel, pas la révision du moteur), et l'image de l'explorateur que tire la stack
« images publiées » doit être la **version courante** — ce défaut est écrit à la main et
Dependabot ne sait pas lire une forme `${VAR:-1.8.8}`, donc rien d'autre ne le ferait bouger. Ce
dernier point échoue sur une *publication* plutôt que sur une modification : c'est le moment où
le rappel est dû.

### V4 — Le prompt ne tenait pas dans la fenêtre du modèle ✅

Hors surface Docker au sens strict, mais trouvé en dimensionnant la stack SpectraLLM et corrigé
dans les fichiers de déploiement : `process-mining.prompt-char-budget` vaut 120 000 caractères
(~30 000 jetons) pendant qu'Ollama donne 4 096 jetons à un modèle sauf VRAM suffisante, et que le
client compatible OpenAI n'envoie aucun `num_ctx`. Ollama ne refuse pas l'excédent — il enlève les
messages les plus anciens et le journalise en DEBUG. Les stacks posent maintenant la fenêtre et le
budget ensemble (`OLLAMA_CONTEXT_LENGTH` / `LLM_CONTEXT` contre
`PROCESS_MINING_PROMPT_CHAR_BUDGET`) ; le défaut applicatif est inchangé, un modèle hébergé
pouvant se le permettre, et porte la règle à côté de lui.

### V5 — La paire Explorer + SpectraLLM démarrable sans rien construire ✅

`docker-compose-spectra-hub.yml` et ses quatre overlays (`gpu`, `small`, `limits`, `ingest`).
Trois décisions y sont structurantes et sont documentées dans l'en-tête du fichier : les modèles
vivent dans un **volume nommé** avec un one-shot d'initialisation de propriété (même idiome que
`kafka-data-init`, et pour la même raison — l'image llama.cpp ne porte aucun `/app/data`) ;
**rien n'attend** les ~4,8 Go de poids du premier démarrage ; et l'ingestion Kafka est un
**overlay** plutôt qu'un drapeau, parce que ce que le drapeau seul rate sont deux problèmes
d'ordonnancement — un consommateur qui s'abonne à un topic inexistant le crée à une partition au
lieu de trois, et indexer avant que le modèle d'embedding ne soit là envoie tout l'historique en
`<topic>.DLT`.

Le job `spectra-hub-stack` la démarre sur `main`, **sans les modèles** : l'assertion qui vaut la
peine à propos d'un modèle absent, c'est que les conteneurs l'attendent au lieu de boucler en
crash. Il dépose ensuite un modèle 0,5B et exige que `POST /api/config/test-llm` réponde `ok` —
un appel qui traverse réellement explorateur → spectra-api → llm-chat.

---

## 9. Validation

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

Le sixième lot (§ 8) en ajoute trois, dans le même esprit : `compose-lint` et
`docs/check-image-pins.py`, qui ne demandent ni démon ni réseau et tournent partout, et
`spectra-hub-stack`, qui démarre la stack d'images publiées sur `main`. Là encore, le contenu de
ce lot est ce que ces contrôles ont trouvé — pas ce qu'une relecture avait deviné.
