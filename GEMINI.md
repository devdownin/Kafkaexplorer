# GEMINI.md - Kafka SQL Explorer Context

This project is a specialized web application for exploring Kafka clusters and querying topics using Flink SQL (or a high-performance Java-based emulation).

## Project Overview

- **Purpose**: Real-time Kafka topic exploration, schema inference, SQL querying, lineage tracking, and cluster auditing.
- **Backend**: Spring Boot 3.5.x, Java 21, Apache Flink 2.3.0 (Embedded).
- **Frontend**: React 19, TypeScript, Vite, Tailwind CSS, Monaco Editor.
- **Key Feature**: Bridges raw Kafka data (JSON/XML/AVRO) to SQL via automated schema inference and dynamic table registration.

## Architecture & Technical Nuances

### Backend Structure (`src/main/java/...`)
- `web/`: REST Controllers (Query, Topic, Audit, Lineage, etc.).
- `service/`: Core business logic.
  - `FlinkSqlService`: **CRITICAL ENGINE**.
    - **Note**: `SELECT` runs through the real Flink planner (`executeViaFlinkPlanner`, `engine=FLINK`), with automatic fallback to `kafkaDirectSelect` on failure. The historical `FlinkRelMetadataQuery` NPE is **fixed** — `FlinkRuntimeCoordinator.ensureFlinkMetadataProvider()` pre-seeds Calcite's `RelMetadataQueryBase.THREAD_PROVIDERS` ThreadLocal. A circuit breaker still guards against residual planner failures. Toggle via `explorer.flink-select-enabled`.
    - `kafkaDirectSelect` (fallback) implements custom SQL aggregation (COUNT, SUM, AVG, MAX, MIN) and windowing (TUMBLE) directly in Java over fetched Kafka records.
    - `CREATE TABLE` and `EXPLAIN` always go through Flink.
  - `KafkaAdminService`: Manages Kafka metadata and low-level record fetching. Supports Avro deserialization via Confluent Schema Registry.
  - `SchemaInferenceService`: Samples messages to detect JSON/XML/AVRO structures.
  - `AuditService`: Runs async health checks and persists reports to `internal.audit.history`.
- `parser/`: Logic for inferring schemas from JSON/XML/AVRO payloads.
  - `AvroSchemaInferrer`: Fetches latest schema from Confluent Schema Registry.
- `domain/`: Data models implemented as Java Records.

### Frontend Structure (`src/main/webapp/src/`)
- **SPA**: React 19 with client-side routing.
- **Editor**: Monaco Editor for SQL with custom highlighting.
- **Visuals**: Cytoscape.js for lineage graphs, Recharts for metrics.
- **Build**: Integrated into Maven via `frontend-maven-plugin`.

## Commands & Workflows

### Development
- **Backend**: `./mvnw spring-boot:run` (Port 8080).
- **Frontend**: `cd src/main/webapp && npm install && npm run dev` (Port 5173, proxies `/api` to 8080).
- **Tests**: `mvn test` (green on Flink 2.3 / Java 21).

### Build & Deploy
- **Full Build**: `mvn clean package` (Produces a single executable JAR containing the static frontend).
- **Docker**:
  - Kafka 4.x (KRaft): `docker compose -f docker-compose-kafka4.yml up -d`
  - Kafka 3.x (Zookeeper): `docker-compose up -d`
- **Demo Setup**: `./setup-demo.sh localhost:9092` (Creates 70+ topics for testing).

## Development Conventions

1. **SQL Validation**: Only `SELECT`, `EXPLAIN`, and `CREATE TABLE` are allowed via `SqlQueryValidator`.
2. **Security**: XXE protection is mandatory for all XML parsing logic.
3. **Naming**: Kafka topics are sanitized into Flink-friendly table names (dots/hyphens → underscores) via `DdlGeneratorService.toTableName()`.
4. **JSON/XML/AVRO**: Prefer Jackson for JSON, StAX/JAXB for XML, and Confluent Schema Registry for Avro. Use the custom `XmlExtract` UDF for XPath-based queries.
5. **State Management**: Frontend uses simple hooks and standard REST patterns; backend relies on Spring's `@Service` and `@RestController`.

## Known Issues / TODOs
- A few Flink-native SELECT tests in `FlinkSqlServiceTest` stay `@Disabled("KAFKA_DIRECT")`; they can be re-enabled now that the planner path is restored.
- Aggregate queries in the `kafkaDirectSelect` fallback fetch up to 100,000 messages from the earliest offset.
- Metric status remains `pending` if SQL aggregates don't use `AS metric_value`.
- Avro support requires a running Confluent Schema Registry (default: `http://localhost:8081`).
