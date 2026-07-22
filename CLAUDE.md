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

KRaft single-node notes: the `apache/kafka` image takes the cluster id via the `CLUSTER_ID` env var (a `KAFKA_CLUSTER_ID` var would be translated into an ignored `cluster.id` server property); all internal-topic replication factors (`offsets`, `transaction state`, share-group state) are pinned to 1 and `__consumer_offsets` runs with a single partition for faster startup.

### Typical local dev workflow

1. `docker compose -f docker-compose-kafka4.yml up kafka` — start Kafka only
2. `./mvnw spring-boot:run` — start backend on port 8080
3. `cd src/main/webapp && npm run dev` — start frontend dev server (port 5173)

## Architecture

### Stack

- **Backend**: Spring Boot 3.5.x, Java 21 (`java.version` in pom.xml — Flink 2.x supports Java 17/21, not 25), embedded Apache Flink 2.3.x (`flink.version` in pom.xml). Kafka connector: `flink-connector-kafka:4.0.1-2.0` (the `-2.0` suffix covers the whole Flink 2.x line).
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
  - `kafkaDirectSelect()` (fallback engine) supports aggregate functions (COUNT/SUM/AVG/MAX/MIN with optional GROUP BY) computed in-process over fetched Kafka messages. SQL must alias the result column (e.g. `COUNT(*) AS metric_value`). COUNT values are returned as `long` (integral), other aggregates as `double`.
  - For aggregate queries, up to 100 000 messages are fetched (earliest-offset). Plain projections fetch `limit + 20`; when a `WHERE` clause is present the scan widens to `max(5000, limit×100)` capped at 100 000 (the row loop still stops at `limit` matches).
  - **WHERE filtering**: simple `col = 'value'` conditions. Column names keep their original case and support dot-notation nested paths (resolved via `getNestedValue`, with a case-insensitive top-level fallback). Values compare case-insensitively.
  - XML payloads are parsed with a per-thread secure `DocumentBuilder` (`XML_BUILDERS` ThreadLocal) — never build a `DocumentBuilderFactory` per message.
  - **Window functions**: `TABLE(TUMBLE(TABLE <name>, DESCRIPTOR(<time_col>), INTERVAL '<n>' MINUTE|HOUR|SECOND|DAY))` is supported via `kafkaWindowSelect()` — buckets messages by timestamp and computes aggregates per window. Time column resolution: message field (ISO-8601 or epoch) → Kafka record timestamp (fallback). HOP/SESSION syntax is accepted but treated as TUMBLE.
  - **SQL comments**: `--` line comments and `/* */` block comments are stripped before any keyword checks. A query beginning with a comment line is valid.
- `KafkaAdminService` — Kafka AdminClient wrapper for metadata and topic ops. `getClusterDetails()` also reports the KRaft controller quorum (`describeMetadataQuorum`: leader, epoch, high watermark, voters/observers with lag) under `kraftQuorum`, and all client groups (`listGroups`, Kafka 4 admin API — types CLASSIC / CONSUMER (KIP-848) / SHARE (KIP-932) / STREAMS with state) under `groups` — each absent when the broker doesn't support the API, and the Cluster page hides the section accordingly. Heavy metadata calls are Caffeine-cached (30s TTL): `listTopics` (`kafkaTopics`), `getTopicDescriptor` (`topicDescriptor`), `getTopicsSize` (`topicSizes`), `getTopicsLastMessageTimestamps` (`topicLastMessages`) — cache names are registered in `WebConfig`. Recent-record seeks are clamped to the partition's beginning offset (retention-trimmed topics would otherwise trigger an `auto.offset.reset` to latest and return nothing).
- `SchemaInferenceService` — samples messages and delegates to `JsonSchemaInferrer` / `XmlSchemaInferrer` / `AvroSchemaInferrer` (inferred column order is deterministic — `LinkedHashMap`)
- `DdlGeneratorService` — auto-generates Flink `CREATE TABLE` DDL from inferred schemas. `maskSensitiveProperties()` (static) redacts credentials (`*password*`, `*secret*`, `sasl.jaas.config`) and MUST be applied to any DDL returned to the UI (`/api/topic/{name}`, `/api/topic/{name}/ddl`, `/api/query/ddl-preview`, lineage `SHOW CREATE TABLE`); internal table registration uses the unmasked DDL.
- `AuditService` — cluster health checks run on a dedicated single-thread executor (`startAudit` submits explicitly — do NOT reintroduce `@Async`, the self-invocation bypasses the Spring proxy and blocks the HTTP thread); per-topic audits fan out on a bounded 4-thread pool. Exact counts go through the direct SELECT engine (`COUNT(*) AS metric_value`, first numeric value of the row); duplicate detection and flow latency are computed **in-process** over fetched messages (key extraction via `MessageFieldExtractorService`) because the direct engine supports neither subqueries nor JOINs. Reports persist to `internal.audit.history` via a shared lazy producer.
- `LineageService` — builds dependency graph (topics → tables → views → jobs) by regex-parsing DDL/SQL; uses `TableEnvironment` (not `StreamTableEnvironment`) — Flink 2.x uses the unified API
- `StreamFlowService` — traces messages across topics using JSONPath / XPath expressions
- `SqlQueryValidator` — whitelist-based guard: only `SELECT`, `EXPLAIN`, and `CREATE TABLE` are allowed
- `FieldProfilingService` — sends Kafka message samples to Claude API for semantic field detection (CORRELATION_ID / TIMESTAMP / STATUS / AMOUNT); returns `FieldProfileResult`. Never swallow exceptions — propagate so callers surface the real error.
- `LlmAnalysisService` — generates Mermaid flowcharts + `AnomalyReport` list from correlated messages (snapshot and live modes via `analyzeSnapshot` / `analyzeLive`)
- `KafkaSnapshotReader` — temp KafkaConsumer (group `snapshot-reader-{uuid}`, `enable.auto.commit=false`) supporting EARLIEST / LATEST_N / TIMESTAMP seek modes
- `KafkaLiveConsumer` — sliding window consumer; triggers LLM on window fill (default 100 msg) or timeout (default 30s); pushes via `SseEmitterManager`. **Threading contract**: the KafkaConsumer is only ever touched by the per-session polling task (init subscribe/seek included); `stopSession()` just raises a stop flag + `consumer.wakeup()` and the polling task closes everything in `finishSession()`. LLM analyses run on a dedicated `analysisExecutor` pool — never on the 4-thread scheduler shared by all sessions' polling/heartbeat tasks.
- `SseEmitterManager` — manages `Map<sessionId, SseEmitter>` (5 min timeout, heartbeat every 15s)
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

