# KAFKA SQL EXPLORER
[![Java CI with Maven](https://github.com/yourusername/kafka-sql-explorer/actions/workflows/ci.yml/badge.svg)](https://github.com/yourusername/kafka-sql-explorer/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-AGPL%20v3-red.svg)](LICENSE)

**Spring Boot 3.5.x | Apache Flink 2.2.x (Embedded) | Java 25 | React 19**

Kafka SQL Explorer is a modern web application designed for Data Engineers and Architects, allowing them to explore Kafka clusters and query topics in real-time via Flink SQL.

🚀 **[Explore the Documentation](docs/)**

---

## 🛠️ Developer Resources
- **Unit Tests**: Coverage for all core services (SQL Execution, Lineage, Auditing, XML Extraction). Run via `mvn test`.
- **Didactic Code**: The codebase is heavily commented to serve as a learning resource for Flink SQL integration in Spring Boot.
- **XXE Security**: Integrated protection against XML External Entity attacks in all parsing layers.

## Why Kafka SQL Explorer?

Writing Flink SQL for Kafka topics can be tedious and error-prone, especially when dealing with nested JSON or complex XML payloads. This application innovates by bridging the gap between raw data and SQL queries:

- **From Preview to Query**: No more manual typing of long paths. Click on any field in a message preview to instantly add it to your `SELECT` or `WHERE` clause.
- **Automated Schema Inference**: It automatically detects JSON structures and generates the necessary `JSON_VALUE` or `XmlExtract` calls for you.
- **Zero-Configuration DDL**: Instantly register Kafka topics as Flink tables with pre-configured schemas, serialization formats, and watermark settings.
- **Tailored for Beginners**: Complex operations like windowing (TUMBLE/HOP) and stateful aggregations are simplified through a visual assistant.
- **Visual Query Lineage**: An interactive graph showing data flow from Kafka topics through Flink tables, views, and active streaming queries.

---

## Detailed Features

### 1. Dashboard & Navigation
- **Topic List**: Overview of all topics available on the Kafka cluster.
- **Advanced Filtering**:
  - **Prefix Filter**: Allows quickly finding topics belonging to a domain (e.g., `order.*`).
  - **Full Name Match**: Exact search to isolate a specific topic.
  - **DLT Filtering**: Toggle to hide Dead Letter Topics (`*.dlt`) and focus on functional streams.
- **Flink Dynamic Tables**: Dedicated section to manage temporary tables and views registered in the local Flink engine.

### 2. Topic Exploration
- **Real-Time Metadata**: Visualization of the number of partitions, min/max offsets, and estimated data size.
- **DLT Identification**: Specific badges and warnings for Dead Letter Topics, alerting about potentially malformed data.
- **Sampling**: Automatic reading of the latest messages from the topic (partition 0) for analysis.
- **Advanced Formatting**: Native pretty-print for messages in **JSON** and **XML** formats.
- **Quick Copy**: One-click copy button for each previewed message.

### 3. Query Assistant (Integrated Intelligence)
The assistant transforms the message preview into a query design tool:
- **Interactive Selection**: Click on a JSON key or an XML tag to automatically add it to the `SELECT` clause.
- **Dynamic Filters**: Click on a value to add it to the `WHERE` clause.
- **Comparison Operators**: Dynamically choose the operator (`=`, `!=`, `LIKE`, `>`, `<`) for your filters.
- **Support for Nested Paths**: Automatic generation of `JSON_VALUE` for complex JSON structures.
- **XML Extraction**: Use of the custom `XmlExtract` function (based on XPath) to query XML payloads.
- **One-Click Registration**: "Register Table" button to instantly execute the generated DDL.

### 4. Professional SQL Editor
- **Monaco Editor**: High-performance SQL editor (VS Code engine) with SQL syntax highlighting and Cyberpunk theme.
- **Read Mode Switch**: Toggle between **Earliest** (start from beginning) and **Latest** (new messages only) offsets directly in the UI.
- **Dynamic SQL Hints**: Automatic injection of Flink SQL hints (`/*+ OPTIONS(...) */`) for per-query offset control without DDL changes.
- **Auto-completion**: Intelligent suggestion of topic names and registered tables (`Ctrl+Space`).
- **Query History**: Quick access to the last 20 queries executed via a persistent sidebar (sessionStorage).
- **Resource Management**: Automatic cancellation of Flink jobs in case of timeout or error, preventing any resource leak in the minicluster.

### 5. Visual Query Lineage
- **Interactive Graph**: Powered by `Cytoscape.js`, visualizing the relationships between topics, tables, and views.
- **Active Job Tracking**: Real-time visualization of running `INSERT INTO` queries as nodes connecting source and target tables.
- **Node Inspector**: Click on any node to view detailed information, such as the table schema or topic type.

### 6. Message Propagation (Stream Flow)
- **Message Tracing**: Trace the path of a specific message across multiple Kafka topics by searching for a key or pattern.
- **Advanced Targeting**: Use **JSONPath** or **XPath** to pinpoint the exact location of the search key within complex payloads.
- **Regular Expression Support**: Flexible matching using standard regex syntax.
- **Time-Based Filtering**: Narrow down the search to specific time windows (e.g., messages from the last 60 minutes).
- **Parallel Scanning**: High-performance concurrent scanning of topics with managed resource limits.
- **Chronological Visualization**: Interactive graph showing the sequential flow of messages between topics.

### 7. Advanced Topic Comparison
- **Side-by-Side Analysis**: Compare messages from two Kafka topics in independent columns.
- **Shared SQL Template**: Apply identical logic to both topics using a shared Flink SQL editor with `{topic}` placeholder support.
- **Time Synchronization**: Linked time range filters for temporal correlation between datasets.
- **Intelligent Diffing**: Specify an ID column to highlight value discrepancies and identify missing records across topics.
- **Live Metrics**: Real-time display of message counts and throughput (msg/s) for the selected topics and time ranges.

### 8. Automated Functional Audit
- **Asynchronous Auditing**: Launch long-running cluster-wide audits in the background.
- **Technical Health Checks**: Automatic detection of "poison messages" (malformed JSON/XML) and precise record counting via Flink SQL.
- **Duplicate Detection**: Intelligent identification of duplicate records based on common ID fields (e.g., `id`, `order_id`).
- **Functional Flow Analysis**: Automatic grouping of topics into logical business processes (using naming conventions) to visualize throughput and drop-off rates across steps.
- **Latency Measurement**: Calculation of average processing time between successive topics in a flow using Flink SQL joins.
- **Audit History**: Persistence of audit reports into a dedicated Kafka topic (`internal.audit.history`) for long-term tracking.

### 9. Security & Robustness
- **XXE Protection**: Strict disabling of external DTD entities for all XML parsers (Schema Inferrer, UDF, Formatter).
- **SQL Validation**: Whitelist of authorized commands (`SELECT`, `EXPLAIN`, `CREATE TABLE`) to prevent destructive DML operations.
- **Connection Management**: Clean lifecycle of the Kafka AdminClient and consumers.

### 10. Process Mining & AI Analysis (LLM)
Kafka Explorer integrates AI to analyze message flows and detect anomalies:
- **Automatic Field Profiling**: Detects `CORRELATION_ID`, `TIMESTAMP`, and `STATUS` fields across topics.
- **Flow Reconstruction**: Generates Mermaid flowcharts of your business processes.
- **Anomaly Detection**: Identifies sequence breaks, temporal delays, and structural inconsistencies.
- **Multi-Provider Support**: Compatible with **Claude (Anthropic)**, **Open Source models** (via OpenAI-compatible APIs like Ollama), and **SpectraLLM** (self-hosted private RAG/fine-tuned models).

### 11. Demo & Sandbox Environment
The application includes an automated demonstration setup to help you explore features immediately:
- **6-Step Order Pipeline**: Sequential topics (`demo.orders.1.received` to `6.delivered`) to test **Stream Flow** traceability.
- **JOINs & Reference Data**: A `demo.customers` topic to practice SQL JOINs with orders.
- **XML Processing**: A `demo.orders.xml` topic to test `XmlExtract` UDF.
- **Complex JSON**: A `demo.orders.complex` topic with deep nesting for testing **Schema Inference**.
- **Poison Messages**: A `demo.errors.poison` topic containing malformed data to observe error resilience.
- **Supply Chain 2.0 (Complex Process)**: A 20-step massive pipeline (`demo.sc.01.order.placed.out` to `20.delivered.out`) involving 60 topics. It features evolving nested JSON payloads (adding payment, fulfillment, quality control, and logistics data incrementally) to demonstrate advanced **Schema Inference** and **Stream Flow** across complex architectures.

---

## Tech Stack
- **Backend**: Spring Boot 3.5.x, Java 25 (Records).
- **Streaming**: Apache Flink 2.2.x (Embedded LocalEnvironment).
- **Parsing**: Jackson (JSON), JAXB/StAX (XML).
- **Frontend**: React 19, Tailwind CSS, Monaco Editor, Vite.
- **Cache**: Caffeine (Kafka Metadata).

---

## Quick Start

### Prerequisites
- Docker & Docker Compose
- JDK 25+

### Installation
1. **Launch Kafka**:
   - For Kafka 3.x (Zookeeper mode):
     ```bash
     docker-compose up -d
     ```
   - For Kafka 4.2 (KRaft mode):
     ```bash
     # This starts Kafka AND the application on port 8080
     docker compose -f docker-compose-kafka4.yml up -d
     ```

2. **Launch the application** (if not using the Dockerized version):
   ```bash
   # If you started ONLY Kafka via: docker compose -f docker-compose-kafka4.yml up -d kafka
   ./mvnw spring-boot:run
   ```
3. **Access the interface**: `http://localhost:8080`

---

## 🤖 LLM Configuration (Process Mining)

Kafka Explorer supports both cloud and local LLMs for process mining.

### Option A: Anthropic Claude (Default)
Set your API key as an environment variable:
```bash
export ANTHROPIC_API_KEY='your-api-key'
```

### Option B: Open Source / Local (Ollama, vLLM, LM Studio)
1. Run your model (e.g., `ollama run qwen2.5-coder:7b`).
2. Update `src/main/resources/application.yml`:
```yaml
claude:
  provider: OPENAI_COMPATIBLE
  base-url: http://localhost:11434/v1 # For Ollama
  model: qwen2.5-coder:7b
```

### Option C: SpectraLLM (local, private, domain-tuned)
Audit Kafka exchanges with a self-hosted [SpectraLLM](https://github.com/devdownin/SpectraLLM)
instance — a fully local RAG + fine-tuning platform. Kafka Explorer calls SpectraLLM's
`POST /api/query` endpoint; no API key leaves your network.
```yaml
claude:
  provider: SPECTRA
  base-url: http://localhost:8080  # SpectraLLM API (e.g. http://spectra-api:8080 in Docker)
  use-rag: false                   # true = also retrieve from SpectraLLM's ingested corpus
```
The `model` field is ignored — SpectraLLM serves whichever model it is configured to run.
Set `use-rag: true` to enrich the audit with SpectraLLM's document corpus; leave it `false`
to ground the analysis solely on the sampled Kafka messages. All settings are also editable
live from the **Config** page.

### Recommended Lightweight Models
- **Qwen 2.5-Coder 7B**: Best for JSON extraction and logic.
- **Llama 3.2 3B**: Fast for real-time (LIVE) analysis.
- **DeepSeek-R1-Distill-Qwen-7B**: Superior for complex anomaly reasoning.

---

### XML Query Example
```sql
SELECT XmlExtract(raw_value, '/Order/Customer') as customer,
       XmlExtract(raw_value, '/Order/Amount') as amount
FROM "demo.orders.xml"
WHERE amount > 100;
```

### JOIN Query Example
```sql
SELECT c.name, c.segment, o.amount, o.state
FROM "demo.orders.1.received" o
JOIN "demo.customers" c ON o.customer_id = c.customer_id;
```

### Complex JSON Query (Supply Chain 2.0)
```sql
SELECT order_id,
       step,
       JSON_VALUE(raw_value, '$.quality_control.score') as qc_score,
       JSON_VALUE(raw_value, '$.logistics.tracking') as tracking
FROM "demo.sc.13.carrier.assigned.out"
WHERE JSON_VALUE(raw_value, '$.quality_control.score') > 95;
```

---

## 🤝 Community & Support
- **Code of Conduct**: We expect all contributors to follow our [Code of Conduct](CODE_OF_CONDUCT.md).
- **Security**: Please report security vulnerabilities to [security@example.com](mailto:security@example.com) as per our [Security Policy](SECURITY.md).
- **Contributing**: Check our [Contributing Guide](CONTRIBUTING.md) to get started.

---
*© 2026 Kafka SQL Explorer - Compagnons du dev. Licensed under AGPL v3.*
