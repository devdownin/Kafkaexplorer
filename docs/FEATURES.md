# Feature Tour

The complete guided tour of everything Kafka SQL Explorer does. For the short version, see the [README](../README.md).

## 1. Dashboard & Navigation
- **Topic List**: Overview of all topics available on the Kafka cluster.
- **Advanced Filtering**:
  - **Prefix Filter**: Allows quickly finding topics belonging to a domain (e.g., `order.*`).
  - **Full Name Match**: Exact search to isolate a specific topic.
  - **DLT Filtering**: Toggle to hide Dead Letter Topics (`*.dlt`) and focus on functional streams.
- **Flink Dynamic Tables**: Dedicated section to manage temporary tables and views registered in the local Flink engine.
- **Command Palette**: `⌘K` / `Ctrl+K` global search over pages, quick actions, Kafka topics and Flink tables.

## 2. Topic Exploration
- **Real-Time Metadata**: Visualization of the number of partitions, min/max offsets, and estimated data size.
- **DLT Identification**: Specific badges and warnings for Dead Letter Topics, alerting about potentially malformed data.
- **Sampling**: Automatic reading of the latest messages from the topic (partition 0) for analysis.
- **Advanced Formatting**: Native pretty-print for messages in **JSON** and **XML** formats.
- **Quick Copy**: One-click copy button for each previewed message.

## 3. Query Assistant (Integrated Intelligence)
The assistant transforms the message preview into a query design tool:
- **Interactive Selection**: Click on a JSON key or an XML tag to automatically add it to the `SELECT` clause.
- **Dynamic Filters**: Click on a value to add it to the `WHERE` clause.
- **Comparison Operators**: Dynamically choose the operator (`=`, `!=`, `LIKE`, `>`, `<`) for your filters.
- **Support for Nested Paths**: Automatic generation of `JSON_VALUE` for complex JSON structures.
- **XML Extraction**: Use of the custom `XmlExtract` function (based on XPath) to query XML payloads.
- **One-Click Registration**: "Register Table" button to instantly execute the generated DDL.

## 4. Professional SQL Editor
- **Monaco Editor**: High-performance SQL editor (VS Code engine) with SQL syntax highlighting and Cyberpunk theme.
- **Read Mode Switch**: Toggle between **Earliest** (start from beginning) and **Latest** (new messages only) offsets directly in the UI.
- **Dynamic SQL Hints**: Automatic injection of Flink SQL hints (`/*+ OPTIONS(...) */`) for per-query offset control without DDL changes.
- **Auto-completion**: Intelligent suggestion of topic names and registered tables (`Ctrl+Space`).
- **Query History**: Quick access to recent and saved queries, persisted in the browser (localStorage).
- **Resource Management**: Automatic cancellation of Flink jobs in case of timeout or error, preventing any resource leak in the minicluster.

## 5. Visual Query Lineage
- **Interactive Graph**: A custom SVG dependency graph visualizing the relationships between topics, tables, and views. Pointer, touch and keyboard driven (arrows pan, `+`/`−` zoom, `0` resets, Tab moves between nodes, Enter opens one).
- **Resolved by Flink, not guessed**: Dependencies come from Flink's own parser walking the resolved operation tree, so a windowed source (`FROM TABLE(TUMBLE(TABLE orders, …))`), a quoted identifier or a multi-statement script is read correctly. When a statement cannot be resolved — a table that no longer exists — the graph falls back to a lexical scan and **says so** above the graph, because a missing edge and an absent dependency look identical.
- **Active Job Tracking**: Real-time visualization of running `INSERT INTO` queries as nodes connecting source and target tables.
- **Node Inspector**: Click on any node to view detailed information, such as the table schema or topic type.

