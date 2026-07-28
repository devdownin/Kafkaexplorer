# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This App Does

Kafka SQL Explorer is a Spring Boot + React web application for exploring Kafka clusters and executing Flink SQL queries against Kafka topics. It provides schema inference, message navigation, lineage tracking, stream flow tracing, and cluster auditing.

## Commands

### Backend (Maven / Spring Boot)

```bash
# Full build (includes frontend via frontend-maven-plugin)
mvn clean package

# Run the app
./mvnw spring-boot:run

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=AuditServiceTest

# Build without tests
mvn clean package -DskipTests
```

A Maven wrapper is checked in (`./mvnw`, Maven 3.9.9, `distributionType=only-script` so there is no wrapper JAR in the tree). Both CI workflows build through it.

#### When `packages.confluent.io` is blocked

`io.confluent:kafka-avro-serializer` and `io.confluent:kafka-schema-registry-client` are published **only** on `packages.confluent.io` — they are not on Maven Central. Behind a proxy that blocks that host, Maven cannot even *collect* the dependency graph (`flink-avro-confluent-registry` pulls the schema-registry client transitively), so it downloads nothing and every Maven goal fails before compiling a single file.

`./verify-offline.sh` gives back a local compile-and-test loop in that situation: it resolves dependencies from a temporary Confluent-free pom, generates stubs for the five Confluent types the code touches, compiles main + test with `javac`, and runs the suite with the JUnit console launcher. It accepts extra ConsoleLauncher arguments, e.g. `./verify-offline.sh "--include-classname=.*LineageServiceTest"`. Use `--include-classname` (a filter), **not** `--select-class`: the script always passes `--scan-classpath`, and the JUnit 6 launcher refuses "scanning the classpath and using explicit selectors at the same time".

Two things to know: Avro / Schema Registry paths run against the stubs, not the real Confluent client, so those results are indicative only; and the launcher must be started as `java -cp … org.junit.platform.console.ConsoleLauncher`, never `java -jar`. With `-jar` the system classpath holds only the launcher, Flink's job-graph deserialization cannot find `flink-table-runtime`, every SELECT fails to submit, the planner circuit breaker trips, and a dozen `FlinkSqlServiceTest` cases fail for no real reason. CI remains the authority — it builds against the real Confluent jars.

### Frontend (React / Vite)

```bash
cd src/main/webapp

npm install          # Install dependencies
npm run dev          # Dev server with hot reload (proxies /api to localhost:8080)
npm run build        # Production build (tsc + vite) → src/main/resources/static/
npm run lint         # ESLint (TS/TSX, --max-warnings 0)
npm test             # Vitest (jsdom + @testing-library/react); test:watch for watch mode
```

### Docker

All bundled compose stacks run **Kafka 4.2 in KRaft mode** (`apache/kafka:4.2.0`, single combined broker+controller node — no Zookeeper anywhere, including CI and `docker-compose.release.yml`).

```bash
# Kafka 4.2 (KRaft) + Schema Registry + app + demo topics (recommended)
docker compose -f docker-compose-kafka4.yml up -d

# Kafka 4.2 (KRaft) + app + demo topics, without Schema Registry
docker compose up -d

# Demo data setup (creates 70+ topics)
./setup-demo.sh localhost:9092
```

KRaft single-node notes: the `apache/kafka` image takes the cluster id via the `CLUSTER_ID` env var (a `KAFKA_CLUSTER_ID` var would be translated into an ignored `cluster.id` server property); all internal-topic replication factors (`offsets`, `transaction state`, share-group state) are pinned to 1 and `__consumer_offsets` runs with a single partition for faster startup. Kafka data persists in a named `kafka_data` volume (`KAFKA_LOG_DIRS=/var/lib/kafka/data`) so `internal.*` topics survive `docker compose down` (`down -v` resets); the image runs as non-root `appuser`, so a `kafka-data-init` one-shot service chowns the volume before the broker starts — don't remove it.

### Typical local dev workflow

1. `docker compose -f docker-compose-kafka4.yml up kafka` — start Kafka only
2. `./mvnw spring-boot:run` — start backend on port 8080
3. `cd src/main/webapp && npm run dev` — start frontend dev server (port 5173)

## Architecture

### Stack

- **Backend**: Spring Boot 4.1.x, Java 21 (`java.version` in pom.xml — Flink 2.x supports Java 17/21, not 25), embedded Apache Flink 2.3.x (`flink.version` in pom.xml). Kafka connector: `flink-connector-kafka:4.0.1-2.0` (the `-2.0` suffix covers the whole Flink 2.x line).
- **Frontend**: React 19, TypeScript, Vite, Tailwind CSS, Monaco Editor
- **Kafka**: `kafka-clients` 4.2.0 (`kafka.version` override in pom.xml — moves in lockstep with `confluent.version` 8.x, see the pom comment) — compatible with Kafka 2.1+ brokers on the classic protocol; all bundled Docker stacks run Kafka 4.2 in KRaft mode. The live Process Mining consumer can opt into the KIP-848 rebalance protocol via `kafka.consumer-group-protocol: consumer` (default `classic`).
- **Build**: Single JAR — Maven's `frontend-maven-plugin` builds the React app and copies it to `src/main/resources/static/`

### Backend Layers

```
web/          REST controllers (one per domain: Query, Topic, Audit, Lineage, etc.)
service/      Business logic — FlinkSqlService is the core engine
config/       Spring configuration (Kafka, Flink, Explorer settings, CORS)
domain/       DTOs — implemented as Java Records
parser/       JSON, XML, and Avro (via Confluent Schema Registry) schema inference
```

