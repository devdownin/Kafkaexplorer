<!--
SPDX-License-Identifier: AGPL-3.0-or-later
Copyright (C) 2026 Kafka Explorer Contributors
-->
# The MCP server — plan, and why each piece is shaped the way it is

`SPEC-MCP.md` at the root is the specification. This note is the *implementation* record: what
has shipped, in what order, and which of the spec's decisions had to be re-decided against the
code that actually exists. Read it before touching anything under
`src/main/java/com/compagnonsdudev/kafkasqlexplorer/mcp/`.

## Where it sits

The server is a module of the same JAR (P4 in the spec), not a second process. Every tool is a
thin adapter over a service the UI already calls — `KafkaAdminService`, `FlinkSqlService`,
`SchemaInferenceService`, `StreamFlowService`, `AuditService`. **No business logic lives under
that package.** That is not tidiness: a second implementation of "read the last N records of a topic"
is a second set of budgets, a second cache, and a second answer to the same question, and the
divergence surfaces as an agent and an operator disagreeing about the same cluster.

## The invariant the whole module exists to carry

> A measurement that failed is never zero.

The application already holds this in `PartitionLag`, `PartitionTimeLag` and `TopicTimeLag`.
The MCP module extends it to every response, because the consumer here is a language model and
a bare `0` is read as a *fact* — "no lag", "no failures", "not found". Two contracts carry it:

- `Measured<T>` — a value that could legitimately not be measured. It serialises as
  `{"value": 12, "measured": true}` or `{"value": null, "measured": false, "reason": "…"}`.
  Never a naked `null`, which a model reads as zero just as readily.
- `Coverage` — what was read, what was *not*, why the pass stopped, and a resume token.
  `hops: []` with `stopReason: TIME_BUDGET` means "not found in what was scanned", which is a
  different sentence from "does not exist", and the difference is the whole point.

Every tool description repeats the reading rule in prose, because the model reads the
description before it reads the payload.

## Registration, not invocation, is where read-only bites (P3)

Spring AI 2.0 scans `@McpTool` methods on **every bean** in the context
(`McpServerAnnotationScannerAutoConfiguration`). So "hide a mutating tool" cannot be a check
inside the method — it has to be that the bean does not exist.

`McpServerConfiguration` therefore declares the tool beans itself rather than letting them be
`@Component`s: read tools unconditionally, mutating tools only when
`explorer.mcp.readonly=false`. A tool that is not a bean is not scanned, so it is neither
listed by `tools/list` nor invocable by name, whatever the prompt says.

`McpCatalogService` is told about **both** sets, and that asymmetry is deliberate: the console
must be able to answer "why does my agent not see `kex_produce_message`?" with
`Masqué (lecture seule)` rather than by the tool's absence. A missing row is not an answer.

## The guard order is KIP-1318's, unchanged

`ToolGuard` implements the spec's §5.7 pipeline in the spec's order, fail-closed. Two notes:

- **Scope is checked before any I/O.** `checkTopicScope` runs before the Kafka client is
  touched, and `StreamFlowMcpToolsTest` asserts `verifyNoInteractions(streamFlow)` for exactly
  that reason: a scope violation that has already read the topic has leaked what it refused.
- **A clamp is not a rejection.** A budget above the ceiling is clamped and *said so* in the
  warnings, rather than refused. The caller asked for something reasonable in the wrong unit far
  more often than it attacked us, and a refusal there costs a round trip for nothing.

## Phases

| Phase | Content | State |
|---|---|---|
| 1 — Socle honnête | `Coverage`/`Measured`/`ToolResult`, `McpProperties`, `ToolGuard`, `McpCallRecorder` + metrics, `McpCatalogService`, tools `kex_list_topics` / `kex_describe_topic` / `kex_preview_messages` / `kex_infer_schema` / `kex_sql_query` / `kex_list_tables` | **in progress** |
| 2 — Écran MCP | `/api/mcp/status`, `/catalog`, `/calls`, `/stats`; React page | not started |
| 3 — Différenciation | `kex_trace_key`, `kex_resume_trace`, `kex_analyze_dead_letters`, `kex_consumer_lag` | not started |
| 4 — Modélisation | `kex_deduce_data_model`, `kex_build_join`, `kex_run_audit`/`kex_get_audit`, `kex_suggest_kpis` | not started |
| 5 — Entreprise | OAuth 2.1, taint guard, approval token, audit topic + replay, kill switch | not started |

Phase 2 before phase 3 is the spec's ordering and it is kept: instrumentation added after the
fact is instrumentation that never gets added.

## Off by default

`explorer.mcp.enabled` ships **false**. Turning an agent loose on a cluster is a decision an
operator makes deliberately, and a feature that arrives switched on in an upgrade is not that.
`readonly` ships `true` and stays true unless it is written otherwise.
