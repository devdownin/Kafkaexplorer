# Metrics demo test scenarios

## Forecast Demo Control Center (P2)

The **Metrics** page shows a Demo Control Center only when the Explorer runs with
`EXPLORER_DEMO_CONTROL_ENABLED=true` (set by `compose/demo-forecast.yml`). It reports
live demo metric counts, forecast series and READY results, and lets the operator
switch between **normal**, **overload**, **incident**, **recover**, and **seasonal**
traffic. Disruptive scenarios show their affected metrics before confirmation; **Return to normal**
remains a one-click escape route. Historical observations and existing forecasts are preserved.

### Scenario application is verified

The API distinguishes the **requested** scenario from the **confirmed** scenario. A scenario
is *Applying* until the generator has executed a complete tick, then inspected Kafka's
log end offsets on the following tick and confirmed that the scenario-affected topic pairs reflect
the requested values. Unaffected topic pairs continue their independent curves and cannot block
confirmation of an otherwise applied incident. Only then does it write a timestamped acknowledgement
(`demo-scenario-applied.conf`) to the local shared control directory. The UI shows
*Active* with the generator's verification time, or *Generator unavailable* when the
receipt becomes stale (default 75 seconds). A UUID on UI requests prevents a previous
acknowledgement from being mistaken for the new request; the CLI's plain single-word
scenario commands remain compatible. A request is not proof of delivery.

During the first load, metric/forecast counters say *Loading*, never a fabricated zero;
unavailable forecast data is labelled *Unavailable*, separately from a genuine zero
series. A failed control API request produces a retryable error panel instead of silently
hiding the demo; an intentional 404 still hides it in non-demo deployments.

The endpoint is disabled (404) in regular deployments, validates a fixed allowlist,
rejects cross-origin browser writes and never runs shell commands. The demo binds
its UI to loopback by default; do not expose demo control on a public host.

**Published-image note:** this UI and API require a KafkaExplorer image built from
this change or a subsequent published release. The no-build demo now follows
`compagnonsdudev/kafkaexplorer:latest` by default; the Control Center appears
once that moving tag includes the merged P2 change. Until then, CLI scenario control
(`./demo-forecast.sh scenario <name>`) remains available.

## Scenario Studio — lots B and C

In the Metrics **Forecast Demo Control Center**, choose **Manage scenarios** to see
five compact mini-curve previews. Those previews illustrate the shape of a scenario;
they are explicitly **not observed Kafka measurements**. Selected cards describe the
affected business signals before the generator request is sent. **View impacted metrics**
focuses the actual relevant metric cards and clears other filters; **Show all metrics**
restores the unfiltered list.

The **Guided demonstration** runs a three-step narrative:
**Baseline → Incident → Recovery**. Each action changes the real Kafka demo generator;
an incident still needs explicit confirmation, and Recovery stays a one-click action.
The UI distinguishes a requested change from Kafka-verified activation. After
confirmation it refreshes the affected metrics once, without issuing a refresh on every
generator heartbeat.

The resulting first-verified activation time stays stable as new heartbeats arrive.
Metric cards and the detailed Forecast chart place a vertical event marker only when
that time falls **inside non-imputed, measured history**. Until then, the note says the
event is not yet represented; it does not draw a future observation or invent a delta.
The detailed view also offers **Compare with previous forecast**, using the existing
previous-prediction overlay. The post-event mean observed-minus-predicted difference
is computed **only at exact matching timestamps**; when no measured point is available,
it explicitly says so. Forecast model uncertainty and out-of-range intervals are
never implied to be actual observations.

The responsive layout supports narrow 320 px screens and touch, uses keyboard-reachable
buttons with focus rings and accessible status messages, and avoids motion-dependent
feedback. If the viewer prefers reduced motion, scrolling to metrics is immediate.
The visual surfaces use KafkaExplorer's theme tokens instead of hard-coded colors.

## No-build forecast demo (published images)

This is the shortest way to run the **complete live Metrics/Forecast demo** without compiling
Java, installing Node dependencies, building containers or downloading TimesFM model weights:

```bash
./demo-forecast.sh
```