## 6. Message Propagation (Stream Flow)
- **Message Tracing**: Trace the path of a specific message across multiple Kafka topics by searching for a key or pattern.
- **Advanced Targeting**: A dot path (`order.items[].sku`), **JSONPath** (`$..id`), **XPath** (`/order/id`) or a single Kafka header (`header:correlation-id`). Left empty, the search covers the record key, the payload and every header — a correlation id very often travels only in a header.
- **Exact Record Key**: Compares the whole Kafka key and scans only the partition the default partitioner would have chosen — a fraction of the work, and what "find record X" actually means.
- **Regular Expression Support**: Flexible matching using standard regex syntax. An invalid regex or a malformed path is rejected with its reason, never silently degraded into a substring search.
- **Time-Based Filtering**: Narrow the search to a time window. Topics whose newest record predates the window are skipped outright and named — nothing inside the window could have matched them.
- **Parallel Scanning**: Concurrent scanning with managed resource limits. Without target topics, the **most recently active** topics are read first, up to `explorer.stream-flow-max-topics`.
- **Streamed Results**: Hops appear on the graph as they are found, with live progress; stopping the scan keeps what was found rather than discarding it.
- **Honest Coverage**: Every trace states how many topics and messages were read, why it stopped, and *which* topics were never reached. An empty result reads as "not in the window I scanned", never as a bare "not found".
- **Continue, don't restart**: A trace stopped by its time budget resumes on the topics it never read, merging the new hops into the same chain.
- **Compare Two Traces**: Put two keys side by side — shared topics, topics only one of them reached, and the per-hop latency difference. Hop latencies are compared, never absolute timestamps: two keys processed an hour apart have nothing to say to each other in the absolute.
- **Chronological Visualization**: Interactive graph of the chain, with the slowest hop and any clock skew marked. Pointer, touch and keyboard driven (arrows pan, `+`/`−` zoom, `0` fits, Tab moves between nodes).
- **Shareable & Exportable**: The whole criterion round-trips through the URL, so a trace pasted into an incident ticket reruns exactly as it was. Hops export to CSV, or to JSON carrying the criterion, the coverage and the warnings.
- **Two-Way Links**: A hop opens the Topic Explorer on the same search; a message in the Topic Explorer traces its key across the cluster. The command palette (⌘K) offers to trace any text that is not a known topic.

## 7. Advanced Topic Comparison
- **Side-by-Side Analysis**: Compare messages from two Kafka topics in independent columns.
- **Shared SQL Template**: Apply identical logic to both topics using a shared Flink SQL editor with `{topic}` placeholder support.
- **Time Synchronization**: Linked time range filters for temporal correlation between datasets.
- **Intelligent Diffing**: Specify an ID column to highlight value discrepancies and identify missing records across topics.
- **Live Metrics**: Real-time display of message counts and throughput (msg/s) for the selected topics and time ranges.

## 8. Automated Functional Audit
- **Asynchronous Auditing**: Launch long-running cluster-wide audits in the background (dedicated executor, bounded per-topic parallelism).
- **Technical Health Checks**: Automatic detection of "poison messages" (malformed JSON/XML) and exact record counting via the direct Kafka SELECT engine.
- **Duplicate Detection**: In-process scan (up to 10 000 messages per topic) counting keys that appear more than once, based on common ID fields (e.g., `id`, `order_id`, `*_id`).
- **Functional Flow Analysis**: Automatic grouping of topics into logical business processes (using naming conventions) to visualize throughput and drop-off rates across steps.
- **Latency Measurement**: Average delta between Kafka record timestamps of messages sharing the same `id` across successive topics in a flow, computed in-process.
- **Audit History**: Persistence of audit reports into a dedicated Kafka topic (`internal.audit.history`) for long-term tracking.

## 9. Security & Robustness
- **XXE Protection**: Strict disabling of external DTD entities for all XML parsers (Schema Inferrer, UDF, Formatter).
- **SQL Validation**: Whitelist of authorized commands (`SELECT`, `EXPLAIN`, `CREATE TABLE`) to prevent destructive DML operations.
- **Credential Masking**: DDL shown in the UI (topic detail, DDL preview, lineage) has SSL passwords and SASL/Confluent secrets redacted.
- **Connection Management**: Clean lifecycle of the Kafka AdminClient, consumers, producers and thread pools; heavy metadata calls are cached (30s) to keep dashboard polling cheap.
- **Guarded Cluster Repointing**: Changing the Kafka connection while an audit, a Flink job or a live Process Mining session is still running is refused (HTTP 409) and the response names what is running — one report must not describe two clusters. The refusal can be overridden explicitly; what was already running keeps reading the previous cluster.
- **Failures That Stay On Screen**: An error that needs acting on is shown as a panel with the server's own message — readable title, hint, raw text one click away — not a toast that fades in three seconds.

