# Metrics that need two SQL queries — audit (2026-08)

Two of the four metric templates answer their question by running **two queries and comparing the
results**: `TOPIC_COUNT_DELTA` (a gap, a ratio or a percentage between two counts) and
`TOPIC_TRANSIT_LATENCY` (the delay between two topics, correlated on a key). They are also the two
the KPI suggestion panel proposes most — `throughputGap`, `endToEndVolume` and `transitLatency` in
`MetricSuggestionService` all emit one of them — so they are what an operator actually ends up
running when they accept a card.

Neither has been reviewed. This document is that review, in the shape the other scope documents
here take: every item is derived from the code, names the file it comes from, and is ranked.

> **Status.** It implemented nothing when it was written. **D1, D2, D3, D4, D6, D7 and D8 have
> since shipped**, in two changes: D1–D3 first (they share a diff, and the second is meaningless
> without the first), then D4, D6, D7 and D8. What each section describes below is therefore the
> state that work was done *from*; each now ends with what replaced it. Three further items
> shipped alongside because the fixes could not be written without them: **D5 in part** (the
> engine's own warnings now travel into the metric's summary, and both templates report what their
> read covered and what it dropped), **D9 for the three scan parameters** (refused when the metric
> is saved, and on the form at last), and **D11 in part** (each of the three messages names the
> side, the read and the alternative). A later change closed **D9 outright** (`operation` is
> refused at save time too) and took **D12** from a note to a measurement: the two assertions this
> document asked for exist now in `KafkaClusterIntegrationTest`, so the option D1 rests on is
> checked against a real broker rather than against a mock that cannot refuse it. **D10 remains
> open**, and the worklist at the end says what would close it.
>
> **One thing was found while implementing rather than while reviewing**, and it is recorded here
> because D5 understated it: an aggregate on the direct reader dropped its `WHERE` caveats
> altogether — the non-aggregate branch appended `whereWarnings` and this one returned without
> them — so a `COUNT(*)` filtered by a predicate the reader could not apply came back as a
> precise-looking number over unfiltered rows, with nothing anywhere to say so. Fixed in the same
> diff, in `kafkaDirectSelect`.

> **What this audit is derived from, and what it is not.** Everything below is read from the code
> and the shipped configuration. **Nothing here is a live measurement**: this sandbox has no Docker
> daemon and only a JDK 21, where `requireJavaVersion` pins 25, so neither `mvn verify` nor the
> Testcontainers broker can run. The two dominant findings (D1, D2) are derivations from four facts
> that are each individually checkable by reading, and each names the one experiment that would
> settle it. Where an item is arithmetic on the configured caps rather than an observation, it says
> so.

The single sentence, if there is only room for one: **the constant that is supposed to bound these
scans does not bound anything** (`MetricService:422`), and everything else in the top half of this
list is a consequence of that.

---

## D1 — The "bounded scan" hint bounds nothing

`MetricService:417-437`:

```java
/**
 * Bounded-scan hint: reads all data that exists in Kafka at query start time, then
 * terminates (no indefinite streaming). This is essential for aggregate metrics:
 * without it, COUNT(*) with latest-offset sees 0 messages and times out.
 */
private static final String BOUNDED_HINT =
    "/*+ OPTIONS('scan.startup.mode'='earliest-offset') */";
```

The comment describes `scan.bounded.mode`. The code writes `scan.startup.mode`. Those are two
different options: the first says where a scan **stops**, the second where it **starts**. The
sentence "then terminates (no indefinite streaming)" is a property of the option that is not there.

Three facts around it, each readable on its own:

- `FlinkConfig:36-37` builds the `TableEnvironment` `.inStreamingMode()`. A Kafka source in
  streaming mode is **unbounded** unless `scan.bounded.mode` is set, and `scan.bounded.mode` appears
  **nowhere in this repository** (`grep -rn "scan.bounded" src/` returns nothing).
- `DdlGeneratorService:105-108` already defaults every auto-registered table to
  `'scan.startup.mode' = 'earliest-offset'` (line 174 writes it). So on an auto-registered table —
  which is every table these metrics read, since the suggestion service names topics, not
  hand-written tables — the hint **sets the value the table already has**. It is a no-op that reads
  as a safeguard.
- The metric path is the only caller of `injectBoundedHint` (`MetricService:761-770`), so no other
  feature has ever depended on it working.

What follows from it is D2 and D3, which are different defects on the two templates because one
runs an aggregate and the other a projection.

**Shipped, then measured, and the measurement said no.** The hint was given both options, with a
degrade-once fallback standing in for the experiment nobody had run. The experiment has now run
(D12), and **`flink-connector-kafka:5.0.0-2.2` does not support `scan.bounded.mode`**: it answers
a hint carrying it with *"Unsupported options found for 'kafka'. Unsupported options:
scan.bounded.mode, scan.bounded.specific-offsets, scan.bounded.timestamp-millis"*.

Two things follow, and the second is why sending it was worse than not.

`FlinkSqlService` classifies that refusal as an **engine** failure, so it falls back to the direct
reader and returns rows with **no error at all**. The caller cannot see that the option was
rejected — which means the degrade-once latch could never fire on it, and three such queries would
have tripped the process-wide circuit breaker that takes the Flink planner out for every other
screen. The latch is gone with the option; a safety net that cannot observe its own trigger is not
one.

So the hint carries the startup mode alone, which is what this stack can express, and **the fix
that actually removed the templates' dependence on an unbounded planner scan is D2/D3's**: the
generated shapes are asked of the direct reader by name, which answers a count with one row and no
changelog. D1's own remedy is unavailable here and the code says so; the integration test asserts
the current answer, so the day a connector bump supports the option it fails and says which day
that was.

