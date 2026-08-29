# CLAUDE.md

Guidance for Claude Code (claude.ai/code) working in this repository.

## What This App Does

Kafka SQL Explorer is a Spring Boot + React web application for exploring Kafka clusters and
executing Flink SQL queries against Kafka topics. It provides schema inference, message
navigation, lineage tracking, stream flow tracing, cluster auditing and LLM-assisted process
mining.

## Deep-dive notes — read the relevant one before changing that area

This file is the map. The rationale lives in `docs/notes/`, because almost every odd-looking
choice in this codebase is a fix and the paragraph beside it is the defect it closes. Changing
one of these areas without reading its note is how a correction gets un-corrected.

| Note | Covers |
|---|---|
| `docs/notes/backend-services.md` | Every service in `service/`: Flink engine, Kafka admin reads, audit, process mining, LLM clients, metrics, stores |
| `docs/notes/frontend.md` | The SPA: app shell, design system, form conventions, and the rationale for each page |
| `docs/notes/docker-and-stacks.md` | Compose stacks and their overlays, both Dockerfiles, GHCR / Docker Hub publishing |
| `docs/notes/ci-and-checks.md` | Workflows, the `docs/check-*.py` family, testing strategy, governance and supply chain |
| `docs/notes/configuration-and-routing.md` | Every `explorer.*` / `claude.*` / `process-mining.*` knob, and the SPA routing rules |
| `docs/notes/audits.md` | What each root `*-AUDIT.md` / `*-SCOPE.md` report concluded and left open |

The audit reports themselves are at the root: `AUDIT-FEATURE-REVIEW.md`, `DOCKER-AUDIT.md`,
`FLINK-JOBS-AUDIT.md`, `METRICS-TWO-QUERY-AUDIT.md`, `MOBILE-LAYOUT-SCOPE.md`,
`PROCESS-MINING-LLM-SCOPE.md`, `PROCESS-MINING-LLM-CALLS-AUDIT.md`, `SQL-EDITOR-AUDIT.md`,
`CODE-SIMPLIFICATION-AUDIT.md`.

## Commands

### Backend (Maven / Spring Boot)

```bash
mvn verify                       # The complete gate: Java tests + ESLint + Vitest. What CI runs.
mvn test                         # Java tests only — a fast backend loop, no npm involved
mvn test -Dtest=AuditServiceTest # A single test class
./mvnw spring-boot:run           # Run the app
mvn clean package                # Full build (frontend included via frontend-maven-plugin)
mvn clean package -DskipTests    # No tests; never reaches verify, so the frontend checks are skipped too

# The Process Mining eval against a real model — excluded from every other command on purpose
CLAUDE_PROVIDER=OPENROUTER OPENROUTER_API_KEY=sk-... CLAUDE_MODEL=openai/gpt-4o-mini \
  ./mvnw test -P llm-eval
```

**`mvn verify` is the only command that runs everything.** `npm run lint` and `npm test` bind to
the `verify` phase of the `build-frontend` profile — deliberately not to `test`, which keeps
`mvn test -Dtest=SomeClass` a Java-only loop and leaves `mvn package -DskipTests` untouched.
`mvn verify -DskipTests` skips both suites.

A Maven wrapper is checked in (`./mvnw`, `distributionType=only-script`, so no wrapper JAR in the
tree). Both CI workflows build through it.

### When `packages.confluent.io` is blocked

`io.confluent:kafka-avro-serializer` and `io.confluent:kafka-schema-registry-client` are published
**only** there, not on Maven Central, and `flink-avro-confluent-registry` pulls the schema-registry
client transitively — so behind a proxy that blocks that host, Maven cannot even *collect* the
dependency graph and every goal fails before compiling anything.

`./verify-offline.sh` restores a local compile-and-test loop: it resolves from a Confluent-free
pom, stubs the five Confluent types the code touches, compiles with `javac` and runs the suite
through the JUnit console launcher. It accepts extra ConsoleLauncher arguments —
`./verify-offline.sh "--include-classname=.*LineageServiceTest"`. Use `--include-classname` (a
filter), **not** `--select-class`: the script always passes `--scan-classpath`, and the JUnit 6
launcher refuses both at once.

