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

**Key services:**
- `FlinkSqlService` — executes SQL against Kafka topics through an embedded Flink `TableEnvironment` (built from `EnvironmentSettings` in `FlinkConfig`; each job runs on an in-process MiniCluster, which is why a finished job's `JobClient` answers "MiniCluster … already shut down" rather than a status); per-request table registration ensures isolation
  - **The slot count and the default parallelism are set together** (`FlinkConfig.tableEnv`), and they are one constant for a reason: a job whose parallelism exceeds the slots available never deploys. `NUM_TASK_SLOTS` was pinned to 8 while Flink's default parallelism is the machine's **core count**, so on any host past eight cores the planner asked for sixteen slots from a TaskManager offering eight, and every subtask sat in `SCHEDULED` until the query's budget expired — no error, no rows, reported as a *timeout*, which is the one classification that does not count toward the circuit breaker and falls straight back to the direct reader. So every Kafka-source SELECT silently answered `engine: KAFKA_DIRECT` while the warmup probe reported a healthy planner, because `SELECT 1` runs at parallelism 1 and needs one slot. Measured on a sixteen-core machine: sixteen subtasks `SCHEDULED`, none deployed, for the whole forty-second budget. Eight rather than the core count is deliberate — this runtime is embedded in the process that serves the UI and has no business taking the whole machine, and a Kafka topic rarely has more partitions than that, a subtask with no partition reading nothing.
  - **Only an engine failure falls back.** `SqlErrorClassifier` splits a planner failure into `USER_ERROR` (parse error, unknown table/column, type mismatch, restricted statement) and `ENGINE_ERROR`; `rejectIfUserError()` returns the planner's own message for the former instead of retrying on the direct reader. Falling back on a bad query is worse than useless — the direct reader only regex-matches the table name out of `FROM`, so `SELECT id, FROM orders` or a misspelled column came back as *rows*, and the planner's precise complaint (with its line and column) was discarded. User errors also don't count toward the circuit breaker, or three typos would disable the Flink planner for the rest of the process. One exception, carried by `AutoRegResult.deferToDirect()`: when a topic exists but schema inference returned nothing, registration is deliberately skipped, so the planner's "not found" is our doing and must still fall back. **A projection that does not fit the sink is the caller's fault by column count as well as by type**: Flink words that one failure two ways — `Different number of columns` and `Incompatible types for sink column` — and only the second was recognised, so one mistake answered 400 through one wording and 500 through the other. The unrecognised one is the commoner: `INSERT INTO <sink> SELECT * FROM <source>` on a generated table carries the computed `proc_time` column no sink accepts, which is exactly why the editor's sidebar names the columns instead. A 500 says the server broke and sends an operator hunting an incident that does not exist — on the one gesture of that page with no fallback to soften it. Two neighbours were added on the same reading: a sink that cannot honour `INSERT OVERWRITE`, and an options hint applied to a view. `FlinkSqlServiceInsertVariantsTest` is where the shapes of an INSERT are enumerated against a real MiniCluster — a column list, a `VALUES`, an aggregate, a join, a CTE, a trailing semicolon, an `INSERT OVERWRITE` — and both of these came out of that enumeration. **And what the streaming planner cannot build is the statement's fault, not the engine's**: an unbounded `ORDER BY` (`Sort on a non-time-attribute field is not supported`) and a correlated `EXISTS` (`unexpected correlate variable`) are valid SQL that means nothing on a stream. Read as engine failures they fell back — to a reader that knows only Kafka topics, which answered `Table 'orders' not found. No matching Kafka topic exists.` about a table sitting in the catalogue, while the planner's real sentence went into `warnings` where nothing reads it. That substitution is the exact thing this classifier exists to prevent: another engine's opinion of a query it was never able to run. `FlinkSqlServiceStatementKindsTest` is the sibling enumeration for the query *kinds* — grouping and `HAVING`, `DISTINCT`, outer joins, subqueries, the set operators, expressions, `VALUES` as a source, bounded ordering and paging, window TVFs and an `OVER` aggregate through the planner rather than the direct reader, the three `EXPLAIN` forms, the `CREATE TABLE` variants, and the whole family the whitelist refuses — and this pair came out of it.
  - **A named read mode is answered by the reader that can honour it.** `readMode` is honoured by the direct reader alone, and while the planner answered nothing it was honoured by accident — every Kafka SELECT fell back. With the engine working, an auto-registered table always starts at `earliest-offset`, so the editor's `Latest` selector returned the *oldest* records to the question "the most recent ones": a plausible wrong answer, which is the worse of the two directions. Passing `latest-offset` to the planner would fix nothing — a Kafka scan starting and bounded at latest reads nothing at all — so the direct reader answers, exactly as it does for the metric templates that ask for it by name. Two guards. The mode must be **named**: `null` falls to `fetchForDirectRead`'s recent branch, so reacting to its absence would route every caller that does not state one — the audit, table previews, most tests — back to the direct reader and undo the engine fix for everybody. And the shape must be one that reader can honour (`MetricService.isSingleTableRead`, called in place rather than copied): on a JOIN or a subquery it would read one table and ignore the rest, so there the planner answers and the result **says the mode was not applied** rather than letting it pass for honoured.
  - **Every fallback says why, not only the permanent one.** `engineFailure` was computed at the fallback site, read nowhere and discarded, so a one-off degradation answered `engine: KAFKA_DIRECT`, `error: null`, `warnings: []` — indistinguishable from a query the direct reader was meant to serve. That silence is what kept three separate defects invisible (a connector option refused, a job that could not obtain its slots, a collection loop blocking on its last row): all three read as "the query worked". The latch below could not stand in for it, and not only because it needs three failures — a *timeout* deliberately resets that counter, so the commonest fault here never reaches it. The two sentences are appended rather than substituted: "the planner is permanently out" and "this query fell back because X" are different facts. A deliberate skip is worded as our own decision (`AutoRegResult.deferToDirect` — no schema could be inferred, so no table was registered) rather than by echoing the planner's "Object not found", which would send an operator to check a name that is correct. A user error never reaches this point: `rejectIfUserError` has already returned the planner's message.
  - **The circuit breaker says so, on the queries it affects.** `flinkSelectDisabled` was written in one place and read in one place and surfaced nowhere, so once it latched — for the lifetime of the process — every later SELECT came back as `engine: KAFKA_DIRECT`, without JOIN or subquery support, with nothing saying the degradation had become permanent. A fallen-back result now carries `plannerUnavailableMessage()` in `QueryResult.warnings()`, which the editor already renders above the grid, and `isFlinkSelectDisabled()` exposes the latch. **The latch decays**, which is that revisit: it needed a restart to clear, defensible while the assumed cause was a Flink version defect — a fault that does not repair itself — and wrong for the *environmental* one this repository has since seen trip it (a job that could not obtain its slots, gone the moment the configuration is fixed). Past `FLINK_SELECT_RETRY_AFTER_MS` (10 minutes) one attempt is allowed; it succeeds and `clearFlinkSelectLatch` reopens the breaker, it fails and the counter re-arms. The permit is **consumed at the gate rather than judged by the outcome**, which is what makes the bound true: a timed-out attempt passes through neither the success path nor the counted-failure path, so re-arming there would let a planner that never finishes be retried on *every* query once the interval elapsed. Several threads can pass together and each pay one attempt — bounded, and a lock on the read path would cost more than it saves. A constant rather than a property: no deployment has been named that needs another value. `plannerUnavailableMessage()` says so, since a message still promising a restart would be the stale claim this file keeps removing.
  - **Job mode registers its source too.** `submitJob` went straight to the planner, so the shortcut the sidebar itself proposes — an `INSERT INTO <sink> SELECT … FROM <a topic's table>` — answered "Object not found" until some *other* gesture, a plain SELECT, had registered that source in this process. Only the **source**: `extractPrimaryTable` reads `FROM`, so the INSERT target is excluded by construction and deliberately — deriving a sink schema from an empty target topic yields `raw_value STRING` and an arity failure, which is a worse answer than "unknown table". The guard that admitted only `SELECT` now admits any `INSERT`, past a leading CTE like everything else — **any**, and not `INSERT INTO` alone, because the job-mode guard classifies on the statement's first word and therefore lets `INSERT OVERWRITE` through: that half-acceptance left the same defect standing one keyword over, its source never registered and its correct topic name answered with "Object not found". Letting Flink decide is the right arbitration there — its refusal names the cause exactly (this sink does not implement `SupportsOverwrite`) where a refusal written here would only repeat that job mode wants an INSERT — so what job mode agrees to submit, auto-registration has to agree to serve. **And every source is registered, not only the first**: `extractSourceTables` reads the `JOIN`s beside the `FROM`, because a statement reading two topics registered one and let the planner answer "Object not found" about the other — a name that is perfectly correct, on the shape a JOIN *is*. The primary table stays first, since `deferToDirect` is decided on it and on nothing else: it is the only one the direct reader would read. **A `STATEMENT SET` is submitted too** (`isJobModeStatement`, one definition the read path and the job path share): it is Flink's fan-out — several INSERTs from one source in a *single* job, so one read of the topic — where the equivalent, N submissions, costs N reads and N embedded clusters. **A submission keeps the query id its caller chose** (`resolveQueryId`, as a read already did): the id used to exist only in the response, so a response that never arrived left a job running that nothing could name. And Flink's "only single statement supported" is re-worded to name the two ways out (Run all, or a STATEMENT SET) rather than stating a limit and stopping there. And `AutoRegResult.deferToDirect()` has no meaning here, there being no direct reader to catch the query: an uninferable source becomes a refusal that names the cause and says what to do (write the `CREATE TABLE` yourself), instead of the planner's complaint about a name that is perfectly correct.
  - **SQL comments**: `--` line comments and `/* */` block comments are stripped before any keyword checks. **A hint is not a comment**, and is kept: `/*+ … */` is Calcite's and Flink's syntax for table options (`FROM t /*+ OPTIONS('scan.startup.mode'='earliest-offset') */`), distinguished from a comment by the single `+` after the opening. Stripping it meant no hint an operator wrote in the editor had ever reached the planner — silently, the engine's own log line printing the query without it. What that cost went beyond the editor: the experiment that concluded this connector refuses `scan.bounded.mode` was run through a hint that never left, and the option genuinely refused in the same statement was a different one (see `MetricService`'s `SCAN_STARTUP_EARLIEST`). A query beginning with a comment line is valid.
