# Audit trail (2026-07 onwards) — what each report covers

Extracted from `CLAUDE.md`. The reports themselves are the root `*-AUDIT.md` /
`*-SCOPE.md` files; this says what each one concluded and what it left open.


`AUDIT-FEATURE-REVIEW.md` is a later, narrower review of the **Cluster Audit feature itself**
(`AuditService`, `AuditController`, `NamingConventionService`, `MessageFieldExtractorService`,
`Audit.tsx`) — bugs B1–B9, optimisations O1–O3, ergonomics E1–E8 and a second lot S1–S3 (graded
severity, one run at a time, consistent `totalMessages`), all fixed, plus a "constaté, non traité"
section listing what was deliberately left open (the write-only `internal.audit.history` topic, no
cancellation of a running audit, duplicates scanned from EARLIEST, no global time budget, the
premature-empty-poll behaviour of `KafkaAdminService.getEarliestRecords`).

**Consumer-group handling** was reviewed the same way — the groups the app creates on the user's
cluster and those it reads to answer "who consumes this topic": `getTopicConsumers`,
`ExplorerConsumerGroups`, `ConsumerGroupLag` / `TopicConsumers`, `ConsumerLagMetrics`,
`AuditService.consumerLagIssues`, the Consumers panel and the Cluster page. Bugs B1–B10 and one
optimisation O1, all fixed here. One defect recurs throughout it, and it is the reason to read the
`KafkaAdminService` / `ConsumerLagMetrics` notes above before touching either: a measurement that
could not be taken came back as zero, and zero was rendered as an answer — zero members, zero lag,
zero groups — which became a *critical* audit finding, a "caught up" Prometheus gauge, and a
confident sentence in the UI. (The report itself, `CONSUMER-GROUPS-AUDIT.md`, was deleted from the
tree in d643f23; its conclusions are the paragraphs in this file, not a separate document.)

`PROCESS-MINING-LLM-SCOPE.md` reviews the half of Process Mining that **uses** the model, the
ingestion half having been audited several times over and the prompt never. It implements nothing:
every item is sized and ranked, and one dominates. The analysis prompt samples messages *per topic,
independently* (`LlmAnalysisService.appendCommonSections`), while four of the five audit prompts ask
questions about a **case** — ordering, orphans, latency, duplicates are all "per correlation id" —
so the model is asked to correlate records it was never shown together, and what it can still do is
infer a pipeline from the topic names. The budget narrows it further: `sample` is forty scalars of
up to 160 characters *per message*, inlined verbatim, and it is by definition the values the mapping
did **not** name — so roughly 2 % of a snapshot's records reach the model, a ratio the coverage
panel has been displaying all along (`messagesDetailed` against `messagesRead`) without anyone
drawing the conclusion. The recommendation is to compute the event log in Java — `digest.fields()`
already carries case id, timestamp and status per record — and send the directly-follows graph,
the variants and the per-edge latencies, leaving the model the interpretation. The pattern already
exists one file over: `FieldProfilingService` aggregates per path instead of inlining per record,
which is why profiling behaves on small models and the analysis does not. Read it before touching
`LlmAnalysisService`, `LlmSchemas` or `AuditPromptCatalog`.

