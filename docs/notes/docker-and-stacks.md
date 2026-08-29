# Docker, compose stacks and image publishing — design notes

Extracted from `CLAUDE.md`. `DOCKER-AUDIT.md` is the full review; this is what has to
stay true. Read it before editing a compose file, either Dockerfile, or `release.yml`.

## Building without a local toolchain

Everything downloaded (Maven repository, npm cache, the Node toolchain frontend-maven-plugin
fetches) lands in the `build_cache` named volume, so the second run is fast; `down -v` resets it.
The source tree is bind-mounted, so `target/` appears in the checkout — root-owned on Linux, same
as the dev stack.

`compose/dev.yml` is the hot-reload stack (Kafka + `spring-boot:run` + Vite). Three things
about it are load-bearing: the backend runs with `-P '!build-frontend'` (the profile is
`activeByDefault`, so a plain `spring-boot:run` downloaded a whole Node toolchain into `target/` and
rebuilt the SPA on every container start); `node_modules` and `target/` are **named volumes
shadowing the bind mounts**, keeping Linux-native binaries and root-owned build output out of the
host checkout; and `VITE_PROXY_TARGET=http://backend:8080` is read by `vite.config.ts` — Vite's
proxy pointed at `localhost:8080`, which inside the frontend container is the frontend itself, so
every `/api` call in the dev stack was proxied into the void. The `~/.m2` bind mount it used to
carry is gone: requiring a Maven directory on the host defeats the point of the stack.

The production `Dockerfile` needs BuildKit (Docker 23+ default) for the `RUN --mount=type=cache` in
its frontend stage. Do not reorder the backend stage to copy sources before resolving dependencies —
that was the original layout, and it re-downloaded the whole Flink/Kafka/Spring tree on every source
edit. That stage resolves them into a **layer** (`dependency:go-offline` keyed on `pom.xml`, with an
in-image `-Dmaven.repo.local`) rather than a cache mount, because BuildKit cache mounts are not
exported with the layers: `cache-to: type=gha` carried none of it, so CI re-downloaded everything on
every run. The `|| true` on that step is deliberate — it is pure cache warming, the `package` below
runs online and fetches whatever go-offline missed, and a warming step must never fail a build. The
accepted cost is that a local build which *changes the pom* re-downloads, where the mount did not.
`.dockerignore` uses `**/node_modules`, not `node_modules/`: the latter only matches the repository
root, while the real one is at `src/main/webapp/node_modules`.

## The bundled stacks

All bundled compose stacks run **Kafka 4.3 in KRaft mode** (`apache/kafka:4.3.1`, single combined broker+controller node — no Zookeeper anywhere, `compose/image.yml` included). CI runs no broker of its own: `KafkaClusterIntegrationTest` starts one through Testcontainers (`apache/kafka-native:4.3.1`), which is also why it works on a developer machine. Prefer that over a workflow-level `services:` block if a new test needs a broker.

```bash
# Kafka 4.3 (KRaft) + Schema Registry + app + demo topics (recommended)
docker compose -f docker-compose.yml -f compose/schema-registry.yml up -d

# Kafka 4.3 (KRaft) + app + demo topics, without Schema Registry
docker compose up -d

# Demo data setup (creates 79 topics)
./setup-demo.sh localhost:9092

# Avro topics — needs Schema Registry and the Confluent CLI, so it is a separate
# script wired only into compose/schema-registry.yml
./setup-demo-avro.sh localhost:9092 http://localhost:8081
```

### The prompt has to fit the model's window

`process-mining.prompt-char-budget` is 120 000 characters — ~30k tokens — and **the window
belongs to the endpoint, so nothing in this application can check that it fits** — with one
exception, added later and narrow: OpenRouter *publishes* each model's `context_length`, so there
the Test button compares the two and says which way it came out (`OpenRouterModelCatalog`, below).
Everywhere else the sentence stands as written, and even there it is a floor rather than a
calibration. It is sized for
the default provider, which is hosted and has the room; what it is wrong for is a **local** model,
which is therefore the thing that changes underfoot the day somebody switches to one — and it
changes in silence: Ollama gives a model
**4 096 tokens** unless the machine has the VRAM for more, `OpenAiCompatibleLlmClient` sends
`model` / `messages` / `max_tokens` / `temperature` / `stream` and **never `num_ctx`** (which
that endpoint would not read from the body anyway), and Ollama does not refuse the excess — it
drops the oldest messages until the prompt fits and logs it at DEBUG, i.e. nowhere on a default
install. Every Process Mining analysis on `compose/ollama.yml` was therefore reasoning on a
fraction of what it had been handed, with nothing on screen or in the log naming the fraction.
The bundled stacks now set both halves together (`OLLAMA_CONTEXT_LENGTH` / `LLM_CONTEXT` against
`PROCESS_MINING_PROMPT_CHAR_BUDGET`) and say so where they set them; the default in
`application.yml` is unchanged, since the provider it now ships pointed at can afford it, and
carries the rule beside it. Raising one without the other buys nothing or truncates again — and the KV cache is what a
wider window costs (~2 GB for a 7B at 16k).

### The SpectraLLM stack