- `FlinkWarmupService` — one throw-away `SELECT 1` after startup, on a daemon thread, so a user's first query does not pay the ~4 s the planner costs to wake up. Off with `explorer.flink-warmup-enabled: false`. What it proves is narrower than it looks, and `docs/notes/backend-services.md` says why.


- `SchemaInferenceService` — samples messages and delegates to `JsonSchemaInferrer` / `XmlSchemaInferrer` / `AvroSchemaInferrer` (inferred column order is deterministic — `LinkedHashMap`)
- `DdlGeneratorService` — auto-generates Flink `CREATE TABLE` DDL from inferred schemas. `maskSensitiveProperties()` (static) redacts credentials and **must** be applied to any DDL that reaches the UI *or a log*; internal registration uses the unmasked DDL. A format option carries the `value.` prefix when the format does, and the connector rather than taste enforces that. Both rules, and what they cost when broken, are in `docs/notes/backend-services.md`.
  - Flow latency memoizes `topic → Map<id, first timestamp>` for the run: a topic in the middle of a flow is both a source and a target and was otherwise fetched twice.
  - `FlowAudit.overallHealthScore` is a **0..1 ratio**, not a percentage (the UI multiplies by 100).
  - `totalMessages` sums the per-topic counts actually reported, not `topicSizes`, so the KPI and the table column agree when exact counts ran.

- `SqlQueryValidator` — cross-join and system-table guard, **not** the statement whitelist. It checks the two `ExplorerConfig` switches and returns silently for anything that is not a `SELECT` or an `EXPLAIN` — which is what used to let `INSERT INTO` reach Flink Job mode. That mode is gone, so nothing downstream of this class accepts an INSERT any more; the silent return stays because the rule it states is about *this* guard's scope, not about which statements exist. The whitelist ("Only SELECT, EXPLAIN, SHOW, DESCRIBE and CREATE TABLE statements are allowed.") is enforced by `FlinkSqlService.executeSql`, and this file described the wrong class for it. **`CREATE TABLE … AS SELECT` is refused there**, because that whitelist classifies on the first word and a CTAS is an INSERT wearing a CREATE TABLE hat — on the *read* path, the one that sends every `INSERT` to Job mode. It created the table **and started the job feeding it**, without going through `submitJob`: nothing in `FlinkJobStore`, so invisible to the dashboard, outside `explorer.max-concurrent-jobs`, and with no id for `POST /api/query/cancel/{queryId}` to reach — on a Kafka source, a continuous job nothing can see or stop. The refusal names the two gestures that do the same thing visibly (`CREATE TABLE`, then the `INSERT INTO` in Job mode, which Run all chains). `isCreateTableAsSelect` blanks string literals before looking, since `WITH ('note' = 'as select …')` is an option value and refusing the only DDL this application accepts over the contents of a string would be the false positive that matters. Its `EXPLAIN` probe still swallows unresolved-table errors (it runs before auto-registration, so those are expected), but a **parse** error is rethrown as `IllegalArgumentException`: syntax never depends on the catalog, so `POST /api/query/validate` can reject a typo with its line/column before the query touches Kafka. **That probe covers an INSERT too**, and did not: the check returned early for anything that was not a SELECT or an EXPLAIN, while the editor calls `/api/query/validate` before *every* Run, job mode included — so an INSERT was pre-flighted by nothing, and its typo was found by submitting it, which writes a FAILED record into the job store and spends one of the retained slots. Measured: `explainSql` on an INSERT separates exactly the two cases this needs — a syntax error surfaces as a parser failure (rejected here with its position), an unresolved table stays a resolution failure and is swallowed, which it must be, since this runs *before* the sources are auto-registered. The classification reads past a leading CTE like everywhere else; without that a `WITH … SELECT` was not validated either.
- Process Mining **runs without an LLM** for the half that never needed one — the directly-follows graph, the variants and the latencies are counted on this side. `ClaudeConfig.configurationProblem()` (key *and* address) is checked after the read, so a deployment with no model still gets its `ProcessModel`, with `error` naming what is missing. See `docs/notes/backend-services.md`.


- `SseEmitterManager` — manages `Map<sessionId, SseEmitter>` (5 min timeout, heartbeat every 15s)
- `ClaudeConfig` — `@ConfigurationProperties(prefix="claude")`; reads `ANTHROPIC_API_KEY` env var via `${ANTHROPIC_API_KEY:}`
- `MetricService` — bridges Flink SQL to Prometheus metrics via Micrometer. Supports 4 Prometheus types:
  - `GAUGE` → Micrometer `Gauge` (point-in-time value)
  - `COUNTER` → Micrometer `Counter` (service tracks delta between polls and increments accordingly)
  - `HISTOGRAM` → Micrometer `DistributionSummary` with `publishPercentileHistogram=true`
  - `SUMMARY` → Micrometer `DistributionSummary` with client-side quantiles (p50/p75/p90/p95/p99)

  Metric status stays `pending` if `lastValue == null` — ensure aggregates use `AS metric_value` alias.
  `MetricConfig.createTableSql` (optional): when set, the DDL is executed before the metric SQL (useful for pre-registering a Flink table).

### Frontend


**The three SVG graphs share one viewport** — `components/graph/useGraphViewport.ts`, used by
`Lineage`, `StreamFlow` and `DataModel`. They carried the same implementation copied three times:
the same `isPanning` / `lastPos` refs, the same three pointer handlers line-for-line, the same
non-passive `wheel` listener, the same keyboard step. That is exactly the shape that lets a fix
land on one page and not the other two, and it had already happened here — the move to *pointer*
events, without which a tablet cannot pan a graph at all, had to be made three times.

The split is **mechanics in the hook, policy in the pages**. The hook owns the transform state,
the gestures, the wheel zoom and the viewport measurement, and exposes `panBy` / `zoomAround` /
`zoomFromCenter` so a page's keyboard handler is a few lines. What stays in each page is what
genuinely differs: what `0` recadres (a fit for Stream Flow and Data Model, a fixed origin for
Lineage), what `Escape` deselects, and which node a table selection brings into view. Absorbing
those through three more callbacks would have made the hook harder to read than the forty lines
it replaces.

Three things are parameters rather than constants because the pages really disagree: the scale
bounds (Data Model goes to 0.1–3, the other two 0.15–4 — a hundred entities need the lower
floor), the initial transform, and whether a press on a `[data-node]` starts a pan. `viewAdjusted`
is the hook's, with `markFitted()` / `markAdjusted()` as the pair of verbs: `panBy` and
`zoomAround` raise it themselves, and a page that sets its own transform — centring on an entity —
says so, because Data Model's automatic refit on a resize must not undo a framing the operator
chose.

**`svgRef` is a callback ref, not an object ref, and that is load-bearing.** Stream Flow and Data
Model render their canvas only once a result has arrived, so an effect keyed on anything else runs
once with `ref.current` still null and never runs again — which is exactly how the `wheel` listener
stopped attaching on two of the three graphs when this hook was extracted. Each page used to work
around it with its own dependency (`[hasResult, nodes.length]`, `[zoomAround, model]`) under a
comment reading "re-attach after the SVG mounts"; sharing the code dropped the dependency and the
defect came back. A callback ref makes the mount observable, so the question no longer reaches the
caller — and pages read the element as `canvas` rather than `svgRef.current`.