`PROCESS-MINING-LLM-CALLS-AUDIT.md` is its sibling on the other axis: the same feature, but the
**calls** rather than the prompt, and every provider **except OpenRouter** — `ANTHROPIC`,
`OPENAI_COMPATIBLE`, `OLLAMA`, `SPECTRA`. It exists because that is where the gap is: the routing
policy, the per-model schema latch, the model catalogue, the key-credit read and the
relayed-upstream diagnosis are all OpenRouter's, and so is most of `LlmStructuredOutputTest` — what
is left over is the path every deployment that keeps inference in-house takes, which is what two
bundled stacks and two setup scripts configure. Twelve items ranked, nothing implemented. Three
dominate. A **400 that has nothing to do with the schema** still marks a model schema-incapable for
the client's lifetime, because `rememberSchemaRefusal` is called *before* the unconstrained retry
that would test the conclusion (`max_tokens` and `temperature: 0.0` are both refused with a 400 by
current OpenAI reasoning models, and this client always sends them) — and `LlmClientProvider`
fingerprints provider, base URL and key, so it survives every Settings change but those three.
**Test LLM gives up at 90 s** on the Process Mining page while `compose/ollama.yml` and
`compose/spectra-hub.yml` both configure the server to wait **300**, so the button reports "could
not be reached" about an endpoint that was answering, on the two stacks where nothing else
diagnoses anything; its twin on the Settings page has no timeout at all. And **the bundled Ollama
stack runs unconstrained**: `compose/ollama.yml`, `setup-llm.sh`, `setup-llm.ps1` and Option C of
`docs/LLM-PROVIDERS.md` all set `OPENAI_COMPATIBLE`, which `structured-output: AUTO` deliberately
leaves alone, while `docs/DOCKERHUB.md` tells operators to set `OLLAMA` — a value nothing in the
tree actually sets. **Eight of the twelve have shipped**, in two changes. The latch now records only what the
retry proves; `pages/llmTimeout.ts` derives both Test waits from the budget `GET /api/config`
publishes, floored at the previous 90 s so nothing waits less than before; every shipped artefact
names `OLLAMA`, keeping `OPENAI_COMPATIBLE` where it is the right answer (vLLM, LM Studio, a
gateway whose behaviour is not established); **`ClaudeConfig.configurationProblem()`** answers "can
this deployment call a model?" — key *and* address, the second of which was checked nowhere, so an
`OPENAI_COMPATIBLE` deployment naming no base URL read its topics and then failed inside the HTTP
client with `URI with undefined scheme` — consulted by both services, by `test-llm` and by the
Process Mining banner, with a typed non-URL refused at save time; the **Anthropic path** applies
the configured timeout, pins `temperature` to 0.0, shares `remedyFor`, reports through
`SqlErrorClassifier.explain` and gets the one-retry schema degrade its sibling has, with
`AnthropicLlmClientTest` as the first test that class has ever had; remote response bodies go
through `LogSafe.text` before reaching the log; and the analysis half reports `explain(e)` where it
reported a `getMessage()` that is null for an NPE. The refusal memory moved to
**`SchemaRefusalMemory`** on the way — two copies of "which models cannot be constrained" is how
one comes to latch on a status the other does not.
**The last four have shipped too**, and three of them turned on a measurement rather than an
argument. **L8**: a redirect fell into "everything else is transient", so a 308 took all three
attempts and came back as `status 308: ` with an empty body — a permanent misconfiguration reported
as a passing one. The audit's first suggestion was `followRedirects(NORMAL)`, and that was
*measured before being taken*: the JDK converts a POST to a GET on 301 and 302 and **drops the
body** (`method=GET bodyLen=0`), so the prompt would leave silently; only 307 and 308 preserve it.
A 3xx is therefore refused, naming the `Location` to put in `claude.base-url` — an endpoint that
redirects every call is configuration to fix once, not a round trip to pay for ever.
**L6**: `LlmClient` is `AutoCloseable` (narrowed to throw nothing — a client being retired must not
fail the save that retired it), the replaced one is closed after the replacement is published, and
`@PreDestroy` releases the last. It is `HttpClient.shutdown()` and not `close()`: `close()` blocks
until every in-flight exchange ends, and an exchange here is a model generating — up to the 300 s
the bundled local-inference stacks configure — landing on the thread that saved the Settings page.
`requestTimeoutSeconds` joins the fingerprint, since it is *both* read per call and baked in as the
connect timeout, so raising it moved one half only. **L4**: `ClaudeConfig.namesTheModel()` is false
for SPECTRA, which serves whichever model it is configured with and ignores the field — a
deployment moved off the shipped OpenRouter default kept `openai/gpt-4o-mini` in it, and that stale
slug was reported as the model answering on every window; `LlmUsage.model` is null there and
`describeRuntimeModel` renders the absence as *model chosen by the server*. **L9** is the one the
audit called out as silent: `LlmResponse.schemaSent` records what the client **sent** (not what was
configured — `AUTO` declines for an unknown provider, and the per-model latch declines after a
refusal), and `LlmAnswerSignals` turns two already-parsed facts into coverage warnings. A schema
that travelled and an answer that still needed repairing means the endpoint accepted the field and
ignored it — a constrained decoder cannot emit the reasoning block or fence that was stripped —
which is `ACCEPTED_UNCONSTRAINED` for every provider that publishes no catalogue. And a
`prompt_tokens` under **half** an optimistic four-characters-per-token floor is a prompt truncated
to fit a window, which is Ollama's documented behaviour and logged by it at debug, i.e. nowhere.
Both are **warnings, not verdicts** — the ratio is an estimate and this application does not own
the tokeniser — and both are logged as well as carried, because the live path reports its scope
through `WINDOW_STATS` rather than a coverage record and a live session reasoning on half its
prompt has no other symptom. Read the audit before touching `AnthropicLlmClient`,
`SpectraLlmClient`, `LlmHttpSupport` or `LlmClientProvider`; what it says about those files is
still the reason they look as they do.

