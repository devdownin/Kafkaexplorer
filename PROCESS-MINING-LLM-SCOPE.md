# Process Mining & the LLM — scope (2026-08)

The ingestion half of Process Mining has been audited repeatedly and is in good shape: payloads are
digested as they arrive, shapes are deduplicated, budgets are enforced, and a read that could not be
taken now says so instead of coming back as a zero. What has never been reviewed is the half that
*uses* the model — what the prompt carries, what the schema forces, what is recomputed on every
call, and whether the model can see the thing it is being asked about.

This document is that review. Each item is sized and ranked, and the one that dominates the rest is
stated first. The order of the sections is the order the work should be done in, because the later
items get cheaper once the first one lands.

> **Status.** It implemented nothing when it was written. **W1, W2 and W3 have since shipped**, as
> one change — they share a diff, and the first two are meaningless apart. What sections 1 and 2
> describe is therefore the state this work was done *from*; the code now computes the event log
> (`ProcessModelBuilder`, `ProcessModel`) and sends the aggregate plus whole case traces, and
> `sample` is bounded per message. **W4 is closed by a measurement** (the stable prefix is ~648
> tokens, below the 1 024-token minimum a cacheable prefix needs, so there is nothing to cache) and
> **W5 and W8 have shipped** — W8 is what turned the argument below into a measurement, and it
> found a defect in the measured process while being written (see section 6). **W6 and W7 remain
> open.**
>
> **A defect W1–W3 introduced, found by measuring rather than by reading, and fixed.** With the
> aggregate carrying the scope, `messagesAnalysed` counted only the inlined worked examples — so a
> measured six-topic run over 3 000 records reported *"6 of 3,000 analysed"* and the panel blamed a
> prompt budget that was **6.5 % spent** (7 840 characters of 120 000, measured). `TopicCoverage`
> now counts `messagesMeasured` apart from `messagesDetailed`, and the browser names the right
> cause on each path. It is the same rule this document is about, turned on the fix itself: a number
> that means one thing and reads as another.
>
> One thing was found while implementing rather than while reviewing, and it is recorded here
> because it changes a claim in section 3: a resolver for event-time values already existed
> **twice**, byte-equivalent, one copy admitting it in a comment ("mirroring the metric engine").
> It is `EventTime` now, and both callers delegate — the same consolidation `SecureXml` and
> `LogSafe` exist for.

Everything below is derived from the code and the shipped configuration, and each derivation names
the file it comes from. Where a number is arithmetic on the configured caps rather than a
measurement on a live run, it says so — and, where the application already displays the figure,
where to read it.

---

## 1. The model is asked to correlate what it is never shown

This is the root finding. Everything in section 2 follows from it.

`AuditPromptCatalog` — the checklist an operator ticks before an audit — asks for five things, and
four of them are questions *about a case*, not about a message:

| Prompt | What it asks the model to do |
|---|---|
| `ordering` | "vérifie l'ordre des événements **par correlation id**" |
| `orphans` | "identifie les **correlation ids** qui démarrent un flux mais n'atteignent jamais un état terminal" |
| `latency` | "analyse les délais **entre étapes corrélées**" |
| `duplicates` | "même **correlation id**, même clé ou payload identique" |

For any of those to be answerable, the model has to see at least two messages of the same
correlation id, in different topics. It almost never does, and the reason is in
`LlmAnalysisService.appendCommonSections`:

```java
byTopic.forEach((topic, digests) -> sampled.put(topic, evenSample(digests, perTopicLimit)));
```

The sample is drawn **per topic, independently, on offset order**. Whether a given case survives in
two topics' samples at once is therefore an accident — it holds only while every topic carries the
same cases, in the same order, at comparable volume. That is exactly what stops being true the
moment a pipeline does anything interesting: a branch, a filter, a retry, a topic that fans out
three payments per order. `demo.payments.*` and `demo.shipments.*` in this repository's own seed
data are correlated to the orders *by header only* and do not carry the order id in the payload at
all, so on the shipped demo cluster the overlap is nil by construction.

Then the budget narrows it further. With the shipped configuration:

- `process-mining.max-messages-per-topic-in-prompt: 60`
- `process-mining.prompt-char-budget: 120000`
- `process-mining.max-sample-fields: 40`, `max-value-chars: 160`

`appendDigest` writes each digest's `sample` map inline — up to 40 scalar values of up to 160
characters — so **one message can occupy 2–7 KB of prompt on its own**. On a six-topic snapshot the
per-topic share is `120000 / 6 = 20000` characters, which the loop spends in **eight to thirteen
digests**, against the sixty it was configured to inline. Read against a default snapshot depth of
500 messages per topic, roughly **2 % of the records reach the model**, chosen independently per
topic.