Only `zoomTransform` is unit-tested; the rest is pointer plumbing over geometry jsdom does not
have, where a test would assert that mocks were called rather than that a graph pans. What covers
it instead is **`docs/screenshots/graph-gestures.mjs`**, run by CI in the screenshot job: real
pointer, wheel and keyboard events against the compiled SPA in Chromium, asserting that the pan
follows the pointer, that the zoom stays anchored under the cursor, that the keyboard reaches the
graph and that `touch-action` is neutralised. It is what found the wheel defect above.

**Form conventions** — build every form out of these; hand-rolled `<input className="…">` blocks drift from the tokens and skip the accessibility wiring.
- `PasswordInput` adds a reveal toggle and `autoComplete="new-password"`. Secrets typed blind fail at connection time with nothing to diagnose.
- Wrap in a real `<form onSubmit>` so Enter submits from any field. `Button` defaults to `type="button"` for that reason — submit buttons declare `type="submit"` explicitly.

**Routes / pages** (`pages/`):
- `QueryController` — `/api/query/**`. Every endpoint here answers with a *reason* rather than a bare status: `ddl-preview` through `SqlErrorClassifier.explain()`, `init` with `kafkaError` / `flinkError` instead of two empty catches, `cancel` with what it actually achieved, `submitJob` with an `ApiError` split 400/500 on the classifier. `docs/notes/frontend.md` has the defect behind each.


  **What Job mode left behind, and what became of each.** #323 removed the `SubmittedJobPanel` and its wiring along with the feature — five pieces of state, a polling effect, a Stop handler, all referencing an `executionMode` that no longer existed, which is why the SPA had stopped type-checking on `main`. #325 then removed the three pieces that still compiled and simply had no caller — `GET /api/query/sink-ddl` (with `DdlGeneratorService.sinkColumns`), `insertTargetAndSource`, `flinkJobHistory.isJobTerminal` — plus the shipped-but-unread `explorer.inference-poll-timeout-ms`, and added `docs/check-config-yaml.py` so a settable, inert `explorer.*` key fails the build instead of sitting there. The one kept is `FlinkSqlService.submitJob` with the `explorer.max-concurrent-jobs` cap that guards it: unreachable over HTTP, and not dead code — it is where `FlinkSqlServiceInsertVariantsTest` establishes what an INSERT means here. What survives in the browser is the rule the two mode guards served: an `INSERT` is refused there, **with its cause**, rather than sent to the engine to be answered by the whitelist. `docs/notes/frontend.md` carries the reasoning for each.


  See `SQL-EDITOR-AUDIT.md` for the full review, including what was deliberately left open.
- `Compare` (`/compare`) — side-by-side topic comparison


- `Help` (`/help`) — the SQL guide, written as a course rather than a reference card, with every example runnable against what `setup-demo.sh` seeds and opened through the same `?sql=` link a colleague would paste. The content lives in `pages/helpContent.ts` so tests can keep it executable; see `docs/notes/frontend.md`.


**A pure module never differs from its page component by case alone.** a lower-case `compare` module sitting beside `pages/Compare.tsx` is one file name on a case-insensitive filesystem: the two collide in the module graph, and what comes back is a `Compare` module whose `default` is `undefined` — React's "Element type is invalid". Measured on Windows: **110 of 1 558 Vitest cases failed**, five whole page suites among them (`Compare`, `DataModel`, `Help`, `QueryWorkbench`, `StreamFlow` — exactly the five pairs), while CI on Linux stayed green. So `mvn verify`, the one command this file calls "the only command that runs everything", could not be trusted on the machine where the code is written. The pure halves are `compareMessages` / `dataModelGraph` / `helpContent` / `queryWorkbenchLogic` / `streamFlowLogic`; the names are uniform rather than precious, and what matters is the rule, not the words. Most pure modules here were never affected — `queryError.ts`, `sqlScope.ts`, `metricScope.ts`, `topicActivity.ts` are named for what they hold rather than for their page, which is the habit that avoids this by construction.


Dev server proxy: Vite forwards `/api/*` to `http://localhost:8080` (configured in `vite.config.ts`).

### Configuration

`src/main/resources/application.yml` controls:
- Kafka bootstrap servers (default `localhost:9092`)
- Kafka connection mode: `PLAIN` (default), `SSL`, or `CONFLUENT_CLOUD` — each mode has its own set of required properties (keystore/truststore for SSL, API key/secret for Confluent Cloud)
- Concurrent Flink jobs: `explorer.max-concurrent-jobs` (10; 0 removes the cap) — how many continuous INSERT jobs one deployment holds at once. **No HTTP path reaches the submission it guards today** — `POST /api/query/jobs` went with Flink Job mode — so on a running deployment this setting cannot bind; it is kept because `FlinkSqlService.submitJob` is kept and tested. Each submission starts its own embedded MiniCluster, measured at ~80 threads and ~6 MB of heap per job in the process that also serves the UI; the refusal names the count and this setting.
- Header label: `explorer.cluster-name` (`EXPLORER_CLUSTER_NAME`, default `Kafka cluster`) — a display name for the environment, nothing more; see the connection-pill note under **App shell**
- Consumer pool: `explorer.consumer-pool-size` (**0**, disabled) — how many byte-array consumers the per-topic record readers keep between reads. See `KafkaConsumerPool` for why the default is off
- Cache TTL: `explorer.cache-expire-seconds` (default 30s) — applied by the custom `CacheManager` bean in `WebConfig`; a `spring.cache.caffeine.spec` in YAML would be silently ignored

- Default result rows (50)
- Audit topic name (`internal.audit.history`) and history read cap (`explorer.audit-history-max-records`, 200)
- Field-mapping topic (`explorer.field-mapping-topic`, `internal.field.mappings`) — where a validated Process Mining mapping is kept, keyed by its id so the topic can be compacted
- Prometheus: `management.endpoints.web.exposure.include: health,info,prometheus` — exposes `/actuator/prometheus` for scraping

### SPA Routing

`SpaController` forwards anything that is not a static asset and not under `/api/` to
`index.html`, so the client-side router handles it and a new page needs no backend change.
Three rules govern it and each one is a defect that was paid for — a Kafka topic name contains
dots, `/api/**` needs its own mapping rather than relying on the catch-all, and a controller
mapped onto a client route shadows the SPA and answers a refresh with a 500. They are written
out, with what each cost, in `docs/notes/configuration-and-routing.md`.

## Testing

Tests use JUnit 5 + Mockito. Unit tests mock Kafka and Flink — no broker needed. Integration tests (`ApplicationContextTest`) use `@SpringBootTest` with `DynamicPropertySource` to inject test config.

`AuditServiceTest` overrides `persistAuditHistory()` to skip real Kafka writes.

**The Process Mining eval has two halves, and only one of them is a unit test** (`eval/`). Nothing
measured whether the prompt work does what it claims, and that failure mode is silent by
construction: a plausible answer about an invented pipeline reads exactly like a correct one. The
way to tell them apart is a dataset whose right answer is known, and `setup-demo.sh` seeds one.
`DemoPipelineFixture` loads it from `src/test/resources/eval/demo-order-pipeline.json`, which holds
**records rather than digests** — a digest is what this application computes, so committing one
would let the fixture and the digester drift together and agree with each other about a payload
neither had read. It is also **not captured from a live cluster**: a capture is a snapshot nobody
can regenerate without a broker, so it is written from the seeder and resolved against it by
`docs/check-eval-fixture.py` (every topic, every order id, every redelivery, the two corrupt
payloads verbatim, and the assertion that the payments and shipments still carry no order id in
their bodies). `ProcessModelEvalTest` asserts the aggregate exactly and runs in `mvn verify`;
`LlmAnalysisEvalTest` calls a real model, asserts loosely, carries `@Tag("llm-eval")` — excluded by
surefire's `excludedGroups` **and** by `verify-offline.sh`'s `--exclude-tag`, since it costs money
and needs the network — and **skips rather than fails** when nothing is configured, a test that goes
red for want of an API key being one people learn to ignore. Writing it is what found the
truncated-record defect described under `ProcessModelBuilder`.

