<!--
SPDX-License-Identifier: AGPL-3.0-or-later
Copyright (C) 2026 Kafka Explorer Contributors
-->
# The MCP server — plan, and why each piece is shaped the way it is

`SPEC-MCP.md` at the root is the specification, and `SPECAGENT.md` beside it specifies the agent
harness that would put its honesty contracts to the test — not yet built. This note is the
*implementation* record: what
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
- **"Try it" is off by default, because it is an authentication bypass waiting to happen.** The
  endpoint executes the real tool over an application URL, and this application authenticates
  nothing while `SPEC-MCP.md` puts OAuth 2.1 in front of `/mcp`. Leaving it on would mean that
  wiring up that OAuth — the phase 5 work — buys nothing, since the same tools stay reachable one
  path over with no token, mutating ones included once `readonly` is cleared. The console does not
  need it: everything else on both tabs is a read.
- **"Try it" runs the real specification.** `McpToolInvoker` resolves the tool out of the same list
  the transport serves — already wrapped by the interceptor — so the scope check, the ceilings, the
  redaction and the recording all apply. Only the attribution differs: `McpCallOrigin` marks it
  `CONSOLE` through a ThreadLocal, because the interception point is an SDK `BiFunction` this
  application cannot add a parameter to. A "Try it" that bypassed the guard would answer a different
  question from the one the button asks.

## Phase 3 — the four tools nothing else offers

Every other Kafka MCP server answers "where did ORD-1042 go?" with N `consume_messages` calls and
the model's own correlation: thousands of records through a context window, an answer that costs
more than the question, and nothing in the reply saying which topics were never looked at. Phase 3
is the part of this server that has no equivalent, and it is where the module's honesty contracts
stop being an ornament — a trace is *always* partial, so a trace that does not say what it missed
is worse than none.

**`kex_trace_key` collapses four modes into one, because the service already infers three of them.**
The spec named `DOT_PATH`, `JSONPATH` and `XPATH` as separate modes; `StreamFlowService.buildCriteria`
decides between them from the path's own shape (a leading `/` is XPath, `..` or `[?` needs the full
JSONPath engine, anything else rides the streaming walker). Keeping three names would have given the
caller a third parameter to disagree with the path about, and the disagreement would have been
resolved silently in favour of the shape. So the tool takes `mode=FIELD` plus a `path`, and both
`headerName` and `path` are **refused when the mode has no use for them** rather than ignored:
ignoring `path` on `mode=ANY` scans the whole record for the value while the caller believes one
field is being read, and the answer — plausible, wider than asked for — carries nothing that
contradicts them.

**The resume token is server-side, which reverses this module's stateless preference on purpose.**
The UI hands its own prior hits back in the next request precisely so the server keeps no session;
that works for a browser and fails for an agent, whose prior hits are hundreds of records that would
cross the model's context twice to come back truncated. `McpTraceStore` holds them instead, bounded
to 50 entries and 15 minutes. Its one weakness — a token can expire — is one the caller can be told
about, and is: an unknown or expired token is **reported as unknown**, never answered with an empty
second pass, which would claim "nothing more found" about topics that were never read. Unknown and
expired are deliberately the same answer, since neither one changes what the caller must do next.

**A scan that finished is not necessarily exhausted.** `stopReason()` maps the service's `COMPLETE`
to `PARTIAL_FAILURE` when any topic failed or was skipped: `EXHAUSTED` is the single value that
licenses reading an empty result as "does not exist", so a pass that could not read four topics must
not claim it. And a hop that appears to precede the one before it is reported as `clockSkew` in a
sentence — not hidden, not "corrected" — because the record did not travel backwards, the brokers
disagree about the time, and a model given only the numbers reasons about a negative delay.

**`kex_compare_traces` reports deltas, never absolute timestamps**, and carries no resume token.
Two keys produced at different moments have every timestamp different, so comparing those is noise
shaped like signal; and continuing one half of a comparison would leave it half-refreshed with
nothing saying which half. When either pass fell short, a `PARTIAL_COMPARISON` warning says that a
topic reached by only one key may simply not have been scanned for the other — the divergence the
tool exists to find is exactly what a spent budget counterfeits.

**`kex_consumer_lag` carries the verdict, not just the numbers.** The domain already grades this
(`ConsumerGroupLag.Health`) and the grade is what an agent gets wrong: a lag of zero on a group with
no assigned member is not "up to date", it is nothing reading a topic that is not moving. So
`verdict` and a sentence explaining it travel with every group, and the groups are sorted worst
first with `UNKNOWN` at the *top* — a group nobody could read is what an operator most needs to see,
and sorting it last hides it under whatever the cap cut off.