On Windows 11 (PowerShell **or** cmd, with Docker Desktop):

```powershell
.\demo-forecast.cmd
```

The launchers use `demo.sh --forecast --prebuilt` / `demo.ps1 -Forecast -Prebuilt`.
They initialize every necessary setting from the environment, then the root `.env`,
then auto-generated `.forecast-stack/.env`, then built-in defaults. The PostgreSQL password,
TimesFM bearer and MCP bearer are generated once and reused across launches. No LLM API key
is needed. `COMPOSE_PROJECT_NAME` defaults to `kafka-explorer-demo`, bound to loopback on
8080 (Explorer), 8085 (Schema Registry) and 9092 (Kafka).

The scripts **pull**, but never build, the latest published Explorer
(`compagnonsdudev/kafkaexplorer:latest`) and Forecast/TimesFM
(`ghcr.io/devdownin/kex-forecast:latest`) images by default.
Only the prebuilt Forecast demo uses these moving tags: other Compose stacks
retain versioned defaults. Override either image independently by setting
`EXPLORER_IMAGE_TAG` and/or `FORECAST_IMAGE_TAG` in the environment or root
`.env`, e.g. `EXPLORER_IMAGE_TAG=2.9.1 FORECAST_IMAGE_TAG=0.3.0 ./demo-forecast.sh`.
Use versioned overrides to reproduce an older demonstration.
A missing/unavailable image is a hard failure rather than an implicit local build. The
forecast image already includes model checkpoints; `forecast-model-prefetch` is not started.
Allow roughly **4 CPUs and 8 GiB RAM** for TimesFM, plus Kafka and PostgreSQL.
The published forecast image includes Timestral-28M, whose modified MIT license has
business restrictions **even when its shadow scoring is disabled**:
[model license notice](../services/timesfm/licenses/NOTICE.md).
Review that notice before using the image.

The stack seeds five live metrics and nine hours of history. Open
`http://localhost:8080` → **Metrics**. One metric has a ready forecast; the other
four can be forecast with their card actions or **Forecast all eligible**.

| Action | Linux/macOS | Windows |
| --- | --- | --- |
| Preflight (no pull/build) | `./demo-forecast.sh doctor` | `.\\demo-forecast.cmd doctor` |
| Start and wait until **fully ready** | `./demo-forecast.sh` | `.\\demo-forecast.cmd` |
| Verify five histories and a READY TimesFM forecast | `./demo-forecast.sh verify` | `.\\demo-forecast.cmd verify` |
| Offline start from already-loaded images | `./demo-forecast.sh --offline up` | `.\\demo-forecast.cmd -Offline up` |
| Select live scenario | `./demo-forecast.sh scenario overload` | `.\\demo-forecast.cmd scenario overload` |
| Status | `./demo-forecast.sh ps` | `.\demo-forecast.cmd ps` |
| Seeding logs | `./demo-forecast.sh logs demo-forecast-seed` | `.\demo-forecast.cmd logs demo-forecast-seed` |
| Stop, keep data | `./demo-forecast.sh down` | `.\demo-forecast.cmd down` |
| Reset, **delete volumes** | `./demo-forecast.sh reset` | `.\demo-forecast.cmd reset` |

The preflight checks the Compose version and configuration, Docker Engine, available Docker RAM,
the three published host ports and image presence. It warns below 8 GiB assigned to Docker.
If a port is occupied and the current Compose project is not running, startup stops with an error.
An already-running demo can keep its own ports.

`up` reports **ready only after** the one-shot seeder has exited successfully, the
Kafka producer and dependent services are running, TimesFM is healthy, all five metrics
have at least 500 seeded historical observations, and the unshipped-orders series has
a READY TimesFM result. Set `DEMO_FORECAST_READY_TIMEOUT` (seconds, default 900)
if your machine needs longer. `verify` performs those checks once and returns nonzero
with the relevant diagnosis; the startup also prints the appropriate log commands on timeout.
A successful HTTP health request alone does **not** mean the demo is ready.

### Fully offline launch

