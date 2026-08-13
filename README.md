<div align="center">

# ⚡ Kafka SQL Explorer

### See your Kafka. Query it like a database. Audit it with AI.

[![CI](https://github.com/devdownin/Kafkaexplorer/actions/workflows/ci.yml/badge.svg)](https://github.com/devdownin/Kafkaexplorer/actions/workflows/ci.yml)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)
[![Docker Hub](https://img.shields.io/docker/pulls/compagnonsdudev/kafkaexplorer?logo=docker&logoColor=white&label=docker%20pulls)](https://hub.docker.com/r/compagnonsdudev/kafkaexplorer)
[![GHCR](https://img.shields.io/badge/ghcr.io-kafkaexplorer-2496ED?logo=github&logoColor=white)](https://github.com/devdownin/Kafkaexplorer/pkgs/container/kafkaexplorer)
[![Java 21](https://img.shields.io/badge/Java-21-orange)](pom.xml)
[![Kafka 4.3](https://img.shields.io/badge/Kafka-4.3_KRaft-231F20?logo=apachekafka)](https://kafka.apache.org/)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](CONTRIBUTING.md)

[Website](https://devdownin.github.io/Kafkaexplorer/) · [Feature Tour](docs/FEATURES.md) · [Quick Start](#-quick-start) · [Contributing](CONTRIBUTING.md) · [🇫🇷 Français](README.fr.md)

</div>

---

**Stop squinting at console consumers.** Kafka SQL Explorer is a web app that turns any Kafka cluster into something you can *see and query*: browse topics, click on a message field, and get a runnable Flink SQL query — no DDL to write, no schema to guess, no CLI gymnastics. One JAR, one URL, zero cluster-side installation.

Built for data engineers, architects and anyone who has ever asked *"what's actually flowing through this topic?"*

![The dashboard: every topic, its message count, its state and when it last received something](docs/img/dashboard.png)

<details>
<summary>More screens — Topic Explorer, SQL Editor, Stream Flow, Audit, Cluster</summary>

**Topic Explorer** — search the whole topic and see what was actually covered.
![Topic Explorer](docs/img/topic-explorer.png)

**SQL Editor** — Monaco, scoped completion, and the engine that answered stated on the result.
![SQL Editor](docs/img/sql-editor.png)

**Stream Flow** — one record key across the cluster, with per-hop latency and a checkable evidence table.
![Stream Flow](docs/img/stream-flow.png)

**Cluster Audit** — graded findings, and every run states its own scope.
![Cluster Audit](docs/img/audit.png)

**Cluster** — KRaft controller quorum, client groups, feature versions.
![Cluster](docs/img/cluster.png)

These are generated, not photographed: `docs/screenshots/` drives the compiled SPA over canned API responses shaped like the demo dataset. See its [README](docs/screenshots/README.md) to regenerate them after a UI change.

</details>

## ✨ Highlights

- 🖱️ **Click-to-query** — click a JSON key or XML tag in a message preview and it lands in your `SELECT`/`WHERE`, with `JSON_VALUE`/XPath generated for you.
- 🧠 **Zero-config schemas** — topics are sampled, their structure inferred (JSON, XML, Avro via Schema Registry), and registered as Flink tables in one click.
- 📝 **A real SQL editor** — Monaco (the VS Code engine), auto-completion of topics and tables, query history, earliest/latest read modes.
- 🕸️ **Lineage & tracing** — an interactive graph of topics → tables → live jobs, resolved by Flink's own parser; plus cross-topic message tracing by key, header, JSONPath or XPath, which streams its hops as it finds them, says exactly what it scanned, resumes where a time budget stopped it, and compares two keys side by side.
- 🩺 **One-click cluster audit** — poison messages, duplicates, flow drop-offs and latency, computed across your whole cluster in the background.
- 🤖 **AI-powered process mining** — reconstruct business flows as flowcharts and hunt anomalies with Claude, any local LLM (Ollama…), or a private [SpectraLLM](https://github.com/devdownin/SpectraLLM).
- 🔭 **Kafka 4 native** — KRaft controller quorum, KIP-848 consumer groups, share groups (KIP-932) and feature versions, visible in the UI and exported to Prometheus.
- 🎁 **A batteries-included sandbox** — 76 demo topics seeded automatically, from a 6-step order pipeline to a 60-topic supply chain, all keyed and header-stamped: an order to trace across partitions, a header-only correlation to follow, a real time series to window, duplicates and poison records for the audit to find.

## 🚀 Quick Start

One command — Kafka 4.3 (KRaft), the app, and all demo topics:

```bash
docker compose up -d
```

Then open **http://localhost:8080** and start clicking. That's it.

<details>
<summary>Other ways to run it</summary>

- **With Confluent Schema Registry** (Avro topics): `docker compose -f docker-compose-kafka4.yml up -d`
- **With a local LLM pre-wired** (Ollama): `docker compose -f docker-compose-llm.yml up -d`
- **From source** (JDK 21): start Kafka with `docker compose up -d kafka`, then `./mvnw spring-boot:run`
- **Build with nothing installed but Docker** — no JDK, no Maven, no Node:
  ```bash
  docker compose -f docker-compose-build.yml run --rm verify    # the full CI gate
  docker compose -f docker-compose-build.yml run --rm package   # JAR into ./target
  docker compose -f docker-compose-build.yml run --rm frontend  # ESLint + Vitest only
  ```
- **Hot-reload dev stack** (backend + Vite + Kafka, still nothing installed locally): `docker compose -f docker-compose-dev.yml up`
- **Prebuilt image** (Docker Hub or GHCR, same image, `linux/amd64` + `linux/arm64`):
  ```bash
  docker run -p 127.0.0.1:8080:8080 -e KAFKA_BOOTSTRAP_SERVERS=your-broker:9092 compagnonsdudev/kafkaexplorer:latest
  # or: ghcr.io/devdownin/kafkaexplorer:latest
  ```
  Tags, environment variables, volumes and probes: **[docs/DOCKERHUB.md](docs/DOCKERHUB.md)** — the page published as the [Docker Hub overview](https://hub.docker.com/r/compagnonsdudev/kafkaexplorer).
- **Against your own cluster**: point `kafka.bootstrap-servers` at any Kafka 2.1+ broker (PLAIN, SSL or Confluent Cloud) — nothing to install cluster-side.

</details>

## 🧭 Take the tour

| You want to… | Head to… |
|---|---|
| Browse topics, partitions, sizes and sample messages | **Dashboard** & **Topic Explorer** |
| Write and run SQL against topics | **SQL Editor** — or just click fields and let it write itself |
| Compare two topics side by side, diff by ID | **Compare** |
| Follow one message across a whole pipeline | **Stream Flow** |
| Visualize topics → tables → running jobs | **Lineage** |
| Turn SQL into Prometheus metrics with live charts | **Metrics** |
| Health-check the entire cluster in one click | **Audit** |
| Inspect brokers, KRaft quorum, client groups, feature flags | **Cluster** |
| Let an LLM reconstruct and audit your business flows | **Process Mining** |

Every feature in detail: **[docs/FEATURES.md](docs/FEATURES.md)** · Ready-to-run SQL: **[docs/QUERY-EXAMPLES.md](docs/QUERY-EXAMPLES.md)**

## 🤖 Bring your own AI

Process Mining works with the LLM you already have — **Anthropic Claude**, anything speaking the OpenAI API (**Ollama**, vLLM, LM Studio…), or a fully private, RAG-enabled **SpectraLLM** where no byte leaves your network. Provider, model and connectivity test are all configurable live from the UI.

→ **[LLM provider guide](docs/LLM-PROVIDERS.md)**

## 🛠️ Under the hood

A single Spring Boot 4.1 JAR embedding Apache Flink 2.3 as the SQL engine, with a React 19 + Tailwind frontend. Kafka clients 4.3 (compatible with brokers 2.1+), Avro via Confluent Schema Registry, Prometheus metrics on `/actuator/prometheus`. SQL is whitelisted (`SELECT` / `EXPLAIN` / `CREATE TABLE` only), XML parsing is XXE-hardened, and credentials are redacted from any DDL shown in the UI.

Architecture deep-dive: **[docs/architecture.md](docs/architecture.md)**

## 🏗️ Build and Development

There are several ways to build and work on the project depending on your needs.

### 1. Docker Production Build (Recommended)
The project uses an optimized multi-stage Docker build that separates frontend and backend compilation for better caching, then packages everything into a lightweight JRE image:
```bash
docker build -t kafka-sql-explorer:latest .
```

### 2. Development Environment (Hot-Reload)
To develop with live-reloading (Hot Module Replacement for the Vite frontend and class reloading for the Spring Boot backend):
```bash
docker-compose -f docker-compose-dev.yml up --build
```
- The **frontend** is available at `http://localhost:5173`
- The **backend** API runs on `http://localhost:8080` (automatically proxied by the frontend)

### 3. Standard Local Build
If you prefer to compile the entire project locally without Docker, Maven handles everything via a default-activated profile (downloading Node.js, building the React app, and packaging the Spring Boot executable):
```bash
./mvnw clean package
```
## 🏗️ Build and Development

There are several ways to build and work on the project depending on your needs.

### 1. Docker Production Build (Recommended)
The project uses an optimized multi-stage Docker build that separates frontend and backend compilation for better caching, then packages everything into a lightweight JRE image:
```bash
docker build -t kafka-sql-explorer:latest .
```

### 2. Development Environment (Hot-Reload)
To develop with live-reloading (Hot Module Replacement for the Vite frontend and class reloading for the Spring Boot backend):
```bash
docker-compose -f docker-compose-dev.yml up --build
```
- The **frontend** is available at `http://localhost:5173`
- The **backend** API runs on `http://localhost:8080` (automatically proxied by the frontend)

### 3. Standard Local Build
If you prefer to compile the entire project locally without Docker, Maven handles everything via a default-activated profile (downloading Node.js, building the React app, and packaging the Spring Boot executable):
```bash
./mvnw clean package
```
## 🤝 Contributing

Contributions are welcome — the codebase is deliberately heavily commented to double as a learning resource for Flink SQL + Spring Boot integration, and `mvn test` / `npm test` cover the core services.

- Read the **[Contributing Guide](CONTRIBUTING.md)** to get started
- Be excellent to each other: **[Code of Conduct](CODE_OF_CONDUCT.md)**
- Found a vulnerability? Follow the **[Security Policy](SECURITY.md)**

## 📄 License

[AGPL v3](LICENSE) — free to use, study, share and improve.

---

<div align="center">
<sub>© 2026 Kafka SQL Explorer — Compagnons du dev. If this project saves you a debugging afternoon, a ⭐ makes our day.</sub>
</div>