That ratio is not a claim needing new instrumentation: it is already on screen. `appendMessages`
returns the per-topic count, it becomes `TopicCoverage.messagesDetailed` beside `messagesRead`, and
`CoverageNotice` renders both. The number has been displayed all along; nothing had drawn the
conclusion from it.

So the model is handed a few dozen unrelated records and asked which correlation ids never reached a
terminal state. It cannot know. What it can do — and what a small model reliably will do — is infer
a plausible pipeline from the **topic names**, which read `demo.orders.1.received`,
`demo.orders.2.validated`, `demo.orders.3.enriched`. The answer looks right, is not observed, and
nothing in the output distinguishes the two.

`blindSpots` was added to the schema for exactly this, and it does not close it: the model cannot
report a blind spot it has no way to detect. Only the caller knows that the sample was drawn per
topic.

---

## 2. The model is asked to do what code does better, and free

Every question in the table above is a **deterministic computation over data the application already
holds**. `PayloadDigest.fields()` carries the value at the mapped correlation-id path, per record,
resolved from `FieldMapping.correlationIdPaths()` and normalised by
`PayloadDigestService.mappedPaths` (`$.order.id` → `order.id`). The timestamp and the status are in
the same map. That is an **event log**: case id, activity, timestamp — the three columns process
mining is built on. Nothing in the backend ever groups by the first one.

The standard shape of the discipline applies directly:

- **Directly-follows graph** — for each case, sort its events by timestamp and count the transitions
  `A → B`. That is the flowchart, computed rather than guessed, with a frequency on every edge.
- **Variants** — the distinct end-to-end paths and how many cases took each. Five variants covering
  95 % of cases and a long tail of forty is *the* finding on most real pipelines, and it is a
  `group by`.
- **Per-transition latency** — p50 / p95 / max per edge, from the timestamps already extracted. The
  `latency` prompt asks the model to eyeball this from a sample of unrelated records.
- **Incomplete cases** — cases whose last activity is not a terminal one, bucketed by where they
  stopped. That is `orphans`, exactly, and it is a set difference.
- **Duplicates and rework** — a repeated `(case, activity)` pair, and self-loops in the graph.
- **Start / end activities, case counts, throughput per bucket** — counting.

None of that needs a model, none of it can be wrong, and all of it fits in **one to three kilobytes
of prompt for a whole cluster**, against the 120 000 characters currently spent on per-message
lines.

The model's job then becomes the thing it is actually good at, and cannot be replaced on: reading an
aggregate and saying what it *means*. Naming the process. Saying that a 4 % variant which skips
validation is a business bypass rather than a bug. Turning a p95 of eleven minutes on one edge into
a hypothesis about a batch job. Writing the Mermaid — from an edge list it was handed, so the
diagram matches the data by construction rather than by luck.

This is not a hypothetical rewrite of the prompt style. **The correct pattern already exists one
file over.** `FieldProfilingService.buildProfilingPrompt` does not inline messages: it aggregates
values *per path* across the sample (`collectValues`, four distinct examples per path) and sends the
structures once. That is why profiling behaves well on small models and the analysis does not. The
analysis prompt is the only one in the tree still built per record.

**W1 — Build the event log and its aggregates in Java; send those. → shipped.** A new pure service
(`ProcessMiningAggregator` or similar) taking `List<PayloadDigest>` plus the `FieldMapping` and
returning a record: cases, variants, directly-follows edges with counts and latency quantiles,
incomplete cases by stopping point, duplicates, per-topic throughput. Pure, so it is unit-testable
with no broker and no model — which is also what makes it the first item: it is the only one here
whose correctness can be pinned by tests rather than judged by reading an answer. The prompt then
carries the aggregate, plus **a handful of complete case traces as evidence** (see W2). Medium
effort, and it retires most of sections 3 and 4 on its own.

**W2 — Sample by case, not by topic. → shipped.** Whatever else happens, the raw examples that go in the prompt
should be *complete traces*: pick N correlation ids and include every event of each, across every
topic, in order. Ten complete cases teach a model more about a process than six hundred unrelated
records, and cost a fraction of the budget. Cheap on its own, and the natural companion to W1 — the
aggregate says what is typical, the traces show what one looks like.

Note what W1 and W2 both require and neither can invent: **a validated field mapping**. Without one
`digest.fields()` is empty and there is no case id, so the aggregate degrades to per-topic
throughput and shape drift. That is the honest degradation and it should be stated in the prompt and
in the coverage, not papered over — a run with no mapping is a run that cannot answer four of the
five audit prompts, and the UI currently offers them all regardless.

