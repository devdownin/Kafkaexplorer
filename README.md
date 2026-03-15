# KAFKA SQL EXPLORER
**Spring Boot 3.x | Apache Flink 2.2.x (Embedded) | Java 21 | React 19**

Kafka SQL Explorer is a modern web application designed for Data Engineers and Architects, allowing them to explore Kafka clusters and query topics in real-time via Flink SQL.

🚀 **[Explore the Documentation](docs/)**

---

## 🛠️ Developer Resources
- **Frontend**: Modern React 19 application with Tailwind CSS and Material Symbols.
- **Unit Tests**: Coverage for all core services (SQL Execution, Lineage, Auditing, XML Extraction). Run via `mvn test`.
- **Didactic Code**: The codebase is heavily commented to serve as a learning resource for Flink SQL integration in Spring Boot.
- **XXE Security**: Integrated protection against XML External Entity attacks in all parsing layers.

## Why Kafka SQL Explorer?

Writing Flink SQL for Kafka topics can be tedious and error-prone, especially when dealing with nested JSON or complex XML payloads. This application innovates by bridging the gap between raw data and SQL queries:

- **From Preview to Query**: No more manual typing of long paths. Use the Pro SQL Editor with interactive features to build your queries.
- **Automated Schema Inference**: It automatically detects JSON structures and generates the necessary `JSON_VALUE` or `XmlExtract` calls for you.
- **Zero-Configuration DDL**: Instantly register Kafka topics as Flink tables with pre-configured schemas, serialization formats, and watermark settings.
- **Pro Editor with Window Assistant**: Complex operations like windowing (TUMBLE/HOP/SESSION) and stateful aggregations are simplified through a visual assistant.
- **Visual Query Lineage**: A responsive SVG-based graph showing data flow from Kafka topics through Flink tables, views, and active streaming queries.
- **Side-by-Side Comparison**: Powerful tool to detect differences and schema mismatches between two Kafka topics.

---

## Detailed Features

### 1. Modern Dashboard
- **KPI Grid**: Real-time overview of topics, message counts, Flink tables, and active jobs.
- **Topic Explorer**: Advanced prefix search and health status monitoring for all Kafka topics.
- **Flink SQL Jobs**: Live monitoring of active streaming jobs with parallelism and uptime tracking.

### 2. Pro SQL Editor
- **Monaco Editor Engine**: Professional code editing experience with SQL syntax highlighting.
- **Window Assistant**: Visual tool to generate complex streaming windowing logic (Tumbling, Hopping, Session) without writing raw SQL.
- **Schema Browser**: Tree-view explorer for Kafka topics and Flink tables, including detailed column schemas.
- **Read Mode Switch**: Toggle between **Earliest** (start from beginning) and **Latest** (new messages only) offsets directly in the UI.
- **Live Statistics**: Real-time throughput (msg/s) and execution timing for streaming queries.

### 3. Advanced Topic Comparison
- **Side-by-Side Analysis**: Compare messages from two Kafka topics in independent columns with inline diff highlighting.
- **Shared SQL Context**: Apply identical filtering logic to both topics using a shared SQL editor.
- **Difference Detection**: Automatic identification of added, modified, or removed fields within message payloads.

### 4. Visual Query Lineage
- **Dependency Graph**: Responsive SVG visualization of the relationships between topics, tables, and SQL jobs.
- **Node Inspector**: Detailed view for each node, including DDL definitions, full schemas, and real-time throughput metrics.
- **Flow Visualization**: Interactive representation of data propagation across the cluster.

### 5. Message Propagation (Stream Flow)
- **Message Tracing**: Trace the path of a specific message across multiple Kafka topics by searching for a key or pattern.
- **Chronological Visualization**: Interactive graph showing the sequential flow of messages between topics.

### 6. Automated Functional Audit
- **Asynchronous Auditing**: Launch long-running cluster-wide audits in the background.
- **Technical Health Checks**: Automatic detection of "poison messages" and precise record counting.
- **Audit History**: Persistence of audit reports into a dedicated Kafka topic (`internal.audit.history`).

### 7. Security & Robustness
- **XXE Protection**: Strict disabling of external DTD entities for all XML parsers.
- **Graceful Failover**: Backend controllers handle Kafka connection timeouts to keep the UI responsive even during cluster outages.

---

## Tech Stack
- **Backend**: Spring Boot 3.5.x, Java 21 (Records).
- **Streaming**: Apache Flink 2.2.x (Embedded LocalEnvironment).
- **Frontend**: React 19, Tailwind CSS, Material Symbols, Monaco Editor.
- **Cache**: Caffeine (Kafka Metadata).

---

## Quick Start

### Prerequisites
- Docker & Docker Compose
- JDK 21+, Node.js 20+

### Installation
1. **Launch Kafka**:
   - For Kafka 3.x (Zookeeper mode):
     ```bash
     docker-compose up -d
     ```
   - For Kafka 4.2 (KRaft mode):
     ```bash
     docker compose -f docker-compose-kafka4.yml up -d
     ```

2. **Launch the application**:
   ```bash
   # Build the frontend
   cd src/main/webapp && npm install && npm run build

   # Start the backend
   cd ../../../
   ./mvnw spring-boot:run
   ```
3. **Access the interface**: `http://localhost:8080`

---
*© 2026 Kafka SQL Explorer - Compagnons du dev. Terminal UI*