Process Mining can be answered by a **local SpectraLLM** instance rather than Ollama or Anthropic
(`CLAUDE_PROVIDER=SPECTRA`, whose client posts to Spectra's single-turn `POST /api/query`). One
file wires the pair. There were two, and the deletion of the other is the point:

- **The developer stack is gone.** It `include:`d SpectraLLM's own compose from a sibling
  checkout (`SPECTRALLM_DIR`, default `../SpectraLLM`) and **built** the explorer from source,
  so running it needed that checkout on disk, a Maven build and an npm build — and it was the
  only file in the tree whose syntax could not be resolved without CI **fabricating a stub of
  somebody else's repository**, which is a check that tests the stub as much as the file. It was
  also never booted end to end by anything, where the hub stack has its own CI job. What it
  bought was following a live SpectraLLM checkout as you edit it; the replacement is to point
  the pins at what you built (`SPECTRA_IMAGE_TAG`, `EXPLORER_IMAGE_TAG` — already how every
  image in that stack is chosen), which is one variable rather than a second stack to maintain.
  Two things it taught are worth keeping, because they are traps rather than history. A service
  named identically to one carried by an `include:` **merges with it and inherits its profile**,
  so a profile-gated upstream `kafka` makes your broker vanish unless that profile is on —
  `depends_on` then stops resolving and the whole project is rejected as invalid, and
  `profiles: []` does not clear it; `extends` is what reuses a definition under a *different*
  name, which a second `-f` cannot do. And a broker renamed that way needs every setting that
  embeds the service name overridden with it (`KAFKA_CONTROLLER_QUORUM_VOTERS`,
  `KAFKA_ADVERTISED_LISTENERS`) — a broker advertising a name nothing can resolve does not
  refuse the connection, it accepts it and hands the client an address it cannot reach, which
  surfaces as a timeout somewhere else entirely.
- **`compose/spectra-hub.yml`** builds nothing. Both projects publish their images under
  `compagnonsdudev` on Docker Hub (`kafkaexplorer`, `spectrallm`, `spectrallm-frontend`), so a
  machine with only Docker runs the pair — no Maven, no npm, no SpectraLLM checkout, and
  nothing built. It does need **this** repository checked out, which the page it is
  advertised on used to deny: it mounts the demo seeder and the three service entrypoints,
  so a `curl -O` of the single file left Docker to create directories where those files
  should be. That was true before the entrypoints moved out of the YAML; extracting them
  made it definitive rather than introducing it, and `docs/DOCKERHUB.md` now says `git
  clone` where it said `curl`:

  ```bash
  docker compose -f compose/spectra-hub.yml pull
  docker compose -f compose/spectra-hub.yml up -d
  ```

  **The three services that run a shell take it from `scripts/spectra-hub/`**, not from a YAML
  literal: compose interpolates `${…}` inside an entrypoint, so every shell variable had to be
  written `$${…}` — around forty escapes across the three, and a single `$` written where two
  belong yields an empty string at runtime rather than an error, which is the failure mode with
  no symptom. They are mounted read-only, and `.gitattributes` pins `*.sh` to LF on every
  platform so a Windows checkout still mounts something the image's `/bin/sh` can run. Note what
  this is **not**: it is not a copy of upstream's `scripts/llm-chat-entrypoint.sh` — see the
  registry-pointer note below, which is exactly the thing this repository refuses to duplicate.
  These are our own three, and they were already ours; only their address changed.

  Four things in it are load-bearing. **The models live in a named volume** (`spectra_data`), not
  in `./data` as upstream: there is no SpectraLLM checkout here to hold that directory, and a
  bind mount created by Docker is root-owned while the API image runs as `spectra` — so a
  `spectra-data-init` one-shot settles ownership before anything else mounts it, the same idiom
  as `kafka-data-init` and for the same reason (`llm-chat` / `llm-embed` run a llama.cpp image
  that carries no `/app/data`, so one of them initialising that volume would leave it empty and
  root-owned). **`llm-chat` reads the registry pointer once, at start**, rather than watching it:
  upstream's supervisor lives in `scripts/llm-chat-entrypoint.sh`, which is not in this
  repository, and inlining a copy of it here would be a copy that drifts — so activating another
  model in the Spectra UI needs a `restart llm-chat`, which the file says. **Nothing waits on the
  first-boot model download** (~4.8 GB): `spectra-api` installs the chat model itself
  (`spectra.startup.auto-install-models`), a `spectra-models` one-shot fetches the embedding
  GGUF that the API does *not* install — with **`wget`, never `curl`**: the Spectra image
  installs both to run the llmfit installer and purges curl at the end of that same layer, so a
  curl there finds nothing at runtime, which is what the CI boot caught (`curl: not found`, and
  no model fetched, on a stack that otherwise looked healthy). It verifies a
  `SPECTRA_*_MODEL_SHA256` when one is pinned and deletes the file rather than serving it when
  the digest does not match, and it does **not** resume a partial transfer: `wget -c` with `-O`
  appends blindly when the server ignores a Range request, which yields the right size and the
  wrong bytes. The two llama.cpp containers poll until the file they serve appears — so the Explorer, the broker and the Spectra UI are up in seconds, and
  `up --wait` is the one thing not to use. **And the two prompt budgets are sized against each
  other**: `LLM_CONTEXT` (16384, split across 2 slots = 8192 tokens per request) against the
  Explorer's `PROCESS_MINING_PROMPT_CHAR_BUDGET`, lowered to 16 000 from the shipped 120 000 —
  ~30k tokens does not fit in that window, and what a model cannot see it does not say it missed.
  The request timeouts follow (`CLAUDE_REQUEST_TIMEOUT_SECONDS` / Spectra's generation timeout,
  300 s each): a 7B Q4 model on CPU takes minutes, and a request timeout is *terminal* on that
  path. Every image is pinned and overridable (`SPECTRA_IMAGE_TAG`, `EXPLORER_IMAGE_TAG`,
  `LLAMA_CPP_IMAGE_TAG`, `CHROMADB_IMAGE_TAG`), and only three ports are published — chromadb's
  is read *and write* access to the ingested corpus and llama-server has no authentication.

  **Do not set `SPECTRA_API_KEY` in that stack.** Spectra's `ApiKeyFilter` reads `X-API-Key`
  while `SpectraLlmClient` sends `Authorization: Bearer`, so a key there leaves the whole stack
  looking healthy while every Process Mining call answers 401. Neither application authenticates:
  what protects the stack is `BIND_ADDR` on the loopback.

  **Three overlays sit beside it**, and each survives for one reason: what it changes cannot be expressed as a variable, which is the test the fourth failed.
  `compose/spectra-hub.gpu.yml` swaps both llama.cpp servers for the CUDA image *pinned to
  the same build number* (`server-cuda-b9828` against `server-b9828` — a floating `server-cuda`
  would put a different engine revision under a stack that pins everything else) and requests the
  devices; it is not safe to leave on where there is no GPU, which is precisely why it is an
  overlay. `compose/spectra-hub.limits.yml` is this stack's own limits file rather than a
  few lines added to `compose/limits.yml`: that one names `explorer` and `kafka`, and a
  service named in an overlay but absent from the base file becomes a new imageless service and
  fails the whole `up` — so this stack needs its own, and it bounds the **seven long-running
  services of its eleven** (the four it leaves out are one-shots that exit) instead of leaving the
  four heaviest unbounded. It deliberately sets **no `cpus` on the llama.cpp
  servers**: on CPU inference throughput *is* the core count.

  **A 3B chat model instead of the default 7B** — ~2 GB of weights rather than 4.7, half the
  memory, and an answer in a fraction of the time, which is what stops the 300 s timeouts
  being load-bearing — used to be a fourth overlay and is now **four lines of `.env`**. That
  is all it ever was: every value it set already had an interpolated default in the stack
  file, so the overlay expressed nothing a variable could not, which is exactly the test for
  whether something deserves to be a file. It is also why `spectra-models` fetches *two*
  models: `spectra-api` installs the default chat model itself and **only** that one, so
  serving another means naming its URL (`SPECTRA_CHAT_MODEL_URL`) and turning the
  auto-install off, together with the file name and the alias — the four have to agree, and
  `.env.example` sets them in one block for that reason.

  `compose/spectra-hub.ingest.yml` is the one that makes the pair more than a shared
  model — SpectraLLM consumes the topics and indexes what is *in* the messages, so the corpus
  answers questions with cited sources and the Explorer's audits can read it (`EXPLORER_USE_RAG`,
  whose collection defaults to the one the ingestion writes to, so one variable lines both halves
  up; with use-rag false Spectra answers directly and never looks at a collection). It is an
  overlay rather than a bare `SPECTRA_KAFKA_ENABLED=true` because the flag alone gets two things
  wrong on a cold stack, and both are **ordering** problems, which is what a compose file can
  express: a consumer subscribing to a topic that does not exist yet **creates** it
  (`allow.auto.create.topics` defaults to true and the demo broker allows it) with one partition
  instead of three, and `setup-demo.sh` then leaves the existing topic alone — the multi-partition
  dataset the key-narrowing and window features are calibrated on silently becomes
  single-partition; and indexing a record means embedding it, so on a first boot every record
  fails three times a second apart and lands in `<topic>.DLT`, a new topic on the cluster under
  exploration, while the embedding model is still downloading. The overlay waits for
  `demo-setup` to complete and for `llm-embed` to be healthy. Its accepted cost is stated where
  it is paid: the Spectra API, and therefore its UI, now waits for both — the Explorer does not,
  it still depends on the broker alone. Topic lists are explicit, with no patterns (Spring
  resolves a comma-separated list, and an **empty** list subscribes to a topic named `""` rather
  than to nothing, which fails at startup), and `internal.*` is excluded: that is the Explorer's
  own bookkeeping, not domain content.

