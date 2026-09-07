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

## One layer makes three claims true

`McpToolInterceptor` wraps every tool's call handler, and it exists because three things this
module already asserted were, without it, false:

- **The recorder was written, tested, wired to metrics — and called by nothing.** A serving
  deployment reported `explorer_mcp_calls_total` at zero, which reads as "no calls", not as
  "nothing counts". That is the module's own invariant broken by the module's own bookkeeping.
- **`hard-max-output-bytes` was published in the catalogue as a ceiling a caller cannot argue
  out of, and nothing measured a byte.** A guarantee that is advertised and not kept is worse
  than none.
- **`McpToolException` carried a KIP-1318 code that nothing read.** `ToolGuard` refused
  correctly, then the SDK flattened the refusal into a generic tool error — so the whole reason
  for adopting those numbers, that an agent trained on that surface can tell "out of scope" from
  "the broker is down", did not survive the trip.

Three claims, one missing layer. Two details of it are load-bearing:

**The envelope arrives as a `Map`, not as a `ToolResult`.** Spring AI serialises a tool's return
value to JSON and parses it back as a plain `Object` before anything downstream sees it — that
round trip *is* MCP structured content. `McpCallContext`'s first version tested
`instanceof ToolResult`, which can never match there: every call would have recorded "does not
count records", and the induced-load column would have read empty on a busy server. Caught by
reading the SDK, not by a test — the test would have passed against a mock returning our type.

**An oversized payload is refused, not cut.** Truncating a serialised result gives malformed JSON
at best and, at worst, a shorter answer a model cannot distinguish from a complete one — the exact
failure this module exists to prevent. The reply names the tool, the size, the ceiling and what to
narrow, and the caller pays one round trip to keep the truth.

`McpToolSpecificationPostProcessor` is how it attaches: Spring AI's `toolSpecs` bean carries no
`@ConditionalOnMissingBean`, so a bean of ours would collide rather than displace it. Post-processing
takes the list the framework built and hands back the same tools with wrapped handlers. It wraps the
**stateful** specification, which is what the shipped `STREAMABLE` protocol produces; switching
`spring.ai.mcp.server.protocol` to `STATELESS` produces a different type this does not see, and
would silently drop all three guarantees above.

## MCP has two error channels, and which one a refusal takes decides who reads it

The SDK documents `CallToolResult.isError` as "the tool **execution** failed and the content
contains error information" — that result goes back to the model. A JSON-RPC error is a failure of
the *call*, and a client may surface it as a transport fault without showing the model anything.
`McpErrorCode.Level` therefore splits the codes, and it is not bookkeeping:

- **`EXECUTION` → an `isError` result.** `-32046` (a `SELECT id, FROM orders`) and `-32043` (the
  broker was away). Phase 1 sent both as hard errors, which quietly undid the reason `SqlMcpTools`
  preserves the planner's sentence at all: "unknown column at line 1, column 8" only turns a failed
  call into a correct one if the thing that has to rewrite the query can see it.
- **`PROTOCOL` → a JSON-RPC error.** Scope, quarantine, taint, approval, rate limit, policy,
  exfiltration. These must *not* arrive as readable tool output: a refusal the model can read is a
  refusal it will try to phrase its way around, and an agent probing at a scope denial is precisely
  what the guard exists to stop.

## `dlp.mode: block` is a refusal now, not a synonym for `redact`

`BLOCK` appeared nowhere in `DlpScrubber` — only `OFF` was distinguished — so an operator who set
it, believing a payload carrying a secret would not leave, received the same masked payload
`redact` produces. A security setting that reads stricter than it behaves is worse than not
offering the setting. It now raises `-32045`, detected by "the masker changed something", which is
exact by construction: what would be masked *is* what is blocked, with no second pattern set to
disagree with the first.

Call **parameters** are the deliberate exception: always redacted, never blocked. Block is about
what leaves the cluster; an argument came *from* the caller, so refusing it protects nobody and
loses the record of the call. What matters there is that the secret does not settle into the ring
buffer and the audit topic.

## The tool hints were not merely missing — they were wrong

`@McpTool.McpAnnotations` defaults `readOnlyHint` to **false** and `destructiveHint` to **true**, so
a tool that declares neither advertises itself as potentially destructive. All six read tools were
making that claim. Clients use these hints to decide whether a call needs a human in the loop, so
the cost fell exactly where it is least deserved: an approval prompt per call, on the tools an agent
uses to explore. `ToolAnnotationsTest` pins them, and will fail the tool added next that forgets.

