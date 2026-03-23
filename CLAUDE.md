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
npm run build        # Production build → src/main/resources/static/
npm run lint         # TypeScript + ESLint checks
```

### Docker

```bash
# Kafka 4.x (KRaft mode, recommended)
docker compose -f docker-compose-kafka4.yml up -d

# Kafka 3.x (Zookeeper)
docker-compose up -d

# Demo data setup (creates 70+ topics)
./setup-demo.sh localhost:9092
```

### Typical local dev workflow

1. `docker compose -f docker-compose-kafka4.yml up kafka` — start Kafka only
2. `./mvnw spring-boot:run` — start backend on port 8080
3. `cd src/main/webapp && npm run dev` — start frontend dev server (port 5173)

## Architecture

### Stack

- **Backend**: Spring Boot 3.5.x, Java 21, embedded Apache Flink 2.2.x
- **Frontend**: React 19, TypeScript, Vite, Tailwind CSS, Monaco Editor
- **Kafka**: Compatible with Kafka 3.x and 4.x (KRaft)
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
  - **IMPORTANT**: All SELECT queries bypass Flink entirely via `kafkaDirectSelect()` due to a persistent `FlinkRelMetadataQuery` NPE in Flink 2.x. Flink is only used for `CREATE TABLE` and `EXPLAIN`.
  - `kafkaDirectSelect()` supports aggregate functions (COUNT/SUM/AVG/MAX/MIN with optional GROUP BY) computed in-process over fetched Kafka messages. SQL must alias the result column (e.g. `COUNT(*) AS metric_value`).
  - For aggregate queries, up to 100 000 messages are fetched (earliest-offset).
  - **Window functions**: `TABLE(TUMBLE(TABLE <name>, DESCRIPTOR(<time_col>), INTERVAL '<n>' MINUTE|HOUR|SECOND|DAY))` is supported via `kafkaWindowSelect()` — buckets messages by timestamp and computes aggregates per window. Time column resolution: message field (ISO-8601 or epoch) → Kafka record timestamp (fallback). HOP/SESSION syntax is accepted but treated as TUMBLE.
  - **SQL comments**: `--` line comments and `/* */` block comments are stripped before any keyword checks. A query beginning with a comment line is valid.
- `KafkaAdminService` — Kafka AdminClient wrapper for metadata and topic ops
- `SchemaInferenceService` — samples messages and delegates to `JsonSchemaInferrer` / `XmlSchemaInferrer` / `AvroSchemaInferrer`
- `DdlGeneratorService` — auto-generates Flink `CREATE TABLE` DDL from inferred schemas
- `AuditService` — async cluster health checks, persists results to `internal.audit.history` topic
- `LineageService` — builds dependency graph (topics → tables → views → jobs) by regex-parsing DDL/SQL; uses `TableEnvironment` (not `StreamTableEnvironment`) — Flink 2.x uses the unified API
- `StreamFlowService` — traces messages across topics using JSONPath / XPath expressions
- `SqlQueryValidator` — whitelist-based guard: only `SELECT`, `EXPLAIN`, and `CREATE TABLE` are allowed
- `FieldProfilingService` — sends Kafka message samples to Claude API for semantic field detection (CORRELATION_ID / TIMESTAMP / STATUS / AMOUNT); returns `FieldProfileResult`. Never swallow exceptions — propagate so callers surface the real error.
- `LlmAnalysisService` — generates Mermaid flowcharts + `AnomalyReport` list from correlated messages (snapshot and live modes via `analyzeSnapshot` / `analyzeLive`)
- `KafkaSnapshotReader` — temp KafkaConsumer (group `snapshot-reader-{uuid}`, `enable.auto.commit=false`) supporting EARLIEST / LATEST_N / TIMESTAMP seek modes
- `KafkaLiveConsumer` — sliding window consumer; triggers LLM on window fill (default 100 msg) or timeout (default 30s); pushes via `SseEmitterManager`
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

The SPA lives in `src/main/webapp/src/`. Routing is in `App.tsx`. Pages:

- `Dashboard` — topic list with filtering
- `QueryWorkbench` — Monaco SQL editor with history (sessionStorage)
- `TopicExplorer` — message sampling and schema preview
- `Lineage` — interactive Cytoscape.js graph
- `StreamFlow` — message tracing across topics
- `Compare` — side-by-side topic comparison
- `Audit` — cluster health dashboard
- `Metrics` — Prometheus metric configuration and live values
- `Config` — Kafka connection and application settings UI
- `Help` — documentation / quick-start guide
- `Cluster` — broker details and configuration (`/api/cluster`)
- `ProcessMining` — 4-step pipeline: topic selection → Claude profiling → schema validation → snapshot/live analysis with Mermaid flowchart + anomaly table. Sub-components in `src/main/webapp/src/components/processmining/`

Shared UI components: `Toast` (global notifications via `ToastProvider` wrapping `App.tsx`), `ErrorBanner`, `LoadingSpinner`.

Dev server proxy: Vite forwards `/api/*` to `http://localhost:8080` (configured in `vite.config.ts`).

### Configuration

`src/main/resources/application.yml` controls:
- Claude API: `claude.api-key` (env var `ANTHROPIC_API_KEY`), `claude.model` (default `claude-opus-4-6`), `claude.max-tokens` (default 4096), `claude.snapshot-window-size` (100), `claude.snapshot-window-timeout-seconds` (30)
- Kafka bootstrap servers (default `localhost:9092`)
- Kafka connection mode: `PLAIN` (default), `SSL`, or `CONFLUENT_CLOUD` — each mode has its own set of required properties (keystore/truststore for SSL, API key/secret for Confluent Cloud)
- Query timeout (default 10s), schema inference timeout (2s)
- Cache TTL (30s, Caffeine)
- Default result rows (50)
- Audit topic name (`internal.audit.history`)
- Prometheus: `management.endpoints.web.exposure.include: health,info,prometheus` — exposes `/actuator/prometheus` for scraping

### SPA Routing

`SpaController` catches all non-API routes and forwards them to `index.html`, enabling client-side routing. The frontend router (`App.tsx`) then handles the route. When adding new frontend pages, no backend changes are needed.

## Testing

Tests use JUnit 5 + Mockito. Unit tests mock Kafka and Flink — no broker needed. Integration tests (`ApplicationContextTest`) use `@SpringBootTest` with `DynamicPropertySource` to inject test config.

`AuditServiceTest` overrides `persistAuditHistory()` to skip real Kafka writes.

**Known issue — `FlinkSqlServiceTest`** (currently untracked): registers in-memory Flink views via `tableEnv.createTemporaryView()` but `executeSql()` routes all SELECT to `kafkaDirectSelect()`, which only resolves Kafka topics. The mock returns `listTopics() = []`, so 13/27 tests fail with "Table not found". These are pre-existing failures — do not treat as regressions.

**Known issue — `FlinkDdlValidationTest`** (currently untracked): fails with a Calcite `SqlParserException` on DDL parsing — pre-existing, unrelated to the SELECT bypass.

Test classes are in `src/test/java/com/yourcompany/kafkasqlexplorer/`.

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
- **No authentication** out of the box — intended for internal/controlled environments