`setup-demo.sh` is the sandbox every stack seeds, and it is written to exercise the features that
have no data otherwise. **Every business record carries a record key and Kafka headers**
(`correlation-id`, W3C `traceparent`, `source-system`, `event-type`, `produced-at`) — without them,
exact-key tracing, key-partition narrowing, HEADER search, log compaction and the audit's key-based
duplicate detection have nothing to run against, which is what the demo looked like before. The
order pipeline and `demo.orders.nested` are **multi-partition** (3 and 6), because "only this key's
partition" narrows nothing on a single-partition topic. `demo.payments.*` / `demo.shipments.*` are
correlated to the orders **by header only** — their payloads carry `PAY-`/`SHP-` references and
never the order id — so they are the dataset that proves the "search headers too" switch does
something. `demo.iot.sensors` and `demo.orders.nested` spread `event_time` over ~2 hours, which is
what makes `TUMBLE`/`HOP` return more than one bucket; with every record stamped "now" the whole
window feature collapsed into a single row. Duplicates (`ORD-103`/`ORD-105` redelivered) and poison
records *inside a healthy topic* (`demo.orders.3.enriched`) exist so the cluster audit reports
findings rather than a clean-room zero. **`demo.orders.2.retry.5m`, `demo.orders.2.dlt` and
`demo.payments.dlq`** exist for the same reason one notch over: nothing seeded here carried a retry
or a dead-letter name, so the Dashboard's retry marker and its dead-letter badge had no row to run
against on the very cluster the README recommends, and the documentation screenshots could never
show either. ORD-107 is received, retried twice and buried, so a trace on it crosses the three; the
payments side spells it `.dlq` **on purpose**, a cluster grown through more than one team carrying
both spellings, which is exactly what a rule recognising a single one lets through.

Two constraints when extending it: **one producer call per topic, not per message** (a JVM start
costs ~1.5 s, and docker-compose blocks the app on this container — the per-message version spent
minutes seeding 400 records), and **no `bc`, no `date -d`, no `${var,,}`** — it runs inside the
busybox-based `apache/kafka` image as well as on macOS. Money is integer cents through `printf`,
and event times are epoch seconds (the query engine reads any value below 10^10 as seconds).
Header values must contain no comma, colon or tab: `kafka-console-producer` parses headers as
`k1:v1,k2:v2` ahead of a `key<TAB>value` line, which is why timestamps travel as epoch millis.
`DEMO_HOP_DELAY` (default 2 s) is the real pause between the traced order's hops — record
timestamps come from the broker at produce time, so a real pause is the only way to give Stream
Flow a hop latency to chart; set it to 0 for the fastest seeding and flat latencies.

`seed-demo-once.sh` is the `demo-setup` entrypoint in every stack: it waits for the broker, then seeds **unless the marker topic `internal.demo.seeded` is present *and* the demo data is still there**, creating that marker after a successful run. Both halves are needed because they answer different questions and only one of them expires: the marker is a *topic*, and a topic never goes away, while the records it vouches for are deleted by retention. A stack brought back up past that point came back with eighty topic names, no records in any of them, and a seeder that skipped for ever — the state this file was found in, with Process Mining profiling an empty cluster while everything looked healthy, and the documented way out (`down -v`) also wiping the explorer's own volume and the operator's stored settings with it. So a canary topic (`demo.orders.1.received`, end offsets minus beginning offsets over its partitions) is checked for records. **It has four answers, not two, and "we could not ask" is its own** (`canary_state`: `populated` / `empty` / `absent` / `unknown`). A topic that is provably empty, or gone altogether, has lost its data and is seeded again — topics do not expire, records do. A read that *failed* says nothing about the data, and folding it into "empty" looks like the safe direction and is not: it is not paid once but at **every** `up`, each replay adding one more generation of duplicates to the dataset the audit's duplicate detection and the Stream Flow traces are calibrated against, which is the exact damage the marker exists to prevent. The marker's presence is itself evidence that a seed once succeeded, so on an unreadable canary that evidence stands — loudly, with the one command that forces a re-seed. The topic list is read once for both questions, retried three times, and if it cannot be read at all the script seeds nothing and says so: it cannot see the marker either, so seeding would be seeding blind, and a compose one-shot runs again at the next `up` anyway. `KAFKA_BIN` and `SETUP_SCRIPT` are overridable so those decisions can be exercised against stub CLIs, which is what `seed-demo-once.test.sh` does — seven cases, no broker and no daemon, run by the `seeder-logic` job. Two of them are measurements the seeder must act on and three are non-answers it must not mistake for one; each was checked to fail against the revision it describes. A broker would cost minutes here and prove nothing extra: what is under test is a decision taken from two command outputs, and a claim about how Kafka itself behaves belongs in `KafkaClusterIntegrationTest`. Kafka data lives in a named volume that survives `docker compose down`, and Compose re-runs a one-shot on every `up`, so the seeder used to replay its ~400 records into topics that already held them — a minute repaid at each start, and one extra generation of duplicates each time, which silently changes what the audit's duplicate detection and the Stream Flow traces report on a dataset calibrated to report a known value. `down -v` wipes the volume and therefore the marker.

KRaft single-node notes: the `apache/kafka` image takes the cluster id via the `CLUSTER_ID` env var (a `KAFKA_CLUSTER_ID` var would be translated into an ignored `cluster.id` server property); all internal-topic replication factors (`offsets`, `transaction state`, share-group state) are pinned to 1 and `__consumer_offsets` runs with a single partition for faster startup. Kafka data persists in a named `kafka_data` volume (`KAFKA_LOG_DIRS=/var/lib/kafka/data`) so `internal.*` topics survive `docker compose down` (`down -v` resets); the image runs as non-root `appuser`, so a `kafka-data-init` one-shot service chowns the volume before the broker starts — don't remove it.

## Starting and stopping the stacks

`DOCKER-AUDIT.md` is the full review; the load-bearing points:

- **The app depends on the broker only.** It used to also wait for `demo-setup: service_completed_successfully` (and, in the LLM stack, for the multi-gigabyte `ollama-pull-model`), so the UI was unreachable for the whole seed — for data it does not need to boot. The Dashboard polls every 30s, so topics appear as they are created. Keep the seeder beside the app, not in front of it.
- **The broker healthcheck is `interval: 30s`, not 5s.** `kafka-broker-api-versions.sh` starts a JVM, and Docker runs a healthcheck for the container's whole life — at 5s that was roughly a fifth of a core, permanently. `start_period: 30s` covers the boot, where failures don't count against `retries`. `start_interval` would give back both but needs Docker 25+/Compose 2.20+, which the main stacks deliberately don't require.
- **`stop_grace_period` is set everywhere and is not decoration.** Docker's default is 10s; the app gets 35s, which is `spring.lifecycle.timeout-per-shutdown-phase` (15s of graceful web shutdown) plus `ShutdownBudget.TOTAL_MS` (10s of bean destruction) plus the JVM's exit. Raise either and you must raise the grace period, or Docker SIGKILLs exactly what they were meant to protect. The broker gets 30s so a stopping KRaft node finishes its flush instead of being SIGKILLed into a log recovery on the next start.
- **`ShutdownBudget` is one deadline shared by every executor pool**, not five seconds each. Six services own a pool (`FlinkSqlService`, `StreamFlowService`, `FlinkRuntimeCoordinator`, `AuditService` ×2, `KafkaLiveConsumer` ×2) and bean destruction is sequential, so the private waits added up to ~35s and grew by five seconds with every pool the codebase gained. The first pool destroyed starts the clock; the rest inherit what is left, with a 500 ms floor each so the last one is not interrupted the instant the budget runs out. Use it for any new pool — do not reintroduce a private `awaitTermination`.
- **Liveness and readiness are split, and the container healthcheck targets liveness.** The project uses `kafka-clients` directly rather than `spring-kafka`, so Boot auto-configures no Kafka health indicator and `/actuator/health` reported UP whatever the broker was doing. `KafkaHealthIndicator` (bean id `kafka`, a 2s `describeCluster` probe) joins the **readiness** group only: an unreachable broker means "not ready to answer queries", not "restart this process" — the UI still serves and the Settings page can repoint the app, which is what an operator needs at that moment. `HealthProbesTest` pins both groups against the real context, because a typo in the `include` would leave the group absent and the HEALTHCHECK reading a 404 as a dead container.
- **Published ports bind to `${BIND_ADDR:-127.0.0.1}` and are parameterized** (`EXPLORER_PORT`, `KAFKA_PORT`, `SCHEMA_REGISTRY_PORT`, `OLLAMA_PORT`, `VITE_PORT` — see `.env.example`, which compose reads from a root `.env` automatically). The app has no authentication and `POST /api/config` can repoint the Kafka cluster at runtime; the Docker default of `0.0.0.0` offered that to the whole LAN on a plain `up`. Expose deliberately with `BIND_ADDR=0.0.0.0 docker compose up -d`. `KAFKA_PORT` also rewrites the broker's advertised `PLAINTEXT_HOST` listener — without that, a host client told to use the new port is redirected straight back to 9092 by the broker itself.
- **Base images are pinned by digest** (`tag@sha256:…`, tag kept in front — it is the only thing that says what the digest is), with Dependabot's `docker` ecosystem grouped so the bumps still arrive. Pinning without that would trade reproducibility for a frozen JRE, which is a worse deal than either.
- **Every `close()` on a Kafka client at shutdown is bounded** (`AdminClient`, the audit-history and metrics-config producers, the live consumers): the no-arg overloads wait without a deadline, and shutdown is exactly when the broker is likely gone — an unbounded close hands the JVM to the SIGKILL mid-teardown. `KafkaLiveConsumer.shutdown()` also completes the SSE emitters of sessions whose polling task never reached `finishSession()`.
- **The app writes under `/app` and both paths are volumes**: `logs/` (`logging.file.name`) and `data/` (`explorer.flink-job-store-path` — Flink job history was lost on every recreate). They are named volumes, never a bind-mounted *file*: `./Kafkaexplorer.log:/app/logs/kafkaexplorer.log` pointed at a path absent from the checkout, so Docker created a directory there and Logback could not open its log at all. That broken mount was also the stated reason the runtime image stayed root; both images now run as `USER 10001:10001`, owning `/app/logs` and `/app/data`, and a named volume inherits that ownership where a host bind mount would not.
- **Both runtime images ship the JAR as Spring Boot's four layers**, most stable first (`dependencies` → `spring-boot-loader` → `snapshot-dependencies` → `application`), extracted with `-Djarmode=tools … extract --layers --launcher` (the Boot 3.3+ entry point; `layertools` is gone). There is no fat JAR in the image any more, so the entrypoint is `java org.springframework.boot.loader.launch.JarLauncher`, and a patch release re-pushes only the small application layer instead of a few hundred megabytes that are ~95% identical to the previous version's. Keep the four COPY lines in that order — a COPY invalidates every layer after it. **A Class Data Sharing archive is built on top of them**, by a training run in the runtime stage (`-XX:ArchiveClassesAtExit` with `-Dspring.context.exit=onRefresh`, which refreshes the context and exits without opening a port). Measured against this exact layout — the extracted layers behind `JarLauncher`, with no broker listening: **7.74 s without it, 6.39 s with**, and 12 911 of the 24 343 classes loaded are served from the archive, application classes included. Three things about it are load-bearing. It is dumped in the **runtime** stage rather than a `--platform=$BUILDPLATFORM` one like the extractor, because a CDS archive is architecture-specific — so the arm64 variant produces its own under emulation, which is the real cost of this alongside ~90 MB of image. The flag goes on the **ENTRYPOINT, not into `JAVA_TOOL_OPTIONS`**: that variable is documented as replaced wholesale when an operator sets one, so a container started with a tuned `-XX:MaxRAMPercentage` would have silently dropped the archive and paid the 90 MB anyway. And it stays on the JVM default `-Xshare:auto`, so a JVM that cannot map the archive starts normally instead of refusing to boot over an optimisation — which is exactly why `ci.yml` runs `-Xshare:on` against it once in the `release-image` job: that leniency is also what would let a mis-wiring ship 90 MB buying nothing, in silence.
- `release.yml` publishes `linux/amd64,linux/arm64` (free here — a JRE base plus architecture-independent bytecode, and the extraction stage is pinned to `--platform=$BUILDPLATFORM` so it is not replayed under QEMU) and gates `latest` on the absence of a `-` in the tag, so a `v1.3.0-rc1` no longer becomes what `docker run …/kafkaexplorer` pulls.
- **One base, and everything else is an overlay of it.** The tree carried **sixteen** compose
  files, of which *five* answered the same question — "how do I run this?" — by restating each
  other: `docker-compose.yml`, `-kafka4`, `-llm`, `-spectra` and `.release` were all **bases**,
  each with its own copy of the explorer service, and the first refactor had already reduced
  their brokers to `extends:` after `docker compose config` reported the copies byte-identical.
  Being separate *bases* was the remaining cost, and it was not only duplication: Schema
  Registry and Ollama **could never run together**, because two bases cannot be layered.
  There is now **one base file at the repository root** and everything else in `compose/`:

  | File | Kind | What it is |
  |---|---|---|
  | `docker-compose.yml` | base | Kafka 4.3 KRaft + explorer + demo seeder. The one everything layers onto. |
  | `compose/schema-registry.yml` | overlay | Schema Registry + the Avro seeder. Was `docker-compose-kafka4.yml`. |
  | `compose/ollama.yml` | overlay | A local Ollama model for Process Mining. Was `docker-compose-llm.yml`. |
  | `compose/image.yml` | overlay | Run the published image instead of building. Was `docker-compose.release.yml`. |
  | `compose/limits.yml` | overlay | Opt-in `mem_limit` / `cpus`. |
  | `compose/ci.yml` | overlay | CI-only, layered on `image.yml`. |
  | `compose/dev.yml` | standalone | Hot reload: broker + `spring-boot:run` + Vite. |
  | `compose/build.yml` | standalone | One-shot toolchain (`run --rm`), not a stack. |
  | `compose/spectra-hub.yml` + `.gpu` / `.ingest` / `.limits` | standalone + overlays | The SpectraLLM pair, from published images. |

  **The `kafka4` name was a lie worth removing.** Every stack here runs Kafka 4.3 in KRaft —
  `docker-compose.yml` included — so a file named for that advertised a choice that had stopped
  existing, on the stack the README recommends, which is the first thing a newcomer reads. What
  it adds is a Schema Registry, and it is named for that now.

  **Where relative paths resolve is the one rule to know**, and it was measured rather than
  assumed. Compose sets the *project directory* to the directory of the **first `-f` file**, and
  every `./…` in *any* of the layered files resolves against that — so an overlay under
  `compose/` mounting `./setup-demo-avro.sh` gets the repository root, because the base always
  comes first. A **standalone** file under `compose/` is its own project directory, so its paths
  carry `../` and it must declare `name:` explicitly, or its volumes would come back under a
  `compose_` prefix and a warm Maven cache would be silently discarded. Paths inherited through
  **`extends`** are the exception: they resolve against the *extended* file's directory, which
  is why `compose/dev.yml` inherits the base broker without a single `../`.

  **`compose/dev.yml` no longer restates the broker either** — it was the last verbatim copy in
  the tree, sixty lines of KRaft settings duplicated so that *one* thing could differ, the name
  of the data volume. `extends` merges sequences, so the base's `kafka_data` mount and this
  file's `kafka_data_dev` would both land on `/var/lib/kafka/data`; **`volumes: !override`** is
  what replaces the list outright, and it works with `extends` (verified, not assumed). The dev
  broker keeps its existing volume name, so nobody loses a broker to the refactor.

  **`compose/image.yml` needs `build: !reset null`**, and that is what makes it an overlay
  rather than a base: without it the base's `build: .` survives the merge and `up` compiles the
  source tree and tags the result with the published image's name — the opposite of the point.
  `compose/ci.yml` now layers on top of it, so the file real deployments use is exercised on
  every CI run instead of only by `compose-lint`.

  **Two files were deleted outright.** `docker-compose-spectra.yml` — see the SpectraLLM
  section above. And `docker-compose-spectra-hub.small.yml`, which set four values that already
  had interpolated defaults in the stack file: it expressed **nothing a variable could not**,
  which is the test for whether something deserves to be a file at all. It is four lines of
  `.env.example` now.

  The whole transform is verifiable the way the previous one was: `docker compose config` over
  every stack is **unchanged**, with one deliberate exception — the explorer no longer waits on
  `schema-registry: service_healthy`, which contradicted this file's own rule that the app
  depends on the broker and nothing else.