## Two settings that could not have meant anything

`scrub-all-outputs` shipped, was read by nothing, and could not have done anything: redaction
already applies to every output whenever the mode is not `off`, so its two values described one
behaviour. Removed — a knob that cannot change what happens invites an operator to believe they
have narrowed something.

`approval-required-tools` is read only by the catalogue, which badged a tool
`EXPOSED_WITH_APPROVAL` while no approval token is checked anywhere. The badge now carries the
reason, saying it is declared and not enforced until phase 5: a console asserting a control that
does not exist fails at the one job it has.

## The console, and the two rules that shape every number on it

Phase 2 is `/api/mcp/**` plus the React screen at `/mcp`. It is the visible counterpart of the
decision to point a model at a cluster, and no other Kafka MCP server offers one — their surface is
discovered with `--list-tools` and their usage by grepping logs.

**Every aggregate carries the window it actually rests on.** `ObservedWindow` travels with the
catalogue and the stats: how far back the ring reaches, how much it evicted, and whether anything is
persisted beyond it. The ring is bounded by design — it is a live feed, the audit topic is history —
so a card headed "24 h" computed over a ring that reaches back forty minutes is a wrong number
wearing a right label, and wrong in the direction that reassures. `windowCaveat` renders the warning
only when there is one; an advisory shown permanently becomes furniture.

**A percentile over too few calls is refused, not computed.** `McpToolRow.MIN_CALLS_FOR_P95` is 20.
Below it, the "95th percentile" is simply the slowest call, most often a cold start, and an operator
reading it as typical goes hunting for a problem that is not there. `0 ms` would be worse still — it
says the tool is instantaneous. The value is `Measured`, and `<MeasuredValue>` renders the reason.

Three smaller decisions worth knowing:

- **The endpoint is the bound address, not the property.** `McpEndpointResolver` captures the port
  from `WebServerInitializedEvent` (which moved to `org.springframework.boot.web.server.context` in
  Spring Boot 4). `server.port=0`, a container mapping or a command-line override all make the
  configured value a URL that does not answer — and it would be pasted into a client before anyone
  found out.
- **The controller answers even when the module is absent**, which is the shipped default. A 404
  would leave the screen unable to tell "the server is off" from "this build is too old"; the empty
  state that explains itself is the reason the screen exists at all.
- **"Try it" runs the real specification.** `McpToolInvoker` resolves the tool out of the same list
  the transport serves — already wrapped by the interceptor — so the scope check, the ceilings, the
  redaction and the recording all apply. Only the attribution differs: `McpCallOrigin` marks it
  `CONSOLE` through a ThreadLocal, because the interception point is an SDK `BiFunction` this
  application cannot add a parameter to. A "Try it" that bypassed the guard would answer a different
  question from the one the button asks.

## Phases

| Phase | Content | State |
|---|---|---|
| 1 — Socle honnête | `Coverage`/`Measured`/`ToolResult`, `McpProperties`, `ToolGuard`, `McpCallRecorder` + metrics, `McpCatalogService`, `McpToolInterceptor`, tools `kex_list_topics` / `kex_describe_topic` / `kex_preview_messages` / `kex_infer_schema` / `kex_sql_query` / `kex_list_tables` | **done** |
| 2 — Écran MCP | `/api/mcp/status`, `/catalog`, `/calls`, `/stats`, `/clients`, `/catalog/client-config`, `/try/{tool}`; the React page with its Catalogue and Supervision tabs | **done** |
| 3 — Différenciation | `kex_trace_key`, `kex_resume_trace`, `kex_analyze_dead_letters`, `kex_consumer_lag` | not started |
| 4 — Modélisation | `kex_deduce_data_model`, `kex_build_join`, `kex_run_audit`/`kex_get_audit`, `kex_suggest_kpis` | not started |
| 5 — Entreprise | OAuth 2.1, taint guard, approval token, audit topic + replay, kill switch | not started |

Phase 2 before phase 3 is the spec's ordering and it is kept: instrumentation added after the
fact is instrumentation that never gets added.

## Off by default

`explorer.mcp.enabled` ships **false**. Turning an agent loose on a cluster is a decision an
operator makes deliberately, and a feature that arrives switched on in an upgrade is not that.
`readonly` ships `true` and stays true unless it is written otherwise.