Two things to know: Avro / Schema Registry paths run against stubs, so those results are
indicative only; and the launcher must be started as
`java -cp … org.junit.platform.console.ConsoleLauncher`, **never** `java -jar` — with `-jar` the
system classpath holds only the launcher, Flink's job-graph deserialization cannot find
`flink-table-runtime`, every SELECT fails to submit and a dozen `FlinkSqlServiceTest` cases fail
for no real reason. CI is the authority: it builds against the real Confluent jars.

### Frontend (React / Vite)

```bash
cd src/main/webapp
npm install
npm run dev     # Dev server, proxies /api to localhost:8080
npm run build   # tsc + vite → src/main/resources/static/
npm run lint    # ESLint (flat config, --max-warnings 0)
npm test        # Vitest (jsdom + @testing-library/react); test:watch for watch mode
```

### Docker

All bundled stacks run **Kafka 4.3 in KRaft mode** (`apache/kafka:4.3.1`, single combined node, no
Zookeeper anywhere). There is **one base file at the repository root** and everything else in
`compose/` is an overlay or a standalone stack.

```bash
docker compose -f docker-compose.yml -f compose/schema-registry.yml up -d  # + Schema Registry
docker compose up -d                                                       # base: broker + app + demo topics
./setup-demo.sh localhost:9092                                             # demo data (79 topics)
./setup-demo-avro.sh localhost:9092 http://localhost:8081                  # Avro topics (needs Schema Registry)
```

Building without a local toolchain — always `run --rm`, these are one-shot services, never `up`:

```bash
docker compose -f compose/build.yml run --rm verify    # the full gate
docker compose -f compose/build.yml run --rm package   # JAR into ./target, no tests
docker compose -f compose/build.yml run --rm frontend  # ESLint + Vitest only, no JVM
docker compose -f compose/build.yml run --rm shell     # interactive toolchain
```

`compose/dev.yml` is the hot-reload stack (Kafka + `spring-boot:run` + Vite).
See `docs/notes/docker-and-stacks.md` for what is load-bearing in each of them.

### Typical local dev workflow

1. `docker compose up -d kafka` — the broker alone (add `schema-registry` when working on Avro).
2. `./mvnw spring-boot:run` — backend on port 8080.
3. `cd src/main/webapp && npm run dev` — frontend dev server on port 5173.

## Architecture

### Stack

- **Backend**: Spring Boot 4.1.x, **Java 25** (`java.version` in pom.xml, pinned by
  `requireJavaVersion` in the enforcer plugin), embedded Apache Flink 2.3.x (`flink.version`).
  Kafka connector `flink-connector-kafka:5.0.0-2.2` — the suffix names the **Flink minor the
  connector was built against**, not a range, and no `-2.3` build is published yet. Check Maven
  Central before assuming a bump exists.
- **Frontend**: React 19, TypeScript, Vite, Tailwind CSS, Monaco Editor.
- **Kafka**: `kafka-clients` 4.3.1 (`kafka.version` override in pom.xml, which wins over Spring
  Boot's managed version *and* over what `io.confluent` 8.3.1 pulls transitively — one client
  version across the whole build, never two). Compatible with Kafka 2.1+ brokers on the classic
  protocol. The live Process Mining consumer speaks the KIP-848 protocol
  (`kafka.consumer-group-protocol`, shipped as `consumer`), which **needs a Kafka 4.x broker**;
  `KafkaConfig` falls back to `classic` when the property is absent, so the code default stays the
  compatible one.
- **Build**: single JAR — `frontend-maven-plugin` builds the SPA into `src/main/resources/static/`.

Two JVM warnings are expected on Java 25 and are **not** ours to fix: Flink's shaded Guava calls
`sun.misc.Unsafe::objectFieldOffset` (a Flink-version problem, not a flag to add here), and under
the offline harness Testcontainers' JNA calls a restricted `System::load` (test-only). No
`--add-opens` is added: a flag added pre-emptively outlives the reason for it.

### Backend layers

```
web/          REST controllers (one per domain: Query, Topic, Audit, Lineage, …)
service/      Business logic — FlinkSqlService is the core engine
config/       Spring configuration (Kafka, Flink, Explorer settings, CORS)
domain/       DTOs — Java Records
parser/       JSON, XML and Avro (via Confluent Schema Registry) schema inference
```