- **No `container_name`, and the app service is `explorer` in every stack.** `container_name` is daemon-global, so the shared `kafka` / `kafka-sql-explorer` names meant two stacks could never coexist and switching files without a `down` first collided; compose derives `<project>-<service>-<n>` instead, and `docker compose -p other … up` gives a second independent stack. The project name still defaults to the directory, so `kafka_data` keeps its name and already-seeded topics survive. Address services by service name (`docker compose logs kafka`). The `app` → `explorer` rename removes an inconsistency and is what lets an overlay target the service at all — a name present in an overlay but absent from the base file becomes a new, imageless service and fails the whole `up`.
- **Resource limits are an opt-in overlay** (`compose/limits.yml`, layered onto `docker-compose.yml` and anything layered with it — it used to have to name four separate bases, and forgetting a fifth was silent). Not in the stacks themselves, because a limit set too low is worse than none — the JVM is OOM-killed instead of running a GC. But without *any* limit, `-XX:MaxRAMPercentage=75.0` reads the host's memory, so on a 32 GB workstation the JVM believes it may take 24 GB: `mem_limit` is what gives that flag a meaning. Use `mem_limit`/`cpus`, never a `deploy:` block — that is Swarm syntax, silently ignored by `docker compose up`.
- **Every compose file is parsed by CI** (`compose-lint` in `ci.yml`), each overlay layered onto
  its base rather than alone — an overlay on its own is a set of services with no image. Twelve
  files shipped here and the build parsed none of them; the job found what was then
  `docker-compose-kafka4.yml`, the stack this file recommends, refusing to start at all since
  the day two volume mounts were
  added without their top-level declarations (`service "explorer" refers to undefined volume
  explorer_logs: invalid compose project`). It takes seconds, needs no daemon and pulls nothing,
  and it **fails on a compose file that no combination names**, so a new stack cannot be added
  without being checked. It also no longer fabricates a stub of SpectraLLM's own compose, that
  having been needed by exactly one file which no longer exists — a check whose fixture is an
  invention of somebody else's repository is partly testing the invention.
