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
`FLINK-JOBS-AUDIT.md`, `INSERT-SCOPE.md`, `METRICS-TWO-QUERY-AUDIT.md`,
`MOBILE-LAYOUT-SCOPE.md`, `PROCESS-MINING-LLM-SCOPE.md`, `PROCESS-MINING-LLM-CALLS-AUDIT.md`,
`SQL-EDITOR-AUDIT.md`, `CODE-SIMPLIFICATION-AUDIT.md`.

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
  - **A busy runtime is a wait, not a broken engine.** The coordinator serialises access to the Flink runtime, so a long DDL, a submission, or a job being cancelled makes every concurrent caller busy — for as long as that lasts and no longer. `FlinkRuntimeBusyException` was one line of `ENGINE_ERROR_TEXT`, which meant a SELECT fell back to the direct reader; that reader knows only Kafka topics, so asked about a table sitting in the catalogue it answered `Table 'x' not found. No matching Kafka topic exists.` — a confident sentence about a name that is perfectly correct, on a query that succeeds on the next try. It is `SqlErrorClassifier.Kind.ENGINE_BUSY` now, checked before every other pattern (the coordinator's message quotes the operation and the caller, so it can contain a table or rule name that would otherwise decide the classification), and `refuseIfRuntimeBusy` reports the wait — the operation, the budget, and what was holding the runtime, which is the only actionable part — instead of substituting another engine's opinion. **It does not count toward the circuit breaker**, on the same rule a timeout already follows: three such moments, which is what three metrics refreshing together produce every thirty seconds, took the planner out for ten minutes while nothing was wrong with it. Only the planner path changed: a busy runtime raised during auto-registration already reached `executeSql`'s outer catch, which reports without falling back.
  - **The circuit breaker says so, on the queries it affects.** `flinkSelectDisabled` was written in one place and read in one place and surfaced nowhere, so once it latched — for the lifetime of the process — every later SELECT came back as `engine: KAFKA_DIRECT`, without JOIN or subquery support, with nothing saying the degradation had become permanent. A fallen-back result now carries `plannerUnavailableMessage()` in `QueryResult.warnings()`, which the editor already renders above the grid, and `isFlinkSelectDisabled()` exposes the latch. **The latch decays**, which is that revisit: it needed a restart to clear, defensible while the assumed cause was a Flink version defect — a fault that does not repair itself — and wrong for the *environmental* one this repository has since seen trip it (a job that could not obtain its slots, gone the moment the configuration is fixed). Past `FLINK_SELECT_RETRY_AFTER_MS` (10 minutes) one attempt is allowed; it succeeds and `clearFlinkSelectLatch` reopens the breaker, it fails and the counter re-arms. The permit is **consumed at the gate rather than judged by the outcome**, which is what makes the bound true: a timed-out attempt passes through neither the success path nor the counted-failure path, so re-arming there would let a planner that never finishes be retried on *every* query once the interval elapsed. Several threads can pass together and each pay one attempt — bounded, and a lock on the read path would cost more than it saves. A constant rather than a property: no deployment has been named that needs another value. `plannerUnavailableMessage()` says so, since a message still promising a restart would be the stale claim this file keeps removing.
  - **Job mode registers its source too.** `submitJob` went straight to the planner, so the shortcut the sidebar itself proposes — an `INSERT INTO <sink> SELECT … FROM <a topic's table>` — answered "Object not found" until some *other* gesture, a plain SELECT, had registered that source in this process. Only the **source**: `extractPrimaryTable` reads `FROM`, so the INSERT target is excluded by construction and deliberately — deriving a sink schema from an empty target topic yields `raw_value STRING` and an arity failure, which is a worse answer than "unknown table". The guard that admitted only `SELECT` now admits any `INSERT`, past a leading CTE like everything else — **any**, and not `INSERT INTO` alone, because the job-mode guard classifies on the statement's first word and therefore lets `INSERT OVERWRITE` through: that half-acceptance left the same defect standing one keyword over, its source never registered and its correct topic name answered with "Object not found". Letting Flink decide is the right arbitration there — its refusal names the cause exactly (this sink does not implement `SupportsOverwrite`) where a refusal written here would only repeat that job mode wants an INSERT — so what job mode agrees to submit, auto-registration has to agree to serve. **And every source is registered, not only the first**: `extractSourceTables` reads the `JOIN`s beside the `FROM`, because a statement reading two topics registered one and let the planner answer "Object not found" about the other — a name that is perfectly correct, on the shape a JOIN *is*. The primary table stays first, since `deferToDirect` is decided on it and on nothing else: it is the only one the direct reader would read. **A `STATEMENT SET` is submitted too** (`isJobModeStatement`, one definition the read path and the job path share): it is Flink's fan-out — several INSERTs from one source in a *single* job, so one read of the topic — where the equivalent, N submissions, costs N reads and N embedded clusters. **A submission keeps the query id its caller chose** (`resolveQueryId`, as a read already did): the id used to exist only in the response, so a response that never arrived left a job running that nothing could name. And Flink's "only single statement supported" is re-worded to name the two ways out (Run all, or a STATEMENT SET) rather than stating a limit and stopping there. And `AutoRegResult.deferToDirect()` has no meaning here, there being no direct reader to catch the query: an uninferable source becomes a refusal that names the cause and says what to do (write the `CREATE TABLE` yourself), instead of the planner's complaint about a name that is perfectly correct.
  - **The shape of a statement is asked of Flink's own grammar** (`SqlAst`): sources and their aliases, the row cap, the projection item by item, the WHERE split into applicable equalities and the rest, and whether a join, a subquery or a cross join is present. Calcite's `SqlParser` with Flink's factory and dialect, **without a catalogue** — `TableEnvironment`'s parser validates, so it cannot answer about a topic that is not registered yet, which is exactly when the name is needed. Every lexical path stays underneath and answers when the grammar refuses, so a misconfigured parser costs precision rather than a broken query. It fixes by construction what a pattern could not see: an equality under an `OR` is no longer applied on its own (`WHERE a='x' OR b='y'` filtered on `a` alone and returned nothing), a `HAVING` no longer leaks into the row filter, a projection is no longer split on the commas inside a function call, an expression this reader cannot evaluate is refused by name instead of coming back as a column of nulls, and `FROM a, b` is recognised as the cross join it is — the guard forbade the cartesian product under one spelling only.
  - **The two engines are compared to each other against a real broker**, which nothing did: each was tested alone, one against a planner and the other against mocks, so a reader that dropped a predicate or lost a column passed everything. Five shapes and a count, the planner side bounded so the comparison cannot become a timeout.
  - **Every lexical pass reads outside the string literals** (`SqlStatements.outsideLiterals`). A motif applied to the raw statement finds its keywords inside quoted values, and the result is never an error — it is another query than the one written: a `--` in a value truncated the statement when comments were stripped, `'CROSS JOIN'` in a value was refused by the guard, a table named in a value was read as the source (and auto-registered), and a quoted `limit 1` cut the page. The scanner blanks the *contents* of literals and backtick identifiers, keeps the delimiters and preserves every position, so a caller finds its boundaries on it and slices the original when it needs the value. It replaces the private copy `isCreateTableAsSelect` carried, and comment stripping is now the same single pass.
  - **A column qualified by its table or its alias resolves, under its bare name.** `SELECT o.state FROM t o WHERE o.state = 'X'` came back with zero rows — the key `o.state` was sought whole, then walked into an object `o` that does not exist, so every row was rejected — and the projection returned a column of nulls beside it. `tableQualifiers` / `withoutQualifier` / `resolveColumn` strip the prefix only when it is the table's or its alias's, and only after the written path has failed, so a flattened XML key called `o.state` still wins. WHERE, projection, GROUP BY and the aggregates all go through it; the output name is the planner's.
  - **A projection that stopped on its scan ceiling says so**, as an aggregate already did: an empty grid from a filtered read is otherwise indistinguishable from "nothing matches", and on a topic older than the ceiling that is wrong by construction.
  - **A window over a Kafka topic is asked of the direct reader by name, not after a failure.** A Kafka source never ends, so a window's last bucket never closes and the collection loop returns only at the row cap or at the budget: a windowed query producing fewer rows than the cap — the ordinary one — could only spend its ten seconds and be answered here anyway, the rows it had already collected thrown away with the timeout. Three conditions, each closing a way of returning wrong rows: the shape must read one source (`MetricService.namesOneSourceOnly`, the structural half of `isSingleTableRead` rather than a second copy of it, since a joined window would have that reader read one table and ignore the rest), the table must be one this application registered from a topic and not one an operator typed (`FlinkTableStore` tells them apart — a definition of theirs may carry the bounded scan and the watermark that let the planner answer), and the statement must not carry its own `OPTIONS(…)` hint, which says what it wants of the source. The result says which engine answered and how to get the other one; the read mode is honoured on the way, so `Latest` and `Earliest` no longer mean two different windows.
  - **A window over a column with no watermark is answered, not blamed on the engine.** Flink refuses to plan `TABLE(TUMBLE(TABLE t, DESCRIPTOR(<col>), …))` unless `<col>` is a *time attribute* — a column some `WATERMARK` clause declares — and it says so wrapped in the Calcite rule that failed, its arguments and the whole `rel#…` tree. That whole block used to be pasted into the editor's warnings behind a sentence about the JOINs and subqueries the query does not contain, and it counted as an engine fault, so three windowed runs latched the circuit breaker and every query of the process lost the planner for ten minutes. `windowNeedsATimeAttribute` names the column, says a watermark is what it lacks, shows the clause, and appends the planner's sentence with the plan stripped (`SqlErrorClassifier.readable`); the counter is left alone, since a table definition is not a broken engine. It still falls back — `kafkaWindowSelect` really does compute that window from the payload's own timestamp field — where an `OVER` over the same column is refused outright, that reader having no `OVER` to ignore it with. With the routing above, what still reaches it is a hand-written table and a statement that bounds its own scan, rather than every topic in the cluster. `docs/notes/backend-services.md` carries the measurements.
  - **SQL comments**: `--` line comments and `/* */` block comments are stripped before any keyword checks. **A hint is not a comment**, and is kept: `/*+ … */` is Calcite's and Flink's syntax for table options (`FROM t /*+ OPTIONS('scan.startup.mode'='earliest-offset') */`), distinguished from a comment by the single `+` after the opening. Stripping it meant no hint an operator wrote in the editor had ever reached the planner — silently, the engine's own log line printing the query without it. What that cost went beyond the editor: the experiment that concluded this connector refuses `scan.bounded.mode` was run through a hint that never left, and the option genuinely refused in the same statement was a different one (see `MetricService`'s `SCAN_STARTUP_EARLIEST`). A query beginning with a comment line is valid.