Core services, one line each — the full rationale is in `docs/notes/backend-services.md`:

- `FlinkSqlService` — executes SQL against Kafka topics on an embedded Flink `LocalEnvironment`.
  SELECT runs through the real planner and falls back to `kafkaDirectSelect()` only on an *engine*
  failure; a user error is returned as the planner's own message. Engine used is reported in
  `QueryResult.engine()`.
- `FlinkRuntimeCoordinator` — one fair read/write lock plus a single mutation thread in front of
  the runtime. Every step takes a wait budget; nothing waits without a deadline.
- `FlinkWarmupService`, `FlinkJobStore`, `FlinkTableStore` / `FlinkTableRestore` — warmup probe,
  job history across restarts, and replay of hand-written `CREATE TABLE` statements.
- `KafkaAdminService` — AdminClient wrapper for metadata, record reads and topic activity.
- `TopicSearchService` + `MessageMatcher` — the bounded server-side scan and the predicate behind
  *every* search (topic search and stream-flow trace alike).
- `SchemaInferenceService`, `DdlGeneratorService` — schema inference and generated Flink DDL.
- `AuditService`, `AuditHistoryService`, `AuditDiffService` — cluster health checks, their history
  in `internal.audit.history`, and run-to-run comparison.
- `DataModelService`, `LineageService`, `StreamFlowService` — deduced data model, dependency graph
  and cross-topic key tracing.
- `PayloadDigestService`, `ProcessModelBuilder`, `LlmAnalysisService`, `FieldProfilingService`,
  `KafkaSnapshotReader`, `KafkaLiveConsumer` — the Process Mining pipeline.
- `LlmClientProvider` + the four clients (Anthropic, OpenAI-compatible, Ollama/OpenRouter,
  SpectraLLM) — `LlmClientProvider` is the **only** way to obtain an `LlmClient`.
- `MetricService`, `MetricSuggestionService`, `ConsumerLagMetrics`, `KraftQuorumMetrics` —
  Prometheus metrics and the KPIs derived from what this cluster was observed to do.
- `SettingsStore` + `StoredSettingsInitializer` — what the Settings page enters, kept across a
  restart in `data/settings.json`, with the environment still outranking it.

Shared single-definition helpers — never write a second copy of any of these:

- `SecureXml` — the **only** place in the tree that configures an XML parser. XXE hardening has to
  hold at every entry point; five copies meant five chances to write seven of the eight lines.
- `LogSafe` — the only place a value is neutralised before being logged. Three functions, because
  what this application logs has no single legal alphabet: `name()` (strict allow-list, for a
  topic / table / session id), `slug()` (adds `/` and `:`, for a model identifier) and `text()`
  (flattens control characters, for free text whose readability is the point).
- `EventTime` — the one place a field value resolves to epoch millis (below 10^10 is seconds, then
  ISO-8601, then a space-separated local date-time read as UTC; `null` means *unresolvable*).
- `ShutdownBudget` — one deadline shared by every executor pool, not five seconds each. Use it for
  any new pool; do not reintroduce a private `awaitTermination`.
- `JsonStoreFile` — read/write for the file-backed stores, so a crash mid-write costs one record
  rather than the file.
- `TopicReadCursor` — a consumption cursor seeded from where a read seeked and advanced only by
  records it was handed. **Never steer by `consumer.position()`**: that is the client's *fetch*
  position, advanced as responses are buffered rather than as records are returned, and reading it
  as a watermark is a defect that has been found and fixed four separate times here.
- `ExplorerConsumerGroups` — the only way an internal consumer is named, and where
  `enable.auto.commit=false` and `allow.auto.create.topics=false` are set together.

### Frontend

The SPA lives in `src/main/webapp/src/`. React 19 + TypeScript + Vite + Tailwind, Monaco (SQL
editor), Recharts (charts), Mermaid (process-mining flowcharts), `lucide-react` + Material Symbols,
`axios`.

- `App.tsx` is a **data router** (`createBrowserRouter`), not `<BrowserRouter>` — `useBlocker` only
  exists on a data router, and it is the single point where an internal navigation can be
  intercepted before it happens.
- `navigation.ts` is the single source of truth for screens. Add a page there, not in each
  component.