**The two metric templates that compare the results of two queries** — `TOPIC_COUNT_DELTA` and
`TOPIC_TRANSIT_LATENCY`, which are also the two the KPI suggestion panel proposes most — are
reviewed in `METRICS-TWO-QUERY-AUDIT.md`, twelve items ranked, of which the first three have
shipped. What they were is worth knowing before touching either compute method, because each fix
is load-bearing and none of it is obvious from the code that remains.

**`BOUNDED_HINT` bounded nothing, and the option that would have is not available here.** Its
javadoc described `scan.bounded.mode` ("reads all data that exists at query start, then
terminates") while the constant wrote `scan.startup.mode`, which says where a scan *begins*; the
environment is `inStreamingMode()`, so the source never ended, and the option merely restated what
`DdlGeneratorService` already writes into every generated table. The bounded option was added, with
a degrade-once fallback standing in for an experiment nobody had run — and then the experiment ran
(`KafkaClusterIntegrationTest`, in CI) and **`flink-connector-kafka:5.0.0-2.2` refuses it**:
*"Unsupported options found for 'kafka'. Unsupported options: scan.bounded.mode,
scan.bounded.specific-offsets, scan.bounded.timestamp-millis"*. Sending it was worse than not:
`FlinkSqlService` reads that refusal as an **engine** failure, so it falls back to the direct reader
and returns rows with **no error**, which means the latch could never fire on it and three such
queries would trip the process-wide circuit breaker that takes the planner out for every other
screen. The hint carries the startup mode alone now; **what actually removed the templates'
dependence on an unbounded planner scan is the direct-read routing below**, and the integration test
asserts the current answer so a connector bump that changes it fails and says which day that was.

**A streaming `COUNT(*)` is a retract changelog, and the value is its *last* row.** The collector
drops `RowKind` and `extractPrimaryMetricValue` kept the first, which is `+I(1)`, so above roughly
five thousand records a `PERCENT_GAP` silent-drop alarm published `0.0` — no loss, on the alarm
whose purpose is to report loss, and precisely on the topics worth alarming about. Three things
together fix it and one alone would only have moved it: the last numeric row (the final aggregate
of a complete changelog, and the only row of a single-row direct read, so one rule serves both
engines); a result that **filled its row budget** refused rather than read as a total; and the
generated shape asked of the direct reader by name, which answers a count with one row and no
changelog at all. That reader has a ceiling of its own — `AGGREGATE_SCAN_RECORDS`, 100 000 — and
now **says so in the result's warnings**, because two counts that both stopped there differ by
nothing: a side that hit it is a floor, and the comparison is refused rather than published as
"no gap".

**A latency now reads the recent end** (`DEFAULT_LATENCY_READ_MODE`), where it read the oldest
records the row cap allowed and therefore never moved again on a topic older than that cap — the
rule `explorer.audit-duplicate-scan-from` already states for the audit's duplicate scan.
`earliest-offset` restores the old behaviour and the form warns when it is chosen. That default
only means anything because `readMode` stopped being a knob that does nothing: it is honoured by
the **direct reader alone**, so the template asks for that reader by name
(`QueryRequest.directRead`, `MetricService.isSingleTableRead`, which fails closed — a join, a
subquery or a table list goes to the planner) rather than letting the planner answer a question it
has no syntax for. There is no scan option for "the most recent N records": a Kafka scan starting
at `latest-offset` and bounded at `latest-offset` reads nothing.

Three smaller rules came with them. `maxRowsPerSide`, `timeoutMs` and `readMode` decide what the
metric measures and were reachable only by a hand-written POST — they are on the form and refused
at **save** time, like `CONSUMER_TIME_LAG`'s `aggregation` three lines above them, instead of
throwing from inside the refresh loop once every thirty seconds. `QueryResult.warnings` is read at
last, on the path whose output feeds an alert (and an aggregate on the direct reader had been
dropping its `WHERE` caveats outright, where the non-aggregate branch kept them). And every
refusal names the side, the read it came from and what to use instead: "the right query counts
zero" now says that `LEFT_MINUS_RIGHT` reports that as a number.

**Four more shipped after them, and each is a measurement the metric was hiding.** *The right side
is counted first* — two counts cannot be taken at one instant, so the arithmetic leans and the only
choice is which way: every operation here grows with the left side, so reading it **last** lets the
interval's traffic inflate the numerator and a gap that survives is real, where the previous order
let the same traffic inflate the denominator and hide the loss the metric exists to report. It is
`KafkaAdminService`' own rule (committed offsets first, log end offsets last) and `readGapMs` says
how much room the interval left; `ABS_DIFF` is symmetric, so its note says the error can go either
way rather than claiming a guarantee. *The latency reports what it could not pair*: `matchRate`,
`unmatchedTargetCount` and `outOfOrderCount`, the rate **exported as a series of its own**
(`explorer_metric_correlation_match_rate`) because a figure that lives only in a summary nobody
alerts on cannot correct the figure that is alerted on — an unmatched source contributes nothing to
the average, so the metric *improves* as the pipeline breaks. *A distribution records each
observation once*: rows are ordered by event time and carry a reserved `__observed_at` column,
excluded from the tags and the label key exactly as `metric_value` is (without which a timestamp
becomes a label and mints one series per observation), and the dedup keys on that watermark rather
than on position — which the sliding window D3 introduced had broken outright, freezing the
distribution after its first cycle, and which was biased before that by rows ordered on the match
key. And *a successful refresh dates itself*
(`explorer_metric_last_success_timestamp_seconds{metric_id}`, set only on a cycle that produced a
value): the value still freezes on a failure, deliberately, but a frozen gauge and a fresh one are
no longer indistinguishable — the alert is `value > N and time() - …last_success… < 120`, the same
series and the same reasoning as `ConsumerLagMetrics`. All of it reaches the operator as well as
Prometheus: `lastSummary` was computed, persisted and rendered nowhere outside the preview modal,
so a metric in service said nothing about its own scope; it is a chip row on the card now
(`pages/metricScope.ts`, pure and tested), with the match rate shown even at 100 % on the rule the
coverage notice already follows — an indicator seen only on bad news is one people stop reading.

Two more followed and closed the document's own last two loose ends. **`operation` is refused at
save time** like the three scan parameters beside it, against a set named once that the compute
switch's error message reads too — it was the last of this template's parameters that could be
accepted by the API and then throw from inside the refresh loop every thirty seconds. And **the two
assertions the audit asked for exist**, in `KafkaClusterIntegrationTest`: the option D1 rests on is
now measured against a real broker rather than read off a page (see the note under **Testing**).

**And two changes that are not defect fixes at all**, each dissolving items the defect list could
only mitigate. **A whole-topic count is metadata, not a scan**: `COUNT(*)` over a topic is
`endOffsets − beginningOffsets`, which `KafkaAdminService.getTopicsSize` already answers *for both
topics in one call*, where the engine was downloading and parsing up to 100 000 records per side
every thirty seconds. `countBy: OFFSETS` — and `AUTO`, which picks it when the metric names both
topics and neither query is anything but a plain whole-topic count — reads no record at all. That
removes the 100 000-record ceiling (so a topic of any size is countable, and D2's "two floors
compared read as no gap" refusal now names the way out), and it removes **D4** rather than leaning
it: both counts come out of the same pair of `listOffsets` responses, so `readGapMs` is `0` because
it *is* zero. The cost is stated on the card and in the summary — this counts **offsets produced,
not records present**, so a transaction marker counts and a compacted record still counts, the same
distinction `getTopicActivity` draws for the dashboard's sparkline — and a query carrying a `WHERE`
cannot be answered this way, which `AUTO` does not pretend otherwise. **And a lifetime total stops
being able to fire**: on two topics running for months, a total outage that started an hour ago is
a fraction of a percent of the totals, under every threshold anyone would set — the metric was least
sensitive exactly when it mattered most. `window: SINCE_LAST_REFRESH` compares what each side
produced since the previous cycle; its first refresh publishes nothing and says why, a count that
went backwards is refused and the baseline re-established, and **a preview never writes a baseline**
(it would leave the running metric subtracting from an instant nobody measured). The suggestion
panel proposes both on every gap card it builds.

**The worst defect of the family was found while doing that**, and it had been there all along:
`left_value` and `right_value` were ordinary row columns, and every non-`metric_value` column
becomes a **Prometheus label**. Both move at every refresh of a live topic, so the label set changed
at every scrape and each time series carried exactly one data point — the metric could not be
graphed or alerted on at all, which is the only thing it exists for. Nothing said so, because the
registry stayed small: `pruneStaleSeries` deregistered the previous series each cycle, the tidy
version of the same defect. The reserved-column rule is a prefix (`__`) now rather than a list of
two names, so a measurement that belongs in the row and not in the label set says so by its name.

**Five more improvements followed, and four of them are about what the metric *means* rather than
what it costs.** *A row cap is not a window, and on two topics it is not even one window*:
`maxRowsPerSide` reads ten thousand records, which is an hour of a slow source and four minutes of a
busy target, so the pairs that survived were the ones whose two halves fell in the overlap — and
what that cost was not the average, computed over real pairs, but the **match rate beside it**,
depressed by the misalignment exactly as by a genuine downstream loss, which sends an operator
somewhere else entirely. `windowMs` reads both sides from the **same instant**, computed once rather
than resolved per read (`KafkaAdminService.getRecordsSinceTimestamp`, the instant form beside the
duration one), travelling as a third read mode — `since:<epochMillis>`, `FlinkSqlService.sinceReadMode` —
because that string already carries direct-reader-only meaning and the alternative was a field on
`QueryRequest` for a concept the planner cannot express. Which is why a window on a side the planner
would answer is **refused at save time, naming the side**: honoured on one side and ignored on the
other is worse than absent, the summary claiming one stretch of time while the reads covered two.
What a window cannot avoid is stated rather than corrected — a source produced near its end has its
target *after* it, outside both reads, so the trailing edge understates the rate by about one hop's
worth of traffic, the same thing `ProcessModelBuilder` says about the cases its own window cuts in
half. *The p95 was computed, put in the summary and alerted on by nobody*: an average holds still
while the worst decile doubles, which is the case the template exists to catch, so
`explorer_metric_correlation_latency_p95_ms` publishes it — and **only for the types that carry no
quantiles of their own**, a `SUMMARY` already publishing `explorer_metric_summary{quantile="0.95"}`.
*Total loss was the one state it could not express*: `PERCENT_GAP` divides by the right side, so
"left > 0, right = 0" — everything produced, nothing arrived — was refused as a division by zero and
published nothing, the most alarming reading staying silent while the alert fired happily at 3 %. It
reports **100**, and that is a definition for the case rather than the formula's limit (which is
infinity): 100 is the number a threshold is set against. Both sides at zero is not a loss and reads
0; `RATIO` stays refused. *And two counts over one topic came out of two full reads* — two
`COUNT(*)` under different `WHERE` clauses, which is what a same-topic gap is, each parsing up to
`AGGREGATE_SCAN_RECORDS` records thirty seconds apart, the per-cycle memoization keying on the SQL
and never bringing them together. `FlinkSqlService.executeSqlPair` runs them as a pair over a slot
the direct reader fills and reuses, deciding on **what the two reads turn out to be** (same topic,
same read mode, both aggregates) rather than on how the SQL looks: aggregates only, since their
fetch size is that constant whatever the statement says, while a projection stops early at its own
row limit and would leave a partial list behind for the other side. The gain is not only the read —
the two counts then describe the **same instant**, which is D4 for that case, and the summary says
`sharedScan` rather than leaving the card to infer it from `readGapMs` being zero: two separate
reads can land in one millisecond, and "these describe one instant" is a claim about how they were
taken, not a reading of the number.

**And the guard against an offset count that could not be taken was written but dead.** `countByOffsets` refuses a topic that does not exist — `listTopics` first, because an unknown name and an empty topic are indistinguishable once either has become a count. The *other* way to have no number is a topic that exists whose offsets did not come back, and the branch for it (`left == null || right == null`) could never fire: `getTopicsSize` pre-seeds every requested name at `0` and swallows the failure, so the map always carries a key for every name it was asked about. Both sides then came back zero and `PERCENT_GAP` published **`0.0` — "nothing is being lost"**, from the metric whose entire job is to report loss, on any broker blip. It reads `getTopicRecordCounts` now — the same offsets read omitting what it could not measure — which is what makes that guard live, and the refusal names the side that went unmeasured, since "we could not read this topic" and "the broker is gone" send an operator to two different places.

**D10 stays open, and half of it closed without any measurement.** It is the cost of a two-query
metric on a single-threaded refresh loop — a property of the design rather than a defect in it.
`refreshIntervalMs` is now a metric's own cadence: every metric was recomputed on every tick, which
is right for a gauge over a cheap query and wrong for a template that reads two topics, and it can
only *slow* one down since the loop's tick is the floor. Skipping touches no state — the gauge keeps
the value it was last measured at, which is correct, and `explorer_metric_last_success_timestamp_seconds`
is what dates it — and an explicit "Refresh now" ignores the interval, a gesture never being a
cadence to be rationed. `explorer_metrics_refresh_duration_seconds` publishes what a cycle cost,
which was the one thing about the loop nobody could see: it is single threaded by design, so a cycle
outlasting its tick does not pile up threads but runs back to back, and the only symptom is a broker
doing more work than anyone asked for (said once per process, naming the two ways out). More threads
would be the wrong answer — the meter state assumes one writer. **The measurement is still untaken
and still the thing that settles the rest**: the wall time of one `refreshMetrics()` over a handful
of template metrics against the demo cluster, which that gauge now makes a reading rather than an
experiment somebody has to set up.

`SQL-EDITOR-AUDIT.md` is the review of the **SQL editor** (`QueryWorkbench.tsx` and its pure modules,
plus `QueryController` / `SqlQueryValidator` / `FlinkSqlService.executeSync`) along the four axes it
was asked for — reliability, ergonomics, optimisation, UI quality. All findings are fixed on this
codebase; the report also carries a "constaté, non traité" section (the absence of a mobile
layout, and two statements of identical text being indistinguishable to `resolveOrigin`).

`MOBILE-LAYOUT-SCOPE.md` scopes the one item that audit left open — the absence of a mobile
layout — with measurements taken by `docs/screenshots/layout-probe.mjs`, the product decision the
work depends on, and sized work items for each answer. **The product question is answered — the
application is not intended for phones** — which is the branch the document itself scoped: W0, W2,
W6 and W7 shipped (Option C plus the items that are right under every answer), **W1, W3, W4 and W8
are closed for want of a user, and W5 shipped** — tap targets under 24 × 24 are a WCAG 2.5.8 item
that mobile merely exposed, so it binds on a desktop-only product exactly as before. It went the
way `Checkbox` did: `layout-probe.mjs --detail` now groups undersized targets **by control**,
because a count is what hides that "genuinely varied" is usually one control repeated — a toggle
switch hand-written four times at 36 × 20 (now `components/ui/Switch`), the SQL editor's Preview
DDL button at 14 × 24 twenty-eight times over, the Dashboard's per-row Explore link at 19 × 19, and
`HelpTip` at 15 × 15. Measured: `dashboard` 32 → 5, `sql-editor` 67 → 39, and `TARGET_BUDGET`
lowered to match, so it cannot drift back — which it promptly proved by refusing the change that
made the Topic Explorer's table the default view, whose sortable headers (five at 17 px, the
partition one at 7 × 17) took that page 7 → 8. Fixed rather than budgeted, since a header is one
row and not every record; the page now measures 3. What is left is *text* in dense rows — sidebar topic
names, table links, sort headers — where 24 px means raising the row pitch of every dense view,
which is an information-density decision rather than an accessibility fix; that is recorded as a
decision, not as a claim of compliance. The measurements and the options are kept
in the document rather than deleted: the answer is recorded so the next person to open a 390 px
window on `/query` finds what was decided instead of re-deriving it, and W1 is named there as the
item to reopen if a narrow *desktop* window — `lg` is 1024 px, so half a 1440p screen falls under it
without being a phone — turns out to be painful in practice. What is load-bearing:

- **Choosing topics lives in `topicSelection.ts`**, shared by Stream Flow, Data Model and Process
  Mining: `addTopicEntries` merges a typed name, a pasted list or an expanded pattern into an
  existing selection, and `describeTopicEntry` states what did *not* go in — an unmatched pattern
  and a reached cap are two different ways of leaving with less than you asked for. The cap is a
  **parameter**, which is the only thing that differed between the screens: Data Model passes its
  endpoint's 30, Process Mining passes none because what bounds a profiling run is the prompt
  budget applied server-side over all topics, not a number of topics. The two primitives stay in
  `pages/streamFlowLogic.ts` where they are defined and tested.
- **The threshold itself lives in `breakpoints.ts`** (`DESKTOP_QUERY`, plus `useIsDesktop` for a
  screen whose *structure* changes rather than its ornament). `Layout` already carried the rule that
  the `lg:` classes describe one threshold and must move together; a second page hardcoding
  `'(min-width: 1024px)'` is exactly how that rule gets lost, so both import it.
- **The shell's "desktop" threshold is `lg`, not `md`** (imported by `components/Layout.tsx`,
  with matching `lg:` classes in `Sidebar` and `Header` — they describe one threshold and must move
  together). At 768 px the navigation used to stop being an off-canvas drawer and take 256 px in
  the flow while the SQL editor's schema browser kept its fixed 288 px, so Monaco fell from 64 px
  at 640 to **5 px** at 768: a tablet in landscape was worse off than a phone. Nobody chose that;
  two independent width decisions met. The measured effect of the move is 768 → 192 px and
  900 → 324 px. Below 640 the editor is still five pixels wide — that is the 288 px browser, W1,
  untouched.