**Key services:**
- `FlinkSqlService` — executes SQL against Kafka topics using embedded Flink `LocalEnvironment`; per-request table registration ensures isolation
  - **SELECT execution**: when `explorer.flink-select-enabled` is true (default), SELECT runs through the real Flink planner via `executeViaFlinkPlanner()` and normally reports `engine=FLINK`, falling back to `kafkaDirectSelect()` only on a planner failure. The historical `FlinkRelMetadataQuery` NPE (`metadataHandlerProvider`, reproduced on Flink 1.18/1.20/2.0) that once forced every SELECT through the direct reader is **fixed**: `FlinkRuntimeCoordinator.ensureFlinkMetadataProvider()` pre-seeds Calcite's `RelMetadataQueryBase.THREAD_PROVIDERS` ThreadLocal with Flink's provider (Flink 2.x leaves it unset before the VolcanoPlanner's cost pass). A process-lifetime circuit breaker (`FLINK_SELECT_FAILURE_THRESHOLD` = 3) still guards against any residual planner failure so it does not add a failed attempt to every query (timeouts don't count toward it). `CREATE TABLE` / `EXPLAIN` always go through Flink. Engine used is reported in `QueryResult.engine()` (`FLINK` vs `KAFKA_DIRECT`).
  - **Only an engine failure falls back.** `SqlErrorClassifier` splits a planner failure into `USER_ERROR` (parse error, unknown table/column, type mismatch, restricted statement) and `ENGINE_ERROR`; `rejectIfUserError()` returns the planner's own message for the former instead of retrying on the direct reader. Falling back on a bad query is worse than useless — the direct reader only regex-matches the table name out of `FROM`, so `SELECT id, FROM orders` or a misspelled column came back as *rows*, and the planner's precise complaint (with its line and column) was discarded. User errors also don't count toward the circuit breaker, or three typos would disable the Flink planner for the rest of the process. One exception, carried by `AutoRegResult.deferToDirect()`: when a topic exists but schema inference returned nothing, registration is deliberately skipped, so the planner's "not found" is our doing and must still fall back.
  - **Cancelling a synchronous run** needs the id up front: `QueryRequest.queryId` is client-supplied, because a server-generated id only reaches the caller in the response, by which point the query is over. `FlinkSqlService.resolveQueryId()` accepts it only when it matches `[A-Za-z0-9_-]{8,64}` (it becomes a job-store key and appears in logs) and mints a UUID otherwise. `POST /api/query/cancel/{queryId}` then reaches the live `JobClient`; the UI also aborts the HTTP request, which is the part that is guaranteed regardless of engine — a `KAFKA_DIRECT` scan has no Flink job to cancel and finishes its in-flight fetch server-side.
  - `SqlErrorClassifier.explain()` flattens a throwable's cause chain into one never-blank message (Flink wraps the useful Calcite text — including the line/column — inside a generic outer exception). It replaced bare `e.getMessage()` on the error paths: that is `null` for a `NullPointerException`, which nulled out `QueryResult.error()` and made the UI report a crash as a successful run of zero rows.
  - `kafkaDirectSelect()` (fallback engine) supports aggregate functions (COUNT/SUM/AVG/MAX/MIN with optional GROUP BY) computed in-process over fetched Kafka messages. SQL must alias the result column (e.g. `COUNT(*) AS metric_value`). COUNT values are returned as `long` (integral), other aggregates as `double`.
  - For aggregate queries, up to 100 000 messages are fetched (earliest-offset). Plain projections fetch `limit + 20`; when a `WHERE` clause is present the scan widens to `max(5000, limit×100)` capped at 100 000 (the row loop still stops at `limit` matches).
  - **WHERE filtering**: simple `col = 'value'` conditions. Anything else — `>`, `LIKE`, `IN`, `OR`, `NOT` — never matches the extraction pattern, so it used to be dropped silently and the rows came back unfiltered. `unsupportedWhereFragments()` now reports what was ignored in `QueryResult.warnings()`; keep that wiring when touching the direct engine, a search that lies is worse than one that refuses. Column names keep their original case and support dot-notation nested paths (resolved via `getNestedValue`, with a case-insensitive top-level fallback). Values compare case-insensitively.
  - XML payloads are parsed with a per-thread secure `DocumentBuilder` (`XML_BUILDERS` ThreadLocal) — never build a `DocumentBuilderFactory` per message.
  - **Window functions**: `TABLE(TUMBLE(TABLE <name>, DESCRIPTOR(<time_col>), INTERVAL '<n>' MINUTE|HOUR|SECOND|DAY))` is supported via `kafkaWindowSelect()` — buckets messages by timestamp and computes aggregates per window. Time column resolution: message field (ISO-8601 or epoch) → Kafka record timestamp (fallback). `HOP`, `CUMULATE` and `SESSION` (with or without `PARTITION BY`) parse too but are **approximated as TUMBLE** of the same width — for the two-interval forms the width is the *last* interval (HOP's size, CUMULATE's max), not the slide — and the approximation is reported in `QueryResult.warnings()`. They used to reach this method and die on a TUMBLE-only regex with "Cannot parse TUMBLE syntax". Flink accepts singular and plural unit names alike (`MINUTE`/`MINUTES`), so both are matched.
  - **`extractPrimaryTable()` drives auto-registration**, not a bare `FROM` match: `FROM TABLE(TUMBLE(TABLE orders, …))` yields the keyword `TABLE`, so a windowed query never registered its topic and the planner's "Object 'orders' not found" was indistinguishable from a typo — which, once user errors stopped falling back, broke windowed reads on unregistered topics outright. The window call is consulted first because it carries the real name.
  - **SQL comments**: `--` line comments and `/* */` block comments are stripped before any keyword checks. A query beginning with a comment line is valid.
