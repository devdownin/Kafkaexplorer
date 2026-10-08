# TimesFM production pilot

The `kex_list_forecastable_metrics` catalogue also exposes `sources` with the operator-declared
`definitionVersion`, `topics`, `groups` and `complete` flag. Sources are returned only after exact
environment approval and authorization of every topic/group. Identifiers changed by DLP redaction
are omitted and provenance is marked incomplete; clients must not turn a masked name into a
resource link. This is an additive field: existing clients can ignore it. Source declarations
describe scope, not a measured incident or a discovered process association.

The pilot is enabled by default. With no approved series, scheduling performs no database or
model calls and **its five read-only MCP tools are not registered**: they used to be listed by
`tools/list`, chosen by a model and answered with an empty list or `OUT_OF_SCOPE`. The MCP console
keeps their rows, hidden with the reason "no approved forecast series", so an operator can see what
to set (`ApprovedForecastSeriesCondition`; YAML and `EXPLORER_FORECASTING_PILOT_SERIES_0_SERIESID`
both count). A configured series is approved at startup, so adding the first one that way needs
the restart that deploying configuration already implies. `explorer.forecasting.pilot.runtime-approval`
counts as well, since the series it admits arrive after registration. Set
`explorer.forecasting.pilot.enabled=false` (or `EXPLORER_FORECASTING_PILOT_ENABLED=false`) to disable
the pilot altogether.

## Runtime approval

`explorer.forecasting.pilot.runtime-approval` (**false**; `compose/forecasts.yml` turns it on) lets
the assistant approve a series directly: `POST /api/forecasts/series` runs the export's validation,
then writes the specification to `kex_forecast_series_v1` under the pilot's lock, counted against
`max-series` as the database holds it, so two instances cannot both spend the last slot.
`DELETE /api/forecasts/series/{id}` withdraws one and deactivates it; its results stay until
retention. Every cycle re-reads the table, so an approval made through one replica is scheduled by
the others at their next cycle. A configured series always wins over a runtime row with the same id,
and only a runtime series can be withdrawn at runtime. Off is the posture for a shared deployment,
where every approval should live in reviewed configuration; on is what makes a local stack start a
forecast without a file and a restart. Neither changes the MCP scope policies.

To calculate forecasts, enable the existing PostgreSQL history and internal CPU service
using [the history runbook](timesfm-history.md), then configure approved series. A non-empty
pilot still requires both history and inference; invalid or incomplete deployment configuration
is rejected at startup. The operator owns
series identity, metric semantics, source resources, environment, thresholds and quality gates.
No series is automatically discovered or activated.

The operator UI now has a [guided setup and complete local stack](../forecasts.md). Its candidate
catalogue is metadata-only; exported configuration derives identity using the same collector
function as capture and preserves existing approvals. Fresh progress reads resolve the declared
series before a bounded history read; dependency probes are explicit, cached and do not infer.

For `CONSUMER_TIME_LAG`, the configured topic and group are also fixed labels on the captured
value. The assistant includes those labels when deriving the series ID; omitting them yields an
approved ID that cannot locate the observations. Existing approvals and stored observations are
preserved. A configuration previously exported with an ID that omitted those labels must be reviewed and
exported again, then deployed explicitly; the new series still starts in `SHADOW`.

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
be omitted; all other series fields are explicit. The metric must be recorded by history: listed in
`metric-ids`, or eligible while `enroll-eligible` is on. The specification's context is 512 points;
a series is forecast from a partial one once it holds 128 points, or one whole season when that is
longer (`MIN_CONTEXT_POINTS`), and the activation gate is unchanged. Horizon is 1–60; season is 1–512. Interval is 1 minute
through 1 day, retention 1–90 days, and max-series 1–100 (20 by default). Empty environment approval
denies every forecast MCP read, even when topic/group scope is unrestricted.

## Persistence and scheduling

The store creates additive `kex_forecast_result_v2`, `kex_forecast_pilot_control_v2`,
`kex_forecast_series_v1` and `kex_forecast_pilot_lease_v2` tables and a history-order index in the same PostgreSQL database.
Grant the dedicated history user the required DDL/DML privileges. Forecast records preserve
prepared history, quantiles, definition and model revisions, input/profile fingerprints, realised
quality, baseline scores and activation visibility. The SHA-256 idempotence key includes the full
approved specification, input/profile fingerprints, history end, horizon and pinned model revision.