Three consequences of the same invariant, in that one tool:

- **A failed read is a failure, not a topic nobody reads.** `TopicConsumers.available()` false
  becomes `-32043 DEPENDENCY_UNAVAILABLE`; returning zero groups would state that nothing consumes
  the topic on the strength of a call that never answered.
- **`recordLag` and `lagMs` fail independently.** Records come from committed offsets, which every
  broker answers; the age needs the record *at* that offset, which compaction or retention may have
  removed. A known backlog of 40 000 records with an unknowable age is a real state, and the pair
  says so rather than reporting a zero for the half that failed.
- **The age is opt-in.** It costs one partition read per partition per group where the record count
  is a single offsets call for all of them — on a topic with fifty groups, an agent asking "who is
  behind" would have paid for a full diagnosis of every one of them. `includeTimeLag` defaults to
  false and the unmeasured `lagMs` names the parameter that would measure it.

**Two deliberate departures from the spec's tool table, both in `kex_consumer_lag`.** The spec asks
for `topics[]` and per-partition `Measured` values; this takes **one** topic, because the coverage
envelope is what makes the answer readable and a fan-out over topics turns "what did you not read"
into a question with several answers. And the per-partition rows are **opt-in**
(`includePartitions`): a topic with fifty partitions and ten groups is five hundred rows, and the
verdict answers the question in almost every case. They are opt-in rather than dropped because the
summary is exactly where a stuck partition hides — a group blocked on one partition of forty
contributes almost nothing to the total and reads as very slightly behind — so the detail has to be
reachable, and each row carries its own measured-ness: a partition with no commit holds *no
position*, which is not offset zero. When both flags are set the age is read **once** and used for
the total and the rows alike, so the detail cannot contradict the sum it was drawn from.

`kex_analyze_dead_letters` is **not** in this phase. Its pairing-and-verdict rule is 666 lines of
`src/main/webapp/src/pages/deadLetterSupervision.ts` with no Java counterpart, so an MCP tool for it
is not an adapter — it is that logic written a second time, which is the one thing this module's
first rule forbids. It waits for the rule to move into a service, and the phase table says so
rather than letting the omission read as an oversight.

## Phase 4 — the model, the audit, and the KPIs that cite their evidence

Phase 3 answered "where did this go?". Phase 4 answers the three questions that come before and
after it: what is in this cluster, is any of it wrong, and what should be watched.

**`kex_build_join` needed a rule that lived only in TypeScript, and that is why
`DataModelSqlService` exists.** `buildMultiJoinSql` in `dataModelGraph.ts` is the one place the
spanning tree, the alias arbitration and the projection cap are written down. Three ways out were
available and two are wrong: a second implementation under `mcp/` is the thing this module's first
rule forbids, and moving the rule server-side would put the page's join preview — a `useMemo` that
recomputes as the selection changes — behind a round trip, turning a preview that keeps up with the
pointer into one that lags it. The third is what `ConsumerGroupLag.Health` and
`topicConsumers.ts` already do here: **two readings of one deterministic rule, kept in step by a
test suite that runs the same cases on both sides.** `DataModelSqlServiceTest` is that suite, case
for case with `dataModelGraph.test.ts`, so a divergence fails on one side or the other. It is
defensible here and was not for `kex_analyze_dead_letters` because this is graph-and-string work
with no judgement call in it: two readings cannot disagree about what a breadth-first traversal
found, where 666 lines of pairing heuristics would drift on the first ambiguous case.

The rule that survived the port intact is the one that matters: **it refuses rather than inventing
a predicate.** A selection the deduced relations do not connect comes back with `sql: null` and the
unreachable entity named. That is the specific mistake a model makes when handed a list of tables
and asked to join them, and the refusal is the tool's whole value over asking the model directly.
One departure from the TypeScript, deliberate: an entity id the model does not hold is **named**
rather than filtered out. The page cannot produce one — its ids come from the model it is
displaying — but a caller can, and silently joining two of the three tables asked for would answer
a question nobody put.

**Every relation carries its confidence and the sentence behind it**, because `MEDIUM` means the
names agree and nothing else does, and a model told only "there is a relation" writes a join on it
as readily as on a `HIGH`. On the same reading a column named like a foreign key that resolves to
nothing is `referencesUnresolved` — the spec's `?` — rather than being dropped or promoted: "points
at orders" and "is named like something that would point somewhere, and points nowhere we found"
are different facts, and only the first is a relation.