- `FlinkWarmupService` — one throw-away `SELECT 1` after startup, on a daemon thread, so a user's first query does not pay the ~4 s the planner costs to wake up. Off with `explorer.flink-warmup-enabled: false`. What it proves is narrower than it looks, and `docs/notes/backend-services.md` says why.


- `SchemaInferenceService` — samples messages and delegates to `JsonSchemaInferrer` / `XmlSchemaInferrer` / `AvroSchemaInferrer` (inferred column order is deterministic — `LinkedHashMap`)
- `DdlGeneratorService` — auto-generates Flink `CREATE TABLE` DDL from inferred schemas. `maskSensitiveProperties()` (static) redacts credentials and **must** be applied to any DDL that reaches the UI *or a log*; internal registration uses the unmasked DDL. A format option carries the `value.` prefix when the format does, and the connector rather than taste enforces that. **The `event_time` it adds is watermarked**, five seconds of lateness, and that one line is what makes an event-time construct legal on a generated table at all — an `OVER`, an `ORDER BY` on time, and a window on the statement that bounds its own scan: without it the column is an ordinary `TIMESTAMP(3)` and the planner refuses all three. It is added only when the column is this generator's own; an `event_time` from the payload keeps its inferred type and gets nothing. All three rules, and what they cost when broken, are in `docs/notes/backend-services.md`.
  - Flow latency memoizes `topic → Map<id, first timestamp>` for the run: a topic in the middle of a flow is both a source and a target and was otherwise fetched twice.
  - `FlowAudit.overallHealthScore` is a **0..1 ratio**, not a percentage (the UI multiplies by 100).
  - `totalMessages` sums the per-topic counts actually reported, not `topicSizes`, so the KPI and the table column agree when exact counts ran.