First run the normal launcher once so the selected images are present, or import those
images from `docker save` archives using `docker load`. Then launch with
`--offline` on Linux/macOS or `-Offline` on Windows. Offline mode **fails
before starting** if an image is missing. It uses `docker compose up --no-build
--pull never` and does not contact registries or download model checkpoints.
It does not prohibit network communication by Kafka or the application; it
specifically disables image/model fetching.

### Interactive live traffic scenarios

These commands work on an already-running stack and are applied at the next live
producer tick (normally 15 seconds), by writing `demo-scenario.conf` into the
host-side `.forecast-stack/config/demo-control` directory. This dedicated
writable directory keeps scenario requests and Kafka-verified receipts separate
from `.forecast-stack/config/forecasts.yml`; the forecast stack bootstrap resets
the parent directory permissions during every CLI call. The producer reads
requests and writes receipts in the dedicated directory.
They change **real Kafka topic offsets** and thus the next live observations; they
do not rewrite the pre-seeded historical observations or spoof forecasts.

| Command suffix | Effect on live Kafka signals |
| --- | --- |
| `scenario overload` | Force unshipped orders to ~480 and request backlog to ~185 (above their critical thresholds) |
| `scenario incident` | Force open tickets to ~195 and unarchived events to ~180 (abrupt shift) |
| `scenario recover` | Return all five live counts to a stable nominal baseline |
| `scenario seasonal` | Enlarge the request backlog's variation into a 20-minute cycle |
| `scenario normal` | Remove overrides and resume the five baseline wall-clock curves |
| `scenario` | Show the selected scenario |

For example, run `./demo-forecast.sh scenario incident`, observe Metrics and
the difference between the previous forecast and reality, then run
`./demo-forecast.sh scenario recover` and `./demo-forecast.sh scenario normal`.
The equivalent Windows commands begin `.\\demo-forecast.cmd`.
A scenario persists across container restarts until changed; `reset` deletes
volumes but deliberately preserves the local scenario file. Select `normal`
before a clean demonstration. Forecast results are not instant: their cadence
and the time needed for new observed measurements still apply.

The dedicated `.github/workflows/forecast-prebuilt-smoke.yml` checks the *published latest
images* via the same launcher, validates the initial READY forecast, exercises
the incident and recovery scenarios against real Kafka metrics, and restarts
offline. It is separate from the source-build CI regression job.

Requires Docker Compose **2.24+** (for `!reset` / `!override`); on Linux/macOS
the Bash launcher also needs `curl` and `openssl` for health checks and first-run credentials.
The original `./demo.sh --forecast` / `.\demo.cmd -Forecast` still run the
source-build demo; its forecast-only mode now also waits for full forecast readiness.

## The forecast demo: two live metrics that are forecast from the first minutes

**Start it with nothing to configure:** `./demo.sh --forecast` (Windows: `.\demo.cmd -Forecast`). That is the
stack this demo needs and nothing else — Kafka and the demo topics, Schema Registry, the Explorer with MCP,
PostgreSQL, TimesFM, the live traffic and its seeded history — with every variable defaulted: no LLM key, no
SpectraLLM weights, no agent. `./demo.sh` alone (the whole suite) starts the same overlay among the rest.
The first run builds the Explorer and fetches the pinned TimesFM checkpoint; later runs start in seconds.
`./demo.sh --forecast down | reset | ps | logs [service] | probe` manage it.

The overlay, `compose/demo-forecast.yml`, is what both start. The rest of the demo's topics are
seeded once and then stand still, so a metric over them reads one value for ever and a forecast has
nothing to extend; these five are fed continuously, and their history is written ahead so a forecast does
not wait the two hours (128 recorded points) it would otherwise need.

