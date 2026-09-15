<!--
SPDX-License-Identifier: AGPL-3.0-or-later
Copyright (C) 2026 Kafka Explorer Contributors
-->
# MCP server — audit (2026-09)

Full review of `src/main/java/com/compagnonsdudev/kafkasqlexplorer/mcp/**` (7 397 lines, 15 tools,
5 phases) plus the surfaces that carry it: `McpHttpAuthFilter`, `compose/mcp.yml`, `mcp-probe.sh`,
`application.yml`, `docs/notes/mcp-server.md`, `docs/notes/mcp-security-p0.md` /
`mcp-security-p1.md`, and the SPA page `pages/Mcp.tsx`.

**Everything marked *fixed* is fixed on this branch**, with the tests that pin it named beside it;
everything marked *open* is a finding and nothing more. The build was red when this started and
every red test was in this module — each of those failures is one of the findings below, which is
the part worth saying first: the suite already knew.

## What was run

| Evidence | Before | After |
|---|---|---|
| `./verify-offline.sh "--include-classname=.*Mcp.*"` | 231 tests, **6 failed** | 257 tests, **0 failed** (1 aborted, the deliberate no-cluster `assumeTrue`) |
| `./verify-offline.sh` (the whole backend) | — | 1 572 tests, **0 failed** |
| CI run 945 on `main` (`4426490`), job `build` | **BUILD FAILURE** — the same 6 inside 1 563 tests | — |
| `sh mcp-probe.test.sh` | **10 of 10 cases failed** | 11 cases, all pass |

The failures were not flakes — they reproduced identically offline and in CI, and each was a symptom
of a finding below (P0-1, P0-2, P1-7, P2-9).

## P0 — the MCP surface is inside out

### P0-1 · `McpHttpAuthFilter.shouldNotFilter` protected the reads and left the writes open — **fixed**

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

The test was right and the filter was wrong. **Fixed**: under `/api/mcp/**` privilege is now
decided by exclusion — every non-GET is covered, plus `/calls/replay`, and the ordinary console
reads are not. By exclusion rather than by a list of paths, so the endpoint added next is covered
by default instead of open by omission; `McpHttpAuthFilterTest` asserts both directions and an
unknown future `POST`.

**One consequence, deliberate — and since built out.** The console's own write gestures — the
toggles, quarantine, the approval mint, the replay — answer 401 in a browser, which holds no token.
That is the posture `docs/notes/mcp-security-p0.md` asks for, and it left the kill switch unusable
exactly where an operator reaches for it. The Supervision tab now has a **Jeton MCP** card: the
operator pastes the token they already hold, it rides the privileged calls and only those, it lives
in `sessionStorage` (never `localStorage`, never a URL, never a read), it is never re-displayed —
`…` and the last four characters — and *Forget* takes it back. A storage that refuses to keep it
says so rather than showing a card that claims to hold what it does not. `explainHttpRefusal` now
takes whether a token is held, because "paste one" and "paste a different one" are different
gestures behind the same 401.

### P0-2 · `/mcp` answered nothing in a deployment configured the documented way — **fixed**

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
trap `CLAUDE.md` names as this module's most expensive pitfall), and the JSON-RPC refusal path were
all **unverified**, by a suite that looked busy.

**Fixed** in two halves. The test class configures the token and `require-tls=false`, and
`McpHttpClient` carries a bearer header — read back from the context rather than restated, so the
two cannot drift — which puts all six cases back in front of the wire; a new case asserts that an
anonymous client is refused, so the boundary is now a fact the transport test owns rather than an
obstacle to it. `mcp-probe.sh` and the agent harness take `MCP_AUTH_TOKEN` the same way. And both
properties are documented in `application.yml` beside `enabled` and `readonly`, with what an
ingress that terminates TLS has to forward for `require-tls` to mean what it says.

### P0-3 · `kex_sql_query` ignored `allowed-topic-prefixes` entirely — **fixed**

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

**Fixed** by `SqlSourceScope`, called before `flink.executeSync`. The sources come from
`SqlAst.read` — the parser `FlinkSqlService` already trusts — and each is matched back to the topic
it would register (`toTableName(topic).equals(reference)`), because a prefix is written in topic
terms and a statement names Flink tables, where a dot has become an underscore: comparing the
written name against the prefix would let `internal_mcp_audit` through while refusing
`internal.mcp.audit`. Four rules came out of it, each with its case in `SqlMcpToolsTest`:

- a source that resolves to **no** topic is refused, not read: a hand-written table carries its own
  `'topic'` option, which is the one way left out of the scope once every reference is resolved;