- **`/query` says so under `lg`** (`components/query/NarrowWindowNotice.tsx`) and names the screens
  that do work at that width, which is the half that helps; it deliberately does not name the Topic
  Explorer, measured clean at 390 px but reachable only with a topic name, so a link there lands on
  the 404.
- **The probe used to print a ceiling as a measurement.** `clipped` was cut to eight entries before
  being counted, so five of seven pages read "8 clipped" at every width and the document concluded
  the clipping was width-independent truncation by design. It now counts the set, samples for
  `--detail`, excludes `sr-only` (which clips by construction) and reports whether the rest of each
  clipped element is reachable at all — a `title` on it, on a descendant or on an ancestor, or an
  ancestor that scrolls. W7 was scoped as confirming nothing hides unreachable content and **found
  the opposite**: a metric card's name, description and SQL line all carried `truncate` with no
  `title` anywhere, and the Cluster page's property names overflowed a grid cell with neither
  ellipsis nor title. Those are fixed; what remains is W8.
- **The `unreachable` column used to count closed tooltips.** `Tooltip` keeps its content mounted
  so `aria-describedby` always resolves; closed, the panel is only transparent, so its text went on
  counting in the `scrollWidth` of everything around it. The probe reported twenty such containers
  on the Topic Explorer and sixteen on Metrics — every one a closed tooltip, which is content that
  appears on demand rather than content cut off. It now takes those panels out of the layout for
  the measurement (`display: none`, restored after) instead of filtering them afterwards, so the
  browser recomputes and the ancestors come out right too. Measured: `topic-explorer` 20 → 2,
  `metrics` 16 → 4, `stream-flow` 2 → 0, target counts unchanged. What is left is real and named in
  the document; W7's answer holds in substance but no longer in the absolute form it was written in.
