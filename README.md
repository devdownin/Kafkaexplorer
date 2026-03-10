# KAFKA SQL EXPLORER
**Spring Boot 3.x | Apache Flink 2.2.x (Embedded) | Java 21**

Kafka SQL Explorer is a modern web application designed for Data Engineers and Architects, allowing them to explore Kafka clusters and query topics in real-time via Flink SQL.

---

## Why Kafka SQL Explorer?

Writing Flink SQL for Kafka topics can be tedious and error-prone, especially when dealing with nested JSON or complex XML payloads. This application innovates by bridging the gap between raw data and SQL queries:

- **From Preview to Query**: No more manual typing of long paths. Click on any field in a message preview to instantly add it to your `SELECT` or `WHERE` clause.
- **Automated Schema Inference**: It automatically detects JSON structures and generates the necessary `JSON_VALUE` or `XmlExtract` calls for you.
- **Zero-Configuration DDL**: Instantly register Kafka topics as Flink tables with pre-configured schemas, serialization formats, and watermark settings.
- **Tailored for Beginners**: Complex operations like windowing (TUMBLE/HOP) and stateful aggregations are simplified through a visual assistant.

---

## Detailed Features

### 1. Dashboard & Navigation
- **Topic List**: Overview of all topics available on the Kafka cluster.
- **Advanced Filtering**:
  - **Prefix Filter**: Allows quickly finding topics belonging to a domain (e.g., `order.*`).
  - **Full Name Match**: Exact search to isolate a specific topic.
- **Flink Dynamic Tables**: Dedicated section to manage temporary tables and views registered in the local Flink engine.

### 2. Topic Exploration
- **Real-Time Metadata**: Visualization of the number of partitions, min/max offsets, and estimated data size.
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
- **CodeMirror Engine**: SQL syntax highlighting and Cyberpunk theme.
- **Auto-completion**: Intelligent suggestion of topic names and registered tables (`Ctrl+Space`).
- **Query History**: Quick access to the last 20 queries executed via a persistent sidebar (sessionStorage).
- **Resource Management**: Automatic cancellation of Flink jobs in case of timeout or error, preventing any resource leak in the minicluster.

### 5. Security & Robustness
- **XXE Protection**: Strict disabling of external DTD entities for all XML parsers (Schema Inferrer, UDF, Formatter).
- **SQL Validation**: Whitelist of authorized commands (`SELECT`, `EXPLAIN`, `CREATE TABLE`) to prevent destructive DML operations.
- **Connection Management**: Clean lifecycle of the Kafka AdminClient and consumers.

---

## Tech Stack
- **Backend**: Spring Boot 3.5.x, Java 21 (Records).
- **Streaming**: Apache Flink 2.2.x (Embedded LocalEnvironment).
- **Parsing**: Jackson (JSON), JAXB/StAX (XML).
- **Frontend**: Thymeleaf, CodeMirror 5.65, Bootstrap 5 (Dark Theme).
- **Cache**: Caffeine (Kafka Metadata).

---

## Quick Start

### Prerequisites
- Docker & Docker Compose
- JDK 21+

### Installation
1. **Launch Kafka**:
   ```bash
   docker-compose up -d
   ```
2. **Launch the application**:
   ```bash
   ./mvnw spring-boot:run
   ```
3. **Access the interface**: `http://localhost:8080`

### XML Query Example
```sql
SELECT invoice_id, XmlExtract(raw_value, '/Invoice/Amount') as total
FROM my_xml_topic
WHERE total > 1000;
```

---
*© 2026 Kafka SQL Explorer - Compagnons du dev. Terminal UI*
