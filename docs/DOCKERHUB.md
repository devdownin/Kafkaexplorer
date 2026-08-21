<!-- This file IS the Docker Hub overview page; the web UI is overwritten by the next sync.
     Editing it: every link and image must be ABSOLUTE (it renders outside the repository),
     and docs/check-links.py enforces that. Full notes at the bottom of this file. -->

# ⚡ Kafka SQL Explorer

### See your Kafka. Query it like a database. Audit it with AI.

[![Docker Pulls](https://img.shields.io/docker/pulls/compagnonsdudev/kafkaexplorer?logo=docker&logoColor=white)](https://hub.docker.com/r/compagnonsdudev/kafkaexplorer)
[![Image Size](https://img.shields.io/docker/image-size/compagnonsdudev/kafkaexplorer/latest?logo=docker&logoColor=white)](https://hub.docker.com/r/compagnonsdudev/kafkaexplorer/tags)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](https://github.com/devdownin/Kafkaexplorer/blob/main/LICENSE)
[![Source](https://img.shields.io/badge/source-GitHub-181717?logo=github&logoColor=white)](https://github.com/devdownin/Kafkaexplorer)

**Stop squinting at console consumers.** Kafka SQL Explorer turns any Kafka cluster into
something you can *see and query*: browse topics, click on a message field, and get a
runnable Flink SQL query — no DDL to write, no schema to guess, no CLI gymnastics.

One container, one URL, **zero cluster-side installation**: it connects as an ordinary
Kafka client, so there is nothing to deploy on your brokers.

![The dashboard: every topic, its message count, its state and when it last received something](https://devdownin.github.io/Kafkaexplorer/img/dashboard.png)

---

## 🚀 Try it in one command

Against a broker you already have:

```bash
docker run --rm -p 127.0.0.1:8080:8080 \
  -e KAFKA_BOOTSTRAP_SERVERS=your-broker:9092 \
  compagnonsdudev/kafkaexplorer:latest
```

Open **http://localhost:8080**.

The port is published on the loopback interface deliberately — this image ships no
authentication. See **Before you expose it** below.

No broker at hand? The snippet below starts Kafka 4.3 (KRaft, no Zookeeper) next to it:

```yaml
# docker-compose.yml — throwaway sandbox, broker data is not persisted.
services:
  kafka:
    image: apache/kafka:4.3.1
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
    image: compagnonsdudev/kafkaexplorer:latest
    # No authentication in the box — see "Before you expose it" below.
    ports:
      - "127.0.0.1:8080:8080"
    # Not optional. The JVM runs with -XX:MaxRAMPercentage=75, which without a limit
    # reads the *host's* memory: on a 32 GB machine it believes it may take 24.
    mem_limit: 2g
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
- 🗺️ **A data model you did not have to draw** — read a set of topics as tables, with the relations between them deduced from key-column names. Kafka has no foreign keys, so every edge is graded, states its evidence, and opens as a ready `JOIN` — one relation or a whole subgraph.
- 🩺 **One-click cluster audit** — poison messages, duplicates, flow drop-offs and latency, graded by severity, computed across your whole cluster in the background and diffable against the previous run.
- 📉 **Consumer lag that grades itself** — who reads a topic and how far behind, with `stalled` (nothing assigned), `partial` (never committed on some partitions) and `ahead` called out rather than folded into one number.
- ⏱️ **Backlog in time, not just in records** — the same 4 000 messages are four seconds of traffic on one topic and four days on another. Ask any group how long its oldest unread message has been waiting, from the topic page or as a scheduled metric; a partition that could not be read says so instead of reporting zero.
- 💡 **KPIs proposed from what your cluster was observed doing** — the Metrics page derives them from your audit, your traces, your running Flink jobs and your Process Mining mapping. Every card names the measurement it rests on and where its thresholds come from; nothing is created until you preview and save it.
- 🤖 **AI process mining** — reconstruct business flows as flowcharts and hunt anomalies with Claude, any local LLM (Ollama, vLLM, LM Studio…), or a fully private [SpectraLLM](https://github.com/devdownin/SpectraLLM). Nothing leaves your network unless you point it outside.
- 🔭 **Kafka 4 native** — KRaft controller quorum, KIP-848 consumer groups, share groups (KIP-932) and feature versions, in the UI and on `/actuator/prometheus`.

Full feature tour: **[docs/FEATURES.md](https://github.com/devdownin/Kafkaexplorer/blob/main/docs/FEATURES.md)**

## 🖼️ A look around

**Topic Explorer** — search the whole topic (text, regex, field path, JSONPath, XPath, record
key or Kafka header), see the matches highlighted, and read exactly what was covered: how many
records were scanned, why the pass stopped, and whether it can be continued.

![Topic Explorer: a text search over demo.orders.5.shipped, two matches highlighted, with the coverage strip stating 4,318 records scanned](https://devdownin.github.io/Kafkaexplorer/img/topic-explorer.png)

**SQL Editor** — Monaco, with the topics and Flink tables in the sidebar, completion scoped to
the tables the query actually cites, and the engine that answered stated on the result
(`FLINK` here, `KAFKA_DIRECT` when the planner falls back).

![SQL Editor: a SELECT over demo_orders_5_shipped, ten rows returned in 11 ms by the Flink engine](https://devdownin.github.io/Kafkaexplorer/img/sql-editor.png)

**Stream Flow** — follow one record key across the cluster. The chain is drawn from first
sightings, each hop carries its latency from the previous one, the slowest is called out, and
the evidence table underneath gives partition, offset and payload for every hop, so the graph
can be checked rather than believed.

![Stream Flow: key ORD-1042 traced across six topics, with per-hop latencies and the slowest hop into demo.orders.5.shipped highlighted](https://devdownin.github.io/Kafkaexplorer/img/stream-flow.png)

**Data Model** — pick a set of topics and read them as tables: each becomes a card carrying its
inferred columns, and the relations between them are deduced from key-column names. Kafka has no
foreign keys, so every edge is a claim rather than a fact — it is graded, drawn in a line style
that says which grade it is, and states in plain words the evidence it rests on. The key column is
detected, never invented: an entity with no id-like field simply has no key. A relation, or a whole
subgraph, opens as a ready `JOIN` in the SQL editor — and is refused rather than given an invented
predicate when the deduced relations do not connect it. Exports as SVG, PNG or a Mermaid
`erDiagram`, each carrying the coverage line and what is *not* drawn.

![Data Model: four topics read as tables — customers, orders, payments and shipments — with three deduced relations drawn in crow's-foot notation between their key columns](https://devdownin.github.io/Kafkaexplorer/img/data-model.png)

**Cluster Audit** — one click, whole cluster: message formats, poison payloads, duplicate
keys, flow drop-off and latency, graded `HEALTHY` / `WARNING` / `CRITICAL`. Every run states
its own scope, because a check that quietly sampled ten messages must not read like a verdict
on a million.

![Cluster Audit: 28 topics, 2 critical and 3 warning, health score 89%, with the per-topic table and its findings](https://devdownin.github.io/Kafkaexplorer/img/audit.png)

**Metrics** — turn a query into a Prometheus series, and let the page propose the KPIs your
own cluster calls for. Each proposal carries the audit measurement behind it and states that
its thresholds are a multiple of that measurement, not a round number someone liked.

![Metrics: two configured metrics above the KPIs suggested for this cluster, each card carrying the audit measurement it rests on and the multiple its thresholds come from](https://devdownin.github.io/Kafkaexplorer/img/metrics.png)

Also there and not pictured here: **Cluster**
([screenshot](https://devdownin.github.io/Kafkaexplorer/img/cluster.png)) with the KRaft
controller quorum and client groups, **Lineage**, **Compare** and **Process Mining**.

## 🏷️ Tags

| Tag | What it is |
|---|---|
| `latest` | The newest **stable** release. Pre-releases (`v1.3.0-rc1`…) never move it. |
| `1.2.3` | An exact version. Nothing here ever re-pushes one. |
| `1.2` | The latest patch of that minor line — it moves. |

Architectures: **`linux/amd64`** and **`linux/arm64`** (Apple Silicon, Graviton — natively,
not under emulation). CI builds *and boots* both before a version is cut.

**In production, pin the digest, not the tag.** "We never re-push `1.2.3`" is a promise;
`@sha256:…` is a property. Every [release](https://github.com/devdownin/Kafkaexplorer/releases)
publishes its digest with the pull command:

```bash
docker pull compagnonsdudev/kafkaexplorer@sha256:<digest-from-the-release-notes>
```

The same image, same digest, is also published on GHCR:

```bash
docker pull ghcr.io/devdownin/kafkaexplorer:latest
```

Images carry a full SLSA provenance attestation and an SBOM
(`docker buildx imagetools inspect --format '{{json .Provenance}}' …`). If Docker Hub's
tag listing shows a third, `unknown/unknown` platform next to the two above, that is those
attestations — an extra manifest in the index, not a broken build.

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

What is entered there is **kept across restarts**, in `/app/data/settings.json` (mount that
volume, or it goes with the container). A variable set here still wins over what was saved:
that ordering is what stops a file written weeks ago from silently overriding the
`KAFKA_BOOTSTRAP_SERVERS` you just changed, and it is the way back out of a saved address
pointing at a cluster that no longer answers. The boot log names any setting that happened to.
Credentials are written to that file too, readable by the container's user alone — set
`EXPLORER_SETTINGS_STORE_SECRETS=false` to keep them out of it, and they will have to be
re-entered after each restart.

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

**Give the container ~2 GB and a real limit** (`mem_limit: 2g`, `--memory=2g`, or a
Kubernetes memory limit). The image embeds a Flink runtime, and the JVM sizes its heap
from the memory it can *see*: with no limit set that is the host's, so on a 32 GB machine
it believes it may take 24 GB. 2 GB is what the project's own
[limits overlay](https://github.com/devdownin/Kafkaexplorer/blob/main/docker-compose.limits.yml)
allocates; a cluster audit over thousands of topics is the workload that wants more.

| Variable | Default | Meaning |
|---|---|---|
| `JAVA_TOOL_OPTIONS` | `-XX:MaxRAMPercentage=75.0` | Replaced wholesale if you set it — re-state the percentage alongside whatever you add. |
| `EXPLORER_CLUSTER_NAME` | `Kafka cluster` | Label for this environment in the header — `Staging`, `Orders prod`. A display name only; the bootstrap address the app is really using is shown beside it on hover. |
| `EXPLORER_DEFAULT_MAX_ROWS` | `50` | Rows a query returns by default. |
| `EXPLORER_DEFAULT_QUERY_TIMEOUT_MS` | `10000` | Per-query wall clock. |
| `EXPLORER_AUDIT_MAX_DURATION_MS` | `1800000` | Budget for one full cluster audit; `0` disables it. |
| `EXPLORER_SEARCH_MAX_SCAN` | `20000` | Records one topic-search pass may read. |
| `EXPLORER_ACTIVITY_MAX_TOPICS` | `100` | Topics one activity read may measure — the sparkline column of the dashboard asks only for the rows it displays. |
| `EXPLORER_ACTIVITY_MAX_LOOKUPS` | `20000` | Ceiling on partitions × bucket boundaries for that read, which is its real unit of work. No record is read: the series comes from offsets alone. Topics past the budget are named in the response rather than silently absent. |
| `EXPLORER_STREAM_FLOW_MAX_TOPICS` | `250` | Topics a whole-cluster trace reads (the most recently active ones). |
| `EXPLORER_DATA_MODEL_MAX_TOPICS` | `100` | Ceiling on the topics one data-model run may analyse. The page carries the per-run budget (30 by default) and reads this ceiling from the server, so raising it needs no rebuild — each topic costs a sample read plus schema inference. |
| `EXPLORER_CONSUMER_GROUP_PREFIX` | `kafka-explorer-` | Prefix for the consumer groups the explorer creates for **its own** reads — metadata, samples, searches, traces, live sessions. It never renames a group belonging to your pipelines. Empty or unset keeps the default, which stays recognised as the app's whatever you set here, so changing it does not orphan the groups the previous value left behind. |
| `EXPLORER_STARTUP_RESTORE_TIMEOUT_MS` | `3000` | How long each of the two startup state restores — metric configurations, Process Mining field mappings — may wait for the broker's first answer. Measured with nothing listening: the Kafka client's 5 s default made those two waits 10.1 s of a 14.5 s boot. Raise it for a cluster that is slow to answer at boot; a restore that gives up now says so at WARN. |
| `EXPLORER_CLEANUP_OWN_GROUPS` | `false` | Delete, at startup, the consumer groups older builds of this app left on the cluster. The only write it ever makes: restricted to its own group names that the broker reports EMPTY or DEAD. |
| `EXPLORER_LAG_METRICS_TOPICS` | `[]` | Topics whose consumer lag is exported to Prometheus, named rather than discovered — a series per group × topic is how a metrics backend gets killed. Empty starts no polling at all. |
| `EXPLORER_LAG_METRICS_TIME` | `false` | Also export that backlog **in time** (`kafka_consumer_group_lag_seconds`). Opt-in because it is the only lag gauge that reads a record rather than metadata. |
| `EXPLORER_SETTINGS_PERSISTENCE` | `true` | Keep what the **Settings** page is used to change, and the `CREATE TABLE` statements written in the SQL editor, so a restart does not discard them. Both live under `/app/data` — mount it. |
| `EXPLORER_SETTINGS_STORE_SECRETS` | `true` | Whether the credentials entered on that page (SSL passwords, the Confluent secret, the LLM API key) are written to that file, which is created readable by the container's user alone. `false` keeps them off disk — the fields left out are then named in the save's answer and in the boot log, rather than silently dropped. |
| `EXPLORER_SETTINGS_STORE_PATH` | `data/settings.json` | Where those settings are kept. A file rather than a Kafka topic, unlike the app's other stores: these settings *contain the bootstrap address*, so a topic could neither receive a save that repoints the cluster nor be found at boot. |
| `EXPLORER_FLINK_TABLE_STORE_PATH` | `data/flink-tables.json` | Where hand-written `CREATE TABLE` statements are kept, to be replayed into Flink at startup. Tables auto-registered from a Kafka topic are not stored — they are re-derived on demand. |

### Ports, volumes, probes

| | |
|---|---|
| **Port** | `8080` — UI and REST API, one server. |
| **Volume** `/app/logs` | `kafkaexplorer.log`. Mount a **named volume**, not a host file: Docker would create a directory in its place and Logback could not open its log at all. |
| **Volume** `/app/data` | Flink job history (`flink-jobs.json`) — lost on every container replacement without it. |
| **Liveness** | `GET /actuator/health/liveness` — what the built-in `HEALTHCHECK` polls. |
| **Readiness** | `GET /actuator/health/readiness` — liveness **plus** a reachable broker. An unreachable broker means "cannot answer queries", not "restart me": the UI still serves and can be repointed. |
| **Metrics** | `GET /actuator/prometheus` — JVM, HTTP, KRaft quorum lag, consumer-group lag for the topics you name, and any SQL query you turn into a metric from the UI. |

## 🩹 If something looks wrong

**The container is `healthy` but the UI shows no topics.** That is the design, not a bug:
the healthcheck polls *liveness*, which asks "can this process serve", and a broker it
cannot reach does not make it dead — the UI still serves and the Settings page can repoint
it, which is exactly what you need at that moment. Ask readiness for the other half:

```bash
docker exec <container> wget -qO- http://127.0.0.1:8080/actuator/health/readiness
```

`{"status":"DOWN"}` with a `kafka` component means the broker is unreachable from *inside*
the container — nearly always `localhost` in `KAFKA_BOOTSTRAP_SERVERS` (which is the
container, not your host: use the service name on a compose network, or
`host.docker.internal`), or a broker advertising a listener the container cannot route to.

**It still connects to `localhost:9092` however I set the variable.** The variable is
`KAFKA_BOOTSTRAP_SERVERS`. `SPRING_KAFKA_BOOTSTRAP_SERVERS` binds to nothing here — this
app uses `kafka-clients` directly, not `spring-kafka`, so the property prefix is `kafka.`
and not `spring.kafka.`.

**The container exits at once with `Failed to mark memory page as executable — check if
grsecurity/PaX is enabled`.** This is the host, not the image: the JVM asked the kernel to
make its JIT code cache executable and was refused, during VM initialisation, before any of
this application runs. Prove it in one command — the base image, with nothing of ours in it:

```bash
docker run --rm eclipse-temurin:25-jre-alpine java -version
```

If that fails too, no image will start on that host until the policy is changed. On RHEL,
Rocky, Alma and derivatives it is almost always SELinux. Settle it in three commands,
without going near the audit log:

```bash
sudo setenforce 0 && docker run --rm eclipse-temurin:25-jre-alpine java -version
sudo setenforce 1                    # put it back immediately, whatever the result
getsebool -a | grep execmem          # which boolean this policy version has
sudo setsebool -P allow_execmem 1    # the durable fix — or: setsebool -P deny_execmem 0
```

Permissive mode is the discriminator rather than `ausearch`, deliberately: a missing `AVC`
record is weak evidence. `auditd` may not be running, and its event queue can silently drop
records when `q_depth` in `/etc/audit/auditd.conf` is small — so "no denial found" and "no
denial happened" are not the same statement.

Still failing under permissive? SELinux is not the cause. Suspect a restrictive seccomp
profile: if `docker run --security-opt seccomp=unconfined …` starts, that is it — and the
fix is to update Docker, whose older profiles block syscalls current JVMs use, not to run
unconfined in production.

**Nothing in `/app/logs`.** Mount a **named volume**, never a host file. Bind-mounting a
path that does not exist on the host makes Docker create a *directory* where the log file
should be, and Logback then fails to open it — silently.

**The JVM is OOM-killed under load.** See the memory note above: a container with no limit
lets `MaxRAMPercentage` size the heap from the host's RAM.

**Startup takes a while.** The embedded Flink runtime is why the image's own healthcheck
allows a 60-second start period before the first failure counts.

Anything else — `docker logs <container>`, then
[open an issue](https://github.com/devdownin/Kafkaexplorer/issues) with what it printed.

## 🔒 Before you expose it

**This image ships no authentication.** It is built for an internal, controlled network,
and `POST /api/config` can repoint the Kafka cluster at runtime — so anyone who reaches
the port can point it at another broker of theirs.

- Publish on the loopback (`-p 127.0.0.1:8080:8080`) or behind an authenticating reverse proxy. `-p 8080:8080` binds `0.0.0.0` and hands the app to your whole LAN.
- SQL is whitelisted to `SELECT` / `EXPLAIN` / `CREATE TABLE`; XML parsing is XXE-hardened; credentials are redacted from every DDL the UI displays.
- The container runs as **uid 10001**, non-root, on an `eclipse-temurin:25-jre-alpine` base pinned by digest and bumped by Dependabot.

Vulnerability reports: **[SECURITY.md](https://github.com/devdownin/Kafkaexplorer/blob/main/SECURITY.md)**.

## 📦 What is inside

A single Spring Boot 4.1 JAR embedding **Apache Flink 2.3** as the SQL engine, with a
React 19 + Tailwind frontend served from the same port. Kafka clients 4.3.

The JAR is unpacked into Spring Boot's four standard layers (dependencies →
spring-boot-loader → snapshot-dependencies → application), so a patch release re-pushes
the small application layer instead of a few hundred megabytes that are ~95 % identical to
the previous version's.

It is also **the exact JAR attached to the [GitHub Release](https://github.com/devdownin/Kafkaexplorer/releases)** —
built and tested once by CI, then copied in, never recompiled unverified inside the image.

The image also carries a **Class Data Sharing archive**, built at image-build time against
this exact layout, so a container does not re-parse and re-verify the same classes on every
start: measured at **7.7 s to boot without it, 6.4 s with**, with more than half the classes
loaded coming from the archive. It costs about 90 MB of image, and nothing needs to be
configured — the JVM maps it automatically, and starts normally if it ever cannot.

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

<!--
  ── Maintainer notes ─────────────────────────────────────────────────────────────────
  (Below the fold on purpose: this is the payload Docker Hub renders, and the first bytes
  of it should be the page, not a memo. Docker Hub drops HTML comments, but "normally
  dropped" is not a reason to put seventeen lines of internal notes at the top of the
  shop window.)

  This file IS the Docker Hub overview page of `compagnonsdudev/kafkaexplorer`, pushed by
  .github/workflows/dockerhub-description.yml on every push to main that touches it.
  Editing the page on hub.docker.com is pointless — the next sync overwrites it.

  Three constraints that do not apply to README.md:

    - Docker Hub renders this file *outside* the repository, so every link must be
      absolute. A relative `docs/FEATURES.md` resolves to nothing there. `check-links.py`
      (ci.yml, job `docs-links`) resolves all of them back into the repository, because a
      rotten link here is invisible until a visitor lands on a 404.
    - No image in the repository is reachable either. The screenshots are served from
      GitHub Pages (https://devdownin.github.io/Kafkaexplorer/img/…), which publishes
      ./docs on every push to main — the same files as docs/img/, at an absolute URL
      Docker Hub can fetch. A repository-relative path renders as a broken image.
    - `enable-url-completion` is off in the sync workflow, so nothing rewrites a relative
      link into a working one behind your back. Absolute or broken.

  The env-var tables, the base-image line and the Java badge are checked by `check-config-table.py`
  (same CI job) against application.yml and the Dockerfiles. That check exists because
  this page once advertised a JRE two majors out of date, and nothing noticed.

  The screenshots are generated, not photographed: docs/screenshots/ drives the compiled
  SPA over canned API responses. Re-run it after a UI change rather than re-shooting.

  Size limits: 25 000 bytes for this file, 100 bytes for the `short-description` in the
  workflow (both are bytes, and the em dash is three of them).
-->