---

## 3. What the prompt budget is spent on

Three things, in descending order of waste.

**`sample` dominates and is the least relevant part of the record.** It is defined in
`PayloadDigest` as "other scalar values" — precisely the ones *not* named by the mapping, i.e. not
the case id, not the timestamp, not the status. Forty of them per message, at up to 160 characters,
inlined verbatim. For an analysis whose subject is the flow, that is the incidental payload crowding
out the cases. The analysis path passes `getMaxSampleFields()` (40) into `readSnapshot`; dropping
that to a handful when a mapping is present, or omitting `sample` from `appendDigest` entirely and
leaving structure to the shape block, is a one-line change that multiplies the number of records
reaching the model.

**The stable part of the prompt is not at the front.** `appendCommonSections` writes, in order: mode
header, field mapping, audit focus, **reference flowchart**, shapes, messages. The reference
flowchart changes on every live window, so everything after it changes too. That matters because
prompt caching — Anthropic's explicit `cache_control`, and the automatic prefix caching OpenRouter
and most OpenAI-compatible gateways apply — keys on an **unchanged prefix**. Reordering to
system → mapping → shapes → *then* the volatile blocks costs nothing and is the precondition for
W4.

**Nothing is ever cached.** `AnthropicLlmClient` says so in as many words:

```java
// Cache accounting is not read on this path: nothing here sets a cache breakpoint,
// so a figure would only ever be zero, and a zero nobody can act on is noise.
```

`LlmUsage.cachedInputTokens` exists, is parsed, is displayed — and can only ever be null or zero,
because no client asks for a cache breakpoint. A live session calls the model every
`snapshot-window-timeout-seconds: 30`, with a system prompt, a field mapping and a shape block that
are identical every time. That is the textbook case for caching, and it is currently paid in full on
every window.

**W3 — Stop spending the analysis budget on `sample`. → shipped.** `max-sample-fields-in-prompt`
(6) bounds what `appendDigest` writes, leaving `max-sample-fields` (40) to the digest, where
profiling still needs the breadth. What it drops is counted in `sampleOmitted` rather than
disappearing.

**W4 — Reorder for a stable prefix, then set a cache breakpoint after it. → closed, measured, not
done.** The stable prefix of this prompt is the system prompt, the mode header, the field mapping
and the message-format legend. Measured on a six-topic mapping with all three roles filled in:

```
SYSTEM_PROMPT           1 420 chars
mode + mapping block      701 chars
MESSAGE_FORMAT_LEGEND     472 chars
------------------------------------
stable prefix           2 594 chars  ≈ 648 tokens
```

The minimum cacheable prefix is **1 024 tokens** — on Anthropic's explicit `cache_control` and on
the automatic prefix caching of the OpenAI-compatible gateways alike — and a shorter prefix
**silently does not cache**. So a breakpoint here would buy nothing and cost the machinery to place
it: splitting the user message into blocks, and a `systemOfTextBlockParams` on the Java path.

Everything after that prefix changes by construction — the measured process is recomputed per
window, and the case traces with it — so there is no larger stable region to reach for. The item is
closed rather than left open: it is not a matter of effort, the prompt is the wrong shape for it. It
would reopen if the stable head grew past ~4 000 characters, which a much larger field mapping would
do on its own.

---

## 4. What the answer is forced to contain

`LlmSchemas.processMiningResult()` marks **every field required**, on an anomaly too:

```java
List.of("id", "topic", "type", "severity", "fields", "description",
    "probableCause", "ksqlSuggestion")
```

With `strict: true`, required means the decoder *cannot* omit it. So every anomaly must carry a
probable cause and a SQL statement, whether or not the model has one — which is a schema compelling
a small model to fabricate, in the one field an operator is most likely to copy.

`ksqlSuggestion` is worse than merely forced. The system prompt teaches it by example:

```
"ksqlSuggestion": "CREATE STREAM ..."
```

`CREATE STREAM` is ksqlDB. **This application runs Flink SQL**, and `FlinkSqlService.executeSql`
whitelists SELECT, EXPLAIN and CREATE TABLE — so the statement the prompt asks for is one the
application's own engine refuses. `AnomalyTable.tsx` renders it in a monospace block under
"KSQL / Flink SQL Suggestion", formatted exactly like something you would paste into the editor. It
is the same defect the Help page was rewritten for: examples in a syntax this engine does not speak.

Meanwhile `claude.max-tokens: 4096` caps the *whole* answer — flowchart, comments, hypotheses,
blind spots and every anomaly with its three prose fields. `truncationHint()` exists because that cap
is hit in practice, and it apologises for a truncation the schema helped cause.

