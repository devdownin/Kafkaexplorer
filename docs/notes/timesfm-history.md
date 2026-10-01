# TimesFM integration — observation journal and series preparation

These deliveries implement history and deterministic series preparation. Inference, predictive alerts, the five new
MCP tools and the forecast UI are subsequent deliveries. It does not advertise forecasts from
an empty or unverified history.

## Architecture decision

Existing stores keep configuration and small UI histories, not a timestamped observation journal.
Use an optional PostgreSQL journal behind `MetricObservationStore`. No JDBC starter, default
application datasource or connection attempt is required when the feature is disabled. An enabled
journal validates its deployment settings before starting; an unreachable database leaves ordinary
metric collection operational, counts losses and emits a warning.

The v1 table is created lazily, with additive, versioned names. Use a dedicated database/user
allowed to create that table and its indexes. `JdbcMetricObservationStore` reads bounded windows,
commits a batch atomically, deduplicates retries by observation id and purges old rows in bounded
passes. No SQL text, DDL, credentials or arbitrary error text is persisted in its payload.

## Enable capture

Set the following properties in a deployment configuration, or through Spring's environment
binding. Keep credentials outside committed files. A PostgreSQL server is supplied by the operator
in this first delivery; there is no new compose overlay yet.

```yaml
explorer:
  forecasting:
    history:
      enabled: true
      cluster-id: production-eu
      collector-id: explorer-a
      jdbc-url: jdbc:postgresql://history-db:5432/kex_history
      username: kex_history
      # password is supplied separately by the deployment
      metric-ids: [metric-id-selected-in-metrics]
      queue-capacity: 128
      max-series-per-refresh: 100
      retention: 30d
```

`metric-ids` selects 1–100 configured metrics explicitly; no wildcard or automatic capture of every
metric. `collector-id` must be stable across restarts and unique per collecting instance. Collectors
with different ids have separate series. Shared ownership/leases and aggregation across instances
will be implemented with orchestration; do not run two instances with the same collector id.

| Setting under `explorer.forecasting.history` | Default | Meaning |
| --- | --- | --- |
| `enabled` | `false` | Create the optional journal |
| `cluster-id` | empty | Stable operator-assigned cluster identity, required when enabled |
| `collector-id` | empty | Stable, unique collecting-instance identity, required when enabled |
| `jdbc-url` | empty | PostgreSQL JDBC URL; configure TLS according to the deployment |
| `username`, `password` | empty | Database credentials |
| `metric-ids` | empty list | Explicit collection allowlist; required when enabled |
| `queue-capacity` | `128` | Pending observation frames; allowed 1–1,024 |
| `max-series-per-refresh` | `100` | Samples/components retained per selected metric refresh; allowed 1–1,000 |
| `retention` | `30d` | Observation retention; allowed 1–90 days |

This is a per-refresh limit, not the future global active-forecast-series quota. Dynamic labels can
still create many historical series over the retention period; select stable-label metrics and
monitor database size. Each queued frame is capped at 128 KiB. Capture performs no database I/O on
the refresh thread. The separate writer uses bounded connection/statement timeouts and one retry;
the queue is in-memory, so an abrupt process crash can lose pending frames. Such loss is a history
gap, never a fabricated zero; this first delivery does not claim a durable spool or exactly-once
collection across process crashes.

## Meaning of an observation

The timestamp is **collection-completion time**, not the Kafka event time or a reconstructed window
boundary. GAUGE captures the latest value for each effective label combination; COUNTER captures
the cumulative total summed by labels, before computing future rates. It is not automatically an
arrival count or a throughput. Raw HISTOGRAM/SUMMARY rows are not converted into gauge time series;
only explicit summary components are captured for these types.

Components such as `p95LatencyMs`, `leftValue` or `maxLagMs` remain separate series. Metric-wide
components have no row-specific labels. Semantic version hashes include SQL/DDL/template parameters,
type, execution mode and label definition, with canonical map ordering. Titles, descriptions and
thresholds do not split an observation history. Endpoint changes and collector changes also split
series to avoid mixing clusters or collecting instances. Hashes reveal no SQL or connection secrets.

Zero stays zero. A failed refresh writes a separate `collection` observation with a null value and
`UNMEASURED`; it never copies the last successfully displayed gauge into the historical series.
Missing per-label samples are gaps detected by the preparation layer. Invalid finite
values and unsupported/oversized frames are rejected and counted.

Quality states are conservative: raw SQL is `UNVERIFIED_SCOPE`; known caveats are `LIMITED_SCOPE`;
labels drawn from the latest message are `UNVERIFIED_LABELS`; per-refresh truncation is
`LIMITED_SERIES`. An absent unit remains `UNKNOWN`. Supported time-lag/latency templates use
milliseconds; otherwise an explicit `templateParams.unit` is retained. Collection does not certify
fitness for prediction: source resolution and operator approval of the metric semantics remain necessary.

## Prepare a context

`MetricSeriesPreparationService` is an internal bean created only when the journal is enabled.
It reads one bounded database window, then delegates to the pure `MetricSeriesPreparer`.
There is no scheduler or new Kafka/SQL metric execution in this step. An internal caller supplies
the series id, expected definition version, explicit source unit, cutoff and
`SeriesPreparationProfile`. This contract is independent of the inference model.

The initial profile is 512 points at one-minute cadence. Smaller contexts (2–512 points) and
cadences from one second to one day are supported for deterministic preparation/tests; the future
model adapter must enforce its own minimum. The 672-point alternate profile is not implemented yet.
Each output timestamp denotes the **end** of a completed UTC-aligned bucket `[start, end)`.
The cutoff is rounded down to that cadence. No incomplete bucket or observation at/after the
rounded cutoff enters a context, an imputation or its input fingerprint.