**The audit is two tools because a full run takes minutes.** A tool that blocked on it would hit
the caller's own timeout and return nothing, having spent the whole scan — so `kex_run_audit` hands
back an id and `kex_get_audit` answers with whatever the run has, saying which it is. Three
consequences, all of them the same invariant applied to a long-running read:

- **`started: false` means this call *attached* to a run already in flight**, whose scope is the one
  that run chose. `AuditService` holds one run per process — an agent and an operator share it — so
  without that field a second caller reads the first one's findings as an answer to its own
  question. The scope of the run it attached to is reported, not the one asked for.
- **An unscoped run on a scoped deployment is restricted, not refused**, when exactly one prefix is
  allowed: an unscoped audit would read precisely what the prefixes withhold. With several allowed
  prefixes the tool asks for one instead of picking, since an audit run takes a single prefix and
  choosing would answer about a slice nobody named.
- **`RUNNING` is `TIME_BUDGET` in the envelope, and healthy topics are not listed.** The two
  together are what make an empty `findings` readable: on a `COMPLETED` run over forty topics it is
  good news, and on a `RUNNING` one it is not news at all.

**`kex_suggest_kpis` exists for one refusal: no threshold is invented.** "Suggest KPIs for my Kafka
cluster" is a question a language model answers fluently from nothing — p99 under 200 ms, lag under
1 000, error rate under 1 % — and every number in that answer is a plausible invention about a
cluster it has never read. Here `thresholdBasis` is `Measured`: it names the observation a threshold
would rest on, or it is unmeasured with the reason none does, and in that second case there is no
number to publish. The tool description says so in as many words, because the model reads the
description before the payload. `auditRunId` names the run each proposal rests on, so a caller can
read that run rather than take the KPI on trust; without an audit the coverage stops at
`PARTIAL_FAILURE` rather than `EXHAUSTED`, since a short list because nothing has been measured is a
different answer from a short list because the cluster is simple.

## Phase 5 — the guard pipeline, the kill switch, and a trail that survives

Six settings shipped for four phases accepted and acted on by nothing. Each said so where an
operator would look, which is better than silence and still leaves a control an operator believes is
in force and is not. Five of them are now real.

**`explorer.mcp.tools.allowed` / `.denied` are enforced by absence, not by refusal.** Checking
inside each call would have been the easy fix and the wrong one: a denied tool would then be listed
by `tools/list`, described to the model, chosen by it, and refused — a round trip spent on a surface
that advertises what it will not do. `McpToolSpecificationPostProcessor` filters the specification
list before the transport sees it, so a denied tool is not there at all, exactly as a mutating tool
under `readonly` is not a bean. The deny-list always wins, because the two settings are written by
different people at different times — one in a base configuration, one in an environment overlay —
and the safe resolution of a disagreement between them is the restrictive one. A name in either list
that no tool carries is logged as the typo it is: a deny-list with a typo in it silences nothing
while reading as though it did.

**The kill switch is `McpRuntimeSwitches`, and an incident is its whole reason.** "An agent is
hammering the cluster" and "this tool is returning something it should not" are answered by a
redeploy in minutes at best, and the minutes are the problem. Four levers — lock read-only, switch
one tool off, quarantine an identity, mint an approval — each taking effect on the next call.

Three rules shape it, and each one is a way the feature could have become the problem:

- **Every switch narrows.** Read-only can be turned *on* when the configuration has it off, never
  off when the configuration has it on: the write surface is decided at bean registration, so a
  switch could not open it anyway, and offering a control that appears to and does not is worse than
  not offering it.
- **Every override carries who, when and why — and none of them expires.** That pairing is the
  mitigation for the worst failure mode this feature has: a runtime derogation that outlives the
  incident it answered and becomes the permanent configuration nobody remembers choosing. An expiry
  would be worse, not better — it would restore a wider surface at an arbitrary moment, quietly. So
  they persist until lifted, and the console keeps a banner naming each live one with its age. The
  banner cannot be dismissed; one that could would be dismissed on the first day and the derogation
  would stay.
- **In memory, per process, deliberately not persisted.** A kill switch has to take effect now, and
  one that must first be written somewhere durable can fail to. A restart returns the deployment to
  its configured posture, which is the safe direction: the YAML is the source of truth and an
  override is an exception to it.

**A tool switched off is refused, not removed**, and that is not inconsistency with the deny-list: a
client caches the tool list from its `initialize`, so a tool that vanishes mid-session is one the
model keeps calling and cannot be told about. The refusal is `-32044` naming the operator and the
reason — the only form this can take that the caller can actually read.