**W5 — Loosen the schema and fix the SQL field.** Make `probableCause` and the SQL suggestion
optional; either drop the field or rename it and teach it Flink SQL by example
(`SELECT … FROM … WHERE …`), matching what the editor accepts. Cheap, and it buys output budget back
on every anomaly.

**W6 — Reconsider `max-tokens`, but after W5.** Raising it is the blunt fix and costs money on every
call; W1 and W5 shrink the answer instead. Worth revisiting only with the two of them in.

---

## 5. What is recomputed that could be reused

- **Profiling reads, then the analysis reads again.** `FieldProfilingService.profile` reads 50
  messages per topic and digests them; `analyzeSnapshot` then opens a second read of the same topics.
  Two broker reads and two model calls per pipeline run. The shapes at least are already shared
  through the LRU registry; the records are not.
- **Live mode resends the whole window.** `analyzeLiveDigests` builds the entire prompt every 30
  seconds. With W1 in, the natural live prompt is the *delta*: what the aggregate did since the last
  window, against the reference flowchart. The `NO_CHANGE` protocol already exists for the answer;
  nothing yet exploits it on the question.

**W7 — Reuse the profiling read for the analysis** where the depth allows it, or state plainly that
they are two different questions and let the second read stand. Small, and worth measuring before
doing: the two reads have different depths, so this may be a real trade rather than a free saving.

---

## 6. Nothing measures whether any of this works

There is no evaluation of the prompt. Not one test asserts that a given set of digests still yields
a usable flowchart, and the failure mode is silent by construction: a plausible answer about an
invented pipeline reads exactly like a correct one.

The material for a golden fixture is already in the repository. `setup-demo.sh` seeds a **known**
pipeline — the order flow through `received → validated → enriched`, payments and shipments
correlated by header, deliberate duplicates on `ORD-103`/`ORD-105`, poison records inside
`demo.orders.3.enriched`. The right answer is known because the seeder writes it.

**W8 — An offline eval on the demo dataset.** ~~Digests captured once from the seeded cluster and
committed as a fixture~~ — **shipped, with one departure from the plan and one finding.**

The fixture holds **records, not digests**: a digest is what this application computes, so
committing one would let the fixture and the digester drift together and agree with each other
about a payload neither had read. And it is not *captured* — a capture is a snapshot nobody can
regenerate without a broker, and it rots in silence. It is written from `setup-demo.sh` and
**checked against it** by `docs/check-eval-fixture.py`: every topic, every order id, every
redelivery and the two corrupt payloads verbatim, plus the assertion that the payments and
shipments still carry no order id in their bodies. A fixture that has drifted from the dataset it
names does not fail — it evaluates the wrong thing, confidently.

The two halves are as recommended. `ProcessModelEvalTest` asserts the aggregate exactly and runs in
`mvn verify` (8 cases: the six seeded orders as cases, an edge per consecutive pair of the
pipeline, the 3 → 4 hop as the slowest because `STEP_PAUSE` makes it 3×, ORD-102 ending at
validation, the redeliveries as a repeated step, the header-correlated topics staying outside the
log, and the clock reported as produce time). `LlmAnalysisEvalTest` is the loose half, tagged
`llm-eval`, excluded by surefire *and* by `verify-offline.sh`, run with `./mvnw test -P llm-eval`,
and it **skips rather than fails** when no provider is configured — a test that goes red for want
of an API key is a test people learn to ignore.

> **What it found, which is the whole argument for having it.** `setup-demo.sh` plants two
> truncated records inside `demo.orders.3.enriched`. A streaming parser reads the fields it reaches
> before the payload breaks off, and `id` is the first of them — so each corrupt record arrived
> carrying a correlation id and **became a one-event case that *ended* at enrichment**. The
> measurement reported a pipeline stalling at its third stage where a producer had written two bad
> records: two findings, two different places to go, and the wrong one was on screen. `ORD-666` was
> also nominated as a spotlight case and inlined into the prompt as a worked example. A record whose
> payload broke off is now outside the log, and the exclusion is counted and named in the model's
> notes rather than done in silence — dropping records without saying so is the mirror defect of
> counting them. The audit remains where a corrupt payload is a finding in its own right.

One thing the fixture makes explicit that nothing had stated: **on this dataset the clock is the
broker's**. The seeder stamps every step of one order with the same `event_time`, so a mapped
business timestamp would tie across all six hops; the hop timing comes from `DEMO_HOP_DELAY`, a real
pause between produce calls. `TimeSource.RECORD_TIMESTAMP` is what reports that, and the eval
asserts it is reported rather than passed off as event time.