- a `CREATE TABLE` naming a topic is refused on the literal, before anything is listed;
- a `DESCRIBE` is scoped like the read it is — it registers the table, which samples the records;
- a statement whose sources the parser cannot read is refused **while the scope is restricted**,
  since otherwise an unparseable statement is the way around the guard, and a broker that cannot be
  listed fails the check closed rather than open;
- the exempt shapes are **named** (`SHOW`, `EXPLAIN`, `CREATE`, `USE`, `SET`) and everything else is
  checked. Written the other way round — "only a statement starting with `SELECT`" — a parenthesised
  set operation walked past the guard unlooked-at, and the Flink catalogue is shared with the UI,
  which registers a table for any topic it is pointed at.

Nothing of this runs when the scope is `"*"`, the shipped default, so the common deployment pays
neither the parse nor the topic listing. `kex_list_tables` filters its rows by the same rule and
says how many it withheld — counted, never named.

### P0-4 · DLP never reached the rows `kex_sql_query` returns — **fixed**

`SqlMcpTools.java:110` scrubs `result.warnings()`. `result.rows()` — the payload — is returned
verbatim at line 112. `kex_preview_messages` scrubs key and value (`TopicMcpTools:231-232`) and
`kex_trace_key` scrubs key and preview (`StreamFlowMcpTools:254-255`), so `dlp.mode=redact` holds
on the small readers and not on the one that can read a whole topic. `dlp.mode=block`, which is
specified to refuse rather than mask, therefore cannot fire on a SQL result either.

**Fixed**: every string cell goes through `guard.dlp().scrub` before the answer is built, and
`BLOCK` raises `-32045` from there rather than returning a masked row. Strings only, as
`DlpScrubber.scrubParams` already does for arguments — a 1 000 row × 20 column answer is 20 000
regex passes otherwise, and a number carries neither a credential nor an address.

## P1 — controls that do not bind what they claim

### P1-5 · Two identity models, and the guards used the weaker one — **fixed**

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

**Fixed**: `identityOf` reads `McpCallerContext.authenticated()` first and falls back to the
session id only for the transports that carry no credential — stdio, and the console's "Try it".
One identity now: the quarantine an operator throws survives the reconnect it used to be defeated
by, the token bucket belongs to the caller rather than to the connection, and the audit topic is
keyed by the credential the review will ask about.

**And the thread question is answered rather than assumed.** `McpCallerContext` is a `ThreadLocal`
set by a servlet filter and read by the interceptor, and nothing asserted that Spring AI dispatches
the tool on that same thread — with the wrong answer the guards fall back silently and every paused
trace is owned by `local`, which is the P0 resume-token isolation quietly becoming none.
`McpTransportContractTest` now calls a tool over the wire and reads the recorded identity back out
of the recorder: it starts with `bearer:`. That assertion only became possible because the same
test can connect again (P0-2).

### P1-6 · `explorer_mcp_audit_write_errors_total` missed the ordinary hole — **fixed**

`McpCallRecorder:96-99` increments the counter when `auditSink.append` **throws** — a
serialisation failure, or `send()` refusing synchronously. The ordinary failure — the broker
rejecting or timing out the append — arrives in the producer callback
(`KafkaMcpAuditSink:71-78`), which logs and closes the producer and increments nothing. Its own
comment says so: *"Asynchronous, so it cannot be counted by the recorder's own catch"*.

The gauge is documented in three places as *the* signal that the trail has holes. It was silent in
the case that actually makes holes.

**Fixed**: the sink counts what it later fails to deliver and publishes it as
`McpAuditSink.asyncWriteErrors()`; `McpCallRecorder.auditWriteErrors()` — and the gauge, which now
reads through that method rather than off its own counter — returns both halves as one number. Read
rather than pushed, because a sink calling back into the recorder is a cycle between two beans, one
of which is constructed with the other.

**The producer is no longer dropped from the callback.** It ran `closeProducer()` on the producer's
own I/O thread, where `close()` cannot join itself, and it rebuilt a `KafkaProducer` — threads, a
metadata fetch, 500 ms of `MAX_BLOCK_MS` — on every transient timeout, on the thread serving tool
calls, for a client that reconnects and retries by itself.

**And the type is fixed without touching the name — fixed.** The metric was a `Gauge` whose
name ends in `_total`, so `rate()` and `increase()`, the two functions anyone alerting on a broken
audit trail reaches for, were not defined on it. Renaming the series would have broken every
dashboard already reading it for a convention the *type* can satisfy on its own, so it is a
`Counter` under the same name, incremented at both sites that notice a failure rather than derived
from a sum. The one risk in that — a registry appending `_total` to a name that already ends in it
— is asserted against a real Prometheus scrape rather than against the convention's documentation.

