---
name: kafka-schema-evolution
description: Assess Kafka event schema changes using bounded samples and an explicit baseline, separating observed drift from unverified Schema Registry compatibility.
---

# Kafka schema evolution review

Use when a producer or consumer contract may have changed, or when assessing a proposed schema migration. This skill reviews **observed fields and explicit baselines**; it does not pretend that sampled records establish Schema Registry compatibility.

## Preconditions

- Confirm the permitted cluster, topic(s), environment and time interval.
- Obtain a versioned **baseline** (JSON Schema, Avro, Protobuf or documented field contract) from an approved source. Do not infer the previous contract from the latest sample.
- Discover the actual MCP tools; `kex_infer_schema` and `kex_describe_topic` may be absent or restricted.
- Record the intended compatibility mode (BACKWARD, FORWARD, FULL or transitive variants), format, key/value subject naming and consumer constraints **only when supplied by the operator**.

## Procedure

1. Call `kex_describe_topic` for metadata and detected format, then `kex_infer_schema` with a bounded record budget. Record partitions, sample size, confidence, missing segments and `coverage`. Detection from a sample is **not** a registry-enforced schema.
2. Normalize the approved baseline and observed candidate into a field matrix: path, primitive/logical type, optionality/nullability, default, enum/domain and evidence. Mark nested unions, tombstones, numeric promotions and logical types as uncertain unless the actual schema is available.
3. Identify observed additions, removals, type shifts, nullability changes, field renames and changes in apparent encoding. Treat absence from a sample as **not observed**, not deleted. Keep the sample's collection time and provenance.
4. Classify each change as `observed drift`, `potential compatibility risk`, `unverified` or `no difference observed in sample`. Do not assert BACKWARD/FORWARD/FULL compatibility from inferred JSON fields.
5. If an authorized Schema Registry client and its **actual compatibility-check API** are available outside this skill, request a read-only registry check against the correct subject/version/mode; attach its result. KafkaExplorer's `kex_infer_schema` alone is not a substitute. Never claim the registry was queried unless evidence exists.
6. Propose consumer/producer contract tests, staged rollout, dual-read or migration windows and rollback. Identify any required explicit approval; do not publish or register a new schema.

## Output contract

- Baseline source/version, observed topic/format, collection window, sample coverage and unknowns.
- Change matrix: `field | baseline | observed | evidence | change type | risk | confidence`.
- Compatibility assessment **with its evidence class**: registry-verified only when a real registry check occurred; otherwise unverified.
- Consumer impact hypotheses, rollout plan, recommended contract tests and rollback conditions.

## Safety / failure modes

- No schema registration, producer changes, broker writes, ACL changes or consumer offset mutation.
- Do not print record payloads or personal data; retain only necessary field/type evidence.
- Respect scope, output budgets, tool refusals and DLP. Do not infer a removed field from a truncated sample.
- Never conflate `detectedFormat` with an authoritative schema or a schema ID.

## Acceptance

Every reported difference has a baseline reference and observed evidence or is clearly labelled hypothetical. No unverified compatibility claim is stated as fact, and no schema or data is mutated.