- `KafkaAdminService` — Kafka AdminClient wrapper for metadata and topic ops. `getEarliestRecords` / `getRecentRecords` share one `drain()` loop whose termination is driven by the **end offsets** (`position(tp) < endOffset`), never by an empty poll: a fresh consumer's first poll very often returns nothing while metadata resolves, and both methods used to treat that as "topic exhausted" — the audit then judged a topic on a handful of records and reported a confident zero. A cap of 3 consecutive empty polls and a 20 s budget remain as safety nets against a slow broker. `getClusterDetails()` also reports the KRaft controller quorum (`describeMetadataQuorum`: leader, epoch, high watermark, voters/observers with lag) under `kraftQuorum`, and all client groups (`listGroups`, Kafka 4 admin API — types CLASSIC / CONSUMER (KIP-848) / SHARE (KIP-932) / STREAMS with state) under `groups` — each absent when the broker doesn't support the API, and the Cluster page hides the section accordingly. Heavy metadata calls are Caffeine-cached (30s TTL): `listTopics` (`kafkaTopics`), `getTopicDescriptor` (`topicDescriptor`), `getTopicsSize` (`topicSizes`), `getTopicsLastMessageTimestamps` (`topicLastMessages`), `getClusterDetails` (`clusterDetails`) — cache names are registered in `WebConfig`. Recent-record seeks are clamped to the partition's beginning offset (retention-trimmed topics would otherwise trigger an `auto.offset.reset` to latest and return nothing).
- `TopicSearchService` — bounded server-side scan behind `POST /api/topic/{name}/search`. Assigns the partitions directly (fresh group, no commits — a search never moves anyone's offsets), seeks per `from` (EARLIEST / LATEST / TIMESTAMP / OFFSET) or per resume cursor, and reads until one of three budgets is spent (`explorer.search-max-hits` / `search-max-scan` / `search-timeout-ms`). The response carries `scanned`, `matched`, `stopReason`, `exhausted` and a `nextCursor` per partition, so the UI states what was covered and can continue — a search is never silently partial. `createConsumer()` is the test seam (`TopicSearchServiceTest` drives a `MockConsumer`).
- `MessageMatcher` — the predicate behind a search, built once per request. CONTAINS / REGEX work on the raw value (no parsing); FIELD walks the payload with a streaming parser, pruning any subtree whose path can no longer reach the target, and compares with EQ / NEQ / CONTAINS / REGEX / GT / GTE / LT / LTE / EXISTS. Paths use dot notation with `[]` for "any array element" (JSONPath is accepted and normalized via `PayloadDigestService.normalizePath`). A malformed payload simply doesn't match — one bad record must not fail a scan.
- `SchemaInferenceService` — samples messages and delegates to `JsonSchemaInferrer` / `XmlSchemaInferrer` / `AvroSchemaInferrer` (inferred column order is deterministic — `LinkedHashMap`)
- `DdlGeneratorService` — auto-generates Flink `CREATE TABLE` DDL from inferred schemas. `maskSensitiveProperties()` (static) redacts credentials (`*password*`, `*secret*`, `sasl.jaas.config`) and MUST be applied to any DDL returned to the UI (`/api/topic/{name}`, `/api/topic/{name}/ddl`, `/api/query/ddl-preview`, lineage `SHOW CREATE TABLE`); internal table registration uses the unmasked DDL.
- `AuditService` — cluster health checks run on a dedicated single-thread executor (`startAudit` submits explicitly — do NOT reintroduce `@Async`, the self-invocation bypasses the Spring proxy and blocks the HTTP thread); per-topic audits fan out on a bounded 4-thread pool. Exact counts go through the direct SELECT engine (`COUNT(*) AS metric_value`, first numeric value of the row); duplicate detection and flow latency are computed **in-process** over fetched messages (key extraction via `MessageFieldExtractorService`) because the direct engine supports neither subqueries nor JOINs. Reports persist to `internal.audit.history` via a shared lazy producer. Retention is bounded (`MAX_RETAINED_RUNS` = 20) — the runs map used to grow forever. Cluster-level findings go into `globalStats`: `getLaggingFeatures()` (KafkaAdminService, `describeFeatures`) compares finalized vs supported feature versions, and a lagging `metadata.version` adds a `metadataVersionWarning` (incomplete KRaft rolling upgrade — surfaced as a banner on the Audit page).
  - **One sample per topic**: format detection, schema inference and the poison check share a single `getSampleMessages()` call, fed to the `detectFormat(topic, samples)` / `inferSchema(topic, format, samples)` overloads. Each used to open its own KafkaConsumer for the same ten messages — keep the sample threaded through when touching `auditTopic`.
  - **No check degrades to a silent zero.** Poison detection *parses* (a truncated `{"id":` is poison, first-character checks are not enough) and falls back to the sample's dominant format when schema inference is off; duplicate detection falls back to the Kafka record key when the schema has no id-like field; an exact count that errors reports the reason as a topic issue instead of quietly returning the offset estimate. `globalStats.scopeNotes` states the bounds of each scan (10 000 messages for duplicates, 10 for poison, 1 000 for latency).
  - The in-flight `RUNNING` report is republished after every topic with `phase` / `topicsCompleted` / `topicsTotal`, which is what drives the Audit page's progress bar. `globalStats` also carries `startedAt`, `durationMs` and `options`.
  - Flow latency memoizes `topic → Map<id, first timestamp>` for the run: a topic in the middle of a flow is both a source and a target and was otherwise fetched twice.
  - `FlowAudit.overallHealthScore` is a **0..1 ratio**, not a percentage (the UI multiplies by 100).
  - **Severity is graded**: `HealthStatus` is `HEALTHY < WARNING < CRITICAL` (`max`/`atLeast` helpers), and every finding is a `TopicIssue(message, severity)` — a topic takes the worst severity among its issues. CRITICAL = the audit failed, unparseable payloads, `COUNT(*)` returning 0 on a non-empty topic; WARNING = duplicates (often legitimate), a degraded measurement. `AuditReport` carries `criticalTopicsCount` + `warningTopicsCount`, and `globalStats.healthScore` (0..1, critical −1 / warning −½) is computed server-side so it is frozen into the persisted report. Reports already in `internal.audit.history` still contain the retired `UNHEALTHY` value — a future history reader must tolerate it.
  - **One run at a time**: `startAudit` returns `AuditStart(auditId, started)` and refuses to queue a second scan behind the single-threaded executor; the controller answers 409 with the in-flight id and the UI attaches to it. The slot is released in a `finally` — a failed or cancelled run must not block every later start.
  - **Time budget**: `explorer.audit-max-duration-ms` (30 min default, 0 disables) bounds one run. Past the deadline it stops through the same path as a cancellation and reports `globalStats.stopReason` = `TIME_BUDGET` vs `REQUESTED` — "cancelled" without saying by whom would be misleading. Raising the property beats reading a truncated report on a very large cluster.
  - **Duplicates are scanned from the END of each topic** (`explorer.audit-duplicate-scan-from`, `LATEST` default / `EARLIEST` to restore the old behaviour): every other check samples recent messages, and on a topic with retention the oldest surviving records answer a different question. The issue wording and the scope note follow whichever end was actually read.
  - **Cancellation is cooperative**: `cancelAudit` raises an `AtomicBoolean` on the `RunHandle`; the run polls it inside each per-topic task (every topic is submitted to the pool up front, so cancelling cannot un-queue them — the check has to be *in* the task) and before the flow phase. Never interrupt mid-topic: a topic's work is already bounded (500 ms poll loop, 5 s query timeout) and interrupting would abandon a KafkaConsumer or a Flink job. The report keeps the topics already audited, with status `CANCELLED`, `globalStats.cancelled` / `topicsInScope`, and a leading `scopeNote`; `totalTopics` counts what was audited so the KPI matches the table, and `healthScore` is computed over that same subset. In-flight progress carries `cancelling: true` so the UI can say "Stopping…" instead of looking frozen.
  - `totalMessages` sums the per-topic counts actually reported, not `topicSizes`, so the KPI and the table column agree when exact counts ran.
- `AuditHistoryService` — reads past reports back out of `internal.audit.history`, which `AuditService` had always written and nothing ever read. `listHistory()` (Caffeine-cached, cache name `auditHistory`) returns `AuditRunSummary` rows newest first; `findReport(id)` returns the stored JSON. **Bounded and says so**: at most `explorer.audit-history-max-records` (200) records from the end of the topic, with `recordsScanned` / `exhausted` in the response — a list that silently shows 20 of 500 runs reads as "the cluster was never audited before". Summaries are extracted from the JSON tree, never deserialized into `AuditReport`: far cheaper, and it survives records written by older versions. Records predating graded severity (`UNHEALTHY`, `unhealthyTopicsCount`, string issues) are marked `legacy: true` and cannot be opened in the report view — the old scale never recorded the warning/critical distinction, so any mapping would be a guess. `createConsumer()` is the test seam (`AuditHistoryServiceTest` drives a `MockConsumer`).
- `AuditDiffService` — topic-by-topic comparison of two runs (`GET /api/audit/compare?from&to`). Only topics whose **health** moved are listed (`REGRESSED` / `IMPROVED` / `ADDED` / `REMOVED` / `ISSUES_CHANGED`, regressions first), with the findings that appeared and were resolved; the rest are counted. A message count moving is not a finding — it moves on every live topic. Refuses with 409 when either run predates graded severity (the retired binary scale cannot decide a direction), and warns when the two runs did not enable the same checks. Reports resolve from the in-memory runs first, then the history topic, and are diffed as JSON trees either way.
- `AuditController` — `@RestController` under `/api/audit` only: `start` (409 + in-flight id when one is running), `status/{id}` (404 on an unknown id), `{id}/cancel` (202 asked / 409 already finished / 404 unknown), `last` (204 when no run yet), `history`, `history/{id}` (404 when outside the scan window), `compare` (404 unknown / 409 legacy shape). Do **not** add a `GET /audit` mapping — `/audit` is a client-side route and a controller mapping on it shadows `SpaController`, producing a circular-view-path 500 on a page refresh (there is no template engine).
- `LineageService` — builds dependency graph (topics → tables → views → jobs) by regex-parsing DDL/SQL; uses `TableEnvironment` (not `StreamTableEnvironment`) — Flink 2.x uses the unified API
- `StreamFlowService` — traces one message key across topics (raw key/payload, JSONPath or XPath) and derives the pipeline it travelled through. **A criterion the user can fix is rejected, never degraded**: an invalid regex used to be caught and dropped, leaving a plain substring search on the regex source, and a malformed search path silently matched nothing; both now raise `IllegalArgumentException` and the controller answers 400 with the reason in `{"message": …}` (an explicit body, because `server.error.include-message` defaults to `never`). A search path is a **scope**, not a hint — on a payload it cannot be applied to the record does not match, instead of falling back to a raw substring search the path never sanctioned. The response carries `hits` (per topic: occurrence count, first/last timestamp, partition/offset/key of the first match, payload preview, latency from the previous hop), `stats` (topics in scope / scanned / skipped / failed, messages scanned, duration, `truncated`, `stopReason`) and `warnings` — an empty graph has to read as "not in the window I scanned", not as a bare "not found". **One node per topic, chained by first sighting** (tie-broken by name): the old code sorted every occurrence and linked each consecutive pair, so a key seen twice in one topic drew back-edges and topics sharing a millisecond drew a different picture on every run. Bounded on both axes — `explorer.stream-flow-timeout-ms` (remaining futures are cancelled, and a `supplyAsync` task that has not started never runs) and `explorer.stream-flow-max-topics` for a scan with no target topic, which also skips the explorer's own `internal.*` topics. Per request the JSONPath is compiled once and the XPath once per topic (both used to be recompiled per record), JSONPath runs with `SUPPRESS_EXCEPTIONS` (no stack trace per missing path) and XPath is evaluated as a NODESET (a match on the second `<item>` used to be invisible). `maxMessagesPerTopic` is a primitive `int`: a body omitting it deserialized to 0 and scanned nothing, so it now floors to 100.
- `SqlQueryValidator` — whitelist-based guard: only `SELECT`, `EXPLAIN`, and `CREATE TABLE` are allowed. Its `EXPLAIN` probe still swallows unresolved-table errors (it runs before auto-registration, so those are expected), but a **parse** error is rethrown as `IllegalArgumentException`: syntax never depends on the catalog, so `POST /api/query/validate` can reject a typo with its line/column before the query touches Kafka.
- `PayloadDigestService` — the Process Mining pipeline never handles a raw payload past ingestion. Each record is turned into a `PayloadDigest` (mapped fields, a bounded sample of other scalars, array cardinalities, a `PayloadShape` reference, original size) by a **streaming** parser: Jackson `JsonParser` for JSON, StAX `XMLStreamReader` for XML — no tree, and on the live path not even a `String`, since the value arrives as `byte[]`. Subtrees past `max-depth` and array elements past `array-sample-size` are `skipChildren()`-ed rather than walked, so a 1 MB / 10-level document costs a bounded walk and yields a ~1 KB digest. Structures are deduplicated by hashing the leaf-path set (array indices collapsed to `[]`) into a shape id, kept in an LRU registry, so a window of identical documents contributes one skeleton to the prompt. Non-JSON/XML payloads keep a bounded preview; unparseable ones carry `parseError` + preview.
- `FieldProfilingService` — sends Kafka message samples to Claude API for semantic field detection (CORRELATION_ID / TIMESTAMP / STATUS / AMOUNT); returns `FieldProfileResult`. Profiles from digests (structures + a few example values per path, `maxSampleFields = maxShapePaths`), not from raw payloads. Never swallow exceptions — propagate so callers surface the real error.
- `LlmAnalysisService` — generates Mermaid flowcharts + `AnomalyReport` list from correlated messages (snapshot and live modes via `analyzeSnapshot` / `analyzeLiveDigests`; `analyzeLive(List<KafkaMessage>)` digests first — the erasure clash is why the digest entry point has its own name). The prompt carries a deduplicated **STRUCTURES DE PAYLOAD** section plus one compact line per message, under a global `prompt-char-budget` split evenly across topics; per topic the messages are an `evenSample()` (first and last always kept). Escaping is single-pass — chaining `String.replace()` copied every payload four times.
- `KafkaSnapshotReader` — temp KafkaConsumer (group `snapshot-reader-{uuid}`, `enable.auto.commit=false`) supporting EARLIEST / LATEST_N / TIMESTAMP seek modes. `read()` returns raw `KafkaMessage`s; `readDigested()` uses `ByteArrayDeserializer` and digests each record as it arrives, so nothing accumulates — that is the one both LLM services use. Fetch sizes come from `process-mining.*`.
- `KafkaLiveConsumer` — sliding window consumer; triggers LLM on window fill (default 100 msg) or timeout (default 30s); pushes via `SseEmitterManager`. **Buffers digests, never payloads**: values are consumed as `byte[]` and digested on the poll thread, so a window of a hundred 1 MB documents costs a few hundred KB instead of hundreds of MB; the buffer is capped by count (`windowSize × 10`) *and* by estimated heap (`max-window-digest-bytes`), dropping oldest and reporting the count. Partitions are seeked to the end from a `ConsumerRebalanceListener` (not after a fixed 500 ms poll), so a backlog is skipped reliably and again after any rebalance. `WINDOW_STATS` is emitted from the poll thread when the window is cut — before the LLM runs — and carries the ingestion counters (`messagesReceived`, `bytesReceived`, `maxPayloadBytes`, `droppedMessages`, `parseFailures`, `distinctShapes`). **Threading contract**: the KafkaConsumer is only ever touched by the per-session polling task (init subscribe included); `stopSession()` just raises a stop flag + `consumer.wakeup()` and the polling task closes everything in `finishSession()`. LLM analyses run on a dedicated `analysisExecutor` pool — never on the 4-thread scheduler shared by all sessions' polling/heartbeat tasks. `createConsumer()` is the test seam (`KafkaLiveConsumerTest` drives a `MockConsumer` through it).
- `SseEmitterManager` — manages `Map<sessionId, SseEmitter>` (5 min timeout, heartbeat every 15s)
- `KraftQuorumMetrics` — polls `KafkaAdminService.getQuorumSnapshot()` (cheap, uncached single admin call) every 30s on a dedicated daemon thread and exposes Prometheus gauges: `kafka_quorum_leader_id` / `kafka_quorum_leader_epoch` / `kafka_quorum_high_watermark` and `kafka_quorum_replica_lag{replicaId,role=voter|observer}`. No gauge is registered on Zookeeper-mode clusters (null snapshot); registered gauges keep their last value across transient failures.
- `ClaudeConfig` — `@ConfigurationProperties(prefix="claude")`; reads `ANTHROPIC_API_KEY` env var via `${ANTHROPIC_API_KEY:}`
- `MetricService` — bridges Flink SQL to Prometheus metrics via Micrometer. Supports 4 Prometheus types:
  - `GAUGE` → Micrometer `Gauge` (point-in-time value)
  - `COUNTER` → Micrometer `Counter` (service tracks delta between polls and increments accordingly)
  - `HISTOGRAM` → Micrometer `DistributionSummary` with `publishPercentileHistogram=true`
  - `SUMMARY` → Micrometer `DistributionSummary` with client-side quantiles (p50/p75/p90/p95/p99)
  Metric status stays `pending` if `lastValue == null` — ensure aggregates use `AS metric_value` alias.
  `MetricConfig.createTableSql` (optional): when set, the DDL is executed before the metric SQL (useful for pre-registering a Flink table).
  Metric configs persist to `internal.metrics.config` via a shared lazy producer; startup restore reads the topic to its end offsets (an empty poll does NOT mean exhausted). Within one `refreshMetrics` cycle, identical queries (same sql/maxRows/timeout/readMode) execute once and are memoized (ThreadLocal cycle cache).

### Frontend

The SPA lives in `src/main/webapp/src/`. Stack: React 19 + TypeScript + Vite + Tailwind, Monaco (SQL editor), Recharts (metric charts), Mermaid (process-mining flowcharts), `lucide-react` + Material Symbols (icons), `axios` (API).

**App shell** — `App.tsx` wraps everything in `ToastProvider` + `ConfirmProvider`, renders the `Layout`, and defines the routes. **Pages are lazy-loaded** (`React.lazy` + `Suspense`, `ProgressBar` fallback) for code-splitting; unknown routes render a `NotFound` (404). `Layout` (`components/Layout.tsx`) is the shell: collapsible `Sidebar` (drawer on mobile) + `Header` + an internally-scrolling content viewport. It polls `/api/dashboard` every 30s for connection health and to feed global search (topics + Flink tables).

**Navigation is centralized** in `navigation.ts` (single source of truth): `NAV_ITEMS` grouped into **Explore** (SQL Editor, Compare, Stream Flow) / **Observe** (Metrics, Audit, Cluster) / **Analyze** (Lineage, Process Mining), plus pinned Dashboard, and `CONFIG_ITEM` (Settings) / `HELP_ITEM` (Help). `resolvePageName()` and `groupNavItems()` derive the Header breadcrumb and Sidebar sections. Add a screen here, not in each component.

**Command palette** — `CommandPalette` (⌘K / Ctrl+K, wired in `Layout`) is the single global search over quick actions, pages, Kafka topics and Flink tables, fully keyboard-driven.

**Design-system library** — `components/ui/` (`import { … } from '../components/ui'`), built on the `tailwind.config.js` + `index.css` tokens; prefer it for any new surface: `Button`, `Card`/`CardHeader`, `Badge`, `EmptyState`, `PageHeader`, `Stat`, `Field`/`Input`/`Select`/`Textarea`, `Combobox`/`TopicInput`/`NumberInput`/`PasswordInput`, `Table` (+ `Th`/`Td`/…), the `Skeleton` family, `Spinner`/`ProgressBar`, `ConfirmProvider`/`useConfirm` (async confirm dialogs), `useVirtualRows` (row virtualization), and `cn()` (clsx + tailwind-merge). Other shared components: `Toast`/`ToastProvider`, `ErrorBanner`, `LoadingSpinner`.

**Form conventions** — build every form out of these; hand-rolled `<input className="…">` blocks drift from the tokens and skip the accessibility wiring.
- `Field` owns the label ↔ control ↔ error/description `aria` plumbing and renders its child through a render prop (`{p => <Input {...p} />}`). Pass an explicit `id` when the form needs to focus the first invalid control after validation.
- **Validate every field at once**, into a `Partial<Record<field, string>>` handed to each `Field error=…`, and focus the first offender — not one message at a time in a banner at the bottom of the page.
- `NumberInput` keeps the raw string while typing and only coerces on blur. Never `parseInt(e.target.value) || fallback` on change: clearing the field snaps it to the fallback mid-typing, and `0` is falsy so a leading zero does too.
- `TopicInput` suggests topic (or Flink table) names from `catalogStore`, which `Layout` fills from its existing `/api/dashboard` poll — no extra request. Free text stays valid: a topic can exist before the 30s cache shows it.
- `PasswordInput` adds a reveal toggle and `autoComplete="new-password"`. Secrets typed blind fail at connection time with nothing to diagnose.
- Wrap in a real `<form onSubmit>` so Enter submits from any field. `Button` defaults to `type="button"` for that reason — submit buttons declare `type="submit"` explicitly.

**Routes / pages** (`pages/`):
- `Dashboard` (`/`) — topic list with filtering
- `QueryWorkbench` (`/query`, nav "SQL Editor") — Monaco SQL editor; saved queries + history in `localStorage` (`kse:saved-queries`, `kse:query-history`). **Every failure lands in one error panel**: `pages/queryError.ts` (`describeQueryError` / `describeApiError`, pure and unit-tested) turns a raw engine message into a readable title, an actionable hint and — when the message cites one — a line/column, which drives both a "jump to line" button and a native Monaco error marker; the raw text is always kept behind "Show raw error". The `panelError` state carries failures that no `QueryResult` holds (execution-mode rejection, `POST /api/query/validate`, transport failure) through that same panel, and is cleared as soon as the SQL is edited so a stale marker never points at a moved position. `QueryResult.warnings` is rendered above the grid — the direct engine reports the WHERE predicates it could not apply, and dropping them presented an unfiltered scan as a filtered one. Tabs persist to `localStorage` (`kse:tabs`, restored once at first render; a `?sql=` opens an *extra* tab rather than clobbering the first), which is why there is no longer a `beforeunload` guard. The row cap is a real control wired to `maxRows` — the "limit reached" badge compares against the limit *that query* ran with, not a frontend constant that silently drifted from `explorer.default-max-rows`. Column autocomplete is scoped to the tables the query actually cites (`pages/sqlScope.ts`, pure and unit-tested: comments and string literals stripped, `TABLE(TUMBLE(TABLE t, …))` understood, topic↔table naming normalized), and their schemas are fetched on a 500 ms debounce, at most once per name per catalog refresh. **Run selection**: a non-empty editor selection is the only thing sent, and `offsetLocation()` maps the engine's line/column back into document coordinates — without it the Monaco marker would point at the top of the file. The Window Assistant (`pages/windowSql.ts`, pure and unit-tested) emits genuinely different SQL per window type (the dropdown used to produce `TUMBLE` whatever the choice), pre-fills the time column from the loaded schema instead of hardcoding `event_time`, states its caveats (SESSION needs a `PARTITION BY`; HOP/SESSION are approximated if the query falls back), and **inserts at the cursor** rather than overwriting the whole tab. CSV export goes through `pages/resultExport.ts` (RFC 4180): the old `JSON.stringify` per cell left a multi-key object's comma unquoted, shifting every following column.
- `TopicExplorer` (`/topic/:name`) — message sampling, schema preview and **server-side search** over the whole topic (`components/topic/TopicSearchPanel.tsx`): text / regex / field modes, the field list fed by the inferred schema, a time range, and a status strip stating hits, records scanned and why the pass stopped, with "continue scanning" driven by the response cursor. Messages carry their coordinates (partition, offset, timestamp, key, headers) and matches are highlighted in the raw view. The read-mode toggle drives which end of the topic is sampled, not only the generated DDL
- `Compare` (`/compare`) — side-by-side topic comparison
- `StreamFlow` (`/stream-flow`) — message tracing across topics. A trace is a bounded scan and the page says so: a coverage line (`describeCoverage`) states what was read, the backend `warnings` are listed as they are, and the "no flow found" panel repeats the coverage rather than a bare "not found". The **evidence table** under the graph is the checkable part — one row per topic with hit count, first sighting, Δ from the previous hop, partition/offset and a payload preview, the topic name linking to its explorer — and selecting a row highlights the node (and vice versa). The run is **cancellable** (`AbortController`; a whole-cluster trace can legitimately take a minute), the scan window is a real choice ("most recent messages" vs a time window, whose read-forward caveat is stated), zoom is anchored on the pointer and the reset button **fits the graph to the viewport** instead of returning to a fixed `translate(40,40) scale(1)` that left half a seven-topic chain off screen. Pure logic (validation, response parsing, layout, fit/zoom, formatting) lives in `pages/streamFlow.ts`
- `Lineage` (`/lineage`) — interactive dependency graph (custom SVG; no external graph lib)
- `Metrics` (`/metrics`) + `MetricsHelp` (`/metrics/help`) — Prometheus metric config, live values and Recharts charts
- `Audit` (`/audit`) — cluster health dashboard. Restores the last run from `/api/audit/last` on mount (a refresh must not force a fresh full-cluster scan) and re-attaches to the poller if it is still `RUNNING`. Topics table has a text filter, a health filter and sortable numeric columns; compacted numbers (`1.2K`) always carry the exact value in `title`. A `FAILED` run renders an error banner, never the KPI grid — it used to show "0 topics / 100% health". A `CANCELLED` run renders its partial results behind a banner stating how many of the in-scope topics were actually covered. A collapsible "Past runs" card lists `/api/audit/history` with a health-score delta against the previous run; "Open" loads an archived report into the current view and "Diff" compares the run with the previous one.
- `Cluster` (`/cluster`) — broker details and configuration (`/api/cluster`)
- `Config` (`/config`, nav "Settings") — Kafka connection and application settings UI
- `Help` (`/help`) — documentation / quick-start guide
- `ProcessMining` (`/process-mining`) — 4-step pipeline: topic selection → Claude profiling → schema validation → snapshot/live analysis with Mermaid flowchart + anomaly table. Sub-components in `components/processmining/`

The live status bar (`components/processmining/LiveStatusBar.tsx`) renders the `WINDOW_STATS` ingestion counters — volume read, distinct payload structures, messages dropped to backpressure, unparsed payloads — so an operator can see that big payloads are being sampled rather than silently lost.

**Tests** — Vitest + `@testing-library/react` on jsdom (`src/test/setup.ts`): `navigation.test.ts`, `components/ui/ui.test.tsx`, `forms.test.tsx`, `ConfirmDialog.test.tsx`, `ScrollList.test.tsx`, `useVirtualRows.test.ts`, `pages/queryError.test.ts`, `pages/sqlScope.test.ts`, `pages/windowSql.test.ts`, `pages/resultExport.test.ts`, `pages/streamFlow.test.ts`, `components/topic/topicSearch.test.ts`. Run with `npm test`.

Dev server proxy: Vite forwards `/api/*` to `http://localhost:8080` (configured in `vite.config.ts`).

### Configuration

`src/main/resources/application.yml` controls:
- Claude API: `claude.api-key` (env var `ANTHROPIC_API_KEY`), `claude.model` (default `claude-opus-4-6`), `claude.max-tokens` (default 4096), `claude.snapshot-window-size` (100), `claude.snapshot-window-timeout-seconds` (30)
- Kafka bootstrap servers (default `localhost:9092`)
- Kafka connection mode: `PLAIN` (default), `SSL`, or `CONFLUENT_CLOUD` — each mode has its own set of required properties (keystore/truststore for SSL, API key/secret for Confluent Cloud)
- Process Mining ingestion (`process-mining.*`, `ProcessMiningConfig`): consumer fetch sizing (`max-poll-records`, `max-partition-fetch-bytes` — **must exceed the largest record**, 1 MB payloads at the 1 MB default would fetch one per round trip — `fetch-max-bytes`), payload digestion (`max-payload-bytes`, `max-depth`, `max-parse-events`, `array-sample-size`, `max-shape-paths`, `max-sample-fields`, `max-value-chars`, `preview-chars`, `shape-cache-size`, `max-window-digest-bytes`) and prompt budgeting (`max-shape-paths-in-prompt`, `max-messages-per-topic-in-prompt`, `prompt-char-budget`)
- Query timeout (default 10s), schema inference timeout (2s), audit run budget (`explorer.audit-max-duration-ms`, 30 min)
- Topic search budgets: `explorer.search-max-hits` (100), `search-max-scan` (20 000), `search-timeout-ms` (10s), `search-max-value-chars` (8 000 — values are truncated in search results and samples, the original size travels in `valueBytes`)
- Stream-flow budgets: `explorer.stream-flow-timeout-ms` (60s wall clock for one trace — each topic read carries its own 20s broker budget, so a whole-cluster trace could otherwise pin the HTTP thread for minutes) and `explorer.stream-flow-max-topics` (250 topics scanned when the request names none)
- Cache TTL: `explorer.cache-expire-seconds` (default 30s) — applied by the custom `CacheManager` bean in `WebConfig`; a `spring.cache.caffeine.spec` in YAML would be silently ignored
- Log level: application package defaults to INFO (`logging.level`); per-query engine logs are DEBUG
- Default result rows (50)
- Audit topic name (`internal.audit.history`) and history read cap (`explorer.audit-history-max-records`, 200)
- Prometheus: `management.endpoints.web.exposure.include: health,info,prometheus` — exposes `/actuator/prometheus` for scraping

### SPA Routing

`SpaController` catches all non-API routes and forwards them to `index.html`, enabling client-side routing. The frontend router (`App.tsx`) then handles the route. When adding new frontend pages, no backend changes are needed.

**Never map a controller onto a client-side route.** There is no template engine, so a `@GetMapping("/stream-flow")` returning the view name `"stream-flow"` shadows `SpaController` and answers a page refresh with a circular-view-path 500. `StreamFlowController` carried exactly that mapping (removed; it is now `@RestController` under `/api/stream-flow`), and the same rule is why there is no `GET /audit`. Domain controllers belong under `/api/**`.

## Testing

Tests use JUnit 5 + Mockito. Unit tests mock Kafka and Flink — no broker needed. Integration tests (`ApplicationContextTest`) use `@SpringBootTest` with `DynamicPropertySource` to inject test config.

`AuditServiceTest` overrides `persistAuditHistory()` to skip real Kafka writes.

`FlinkSqlServiceTest` and `FlinkDdlValidationTest` **pass on Flink 2.3**. Before the migration these suites were broken (SELECT was routed to `kafkaDirectSelect()`, so tests against in-memory `createTemporaryView()` tables failed with "Table not found"; DDL validation hit a Calcite `SqlParserException`). With the Flink planner path restored (the `THREAD_PROVIDERS` fix, see above), SELECT resolves in-memory views through Flink and the whole suite is green. The Flink-native SELECT tests that were once `@Disabled("KAFKA_DIRECT")` (in-memory views, multi-topic JOIN, the `XmlExtract` UDF) are enabled again — they run against the restored planner, so the suite has no skipped tests.

Test classes are in `src/test/java/com/yourcompany/kafkasqlexplorer/`.

## Audit (2026-07)

`AUDIT-FEATURE-REVIEW.md` is a later, narrower review of the **Cluster Audit feature itself**
(`AuditService`, `AuditController`, `NamingConventionService`, `MessageFieldExtractorService`,
`Audit.tsx`) — bugs B1–B9, optimisations O1–O3, ergonomics E1–E8 and a second lot S1–S3 (graded
severity, one run at a time, consistent `totalMessages`), all fixed, plus a "constaté, non traité"
section listing what was deliberately left open (the write-only `internal.audit.history` topic, no
cancellation of a running audit, duplicates scanned from EARLIEST, no global time budget, the
premature-empty-poll behaviour of `KafkaAdminService.getEarliestRecords`).

`AUDIT.md` at the repository root documents a full bug & optimisation audit. All critical (C1–C4), major (M1–M8), minor and optimisation findings listed there have been fixed on this codebase — the report describes the *pre-fix* state and the corrective decisions (useful context before refactoring `AuditService`, `KafkaLiveConsumer`, `MetricService` or the direct SELECT engine).

## License

**AGPL v3** — `LICENSE` file at root. All Java source files carry the header:
```
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
```

## Claude API Java SDK (anthropic-java 2.16.1)

- Streaming collect pattern: `.flatMap(e -> e.contentBlockDelta().stream()).flatMap(d -> d.delta().text().stream()).map(TextDelta::text).collect(joining())`
- `.maxTokens()` expects `long` — cast explicitly: `(long) config.getMaxTokens()`
- Always propagate exceptions from LLM call methods (no silent catch + return null) — callers must surface the real error message in API responses
- `MessageFormat` enum values: `JSON, XML, AVRO, AUTO` — **no `UNKNOWN`**; use `AUTO` as default

## XML / JSON Field Access in FlinkSqlService

- **XML DOM Java**: `DocumentBuilderFactory` sans `setNamespaceAware(true)` → `element.getLocalName()` retourne `null`. Toujours utiliser `element.getTagName()`.
- `parseMessageToRow()` — XML → flat map dot-notation (`{"customer.name": "John"}`), JSON → map imbriqué. `getNestedValue(row, path)` unifie les deux : essaie `row.get(path)` d'abord (XML), puis traversée segment par segment (JSON).
- `flattenXmlElement(element, prefix, row)` — récursif, ignore l'élément racine (`prefix=""`), utilise `getTagName()` (jamais `getLocalName()`).

## TopicExplorer — Sélection interactive de champs

- `JsonNode` passe `fieldPath` en dot-notation récursivement (`fieldPath ? fieldPath+"."+k : k`). `XmlViewer` utilise `DOMParser` navigateur (récursif) avec le même schéma de chemin.
- Les champs sélectionnés alimentent `SELECT col1, col2 FROM topic LIMIT 50` via `openInEditor()`.

## Security Notes

- **SQL injection**: `SqlQueryValidator` whitelists only `SELECT`, `EXPLAIN`, `CREATE TABLE`
- **XXE**: All XML parsers have external DTD loading disabled
- **Credential masking**: every endpoint returning generated or `SHOW CREATE TABLE` DDL must pass it through `DdlGeneratorService.maskSensitiveProperties()` — the DDL embeds Kafka client properties, including SSL passwords and the Confluent `sasl.jaas.config` secret
- **No authentication** out of the box — intended for internal/controlled environments. Note that `POST /api/config` can repoint Kafka and LLM settings at runtime; protect the app before exposing it beyond a trusted network

## Secrets & CI

- **Ne jamais mettre de vraie clé en fallback Spring** : `${ANTHROPIC_API_KEY:sk-ant-...}` expose la clé dans git. Utiliser `${ANTHROPIC_API_KEY:}` (fallback vide).
- Si une clé est commitée : `git reset --soft HEAD~N` pour réécrire le commit, puis recommiter proprement.
- **Rebase conflicts** : dans un `git rebase`, `--theirs` = le commit local rejoué, `--ours` = la branche upstream. Pour accepter tous les fichiers conflictuels en faveur du commit local : `git checkout --theirs <fichiers> && git add -u && git rebase --continue`.

## Docker & GHCR

- Image publiée sur `ghcr.io/devdownin/kafkaexplorer` via `.github/workflows/release.yml` au push d'un tag `v*`.
- Le job `docker` dépend du job `build` et récupère le JAR via `actions/upload-artifact` / `actions/download-artifact`.
- Tags générés : `{{version}}` (ex: `0.0.3`), `{{major}}.{{minor}}`, `latest`.
- Lancement local : `docker run -p 8080:8080 -e SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:9092 -e ANTHROPIC_API_KEY=sk-ant-... ghcr.io/devdownin/kafkaexplorer:latest`