## 10. Process Mining & AI Analysis (LLM)
Kafka Explorer integrates AI to analyze message flows and detect anomalies:
- **Automatic Field Profiling**: Detects `CORRELATION_ID`, `TIMESTAMP`, and `STATUS` fields across topics.
- **Flow Reconstruction**: Generates Mermaid flowcharts of your business processes.
- **Anomaly Detection**: Identifies sequence breaks, temporal delays, and structural inconsistencies.
- **Audit checklist**: A built-in library of ready-to-use audit prompts (ordering, duplicates, orphan flows, latency/SLA, schema drift, missing required fields, invalid status transitions, error/retries, amount outliers, PII exposure, correlation integrity). Tick the checks — plus an optional free-form instruction — to focus the LLM on a specific audit, in both snapshot and live modes. Audits that need a field the profiling step didn't detect (e.g. amount outliers with no `AMOUNT` field) are greyed out automatically. Served from `GET /api/process-mining/audit-templates`.
- **Multi-Provider Support**: Compatible with **Claude (Anthropic)**, **Open Source models** (via OpenAI-compatible APIs like Ollama), and **SpectraLLM** (self-hosted private RAG/fine-tuned models). See the [LLM provider guide](LLM-PROVIDERS.md).

## 11. Demo & Sandbox Environment
The application includes an automated demonstration setup to help you explore features immediately:
- **6-Step Order Pipeline**: Sequential topics (`demo.orders.1.received` to `6.delivered`) to test **Stream Flow** traceability.
- **JOINs & Reference Data**: A `demo.customers` topic to practice SQL JOINs with orders.
- **XML Processing**: A `demo.orders.xml` topic to test `XmlExtract` UDF.
- **Complex JSON**: A `demo.orders.complex` topic with deep nesting for testing **Schema Inference**.
- **Poison Messages**: A `demo.errors.poison` topic containing malformed data to observe error resilience.
- **Supply Chain 2.0 (Complex Process)**: A 20-step massive pipeline (`demo.sc.01.order.placed.out` to `20.delivered.out`) involving 60 topics. It features evolving nested JSON payloads (adding payment, fulfillment, quality control, and logistics data incrementally) to demonstrate advanced **Schema Inference** and **Stream Flow** across complex architectures.

## 12. Kafka 4 / KRaft Observability
- **KRaft Controller Quorum** (Cluster page): metadata-log leader, epoch and high watermark, plus a voters/observers table with per-replica lag and last fetch / last caught-up timestamps. Hidden automatically on Zookeeper-based clusters.
- **Client Groups** (Cluster page): every registered group with its type — `CLASSIC`, `CONSUMER` (KIP-848), `SHARE` (KIP-932 queues) or `STREAMS` — and state.
- **Feature Versions** (Cluster page): finalized vs broker-supported version for each cluster feature (`metadata.version`, `group.version`, `share.version`, …) with an *Up to date / Lagging* badge.
- **Incomplete-upgrade detection** (Audit page): if the finalized `metadata.version` lags what every broker supports (a rolling upgrade that was never finalized with `kafka-features.sh upgrade`), the audit report raises a dedicated warning banner.
- **Prometheus quorum gauges** (`/actuator/prometheus`): `kafka_quorum_leader_id`, `kafka_quorum_leader_epoch`, `kafka_quorum_high_watermark` and `kafka_quorum_replica_lag{replicaId,role}` — alert on a lagging voter or a controller failover.
- **KIP-848 rebalances (opt-in)**: set `kafka.consumer-group-protocol: consumer` (env `KAFKA_CONSUMER_GROUP_PROTOCOL=consumer`) to switch the live Process Mining consumer to the next-gen incremental rebalance protocol. Requires Kafka 4.x brokers; the default `classic` keeps compatibility with older brokers. The bundled Docker stacks enable it out of the box.