---

## Recommendation

**W1 + W2 + W3 shipped together, as one change**, which is how they were recommended: they share a
diff, they are pinned by the same tests, and the first two are meaningless apart. The analysis went
from "guess the process from 2 % of the records" to "interpret a process that was measured".

What remains: **W6** and **W7** are worth measuring before doing. **W4** was closed by a
measurement (the stable prefix is ~648 tokens, below the 1 024-token minimum a cacheable prefix
needs), **W5** shipped, and **W8** shipped and earned its keep — writing it found the
truncated-record defect above, which no test written from the code would have thought to look for.
It is also what can now settle the bilingual-prompt question below: the harness exists, only the
runs are missing.

### What shipping W1–W3 actually changed

- `ProcessModelBuilder` + `ProcessModel` — the event log's aggregate: directly-follows edges with
  p50/p95/max latency, variants with case counts, start/end distributions, repeated
  `(case, activity)` pairs. Pure, deterministic, and pinned by 18 cases rather than judged by
  reading an answer.
- The prompt opens with **PROCESSUS MESURÉ**, computed over *every* record read, and the
  instructions tell the model to draw the flowchart from the listed transitions rather than from
  the topic names.
- **Whole case traces** replace the per-topic sample, one per selected variant — and the variant
  selection takes from **both ends** of the frequency distribution, because a top-N cut drops
  exactly the deviation an audit is looking for. A topic no example passes through is named, so its
  silence is not read as a finding.
- With no field mapping the section says so and **forbids the inference** instead of falling
  silent, which is the failure mode this whole document is about: a prompt that merely omits the
  flows is one the model fills in for itself.
- Three things the measurement refuses to do, each recorded in the code: it does not decide which
  activity is terminal (the end distribution is reported, the reading is the model's), it does not
  hide the window boundary (a snapshot manufactures incomplete cases at both ends), and it does not
  report "no process" when it means "no case id".

## Constaté, non traité

Recorded rather than fixed, because each is a decision rather than a defect:

- ~~**The measured process is computed and only the model sees it.**~~ **Fixed.** `ProcessModel`
  travels on `ProcessMiningResult` and `ProcessModelPanel` renders it — on a failed analysis and
  with no LLM configured at all, since the transitions, the variants and the latencies are counting
  and only their reading needed a model. It is then **read a second time, by the Metrics page**:
  `pages/processModelEvidence.ts` keeps the measurement the way `flowChains.ts` keeps a trace, and
  `MetricSuggestionService` derives a hop-latency KPI whose thresholds are 2×/4× a **p95 over every
  case in the window** — where the audit had one average over a flow reconstructed from topic names.
  That reversed the panel's dedupe precedence, which is stated in `CLAUDE.md` rather than left to be
  discovered: the measured process now wins over the audit on a hop both describe.
- **The prompts are bilingual.** The system prompt is English, the user prompt's headings and
  instructions are French, the enums are English. Small models are measurably worse at holding a
  format across a language switch, and this application is routinely pointed at a 3B model. Nobody
  had measured it *here*, so it stayed a suspicion — and W8 is what would settle it.
  **The first half of that experiment now exists**: `LlmAnalysisEvalTest.theShippedPromptHoldsItsFormat`
  runs the shipped prompt N times (`-Dllm.eval.runs`, default 3) against the configured model and
  reports how often it came back usable, distinguishing a parse failure from an answer that
  parsed and carried no flowchart. An English variant is deliberately **not** shipped to compare
  against: a second production prompt is a second surface to keep in step for ever, built on a
  belief, and if the prompt as it stands holds the format every time on the model under test then
  the suspicion is answered for that model and nothing needs building. Only a run that shows
  failures justifies the variant — and it would then be justified by a number. Record the rate
  here when someone runs it; the suspicion stands until then, neither confirmed nor dismissed.
- **`temperature` is hardcoded to `0.0`** in both plain-HTTP clients. That is the right default for
  a structured extraction and it is not configurable; a deployment wanting variety in the prose has
  no way to ask. Left alone deliberately — determinism is worth more here.
- **The audit prompts are free text appended to the prompt** (`auditFocus`). With W1 in, several of
  them stop being prompts at all and become flags on the aggregator. That is a bigger rewrite of
  `AuditPromptCatalog` than this document proposes, and it should wait until the aggregate exists.
- **Nothing bounds how many anomalies the model may return.** In practice `max-tokens` does it, by
  truncating mid-object. A schema `maxItems` would refuse rather than truncate, at the cost of
  narrowing the decoder — the trade `LlmSchemas` deliberately declines everywhere else.