**Approval tokens make `approval-required-tools` mean something.** Single use, bound to one tool,
fifteen minutes, unguessable, compared in constant time; each of those is the answer to a way an
approval can be defeated, and the reasons are written out in `McpApprovalStore`. Two decisions worth
knowing: it applies to **any** tool an operator lists rather than only the mutating ones, because a
read is the sensitive gesture on a cluster whose payloads are regulated and hard-coding the list to
the write surface would deny that operator the control; and the refusal never says which of the
three ways it failed — unknown, expired, or minted for another tool — because distinguishing them
tells a caller holding a stolen token which part of it to change, while a caller holding a
legitimate one has the same thing to do in all three cases. The token travels as `_approvalToken`
and is **removed, not masked,** before the call is recorded: a bearer credential in a durable log
outlives the fifteen minutes it was minted for.

**Rate limiting (`-32029`) is a token bucket per identity, refilled continuously.** A fixed window
lets a caller spend the whole allowance in the last second of one minute and the whole allowance
again in the first second of the next, which is twice the configured rate at exactly the moment the
cluster is least able to take it. An operator clicks; a model loops — and a tool that answers "not
found in what was scanned" invites another pass, so the ceilings in `ToolGuard` bound one call and
this bounds the sequence. The refusal names the wait, because a bare "rate limited" teaches a model
to retry immediately, which is the behaviour the limit exists to stop. It is per server instance and
says so: a distributed limiter needs a store this application does not have, and one that silently
allowed N times the rate would be the claim-without-code this module keeps removing.

**The audit topic is written, and the replay reads it back.** Every call *and every refusal* — a
control that blocks silently is a control nobody ever tunes, and the refusals are the half an
incident review actually needs. A failed append never fails the call: it increments
`explorer_mcp_audit_write_errors_total`, and that gauge moving is the signal that the trail has
holes. Refusing the tool instead would turn an unreachable broker into an outage of the whole MCP
surface, and a trail is not worth that; a trail with a counted hole is honest, a surface that goes
down when its trail does is not.

`McpAuditReplayService` seeks by timestamp on the broker's own index rather than scanning from the
beginning, and **reports what it could not reach**, which is the point of it. The scan is bounded, so
a window that returned nothing is either a window in which nothing happened or a window the scan
never reached — opposite conclusions from the same empty list. `scanReachedWindowStart` separates
them, and it is computed from the case retention actually hides: a seek landing exactly on a
partition's first surviving record proves nothing older is left, so whether anything in the window
preceded it cannot be known.

### The console reaches every lever, which it did not at first

The first cut of this phase shipped the banner and the per-tool switch and left four of the five
endpoints reachable only by `curl`: quarantine, the read-only lock, the approval mint and the
replay. That is the same failure the whole phase exists to close — a control an operator cannot use
is a control that does not exist — so each has its place on the screen now, beside the thing it
changes: the lock next to the badge stating the posture, quarantine on the client row, the mint on
the tool whose badge says it needs one, the replay under the live feed that already said the
history lives elsewhere.

**Quarantine is the lever that matters most in an incident**, and it was the one missing: "this
agent is looping" is answered by stopping the identity, not the tool — stop the tool and it calls
the next one.

**Restricting asks for a reason; lifting asks for nothing.** The asymmetry is deliberate. The reason
is what the banner shows and what gets the derogation lifted weeks later, so it is required going
in. Coming out there is nothing left to explain, and a form at that moment is friction on the one
gesture that returns the surface to its configured state.

**The form is a real form, not `prompt()`.** The browser prompt blocks the thread, cannot be styled,
validates nothing, and several browsers remove it outright inside an iframe — an emergency switch
that depends on it is one that does not open on the day of the incident.

**A minted token is shown once and read back nowhere.** It is a bearer credential; an endpoint able
to re-read it would turn a single-use approval into a standing permission for anyone who can reach
this application.

**The replay says what it covered, not only what it found.** `replaySummary` renders the difference
`scanReachedWindowStart` carries, because a bounded scan that comes back empty is either a quiet
window or a window it never reached, and a screen that shows only the empty list lets the reader
pick the reassuring one.

**Three smaller things the screen owed its reader.** A call's `redactedParams`, `correlationId`
and `stopReason` existed only in the CSV — an operator had to export a file to find out why a call
went wrong, which is the last gesture anyone makes, not the first; the row expands now. The tool
descriptions are written for a model, which reads all twenty lines of them, and the table stacked
fifteen of those: the row carries the first sentence and the rest unfolds, cut on the sentence
rather than a character count because a truncation mid-word says "there is more" without teaching
anything. And a denial code is KIP-1318's, which is its merit for an agent trained on that surface
and its failure for a person: `explainDenial` gives the cause in a sentence and **names the setting
that produced it**, which is the only action the card invites. It returns nothing on a code it does
not know — a code this screen has never seen comes from a server newer than itself, and inventing a
meaning would be worse than letting the number speak.