| Explicit transformation | Meaning |
| --- | --- |
| `GAUGE_MEAN` | Arithmetic mean of successful sampled gauge values within each bucket, not a time-weighted mean |
| `GAUGE_MAX` | Maximum successful sampled value within each bucket |
| `GAUGE_LAST` | Last successful sampled value within each bucket |
| `COUNTER_RATE` | Difference of the last cumulative readings in consecutive buckets, divided by their actual elapsed seconds |

Choose the transformation after reviewing SQL/template semantics. A retained-record stock is not
an arrival counter. For counter rates, the extra preceding bucket is read, decreases anywhere in
the input invalidate the context, and missing predecessors do not produce zero rates. A flat
counter produces a measured zero rate. The output unit becomes `<source unit>/second`.
All gauge transformations reject a scalar `COUNTER`; counter rates accept only its scalar `value`.
Sampled SUMMARY/HISTOGRAM components may be explicitly treated as gauges. Means of the known
`p95*`, `avg*` and `matchRate` components are refused: this does not reconstruct a global
percentile, average or ratio. Counts of disjoint arrival windows and numerator/denominator ratio
aggregation require additional source-window metadata and are not implemented here.

Preparation sorts observations, removes identical retries and refuses conflicting retry payloads
or different collection runs at the same timestamp. It verifies canonical series identity,
definition, unit and consistent semantic kind. Non-null values require `OBSERVED` quality;
`UNMEASURED` nulls are gaps. Unknown units and limited/unverified quality are inadmissible.
The store reads at most 4,097 raw observations: up to 4,096 are usable, and the extra row detects
saturation without certifying truncated buckets. This supports the default 30-second collector
cadence for a 512-minute context. A read also stops at 4 Mi characters of raw JSON payload,
before further deserialization. PostgreSQL reads use a read-only cursor with 64-row fetches to
avoid buffering the entire window in the driver. A saturated read returns `HISTORY_LIMIT`; reduce the context or
capture frequency rather than silently keeping its oldest samples. Store failures propagate to
the internal caller and are not reported as an empty history.

Only admissible gauge gaps are filled by carrying the last available **past** bucket value.
There must be an observed first bucket, at most two consecutive missing steps, and at most 5%
missing buckets over the whole context. Filled points retain `imputed=true` and `samples=0`;
the original missing-point count stays visible. Counter gaps are never filled. A context ends in
`STALE` after more than two missing gauge buckets, or any missing counter-rate bucket.

| Status | Meaning |
| --- | --- |
| `READY` | Every required point is available under the selected preparation policy |
| `WARMING_UP` | No observations or missing initial context/counter predecessor |
| `INSUFFICIENT_HISTORY` | Interior gaps exceed the policy |
| `STALE` | Recent complete buckets have no usable measurement beyond the tolerated gap |
| `INVALID_DATA` | Ambiguous timestamps, incompatible transformation, unknown units or unverified quality |
| `SCOPE_CHANGED` | Identity, definition, unit or semantic kind differs from the requested series |
| `COUNTER_RESET` | Cumulative readings decreased or became negative |
| `HISTORY_LIMIT` | The observation count or payload budget prevented a complete read |

`PreparedMetricSeries` carries source/output units, window bounds, per-bucket sample counts,
observed/missing/imputed counts, and separate SHA-256 fingerprints for the versioned preparation
policy and canonical input. Policy changes or data changes can therefore invalidate a later cache.
`MetricSeriesCorpusExporter.toJson` exports only `READY` contexts as versioned JSON
(`schemaVersion=1`, `kind=PREPARED_CONTEXT`), retaining the imputation markers and both fingerprints.
The caller controls offline storage and source authorization; this is not a public download API.
These are prepared observations, not forecasts or evidence of predictive accuracy.

## Observability and access

Prometheus exposes `explorer_forecast_history_persisted_total`,
`explorer_forecast_history_dropped_total`, `explorer_forecast_history_rejected_total`,
`explorer_forecast_history_write_failures_total` and `explorer_forecast_history_queue_batches`.
The persisted counter counts accepted append attempts, including deduplicated retry attempts;
the database, not that counter, gives the exact number of unique observations. No per-series labels
are added to these technical counters. Monitor failures and gaps before using the history.

The journal has no public REST or MCP endpoint in this delivery. A later history tool must resolve
and authorize **all** resources of a series before exposing its values or labels; a definition hash
alone is not resource provenance. No forecasts are exposed until these controls are implemented.

## Verification and rollback

Targeted tests cover canonical identity, semantic version separation, file-backed JDBC reopen,
duplicate writes, bounded reads/purge, asynchronous failure isolation, backpressure, nested property
binding and disabled startup. `MetricServiceTest` checks that every counter-label series is captured
without another SQL execution. `MetricObservationPostgresTest` exercises actual PostgreSQL under
Testcontainers and skips explicitly when Docker is unavailable; H2 results alone do not certify
PostgreSQL integration.

Preparation tests verify known bucket means/maxima/last values, actual-time and zero counter
rates, resets within/between buckets, short causal gap filling, over-limit/long gaps, scope and unit
changes, quality rejection, ambiguous duplicates, percentile handling and future-data exclusion.
JDBC-to-corpus tests verify a reopened store reconstructs exactly the same JSON and cover the
default 30-second collection cadence with 512 prepared points. No model or network is used.
An oversized-label fixture proves that a payload limit cannot yield a partial `READY` context.

Run `./mvnw verify` and the repository documentation checks. Set `enabled=false` to stop new capture;
ordinary collection remains unchanged and stored observations can be retained for later analysis.
Do not drop the journal table to roll back. Quantitative collection overhead, crash recovery with
actual PostgreSQL and long-running retention remain pilot checks, not claims from unit tests.
