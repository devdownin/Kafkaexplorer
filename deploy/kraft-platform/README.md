# Plateforme Kafka de dev — migration ZooKeeper → Kafka 4.2 (KRaft)

Migration de la stack `cp-kafka:7.9.2` + ZooKeeper vers **Confluent Platform 8.3
(= Apache Kafka 4.2), en mode KRaft**, sans ZooKeeper.

```bash
cp .env.example .env                              # ajuster REGISTRY / KAFKA_EXTERNAL_HOST
curl -sSLo jmx-exporter/jmx_prometheus_javaagent-0.20.0.jar \
  https://repo1.maven.org/maven2/io/prometheus/jmx/jmx_prometheus_javaagent/0.20.0/jmx_prometheus_javaagent-0.20.0.jar

docker compose up -d                              # broker + ksqlDB + AKHQ + REST
docker compose --profile observability up -d      # + Prometheus + Grafana
docker compose --profile schema-registry up -d    # + Schema Registry
docker compose --profile cli run --rm ksqldb-cli  # console ksqlDB à la demande
```

| Service | URL | Note |
|---|---|---|
| Kafka (hors docker) | `${KAFKA_EXTERNAL_HOST}:9092` | listener `OUTSIDE` |
| Kafka (dans `dip`) | `kafka-00:29092` | listener `INTERNAL` |
| AKHQ | http://localhost:9922 | |
| ksqlDB | http://localhost:8088 | |
| Kafka REST | http://localhost:8082 | |
| Schema Registry | http://localhost:8081 | profil `schema-registry` |
| Prometheus | http://localhost:9090 | profil `observability` |
| Grafana | http://localhost:3000 | profil `observability` |
| Métriques broker | http://localhost:9200/metrics | jmx_exporter |

---

## 1. Ce que la migration change

### ZooKeeper disparaît

Kafka 4.0 a **supprimé** ZooKeeper : il n'y a plus de mode de compatibilité. Le
nœud `kafka-00` porte désormais les deux rôles (`broker,controller`) dans une
seule JVM, et le quorum de métadonnées vit dans le log KRaft, sur le même volume
que les données.

| Avant | Après |
|---|---|
| `KAFKA_BROKER_ID: 0` | `KAFKA_NODE_ID: 0` |
| `KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181` | `KAFKA_PROCESS_ROLES` + `KAFKA_CONTROLLER_QUORUM_VOTERS: 0@kafka-00:9093` |
| — | `KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER` + listener `CONTROLLER://0.0.0.0:9093` |
| — | `CLUSTER_ID` (identité du cluster, formatée au premier démarrage) |
| service `zookeeper` | supprimé |

### Le port 9093 n'est plus publié

Il était publié sans usage réel côté ZooKeeper ; c'est maintenant le **listener
du plan de contrôle KRaft**. Le publier reviendrait à exposer le quorum du
cluster. Il reste joignable dans le réseau `dip`, ce dont personne n'a besoin
hors du broker lui-même.

### Control Center est retiré

