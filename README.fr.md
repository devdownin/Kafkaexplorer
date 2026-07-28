<div align="center">

# ⚡ Kafka SQL Explorer

### Voyez votre Kafka. Interrogez-le comme une base de données. Auditez-le avec l'IA.

[![CI](https://github.com/devdownin/Kafkaexplorer/actions/workflows/ci.yml/badge.svg)](https://github.com/devdownin/Kafkaexplorer/actions/workflows/ci.yml)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)
[![Docker](https://img.shields.io/badge/ghcr.io-kafkaexplorer-2496ED?logo=docker&logoColor=white)](https://github.com/devdownin/Kafkaexplorer/pkgs/container/kafkaexplorer)
[![Java 21](https://img.shields.io/badge/Java-21-orange)](pom.xml)
[![Kafka 4.2](https://img.shields.io/badge/Kafka-4.2_KRaft-231F20?logo=apachekafka)](https://kafka.apache.org/)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](CONTRIBUTING.md)

[Site web](https://devdownin.github.io/Kafkaexplorer/) · [Tour des fonctionnalités](docs/FEATURES.md) · [Démarrage rapide](#-démarrage-rapide) · [Contribuer](CONTRIBUTING.md) · [🇬🇧 English](README.md)

</div>

---

**Arrêtez de plisser les yeux devant un console consumer.** Kafka SQL Explorer est une application web qui transforme n'importe quel cluster Kafka en quelque chose que l'on peut *voir et interroger* : parcourez les topics, cliquez sur un champ d'un message, et obtenez une requête Flink SQL prête à exécuter — pas de DDL à écrire, pas de schéma à deviner, pas de gymnastique CLI. Un JAR, une URL, zéro installation côté cluster.

Pensé pour les data engineers, les architectes, et tous ceux qui se sont un jour demandé *« qu'est-ce qui circule vraiment dans ce topic ? »*

## ✨ Points forts

- 🖱️ **Cliquer, c'est requêter** — cliquez sur une clé JSON ou une balise XML dans l'aperçu d'un message et elle atterrit dans votre `SELECT`/`WHERE`, `JSON_VALUE`/XPath générés pour vous.
- 🧠 **Schémas sans configuration** — les topics sont échantillonnés, leur structure inférée (JSON, XML, Avro via Schema Registry) et enregistrée comme table Flink en un clic.
- 📝 **Un vrai éditeur SQL** — Monaco (le moteur de VS Code), auto-complétion des topics et tables, historique de requêtes, lecture earliest/latest.
- 🕸️ **Lignage & traçage** — un graphe interactif topics → tables → jobs actifs, plus le traçage d'un message à travers plusieurs topics par JSONPath/XPath.
- 🩺 **Audit du cluster en un clic** — messages toxiques, doublons, pertes en ligne et latence des flux, calculés sur tout le cluster en tâche de fond.
- 🤖 **Process mining assisté par IA** — reconstruisez vos flux métier en flowcharts et traquez les anomalies avec Claude, un LLM local (Ollama…) ou un [SpectraLLM](https://github.com/devdownin/SpectraLLM) privé.
- 🔭 **Nativement Kafka 4** — quorum de contrôleurs KRaft, groupes KIP-848, share groups (KIP-932) et versions de features, visibles dans l'UI et exportés vers Prometheus.
- 🎁 **Un bac à sable inclus** — plus de 70 topics de démo, du pipeline de commandes en 6 étapes à la supply chain de 60 topics, créés automatiquement.

## 🚀 Démarrage rapide

Une seule commande — Kafka 4.2 (KRaft), l'application et tous les topics de démo :

```bash
docker compose up -d
```

Ouvrez ensuite **http://localhost:8080** et commencez à cliquer. C'est tout.

<details>
<summary>Autres façons de lancer</summary>

- **Avec Confluent Schema Registry** (topics Avro) : `docker compose -f docker-compose-kafka4.yml up -d`
- **Avec un LLM local pré-câblé** (Ollama) : `docker compose -f docker-compose-llm.yml up -d`
- **Depuis les sources** (JDK 21) : lancez Kafka avec `docker compose up -d kafka`, puis `./mvnw spring-boot:run`
- **Image précompilée** : `docker run -p 8080:8080 -e SPRING_KAFKA_BOOTSTRAP_SERVERS=votre-broker:9092 ghcr.io/devdownin/kafkaexplorer:latest`
- **Sur votre propre cluster** : pointez `kafka.bootstrap-servers` vers n'importe quel broker Kafka 2.1+ (PLAIN, SSL ou Confluent Cloud) — rien à installer côté cluster.

</details>

## 🧭 Le tour du propriétaire

| Vous voulez… | Direction… |
|---|---|
| Parcourir topics, partitions, volumes et messages | **Dashboard** & **Topic Explorer** |
| Écrire et exécuter du SQL sur les topics | **SQL Editor** — ou cliquez sur les champs et laissez-le s'écrire tout seul |
| Comparer deux topics côte à côte, diff par ID | **Compare** |
| Suivre un message à travers tout un pipeline | **Stream Flow** |
| Visualiser topics → tables → jobs en cours | **Lineage** |
| Transformer du SQL en métriques Prometheus avec graphiques | **Metrics** |
| Vérifier la santé de tout le cluster en un clic | **Audit** |
| Inspecter brokers, quorum KRaft, groupes clients, feature flags | **Cluster** |
| Laisser un LLM reconstruire et auditer vos flux métier | **Process Mining** |

Chaque fonctionnalité en détail : **[docs/FEATURES.md](docs/FEATURES.md)** · Requêtes prêtes à l'emploi : **[docs/QUERY-EXAMPLES.md](docs/QUERY-EXAMPLES.md)**

## 🤖 Apportez votre IA

Le Process Mining fonctionne avec le LLM que vous avez déjà — **Claude (Anthropic)**, tout ce qui parle l'API OpenAI (**Ollama**, vLLM, LM Studio…), ou un **SpectraLLM** entièrement privé avec RAG, où aucun octet ne quitte votre réseau. Fournisseur, modèle et test de connectivité se configurent en direct depuis l'interface.

→ **[Guide des fournisseurs LLM](docs/LLM-PROVIDERS.md)** *(en anglais)*

## 🛠️ Sous le capot

Un unique JAR Spring Boot 4.1 embarquant Apache Flink 2.3 comme moteur SQL, avec un frontend React 19 + Tailwind. Clients Kafka 4.2 (compatibles brokers 2.1+), Avro via Confluent Schema Registry, métriques Prometheus sur `/actuator/prometheus`. Le SQL est restreint par liste blanche (`SELECT` / `EXPLAIN` / `CREATE TABLE` uniquement), le parsing XML est durci contre les attaques XXE, et les secrets sont masqués dans tout DDL affiché par l'UI.

Plongée dans l'architecture : **[docs/architecture.md](docs/architecture.md)**

## 🏗️ Build et Développement

Il y a plusieurs façons de builder et de travailler sur le projet, selon vos besoins.

### 1. Build de production Docker (Recommandé)
Le projet utilise un build Docker "multi-stage" optimisé qui sépare le front et le back pour une meilleure mise en cache, puis package le tout dans un JRE ultra-léger :
```bash
docker build -t kafka-sql-explorer:latest .
```

### 2. Environnement de Développement (Hot-Reload)
Pour développer avec rechargement à chaud (Hot Module Replacement pour le frontend via Vite et rechargement de classe pour le backend via Spring Boot DevTools) :
```bash
docker-compose -f docker-compose-dev.yml up --build
```
- Le **frontend** sera accessible sur `http://localhost:5173`
- Le **backend** API sera sur `http://localhost:8080` (proxyfié automatiquement par le front)

### 3. Build standard (localement)
Si vous souhaitez compiler l'intégralité du projet localement (sans Docker pour la compilation), Maven s'occupera de tout via un profil activé par défaut (téléchargement de Node, build du React, et packaging Spring Boot) :
```bash
./mvnw clean package
```
## 🏗️ Build et Développement

Il y a plusieurs façons de builder et de travailler sur le projet, selon vos besoins.

### 1. Build de production Docker (Recommandé)
Le projet utilise un build Docker "multi-stage" optimisé qui sépare le front et le back pour une meilleure mise en cache, puis package le tout dans un JRE ultra-léger :
```bash
docker build -t kafka-sql-explorer:latest .
```

### 2. Environnement de Développement (Hot-Reload)
Pour développer avec rechargement à chaud (Hot Module Replacement pour le frontend via Vite et rechargement de classe pour le backend via Spring Boot DevTools) :
```bash
docker-compose -f docker-compose-dev.yml up --build
```
- Le **frontend** sera accessible sur `http://localhost:5173`
- Le **backend** API sera sur `http://localhost:8080` (proxyfié automatiquement par le front)

### 3. Build standard (localement)
Si vous souhaitez compiler l'intégralité du projet localement (sans Docker pour la compilation), Maven s'occupera de tout via un profil activé par défaut (téléchargement de Node, build du React, et packaging Spring Boot) :
```bash
./mvnw clean package
```
## 🤝 Contribuer

Les contributions sont bienvenues — le code est volontairement très commenté pour servir aussi de ressource d'apprentissage sur l'intégration Flink SQL + Spring Boot, et `mvn test` / `npm test` couvrent les services cœur.

- Lisez le **[guide de contribution](CONTRIBUTING.md)** pour démarrer
- Restez bienveillants : **[Code de conduite](CODE_OF_CONDUCT.md)**
- Une faille de sécurité ? Suivez la **[politique de sécurité](SECURITY.md)**

## 📄 Licence

[AGPL v3](LICENSE) — libre d'utiliser, d'étudier, de partager et d'améliorer.

---

<div align="center">
<sub>© 2026 Kafka SQL Explorer — Compagnons du dev. Si ce projet vous épargne une après-midi de debug, une ⭐ nous fait toujours plaisir.</sub>
</div>