**The experiment that settles it**: register a demo topic, run
`SELECT COUNT(*) AS metric_value FROM demo_orders_1_received` through `FlinkSqlService.executeSql`
with the hint, and read `QueryResult.engine()` and the first row. `FLINK` with a small
`metric_value` is D2; a `Query timed out` in the log followed by `KAFKA_DIRECT` is D3's cost. Both
belong in `KafkaClusterIntegrationTest`, which already runs a real broker — and, per this
repository's own rule, a defect produced by the engine's own behaviour cannot be caught by a mock.

---

## D2 — `TOPIC_COUNT_DELTA` reads a changelog and keeps its first row

`SELECT COUNT(*) AS metric_value FROM t` — the exact SQL `MetricSuggestionService.countSql`
(line 1344) proposes — is, on an unbounded source in streaming mode, a **retract aggregation**. It
does not produce one row when the scan finishes; it produces one row per input record: `+I(1)`,
then `-U(1) +U(2)`, then `-U(2) +U(3)`, and so on, for ever.

`FlinkSqlService:1017-1038` collects those rows in arrival order, stops at `limit`, and **discards
`RowKind`** — `row.getKind()` is passed to a `log.debug` and to nothing else. `MetricService:809`
then takes the value:

```java
private Double extractPrimaryMetricValue(List<Map<String, Object>> rows) {
    for (Map<String, Object> row : rows) {
        Double value = extractValue(row);
        if (value != null) return value;      // ← the first row
    }
```

The first row of a `COUNT(*)` changelog is `+I(1)`. With `limit` = `DEFAULT_TEMPLATE_MAX_ROWS` =
10 000 (`MetricService:81`) and ~2 changelog rows per record, a topic holding more than ~5 000
records fills the collector before the timeout, the planner path succeeds, and the metric reports
**`left_value = 1`, `right_value = 1`** — from which `PERCENT_GAP`, the operation the suggestion
service picks (`MetricSuggestionService:453`, `:998`, `:1108`), computes **0.0**.

A silent-drop alarm that reads exactly "no loss" is the worst possible failure for this metric, and
it is the *large* topics — the ones worth alarming on — that fall into it. Below ~5 000 records the
collector blocks instead, the query times out, and the direct reader answers with a real count
(D10 is what that costs).

Note that no threshold catches this: `warning`/`critical` are set at 2× and 4× a measured gap
(`MetricSuggestionService:395-397`), and 0.0 is under every one of them.

**Shipped**, in three parts, because one alone would have moved the lie rather than removed it.
The value of an aggregate side is now the **last** numeric row — the final aggregate of a complete
changelog, and the only row of a single-row direct read, so one rule serves both engines. A result
that *filled its row budget* is refused instead, since the last row of a truncated changelog is a
partial count that looks exactly like a total. And the generated shape no longer produces a
changelog at all: a single-table read is asked of the direct reader by name
(`QueryRequest.directRead`, `MetricService.isSingleTableRead`), which answers a `COUNT(*)` with one
row. That reader has a ceiling of its own — 100 000 records — and it now **says so in the result's
warnings**, because two counts that both stopped there differ by nothing: a side that hit it is
reported as a floor and the comparison is refused rather than published as "no gap".

---

## D3 — `TOPIC_TRANSIT_LATENCY` measures the oldest records it can find, for ever

The correlation queries are plain projections (`MetricSuggestionService:1340`):

```sql
SELECT `order_id` AS match_key, `event_time` AS event_time
FROM demo_orders_1_received
```

Append-only, so no changelog problem — and no bound either. Whichever engine answers, the read
starts at the **earliest** offset:

- Flink path: the collect loop takes the first 10 000 rows the unbounded scan yields, which are the
  10 000 oldest surviving records.
- Direct path (`FlinkSqlService:1250-1258`): a projection with no `WHERE` fetches `limit + 20`
  records, `readMode` is `earliest-offset` (`MetricService:83`), so `getEarliestRecords(topic,
  10_020)`.

Both then compute an average over that set and publish it as a gauge, every 30 seconds, for the
life of the deployment. On any topic older than 10 000 records the number **never moves** — it is
the average transit latency of a fixed window of history, refreshed forever. A pipeline that
degrades today changes nothing on the chart; a pipeline that was slow the day it was seeded is
permanently red.