- **The probe opens things now, and it did not before.** It walked eight pages *at rest*, so
  every surface that exists only after a gesture — the four modals, a combobox's suggestion
  list — was outside every number it produced. That is not a gap in coverage but a gap in the
  one column that matters here: `unreachable` names precisely "this content is cut off and the
  rest is reachable nowhere", which is the class both truncation defects of 2026-08 belonged to,
  and it was not looking at them. `STATES` declares them beside `PAGES` (`sql-editor·confirm`,
  `sql-editor·ddl`, `metrics·editor`, `dashboard·palette`), they are reported and **gated** like
  a page — `MEASURED` feeds the `TARGET_BUDGET` guard, so a new state cannot be added without a
  budget — and a state that fails to open at a width it declares is a *failure*, never a silence,
  or a broken gesture would be indistinguishable from one nobody measures. Verified against the
  build that carried the defect rather than asserted: `sql-editor·confirm` reported
  `unreachable=5` against 1 for the page at rest, naming `p 374>294 "The window query replaces
  everything in …"` — the same two numbers that had been taken by hand — and comes back to 1 on
  the fixed build. Two things it taught. A state is measured **on the page already loaded**: a
  gesture costs a few hundred milliseconds where a navigation costs two to four thousand, which
  is why four of them add ~8 s to a 52 s `--check` instead of pushing the job past the timeout
  that already cost this mode its tablet viewport. And **a state must be able to report
  something**: an opened topic list was written, measured, and removed on that rule — the demo
  catalogue's names truncate at no width the probe walks, so the row could not move, and
  lengthening one would mean inventing data `setup-demo.sh` does not seed. The rule it would have
  guarded is pinned by a unit test instead, which depends on no data at all.