**It also carries the first half of the bilingual-prompt experiment** (`theShippedPromptHoldsItsFormat`,
`-Dllm.eval.runs`, default 3): the system prompt is English and the user prompt's headings are
French, small models are reputed to hold a format less well across a language switch, and nothing
had measured that *on this prompt*. It measures the shipped prompt and reports the rate — a parse
failure, an answer that parsed with no flowchart, and an unreachable endpoint being three different
answers. An English variant is deliberately **not** shipped to compare against: a second production
prompt is a second surface to keep in step for ever, built on a belief, and a prompt that holds the
format every time on the model under test answers the suspicion without one. Only a run showing
failures justifies building it, and it would then be justified by a number.


**The metric templates' scan bounds are asked here too, and that question has now been answered twice — the second time correctly.** A mock cannot refuse a setting it has never heard of, so `MetricService`'s bounded-scan option had been added on a reading of the connector's documentation, and the first real-broker run appeared to refuse it. That verdict was confounded, in two ways neither visible from the test: the hint never reached the planner (`stripSqlComments` erased it — a Calcite hint is comment-shaped, and the engine's own log printed the query without it), and the key actually refused in the same `WITH (…)` was `json.ignore-parse-errors` written without its `value.` prefix, the exception listing every unconsumed option together so the blame fell on the one just added. With both fixed, `thisConnectorBoundsAScanWhenAsked` measures the opposite: the count runs through the planner and its source tasks reach `FINISHED`, which an unbounded source never does. The unbounded half of the same case is what keeps that conclusive rather than tautological — without the option the identical query spends its budget and falls back. **`MetricService` still sends the startup mode alone, deliberately**: what bounds a metric's read changes what the metric *measures*, and that is a change to argue on its own rather than a line to flip. The sibling case pins the path a count-delta side really takes — `directSql` to the direct reader, one row rather than a changelog, a number that had to come out of the broker — and it is why the wrong option was survivable: the count was right all along, by the other route. Both build their own local Flink cluster on demand rather than as a field, since the rest of the class talks to the broker directly and should not pay for one.


`InternalTopicProvisionerTest` and the new cases in `FieldMappingStoreTest`, `MetricServiceTest`,
`AuditServiceTest`, `StreamFlowServiceTest` and `ExplorerGroupCleanupServiceTest` cover the
application's own resources on the cluster — the topics it writes and the groups it leaves — and
what they mostly pin is what those must *not* do: create anything of the user's, alter a topic
nobody asked to have altered, audit or trace themselves, or delete on a broker that never answered.
The two that describe a real defect were verified to fail against the revision they describe:
`aPrefetchPositionAtTheLogEndDoesNotEndTheRestore` (which needs a `MockConsumer` that lies the way a
real client lies, reporting the log end while handing over nothing — `MockConsumer` otherwise
advances its position only on delivered records and could never reproduce it) and
`anEvictedMappingIsTombstonedSoTheTopicFollowsTheSameBound`.

Test classes are in `src/test/java/com/compagnonsdudev/kafkasqlexplorer/`.

## Audits and scope documents

`AUDIT-FEATURE-REVIEW.md` is a later, narrower review of the **Cluster Audit feature itself**
(`AuditService`, `AuditController`, `NamingConventionService`, `MessageFieldExtractorService`,
`Audit.tsx`) — bugs B1–B9, optimisations O1–O3, ergonomics E1–E8 and a second lot S1–S3 (graded
severity, one run at a time, consistent `totalMessages`), all fixed, plus a "constaté, non traité"
section listing what was deliberately left open (the write-only `internal.audit.history` topic, no
cancellation of a running audit, duplicates scanned from EARLIEST, no global time budget, the
premature-empty-poll behaviour of `KafkaAdminService.getEarliestRecords`).

**Consumer-group handling** was reviewed the same way — the groups the app creates on the user's
cluster and those it reads to answer "who consumes this topic": `getTopicConsumers`,
`ExplorerConsumerGroups`, `ConsumerGroupLag` / `TopicConsumers`, `ConsumerLagMetrics`,
`AuditService.consumerLagIssues`, the Consumers panel and the Cluster page. Bugs B1–B10 and one
optimisation O1, all fixed here. One defect recurs throughout it, and it is the reason to read the
`KafkaAdminService` / `ConsumerLagMetrics` notes above before touching either: a measurement that
could not be taken came back as zero, and zero was rendered as an answer — zero members, zero lag,
zero groups — which became a *critical* audit finding, a "caught up" Prometheus gauge, and a
confident sentence in the UI. (The report itself, `CONSUMER-GROUPS-AUDIT.md`, was deleted from the
tree in d643f23; its conclusions are the paragraphs in this file, not a separate document.)

`PROCESS-MINING-LLM-SCOPE.md` reviews the half of Process Mining that **uses** the model, the
ingestion half having been audited several times over and the prompt never. It implements nothing:
every item is sized and ranked, and one dominates. The analysis prompt samples messages *per topic,
independently* (`LlmAnalysisService.appendCommonSections`), while four of the five audit prompts ask
questions about a **case** — ordering, orphans, latency, duplicates are all "per correlation id" —
so the model is asked to correlate records it was never shown together, and what it can still do is
infer a pipeline from the topic names. The budget narrows it further: `sample` is forty scalars of
up to 160 characters *per message*, inlined verbatim, and it is by definition the values the mapping
did **not** name — so roughly 2 % of a snapshot's records reach the model, a ratio the coverage
panel has been displaying all along (`messagesDetailed` against `messagesRead`) without anyone
drawing the conclusion. The recommendation is to compute the event log in Java — `digest.fields()`
already carries case id, timestamp and status per record — and send the directly-follows graph,
the variants and the per-edge latencies, leaving the model the interpretation. The pattern already
exists one file over: `FieldProfilingService` aggregates per path instead of inlining per record,
which is why profiling behaves on small models and the analysis does not. Read it before touching
`LlmAnalysisService`, `LlmSchemas` or `AuditPromptCatalog`.

`PROCESS-MINING-LLM-CALLS-AUDIT.md` is its sibling on the other axis: the same feature, but the
**calls** rather than the prompt, and every provider **except OpenRouter** — `ANTHROPIC`,
`OPENAI_COMPATIBLE`, `OLLAMA`, `SPECTRA`. It exists because that is where the gap is: the routing
policy, the per-model schema latch, the model catalogue, the key-credit read and the
relayed-upstream diagnosis are all OpenRouter's, and so is most of `LlmStructuredOutputTest` — what
is left over is the path every deployment that keeps inference in-house takes, which is what two
bundled stacks and two setup scripts configure. Twelve items ranked, nothing implemented. Three
dominate. A **400 that has nothing to do with the schema** still marks a model schema-incapable for
the client's lifetime, because `rememberSchemaRefusal` is called *before* the unconstrained retry
that would test the conclusion (`max_tokens` and `temperature: 0.0` are both refused with a 400 by
current OpenAI reasoning models, and this client always sends them) — and `LlmClientProvider`
fingerprints provider, base URL and key, so it survives every Settings change but those three.
**Test LLM gives up at 90 s** on the Process Mining page while `compose/ollama.yml` and
`compose/spectra-hub.yml` both configure the server to wait **300**, so the button reports "could
not be reached" about an endpoint that was answering, on the two stacks where nothing else
diagnoses anything; its twin on the Settings page has no timeout at all. And **the bundled Ollama
stack runs unconstrained**: `compose/ollama.yml`, `setup-llm.sh`, `setup-llm.ps1` and Option C of
`docs/LLM-PROVIDERS.md` all set `OPENAI_COMPATIBLE`, which `structured-output: AUTO` deliberately
leaves alone, while `docs/DOCKERHUB.md` tells operators to set `OLLAMA` — a value nothing in the
tree actually sets. **Eight of the twelve have shipped**, in two changes. The latch now records only what the
retry proves; `pages/llmTimeout.ts` derives both Test waits from the budget `GET /api/config`
publishes, floored at the previous 90 s so nothing waits less than before; every shipped artefact
names `OLLAMA`, keeping `OPENAI_COMPATIBLE` where it is the right answer (vLLM, LM Studio, a
gateway whose behaviour is not established); **`ClaudeConfig.configurationProblem()`** answers "can
this deployment call a model?" — key *and* address, the second of which was checked nowhere, so an
`OPENAI_COMPATIBLE` deployment naming no base URL read its topics and then failed inside the HTTP
client with `URI with undefined scheme` — consulted by both services, by `test-llm` and by the
Process Mining banner, with a typed non-URL refused at save time; the **Anthropic path** applies
the configured timeout, pins `temperature` to 0.0, shares `remedyFor`, reports through
`SqlErrorClassifier.explain` and gets the one-retry schema degrade its sibling has, with
`AnthropicLlmClientTest` as the first test that class has ever had; remote response bodies go
through `LogSafe.text` before reaching the log; and the analysis half reports `explain(e)` where it
reported a `getMessage()` that is null for an NPE. The refusal memory moved to
**`SchemaRefusalMemory`** on the way — two copies of "which models cannot be constrained" is how
one comes to latch on a status the other does not.
**The last four have shipped too**, and three of them turned on a measurement rather than an
argument. **L8**: a redirect fell into "everything else is transient", so a 308 took all three
attempts and came back as `status 308: ` with an empty body — a permanent misconfiguration reported
as a passing one. The audit's first suggestion was `followRedirects(NORMAL)`, and that was
*measured before being taken*: the JDK converts a POST to a GET on 301 and 302 and **drops the
body** (`method=GET bodyLen=0`), so the prompt would leave silently; only 307 and 308 preserve it.
A 3xx is therefore refused, naming the `Location` to put in `claude.base-url` — an endpoint that
redirects every call is configuration to fix once, not a round trip to pay for ever.
**L6**: `LlmClient` is `AutoCloseable` (narrowed to throw nothing — a client being retired must not
fail the save that retired it), the replaced one is closed after the replacement is published, and
`@PreDestroy` releases the last. It is `HttpClient.shutdown()` and not `close()`: `close()` blocks
until every in-flight exchange ends, and an exchange here is a model generating — up to the 300 s
the bundled local-inference stacks configure — landing on the thread that saved the Settings page.
`requestTimeoutSeconds` joins the fingerprint, since it is *both* read per call and baked in as the
connect timeout, so raising it moved one half only. **L4**: `ClaudeConfig.namesTheModel()` is false
for SPECTRA, which serves whichever model it is configured with and ignores the field — a
deployment moved off the shipped OpenRouter default kept `openai/gpt-4o-mini` in it, and that stale
slug was reported as the model answering on every window; `LlmUsage.model` is null there and
`describeRuntimeModel` renders the absence as *model chosen by the server*. **L9** is the one the
audit called out as silent: `LlmResponse.schemaSent` records what the client **sent** (not what was
configured — `AUTO` declines for an unknown provider, and the per-model latch declines after a
refusal), and `LlmAnswerSignals` turns two already-parsed facts into coverage warnings. A schema
that travelled and an answer that still needed repairing means the endpoint accepted the field and
ignored it — a constrained decoder cannot emit the reasoning block or fence that was stripped —
which is `ACCEPTED_UNCONSTRAINED` for every provider that publishes no catalogue. And a
`prompt_tokens` under **half** an optimistic four-characters-per-token floor is a prompt truncated
to fit a window, which is Ollama's documented behaviour and logged by it at debug, i.e. nowhere.
Both are **warnings, not verdicts** — the ratio is an estimate and this application does not own
the tokeniser — and both are logged as well as carried, because the live path reports its scope
through `WINDOW_STATS` rather than a coverage record and a live session reasoning on half its
prompt has no other symptom. Read the audit before touching `AnthropicLlmClient`,
`SpectraLlmClient`, `LlmHttpSupport` or `LlmClientProvider`; what it says about those files is
still the reason they look as they do.

