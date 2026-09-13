<!--
SPDX-License-Identifier: AGPL-3.0-or-later
Copyright (C) 2026 Kafka Explorer Contributors
-->
# MCP server — audit (2026-09)

Full review of `src/main/java/com/compagnonsdudev/kafkasqlexplorer/mcp/**` (7 397 lines, 15 tools,
5 phases) plus the surfaces that carry it: `McpHttpAuthFilter`, `compose/mcp.yml`, `mcp-probe.sh`,
`application.yml`, `docs/notes/mcp-server.md`, `docs/notes/mcp-security-p0.md` /
`mcp-security-p1.md`, and the SPA page `pages/Mcp.tsx`.

Nothing here is fixed yet: this report is the finding list. Each item names the file, what it
costs, and the shape of the fix.

## What was run

| Evidence | Result |
|---|---|
| `./verify-offline.sh "--include-classname=.*Mcp.*"` on this tree | 231 tests, **6 failed**, 1 aborted |
| CI run 945 on `main` (`4426490`), job `build` | **BUILD FAILURE** — the same 6, inside 1 563 tests |
| CI run 945, job *The MCP probe's decisions* | **10 of 10 cases failed** |
| `sh mcp-probe.test.sh` locally | **10 of 10 cases failed**, all `FAIL: MCP_AUTH_TOKEN is required` |

`main` is red, and every red test is in this module. The failures are not flakes — they reproduce
identically offline and in CI, and each one is a symptom of a finding below (P0-1, P0-2, P2-9,
P1-7).

## P0 — the MCP surface is inside out

### P0-1 · `McpHttpAuthFilter.shouldNotFilter` protects the reads and leaves the writes open

`src/main/java/com/compagnonsdudev/kafkasqlexplorer/mcp/security/McpHttpAuthFilter.java:32-40`

`OncePerRequestFilter.shouldNotFilter` returning `true` means *skip the filter*. The `/api/mcp/**`
branch returns `true` for exactly the endpoints that change state:

```java
if ("GET".equalsIgnoreCase(request.getMethod())) return "/api/mcp/calls/replay".equals(path);
return path.startsWith("/api/mcp/try/") || path.startsWith("/api/mcp/toggle/")
        || path.startsWith("/api/mcp/quarantine/") || path.startsWith("/api/mcp/approve/");
```

So, with `explorer.mcp.enabled=true`:

- `POST /api/mcp/try/{tool}`, `/toggle/readonly`, `/toggle/tool/{name}`, `/quarantine/{identity}`,
  `/approve/{tool}` — **no token required**. Anyone who can reach the application can lift the
  read-only lock, switch a tool off, quarantine an agent, or mint an approval token
  (`allow-runtime-toggle` ships `true`).
- `GET /api/mcp/calls/replay` — **no token required**, though it replays the audit trail, which is
  the one console read `docs/notes/mcp-security-p0.md` singles out as needing the token.
- every other console read (`/status`, `/catalog`, `/calls`, `/stats`, `/clients`, `/overrides`) —
  **401**. `pages/Mcp.tsx:81-86` sends plain `axios.get` with no `Authorization` header, so the MCP
  screen is blank-with-errors on any deployment that enables the server.

The module's own test says the opposite and fails:

```
McpHttpAuthFilterTest.privilegedEndpointsAreProtected:72 expected: <401> but was: <200>
```

The test is right and the filter is wrong; the two branches need their return values swapped
(`return false` for the privileged paths, `true` for the ordinary console GETs). A single assertion
per path, as the test already writes them, keeps it fixed.

### P0-2 · `/mcp` answers nothing in a deployment configured the documented way

`McpProperties.requireTls` defaults `true` and `McpProperties.authToken` defaults `null`, and
**neither appears in `application.yml`** — the file that documents every other `explorer.mcp.*`
knob at length (`src/main/resources/application.yml:262-358`). An operator who follows that file
and sets `explorer.mcp.enabled=true` gets:

- `503 mcp_auth_not_configured` while no token is set, and
- `426 mcp_tls_required` as soon as one is, unless TLS terminates in a way that leaves
  `request.isSecure()` true.

Both are the correct fail-closed behaviour and neither is discoverable from the configuration file
or from `docs/notes/mcp-server.md`, which still states the module's posture as "this application
authenticates nobody" and lists OAuth as *not started*.