- `SqlQueryValidator` — cross-join and system-table guard, **not** the statement whitelist. The cross-join half now asks `SqlAst`, because `FROM a, b` is a cross join that the text check could not see and the plan heuristic beside it never fired on. It checks the two `ExplorerConfig` switches and returns silently for anything that is not a `SELECT` or an `EXPLAIN` — which is what used to let `INSERT INTO` reach Flink Job mode. That mode is gone, so nothing downstream of this class accepts an INSERT any more; the silent return stays because the rule it states is about *this* guard's scope, not about which statements exist. The whitelist ("Only SELECT, EXPLAIN, SHOW, DESCRIBE and CREATE TABLE statements are allowed.") is enforced by `FlinkSqlService.executeSql`, and this file described the wrong class for it. **`CREATE TABLE … AS SELECT` is refused there**, because that whitelist classifies on the first word and a CTAS is an INSERT wearing a CREATE TABLE hat — on the *read* path, the one that sends every `INSERT` to Job mode. It created the table **and started the job feeding it**, without going through `submitJob`: nothing in the registry, outside `explorer.max-concurrent-jobs`, and with no id for `POST /api/query/cancel/{queryId}` to reach — on a Kafka source, a continuous job nothing can see or stop. The refusal names the two gestures that do the same thing visibly (`CREATE TABLE`, then the `INSERT INTO` in Job mode, which Run all chains). `isCreateTableAsSelect` blanks string literals before looking, since `WITH ('note' = 'as select …')` is an option value and refusing the only DDL this application accepts over the contents of a string would be the false positive that matters. Its `EXPLAIN` probe still swallows unresolved-table errors (it runs before auto-registration, so those are expected), but a **parse** error is rethrown as `IllegalArgumentException`: syntax never depends on the catalog, so `POST /api/query/validate` can reject a typo with its line/column before the query touches Kafka. **That probe covers an INSERT too**, and did not: the check returned early for anything that was not a SELECT or an EXPLAIN, while the editor calls `/api/query/validate` before *every* Run, job mode included — so an INSERT was pre-flighted by nothing, and its typo was found by submitting it, which spends one of the slots `explorer.max-concurrent-jobs` bounds. Measured: `explainSql` on an INSERT separates exactly the two cases this needs — a syntax error surfaces as a parser failure (rejected here with its position), an unresolved table stays a resolution failure and is swallowed, which it must be, since this runs *before* the sources are auto-registered. The classification reads past a leading CTE like everywhere else; without that a `WITH … SELECT` was not validated either.
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


  **What Job mode left behind, and what became of each.** #323 removed the `SubmittedJobPanel` and its wiring along with the feature — five pieces of state, a polling effect, a Stop handler, all referencing an `executionMode` that no longer existed, which is why the SPA had stopped type-checking on `main`. #325 then removed the three pieces that still compiled and simply had no caller — `GET /api/query/sink-ddl` (with `DdlGeneratorService.sinkColumns`), `insertTargetAndSource`, `flinkJobHistory.isJobTerminal` — plus the shipped-but-unread `explorer.inference-poll-timeout-ms`, and added `docs/check-config-yaml.py` so a settable, inert `explorer.*` key fails the build instead of sitting there. **The last of it was the Dashboard's own "Flink SQL Jobs" table**, which had a caller and was the reason the three job reads and the persistence behind them survived two clean-ups: without a submission it no longer listed jobs but the synchronous reads `FlinkJobStore` recorded on the way past, all finished, one per Run and ~2 900 a day per planner-answered metric — a panel of running jobs that never shows one, which is misleading rather than merely reduced. It went, and `GET /api/query/jobs`, `GET /api/query/jobs/{queryId}`, `POST /api/query/jobs/{queryId}/cancel`, `FlinkJobService`, `FlinkJobStore`, `FlinkManagedJobDetails`, `FlinkJobHistoryEntry` and two `explorer.*` keys with it. The one kept is `FlinkSqlService.submitJob` with the `explorer.max-concurrent-jobs` cap that guards it: unreachable over HTTP, and not dead code — it is where `FlinkSqlServiceInsertVariantsTest` establishes what an INSERT means here. What survives in the browser is the rule the two mode guards served: an `INSERT` is refused there, **with its cause**, rather than sent to the engine to be answered by the whitelist. `docs/notes/frontend.md` carries the reasoning for each.


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

