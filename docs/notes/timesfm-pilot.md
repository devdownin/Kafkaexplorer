# TimesFM production pilot

The `kex_list_forecastable_metrics` catalogue also exposes `sources` with the operator-declared
`definitionVersion`, `topics`, `groups` and `complete` flag. Sources are returned only after exact
environment approval and authorization of every topic/group. Identifiers changed by DLP redaction
are omitted and provenance is marked incomplete; clients must not turn a masked name into a
resource link. This is an additive field: existing clients can ignore it. Source declarations
describe scope, not a measured incident or a discovered process association.

The pilot and its five read-only MCP tools are enabled by default. With no approved series,
the catalogue is empty and scheduling performs no database or model calls. Set
`explorer.forecasting.pilot.enabled=false` (or `EXPLORER_FORECASTING_PILOT_ENABLED=false`)
to disable the pilot and withhold its MCP tools.

To calculate forecasts, enable the existing PostgreSQL history and internal CPU service
using [the history runbook](timesfm-history.md), then configure approved series. A non-empty
pilot still requires both history and inference; invalid or incomplete deployment configuration
is rejected at startup. The operator owns
series identity, metric semantics, source resources, environment, thresholds and quality gates.
No series is automatically discovered or activated.

## Configuration

Merge this fragment into deployment configuration. Replace the series hash and definition version
with values from the enrolled observation journal. The source declarations are an operator
attestation: enumerate **every** topic/group used by the metric, including SQL dependencies.
Use the same pilot configuration and series budget on every replica sharing the database.

```yaml
explorer:
  forecasting:
    pilot:
      enabled: true
      interval: 5m
      retention: 7d
      max-series: 20
      series:
        - series-id: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          metric-id: orders-depth
          environment: production
          definition-version: "replace-with-observed-definition-version"
          unit: messages
          topics: [orders]
          groups: [orders-worker]
          profile:
            step-millis: 60000
            context-points: 512
            transformation: GAUGE_LAST
          horizon: 10
          season-length: 60
          max-mae: 100
          minimum-coverage: 0.8
          minimum-evaluated-points: 120
          threshold:
            series-id: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            definition-version: "replace-with-observed-definition-version"
            threshold: 10000
            direction: ABOVE
            horizon-points: 10
            confidence: 0.9
            history-quality: READY
            visibility: SHADOW
  mcp:
    allowed-forecast-environments: [production]
    allowed-topic-prefixes: [orders]
    allowed-group-prefixes: [orders-worker]
```

These thresholds are illustrative, not recommended universally. The threshold confidence must be
0.9, matching the supported one-sided Q10/Q90 bounds; other confidence levels are rejected. `threshold` and `max-mae` may
be omitted; all other series fields are explicit. The metric must already be included in history's
`metric-ids`. Context is exactly 512 points; horizon is 1–60; season is 1–512. Interval is 1 minute
through 1 day, retention 1–90 days, and max-series 1–100 (20 by default). Empty environment approval
denies every forecast MCP read, even when topic/group scope is unrestricted.

## Persistence and scheduling

The store creates additive `kex_forecast_result_v2`, `kex_forecast_pilot_control_v2` and
`kex_forecast_pilot_lease_v2` tables and a history-order index in the same PostgreSQL database.
Grant the dedicated history user the required DDL/DML privileges. Forecast records preserve
prepared history, quantiles, definition and model revisions, input/profile fingerprints, realised
quality, baseline scores and activation visibility. The SHA-256 idempotence key includes the full
approved specification, input/profile fingerprints, history end, horizon and pinned model revision.

The scheduled loop is serial and rejects local overlap. Each cycle admits work for at most
one minute, plus completion of one bounded in-flight call; a rotating cursor avoids starving
later series. A global PostgreSQL transaction advisory
lock spans preparation, inference and publication. A lease with a random owner expires after two
minutes according to database time; publication requires the same unexpired owner. Rollback or
connection close releases the lock, and expiry refuses publication. No inference is launched
without the lock. PostgreSQL connections use bounded connect/socket/query timeouts. The global
retained-series quota is checked under that lock; retention purge deletes at most 1000 rows per
pass. Keep the database dedicated to one pilot deployment; different replica configurations are
not a supported way to enlarge the quota. Infrastructure must eventually close dead sessions.

A repeated input/specification skips inference. Changing semantics or policy resets evaluated
quality and disables activation. Old records remain until retention expiry. Database failure
never substitutes an in-memory result for durable evidence. Refresh failures are counted; REST
returns 503 and MCP returns `DEPENDENCY_UNAVAILABLE` when persistence cannot be read.

## Quality, fallback and activation