The loop runs on its own `forecast-pilot` thread (`ForecastPilotScheduler`), never on Spring's
shared scheduler: that one has a single thread, and a cycle there delayed metric collection and
put gaps into the history being forecast. Its executor is private and published as no
`ScheduledExecutorService` or `TaskScheduler` bean, either of which would switch off Spring Boot's
own scheduler and move every other `@Scheduled` method onto this thread. The loop is serial and rejects local overlap. Each cycle admits work for at most
one minute, plus completion of one bounded in-flight call; a rotating cursor avoids starving
later series. A global PostgreSQL transaction advisory
lock spans preparation, inference and publication. A lease with a random owner expires after two
minutes according to database time; publication requires the same unexpired owner. Rollback or
connection close releases the lock, and expiry refuses publication. No inference is launched
without the lock. PostgreSQL connections use bounded connect/socket/query timeouts and come from
a small pool (`ForecastConnectionPool`, four idle at most, no limit or queue on those in use): every
read used to open a connection, TLS included, and the breach tool one per series. A returned
connection is rolled back before reuse, so a borrower that failed mid-transaction releases the
advisory lock exactly as a physical close did. The global retained-series quota is checked under
that lock and counts **approved** series only — a series removed from the configuration used to
hold its share until retention expired its rows; retention purge deletes at most 1000 rows per
pass. Keep the database dedicated to one pilot deployment; different replica configurations are
not a supported way to enlarge the quota. Infrastructure must eventually close dead sessions.

The idempotence key encodes the approved specification field by field (`ForecastPilotService.canonical`),
doubles as their bit patterns; it was `Record.toString()`, whose format the JDK leaves unspecified,
so a runtime upgrade could re-key every record at once. Records written under the former key are
still read as compatible (`legacyKey`), so the upgrade neither disables an ACTIVE series nor drops a
cohort in flight; that branch can go once every deployment has run for one retention period. When a
read finds a record of an earlier specification, MCP says so instead of "No forecast has been
persisted". A repeated input/specification skips inference. Changing semantics or policy resets evaluated
quality and disables activation. Old records remain until retention expiry. Database failure
never substitutes an in-memory result for durable evidence. Refresh failures are counted; REST
returns 503 and MCP returns `DEPENDENCY_UNAVAILABLE` when persistence cannot be read.

## Quality, fallback and activation

Evaluation waits for a full forecast horizon to be realised. It scores observed values only: an
imputed point inside the realised horizon withholds that cohort, an imputed point elsewhere in the
context does not (it used to suspend evaluation for 512 steps). Only compatible
definition/profile/specification revisions are compared. The evaluated-through watermark excludes
overlapping horizons from the sample count. Each cohort uses identical actual timestamps for
TimesFM and LAST_VALUE, MOVING_AVERAGE (last 12 training points), SEASONAL_NAIVE and LINEAR_TREND
(training OLS). MASE uses training seasonal naive error, and stays unmeasured for a zero scale.

**Quality is judged over blocks, never over one cohort** (`ForecastQualityWindow`). A cohort is one
horizon — ten points in the example above — and a calibrated 80 % interval covers fewer than eight
of ten points about one time in three, so a per-cohort verdict that latches is drift by
construction. Cohorts accumulate until a block holds at least 120 points **and six cohorts** (`blockPoints(horizon)`,
360 points at a 60-point horizon); the block is judged once
on its pooled figures and a new one starts. A block fails when its coverage is below
`minimum-coverage` by more than 2.326 standard errors (the larger of the binomial one and the one
measured between cohorts, since the points of one horizon share their errors), when its MAE exceeds
`max-mae`, or when its MAE is more than 10 % above any baseline's. Two consecutive failed blocks,
once `minimum-evaluated-points` is reached, latch DEGRADED. Displayed quality and baseline MAE are
the last judged block, or the cohorts realised so far before the first one closes. In a simulation
of a calibrated model over 30 days, about 1 % of runs latch whatever the correlation inside a
cohort. The six-cohort floor exists for long horizons: at 60 points, 120 points were two cohorts,
and the between-cohort error was estimated from two values. A long horizon therefore waits longer
for its first verdict, which is the price of one that means something. This is a gate, not a
calibration proof or a joint horizon probability.