`cp-enterprise-control-center` est en fin de vie sur la ligne CP 8.x, remplacé
par *Control Center Next Gen*, qui impose ses propres Prometheus et Alertmanager
Confluent. La stack a déjà **AKHQ** (topics, groupes, ksqlDB) et
**Prometheus + Grafana** (métriques) : le remettre ajouterait quatre conteneurs
et une licence pour une redite. Sont partis avec lui : `CONFLUENT_METRICS_REPORTER_*`,
`CONFLUENT_METRICS_ENABLE`, `KAFKA_CONFLUENT_SUPPORT_METRICS_ENABLE`
(Proactive Support n'existe plus en CP 8).

### Écoute sur `0.0.0.0`

`KAFKA_LISTENERS` pointait sur le hostname (`INTERNAL://kafka-00:29092`). En
écoutant sur `0.0.0.0`, le healthcheck peut interroger `localhost:9092` et les
`advertised.listeners` restent la seule source de vérité pour les clients.
`OUTSIDE` continue d'annoncer `${KAFKA_EXTERNAL_HOST}` (`kafkadev` par défaut).

---

## 2. Bugs corrigés au passage

Indépendants de KRaft, mais présents dans l'ancien fichier :

1. **`KAFKA_REPLIQUA_MAX_BYTES`** n'est pas une propriété Kafka — elle n'a jamais
   rien réglé. C'est `replica.fetch.max.bytes`, et elle doit être ≥
   `message.max.bytes`, faute de quoi un message de 2 Mo bloque la réplication.
2. **Volume Prometheus monté au mauvais endroit** : `prometheus-data-0` était
   monté sur `/opt/bitnami/prometheus/data` (chemin de l'image *Bitnami*) alors
   que `prom/prometheus` écrit dans `/prometheus`, valeur passée par
   `--storage.tsdb.path`. Aucune métrique ne survivait à un redémarrage.
   Le chemin `//etc/prometheus/prometheus.yml` (double slash) est nettoyé.
3. **`grafana-storage` déclaré, monté nulle part** : dashboards, annotations et
   utilisateurs repartaient de zéro à chaque recréation. Monté sur
   `/var/lib/grafana`, avec provisioning de la datasource Prometheus.
4. **Guillemets littéraux dans `GF_PLUGINS_ALLOW_LOADING_UNSIGNED_PLUGINS`** :
   la valeur commençait par `"`, donc le premier plugin s'appelait
   `"jdbranham-diagram-panel` et n'était jamais autorisé.
5. **`GF_FEATURE_TOGGLES_ENABLE`** contenait `metrsSummary` (faute de frappe).
6. **AKHQ : bloc `topic-data` déclaré deux fois**, le second écrasant
   silencieusement le premier (`size: 60` → `50`). Les clés
   `kafka-max-message-length`, `kafka-max-request-size`,
   `kafka-properties-max-request-size` n'existent pas dans le schéma AKHQ : les
   vraies sont `akhq.topic-data.max-message-length` et les propriétés client
   Kafka telles quelles (`max.request.size`, `compression.type`) sous
   `clients-defaults`.
7. **`KSQL_CACHE_MAX_BYTES_BUFFERING`** : la propriété Kafka Streams sous-jacente
   est dépréciée depuis 3.x ; ksqlDB attend
   `ksql.streams.cache.max.bytes.buffering`, d'où `KSQL_KSQL_STREAMS_...`.
8. **`links:`** — hérité de Docker v1, sans effet dès lors que les services
   partagent le réseau `dip` (et `kafka-rest` était lié à `zookeeper`, qu'il
   n'appelait pas). Supprimés.
9. **Blocs `deploy:`** (`replicas`, `placement.constraints`,
   `restart_policy`) sur Prometheus et Grafana : c'est du Docker **Swarm**,
   intégralement ignoré par `docker compose up`. La politique de redémarrage
   qu'ils semblaient décrire est maintenant réelle (`restart: unless-stopped`).
10. **`KAFKA_ALLOW_EVERYONE_IF_NO_ACL_FOUND`** sans `authorizer.class.name` :
    sans authorizer, aucune ACL n'est évaluée, la propriété ne protégeait rien.

Ajouts : `healthcheck` sur chaque service et `depends_on: condition:
service_healthy` (ksqlDB et REST démarraient contre un broker pas encore prêt et
se contentaient de boucler en erreur), versions épinglées via `.env` au lieu de
`:latest`, profils `observability` / `schema-registry` / `cli`.

---

## 3. Données : le volume de l'ancien cluster n'est pas réutilisable

Le compose crée un volume **neuf** (`kafka-00-kraft-data`) et ne touche pas à
`kafka-data-00`. Ce n'est pas de la prudence excessive : un répertoire de
données écrit par un cluster ZooKeeper n'a pas de log de métadonnées KRaft, et
un broker 4.x refuse de démarrer dessus.

* **Environnement de dev** — repartir à neuf, recréer les topics (le
  `auto.create.topics.enable` est resté à `true`) et rejouer les scripts ksqlDB
  de `./ksql`.
* **Cluster à préserver** — la migration ZK → KRaft **doit être faite avant** de
  passer en 4.x, depuis une 3.9 / CP 7.9 (dernière version « pont ») avec
  `zookeeper.metadata.migration.enable`, puis mise à niveau vers 4.2 une fois le
  cluster en KRaft. Kafka 4.x ne sait plus lire un cluster ZooKeeper, il n'y a
  pas de saut direct 7.9 → 8.3.

L'ancien volume peut être supprimé une fois la bascule validée :

```bash
docker volume rm <projet>_kafka-data-00 <projet>_zookeeper-data
```

---

## 4. Vérifications après démarrage

```bash
# Le quorum KRaft (remplace `zookeeper-shell ... ls /brokers/ids`)
docker compose exec kafka-00 kafka-metadata-quorum \
  --bootstrap-server kafka-00:29092 describe --status

# Le broker est enregistré
docker compose exec kafka-00 kafka-broker-api-versions \
  --bootstrap-server kafka-00:29092 | head -1

# Aller-retour producteur / consommateur
docker compose exec kafka-00 kafka-topics --bootstrap-server kafka-00:29092 \
  --create --topic smoke-test --partitions 3
docker compose exec kafka-00 kafka-topics --bootstrap-server kafka-00:29092 --describe --topic smoke-test

# Les métriques du plan de contrôle sont bien exposées
curl -s localhost:9200/metrics | grep -E 'activecontrollercount|raft_metrics_current_state'
```

`kafka_controller_kafkacontroller_activecontrollercount` à 1 et
`raft_metrics_current_state{...} = leader` : le nœud est bien controller actif de
son propre quorum.

---

## 5. Versions épinglées

Relevé sur le registre public le 2026-08-06 (à recouper avec ce que miroite
`nx-repo` ; tout est surchargeable par `.env`).

| Image | Tag | Remarque |
|---|---|---|
| `confluentinc/cp-kafka` | `8.3.0` | dernier 8.x publié — CP 8.3 = Kafka 4.2 |
| `confluentinc/cp-ksqldb-server` | `8.3.0` | |
| `confluentinc/cp-kafka-rest` | `8.3.0` | |
| `confluentinc/cp-schema-registry` | `8.3.0` | |
| `confluentinc/cp-ksqldb-cli` | **`8.0.6`** | voir ci-dessous |
| `tchiotludo/akhq` | `0.27.1` | dernière release, digest identique à `latest` |
| `prom/prometheus` | `v3.13.2` | digest identique à `latest` |
| `grafana/grafana` | `13.1.2` | digest identique à `latest` |

**`cp-ksqldb-cli` s'arrête à 8.0.x.** Confluent ne publie plus cette image
au-delà : `8.1.4`, `8.2.2` et `8.3.0` renvoient 404 sur le registre, alors que
le serveur ksqlDB, lui, est bien en 8.3.0. D'où une variable dédiée
(`KSQLDB_CLI_VERSION`) plutôt que `CONFLUENT_VERSION` — sans quoi le profil
`cli` échouerait au premier `docker compose run`. La CLI dialogue avec le
serveur par son API REST, elle signale l'écart de version au démarrage et
fonctionne.

C'est un signe de plus du statut de ksqlDB chez Confluent (cf. point suivant).

Un mot sur AKHQ : `0.27.1` est bien la dernière version publiée. Les notes de
release ne détaillent pas la version de `kafka-clients` embarquée et le dépôt
upstream est hors du périmètre GitHub de cette session, donc ce point n'est pas
vérifié ici — sans enjeu pratique, un client Kafka ≥ 2.1 suffit à parler à un
broker 4.x, et AKHQ est très au-delà de ce plancher depuis longtemps.

## 6. Points restants à décider

* **ksqlDB** est en mode maintenance chez Confluent (l'investissement va vers
  Flink) — l'image CLI figée en 8.0.x le confirme assez nettement. Il est
  conservé ici à l'identique ; si les requêtes doivent évoluer, Flink SQL est le
  chemin vers lequel regarder.
* **Nœud unique, `replication.factor: 1`** : conforme à un environnement de dev,
  aucune tolérance de panne. Un quorum de 3 controllers demanderait de dupliquer
  le service et d'allonger `KAFKA_CONTROLLER_QUORUM_VOTERS`.
* **Aucune authentification** (PLAINTEXT partout, Grafana en anonyme Admin) —
  identique à l'ancienne stack, à réserver à un réseau fermé.
* **Agent JMX en 0.20.0**, comme avant. La ligne maintenue est la 1.x : déposer
  le jar dans `jmx-exporter/` et changer `JMX_AGENT_JAR` dans `.env` suffit, la
  configuration `kafka-broker.yml` fournie est valide pour les deux.
