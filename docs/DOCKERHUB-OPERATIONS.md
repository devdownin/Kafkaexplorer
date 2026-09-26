# KafkaExplorer deployment reference

The [Docker Hub overview](https://hub.docker.com/r/compagnonsdudev/kafkaexplorer) is kept below 25 KB so the registry can show it in full. This guide contains the detailed settings and troubleshooting notes.

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
| `KAFKA_CONSUMER_GROUP_PROTOCOL` | `consumer` | KIP-848 incremental rebalances for the live consumer — **needs a Kafka 4.x broker**. Set `classic` for older brokers. |
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
| `CLAUDE_PROVIDER` | `OPENROUTER` | `ANTHROPIC`, `OPENAI_COMPATIBLE`, `OLLAMA`, `OPENROUTER` or `SPECTRA`. The default is a **hosted** gateway: message digests leave the host. Use `OLLAMA` or `SPECTRA` to keep everything on your own network. |
| `CLAUDE_BASE_URL` | `https://openrouter.ai/api/v1` | Endpoint of the provider. Blank falls back to the provider's own default — `http://localhost:11434/v1` for `OLLAMA`. |
| `CLAUDE_MODEL` | `openai/gpt-4o-mini` | Model name at that endpoint. `OPENROUTER` names models `vendor/model` — anything on [its model list](https://openrouter.ai/models), though you need not know a name: the Settings page lists the models that fit this deployment, cheapest first. |
| `OPENROUTER_API_KEY` | — | The OpenRouter key (`sk-or-v1-…`), and with the default provider it is **required**: an anonymous request is a 401. |
| `ANTHROPIC_API_KEY` | — | The same setting under its historical name, read when `OPENROUTER_API_KEY` is unset. Required for `ANTHROPIC`; ignored by a local Ollama. On a machine that exports several, `CLAUDE_API_KEY` outranks both and is the unambiguous form. |
| `CLAUDE_USE_RAG` | `false` | `SPECTRA` provider only: also retrieve from SpectraLLM's ingested corpus instead of reasoning solely on the messages inlined in the prompt. |
| `CLAUDE_COLLECTION` | — | `SPECTRA` + `CLAUDE_USE_RAG` only: which ChromaDB collection to retrieve from. Blank uses SpectraLLM's default. |
| `CLAUDE_OPENROUTER_DATA_COLLECTION` | `DENY` | `OPENROUTER` only: `DENY` restricts routing to upstream providers that do not retain or train on what is sent. `ALLOW` widens the choice of models back — a model served only by data-collecting providers is otherwise unroutable, and the error says so. |
| `CLAUDE_OPENROUTER_REQUIRE_PARAMETERS` | `false` | `OPENROUTER` only: route only to providers implementing every parameter sent, making structured output a routing guarantee. Off by default because a model whose providers lack it then becomes unroutable rather than degrading. |
| `CLAUDE_OPENROUTER_MAX_PRICE_USD_PER_MILLION` | `0` | `OPENROUTER` only: the most this deployment will pay a provider, in USD per million tokens, refused at the routing layer rather than counted afterwards. `0` = no ceiling. Applies to both published prices; completion is the dearer in practice. |
| `CLAUDE_SESSION_COST_LIMIT_USD` | `0` | Spend cap, in USD, for one live Process Mining session — it stops itself when reached. `0` disables it. A live session calls the model on every window for up to twelve hours, so this is what bounds a tab left open overnight. Applies only where the provider reports a cost. |
| `PROCESS_MINING_PROMPT_CHAR_BUDGET` | `120000` | Characters of Kafka messages one analysis prompt may carry — about 30 000 tokens. **Lower it, or widen the model's window, when you point this at a small local model.** |

Leave it alone and every other feature works — Process Mining is the only page that calls
a model.

**The prompt has to fit the model's window**, and on every provider but one nothing here can
check that — the window belongs to the endpoint. OpenRouter is the exception: it publishes each
model's context length, so **Test LLM** compares the two and says which way it came out. Read that
as a floor rather than a calibration; the estimate is deliberately optimistic, so a budget it
passes may still not fit while one it rejects certainly does not.

The shipped default is sized for the shipped provider: a hosted
OpenRouter model has room for 30 000 tokens. It is when you point this at a **local** model that
the budget stops fitting, and it does so in silence: Ollama gives a
model 4 096 tokens unless the machine has the VRAM for more, this image's request carries no
`num_ctx` (the OpenAI-compatible endpoint would not read one from the body), and the default
budget above is roughly 30 000 tokens. Ollama does not refuse the excess — it drops the oldest
messages until the prompt fits, and logs that at debug level. The analysis then reasons on a
fraction of what it was given, with nothing saying which fraction. Raise the window
(`OLLAMA_CONTEXT_LENGTH` on the Ollama server, `-c` on llama.cpp) or lower the budget so the
two agree; the [bundled stacks](https://github.com/devdownin/Kafkaexplorer/blob/main/compose/ollama.yml)
set both together.

### Runtime

**Give the container ~2 GB and a real limit** (`mem_limit: 2g`, `--memory=2g`, or a
Kubernetes memory limit). The image embeds a Flink runtime, and the JVM sizes its heap
from the memory it can *see*: with no limit set that is the host's, so on a 32 GB machine
it believes it may take 24 GB. 2 GB is what the project's own
[limits overlay](https://github.com/devdownin/Kafkaexplorer/blob/main/compose/limits.yml)
allocates; a cluster audit over thousands of topics is the workload that wants more.

| Variable | Default | Meaning |
|---|---|---|
| `JAVA_TOOL_OPTIONS` | `-XX:MaxRAMPercentage=75.0` | Replaced wholesale if you set it — re-state the percentage alongside whatever you add. |
| `EXPLORER_CLUSTER_NAME` | `Kafka cluster` | Label for this environment in the header — `Staging`, `Orders prod`. A display name only; the bootstrap address the app is really using is shown beside it on hover. |
| `EXPLORER_DEFAULT_MAX_ROWS` | `50` | Rows a query returns by default. |
| `EXPLORER_DEFAULT_QUERY_TIMEOUT_MS` | `10000` | Per-query wall clock. |
| `EXPLORER_MAX_CONCURRENT_JOBS` | `10` | Continuous Flink jobs (`INSERT INTO` in Job mode) held at once; `0` removes the cap. Each submission starts its own embedded Flink cluster inside the app's process — measured at about 80 threads and 6 MB of heap per job — so this bounds a gesture that is easy to repeat from the editor. A refused submission names the count and this variable; running jobs are stopped from the dashboard. |
| `EXPLORER_AUDIT_MAX_DURATION_MS` | `1800000` | Budget for one full cluster audit; `0` disables it. |
| `EXPLORER_SEARCH_MAX_SCAN` | `20000` | Records one topic-search pass may read. |
| `EXPLORER_ACTIVITY_MAX_TOPICS` | `100` | Topics one activity read may measure — the sparkline column of the dashboard asks only for the rows it displays. |
| `EXPLORER_ACTIVITY_MAX_LOOKUPS` | `20000` | Ceiling on partitions × bucket boundaries for that read, which is its real unit of work. No record is read: the series comes from offsets alone. Topics past the budget are named in the response rather than silently absent. |
| `EXPLORER_STREAM_FLOW_MAX_TOPICS` | `250` | Topics a whole-cluster trace reads (the most recently active ones). |
| `EXPLORER_DATA_MODEL_MAX_TOPICS` | `100` | Ceiling on the topics one data-model run may analyse. The page carries the per-run budget (30 by default) and reads this ceiling from the server, so raising it needs no rebuild — each topic costs a sample read plus schema inference. |
| `EXPLORER_INTERNAL_TOPIC_PREFIX` | — | Prefix for the three topics the explorer writes to **your** cluster for its own state (`internal.audit.history`, `internal.metrics.config`, `internal.field.mappings`). Empty changes nothing. Set it when the explorer shares a cluster with other tenants, or when your naming convention reserves a namespace per application. It never renames a topic of your own pipelines. A value Kafka could not accept in a topic name is refused with a warning and the app runs unprefixed; a value with no trailing separator gets one, so `acme` gives `acme.internal.audit.history`. Not retroactive — changing it points the app at different topics, it does not move what the previous value wrote. |
| `EXPLORER_CONSUMER_GROUP_PREFIX` | `kafka-explorer-` | Prefix for the consumer groups the explorer creates for **its own** reads — metadata, samples, searches, traces, live sessions. It never renames a group belonging to your pipelines. Empty or unset keeps the default, which stays recognised as the app's whatever you set here, so changing it does not orphan the groups the previous value left behind. |
| `EXPLORER_STARTUP_RESTORE_TIMEOUT_MS` | `3000` | How long each of the two startup state restores — metric configurations, Process Mining field mappings — may wait for the broker's first answer. Measured with nothing listening: the Kafka client's 5 s default made those two waits 10.1 s of a 14.5 s boot. Raise it for a cluster that is slow to answer at boot; a restore that gives up now says so at WARN. |
| `EXPLORER_CLEANUP_OWN_GROUPS` | `false` | Delete, at startup, the consumer groups older builds of this app left on the cluster. The only write it ever makes: restricted to its own group names that the broker reports EMPTY or DEAD. |
| `EXPLORER_LAG_METRICS_TOPICS` | `[]` | Topics whose consumer lag is exported to Prometheus, named rather than discovered — a series per group × topic is how a metrics backend gets killed. Empty starts no polling at all. |
| `EXPLORER_LAG_METRICS_TIME` | `false` | Also export that backlog **in time** (`kafka_consumer_group_lag_seconds`). Opt-in because it is the only lag gauge that reads a record rather than metadata. |
| `EXPLORER_MCP_ENABLED` | `true` | Expose this application's analysis layer to an LLM agent over MCP (Model Context Protocol) — SQL over your topics, schema inference, bounded samples. On by default on this image, with `EXPLORER_MCP_READONLY` still `true` so no mutating tool is registered until you deliberately turn that off too. Set `false` to close the surface entirely. It is the only switch: the transport reads this variable, so there is no second one to forget. See [SPEC-MCP.md](https://github.com/devdownin/Kafkaexplorer/blob/main/SPEC-MCP.md). |
| `EXPLORER_MCP_AUTH_TOKEN` | — | The bearer token `/mcp` requires, and that every state-changing `/api/mcp/**` call requires with it. **Turning MCP on without this gives you a dead endpoint on purpose**: with no token the server answers `503` rather than opening. Generate one per deployment (`openssl rand -hex 32`); the bundled compose overlay ships a published development value that protects nothing. The raw token is never stored or logged — its SHA-256 fingerprint is the identity the call trail carries, and the one quarantine and the rate limit bite on, so an agent cannot shake either by reconnecting. |
| `EXPLORER_MCP_REQUIRE_TLS` | `true` | Refuse that token over cleartext (`426`). A bearer credential on plain HTTP is a bearer credential given away, so this stays on unless you are running a loopback development stack. Behind an ingress that terminates TLS, make sure the request still reaches the app marked secure, or every call is refused for a transport that is in fact encrypted. |
| `EXPLORER_MCP_READONLY` | `true` | Whether the agent surface stays read-only. Enforced at registration: with this true, a mutating tool is not registered at all, so it is neither listed to the agent nor invocable by name — not a check the model could argue with. |
| `EXPLORER_MCP_ALLOWED_TOPIC_PREFIXES` | `*` | Restrict every agent tool to these topic prefixes at once, e.g. `demo.,sandbox.`. Checked before any Kafka call — a scope check that runs after the read has already disclosed what it refused — and **including the SQL tool**, whose sources are resolved back to the topics they would read rather than matched as text, since a statement names Flink tables where the dots have become underscores. `EXPLORER_MCP_ALLOWED_GROUP_PREFIXES` does the same for consumer groups. |
| `EXPLORER_SETTINGS_PERSISTENCE` | `true` | Keep what the **Settings** page is used to change, and the `CREATE TABLE` statements written in the SQL editor, so a restart does not discard them. Both live under `/app/data` — mount it. |
| `EXPLORER_SETTINGS_STORE_SECRETS` | `true` | Whether the credentials entered on that page (SSL passwords, the Confluent secret, the LLM API key) are written to that file, which is created readable by the container's user alone. `false` keeps them off disk — the fields left out are then named in the save's answer and in the boot log, rather than silently dropped. |
| `EXPLORER_SETTINGS_STORE_PATH` | `data/settings.json` | Where those settings are kept. A file rather than a Kafka topic, unlike the app's other stores: these settings *contain the bootstrap address*, so a topic could neither receive a save that repoints the cluster nor be found at boot. |
| `EXPLORER_FLINK_TABLE_STORE_PATH` | `data/flink-tables.json` | Where hand-written `CREATE TABLE` statements are kept, to be replayed into Flink at startup. Tables auto-registered from a Kafka topic are not stored — they are re-derived on demand. |

### Operational MCP reviews

`kex_consumer_lag_trend` compares two **complete** readings of the same topic and group.
By default its previous reading is kept in this process for 30 minutes. For scheduled
checks or multiple replicas, mount the **same** writable directory on every Explorer
instance and set `EXPLORER_MCP_LAG_HISTORY_DIRECTORY=/shared/lag`. The backing filesystem
must support interprocess file locks. `EXPLORER_MCP_LAG_HISTORY_TTL_MS` defaults to
`172800000` (48 hours); an expired or missing baseline yields an unmeasured trend.

To configure the environment rules and declared DLQ links, add these properties to
your deployment configuration (example values only):

```yaml
explorer:
  mcp:
    topic-policies:
      - environment: prod
        min-replicas: 3
        min-in-sync-replicas: 2
        min-retention-ms: 86400000
        max-retention-ms: 604800000
        cleanup-policy: delete
    dlq-routes:
      - queue-topic: orders.dlq
        source-topic: orders
        retry-topics: [orders.retry]
        connector-name: orders-sink
        monitoring-reference: dashboards/orders-dlq
        replay-runbook: runbooks/orders-dlq.md
```

See [the application configuration](https://github.com/devdownin/Kafkaexplorer/blob/main/src/main/resources/application.yml)
for the property names. `kex_topic_policy_review` needs an explicit `environment`;
without a matching rule it reports `NOT_CONFIGURED`. `kex_dlq_review` checks Kafka
metadata and a bounded header sample, but connector, monitoring and runbook references
are declarations, not live health checks. It never replays records. Restrict the agent
to the relevant topics and groups with the MCP scope settings above.

### Ports, volumes, probes

| | |
|---|---|
| **Port** | `8080` — UI and REST API, one server. |
| **Volume** `/app/logs` | `kafkaexplorer.log`. Mount a **named volume**, not a host file: Docker would create a directory in its place and Logback could not open its log at all. |
| **Volume** `/app/data` | The Settings page's own input (`settings.json`) and the tables you declare with `CREATE TABLE` (`flink-tables.json`) — lost on every container replacement without it. |
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

**The SpectraLLM stack starts but seeds no demo topics, and `demo-setup` exited non-zero.**
That file is not self-contained, and downloading it on its own is the way to reproduce this:
it mounts `setup-demo.sh`, `seed-demo-once.sh` and the three service entrypoints from the
repository, and Docker creates a *directory* where a bind-mount source is missing — so the
seeder is handed a directory instead of a script. `git clone` the repository and run the file
from inside it, as the snippet above does. Everything else in the stack still works: the
Explorer, the broker and the SpectraLLM UI do not depend on the seeding.

**The stack comes back up with every demo topic present and no records in any of them.**
The seeder is a one-shot that Compose re-runs on every `up`, so it decides for itself
whether there is anything to do — and it used to decide on the marker topic
`internal.demo.seeded` alone. A topic never expires; the ~400 records it vouches for are
deleted by retention, so a stack left down long enough came back with eighty topic names,
nothing in any of them, and a seeder that skipped for ever. Nothing looks wrong from
outside: the container is healthy, the dashboard lists the topics, and Process Mining
profiles an empty cluster. The seeder now checks a canary topic for records as well as the
marker, and seeds again when that topic is provably empty or gone — topics do not expire,
records do. If the check itself cannot be made (a broker still settling, a missing CLI) it
skips and says so rather than assuming the worst: that assumption is not paid once but at
every `up`, and each replay adds a generation of duplicate records to a demo dataset whose
audit findings are meant to be a known quantity. The message names the command below.

Note where that fix lives: `seed-demo-once.sh` is bind-mounted from **this repository**, not
baked into any image, so it arrives with a `git pull` and no tag to chase. On a checkout that
predates it, delete the marker by hand and bring the seeder back up:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic internal.demo.seeded
docker compose up -d --force-recreate demo-setup
```

Reach for `docker compose down -v` only if you mean it: it removes **every** volume of the
project, so it takes the Explorer's own `/app/data` with the broker's — and with it the
settings entered on the Settings page.

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
- Turning the MCP server on (`EXPLORER_MCP_ENABLED=true`) means setting `EXPLORER_MCP_AUTH_TOKEN` to a value of your own and leaving `EXPLORER_MCP_REQUIRE_TLS` alone: without the token the endpoint answers `503` instead of opening, and without TLS it refuses the token rather than carrying it in clear. That credential authenticates an *agent*; the rest of the application still authenticates nobody, so the first line of this section keeps deciding how the whole thing is deployed.

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