- **The images the stacks pull are checked too** (`docs/check-image-pins.py`, in the
  `docs-links` job): nothing floats (`curlimages/curl:latest` sat two services below the comment
  claiming Ollama was "the only floating tag left in the tree"), the llama.cpp CPU and CUDA tags
  name the **same build** (the GPU overlay must change the hardware, not the engine's revision),
  and the Explorer pin does not name a release **nobody has published yet**. That default is
  hand-written and Dependabot cannot read a `${VAR:-1.8.9}` form, so nothing else would ever
  move it — but whether it has gone *stale* is a question for the **registry**, not for the git
  tags, and that half is `--published`, run in the `spectra-hub-stack` job. A tag exists the
  moment it is pushed and the image only when the release workflow finishes: asking the tags
  made this check demand a bump to an image that was still building, and a release whose
  publication *failed* — which has happened here — would leave a tag with no image behind it,
  blocking every pull request on a bump that could never be made. So the offline half gates
  every PR, the registry half runs where the network is already a dependency, and a registry
  that cannot be reached is reported rather than failing the build: "we asked and it is stale"
  and "we could not ask" are different answers. It needs tags, hence `fetch-tags` on both jobs'
  checkouts, and it fails rather than skipping when they are absent.
- **The documentation checks are discovered, not listed** (`for check in docs/check-*.py`).
  That step was six `- run:` lines, so a seventh script would have been executed by nothing
  until somebody remembered to add one — the same structural argument `compose-lint` was
  rewritten for, left standing one job below it. What each check answers is now a comment block
  above the loop rather than a line beside each invocation. `check-image-pins.py --published`
  stays a separate step in `spectra-hub-stack`: it takes an argument and needs the network.
- **The stacks are also checked against `.env.example`, and against themselves**
  (`docs/check-compose.py`, in the `docs-links` job). `.env.example` exists so that changing
  where a stack is published does not mean editing six compose files, which only holds if it
  lists them all — **five variables had a default in compose and no line there**
  (`SPECTRALLM_DIR`, `SPECTRA_JAVA_OPTS`, `LLM_EMBED_MODEL_NAME`, `LLM_EMBED_PARALLEL`,
  `LLM_EMBED_EXTRA_ARGS`), and nothing noticed, because `check-config-table.py` resolves
  `application.yml` and the Dockerfiles and never reads a compose file. The reverse is checked
  too: a documented knob no stack reads invites an operator to set a value that changes
  nothing. Defaults are compared against **the set the stacks use** rather than one value, so
  an overlay that changes one deliberately (`LLM_EMBED_EXTRA_ARGS` is empty in the base and
  `--n-gpu-layers 99` in the GPU one) is not a finding, while the ordinary single-default case
  stays an exact comparison. The interpolation scan is hand-rolled rather than a regex for two
  reasons a regex gets wrong: `$${…}` is a literal `$` for a shell inside an entrypoint and is
  not an interpolation at all, and a default can itself be one (`${A:-${B:-c}}`), which
  `[^}]*` truncates at the first brace and attributes to the wrong variable. **And the third
  pass is not documentation**: it asserts that `PROCESS_MINING_PROMPT_CHAR_BUDGET` fits the
  window the stack serves — the whole context for Ollama, the context divided by
  `--parallel` slots for llama.cpp. Those two halves are written in three files, each with a
  comment saying it is "kept in step" with the others, and nothing executes a comment; a prompt
  that exceeds the window is dropped in silence and logged at DEBUG rather than refused. The
  4 characters-per-token ratio is deliberately optimistic, so a budget it passes may still not
  fit while one it rejects certainly does not — a floor, not a calibration.
- **The published-images stack is booted too** (`spectra-hub-stack`), and **on a pull request
  that touches it**. It used to run on main and `workflow_dispatch` only — it pulls ~2 GB to test
  a deployment file whose content does not move with the code, the same trade-off as the arm64
  boot — and the cost of that was measured rather than guessed: **five of six consecutive `main`
  runs were red on this job**, every failure found *after* a merge, each costing a merge, a fix
  and a second merge. The trade is right for every pull request and wrong for the handful that
  edit those files, so a `hub-changes` job makes the distinction from a plain `git diff`. It is a
  job with an `if:` and deliberately **not** a `paths:` filter: `paths:` makes a job *skip*, and
  a required check that skips blocks a merge for ever, where a job whose `if:` is false reports
  success. The file list includes `ci.yml` (this job is defined there), the seeders and
  `.env.example` (the stack mounts and reads them). **The model fetch is not allowed to redden
  `main` on somebody else's outage**: it reaches `huggingface.co`, and a red default branch
  meaning "HuggingFace was unavailable" is indistinguishable from one meaning "the stack broke".
  A failed *transfer* warns and skips the end-to-end assertion; every other failure of the
  fetcher still fails the job — which is why the outcome is read from the fetcher's own message
  and not from its exit code, a mismatched digest being a substituted file rather than a network
  problem. The same rule `check-image-pins.py --published` already applies to the registry: "we
  asked and it is stale" and "we could not ask" are different answers. The CI model is **pinned
  by digest** (`CI_CHAT_MODEL_SHA256`, observed on two independent downloads before being
  written down), which also stops the fetcher's verification branch from being code CI never
  runs. It runs with
  `SPECTRA_AUTO_INSTALL_MODELS=false`, because the interesting assertion about a missing model is
  that the containers **wait** for it rather than crash-looping — which is what the inline
  entrypoints exist for. It also pins the wiring nothing else can: that `GET /api/config` really
  reports `SPECTRA` and `http://spectra-api:8080` (so a renamed variable fails here, not in
  production), and that the UI reaches the API through nginx's `/api/` proxy — whose upstream is
  baked into the published image, which is what forces the service to keep the name `spectra-api`.
  It then drops a **0.5B model** into the volume — through the stack's own `spectra-models`
  one-shot, so the fetcher is exercised rather than bypassed — and asserts that
  `POST /api/config/test-llm` answers `ok`. That is the assertion the job existed without: a
  Process Mining call really travelling explorer → spectra-api → llm-chat and coming back, which
  is what would have caught the `X-API-Key` / `Bearer` mismatch this stack documents instead of
  leaving it a paragraph nobody executes.
- **CI runs the stack, it does not merely build it.** The `docker` job starts `docker-compose.yml` over the image it just built (`compose/ci.yml` supplies it) and asserts the deployment contract: the container reaches `healthy`, both probes answer UP, `/api/dashboard` responds, the process runs as uid 10001, `/app/logs/kafkaexplorer.log` is non-empty, a second seeding run skips, and the app's exit code after `stop` is not 137 (SIGKILL). Each assertion corresponds to a bug that lived here for months precisely because nothing ever ran these files. A second job, `release-image`, builds `Dockerfile.release` from the `build` job's JAR and boots it with no broker — that file used to be exercised for the first time by the release itself. **That job is also where the startup audit's findings are guarded**, because it is the one place in CI that runs the app with *no broker* — the `docker` job's stack has a healthy one, so the failure mode measured there cannot occur in it. After liveness it holds the container for a fixed 30 s window and asserts three things: the log stays under **5 000 lines** (the flood was ~2 300 lines a second from a single class, so a return of it is ~70 000 in that window — the ceiling sits more than tenfold from both, deliberately, because a gate that flakes is a gate people learn to ignore); the startup summary names the broker that did not answer; and each of the two state restores reports itself, naming its topic, since both used to fail at DEBUG and therefore silently.

## Typical local dev workflow

1. `docker compose up -d kafka` — the broker alone. The base file is enough: it carries the broker,
   and layering `compose/schema-registry.yml` here changed nothing, since that overlay adds services
   rather than touching `kafka`. Add `schema-registry` to the service list if you are working on Avro.
2. `./mvnw spring-boot:run` — start backend on port 8080
3. `cd src/main/webapp && npm run dev` — start frontend dev server (port 5173)


## Docker : GHCR & Docker Hub

- Image publiée sur **`ghcr.io/devdownin/kafkaexplorer`** *et* **`docker.io/compagnonsdudev/kafkaexplorer`** via `.github/workflows/release.yml` au push d'un tag `v*`. **Un seul build** alimente les deux : `metadata-action` reçoit les deux noms dans `images`, `build-push-action` pousse les manifests aux deux registres. Un second `build-push-action` recompilerait l'image, et deux builds peuvent diverger.
- **Les deux namespaces diffèrent volontairement** : GHCR suit l'organisation GitHub (`devdownin`, imposé — `ghcr.io/${{ github.repository }}`), Docker Hub suit le compte Docker Hub (`compagnonsdudev`, qui est le propriétaire du `DOCKERHUB_USERNAME`). Un push vers `docker.io/<ns>/…` n'est autorisé que si `<ns>` est le compte connecté ou une organisation dont il est membre : changer le nom du dépôt sans changer le compte donne un 403 à l'étape de push, pas au login.
- Docker Hub est **optionnel et détecté**, pas supposé : le contexte `secrets` n'existe pas dans un `if` de job, donc l'étape `Is Docker Hub configured?` pose une fois `steps.hub.outputs.enabled` (à partir de `DOCKERHUB_USERNAME` + `DOCKERHUB_TOKEN`, passés par `env` — les interpoler dans le script les mettrait dans la ligne de commande), et le nom `docker.io/…` porte `enable=` sur cette sortie. Un fork, ou ce dépôt avant que les secrets existent, publie sur GHCR seul au lieu d'échouer sur un login qu'il n'a jamais demandé. Le dépôt Docker Hub vient de la variable `vars.DOCKERHUB_REPOSITORY` (défaut `compagnonsdudev/kafkaexplorer`), donc un fork republie sous son propre namespace sans toucher au fichier.
- Le job `docker` dépend du job `build`, récupère le JAR via `actions/upload-artifact` / `actions/download-artifact` et le copie dans `Dockerfile.release` : l'image publiée contient exactement le JAR attaché à la Release, et n'est pas une seconde compilation non testée.
- Tags générés : `{{version}}` (ex: `0.0.3`), `{{major}}.{{minor}}`, `latest` (jamais sur une pré-version — le garde est le tiret dans le nom du tag). Plateformes : `linux/amd64,linux/arm64`.
- **Les labels ne suffisent pas en multi-arch : il faut les annotations.** Un `labels:` est écrit dans la *config* de chaque image de plateforme, et un push multi-arch publie un **index** par-dessus, qui n'en porte aucun — or GHCR lit `org.opencontainers.image.source` sur l'index pour rattacher le package au dépôt. La sortie `annotations` de `metadata-action` existait et n'était pas branchée ; elle est maintenant générée en `manifest,index` (`DOCKER_METADATA_ANNOTATIONS_LEVELS`, en `env` sur l'étape `meta`) et passée à `build-push-action`. Les deux niveaux, parce que le registre décide lequel il lit et que cette image part vers deux registres.
- **`provenance` et `sbom` sont déclarés, pas laissés au défaut de buildx** (`mode=max` + `true`). Un push multi-arch embarque des attestations dans tous les cas ; ce qui restait non choisi, c'est leur contenu. Le coût est visible et assumé : une attestation voyage comme un manifeste supplémentaire dans l'index, que Docker Hub et GHCR affichent tous deux comme une plateforme **`unknown/unknown`** à côté de amd64 et arm64 — ce qui ressemble à un build cassé pour qui lit la liste des tags, d'où le paragraphe qui l'explique dans `docs/DOCKERHUB.md`. Inverser l'arbitrage, c'est `provenance: false` + `sbom: false` sur ces deux lignes et rien d'autre.
- **Le digest publié est ajouté aux notes de la Release** (`append_body`, d'où le `contents: write` sur le job `docker`). La Release portait `SHA256SUMS.txt` pour le JAR — le seul fichier vérifiable — pendant que l'image, l'artefact que la plupart des gens consomment réellement, n'offrait rien à épingler. Un tag est mutable : *rien ne le republie ici* est une promesse sur notre comportement, pas une propriété. Le digest en est une, et c'est ce que la page recommande en production.
- **`linux/arm64` est vérifié aux deux bouts, et pas au même endroit par hasard.** `platforms:` était une demande que rien ne contrôlait, alors que la page vend l'arm64 comme tournant nativement. `release.yml` se contente d'affirmer que la plateforme est **présente** dans l'index publié (`buildx imagetools inspect --raw` + `jq`, en filtrant les manifestes `unknown` des attestations) ; c'est `ci.yml` qui la **démarre**, sous QEMU, en exigeant liveness + `aarch64` + uid 10001. Dans cet ordre parce qu'au moment où un job de release pourrait sonder une image, elle est déjà poussée : l'information n'est plus actionnable. La CI, elle, échoue avant que le tag existe. Pas sur les pull requests (une JVM sous émulation prend des minutes, et cet étage ne bouge qu'avec un digest de base) — mesuré à ~110 s au premier run, pour un budget de 900 s.
- **`docs/DOCKERHUB.md` EST la page de présentation Docker Hub**, poussée par `.github/workflows/dockerhub-description.yml` (push sur `main` touchant ce fichier, ou `workflow_dispatch`). Éditer la page sur hub.docker.com ne sert à rien : la synchro suivante l'écrase. Deux contraintes propres à ce fichier, absentes du README : Docker Hub le rend hors du dépôt, donc **tous les liens doivent être absolus**, et aucune image du dépôt n'y est atteignable (seul shields.io l'est). Le workflow est délibérément séparé de `release.yml` — corriger une phrase de la page ne doit pas demander de couper une version. `enable-url-completion` est **désactivé** : l'option réécrit les liens relatifs en absolus, ce dont cette page n'a aucun besoin (ils sont déjà tous absolus, et `check-links.py` casse le build sinon), pendant que ses modes de défaillance documentés sont le code inline et les liens contenant des crochets — dont la page est presque entièrement faite.
- **Diagnostiquer un échec de la synchro par l'étape où il tombe**, parce qu'ils ne veulent pas dire la même chose et que ce fichier a longtemps annoncé le mauvais. Un **404** à la pose de la description signifie que le dépôt Docker Hub n'existe pas encore : il est créé par le premier `docker push`, donc par la première release. Un **401 à l'étape `Acquiring token`** est antérieur à tout ça — c'est le login lui-même qui est refusé, et le dépôt peut très bien exister. Dans ce cas les deux secrets sont forcément présents (sans quoi le garde `Is Docker Hub configured?` aurait sauté l'étape), et les causes sont : un PAT read-only (voir plus bas), un `DOCKERHUB_USERNAME` qui n'est pas le compte de login (une organisation, ou le namespace du dépôt), un mot de passe au lieu d'un PAT sur un compte à 2FA, ou une espace/un retour ligne collé dans le secret. Ne pas consigner ici l'état du jour : la première rédaction de ce paragraphe affirmait « le dépôt est vide, aucune release n'a jamais tourné, la page n'est pas en ligne », et les trois étaient faux le soir même (`v1.5.0` publiée, trois tags sur Docker Hub, page synchronisée). Le diagnostic se périme moins vite que le constat.
- **Un token Docker Hub invalide fait tomber la publication GHCR avec lui** — c'est ce qui a tué la release `v1.4.0`, et c'est le contraire de ce que le garde `Is Docker Hub configured?` est censé garantir. Le job se déroule ainsi : `Logging into ghcr.io... Login Succeeded!`, puis `Logging into docker.io... unauthorized: incorrect username or password`, et le job s'arrête **avant de construire quoi que ce soit** — aucune image nulle part, alors que GHCR était parfaitement disponible. Le garde ne vérifie que la *présence* des deux secrets, pas qu'ils fonctionnent : un secret présent mais périmé passe le test et casse tout. Un registre optionnel ne doit pas pouvoir faire échouer un registre obligatoire. **C'est corrigé** : l'étape `Log in to Docker Hub` porte `continue-on-error: true`, une étape `Report a refused Docker Hub login` émet un `::warning::` nommant les causes probables, et le nom `docker.io/…` de `metadata-action` est conditionné à `steps.hub_login.outcome == 'success'` — donc un login refusé (comme un login sauté faute de secrets) sort simplement le nom de la liste et le build pousse vers GHCR seul. `outcome` et non `conclusion` : `conclusion` vaut `success` pour une étape en `continue-on-error` qui vient d'échouer. Le garde `Is Docker Hub configured?` reste utile mais ne prouve que la *présence* des secrets ; c'est l'`outcome` du login qui prouve qu'ils fonctionnent.
- **Le cache de build est scopé par Dockerfile** (`type=gha,scope=source-image` pour le `Dockerfile` multi-stage, `scope=release-image` pour `Dockerfile.release`). `type=gha` n'a qu'une portée par défaut : deux jeux de couches sans rapport — dont l'un porte le dépôt Maven et `node_modules` — réécrivaient tour à tour le même manifeste de cache, chaque export `mode=max` invalidant celui de l'autre. `mode=max` n'a de sens que pour le build depuis les sources (ce sont les étapes builder qui coûtent : `npm ci`, `go-offline`, `mvn package`) ; `Dockerfile.release` est en `mode=min`, son unique étape builder étant de toute façon invalidée par chaque nouveau JAR.
- **`release.yml` ne fait que *lire* ce cache** (`cache-from` seul) : il est alimenté par `ci.yml`, qui construit les deux mêmes fichiers à chaque run. GitHub rend les caches de la branche par défaut lisibles depuis n'importe quelle ref, donc un push sur `main` laisse le build d'un tag déjà chaud — y compris le `RUN adduser` de l'étage runtime, que `release.yml` rejouerait sinon sous QEMU pour la variante arm64. Une ref qui build deux fois par an n'a pas à réécrire un cache que la CI entretient.
- Le job `release-image` de `ci.yml` passe par Buildx et non plus par un `docker build` nu : le driver `docker` par défaut ne sait exporter aucun cache, et c'était la seule image du dépôt reconstruite à froid à chaque fois.
- **`Dockerfile.release.dockerignore`** réduit le contexte à `app.jar`, le seul fichier que ce Dockerfile copie. BuildKit lit `<dockerfile>.dockerignore` **à la place** du `.dockerignore` racine (il ne s'y ajoute pas), d'où le `*` + `!app.jar` plutôt qu'une liste d'exclusions. Ça corrige surtout un doublon : CI et `release.yml` téléchargent l'artefact dans `dist/` puis le copient en `app.jar`, donc les mêmes centaines de mégaoctets voyageaient deux fois dans chaque contexte (`dist/` est aussi entré dans le `.dockerignore` racine, pour un Docker antérieur à cette fonctionnalité — où la dégradation est simplement l'absence de gain, pas une casse).
- Coût assumé, pas un oubli : le job `docker` de `ci.yml` recompile tout depuis les sources alors que le job `build` vient de le faire via `mvn verify`. C'est ce qui garantit que le `Dockerfile` multi-stage ne pourrit pas — la rupture `"/app/dist": not found` a vécu jusqu'à un tag précisément parce que rien ne construisait cette image.
- **`release.yml` valide le tag avant de construire quoi que ce soit** (job `guard`). Trois échecs muets sont sortis de ce seul mécanisme : un tag en majuscule (`V1.3`) ne correspond à aucun filtre — ils sont sensibles à la casse — donc il ne déclenche *rien*, en silence ; un tag à deux composantes (`v1.1`, `v1.2`) n'est pas du semver, donc `metadata-action` n'émet aucun tag de version et l'image ne sort qu'en `latest`, ce qu'on découvre des mois plus tard en tirant `:1.2` ; et l'historique a dérivé (`0.0.1`, `0.0.2`, `v0.0.3`, `v0.1.0`, `v1.1`, `v1.2`, `V1.3`). Le déclencheur inclut donc `V*` **pour que le garde puisse refuser** un tag majuscule au lieu de l'ignorer, et le message d'erreur nomme lequel des trois cas s'applique.
- **La release ouvre la PR qui remet le pin de la stack hub à jour.** `compose/spectra-hub.yml` nomme l'image de l'Explorer à la main (`${EXPLORER_IMAGE_TAG:-…}`), Dependabot ne sait pas lire cette forme, et le pin devient périmé à l'instant précis où le job `docker` publie — le seul endroit du dépôt qui sache qu'une version vient d'exister. Laissé à la main, ce n'est pas la release qui s'en aperçoit : c'est **la pull request suivante qui touche un fichier surveillé par `hub-changes`** et qui échoue sur `check-image-pins.py --published`, à propos de quelque chose qu'elle n'a pas changé — arrivé deux fois en une journée, sur deux branches sans rapport. L'étape ouvre donc une **pull request**, jamais un push sur `main` : toutes les entrées de `main` sont des merge commits relus, et cette PR touche les fichiers de la stack, donc `hub-changes` déclenche `spectra-hub-stack` et la nouvelle valeur est vérifiée **contre le registre** avant d'être fusionnée — le mécanisme qui signale le problème est celui qui valide la correction. Trois garde-fous : elle ne s'exécute que sur un tag **stable** (même règle que `latest` — une pré-version ne déplace pas le pin de la stack que les gens exécutent), elle est **best-effort** (`continue-on-error` : l'image est déjà publiée, une release ne doit pas être déclarée en échec parce qu'un suivi n'a pas pu s'ouvrir), et elle est écrite avec `git` et `gh` plutôt qu'une action tierce — c'est le chemin de release, qui porte déjà `contents: write` et un login registre. Elle a besoin de `pull-requests: write` sur le job et du réglage « Allow GitHub Actions to create and approve pull requests » ; s'il manque, la branche est poussée et un `::warning::` nomme le réglage.
- **Le JAR publié porte la version du tag**, pas celle du pom : `versions:set -DgenerateBackupPoms=false` réécrit la version dans la copie du runner avant le `verify`, sans commit. Le pom du dépôt reste en `0.0.1-SNAPSHOT` — c'est la version de développement et couper une release ne doit pas demander de la bousculer — mais une Release taguée `v1.1` attachait `kafka-sql-explorer-0.0.1-SNAPSHOT.jar` : le seul fichier que l'utilisateur télécharge n'avait aucun rapport avec la version sur laquelle il venait de cliquer.
- Secrets/variables attendus (Settings → Secrets and variables → Actions) : secret `DOCKERHUB_USERNAME` (le compte, pas l'organisation), secret `DOCKERHUB_TOKEN` (**PAT scope Read, Write, Delete** — l'API de description refuse un token read-only, et l'échec ressemble à un 401 sur un token pourtant valide : c'est le premier suspect quand la synchro tombe en 401, voir le diagnostic plus haut), variable `DOCKERHUB_REPOSITORY` (optionnelle).
- Lancement local : `docker run -p 127.0.0.1:8080:8080 -e KAFKA_BOOTSTRAP_SERVERS=localhost:9092 -e ANTHROPIC_API_KEY=sk-ant-... compagnonsdudev/kafkaexplorer:latest`. La variable est `KAFKA_BOOTSTRAP_SERVERS`, pas `SPRING_KAFKA_BOOTSTRAP_SERVERS` : le préfixe de configuration est `kafka.`, pas `spring.kafka.` (le projet utilise `kafka-clients` directement, pas `spring-kafka`), donc l'ancienne forme ne se liait à rien et l'app restait sur `localhost:9092`. Publier sur la loopback : l'app n'a **aucune authentification** et `POST /api/config` repointe le cluster à chaud.