- **`unreachable` is gated, `clipped` is not, and the split was measured rather than argued.**
  Both were excluded from `--check` on one argument: clipping turns on text metrics, so a ceiling
  set on a developer machine would fail on a runner for a reason unrelated to the change. Reading
  the CI job's own probe output against a local run of the same commit, over the 21 rows `--check`
  walks, showed the argument covers one column and not the other — **`clipped` differed on 4 rows
  (all four on the SQL editor, 2 against 3), `unreachable` on none**. That is what the two count:
  where a string happens to wrap, versus whether any path to the rest of it exists — a `title`, a
  scrollable ancestor — which is a fact about the markup. `UNREACHABLE_BUDGET` therefore gates the
  second, with the same "no worse than today" ceilings and the same guard as `TARGET_BUDGET` (a
  measured row with no entry fails). It is only worth gating because Monaco left the measurement
  first: a ceiling over 18 findings of which 7 were the editor's own scroll layers would have
  capped noise.
- **Monaco's insides are out of the measurement.** The editor scrolls its own synthetic surface:
  `.monaco-scrollable-element` reports a `scrollWidth` of **16 777 216 px** and `.overflow-guard`
  clips by construction. The column claims to name "content cut off whose rest is reachable
  nowhere", and a text editor that scrolls is the opposite of that — measured on `main`, **7 of
  the 18 `unreachable` findings were those layers**, which is what made the column unsafe to gate:
  a ceiling would have capped noise. The rule excludes the whole editor rather than one class,
  since Monaco stacks several and a clipping inside its own rendering is Monaco's defect, not this
  application's. With them gone, and with the one real finding they were crowding out fixed
  (`SuggestionsPanel` truncated the name of the metric a proposal is already covered by, with
  nothing carrying it), **every page and state reports zero unreachable at desktop width**, which
  is the precondition for ever gating that column.