**Shared-runtime state is reset between cases, and the culprit is the one that fails.** The two `FlinkSqlService` classes that drive a real MiniCluster hold it for the whole class, so a case that leaves a cancelled job behind makes the *next* one — any of them — read `The Flink runtime was busy`, and the symptom lands on an innocent. An `@AfterEach` in each waits for the runtime to be free again, or for the job registry to empty; `@BeforeEach` clears the SELECT circuit breaker beside the mocks, that latch having the lifetime of the process by design. `docs/notes/ci-and-checks.md` carries what it cost to find.

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

The reports are named under **Deep-dive notes** above; what each one concluded, what has since
shipped out of it and what it left open is in `docs/notes/audits.md`. Read that note before
changing any area a report covers — most of what those audits fixed is load-bearing, and none of
it is obvious from the code that remains.

## Security

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

## Claude API Java SDK (anthropic-java 2.59.0)

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

`docs/notes/ci-and-checks.md` carries the community-health files, the four workflows beyond
`ci.yml` / `release.yml` / `dockerhub-description.yml` (`codeql.yml`, `security.yml`,
`scorecard.yml`, `image-pins.yml`), the keyless JAR signing, and what `.gitattributes` and
`.editorconfig` are for. Two rules bind whether or not that note has been read:

- **Every action is pinned to a commit SHA**, with the version in a trailing comment
  (`actions/checkout@3d3c42e… # v7`). A tag is mutable, and several of these actions run with
  credentials or write scope. When adding an action, pin it the same way rather than reaching for
  the tag.
- **`CONTRIBUTING.md` names `mvn verify`, never `mvn test`** — the latter runs neither ESLint nor
  Vitest, so a contributor following it is green locally and red in CI.
