---
name: kafka-data-architect
description: Discover a Kafka topic domain, infer schema and lineage, and deliver evidenced entity relationships with runnable Flink SQL.
---

# Kafka data architect

Use when onboarding a Kafka domain, documenting an event topology, or proposing read-only Flink SQL joins.

## Procedure

1. Establish the permitted cluster and **specific** topic prefix. Call `kex_list_topics` using small limits. Narrow and repeat on partial coverage; never describe a sampled topic list as exhaustive.
2. Describe representative topics with `kex_describe_topic`; inspect counts, partition offsets, detected format and groups. `detectedFormat` is sampled, not a broker-enforced schema.
3. Call `kex_infer_schema` in bounded batches. Record types, sampling limits, field confidence, format and nullable/optional ambiguities. Do not treat missing sampled fields as absent from the topic.
4. Call `kex_deduce_data_model` and record HIGH/MEDIUM/LOW relation confidence plus the evidence. Relationships inferred from field names are hypotheses, not declared foreign keys.
5. Where a relation is supported, call `kex_build_join` or use the generated SQL and verify with `kex_sql_query` in a bounded SELECT/EXPLAIN. If the tool reports an unreachable entity, report that gap; never invent a join predicate.
6. Produce a compact Mermaid `erDiagram`, a topic-to-entity mapping and runnable SQL examples with assumptions, confidence and incomplete coverage clearly marked.

## Output contract

Domain boundaries; entity/topic/field matrix; evidence-backed relationships and provenance; diagram; verified or explicitly unverified SQL; data-contract risks; unknowns and next validation steps.

## Safety / failure modes

- No DDL changes, table registration, production writes or topology mutation.
- Do not leak payloads, secrets, credentials or personal data into architecture deliverables.
- Never bypass resource scope or disabled tools through direct broker access.

## Acceptance

All proposed joins have named keys and evidence; each diagram relationship has a confidence grade; any query that has not actually run is labelled unverified.