- **`--detail` reports the innermost clipped element, not the pile above it.** A container is
  usually cut off only because its child is, so one defect surfaced three or four times under the
  class names of a stack of layout `div`s — and since the sample is capped at eight in DOM order,
  those duplicates crowded out the real findings, a modal (rendered last in the document) falling
  past the cut every time. Same argument as `tooSmallGroups`: the count stays the whole set, the
  sample says *which* element.
- Re-run the probe before trusting any number in that document, and read a zero in its
  `unreachable` column as unconfirmed rather than proven: the pages that fetch asynchronously clip
  nothing while their cards are still arriving. Making those counts stable enough to gate a build
  is W6.

An earlier full bug & optimisation audit covered the whole codebase: all critical (C1–C4), major (M1–M8), minor and optimisation findings have been fixed here. Its report, `AUDIT.md`, was deleted from the tree in 31767bd — so the corrective decisions survive only as the behaviour of the code and the notes in this file, which is worth knowing before refactoring `AuditService`, `KafkaLiveConsumer`, `MetricService` or the direct SELECT engine: what looks like an odd choice in those four is usually a fix, and `git show 31767bd^:AUDIT.md` is where the reasoning is.

`INSERT-SCOPE.md` sizes the one thing this application refuses to do: run an `INSERT`. It exists
because the premise was nearly lost. Flink Job mode was removed with the sentence "it did not
work" — true of the build it was written against, and that build was **55 minutes old**:
`735b900` had just fixed the three defects behind the `Internal Server Error` an INSERT answered
with, two of which were silently degrading every SELECT as well and were surfaced by the INSERT
only because it is the one statement with no fallback. The judgment was never re-taken against the
fixed build, and the next morning `submitJob` came back and was enumerated against a real
MiniCluster (`FlinkSqlServiceInsertVariantsTest`, 21 cases). So what is missing is a door and a
window, not an engine: I1–I6 total **4 days** for a minimum honest feature, 6.5 complete, 1.5 for
an API-only version. The document also states the ceiling the feature cannot exceed, which is what
the decision should turn on — a submitted job lives in an embedded MiniCluster (~80 threads and
~6 MB of heap apiece) and dies with the process, so no store can make it survive a deploy. And it
names the real decision as a product one rather than a technical one: `POST /api/query/jobs`
widens an unauthenticated surface from reads to writes on the user's cluster.