Evaluation waits for a full forecast horizon to be realised. It uses observed, unimputed values
from the current bounded history and only compatible definition/profile/specification revisions.
The evaluated-through watermark excludes overlapping horizons from the sample count. Each cohort
uses identical actual timestamps for TimesFM and LAST_VALUE, MOVING_AVERAGE (last 12 training
points), SEASONAL_NAIVE and LINEAR_TREND (training OLS). Quality and baseline MAE describe the
**latest evaluated cohort**, while evaluated-points counts all accepted non-overlapping cohorts;
these are not cumulative averages or a statistical significance test. MASE uses training seasonal
naive error, and stays unmeasured for a zero scale. Pinball, coverage and interval width are retained.

Metrics Forecast polls persisted results every 30 seconds, cancels reads on unmount, and shows
states, actual history, Q50/Q10–Q90 and baseline MAE. `GET /api/forecasts` reports disabled, available
or unavailable state. `PUT /api/forecasts/{seriesId}/activation` accepts `active` and `confirmed`;
activation requires confirmed=true, a current READY TimesFM forecast, minimum evaluated sample
count, configured coverage/MAE gates and TimesFM MAE no greater than **every** baseline MAE. It
uses the same global lock, returning 409 during refresh or when quality is insufficient. Operator
REST uses the application's existing deployment access boundary; expose it through the same
operator-only access controls as other management endpoints. MCP offers no mutation.

Poor realised MAE, coverage, or loss against any baseline latches DEGRADED, disables activation and retains forecasts for
inspection. Recovery requires an explicit configuration/policy revision, new realised evidence,
and fresh operator activation. Inference timeout, BUSY, service outage or invalid output uses a
labelled seasonal-naive point forecast (season > 1), otherwise last value. Baselines carry no
confidence interval, never become ACTIVE and never create predicted threshold breaches. Invalid
history produces no forecast. Expired horizons read as STALE/SHADOW. A specification mismatch withholds the old record entirely
until a compatible result exists, so changed source approvals cannot expose old contexts or quality.
The same input is not retried until input/specification changes, including after a fallback.

## MCP contract

Registration requires both enabled MCP and enabled pilot. Existing allow/deny, read-only,
rate-limit, DLP and append-only audit interceptors remain in force. Before any storage access,
tools resolve the configured series, check quarantine, exact environment and **all** source topics
and groups. Unknown ids are OUT_OF_SCOPE. The catalogue omits inaccessible series.

| Tool | Read result |
|---|---|
| `kex_list_forecastable_metrics` | Authorized metric/series/environment catalogue |
| `kex_metric_history` | Existing prepared context, at most 512 points; unmeasured before first record |
| `kex_forecast_metric` | Persisted forecast, state, strategy, quality and provenance |
| `kex_get_forecast_quality` | Realised TimesFM metrics, four baseline MAEs, sample count, watermark and current state/strategy; unmeasured until maturity |
| `kex_list_predicted_threshold_breaches` | Explicit conservative bound breaches, result key, timestamps, revision and fingerprints |

The former `kex_forecast_catalog`, `kex_forecast_get`, `kex_forecast_latest`,
`kex_forecast_metadata` and `kex_forecast_limits` names are replaced. Reads never perform SQL
metric collection or model inference. Breaches inspect only future points inside the original
policy horizon; evaluation time and original window end are returned, and elapsed points cannot
create a predicted breach or extend the policy window. SHADOW breaches are labelled as such. Q10/Q90 one-sided
bounds carry nominal 0.9 confidence; this is not guaranteed calibration or a joint horizon
probability. No notification, alert execution or Kafka mutation is implemented.

## Metrics and operational checks

Monitor the existing history/client metrics plus `explorer_forecast_strategy_total` (TIMESFM,
LAST_VALUE, SEASONAL_NAIVE, UNAVAILABLE), `explorer_forecast_pilot_failures_total`,
`explorer_forecast_pilot_skipped_total` and `explorer_forecast_drift_total`. Fixed strategy/state
labels avoid high-cardinality series ids. Verify storage remains readable after restart, a second
replica skips a held lock, expired owners cannot publish, and retained-series cap/retention hold.
The real PostgreSQL tests use Testcontainers when Docker is available; an isolated database can
also be supplied with `TIMESFM_TEST_POSTGRES_URL` and `TIMESFM_TEST_POSTGRES_USER`.

For model-only CPU measurements, run the benchmark from `services/timesfm` after prefetch:

```sh
uv sync --frozen --group test
TIMESFM_CACHE_DIR=/models uv run python -m kex_timesfm.prefetch
uv run python benchmark.py --cache-dir /models --output benchmark.json --batch 1
uv run python benchmark.py --cache-dir /models --output benchmark-batch4.json --batch 4
```

The benchmark isolates 1/4/8-thread settings, records pinned revision, cold start, process peak
RSS and p50/p95 over 20 warm calls, and exits nonzero with UNMEASURED on failed runs. It never
implicitly downloads weights or silently reports a failed run as measured. Deployment sizing
also needs HTTP, worker/API processes, JVM, PostgreSQL and concurrent workloads; the measurements
in [the acceptance plan](../../timesfm.md) are not end-to-end service guarantees.