This is the same defect class the snapshot reader was fixed for and the audit's duplicate scan was
fixed for (`explorer.audit-duplicate-scan-from`, `LATEST` by default: *"every other check samples
recent messages, and on a topic with retention the oldest surviving records answer a different
question"*). The rule exists in this codebase already; this template predates it and never got it.

`maxRowsPerSide` would be the knob, and D9 covers why it is not reachable.

**Shipped.** The latency template now reads from the recent end by default
(`DEFAULT_LATENCY_READ_MODE`), which is what makes the figure move; `earliest-offset` restores the
old behaviour for a metric that really is asking about the beginning of a topic, and the form warns
when it is chosen. That default only means anything because `readMode` stopped being a knob that
does nothing: it is honoured by the direct reader alone, so the template asks for that reader by
name rather than letting the planner answer a question it has no syntax for — a Kafka scan starting
at `latest-offset` and bounded at `latest-offset` reads nothing at all, which is why "the most
recent N records" cannot be expressed as a scan option. `maxRowsPerSide`, `timeoutMs` and
`readMode` are on the form (D9), and the summary states what the read covered.

---

## D4 — The two queries are never taken at one instant, and the order errs toward silence

`computeCountDeltaMetric:639-642` runs the left query to completion, then the right one. Between
them sits a whole query — on the shipped defaults, potentially a 30 s planner timeout plus a
100 000-record direct scan. Whatever the pipeline produced in that interval is in the second
measurement and not in the first.

The direction matters and nothing states it. The suggestion service always puts the **upstream**
topic on the left and the **downstream** on the right (`MetricSuggestionService:451-452`,
`:996-997`, `:1106-1107`) and asks for `PERCENT_GAP = (left − right) × 100 / right`. Reading the
downstream count *later* inflates it relative to the upstream one, so the computed gap is
**understated** — the metric under-reports exactly the loss it exists to report, and on a fast
pipeline can go negative.

`KafkaAdminService` already solves this problem, deliberately and in the opposite direction:
committed offsets first, log end offsets **last**, *"so a consumer committing between the two calls
can only make the lag look larger… a negative lag that survives is a real one"*. Here there is no
stated ordering and no note; the arithmetic simply leans toward "nothing is wrong".

For `TOPIC_TRANSIT_LATENCY` the same gap has a second effect: source events produced after the
source query ran, but before the target query, appear in neither — while source events produced
just before the source read have their target counted, so the *most recent* pairs are the ones
systematically dropped. That compounds D6.

**Shipped.** The right side is read first and the left second, so the interval's traffic lands in
the side every operation here grows with — a gap that survives that is a real one, where the
previous order let the same traffic inflate the denominator and hide it. The scope note states the
direction in as many words, and `readGapMs` reports how much room the interval actually left: a
number rather than a reassurance, since four seconds on a topic doing a thousand records a second
is four thousand records in one count and not the other. `ABS_DIFF` is the one operation no
ordering helps — it is symmetric — and its note says so instead of claiming a guarantee it does
not have.

---

## D5 — Neither template says what it could not read

Three things are known at the point of computation and reported nowhere.

**Truncation.** Both sides stop at `maxRowsPerSide`. `QueryResult` carries no truncation flag at
all, and neither compute method compares `rows.size()` against the limit it asked for. A latency
computed over 10 000 of 4 million records and one computed over a whole small topic are the same
object with the same summary.

**Rows that were dropped.** `extractCorrelationEvents:771-793` silently `continue`s over every row
missing `match_key` or `event_time`. Only a *total* absence produces a message, and that message
("Transit latency requires match_key and event_time rows on both queries") is also what an empty
topic produces — two states that send an operator to two different screens, which is the exact
distinction `FieldProfileResult.error` and `SnapshotRead.emptyReadExplanation()` were added for.
A run where 90 % of rows lack the key looks identical to a healthy one.

**The engine's own caveats.** `QueryResult.warnings` — whose entire purpose, per its javadoc, is
*"predicates the direct engine could not apply… Silently returning unfiltered rows for a WHERE it
does not understand makes the result look precise when it is not"* — is **read by neither
template**. A metric written as `… WHERE status = 'OK' AND ts > x` has its second predicate dropped
by the direct engine, and the number published is computed over unfiltered rows with the caveat
discarded. The SQL editor renders those warnings above the grid; the metric engine throws them
away, on the path whose output feeds an alert.

---

## D6 — An unmatched source makes the latency look better

`computeTransitLatencyMetric:712-728` counts `unmatchedSourceCount` and then computes the average
over `latencies` alone. A source event whose target never arrived — a lost message, or one still in
flight, or one so slow it fell outside the window — contributes **nothing** to the value.

So the metric improves as the pipeline breaks: when a downstream stage stalls, the slow pairs stop
being pairs, and the average latency published is that of whatever still completes. At the limit,
one message getting through fast reads as a perfectly healthy hop.

`unmatchedSourceCount` is in the summary, which is the right measurement to have — it is simply not
used, not thresholded, and (D8) not displayed on the running metric. The minimum honest form is a
match rate exported beside the latency, on the same reasoning that made
`kafka_consumer_group_partitions_without_commit` a series of its own: *"a group ignoring half the
partitions has a backlog the lag does not count"*.

There is no `unmatchedTargetCount` either, so a target with no source — a duplicate, a replay, an
event from outside the window — is invisible. And targets earlier than their source are consumed
and discarded (`:718-720`) rather than counted: clock skew between two producers is exactly what
`ProcessModelBuilder.outOfOrderCount` and Stream Flow's dashed-red edge report as a finding, and
here it is silently absorbed.

**Shipped.** `matchRate`, `unmatchedTargetCount` and `outOfOrderCount` are measured and reported,
and the rate is **exported as a series of its own** —
`explorer_metric_correlation_match_rate{metric_id,metric_name,metric_type}` — because a figure
that exists only in a summary nobody alerts on cannot correct the figure that is alerted on. It
carries the metric's identity and none of its row labels, so a companion cannot multiply with the
series it describes. Both counts and the rate now reach the operator too: `lastSummary` was
computed, persisted to `internal.metrics.config` and rendered nowhere outside the preview modal, so
a metric *in service* said nothing about its own scope — it is a chip row on the card now
(`pages/metricScope.ts`, pure and tested), with the rate shown even at 100 % on the rule this
codebase applies to the coverage notice: an indicator seen only on bad news is one people stop
reading. A run that paired nothing no longer reports a bare "no correlated messages" either: it
says how many events each side yielded and, when a clock disagreement is what stopped them
pairing, that this is what happened.

---

## D7 — The `HISTOGRAM` / `SUMMARY` dedup is biased by the key ordering

`recordDistributionRows:1063-1114` deduplicates positionally: it records the suffix beyond the count
already recorded for that label series. Its javadoc names the risk and understates it:

> templates whose output is re-sorted each cycle rather than strictly appended (e.g.
> TOPIC_TRANSIT_LATENCY sorts by match key) get approximate positional dedup — the observation
> *count* stays bounded/correct, but the exact boundary values may shift slightly.

The rows are emitted in `(match_key, event_time)` order (`:704`). When the match key is monotonic —
`ORD-101`, `ORD-102` — new events do land at the end and the scheme works. When it is a UUID, a
hash or anything else unordered, new observations insert **uniformly at random** into the sorted
list, and what gets recorded each cycle is the tail of the key ordering: the observations whose
match key sorts highest, over and over. The p95 that `explorer_metric_summary{quantile="0.95"}`
publishes is then a quantile of a systematically selected subset, not of the latencies.

That is not "boundary values shift slightly"; it is a sample chosen by an attribute unrelated to
the measurement. Sorting the emitted rows by `event_time` instead of by key would cost nothing and
make the positional assumption true again — the correlation itself needs the key ordering, the
*output* does not.

**Shipped, and it had to go further than that**, because D3 broke the positional scheme outright:
a latency now reads the most recent records, so each cycle drops observations off the front and
gains others at the back while the count stays the same — which positional dedup reads as "nothing
new" for ever, freezing the distribution after its first window. Rows are ordered by event time
now *and* each carries when it was observed, in a reserved column (`__observed_at`) that is
excluded from the tags and from the label key exactly as `metric_value` is; without that exclusion
a timestamp would become a Prometheus label and mint one series per observation. The dedup keys on
that watermark for any series whose rows all carry one, and stays positional for those that do not,
so nothing changes for a raw-SQL metric. Two observations sharing a millisecond across two cycles
are recorded once, which is the safe direction for a summary that must never be inflated.

---

## D8 — A failed refresh freezes the gauge, and nothing dates it

`refreshSingleMetric:901-926` calls `updateMetricState(id, null, error, summary)` on failure, and
`updateMetricState:1284` keeps the previous value (`value != null ? value : current.lastValue()`).
The Micrometer instruments are only touched on the success path (`processRows`), so the
`AtomicReference` behind the gauge keeps its last value and `/actuator/prometheus` keeps exporting
it, indefinitely, with nothing saying the underlying read stopped working.

The `errorMessage` is visible on the Metrics page. Prometheus — the consumer this whole subsystem
exists for — sees a healthy series. `ConsumerLagMetrics` had the identical problem and it was fixed
with a companion series, for reasons that apply verbatim here:

> a frozen gauge is otherwise indistinguishable from a fresh one, so an alert on `lag > N` fires the
> same way whether the backlog is real and stuck or simply no longer measured […] A timestamp rather
> than a boolean: same cardinality, and it carries *how* stale.

A two-query metric has twice the failure surface of a one-query metric, so it is the family where
this matters most. An `explorer_metric_last_success_timestamp_seconds{metric_id}` is the same fix.

**Shipped**, as exactly that series, set only on a cycle that produced a value — so a failed
refresh leaves it where it was, which is the whole point. The alert becomes
`explorer_metric_gauge > N and time() - explorer_metric_last_success_timestamp_seconds < 120`. A
timestamp rather than a boolean, on `ConsumerLagMetrics`' own reasoning: same cardinality, and it
carries *how* stale. Freezing the value stays the behaviour — a broker blip must not read as "the
condition cleared" — and it is now a frozen value somebody can date rather than one nobody can.

---

## D9 — Half the parameters are validated at refresh time, and three are unreachable

`validateMetric:499-512` checks that `leftSql`/`rightSql` and `sourceSql`/`targetSql` are present and
that the metric type is allowed. It does not check:

- **`operation`** — an unrecognised value throws from `computeCountDeltaMetric:656` on *every
  refresh*, for ever, and is accepted by `POST /api/metrics` with a 200. Compare `CONSUMER_TIME_LAG`
  eleven lines below, which validates its `aggregation` against `{MAX, AVG}` at save time. The rule
  exists in the same switch statement; two of the four branches do not follow it.
- **`maxRowsPerSide` / `timeoutMs`** — `getIntParam`/`getLongParam` (`:830-840`) call
  `Integer.parseInt` with no guard, so `"10k"` becomes a `NumberFormatException` whose message
  (`For input string: "10k"`) reaches the operator as the metric's error, once per refresh.
- **`readMode`** — anything that is not the string `earliest-offset` is silently treated as
  latest-offset by the direct reader (`FlinkSqlService:1257`), and is ignored entirely on the Flink
  path, where the table's own DDL decides.

And those last three **are not on the form at all**. `Metrics.tsx:633-666` renders, for these two
templates, the two SQL boxes, the operation select and two optional label topics — nothing else. So
`maxRowsPerSide`, `timeoutMs` and `readMode` are reachable only by hand-crafting a `POST`, which
means the scan bound of D3 cannot be raised, the timeout of D10 cannot be lowered, and the end of
the topic cannot be chosen, from the screen that exists to configure the metric. `CONSUMER_TIME_LAG`
sitting immediately above them *does* state its budget, in a warning hint
(`Metrics.tsx:215-216`) — the two-query templates state nothing about theirs.

---

## D10 — What one of these metrics costs a refresh cycle

Arithmetic on the shipped defaults, not a measurement:

| | value | where |
|---|---|---|
| refresh period | 30 000 ms | `@Scheduled` default, `MetricService:848` |
| per-query timeout | 30 000 ms | `DEFAULT_TEMPLATE_TIMEOUT_MS`, `:82` |
| queries per metric | 2 | by construction |
| scheduler threads | 1 | Spring Boot default; no `TaskScheduler` bean here |
| concurrency | serialized | `refreshLock` around the whole cycle, `:852-864` |

Whenever a side blocks — which per D2 is the common case on topics under ~5 000 records, and per D3
the case whenever the topic is smaller than the row cap — that side costs its full 30 s before
falling back to the direct reader. One such metric therefore costs **60 s of a 30 s cycle**, and
`doRefreshMetrics` runs metrics one after another on a single thread. Five of them and the effective
refresh period is five minutes, while every gauge on the page and in Prometheus continues to look
current (D8 is why nobody can see that from the outside).

The cycle cache (`refreshCycleQueryCache`, `:164`) is the right idea and does help — two metrics
over the same pair of topics share one read — but it is keyed on the SQL string, so it only collapses
*identical* queries, and it holds every result of the cycle with no size bound. A cycle over ten
latency metrics holds up to 200 000 row maps until it ends.

---

## D11 — Three messages that name the wrong thing

- *"Both queries must return a numeric metric_value"* (`:647`) is returned when either side yields
  no numeric value. That is a config error (no `AS metric_value` alias) **and** an empty topic
  **and** a query the engine could not run — three causes, one sentence, and only the first is
  something the operator should go and edit. It also does not say *which* side.
- *"Cannot compute PERCENT_GAP when right metric value is zero"* (`:659`) turns the most alarming
  measurable state — the downstream topic is empty, i.e. total loss — into a metric error. The
  metric goes red, but as a *broken metric*, not as a breached threshold, so the alert built on it
  does not fire and the value published is the previous one (D8). A zero denominator is genuinely
  undefined for a ratio, but the state is real and has to be reportable; `left > 0, right == 0`
  deserves its own answer, not a shrug.
- *"Transit latency requires match_key and event_time rows on both queries"* (`:692`) — see D5.

---

## D12 — What the suite would catch today: none of the above

`MetricServiceTest:127` and `:155` both mock `flinkSqlService.executeSql` with two canned
`QueryResult`s of two rows each. They pin the arithmetic — 12 − 7 = 5, an 8 500 ms average — and
nothing else. Truncation, ordering, warnings, changelog rows, timeouts, key-sorted dedup and the
frozen gauge are all outside what a mocked `QueryResult` can express, which is this repository's own
stated reason for `KafkaClusterIntegrationTest`: *"a defect produced by the client's own behaviour
belongs in the integration suite, because a mock that cannot fail is not coverage."*

D1 and D2 are the two that need the real broker. Everything else (D4 ordering, D5 truncation and
warnings, D6 match rate, D7 emission order, D9 validation, D11 messages) is unit-testable against
the existing mock the moment the behaviour changes.

**Shipped, and it overturned one of the fixes on its first run.** `MetricServiceTest` carries the
mock-side cases — forty-one of them — and `KafkaClusterIntegrationTest` now carries the two that
need the broker.

The first was written as a differential — the same `COUNT(*)`, over the same topic, run twice, one
scan option apart — expecting the bounded half to be answered by the planner. It was not. This
connector refuses `scan.bounded.mode` outright, `FlinkSqlService` reads the refusal as an engine
failure and falls back, and the query comes back with rows, no error, and `engine: KAFKA_DIRECT`.
Both halves therefore land in the same place, which is the finding: **on this stack a scan cannot
be bounded at all**, and the option W1 added was not merely inert but harmful — see D1. The test
now asserts that answer, so a connector bump that changes it fails here and says so.

The second pins the path a count-delta side really takes: `directSql` reaching the direct reader,
one row rather than a changelog, and a number that had to come out of the broker. It passed on the
same run, which is what makes the first finding survivable: the count was right all along, by the
route D2/D3 had already sent it down.

What cannot be checked without Docker — which this environment has no daemon for — is anything in
this class: it skips locally and CI runs it. The gap it closes is larger than the one that remains,
and this first run is the proof: a claim that had been read off documentation, shipped, and
documented in two files was wrong, and nothing but a broker could have said so.

---

## Minor, recorded so they are not re-derived

- **`lastSummary` is rendered at preview and nowhere else.** `Metrics.tsx:1745` prints the summary
  in the preview modal; no page reads `MetricConfig.lastSummary`. So `matchedCount`,
  `unmatchedSourceCount`, `p95LatencyMs`, `maxLatencyMs`, `leftValue`, `rightValue` and
  `CONSUMER_TIME_LAG`'s `scopeNote` are computed, persisted to `internal.metrics.config`, and shown
  to nobody once the metric is running. D6 depends on fixing this to be visible at all.
- **The preview response shape is declared at the call site.** `Metrics.tsx:744` writes
  `useState<{ value?: unknown; rows?: unknown[]; error?: string; summary?: … }>` while
  `MetricPreviewResult` exists in `api/types.ts` under a `@java` marker. That is precisely the
  pattern `docs/check-api-types.py` was written to remove, and the checker cannot see a type
  declared inline.
- **`percentile(latencies, 0.95)`** (`:801`) returns the maximum for any sample under 20
  observations — `ceil(0.95 × n) − 1 = n − 1`. `MetricSuggestionService` already names this exact
  threshold and says so in its evidence line ("the worst of the N cases observed"); the summary here
  labels it `p95LatencyMs` whatever the count.
- **`injectBoundedHint` hints the first `FROM` it finds** (`:433`), which for a CTE is the table
  inside the `WITH`. Harmless today only because a CTE never reaches the direct reader and would
  fail anyway.
- **`MetricsHelp.tsx:194-199`** describes both templates in one sentence each with no mention of
  what they scan, while the `CONSUMER_TIME_LAG` block three lines below states its bound and its
  "unknown, never zero" rule. The help page is honest about the template that has no problem.
- **`explorer.metrics-refresh-rate`** exists only inside the `@Scheduled` string, so it is in
  neither `application.yml` nor a `@ConfigurationProperties` class, and `docs/check-config-table.py`
  cannot resolve it — it is therefore undocumentable as things stand.

---

## What I would do, in this order

| # | Work | Size | Closes |
|---|---|---|---|
| ~~W1~~ | **Shipped, then measured and partly reverted.** The javadoc describes the code, and the hint carries the startup mode alone: `flink-connector-kafka:5.0.0-2.2` *refuses* `scan.bounded.mode`, and refuses it in the one way that is worse than not having it — as an engine failure the planner path swallows by falling back. See D1's own section; what actually removed the dependence on an unbounded scan is W2's direct-read routing. | M | D1 (as far as this stack allows), part of D2 |
| ~~W2~~ | **Shipped.** The last numeric row, a truncated changelog refused, the generated shape answered by the direct reader, and that reader's own ceiling reported as a floor rather than compared. | M | D2 |
| ~~W3~~ | **Shipped.** `maxRowsPerSide`, `timeoutMs` and `readMode` on the form and validated at save; the recent end as the latency template's default; the coverage in the summary. | M | D3, D9 (scan half) |
| W4 | Report the scope: rows read vs cap, rows dropped for a missing column, `QueryResult.warnings` propagated into the metric's summary and error. | S–M | D5 (mostly shipped with W1–W3) |
| ~~W5~~ | **Shipped.** `matchRate` exported as a series of its own, `unmatchedTargetCount` and `outOfOrderCount` measured, and `lastSummary` rendered on the card. | M | D6, minor 1 |
| ~~W6~~ | **Shipped.** `explorer_metric_last_success_timestamp_seconds{metric_id}`, set only on a cycle that produced a value. | S | D8 |
| ~~W7~~ | **Shipped**, and further: event-time order *and* a reserved observation column, because a sliding window breaks positional dedup outright. | M | D7 |
| ~~W8~~ | **Shipped.** `operation` joins the three scan parameters: refused at save time against one named set, which the compute switch's own error message reads too. | S | D9 |
| ~~W9~~ | **Shipped.** The right side first, the direction stated in the scope note, and `readGapMs` reported. | S | D4, D11 |

W1 and W2 were the ones that changed what the numbers mean; W4 to W9 are what made them readable
as measurements rather than as assertions. All of them have shipped, and with them the two
experiments D1 and D2 named — so the option W1 rests on is measured against a real broker instead
of read off a page.


---

## Beyond the defects: what makes a count delta worth running

Everything above is a defect list. Two changes that are not — they alter what the metric measures
and what it costs — have since shipped, and they are recorded here because each dissolves items the
defect list could only mitigate.

### A whole-topic count is metadata, not a scan

`COUNT(*)` over a topic is `endOffsets − beginningOffsets` summed over its partitions.
`KafkaAdminService.getTopicsSize` already answers exactly that, **for both topics in one call**, and
the metric engine was instead downloading and parsing up to 100 000 records per side, every thirty
seconds. `countBy: OFFSETS` (and `AUTO`, which picks it when the metric names both topics and
neither query is anything but a plain whole-topic count) reads no record at all.

What that dissolves rather than mitigates:

- **D2's ceiling.** The refusal — two floors compared read as no gap — exists because the direct
  reader stops at 100 000 records. Offsets have no ceiling, so a topic of any size is countable, and
  the refusal's message now names the way out.
- **D4 entirely.** The interval between the two sides is not leaned in the safe direction, it is
  *gone*: both counts come out of the same pair of `listOffsets` responses. `readGapMs` is `0`
  because it is zero, not because nobody measured it.
- **Most of D10.** A metric goes from two bounded scans to two numbers already in the broker's
  metadata.

The cost is stated where it is paid, on the card and in the summary: this counts **offsets
produced, not records present** — a transaction marker takes one, and a record later compacted away
still counts. That is the right answer to "how many did this stage emit", which is the question a
silent-drop alarm asks, and the wrong one to "how many are in there now". `getTopicActivity` draws
the same distinction for the dashboard's sparkline. A query with a `WHERE` cannot be answered this
way and `AUTO` does not pretend otherwise.

### A lifetime total stops being able to fire

`TOPIC_COUNT_DELTA` compared the counts themselves. On two topics running for months, **a total
outage that started an hour ago is a fraction of a percent of the lifetime totals** — under every
threshold anyone would set. The metric was least sensitive exactly when it mattered most, and
nothing about the arithmetic said so.

`window: SINCE_LAST_REFRESH` compares what each side produced since the previous cycle. Its first
refresh publishes nothing and says why, a side whose count went backwards (a recreated topic, a
reset) is refused and the baseline re-established rather than a negative production published, and
**a preview never writes a baseline** — previewing a running metric would otherwise leave it
subtracting from an instant nobody measured. The suggestion panel proposes both settings on the gap
cards it builds, which are plain whole-topic gaps between two named topics: the shape offsets
answer exactly, and the shape a lifetime total desensitises.

### One defect found while doing it, and it was the worst of them

`left_value` and `right_value` were ordinary row columns, and **every non-`metric_value` column
becomes a Prometheus label.** On any live topic both move at every refresh, so the label set changed
at every scrape: each time series carried exactly one data point, and the metric could not be
graphed or alerted on at all — the only thing it exists for. Nothing said so, because the registry
stayed small; `pruneStaleSeries` deregistered the previous series each cycle, which is the tidy
version of the same defect.

The reserved-column rule is now a prefix (`__`) rather than a list of two names, so a measurement
that belongs in the row but not in the label set says so by its name. `theTwoCountsAreMeasurementsAndNeverPrometheusLabels` pins it, and was checked to fail against the previous code.

### The two sides of a latency were never read over the same stretch of time

`maxRowsPerSide` is a row cap, and a row cap is not a window — on two topics it is not even one
window. Ten thousand records is an hour of a slow source and four minutes of a busy target, so the
pairs that survived were the ones whose two halves happened to fall in the overlap. What that cost
is not the average, which is computed over real pairs, but the **match rate beside it**: the rate
was depressed by the misalignment exactly as it is by a genuine downstream loss, and those two send
an operator to opposite places. The rate had only just been exported as a series of its own (W5),
which is what made the flaw worth fixing rather than merely worth knowing.

`windowMs` reads both sides from the **same instant**, computed once in `computeTransitLatencyMetric`
rather than resolved per read — the distinction `KafkaAdminService.getRecordsSinceTimestamp` exists
for, beside the duration form. It travels as a third `readMode`, `since:<epochMillis>`, because the
mode string already carries direct-reader-only meaning and the alternative was a new field on
`QueryRequest` for a concept the planner cannot express. Which is also why a window on a side the
planner would answer is **refused at save time, naming the side**: a window silently honoured on one
side and ignored on the other is worse than none, the summary claiming one stretch of time while the
reads covered two.

One thing a window cannot avoid is stated rather than corrected: a source produced near the end of
it has its target *after* it, outside both reads, so the trailing edge understates the match rate by
about one hop's worth of traffic whatever the pipeline is doing. `ProcessModelBuilder` says the same
thing about the cases its window cuts in half, and for the same reason — what looks like a defect
and is not must be named, or it will be read as one. The suggestion panel proposes a 15-minute
window on the latency cards it builds, and says so in a caveat.

### The p95 was computed, put in the summary, and alerted on by nobody

A latency alert is set on the tail: an average holds still while the worst decile doubles, which is
the case this template exists to catch. `p95LatencyMs` and `maxLatencyMs` were both computed and
neither left `lastSummary`. `explorer_metric_correlation_latency_p95_ms{metric_id}` publishes the
first, through the same companion-series mechanism W5 and W6 added — and **only for the types that
carry no quantiles of their own**: a `SUMMARY` already publishes `explorer_metric_summary{quantile="0.95"}`
and a `HISTOGRAM` its buckets, so publishing beside them would be two answers to one question. What
lacks one is a `GAUGE`, which is the default and what every suggestion card proposes.

### Total loss was the one state the metric could not express

`PERCENT_GAP` divides by the right side, so "left > 0, right = 0" — everything the source produced
and nothing arrived — was refused as a division by zero and published *nothing at all*. The most
alarming reading this template can take was the one reading it stayed silent on, while firing
happily at 3 %. It reports **100** now, and that is a definition for the case rather than the
formula's own answer (whose limit there is infinity): 100 is the number a threshold is set against,
so any threshold below it fires. Both sides at zero is not a loss — nothing was produced and nothing
was missed — and reads 0. `RATIO` stays refused, there being no defensible finite value for a ratio
to zero, and the refusal names the three operations that report the same fact as a number.

### Two counts over one topic came out of two full reads

Two `COUNT(*)` under different `WHERE` clauses over the same topic — which is what a same-topic
`TOPIC_COUNT_DELTA` is — each downloaded and parsed up to `AGGREGATE_SCAN_RECORDS` records, thirty
seconds apart. The per-cycle memoization keys on the SQL, so it never brought them together.

`FlinkSqlService.executeSqlPair` runs the two as a pair and opens a slot the direct reader fills
and reuses; the decision is made on **what the two reads turn out to be** (same topic, same read
mode, both aggregates) rather than on what the SQL looks like from the caller. Aggregates only, on
purpose: their fetch size is that constant whatever the statement says, so there is no
larger-read-serving-a-smaller-one to reason about, and a projection stops early at its own row limit,
which would leave a partial list behind for the other side. The gain is not only the read — the two
counts now describe the **same instant**, which is the whole of D4 for that case, and the summary
says `sharedScan` rather than leaving the card to infer it from `readGapMs` being zero (two separate
reads can land in one millisecond; "these describe one instant" is a claim about how they were
taken, not a reading of the number).

### D10, and the half of it that can be closed without measuring anything

D10 is the one item here that is a property of the design rather than a defect in it: a refresh
cycle is single-threaded and serialized under one lock, each side of a two-query metric has its own
timeout, and the scheduler fires every thirty seconds. Two earlier changes cut the cost rather than
the shape (the generated shape no longer consults the planner, and a whole-topic count reads no
record at all), so the arithmetic that made it alarming — 60 s of a 30 s cycle, per metric — no
longer describes the common case.

Two more have shipped, and neither needs the measurement first because neither changes the shape:

- **`refreshIntervalMs` per metric.** Every metric was recomputed on every tick, which is right for
  a gauge over a cheap query and wrong for a template that reads two topics. It can only *slow* a
  metric down — the loop's tick is the floor, and the form says so rather than accepting a number
  that cannot be honoured. Skipping touches no state: the gauge keeps the value it was last
  measured at, which is correct, and `explorer_metric_last_success_timestamp_seconds` is what dates
  it. An explicit "Refresh now" ignores the interval, a gesture never being a cadence to be
  rationed.
- **`explorer_metrics_refresh_duration_seconds`.** The cost of the loop was the one thing about it
  nobody could see. A cycle that outlasts its tick does not pile up threads — it runs back to back,
  and the only symptom is a broker doing more work than anyone asked for. That is now a number, and
  a cycle over its own tick logs once per process naming the two ways out.

**The measurement is still the thing that would settle the rest**, and it is still untaken: the wall
time of one `refreshMetrics()` over a handful of template metrics against the demo cluster. The
gauge above is what makes it a reading rather than an experiment somebody has to set up. If it is
still minutes after all of the above, the fix is a bounded per-cycle budget, not more threads.

---

## The screen, which was not in scope and turned out to be the same finding

This document reviewed the engine. Reading the page afterwards found the *same* defect one layer
up, three times over: **everything computed here was being shown to a Prometheus scraper rather
than to the person looking at the card.**

### The card showed one number and hid the two that make it

`MetricCard` rendered `lastValue` and nothing else. On an écart, **`5` says nothing and `12 against
7` is the diagnostic** — and both were in `lastSummary`, computed, persisted, rendered nowhere. On a
latency it got worse the moment the p95 became a Prometheus series (above): it travelled to a
scraper and not to the card. `describeMeasurement` renders the components the server *measured* —
never a derivation, an absent key producing no part, and a zero producing one, since zero is a
measurement.

### The threshold had one direction, and one of the four operations needs the other

`getStatus` knew only `>=`, and `validateThresholds` **refused `warn >= crit` outright**. A `RATIO`
is healthy at 1.0 and breaks by *falling*, so its thresholds are 0.99 then 0.95 — the exact pair
that rule forbade. The operation was offered in the form and could not be alerted in the direction
that matters.

The direction is now **derived from the order of the two thresholds** rather than from a field of
its own. Critical is always worse than warning — the one thing the pair is known to say — so a
critical below the warning reads the metric downwards. No component on `MetricConfig`, none of its
forty-three construction sites touched, and the rule is checkable by looking at two numbers. That is
only not magic **because it is displayed**: the form states the direction the entered pair implies,
and the card's `≤` / `≥` follows. Equality stays an error; two identical thresholds express no
direction and would fire together.

### The page stopped one step before what these metrics are for

All of this exists so that an alert fires, and the PromQL was written nowhere. It stopped being
obvious the day the companion series arrived: a refresh that fails keeps the previous value —
deliberately — so `value > N` fires the same way whether the condition is real and stuck or simply
no longer measured.

`buildAlertRule` emits the rule under two constraints. **The alert must compare what the card
compares**: a `GAUGE`'s series *is* the number displayed, a `COUNTER`'s accumulates deltas between
refreshes and a `HISTOGRAM`/`SUMMARY` exports a distribution — so those three get the reason there
is no rule, naming what is exported, rather than a threshold copied onto a different quantity. And
every rule carries `explorer_metric_last_success_timestamp_seconds`, over four times the metric's
own `refreshIntervalMs` — or 120 s **said to be an assumption** when the cadence is the loop's own.
On a transit latency the p95 series is offered as a *second* rule rather than substituted: the
threshold was set against an average, and a p95 is above one by construction.

### Three smaller ones, all of the same family

- **The cost was only ever stated afterwards.** `explorer_metrics_refresh_duration_seconds` measures
  the cycle once it has run; `describeRefreshCost` says it in the editor, at the moment it is
  chosen. It **invents no total** — the direct reader bounds an aggregate by its own ceiling
  whatever `maxRowsPerSide` says, and a projection stops at its row limit — so what it states is
  what is configured and what bounds it.
- **Two sides that cannot differ were accepted.** The same statement on both, or the same topic on
  both under an offsets count (where the SQL is not read at all). Such a metric reports 0 for ever,
  and **0 here reads as "nothing is being lost"** — the one value it must never publish by accident.
- **The preview rendered `lastSummary` raw.** `Object.entries(summary)` put the whole `scopeNote`
  paragraph inside a 10 px chip and `warnings` through `String(v)`, i.e. `a,b`. It was also a
  *second* renderer for what `describeMetricScope` already did properly, one screen away from the
  card — the "two answers to one question" shape this codebase keeps removing, left standing.

### The sparkline plotted the value, and the value is not the measurement

This one was argued against and then asked for, and the argument was wrong in the part that
mattered: the cost was "a component on `MetricConfig` means forty-three construction sites", and
that record already carries two backwards-compatible constructors for exactly this. A third one
makes the nineteenth component cost **zero call sites**.

`history` holds the metric's own value — on a gap, the difference. The two counts are what an
operator needs to see move, so `componentHistory` keeps a series per component beside it. The keys
are a **closed list**, not "every number in the summary": most of a summary is scope, and a series
of rows-read or partitions-measured is noise on a card.

Two invariants make it drawable, and they are the same rule twice. Every series is exactly as long
as `history`, so index *i* is the same refresh in all of them. And a refresh that produced no value
for a series appends **`null`, never `0`** — a zero draws a fall that never happened, on the metric
whose entire job is to report a fall. A key seen for the first time is back-filled with nulls, which
is what makes it self-healing: a metric edited from one template to another flatlines its old series
until they scroll out of the window, with no shape-change detection to get wrong.

It is rendered as **its own chart with its own scale, deliberately not a second axis on the first**.
On a gap the value is 5 while the two sides are twelve thousand: a shared scale flattens the value
onto the baseline, and a dual axis manufactures a crossing that does not exist. A gap in a series is
drawn as a gap.

What is still **not** done, and now for the right reason: the two series are not aggregated,
smoothed or interpolated anywhere. A hole is a hole, and a card that filled one in would be
inventing a measurement — which is what the rest of this document exists to stop.