| Metric | What it reads | Story | What to look at |
| --- | --- | --- | --- |
| `gauge_unshipped_demo_live_orders_minus_shipped` | orders taken and not yet shipped: records in `demo.live.orders` minus those in `demo.live.shipped` | the warehouse falls behind for three hours (about 20 towards 450 orders), then a bulk shipment drains it; a small hourly ripple | warning at 300 and critical at 400: for about half an hour before each, the card says **"Warning expected in ~N min"**, then "Critical expected". **Already forecast** when the page opens |
| `gauge_backlog_demo_live_requests_minus_processed` | records produced to `demo.live.requests` minus those produced to `demo.live.processed` | an hourly cycle between 10 and 130 records | the forecast **follows** the cycle rather than extrapolating a line; the history preview finds the hourly cycle; the warning at 110 is crossed near each peak and the critical at 135 never. **Not forecast yet**: press **Forecast** on its card |
| `gauge_imports_demo_live_imports_minus_indexed` | imports not yet indexed: `demo.live.imports` minus `demo.live.indexed` | a slow, noisy climb from about 25 to 235 over four hours, then a reindex drains it | a **trend with noise on it**: the forecast extends the slope and its 80 % band widens with the distance; warning 180, critical 220. **Not forecast yet** |
| `gauge_events_demo_live_events_minus_archived` | events not yet archived: `demo.live.events` minus `demo.live.archived` | noisy and stationary, around 80 (±30), no cycle | **nothing to announce**: a flat forecast and a wide band, and the warning at 150 never reached — what an uncertain series looks like, and that a forecast is not always news. **Not forecast yet** |
| `gauge_tickets_demo_live_tickets_minus_resolved` | tickets open: `demo.live.tickets` minus `demo.live.resolved` | the level shifts between about 50 and 130 every ninety minutes (a three-hour cycle) | **a change no forecast can see coming**: at each handover the forecast holds the old level and the actual leaves it — the reliability badge drops and *Previous forecast vs actual* shows the miss. Warning 100, critical 140. **Not forecast yet** |

Open **Metrics**: the five cards already draw a curve, the unshipped-orders one with its forecast beside it.
**Forecast all eligible (4)** starts the other four at once — the bulk creation, with each one's own cycle found
from its history.

1. **The unshipped-orders forecast** — a cyan dashed median and its 80 % band continue the curve; the card
   shows the predicted threshold ("Warning expected in ~12 min (≥ 300, median)") when the forecast gets there.
2. **Press Forecast on the backlog card** — nothing to fill in: it is created with the cycle its history
   shows, and its first forecast is computed at the next cycle, one minute later, because the history is
   already there. A card the demo cannot forecast (the raw-SQL examples) keeps a disabled button and says why.
3. **Previous forecast vs actual** in the Metrics Forecast panel, once a forecast has matured (one horizon,
   thirty minutes after the first): the earlier forecast drawn over what then happened, and how it did.

**Why a count, and why these five.** Only a measurement the Explorer records as *complete* can be forecast: a
latency or a consumer delay is recorded as limited-scope (a bounded read of recent rows), and a metric over
raw SQL as unverified, so neither is ever offered a forecast. The difference of two topics' end offsets is
exactly that, and a generator can hold it on any curve with nothing to back-date.

**What is seeded is demo data, and says so.** `demo-forecast-seed` writes the nine hours before the first
observation the Explorer recorded itself, as clones of that observation with their own `collectorRunId`
`demo-seed-<n>`: the same series, unit and scope, distinguishable from what was measured. It never seeds a
metric that was not recorded for real, and never twice. It follows the same two curves the generator
produces (`scripts/demo-forecast/signals.awk`), which depend on the wall clock alone: a stack restarted at
any hour resumes the same curve. The topics themselves do not hold those nine hours — only the history the
forecast reads does. `./demo.sh logs demo-forecast-seed` shows what was done; `./demo.sh reset` removes it.

`docs/check-demo-forecast.py` holds the curves to the thresholds above and the generator to its stubs;
the `forecast-stack` CI job (`ci/demo-forecast.py`) runs the overlay against the real preparer, PostgreSQL
and TimesFM and checks a forecast comes out of each metric.

## The static `demo.metrics.requests` scenarios

The bundled `setup-demo.sh` seeds `demo.metrics.requests` with **180 one-minute aggregate records** spanning the last three hours, plus the existing `demo.iot.sensors` readings. No external credentials or inference model are needed to inspect these Kafka records.

## Start and inspect

```bash
./demo.sh --no-spectra up
# Open http://localhost:8080 → Topics → demo.metrics.requests
# Inspect JSON records and their event_time, phase, requests, errors, latency_ms.
```

