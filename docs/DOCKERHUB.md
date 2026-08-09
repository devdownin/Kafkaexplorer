<!--
  This file IS the Docker Hub overview page of `devdownin/kafkaexplorer`.

  It is pushed there by .github/workflows/dockerhub-description.yml — editing the page in
  the Docker Hub web UI is therefore pointless, the next sync overwrites it.

  Two constraints that do not apply to README.md:
    - Docker Hub renders this file outside the repository, so every link must be absolute.
      A relative `docs/FEATURES.md` resolves to nothing there.
    - No image in the repository is reachable either; only external URLs (shields.io) are.
-->

# ⚡ Kafka SQL Explorer

### See your Kafka. Query it like a database. Audit it with AI.

[![Docker Pulls](https://img.shields.io/docker/pulls/devdownin/kafkaexplorer?logo=docker&logoColor=white)](https://hub.docker.com/r/devdownin/kafkaexplorer)
[![Image Size](https://img.shields.io/docker/image-size/devdownin/kafkaexplorer/latest?logo=docker&logoColor=white)](https://hub.docker.com/r/devdownin/kafkaexplorer/tags)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](https://github.com/devdownin/Kafkaexplorer/blob/main/LICENSE)
[![Source](https://img.shields.io/badge/source-GitHub-181717?logo=github&logoColor=white)](https://github.com/devdownin/Kafkaexplorer)

**Stop squinting at console consumers.** Kafka SQL Explorer turns any Kafka cluster into
something you can *see and query*: browse topics, click on a message field, and get a
runnable Flink SQL query — no DDL to write, no schema to guess, no CLI gymnastics.

One container, one URL, **zero cluster-side installation**: it connects as an ordinary
Kafka client, so there is nothing to deploy on your brokers.

---

## 🚀 Try it in one command

Against a broker you already have:

```bash
docker run --rm -p 8080:8080 \
  -e KAFKA_BOOTSTRAP_SERVERS=your-broker:9092 \
  devdownin/kafkaexplorer:latest
```

Open **http://localhost:8080**.

No broker at hand? The snippet below starts Kafka 4.2 (KRaft, no Zookeeper) next to it:

```yaml
# docker-compose.yml — throwaway sandbox, broker data is not persisted.
services:
  kafka:
    image: apache/kafka:4.2.0
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_LISTENERS: PLAINTEXT://:29092,CONTROLLER://:9093,PLAINTEXT_HOST://:9092
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:29092,PLAINTEXT_HOST://localhost:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_SHARE_COORDINATOR_STATE_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_SHARE_COORDINATOR_STATE_TOPIC_MIN_ISR: 1
      CLUSTER_ID: MkU2OhlMTT69sPFvS1n16g

  explorer:
    image: devdownin/kafkaexplorer:latest
    # No authentication in the box — see "Before you expose it" below.
    ports:
      - "127.0.0.1:8080:8080"
    environment:
      KAFKA_BOOTSTRAP_SERVERS: kafka:29092
      KAFKA_CONSUMER_GROUP_PROTOCOL: consumer   # KIP-848, Kafka 4.x brokers only
    volumes:
      - explorer_logs:/app/logs
      - explorer_data:/app/data
    # Graceful web shutdown (15s) + bean destruction (10s) + JVM exit. Docker's default
    # of 10s SIGKILLs exactly what those budgets exist to protect.
    stop_grace_period: 35s
    depends_on: [kafka]

volumes:
  explorer_logs:
  explorer_data:
```

The [repository's own stacks](https://github.com/devdownin/Kafkaexplorer#-quick-start) go
further: `docker compose up -d` there also seeds **76 demo topics** — a 6-step order
pipeline to trace across partitions, header-only correlations, a real time series to
window, plus duplicates and poison records for the audit to find.

## ✨ What you get

- 🖱️ **Click-to-query** — click a JSON key or XML tag in a message preview and it lands in your `SELECT`/`WHERE`, with `JSON_VALUE`/XPath generated for you.
- 🧠 **Zero-config schemas** — topics are sampled, their structure inferred (JSON, XML, Avro via Schema Registry), and registered as Flink tables in one click.
- 📝 **A real SQL editor** — Monaco (the VS Code engine), auto-completion scoped to the tables your query actually cites, query history, earliest/latest read modes, windowing assistant.
- 🔎 **Search that says what it scanned** — text, regex, field path, JSONPath, XPath, record key or Kafka header, over the whole topic, with hits, records scanned, why the pass stopped, and a cursor to continue. A search here is never silently partial.
- 🕸️ **Lineage & tracing** — an interactive graph of topics → tables → live jobs, resolved by Flink's own parser; plus cross-topic message tracing by key, header, JSONPath or XPath, streaming its hops as it finds them and comparing two keys side by side.
- 🩺 **One-click cluster audit** — poison messages, duplicates, flow drop-offs and latency, graded by severity, computed across your whole cluster in the background and diffable against the previous run.
- 📉 **Consumer lag that grades itself** — who reads a topic and how far behind, with `stalled` (nothing assigned), `partial` (never committed on some partitions) and `ahead` called out rather than folded into one number.
- 🤖 **AI process mining** — reconstruct business flows as flowcharts and hunt anomalies with Claude, any local LLM (Ollama, vLLM, LM Studio…), or a fully private [SpectraLLM](https://github.com/devdownin/SpectraLLM). Nothing leaves your network unless you point it outside.
- 🔭 **Kafka 4 native** — KRaft controller quorum, KIP-848 consumer groups, share groups (KIP-932) and feature versions, in the UI and on `/actuator/prometheus`.

Full feature tour: **[docs/FEATURES.md](https://github.com/devdownin/Kafkaexplorer/blob/main/docs/FEATURES.md)**

## 🏷️ Tags

| Tag | What it is |
|---|---|
| `latest` | The newest **stable** release. Pre-releases (`v1.3.0-rc1`…) never move it. |
| `1.2.3` | An exact version. Use this in production — it is immutable. |
| `1.2` | The latest patch of that minor line. |

Architectures: **`linux/amd64`** and **`linux/arm64`** (Apple Silicon, Graviton — natively,
not under emulation).

The same image is also published on GHCR as
[`ghcr.io/devdownin/kafkaexplorer`](https://github.com/devdownin/Kafkaexplorer/pkgs/container/kafkaexplorer).

## ⚙️ Configuration

Everything is a Spring property, so **any** setting in
[`application.yml`](https://github.com/devdownin/Kafkaexplorer/blob/main/src/main/resources/application.yml)
can be overridden by an environment variable: uppercase it and turn `.` and `-` into `_`
(`explorer.search-max-scan` → `EXPLORER_SEARCH_MAX_SCAN`). The ones that matter:

### Kafka connection

| Variable | Default | Meaning |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Your brokers. Kafka **2.1+** on the classic protocol. |
| `KAFKA_MODE` | `PLAIN` | `PLAIN`, `SSL` or `CONFLUENT_CLOUD`. |
| `KAFKA_SCHEMA_REGISTRY_URL` | `http://localhost:8081` | Confluent Schema Registry, for Avro topics. |
| `KAFKA_CONSUMER_GROUP_PROTOCOL` | `classic` | `consumer` opts the live consumer into KIP-848 (Kafka 4.x brokers). |
| `KAFKA_TRUSTSTORE_PATH` / `_PASSWORD` | — | `SSL` mode. Mount the store into the container. |
| `KAFKA_KEYSTORE_PATH` / `_PASSWORD`, `KAFKA_KEY_PASSWORD` | — | `SSL` mode, mutual TLS. |
| `KAFKA_CONFLUENT_KEY` / `KAFKA_CONFLUENT_SECRET` | — | `CONFLUENT_CLOUD` mode. |

All of it is also settable live from the **Settings** page — which is precisely why the app
must not be exposed to an untrusted network (see below).

### LLM (Process Mining — entirely optional)

| Variable | Default | Meaning |
|---|---|---|
| `CLAUDE_PROVIDER` | `OLLAMA` | `ANTHROPIC`, `OPENAI_COMPATIBLE`, `OLLAMA` or `SPECTRA`. |
| `CLAUDE_BASE_URL` | `http://localhost:11434/v1` | Endpoint of the local/compatible provider. |
| `CLAUDE_MODEL` | `qwen3:4b` | Model name at that endpoint. |
| `ANTHROPIC_API_KEY` | — | `ANTHROPIC` provider only. |

Leave it alone and every other feature works — Process Mining is the only page that calls
a model.

### Runtime

| Variable | Default | Meaning |
|---|---|---|
| `JAVA_TOOL_OPTIONS` | `-XX:MaxRAMPercentage=75.0` | Set a container **memory limit** or this reads the *host's* memory: on a 32 GB machine the JVM believes it may take 24 GB. |
| `EXPLORER_DEFAULT_MAX_ROWS` | `50` | Rows a query returns by default. |
| `EXPLORER_DEFAULT_QUERY_TIMEOUT_MS` | `10000` | Per-query wall clock. |
| `EXPLORER_AUDIT_MAX_DURATION_MS` | `1800000` | Budget for one full cluster audit; `0` disables it. |
| `EXPLORER_SEARCH_MAX_SCAN` | `20000` | Records one topic-search pass may read. |
| `EXPLORER_STREAM_FLOW_MAX_TOPICS` | `250` | Topics a whole-cluster trace reads (the most recently active ones). |

### Ports, volumes, probes

| | |
|---|---|
| **Port** | `8080` — UI and REST API, one server. |
| **Volume** `/app/logs` | `kafkaexplorer.log`. Mount a **named volume**, not a host file: Docker would create a directory in its place and Logback could not open its log at all. |
| **Volume** `/app/data` | Flink job history (`flink-jobs.json`) — lost on every container replacement without it. |
| **Liveness** | `GET /actuator/health/liveness` — what the built-in `HEALTHCHECK` polls. |
| **Readiness** | `GET /actuator/health/readiness` — liveness **plus** a reachable broker. An unreachable broker means "cannot answer queries", not "restart me": the UI still serves and can be repointed. |
| **Metrics** | `GET /actuator/prometheus` — JVM, HTTP, KRaft quorum lag, and any SQL query you turn into a metric from the UI. |

## 🔒 Before you expose it

**This image ships no authentication.** It is built for an internal, controlled network,
and `POST /api/config` can repoint the Kafka cluster at runtime — so anyone who reaches
the port can point it at another broker of theirs.

- Publish on the loopback (`-p 127.0.0.1:8080:8080`) or behind an authenticating reverse proxy. `-p 8080:8080` binds `0.0.0.0` and hands the app to your whole LAN.
- SQL is whitelisted to `SELECT` / `EXPLAIN` / `CREATE TABLE`; XML parsing is XXE-hardened; credentials are redacted from every DDL the UI displays.
- The container runs as **uid 10001**, non-root, on an `eclipse-temurin:21-jre-alpine` base pinned by digest.

Vulnerability reports: **[SECURITY.md](https://github.com/devdownin/Kafkaexplorer/blob/main/SECURITY.md)**.

## 📦 What is inside

A single Spring Boot 4.1 JAR embedding **Apache Flink 2.3** as the SQL engine, with a
React 19 + Tailwind frontend served from the same port. Kafka clients 4.2.

The JAR is unpacked into Spring Boot's four standard layers (dependencies →
spring-boot-loader → snapshot-dependencies → application), so a patch release re-pushes
the small application layer instead of a few hundred megabytes that are ~95 % identical to
the previous version's.

It is also **the exact JAR attached to the [GitHub Release](https://github.com/devdownin/Kafkaexplorer/releases)** —
built and tested once by CI, then copied in, never recompiled unverified inside the image.

## 📚 Links

- **Source & issues** — https://github.com/devdownin/Kafkaexplorer
- **Website** — https://devdownin.github.io/Kafkaexplorer/
- **Feature tour** — [docs/FEATURES.md](https://github.com/devdownin/Kafkaexplorer/blob/main/docs/FEATURES.md)
- **Ready-to-run SQL** — [docs/QUERY-EXAMPLES.md](https://github.com/devdownin/Kafkaexplorer/blob/main/docs/QUERY-EXAMPLES.md)
- **LLM providers** — [docs/LLM-PROVIDERS.md](https://github.com/devdownin/Kafkaexplorer/blob/main/docs/LLM-PROVIDERS.md)
- **Architecture** — [docs/architecture.md](https://github.com/devdownin/Kafkaexplorer/blob/main/docs/architecture.md)
- **🇫🇷 Français** — [README.fr.md](https://github.com/devdownin/Kafkaexplorer/blob/main/README.fr.md)

## 📄 License

[AGPL v3](https://github.com/devdownin/Kafkaexplorer/blob/main/LICENSE) — free to use,
study, share and improve.
