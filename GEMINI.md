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
    - **Note**: `SELECT` runs through the Flink planner (`executeViaFlinkPlanner`) with automatic fallback to `kafkaDirectSelect` on failure (a circuit breaker disables the planner path after repeated failures — historically a `FlinkRelMetadataQuery` NPE). Toggle via `explorer.flink-select-enabled`.
    - Implements custom SQL aggregation (COUNT, SUM, AVG, MAX, MIN) and windowing (TUMBLE) directly in Java over fetched Kafka records.
    - Flink is still used for `CREATE TABLE` and `EXPLAIN`.
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
- **Tests**: `mvn test` (Note: some `FlinkSqlServiceTest` failures are pre-existing due to the SELECT bypass).

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
- `FlinkSqlServiceTest` has several failures related to the SELECT bypass and mock environment limitations.
- Aggregate queries in `kafkaDirectSelect` fetch up to 100,000 messages from the earliest offset.
- Metric status remains `pending` if SQL aggregates don't use `AS metric_value`.
- Avro support requires a running Confluent Schema Registry (default: `http://localhost:8081`).