For an existing running demo, the one-shot seed marker intentionally prevents replay. To add the new fixture without deleting the existing volumes, run the seeder **once** explicitly:

```bash
docker compose exec -T kafka bash /data/setup-demo.sh kafka:29092
```

**Caution:** the direct seeder replays *all* demo records, including order-flow events; prefer a fresh demo environment when audit counts and duplicate detection must remain reproducible. The direct command assumes the `/data/setup-demo.sh` mount used by the demo stack; check the Compose configuration before running it.

## Scenarios

| Minutes from series start | Phase | Expected behavior | What to verify |
| --- | --- | --- | --- |
| 0–44 | `baseline` | Stable ~90–114 requests/min, 65–89 ms latency, zero errors | Stable GAUGE and throughput |
| 45–104 | `ramp` | Increasing requests and latency | Rising time series and alert threshold |
| 105–114 and 125–139 | `saturation` | ~340–380 requests/min, high latency, 3–7 errors/min | Sustained warning and error-rate signal |
| 115–124 | `error_burst` | ~20% errors | Short critical spike |
| 140–179 | `recovery` | Return to lower load and latency | Recovery after alert |

## Suggested Metrics SQL

The topic records are *minute aggregates*, not individual requests. Use sums rather than `COUNT(*)` to measure requests and errors. The SQL examples assume that the topic is registered as a Flink table with numeric fields `requests`, `errors`, `latency_ms` and an event-time column. Confirm the schema in the Metrics editor first.

**Request volume (GAUGE)**

```sql
SELECT SUM(requests) AS metric_value FROM "demo.metrics.requests"
```

**Total errors (COUNTER-like source, aggregated snapshot)**

```sql
SELECT SUM(errors) AS metric_value FROM "demo.metrics.requests"
```

**Average latency (GAUGE)**

```sql
SELECT AVG(latency_ms) AS metric_value FROM "demo.metrics.requests"
```

**Error percentage (GAUGE)**

```sql
SELECT CASE WHEN SUM(requests) = 0 THEN 0 ELSE 100.0 * SUM(errors) / SUM(requests) END AS metric_value
FROM "demo.metrics.requests"
```

For time-resolved curves and forecasting, use a windowed aggregation on the declared event-time field, respecting the editor's supported Flink SQL syntax. A single aggregate over the entire topic produces only one value and is **not** a forecast-ready historical series. TimesFM additionally requires configured PostgreSQL history and approved series; seeding Kafka alone does not enable it.

## Acceptance checklist

1. Confirm 180 `demo.metrics.requests` records and the five phases.
2. Create and preview request, error, latency and error-rate metrics in the Metrics UI.
3. Check that warning/critical thresholds distinguish baseline, saturation and error burst.
4. Inspect the metric chart and ensure its timestamps and units are clear.
5. If forecasting is configured, collect a durable history before testing **Prepare a forecast**; verify the observed/predicted distinction and any confidence band.
6. Confirm that a metric without sufficient history or an approved forecast source disables or explains the forecast action.

The fixture is deterministic in shape but uses the current time as its origin. It does not generate live traffic after seeding: that is what the live forecast demo above adds, on `demo.live.*` topics of its own so that the audit counts these scenarios rely on do not move.

**The five curves, and the noise.** Three of them were added to show a forecast doing something *else* than
following a ramp or a cycle: a trend with noise, a series with nothing to say, a series that changes
level. Their noise is not random: `noise()` in `signals.awk` is a function of the minute alone, so the
seeded past and the live future read the same value at the same minute, in the Kafka image's awk and in
the PostgreSQL image's, and a restart resumes the same signal.

**The two topics of a pair share their keys.** The k-th record of `demo.live.shipped` carries the key of
the k-th record of `demo.live.orders` (`orders-1234`), and its `event_time` is when it was written — so
**Stream Flow** traces `orders-1` through both topics and the time between them is how long that order
waited. The drawer's *Create metrics* then has a real hop to propose a latency for. The same holds for
the four other pairs (`requests-N`, `imports-N`, `events-N`, `tickets-N`).
