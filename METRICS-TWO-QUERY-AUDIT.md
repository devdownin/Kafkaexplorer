# Metrics that need two SQL queries — audit (2026-08)

Two of the four metric templates answer their question by running **two queries and comparing the
results**: `TOPIC_COUNT_DELTA` (a gap, a ratio or a percentage between two counts) and
`TOPIC_TRANSIT_LATENCY` (the delay between two topics, correlated on a key). They are also the two
the KPI suggestion panel proposes most — `throughputGap`, `endToEndVolume` and `transitLatency` in
`MetricSuggestionService` all emit one of them — so they are what an operator actually ends up
running when they accept a card.

Neither has been reviewed. This document is that review, in the shape the other scope documents
here take: every item is derived from the code, names the file it comes from, and is ranked. It
implements nothing.

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
| W1 | Make the hint bound the scan: `scan.bounded.mode='latest-offset'` beside the startup mode, verified against `flink-connector-kafka:5.0.0-2.2`, with the javadoc rewritten to describe what the code does. Add the integration assertions in `KafkaClusterIntegrationTest`. | M | D1, and most of D2 |
| W2 | Take the last changelog row, not the first, when the engine is `FLINK` — or refuse the result outright and let the direct reader answer, which is what the count path effectively relies on today. | S–M | D2 |
| W3 | Give both templates a scan window they can state: `maxRowsPerSide`, `timeoutMs` and `readMode` on the form, `latest-offset` as the default read mode for the latency template, and the coverage in the summary. | M | D3, D9 (form half) |
| W4 | Report the scope: rows read vs cap, rows dropped for a missing column, `QueryResult.warnings` propagated into the metric's summary and error. | S–M | D5 |
| W5 | Export a match rate beside the latency, and `unmatchedTargetCount` and an out-of-order count beside `unmatchedSourceCount`. Render `lastSummary` on the metric card. | M | D6, minor 1 |
| W6 | `explorer_metric_last_success_timestamp_seconds{metric_id}`. | S | D8 |
| W7 | Emit latency rows in `event_time` order so the positional dedup's assumption holds. | S | D7 |
| W8 | Validate `operation`, `maxRowsPerSide`, `timeoutMs` and `readMode` at save time, in the same switch that already validates `aggregation`; mirror it in `validateTemplate`. | S | D9 |
| W9 | Read the two sides in the order that errs toward *over*-reporting the gap, and say so in a comment — the `KafkaAdminService` rule. Separate the `right == 0` answer from a metric error. | S | D4, D11 |

W1 and W2 are the ones that change what the numbers mean. W3 to W5 are what make the numbers
readable as measurements rather than as assertions. Everything from W6 down is small and
independent.