### P1-7 · The probe's own test suite passed no token, so all 10 cases failed — **fixed**

`mcp-probe.sh:27` now hard-requires `MCP_AUTH_TOKEN`; `mcp-probe.test.sh` never sets one, so every
case exits 1 before reaching the decision it asserts. Reproduced locally and in CI.

**Fixed**: the harness exports a token, and the missing-token refusal — which was being tested by
accident, ten times over — gets a case of its own. One case then failed for a better reason: the
probe answered a 404 with "Is EXPLORER_MCP_AUTH_TOKEN correct?", so an endpoint that is not bound
read as a credential problem. It reads the status now — 401 and 403 the credential, 426 the
transport, 503 a server with no token, 404 and 405 nothing bound — because a message listing every
possibility sends an operator to check three settings when the server already said which. Eleven
cases, all passing.

### P1-8 · A default credential ships, and a note said it does not — **fixed in the note**

`docs/notes/mcp-security-p0.md:16` — *"The bundled MCP compose overlay requires
`EXPLORER_MCP_AUTH_TOKEN` explicitly with Docker Compose's `:?` interpolation. There is no
development default credential."*

`compose/mcp.yml:14` — `EXPLORER_MCP_AUTH_TOKEN=${EXPLORER_MCP_AUTH_TOKEN:-dev-only-mcp-token}`
`compose/mcp.yml:43` — the same default for the probe
`.env.example:70` — `EXPLORER_MCP_AUTH_TOKEN=dev-only-mcp-token`

The `:?` was replaced by `:-` in `11c9f18` so the compose-configuration check could run
self-contained. That is a reasonable trade for CI and it made the note false, which is the half
that was fixed: **the note now says the overlay ships a development credential** and that any stack
reachable by more than its author must export its own. The compose default is left alone — a shared
secret with a published value protects nothing, but a check that cannot run is worse, and the
honest sentence is cheaper than either. Restoring `:?` with a `.env` of the checker's own stays
available if the default ever reads as an endorsement.

## P2 / P3 — smaller, and each with a one-line fix

**P2-9 · `McpRateLimiterBoundTest` asserted a bound Caffeine does not promise synchronously —
fixed.** `identityCardinalityIsBoundedAgainstMemoryExhaustion` inserts 10 500 identities and asserts
`estimatedSize() <= 10 000`. Caffeine evicts on later reads and writes, so the assertion was
timing-dependent and failed here and in CI. The limiter was never wrong; `identityCount()` drains
the cache before estimating, which makes the bound the test reads the bound the cache keeps.

**P3-10 · An approval token was bound to a tool, never to a caller — fixed.**
`McpApprovalStore.spend` checked `tool` and the TTL, so any caller could spend a token minted for
any other: on the deployment this store exists for — one where more people reach the application
than may approve — the approval an operator granted to a named agent went to whoever asked first.
`mint` takes the identity it is for and `spend` takes the caller's, which the interception layer
already knows because it is the same identity quarantine and the rate limit now use.

Two decisions inside it. The binding is **optional**, because an operator may legitimately be
approving for a client that has not called yet and has no identity to name — and the answer says
which of the two was minted, with the console defaulting to the single known caller where there is
exactly one, so the wider token is never what you get without asking. And the refusal stays
**undistinguishable from the other three**: a wrong caller reads exactly like an invented token,
because telling a holder of a stolen token which part of it to change is the one thing this
refusal must not do.

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

## What is left

Every finding above is closed. Two things are deliberately *not* done, and they are decisions
rather than omissions — both were already in `docs/notes/mcp-server.md` before this audit and both
survive it:

- **The taint guard.** It exists to stop a value read from the cluster being used as a mutating
  argument, and there is no mutating tool in this tree: under the shipped `readonly=true` it could
  never fire. A guard nobody has seen work is not a control. It lands with the write surface it
  protects.
- **OAuth 2.1.** The bearer token is an interim — a static credential an operator distributes, with
  no issuer, no audience, and revocation by redeployment — and `SPEC-MCP.md` now records that
  distinction rather than implying the specified thing shipped. Real OAuth changes the deployment
  contract and deserves its own review.

The one habit worth carrying forward from this audit: three of the four P0s were already failing in
CI when it started. The suite knew, and the build had been red long enough for that to stop being
information. A finding list is cheaper than the next red build nobody reads.