### The server had never been started

Every test in this module built its tool specifications by hand.
`McpServerConfigurationTest` runs an `ApplicationContextRunner` with `AutoConfigurations.of()` —
**empty** — so Spring AI's own scanner had never run in a test, the `toolSpecs` bean the
post-processor exists to post-process had never been built by the framework, and nothing had ever
asserted what `tools/list` would answer. Two hundred unit tests over five phases, and the thing had
never been switched on.

What that hid is a class of failure the suite could not reach. Spring AI derives a JSON schema for
every `@McpTool` method **from its signature**, and a parameter or return type it cannot express is
a startup failure or a silently missing tool — found in production, by the first agent that
connects. These tools take `List<String>` parameters and return a generic `ToolResult<T>` over
records that nest other records and a generic `Measured<T>`; none of it had been put in front of the
scanner.

`McpServerBootTest` starts the application with `explorer.mcp.enabled=true` and asserts the fifteen
tools are registered, each with a description and an input schema, each wrapped by the interceptor,
and that the catalogue the console reads names exactly what the transport serves — two lists that
can drift are two answers to "what does this server offer", and the console's whole purpose is to be
the one an operator can trust. It found nothing broken, which is the outcome to hope for and not one
that could be assumed: the point is that the next signature change cannot break the wiring in
silence.

**It also pins a fact that is easy to misread today.** `readonly=true` withholds the write surface
at bean registration, and that surface is currently *empty* — no `MutatingMcpTools` exists, so
`writeSurfaceOpen()` cannot return true and the console's "hidden by read-only" row never renders on
a real deployment. The module's headline posture is, for now, a claim about an empty set. The test
asserts the fact rather than the mechanism, so when the first write tool lands it is what says
whether it stayed withheld.

### What phase 5 deliberately leaves

**The taint guard is deferred, and its reason is the mirror of every other deferral here.** It
exists to stop a value read from the cluster being used as a mutating argument — and there are no
mutating tools in this tree. Under the shipped `readonly=true` there is nothing it could ever fire
on, so shipping it now would be dead code rather than a control, and a guard that has never had
anything to guard is one nobody has ever seen work. It lands with the write surface it protects.

**OAuth 2.1 is separate**, and not for want of anything to build on: it changes the deployment
contract — a new starter, a filter chain, and a direct contradiction of `SECURITY.md`'s "no
authentication out of the box" — and that deserves its own review rather than riding in behind a
guard-pipeline change.

## Phases

| Phase | Content | State |
|---|---|---|
| 1 — Socle honnête | `Coverage`/`Measured`/`ToolResult`, `McpProperties`, `ToolGuard`, `McpCallRecorder` + metrics, `McpCatalogService`, `McpToolInterceptor`, tools `kex_list_topics` / `kex_describe_topic` / `kex_preview_messages` / `kex_infer_schema` / `kex_sql_query` / `kex_list_tables` | **done** |
| 2 — Écran MCP | `/api/mcp/status`, `/catalog`, `/calls`, `/stats`, `/clients`, `/catalog/client-config`, `/try/{tool}`; the React page with its Catalogue and Supervision tabs | **done** |
| 3 — Différenciation | `kex_trace_key`, `kex_resume_trace`, `kex_compare_traces`, `kex_consumer_lag` | **done** |
| 3b — `kex_analyze_dead_letters` | blocked: the pairing rule lives only in `deadLetterSupervision.ts`; it needs a Java service first | not started |
| 4 — Modélisation | `kex_deduce_data_model`, `kex_build_join`, `kex_run_audit`/`kex_get_audit`, `kex_suggest_kpis` | **done** |
| 5 — Entreprise | per-tool allow/deny enforced by absence, kill switch (read-only lock, per-tool off, quarantine), approval token, rate limit, audit topic + replay | **done** |
| 5b — OAuth 2.1 | separate: it changes the deployment contract and contradicts `SECURITY.md`'s "no authentication" | not started |
| 5c — Taint guard | blocked: nothing to guard until a mutating tool exists | not started |

Phase 2 before phase 3 is the spec's ordering and it is kept: instrumentation added after the
fact is instrumentation that never gets added.

## Off by default

`explorer.mcp.enabled` ships **false**. Turning an agent loose on a cluster is a decision an
operator makes deliberately, and a feature that arrives switched on in an upgrade is not that.
`readonly` ships `true` and stays true unless it is written otherwise.