Metrics Forecast polls persisted results every 30 seconds, cancels reads on unmount, and shows
states, actual history, Q50/Q10–Q90 and baseline MAE. `GET /api/forecasts` reports disabled, available
or unavailable state. `PUT /api/forecasts/{seriesId}/activation` accepts `active` and `confirmed`;
activation requires confirmed=true, a current READY TimesFM forecast, minimum evaluated sample
count, and a last judged block that passed with no failed block since, met the coverage/MAE gates
and kept TimesFM MAE no greater than **every** baseline MAE (0.1 % of the series level as slack, so
a constant series does not lose to a zero-error LAST_VALUE by float noise). Activation is stricter
than drift on purpose: 5 % worse than a baseline is not drift, but it does not earn ACTIVE. It
uses the same global lock, returning 409 during refresh or when quality is insufficient, with
the blocking sentence as `reason`; `GET /api/forecasts` carries the same verdict per series as
`activation`, so the page can say it before the click. **Returning to SHADOW (`active=false`) takes
no lock** and is never refused for a running cycle: visibility is recomputed from the control row
on every read, so a record a cycle publishes as ACTIVE a moment later still reads SHADOW. Operator
REST uses the application's existing deployment access boundary; expose it through the same
operator-only access controls as other management endpoints. MCP offers no mutation.

Two consecutive failed blocks latch DEGRADED, disables activation and retains forecasts for
inspection. Recovery requires an explicit configuration/policy revision, new realised evidence,
and fresh operator activation. Inference timeout, BUSY, service outage or invalid output uses a
labelled seasonal-naive point forecast (season > 1), otherwise last value. Baselines carry no
confidence interval, never become ACTIVE and never create predicted threshold breaches. Invalid
history produces no forecast. Expired horizons read as STALE/SHADOW. A specification mismatch withholds the old record entirely
until a compatible result exists, so changed source approvals cannot expose old contexts or quality.
The same input is not retried until input/specification changes, including after a fallback.
A TIMEOUT or UNAVAILABLE answer takes the model out **for the rest of the cycle**: the remaining
series get the labelled point fallback at once, with the reason "TimesFM TIMEOUT earlier in this
cycle", and `explorer_forecast_pilot_skipped_total{state="MODEL_OUT_THIS_CYCLE"}` counts them. A
hung service otherwise cost the whole timeout per series, so a 60 s cycle reached two series and
the others went stale. The next cycle calls the model again. BUSY and INVALID_OUTPUT do not take
it out: they say nothing about the next call.

## MCP contract

Registration requires enabled MCP, enabled pilot and at least one approved series. Existing
allow/deny, read-only, rate-limit, DLP and append-only audit interceptors remain in force; the
caller's quarantine is the interceptor's, keyed on the authenticated identity. Every tool scrubs
the identity strings it returns (definition version, units, reasons) exactly as the catalogue
does — the interceptor scrubs parameters only, so the four other tools returned in clear what the
catalogue masked. Before any storage access, tools resolve the configured series, check the exact
environment and **all** source topics and groups. Unknown ids are OUT_OF_SCOPE. The catalogue omits inaccessible series.

| Tool | Read result |
|---|---|
| `kex_list_forecastable_metrics` | Authorized metric/series/environment catalogue |
| `kex_metric_history` | Existing prepared context, at most 512 points; unmeasured before first record |
| `kex_forecast_metric` | Persisted forecast, state, strategy, quality and provenance |
| `kex_get_forecast_quality` | Realised TimesFM metrics, four baseline MAEs, sample count, watermark, current state/strategy and `activation` — the same eligibility verdict and reason the page shows, with the quality block's progress; unmeasured until maturity, the reason then naming what activation waits for |
| `kex_list_predicted_threshold_breaches` | One row per authorized series: `BREACH`, `NO_BREACH`, `NO_POLICY` or `NOT_EVALUATED`, its reason, and for an evaluated forecast the conservative bound, result key, timestamps, revision and fingerprints |

The former `kex_forecast_catalog`, `kex_forecast_get`, `kex_forecast_latest`,
`kex_forecast_metadata` and `kex_forecast_limits` names are replaced. Reads never perform SQL
metric collection or model inference. Breaches inspect only future points inside the original
policy horizon; evaluation time and original window end are returned, and elapsed points cannot
create a predicted breach or extend the policy window. SHADOW breaches are labelled as such. Q10/Q90 one-sided
bounds carry nominal 0.9 confidence; this is not guaranteed calibration or a joint horizon
probability. No notification, alert execution or Kafka mutation is implemented.

**No breach is only said of a forecast that was evaluated.** A series with a policy whose latest
result is missing, a fallback, not READY or past its policy horizon is `NOT_EVALUATED`, and the
coverage is then `PARTIAL_FAILURE` with those series ids in `topicsNotReached` (the field is named
for topics; here it carries series) plus a `FORECAST_NOT_EVALUATED` warning. The tool used to drop
such series and answer an empty `EXHAUSTED` list, which read as "nothing predicted" precisely while
TimesFM was down and every series was on its fallback. Series without a policy are listed as
`NO_POLICY` and are not counted as requested.

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