**The two metric templates that compare the results of two queries** — `TOPIC_COUNT_DELTA` and
`TOPIC_TRANSIT_LATENCY`, which are also the two the KPI suggestion panel proposes most — are
reviewed in `METRICS-TWO-QUERY-AUDIT.md`, twelve items ranked, of which the first three have
shipped. What they were is worth knowing before touching either compute method, because each fix
is load-bearing and none of it is obvious from the code that remains.

**The "bounded scan" hint bounded nothing, and the option that would have is not available here.**
(It was `BOUNDED_HINT`; what is left of it is `SCAN_STARTUP_EARLIEST`, named for what it actually
sets.) Its
javadoc described `scan.bounded.mode` ("reads all data that exists at query start, then
terminates") while the constant wrote `scan.startup.mode`, which says where a scan *begins*;
the environment is `inStreamingMode()`, so the source never ended, and the option merely
restated what `DdlGeneratorService` already writes into every generated table. The bounded
option was then added, measured against a real broker, and recorded as **refused** by
`flink-connector-kafka:5.0.0-2.2` on the strength of *"Unsupported options found for
'kafka'"*. **That measurement was confounded and the conclusion is retired.** The hint never
reached the planner — `FlinkSqlService.stripSqlComments` erased `/* … */` blocks and a
Calcite hint is comment-shaped, which the engine's own log showed by printing the query
without it — and the key genuinely refused in that same `WITH (…)` was
`json.ignore-parse-errors` written without its `value.` prefix, the exception enumerating
every unconsumed option at once so the blame fell on the option just added rather than on
the one that had always been wrong. Both fixed, the connector bounds the scan and the
planner answers the count. What has **not** changed is what `MetricService` sends: the
startup mode alone. Bounding a metric's read changes what the metric *measures* — a count
over "everything at query start" is not the count these templates publish today, whose
semantics were settled against the direct reader (`isSingleTableRead`, `directRead`, the
window and offsets modes) — so sending it again is a separate change, argued and measured
on its own. What removed the templates' dependence on an unbounded planner scan in the
first place is the direct-read routing below, and that is untouched.

**Raw SQL is routed by its shape, and its result is read like a changelog.** `computeRawSqlMetric` asked for the planner unconditionally, under a comment reading "never the direct reader: raw SQL is the operator's own, and may need the planner" — written when the planner answered nothing, so in practice every such metric fell back to the direct reader, which returns an aggregate in one row. With the engine working the planner really answers, and a streaming `COUNT(*)` is an endless changelog: on a large topic it fills the row budget and the last row is a **partial** count published as a total; on a small one it never fills it, blocks, and the metric spends its whole time budget at every refresh before falling back — measured at 30 s on an eight-record topic. The shape decides now, not the mode: `isSingleTableRead` sends a single-table read to the reader that answers it in one row (the rule the templates already apply), and everything else — a join, a subquery — genuinely needs the planner, which is an improvement over a direct reader that read one table and ignored the rest in silence. Two guards follow it. A truncated changelog is **refused** rather than published, through `isTruncatedChangelog`, one predicate that this path and `aggregateValue` both call — two copies of "FLINK and rows >= budget" is how they come to disagree. And on a FLINK result the value is the **last** numeric row: `extractPrimaryMetricValue` returns the first, which is the aggregate on the direct reader and `+I(1)` on a changelog — the same defect the two-query path was fixed for, left standing here and made reachable the day the planner started answering. Nothing changes for a metric the direct reader serves.

**A streaming `COUNT(*)` is a retract changelog, and the value is its *last* row.** The collector
drops `RowKind` and `extractPrimaryMetricValue` kept the first, which is `+I(1)`, so above roughly
five thousand records a `PERCENT_GAP` silent-drop alarm published `0.0` — no loss, on the alarm
whose purpose is to report loss, and precisely on the topics worth alarming about. Three things
together fix it and one alone would only have moved it: the last numeric row (the final aggregate
of a complete changelog, and the only row of a single-row direct read, so one rule serves both
engines); a result that **filled its row budget** refused rather than read as a total; and the
generated shape asked of the direct reader by name, which answers a count with one row and no
changelog at all. That reader has a ceiling of its own — `AGGREGATE_SCAN_RECORDS`, 100 000 — and
now **says so in the result's warnings**, because two counts that both stopped there differ by
nothing: a side that hit it is a floor, and the comparison is refused rather than published as
"no gap".

**A latency now reads the recent end** (`DEFAULT_LATENCY_READ_MODE`), where it read the oldest
records the row cap allowed and therefore never moved again on a topic older than that cap — the
rule `explorer.audit-duplicate-scan-from` already states for the audit's duplicate scan.
`earliest-offset` restores the old behaviour and the form warns when it is chosen. That default
only means anything because `readMode` stopped being a knob that does nothing: it is honoured by
the **direct reader alone**, so the template asks for that reader by name
(`QueryRequest.directRead`, `MetricService.isSingleTableRead`, which fails closed — a join, a
subquery or a table list goes to the planner) rather than letting the planner answer a question it
has no syntax for. There is no scan option for "the most recent N records": a Kafka scan starting
at `latest-offset` and bounded at `latest-offset` reads nothing.

Three smaller rules came with them. `maxRowsPerSide`, `timeoutMs` and `readMode` decide what the
metric measures and were reachable only by a hand-written POST — they are on the form and refused
at **save** time, like `CONSUMER_TIME_LAG`'s `aggregation` three lines above them, instead of
throwing from inside the refresh loop once every thirty seconds. `QueryResult.warnings` is read at
last, on the path whose output feeds an alert (and an aggregate on the direct reader had been
dropping its `WHERE` caveats outright, where the non-aggregate branch kept them). And every
refusal names the side, the read it came from and what to use instead: "the right query counts
zero" now says that `LEFT_MINUS_RIGHT` reports that as a number.

**Four more shipped after them, and each is a measurement the metric was hiding.** *The right side
is counted first* — two counts cannot be taken at one instant, so the arithmetic leans and the only
choice is which way: every operation here grows with the left side, so reading it **last** lets the
interval's traffic inflate the numerator and a gap that survives is real, where the previous order
let the same traffic inflate the denominator and hide the loss the metric exists to report. It is
`KafkaAdminService`' own rule (committed offsets first, log end offsets last) and `readGapMs` says
how much room the interval left; `ABS_DIFF` is symmetric, so its note says the error can go either
way rather than claiming a guarantee. *The latency reports what it could not pair*: `matchRate`,
`unmatchedTargetCount` and `outOfOrderCount`, the rate **exported as a series of its own**
(`explorer_metric_correlation_match_rate`) because a figure that lives only in a summary nobody
alerts on cannot correct the figure that is alerted on — an unmatched source contributes nothing to
the average, so the metric *improves* as the pipeline breaks. *A distribution records each
observation once*: rows are ordered by event time and carry a reserved `__observed_at` column,
excluded from the tags and the label key exactly as `metric_value` is (without which a timestamp
becomes a label and mints one series per observation), and the dedup keys on that watermark rather
than on position — which the sliding window D3 introduced had broken outright, freezing the
distribution after its first cycle, and which was biased before that by rows ordered on the match
key. And *a successful refresh dates itself*
(`explorer_metric_last_success_timestamp_seconds{metric_id}`, set only on a cycle that produced a
value): the value still freezes on a failure, deliberately, but a frozen gauge and a fresh one are
no longer indistinguishable — the alert is `value > N and time() - …last_success… < 120`, the same
series and the same reasoning as `ConsumerLagMetrics`. All of it reaches the operator as well as
Prometheus: `lastSummary` was computed, persisted and rendered nowhere outside the preview modal,
so a metric in service said nothing about its own scope; it is a chip row on the card now
(`pages/metricScope.ts`, pure and tested), with the match rate shown even at 100 % on the rule the
coverage notice already follows — an indicator seen only on bad news is one people stop reading.

Two more followed and closed the document's own last two loose ends. **`operation` is refused at
save time** like the three scan parameters beside it, against a set named once that the compute
switch's error message reads too — it was the last of this template's parameters that could be
accepted by the API and then throw from inside the refresh loop every thirty seconds. And **the two
assertions the audit asked for exist**, in `KafkaClusterIntegrationTest`: the option D1 rests on is
now measured against a real broker rather than read off a page (see the note under **Testing**).

**And two changes that are not defect fixes at all**, each dissolving items the defect list could
only mitigate. **A whole-topic count is metadata, not a scan**: `COUNT(*)` over a topic is
`endOffsets − beginningOffsets`, which `KafkaAdminService.getTopicsSize` already answers *for both
topics in one call*, where the engine was downloading and parsing up to 100 000 records per side
every thirty seconds. `countBy: OFFSETS` — and `AUTO`, which picks it when the metric names both
topics and neither query is anything but a plain whole-topic count — reads no record at all. That
removes the 100 000-record ceiling (so a topic of any size is countable, and D2's "two floors
compared read as no gap" refusal now names the way out), and it removes **D4** rather than leaning
it: both counts come out of the same pair of `listOffsets` responses, so `readGapMs` is `0` because
it *is* zero. The cost is stated on the card and in the summary — this counts **offsets produced,
not records present**, so a transaction marker counts and a compacted record still counts, the same
distinction `getTopicActivity` draws for the dashboard's sparkline — and a query carrying a `WHERE`
cannot be answered this way, which `AUTO` does not pretend otherwise. **And a lifetime total stops
being able to fire**: on two topics running for months, a total outage that started an hour ago is
a fraction of a percent of the totals, under every threshold anyone would set — the metric was least
sensitive exactly when it mattered most. `window: SINCE_LAST_REFRESH` compares what each side
produced since the previous cycle; its first refresh publishes nothing and says why, a count that
went backwards is refused and the baseline re-established, and **a preview never writes a baseline**
(it would leave the running metric subtracting from an instant nobody measured). The suggestion
panel proposes both on every gap card it builds.

**The worst defect of the family was found while doing that**, and it had been there all along:
`left_value` and `right_value` were ordinary row columns, and every non-`metric_value` column
becomes a **Prometheus label**. Both move at every refresh of a live topic, so the label set changed
at every scrape and each time series carried exactly one data point — the metric could not be
graphed or alerted on at all, which is the only thing it exists for. Nothing said so, because the
registry stayed small: `pruneStaleSeries` deregistered the previous series each cycle, the tidy
version of the same defect. The reserved-column rule is a prefix (`__`) now rather than a list of
two names, so a measurement that belongs in the row and not in the label set says so by its name.

**Five more improvements followed, and four of them are about what the metric *means* rather than
what it costs.** *A row cap is not a window, and on two topics it is not even one window*:
`maxRowsPerSide` reads ten thousand records, which is an hour of a slow source and four minutes of a
busy target, so the pairs that survived were the ones whose two halves fell in the overlap — and
what that cost was not the average, computed over real pairs, but the **match rate beside it**,
depressed by the misalignment exactly as by a genuine downstream loss, which sends an operator
somewhere else entirely. `windowMs` reads both sides from the **same instant**, computed once rather
than resolved per read (`KafkaAdminService.getRecordsSinceTimestamp`, the instant form beside the
duration one), travelling as a third read mode — `since:<epochMillis>`, `FlinkSqlService.sinceReadMode` —
because that string already carries direct-reader-only meaning and the alternative was a field on
`QueryRequest` for a concept the planner cannot express. Which is why a window on a side the planner
would answer is **refused at save time, naming the side**: honoured on one side and ignored on the
other is worse than absent, the summary claiming one stretch of time while the reads covered two.
What a window cannot avoid is stated rather than corrected — a source produced near its end has its
target *after* it, outside both reads, so the trailing edge understates the rate by about one hop's
worth of traffic, the same thing `ProcessModelBuilder` says about the cases its own window cuts in
half. *The p95 was computed, put in the summary and alerted on by nobody*: an average holds still
while the worst decile doubles, which is the case the template exists to catch, so
`explorer_metric_correlation_latency_p95_ms` publishes it — and **only for the types that carry no
quantiles of their own**, a `SUMMARY` already publishing `explorer_metric_summary{quantile="0.95"}`.
*Total loss was the one state it could not express*: `PERCENT_GAP` divides by the right side, so
"left > 0, right = 0" — everything produced, nothing arrived — was refused as a division by zero and
published nothing, the most alarming reading staying silent while the alert fired happily at 3 %. It
reports **100**, and that is a definition for the case rather than the formula's limit (which is
infinity): 100 is the number a threshold is set against. Both sides at zero is not a loss and reads
0; `RATIO` stays refused. *And two counts over one topic came out of two full reads* — two
`COUNT(*)` under different `WHERE` clauses, which is what a same-topic gap is, each parsing up to
`AGGREGATE_SCAN_RECORDS` records thirty seconds apart, the per-cycle memoization keying on the SQL
and never bringing them together. `FlinkSqlService.executeSqlPair` runs them as a pair over a slot
the direct reader fills and reuses, deciding on **what the two reads turn out to be** (same topic,
same read mode, both aggregates) rather than on how the SQL looks: aggregates only, since their
fetch size is that constant whatever the statement says, while a projection stops early at its own
row limit and would leave a partial list behind for the other side. The gain is not only the read —
the two counts then describe the **same instant**, which is D4 for that case, and the summary says
`sharedScan` rather than leaving the card to infer it from `readGapMs` being zero: two separate
reads can land in one millisecond, and "these describe one instant" is a claim about how they were
taken, not a reading of the number.


**D10 stays open, and half of it closed without any measurement.** It is the cost of a two-query
metric on a single-threaded refresh loop — a property of the design rather than a defect in it.
`refreshIntervalMs` is now a metric's own cadence: every metric was recomputed on every tick, which
is right for a gauge over a cheap query and wrong for a template that reads two topics, and it can
only *slow* one down since the loop's tick is the floor. Skipping touches no state — the gauge keeps
the value it was last measured at, which is correct, and `explorer_metric_last_success_timestamp_seconds`
is what dates it — and an explicit "Refresh now" ignores the interval, a gesture never being a
cadence to be rationed. `explorer_metrics_refresh_duration_seconds` publishes what a cycle cost,
which was the one thing about the loop nobody could see: it is single threaded by design, so a cycle
outlasting its tick does not pile up threads but runs back to back, and the only symptom is a broker
doing more work than anyone asked for (said once per process, naming the two ways out). More threads
would be the wrong answer — the meter state assumes one writer. **The measurement is still untaken
and still the thing that settles the rest**: the wall time of one `refreshMetrics()` over a handful
of template metrics against the demo cluster, which that gauge now makes a reading rather than an
experiment somebody has to set up.

`SQL-EDITOR-AUDIT.md` is the review of the **SQL editor** (`QueryWorkbench.tsx` and its pure modules,
plus `QueryController` / `SqlQueryValidator` / `FlinkSqlService.executeSync`) along the four axes it
was asked for — reliability, ergonomics, optimisation, UI quality. All findings are fixed on this
codebase; the report also carries a "constaté, non traité" section (the absence of a mobile
layout, and two statements of identical text being indistinguishable to `resolveOrigin`).

`MOBILE-LAYOUT-SCOPE.md` scopes the one item that audit left open — the absence of a mobile
layout — with measurements taken by `docs/screenshots/layout-probe.mjs`, the product decision the
work depends on, and sized work items for each answer. **The product question is answered — the
application is not intended for phones** — which is the branch the document itself scoped: W0, W2,
W6 and W7 shipped (Option C plus the items that are right under every answer), **W1, W3, W4 and W8
are closed for want of a user, and W5 shipped** — tap targets under 24 × 24 are a WCAG 2.5.8 item
that mobile merely exposed, so it binds on a desktop-only product exactly as before. It went the
way `Checkbox` did: `layout-probe.mjs --detail` now groups undersized targets **by control**,
because a count is what hides that "genuinely varied" is usually one control repeated — a toggle
switch hand-written four times at 36 × 20 (now `components/ui/Switch`), the SQL editor's Preview
DDL button at 14 × 24 twenty-eight times over, the Dashboard's per-row Explore link at 19 × 19, and
`HelpTip` at 15 × 15. Measured: `dashboard` 32 → 5, `sql-editor` 67 → 39, and `TARGET_BUDGET`
lowered to match, so it cannot drift back — which it promptly proved by refusing the change that
made the Topic Explorer's table the default view, whose sortable headers (five at 17 px, the
partition one at 7 × 17) took that page 7 → 8. Fixed rather than budgeted, since a header is one
row and not every record; the page now measures 3. What is left is *text* in dense rows — sidebar topic
names, table links, sort headers — where 24 px means raising the row pitch of every dense view,
which is an information-density decision rather than an accessibility fix; that is recorded as a
decision, not as a claim of compliance. The measurements and the options are kept
in the document rather than deleted: the answer is recorded so the next person to open a 390 px
window on `/query` finds what was decided instead of re-deriving it, and W1 is named there as the
item to reopen if a narrow *desktop* window — `lg` is 1024 px, so half a 1440p screen falls under it
without being a phone — turns out to be painful in practice. What is load-bearing:

- **Choosing topics lives in `topicSelection.ts`**, shared by Stream Flow, Data Model and Process
  Mining: `addTopicEntries` merges a typed name, a pasted list or an expanded pattern into an
  existing selection, and `describeTopicEntry` states what did *not* go in — an unmatched pattern
  and a reached cap are two different ways of leaving with less than you asked for. The cap is a
  **parameter**, which is the only thing that differed between the screens: Data Model passes its
  endpoint's 30, Process Mining passes none because what bounds a profiling run is the prompt
  budget applied server-side over all topics, not a number of topics. The two primitives stay in
  `pages/streamFlowLogic.ts` where they are defined and tested.
- **The threshold itself lives in `breakpoints.ts`** (`DESKTOP_QUERY`, plus `useIsDesktop` for a
  screen whose *structure* changes rather than its ornament). `Layout` already carried the rule that
  the `lg:` classes describe one threshold and must move together; a second page hardcoding
  `'(min-width: 1024px)'` is exactly how that rule gets lost, so both import it.
- **The shell's "desktop" threshold is `lg`, not `md`** (imported by `components/Layout.tsx`,
  with matching `lg:` classes in `Sidebar` and `Header` — they describe one threshold and must move
  together). At 768 px the navigation used to stop being an off-canvas drawer and take 256 px in
  the flow while the SQL editor's schema browser kept its fixed 288 px, so Monaco fell from 64 px
  at 640 to **5 px** at 768: a tablet in landscape was worse off than a phone. Nobody chose that;
  two independent width decisions met. The measured effect of the move is 768 → 192 px and
  900 → 324 px. Below 640 the editor is still five pixels wide — that is the 288 px browser, W1,
  untouched.