- `components/ui/` is the design system — prefer it for any new surface.
- `draftStore.ts` keeps typed-but-unsubmitted state across navigation, in a versioned envelope
  with a seven-day expiry. `Config` never drafts a secret.
- Pure logic lives in `pages/*.ts` / `components/**/*.ts` modules beside the components, and is
  unit-tested. Logic in a `.tsx` is logic that needs React mounted to be tested.

Page-by-page rationale, the form conventions and the graph-viewport rules are in
`docs/notes/frontend.md`.

## Rules that a change can break silently

These are the ones that cost the most when re-broken. Each is expanded in the relevant note.

- **Never map a controller onto a client-side route.** There is no template engine, so a
  `@GetMapping("/stream-flow")` returning a view name shadows `SpaController` and answers a page
  refresh with a circular-view-path 500. Same for `/audit`, `/config`, `/compare`, `/help`,
  `/table/{name}` — all removed for exactly this. Domain controllers belong under `/api/**`.
- **An endpoint nobody calls is an entry point nobody guards.** Two were deleted on this rule
  (`TableController`, `POST /api/metrics/preview`), and their absence is pinned by tests.
- **Every `@PathVariable` / `@RequestParam` in `web/` carries an explicit name.** `verify-offline.sh`
  compiles with plain `javac` and passes no `-parameters`, so an unnamed one fails at *request*
  time under that harness and never at compile time.
- **Boxed components on any record Jackson binds from a request body.** A record is bound through
  its canonical constructor, so an absent property arrives as `null` and a primitive component
  fails the whole request. `StreamFlowRequest` and `AuditOptions` were both fixed for this.
- **A measurement that could not be taken is never a zero.** Zero members, zero lag, zero messages,
  zero groups are all *answers*, and rendering an unread value as one manufactures critical audit
  findings, "caught up" Prometheus gauges and confident sentences in the UI. Carry the third state
  (`available: false`, `null`, an explicit reason) and say which of the answers it is.
- **Generated or `SHOW CREATE TABLE` DDL is masked at every exit**, log lines included —
  `DdlGeneratorService.maskSensitiveProperties()`. The DDL embeds Kafka client properties,
  including SSL passwords and the Confluent `sasl.jaas.config` secret.
- **A bounded scan says what it covered.** Every search, trace, audit and snapshot reports what it
  read, what it skipped and why it stopped; an empty result must read as "not in the window I
  scanned", never as a bare "not found".
- **Do not reintroduce `@Async` on `AuditService.startAudit`** — the self-invocation bypasses the
  Spring proxy and blocks the HTTP thread. It submits to its executor explicitly.
- **The app depends on the broker only** in every compose stack. Keep the seeder beside the app,
  not in front of it.
- The doc checks **expire their own exemption lists**: an entry nothing cites any more fails the
  build. When prose stops naming a path or a version, prune the matching entry in
  `docs/check-doc-paths.py` / `docs/check-config-table.py`.

## Configuration

`src/main/resources/application.yml` is the source of truth. The prefixes:

- `kafka.*` — bootstrap servers (default `localhost:9092`), connection mode (`PLAIN` / `SSL` /
  `CONFLUENT_CLOUD`), consumer group protocol.
- `claude.*` (`ClaudeConfig` — the prefix is historical, the provider is a choice) — provider
  (`ANTHROPIC` / `OPENAI_COMPATIBLE` / `OLLAMA` / `OPENROUTER` / `SPECTRA`, **default
  `OPENROUTER`**), model, base URL, API key, token and timeout budgets, structured-output mode and
  the three OpenRouter routing knobs.
- `process-mining.*` (`ProcessMiningConfig`) — consumer fetch sizing, payload digestion, prompt
  budgeting and the measured process.
- `explorer.*` (`ExplorerConfig`) — query and inference timeouts, audit budgets, topic-search
  budgets, consumer-lag and activity bounds, internal topic and consumer-group prefixes, cache TTL,
  and the two persistence stores.