**Design-system library** — `components/ui/` (`import { … } from '../components/ui'`), built on the `tailwind.config.js` + `index.css` tokens; prefer it for any new surface: `Button`, `Card`/`CardHeader`, `Badge`, `EmptyState`, `PageHeader`, `Stat`, `Field`/`Input`/`Select`/`Textarea`, `Table` (+ `Th`/`Td`/…), the `Skeleton` family, `Spinner`/`ProgressBar`, `ConfirmProvider`/`useConfirm` (async confirm dialogs), `useVirtualRows` (row virtualization), and `cn()` (clsx + tailwind-merge). Other shared components: `Toast`/`ToastProvider`, `ErrorBanner`, `LoadingSpinner`.

**Routes / pages** (`pages/`):
- `Dashboard` (`/`) — topic list with filtering
- `QueryWorkbench` (`/query`, nav "SQL Editor") — Monaco SQL editor; saved queries + history in `localStorage` (`kse:saved-queries`, `kse:query-history`)
- `TopicExplorer` (`/topic/:name`) — message sampling and schema preview
- `Compare` (`/compare`) — side-by-side topic comparison
- `StreamFlow` (`/stream-flow`) — message tracing across topics
- `Lineage` (`/lineage`) — interactive dependency graph (custom SVG; no external graph lib)
- `Metrics` (`/metrics`) + `MetricsHelp` (`/metrics/help`) — Prometheus metric config, live values and Recharts charts
- `Audit` (`/audit`) — cluster health dashboard
- `Cluster` (`/cluster`) — broker details and configuration (`/api/cluster`)
- `Config` (`/config`, nav "Settings") — Kafka connection and application settings UI
- `Help` (`/help`) — documentation / quick-start guide
- `ProcessMining` (`/process-mining`) — 4-step pipeline: topic selection → Claude profiling → schema validation → snapshot/live analysis with Mermaid flowchart + anomaly table. Sub-components in `components/processmining/`

**Tests** — Vitest + `@testing-library/react` on jsdom (`src/test/setup.ts`): `navigation.test.ts`, `components/ui/ui.test.tsx`, `ConfirmDialog.test.tsx`, `useVirtualRows.test.ts`. Run with `npm test`.

Dev server proxy: Vite forwards `/api/*` to `http://localhost:8080` (configured in `vite.config.ts`).

### Configuration

`src/main/resources/application.yml` controls:
- Claude API: `claude.api-key` (env var `ANTHROPIC_API_KEY`), `claude.model` (default `claude-opus-4-6`), `claude.max-tokens` (default 4096), `claude.snapshot-window-size` (100), `claude.snapshot-window-timeout-seconds` (30)
- Kafka bootstrap servers (default `localhost:9092`)
- Kafka connection mode: `PLAIN` (default), `SSL`, or `CONFLUENT_CLOUD` — each mode has its own set of required properties (keystore/truststore for SSL, API key/secret for Confluent Cloud)
- Query timeout (default 10s), schema inference timeout (2s)
- Cache TTL: `explorer.cache-expire-seconds` (default 30s) — applied by the custom `CacheManager` bean in `WebConfig`; a `spring.cache.caffeine.spec` in YAML would be silently ignored
- Log level: application package defaults to INFO (`logging.level`); per-query engine logs are DEBUG
- Default result rows (50)
- Audit topic name (`internal.audit.history`)
- Prometheus: `management.endpoints.web.exposure.include: health,info,prometheus` — exposes `/actuator/prometheus` for scraping

### SPA Routing

`SpaController` catches all non-API routes and forwards them to `index.html`, enabling client-side routing. The frontend router (`App.tsx`) then handles the route. When adding new frontend pages, no backend changes are needed.

## Testing

Tests use JUnit 5 + Mockito. Unit tests mock Kafka and Flink — no broker needed. Integration tests (`ApplicationContextTest`) use `@SpringBootTest` with `DynamicPropertySource` to inject test config.

`AuditServiceTest` overrides `persistAuditHistory()` to skip real Kafka writes.

`FlinkSqlServiceTest` and `FlinkDdlValidationTest` **pass on Flink 2.3**. Before the migration these suites were broken (SELECT was routed to `kafkaDirectSelect()`, so tests against in-memory `createTemporaryView()` tables failed with "Table not found"; DDL validation hit a Calcite `SqlParserException`). With the Flink planner path restored (the `THREAD_PROVIDERS` fix, see above), SELECT resolves in-memory views through Flink and the whole suite is green. A handful of Flink-native SELECT tests remain `@Disabled("KAFKA_DIRECT")` — they document the old bypass path and can be re-enabled/re-baselined against the restored planner.

Test classes are in `src/test/java/com/yourcompany/kafkasqlexplorer/`.

## Audit (2026-07)

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