- **`/query` says so under `lg`** (`components/query/NarrowWindowNotice.tsx`) and names the screens
  that do work at that width, which is the half that helps; it deliberately does not name the Topic
  Explorer, measured clean at 390 px but reachable only with a topic name, so a link there lands on
  the 404.
- **The probe used to print a ceiling as a measurement.** `clipped` was cut to eight entries before
  being counted, so five of seven pages read "8 clipped" at every width and the document concluded
  the clipping was width-independent truncation by design. It now counts the set, samples for
  `--detail`, excludes `sr-only` (which clips by construction) and reports whether the rest of each
  clipped element is reachable at all — a `title` on it, on a descendant or on an ancestor, or an
  ancestor that scrolls. W7 was scoped as confirming nothing hides unreachable content and **found
  the opposite**: a metric card's name, description and SQL line all carried `truncate` with no
  `title` anywhere, and the Cluster page's property names overflowed a grid cell with neither
  ellipsis nor title. Those are fixed; what remains is W8.
- **The `unreachable` column used to count closed tooltips.** `Tooltip` keeps its content mounted
  so `aria-describedby` always resolves; closed, the panel is only transparent, so its text went on
  counting in the `scrollWidth` of everything around it. The probe reported twenty such containers
  on the Topic Explorer and sixteen on Metrics — every one a closed tooltip, which is content that
  appears on demand rather than content cut off. It now takes those panels out of the layout for
  the measurement (`display: none`, restored after) instead of filtering them afterwards, so the
  browser recomputes and the ancestors come out right too. Measured: `topic-explorer` 20 → 2,
  `metrics` 16 → 4, `stream-flow` 2 → 0, target counts unchanged. What is left is real and named in
  the document; W7's answer holds in substance but no longer in the absolute form it was written in.
- **The probe opens things now, and it did not before.** It walked eight pages *at rest*, so
  every surface that exists only after a gesture — the four modals, a combobox's suggestion
  list — was outside every number it produced. That is not a gap in coverage but a gap in the
  one column that matters here: `unreachable` names precisely "this content is cut off and the
  rest is reachable nowhere", which is the class both truncation defects of 2026-08 belonged to,
  and it was not looking at them. `STATES` declares them beside `PAGES` (`sql-editor·confirm`,
  `sql-editor·ddl`, `metrics·editor`, `dashboard·palette`), they are reported and **gated** like
  a page — `MEASURED` feeds the `TARGET_BUDGET` guard, so a new state cannot be added without a
  budget — and a state that fails to open at a width it declares is a *failure*, never a silence,
  or a broken gesture would be indistinguishable from one nobody measures. Verified against the
  build that carried the defect rather than asserted: `sql-editor·confirm` reported
  `unreachable=5` against 1 for the page at rest, naming `p 374>294 "The window query replaces
  everything in …"` — the same two numbers that had been taken by hand — and comes back to 1 on
  the fixed build. Two things it taught. A state is measured **on the page already loaded**: a
  gesture costs a few hundred milliseconds where a navigation costs two to four thousand, which
  is why four of them add ~8 s to a 52 s `--check` instead of pushing the job past the timeout
  that already cost this mode its tablet viewport. And **a state must be able to report
  something**: an opened topic list was written, measured, and removed on that rule — the demo
  catalogue's names truncate at no width the probe walks, so the row could not move, and
  lengthening one would mean inventing data `setup-demo.sh` does not seed. The rule it would have
  guarded is pinned by a unit test instead, which depends on no data at all.
- **`unreachable` is gated, `clipped` is not, and the split was measured rather than argued.**
  Both were excluded from `--check` on one argument: clipping turns on text metrics, so a ceiling
  set on a developer machine would fail on a runner for a reason unrelated to the change. Reading
  the CI job's own probe output against a local run of the same commit, over the 21 rows `--check`
  walks, showed the argument covers one column and not the other — **`clipped` differed on 4 rows
  (all four on the SQL editor, 2 against 3), `unreachable` on none**. That is what the two count:
  where a string happens to wrap, versus whether any path to the rest of it exists — a `title`, a
  scrollable ancestor — which is a fact about the markup. `UNREACHABLE_BUDGET` therefore gates the
  second, with the same "no worse than today" ceilings and the same guard as `TARGET_BUDGET` (a
  measured row with no entry fails). It is only worth gating because Monaco left the measurement
  first: a ceiling over 18 findings of which 7 were the editor's own scroll layers would have
  capped noise.
