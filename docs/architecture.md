# Architecture - C4 Models

This document describes the architecture of Kafka SQL Explorer using the C4 model.

## 1. System Context Diagram

```mermaid
C4Context
    title System Context Diagram for Kafka SQL Explorer

    Person(user, "Data Engineer / Architect", "Uses the explorer to analyze Kafka data and monitor flows.")
    System(explorer, "Kafka SQL Explorer", "Provides a web interface to query Kafka topics via Flink SQL and perform audits.")
    System_Ext(kafka, "Kafka Cluster", "Source of data and metadata.")

    Rel(user, explorer, "Uses", "HTTPS/8080")
    Rel(explorer, kafka, "Queries metadata and samples records", "Kafka Protocol")
```

## 2. Container Diagram

```mermaid
C4Container
    title Container Diagram for Kafka SQL Explorer

    Person(user, "Data Engineer / Architect", "Uses the explorer")

    System_Boundary(c1, "Kafka SQL Explorer") {
        Container(web_ui, "Web UI", "Thymeleaf, Bootstrap, CodeMirror", "Visualizes topics, query results, and lineage graphs.")
        Container(spring_app, "Spring Boot Application", "Java 21, Spring Boot 3.5", "Handles business logic, security, and integration.")
        Container(flink_engine, "Embedded Flink Engine", "Apache Flink 2.2", "Executes SQL queries against Kafka topics.")
    }

    System_Ext(kafka, "Kafka Cluster", "Maintains topics and message streams.")

    Rel(user, web_ui, "Interacts with", "Browser")
    Rel(web_ui, spring_app, "Sends requests to", "REST/HTML")
    Rel(spring_app, flink_engine, "Submits SQL jobs", "Flink Table API")
    Rel(spring_app, kafka, "Fetches metadata & samples", "Kafka Admin/Consumer Client")
    Rel(flink_engine, kafka, "Reads/Writes streams", "Flink Kafka Connector")
```

## 3. Component Diagram (Spring Boot Application)

```mermaid
C4Component
    title Component Diagram - Spring Boot Backend

    Container_Boundary(api, "Spring Boot Backend") {
        Component(query_ctrl, "QueryController", "Spring MVC", "Handles SQL execution requests.")
        Component(audit_ctrl, "AuditController", "Spring MVC", "Manages cluster-wide audits.")

        Component(flink_svc, "FlinkSqlService", "Service", "Manages Flink job lifecycle. Uses dedicated ExecutorService for non-blocking result fetching.")
        Component(kafka_svc, "KafkaAdminService", "Service", "Interfaces with Kafka AdminClient. Features Caffeine-based caching and strict timeouts.")
        Component(audit_svc, "AuditService", "Service", "Performs parallelized topic health checks and business flow analysis.")
        Component(inference_svc, "SchemaInferenceService", "Service", "Detects JSON/XML structures and generates schemas.")

        Component(cache, "Caffeine Cache", "Cache", "Stores topic metadata to reduce Kafka load.")
    }

    Rel(query_ctrl, flink_svc, "Uses")
    Rel(audit_ctrl, audit_svc, "Uses")
    Rel(audit_svc, kafka_svc, "Uses")
    Rel(audit_svc, flink_svc, "Uses")
    Rel(flink_svc, kafka_svc, "Uses for DDL")
    Rel(kafka_svc, cache, "Reads/Writes")
```

## Key Architectural Decisions (Robustness & Performance)

- **Parallel Auditing**: `AuditService` uses `CompletableFuture` to audit multiple topics concurrently, significantly speeding up cluster-wide reports.
- **Asynchronous SQL Fetching**: `FlinkSqlService` uses a dedicated `ExecutorService` to fetch streaming results, ensuring that Spring's worker threads are never blocked by infinite Flink iterators.
- **Metadata Caching**: `KafkaAdminService` utilizes Caffeine to cache topic lists and descriptors, improving UI responsiveness and reducing pressure on Kafka brokers.
- **Safe XML Processing**: `XmlExtractUDF` caches compiled `XPathExpression` instances while maintaining strict XXE protection.
- **Strict Timeouts**: All interactions with the Kafka cluster and Flink engine have explicit timeouts to prevent the application from hanging.