The consequence in the test suite is the part that matters: `McpTransportContractTest` — the only
test in this repository that talks to the bound port as a third-party client, and the one that
exists because *the server once shipped with no reachable endpoint* — now fails at the handshake:

```
McpTransportContractTest.handshakesOverTheRealTransport » IllegalState initialize was refused:
    {"error":"mcp_tls_required","message":"the MCP HTTP endpoint requires TLS"}
McpTransportContractTest.listsEveryToolWithASchema » ... same
McpTransportContractTest.aGuardRefusalCrossesTheWireAsOne:214 a clamped request answered nothing
McpTransportContractTest.aDependencyFailureIsARefusalThatNamesItself:154 ... answered nothing
```

So the transport, the wire-level `Measured` / `Coverage` serialisation (the Jackson 2 vs Jackson 3
trap `CLAUDE.md` names as this module's most expensive pitfall), and the JSON-RPC refusal path are
all **currently unverified**. Fix: give that test class `explorer.mcp.require-tls=false` and
`explorer.mcp.auth-token=<fixture>` and have `McpHttpClient` send the bearer header — the test then
covers the auth boundary instead of being blocked by it. Then document both properties in
`application.yml` beside `enabled` and `readonly`.

### P0-3 · `kex_sql_query` ignores `allowed-topic-prefixes` entirely

`src/main/java/com/compagnonsdudev/kafkasqlexplorer/mcp/tools/SqlMcpTools.java:81-126`

Every other tool calls the guard before any I/O — `TopicMcpTools:153,213,250`,
`SchemaMcpTools:76`, `StreamFlowMcpTools:100,174`, `ConsumerLagMcpTools:107`,
`DataModelMcpTools:82,156`, `AuditMcpTools:94`. `SqlMcpTools` calls **no** scope check at all,
and `FlinkSqlService.autoRegister` (`FlinkSqlService.java:499-556`) resolves any name in `FROM` to
a topic through `DdlGeneratorService.toTableName`, registers it and reads it.

On a deployment configured `allowed-topic-prefixes: "demo."`:

```
kex_preview_messages(topic="internal.mcp.audit")  → -32041 out of scope
kex_sql_query(sql="SELECT * FROM internal.mcp.audit") → the rows
```

The audit trail, `internal.field.mappings`, `internal.audit.history` and every regulated topic the
prefix was written to withhold are one tool call away. `McpConsoleService:76-77` publishes
`topicScope` to the console as the deployment's posture, so the screen states a restriction the
largest tool does not apply — the exact "a control an operator believes is in force and is not"
this module's notes keep removing elsewhere.

`kex_list_tables` has the same hole in the quieter direction: it returns the names, columns and
DDL of every table registered in the process, including those an out-of-scope query registered.

Fix: in `sqlQuery`, resolve the sources with `SqlAst.read(sql)` / `SqlAst.tableNames(read)` (both
public, and already the parser `FlinkSqlService` trusts), map each to its topic the way
`registerSourceTable` does (`toTableName(topic).equals(reference)`), and call
`guard.checkTopicScope(...)` **before** `flink.executeSync`. Where the scope is restricted and a
source resolves to no topic, refuse rather than let auto-registration decide — a name that cannot
be resolved cannot be proven in scope. `listTables` filters its rows by the same rule.

### P0-4 · DLP never reaches the rows `kex_sql_query` returns

`SqlMcpTools.java:110` scrubs `result.warnings()`. `result.rows()` — the payload — is returned
verbatim at line 112. `kex_preview_messages` scrubs key and value (`TopicMcpTools:231-232`) and
`kex_trace_key` scrubs key and preview (`StreamFlowMcpTools:254-255`), so `dlp.mode=redact` holds
on the small readers and not on the one that can read a whole topic. `dlp.mode=block`, which is
specified to refuse rather than mask, therefore cannot fire on a SQL result either.

Fix: run `guard.dlp().scrub(...)` over the string values of each row before building
`SqlView.SqlAnswer` (and let `BLOCK` raise from there, as `DlpScrubber.blockOrReturn` already
does). Note that this is the one place where a per-cell scrub has a cost worth measuring — a 1 000
row × 20 column answer is 20 000 regex passes; scrubbing only `String` cells, as `scrubValue`
already does for parameters, keeps it to the columns that can carry a secret.

## P1 — controls that do not bind what they claim

### P1-5 · Two identity models, and the guards use the weaker one

`McpToolInterceptor.identityOf` (`:336-342`) names the caller `session:<exchange.sessionId()>`.
`McpCallerContext` (`security/McpCallerContext.java`) holds `bearer:<sha256>` — the authenticated
identity — and is read by **one** class, `McpTraceStore:80,94`.

So quarantine, the rate limit and the audit-trail key are all keyed on the MCP *session id*:

- a quarantined agent reconnects and is no longer quarantined — one `initialize` away from the
  lever `docs/notes/mcp-server.md` calls "the lever that matters most in an incident";
- the token bucket resets on reconnect, so the rate limit bounds a session, not a caller;
- the audit topic is keyed by session id, so "what did this credential do last Tuesday?" cannot be
  answered by the key, and the compaction/ordering rationale in `KafkaMcpAuditSink` (one record per
  caller, a caller's calls in order) does not hold.

Fix: `identityOf` reads `McpCallerContext.identity()` first and falls back to the session id only
when it is `local` (stdio, console "Try it"). That also makes the console's quarantine row act on
something stable enough to be worth typing.

Related, and worth one test rather than a paragraph: `McpCallerContext` is a `ThreadLocal` set by
the servlet filter, and nothing asserts that Spring AI dispatches the tool call on that same
thread. If it ever does not, every paused trace is owned by `local` and the P0 resume-token
isolation silently becomes no isolation. The only test that could see it is
`McpTransportContractTest`, which does not currently connect (P0-2).

### P1-6 · `explorer_mcp_audit_write_errors_total` misses the ordinary hole

`McpCallRecorder:96-99` increments the counter when `auditSink.append` **throws** — a
serialisation failure, or `send()` refusing synchronously. The ordinary failure — the broker
rejecting or timing out the append — arrives in the producer callback
(`KafkaMcpAuditSink:71-78`), which logs and closes the producer and increments nothing. Its own
comment says so: *"Asynchronous, so it cannot be counted by the recorder's own catch"*.

The gauge is documented in three places as *the* signal that the trail has holes. It is silent in
the case that actually makes holes. Fix: pass a failure sink (a `LongAdder`, or the recorder's
own `auditWriteErrors`) into `KafkaMcpAuditSink` and increment it from the callback.

Two smaller things in the same file: the metric is registered as a `Gauge` whose name ends in
`_total`, which no Prometheus `rate()` rule will treat as expected — a `Counter` is what the name
promises; and `closeProducer()` runs from the producer's own callback thread on every failure, so a
broker outage rebuilds a `KafkaProducer` per call (threads, metadata fetch, 500 ms `MAX_BLOCK_MS`)
on the thread serving tool calls.

### P1-7 · The probe's own test suite does not pass a token, so all 10 cases fail

`mcp-probe.sh:27` now hard-requires `MCP_AUTH_TOKEN`; `mcp-probe.test.sh` never sets one, so every
case exits 1 before reaching the decision it asserts. Reproduced locally and in CI. The suite tests
the probe's *decisions* (how it reports MCP off, a deny-list, a tool error, a missing coverage
envelope), none of which need a live server — so the fix is one `MCP_AUTH_TOKEN=test-token` export
in the harness, plus one new case asserting the missing-token refusal that is now being tested by
accident.

### P1-8 · A default credential ships, and a note says it does not

`docs/notes/mcp-security-p0.md:16` — *"The bundled MCP compose overlay requires
`EXPLORER_MCP_AUTH_TOKEN` explicitly with Docker Compose's `:?` interpolation. There is no
development default credential."*

`compose/mcp.yml:14` — `EXPLORER_MCP_AUTH_TOKEN=${EXPLORER_MCP_AUTH_TOKEN:-dev-only-mcp-token}`
`compose/mcp.yml:43` — the same default for the probe
`.env.example:70` — `EXPLORER_MCP_AUTH_TOKEN=dev-only-mcp-token`

The `:?` was replaced by `:-` in `11c9f18` so the compose-configuration check could run
self-contained. That is a reasonable trade for CI and it makes the note false, which is the part
that has to change: a shared secret with a published value protects nothing, and the note is what
an operator reads before deciding whether to set one. Either restore `:?` and give the checker its
own `.env`, or say plainly in the note that the overlay carries a development credential that a
shared deployment must override.

## P2 / P3 — smaller, and each with a one-line fix

**P2-9 · `McpRateLimiterBoundTest` asserts a bound Caffeine does not promise synchronously.**
`identityCardinalityIsBoundedAgainstMemoryExhaustion` inserts 10 500 identities and asserts
`estimatedSize() <= 10 000`. Caffeine evicts asynchronously, so the assertion is timing-dependent —
it fails here and in CI. The limiter is fine; the test needs `buckets.cleanUp()` inside
`identityCount()` (test-visible accessor) before estimating.

**P3-10 · An approval token is bound to a tool, never to a caller.** `McpApprovalStore.spend`
checks `tool` and TTL. Any caller may spend a token minted for any other, which matters on the
deployment the store exists for — one where more people reach the application than may approve.
Binding the mint to the identity that will spend it is a two-field change; leaving it as is would
at least deserve a sentence in the class doc, which currently enumerates four properties as if they
were exhaustive.

**P3-11 · `docs/notes/mcp-server.md` predates the security work.** It has no mention of the bearer
filter, of `require-tls`, or of `McpCallerContext`, and its phase table still reads *5b — OAuth 2.1
· not started* beside a shipped token boundary. `CLAUDE.md`'s MCP section (four binding rules) says
nothing about authentication either, so the next change to this module starts from a map that is
missing its newest road. `docs/notes/mcp-security-p0.md` and `-p1.md` are separate files with no
inbound link from either.

**P3-12 · `ToolGuard` keeps its own quarantine set beside `McpRuntimeSwitches`.** Two sources of
truth for one control, reconciled by the interceptor checking both (`:189-196`). It works, and only
one of them (`McpRuntimeSwitches`) carries the actor and reason the console renders, so an identity
quarantined through `ToolGuard.quarantine` shows up in no banner.

## What holds

Worth stating, because the list above is not the shape of the module:

- **Registration-time read-only and the deny-list by absence** are correct and correctly reasoned.
  A tool that is not a bean is not scanned; a denied tool is filtered out of the specification list
  before the transport sees it (`McpToolSpecificationPostProcessor:55-84`). Both are stronger than
  the refusal-in-the-method that the framework would have made tempting.
- **`Measured<T>` / `Coverage` as real record components** rather than a Jackson serializer, for
  the Jackson 2 / Jackson 3 reason — the one decision in this module that could not have been
  recovered later.
- **The two MCP error channels** (`McpErrorCode.Level`): an execution failure the model must read
  comes back as `isError` content, a guard refusal as a JSON-RPC error. That distinction is rarer
  than it should be in MCP servers.
- **The interceptor** genuinely makes the three claims true that it was added for — the metrics
  move, the output ceiling is measured and refused rather than truncated, and the KIP-1318 code
  survives to the client.
- **The console** — every aggregate carrying its observed window, a p95 refused below 20 calls,
  the endpoint read from the bound port rather than the property. There is no other Kafka MCP
  server with an equivalent.
- **1 563 tests**, of which 231 cover this module, including the boot test that asserts what
  `tools/list` answers and the catalogue against it.

The findings above are what happens when a security boundary is added late to a module whose
contracts were written assuming there was none: the boundary is correct in isolation
(`McpHttpAuthFilter` fails closed, hashes the token, compares in constant time) and wrong in every
place it had to meet something that already existed — the console's unauthenticated reads, the
interceptor's session identity, the transport test's plain HTTP client, the probe harness.

## Suggested order

1. P0-1 — swap the two `shouldNotFilter` branches. Smallest diff, largest exposure closed, and it
   turns four CI failures into one.
2. P0-2 — configure the transport contract test with a token, and document both properties in
   `application.yml`. The wire-level contract is unverified until this lands.
3. P0-3 / P0-4 — scope and DLP in `SqlMcpTools`, with the cases
   `SqlMcpToolsTest` does not yet have: an out-of-scope `FROM`, an out-of-scope `JOIN`, a
   restricted scope with an unresolvable source, and a row carrying a credential.
4. P1-5 — one identity, read from `McpCallerContext`.
5. P1-6, P1-7, P1-8, P2-9 — each independent, each small.