- **Monaco's insides are out of the measurement.** The editor scrolls its own synthetic surface:
  `.monaco-scrollable-element` reports a `scrollWidth` of **16 777 216 px** and `.overflow-guard`
  clips by construction. The column claims to name "content cut off whose rest is reachable
  nowhere", and a text editor that scrolls is the opposite of that — measured on `main`, **7 of
  the 18 `unreachable` findings were those layers**, which is what made the column unsafe to gate:
  a ceiling would have capped noise. The rule excludes the whole editor rather than one class,
  since Monaco stacks several and a clipping inside its own rendering is Monaco's defect, not this
  application's. With them gone, and with the one real finding they were crowding out fixed
  (`SuggestionsPanel` truncated the name of the metric a proposal is already covered by, with
  nothing carrying it), **every page and state reports zero unreachable at desktop width**, which
  is the precondition for ever gating that column.
- **`--detail` reports the innermost clipped element, not the pile above it.** A container is
  usually cut off only because its child is, so one defect surfaced three or four times under the
  class names of a stack of layout `div`s — and since the sample is capped at eight in DOM order,
  those duplicates crowded out the real findings, a modal (rendered last in the document) falling
  past the cut every time. Same argument as `tooSmallGroups`: the count stays the whole set, the
  sample says *which* element.
- Re-run the probe before trusting any number in that document, and read a zero in its
  `unreachable` column as unconfirmed rather than proven: the pages that fetch asynchronously clip
  nothing while their cards are still arriving. Making those counts stable enough to gate a build
  is W6.

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

The version is read from `pom.xml` rather than restated here from memory: this heading carried
`2.16.1` for a long while, which is what `check-config-table.py`'s `VERSION_CLAIMS` pass now
exists to catch.

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

## Project governance & supply chain

The community-health files are the ones GitHub looks for, and the ones a stranger reads before
filing anything: `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`, `SUPPORT.md`,
`CHANGELOG.md`, `.github/CODEOWNERS`, the two issue templates plus `ISSUE_TEMPLATE/config.yml`,
and `pull_request_template.md`.

- **`CONTRIBUTING.md` names `mvn verify`, never `mvn test`.** It documented the latter, which runs
  neither ESLint nor Vitest — so a contributor following the file was green locally and red in CI.
  It also carries the `verify-offline.sh` escape hatch, since a contributor behind a proxy that
  blocks `packages.confluent.io` cannot build at all otherwise.
- **`SECURITY.md` supports the latest release only**, and says so rather than naming a version. It
  named `0.0.1` for eleven releases after that stopped being true. It also states plainly that the
  app has no authentication and that `POST /api/config` repoints the cluster at runtime — that is
  a deployment constraint, not a vulnerability, and a report saying otherwise can be answered by
  pointing at it.
- **`ISSUE_TEMPLATE/config.yml` keeps blank issues enabled.** The two templates cover a bug and a
  feature request; a usage question fits neither, and forcing it into one produces a worse issue.
  There is deliberately **no Discussions link**: the feature is disabled on the repository, and a
  contact link that 404s is worse than none — enable Discussions and add the link together.
- **`CODEOWNERS` requests reviews, it does not require them.** Requiring them is a branch
  protection rule on `main` ("Require review from Code Owners"), which lives in the repository
  settings and is the thing that turns the file into a gate.

Three workflows beyond `ci.yml` / `release.yml` / `dockerhub-description.yml`:

- **`codeql.yml`** — static analysis of the *source*, on push, pull request and weekly. Trivy in
  `ci.yml` scans the released image, which means its OS packages and the jars it ships; it never
  reads a line of Java or TypeScript. That gap mattered here more than most: this app parses
  untrusted XML, assembles SQL, masks credentials inside generated DDL, and ships unauthenticated.
  The Java half uses `build-mode: manual` with `./mvnw -DskipTests -P '!build-frontend' compile` —
  `autobuild` would activate `build-frontend` (it is `activeByDefault`) and download a whole Node
  toolchain to rebuild a SPA the javascript-typescript half already reads from source.
  **It also copies its findings into the job log** — severity, query id, file and line, one row
  each. The check a pull request shows says `8 new alerts including 1 high severity` and nothing
  else: the detail lives in the Security tab, which needs write access on the repository, and the
  annotations API is out of reach from a CI environment. So "CodeQL is red" was diagnosed by
  guessing, and a guessed fix to an injection query is precisely the change that removes a
  deliberate behaviour to silence a check. Knowing *which* rows they are is also what lets them be
  crossed against the diff — which is how it was established that not one of the tree's twenty-nine
  security findings sits on a line the branch reporting them had written. The step cannot fail the
  job (`if: always()` so a red `analyze` is still explained, `exit 0` so a missing or unexpected
  SARIF stays a silence rather than a breakage), uploads nothing, and changes no verdict.
- **`security.yml`** — `dependency-review` on pull requests (fails on a **newly introduced** high
  severity advisory, and on licences that cannot ship inside an AGPL artifact), plus TruffleHog
  over the full history with `--only-verified`. The severity gate is narrow on purpose: it says
  nothing about the existing tree, so it cannot start failing builds on a morning nobody chose.
  TruffleHog **does** fail the build where Trivy only reports, and the asymmetry is deliberate —
  Trivy's findings are often unfixable transitive CVEs, whereas a verified secret is a live
  credential in a public repository.

- **`scorecard.yml`** — OpenSSF Scorecard, weekly plus on every `branch_protection_rule` event
  (that one change otherwise leaves no trace in the repository). It overlaps with nothing else
  here on purpose: CodeQL reads the code, Trivy the image, dependency-review what a pull request
  adds, and Scorecard the *project* — signed releases, pinned actions, branch protection, a
  published security policy. Those decay silently, because no build ever fails when they do.
  `publish_results: true` is what makes the README badge resolve. The Branch-Protection check
  needs a classic PAT as the optional `SCORECARD_TOKEN` secret — the default `GITHUB_TOKEN`
  cannot read those settings, so without it that one check reports unknown while the rest score
  normally; it stays optional so a fork is not failed on a secret it cannot have.

- **`image-pins.yml`** — `check-image-pins.py --published`, weekly plus `workflow_dispatch`. The
  Explorer pin of the published-image stacks is hand-written as `${EXPLORER_IMAGE_TAG:-<version>}`,
  which Dependabot cannot read, so the only thing that moves it is `release.yml`'s bump pull
  request — and that step is `continue-on-error` by design, the image being published by the time
  it runs. The hole is not that posture but its consequence: when the step opens nothing,
  **nothing says so**, and the pin goes stale in silence until the next pull request touching a
  file `hub-changes` gates on fails a check about something it did not change. `release.yml`'s own
  comment records that happening twice in one day; it then happened again across v1.9.3 and
  v1.9.4. So the release keeps trying to fix it and this asks the question on a timer, which is
  the same argument Scorecard rests on — what decays silently needs something looking at it when
  nothing else is happening. It deliberately does **not** open the bump pull request: that logic
  lives in `release.yml`, and a second copy is the drift this repository keeps removing. A
  registry that cannot be reached is a `::warning::` inside the script rather than a red run, so
  a failure here means the pin really is behind, and its message names the three files.

**The JAR is signed, keylessly** (`actions/attest-build-provenance` in `release.yml`'s `build`
job, hence the `id-token: write` + `attestations: write` on it). The image had a full SLSA
provenance and an SBOM while the JAR beside it on the same Release had only `SHA256SUMS.txt` —
and a checksum published in the same place as the file it describes answers "did this arrive
intact", never "did this come from here". Sigstore rather than GPG because there is no key to
store, leak or rotate: the signing identity *is* this workflow at this commit, in a public
transparency log. Consumers verify with `gh attestation verify <jar> --repo devdownin/Kafkaexplorer`;
`SECURITY.md` carries that, the image equivalent, and the reason to pin by digest in production.

**Every action is pinned to a commit SHA**, with the version in a trailing comment
(`actions/checkout@3d3c42e… # v7`). A tag is mutable and `softprops`, `peter-evans`,
`aquasecurity` and `trufflesecurity` all run with credentials or write scope. Dependabot updates
SHA pins and rewrites the comment, so this costs nothing to maintain — when adding an action,
pin it the same way rather than reaching for the tag.

`.gitattributes` normalises text to LF in the repository while checking `*.bat` / `*.cmd` /
`*.ps1` out as CRLF. The tree mixes Unix entry points (`mvnw`, the `setup-*.sh` seeders,
everything the images run) with Windows ones, and a CRLF that reaches a shell script is the
classic `bad interpreter: /bin/sh^M` — inside a container, where it is awkward to diagnose.
`.editorconfig` describes what the tree already does (Java 4 spaces, frontend/YAML/shell 2,
`pom.xml` tabs); it is not an invitation to reformat.