- `management.endpoints.web.exposure.include: health,info,prometheus` — `/actuator/prometheus`.
- Logging: the application package defaults to INFO; `AdminMetadataManager` is pinned to **WARN**
  (measured at 98.2 % of a boot's log volume when the broker does not answer) and
  `logging.logback.rollingpolicy.total-size-cap` is 200MB, Spring Boot's default being no cap.

Every knob, its default and why that default, is in `docs/notes/configuration-and-routing.md`.
Documented variables are checked against the YAML, the config classes and the Dockerfiles by
`docs/check-config-table.py`.

## Testing

JUnit 5 + Mockito. Unit tests mock Kafka and Flink — no broker needed. `ApplicationContextTest`
uses `@SpringBootTest` with `DynamicPropertySource`. Test classes live in
`src/test/java/com/compagnonsdudev/kafkasqlexplorer/`.

- **A defect produced by the client's own behaviour belongs in the integration suite.**
  `KafkaClusterIntegrationTest` runs a real Kafka 4.3 broker through Testcontainers, because
  `MockConsumer` emulates neither an out-of-range seek, nor the `auto.offset.reset` that follows
  one, nor background prefetch — and all three produced shipped defects. A mock that cannot fail
  is not coverage.
- **Every test that describes a defect was verified to fail against the revision it describes**
  before the fix landed. Keep that discipline: a regression test that passes on the broken code
  documents nothing.
- The frontend suite is Vitest on jsdom (`src/test/setup.ts` stubs `scrollIntoView`,
  `ResizeObserver` and `matchMedia` — the last answers *false*, so the narrow layout is what the
  suite exercises).
- The Process Mining eval (`eval/`) has two halves: `ProcessModelEvalTest` asserts the aggregate
  exactly and runs in `mvn verify`; `LlmAnalysisEvalTest` calls a real model, is tagged
  `llm-eval`, is excluded from every other command, and **skips rather than fails** when nothing
  is configured.

More in `docs/notes/ci-and-checks.md`.

## Security Notes

- **SQL injection**: `FlinkSqlService.executeSql` whitelists `SELECT`, `EXPLAIN` and
  `CREATE TABLE`; `SqlQueryValidator` is the cross-join and system-table guard on top of it, not
  the whitelist itself.
- **XXE**: every XML parser comes from `SecureXml`, with external DTD loading disabled.
- **Credential masking**: see the rule above — every DDL exit, logs included.
- **No authentication** out of the box; intended for internal, controlled environments.
  `POST /api/config` repoints Kafka and LLM settings at runtime, so protect the app before
  exposing it beyond a trusted network. `SECURITY.md` states this as a deployment constraint.
- **Never put a real key in a Spring fallback**: `${ANTHROPIC_API_KEY:sk-ant-...}` commits it.
  Use an empty fallback, `${ANTHROPIC_API_KEY:}`.

## Claude API Java SDK (anthropic-java 2.57.0)

- Streaming collect pattern:
  `.flatMap(e -> e.contentBlockDelta().stream()).flatMap(d -> d.delta().text().stream()).map(TextDelta::text).collect(joining())`
- `.maxTokens()` expects `long` — cast explicitly: `(long) config.getMaxTokens()`.
- Always propagate exceptions from LLM call methods (no silent catch returning null) — callers
  must surface the real error message in API responses.
- `MessageFormat` enum values are `JSON, XML, AVRO, AUTO` — there is **no `UNKNOWN`**; `AUTO` is
  the default.

## XML / JSON field access in FlinkSqlService

- A `DocumentBuilderFactory` without `setNamespaceAware(true)` makes `element.getLocalName()`
  return `null`. Always use `element.getTagName()`.
- `parseMessageToRow()` — XML flattens to a dot-notation map (`{"customer.name": "John"}`), JSON
  stays nested. `getNestedValue(row, path)` unifies both: it tries `row.get(path)` first (XML),
  then walks segment by segment (JSON).
- `flattenXmlElement(element, prefix, row)` is recursive, ignores the root element (`prefix=""`),
  and uses `getTagName()` — never `getLocalName()`.

## License

**AGPL v3** — `LICENSE` at root. Every Java **and** frontend source file carries the header:

```
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
```

It applies to `src/main/java`, `src/test/java` and every `.ts` / `.tsx` under
`src/main/webapp/src` — the SPA ships inside the AGPL jar and image. The one exception is
`vite-env.d.ts`, a line of Vite boilerplate. `package.json` declares
`"license": "AGPL-3.0-or-later"` beside it.
