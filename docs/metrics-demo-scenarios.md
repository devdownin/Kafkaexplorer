# Metrics demo test scenarios

## The forecast demo: two live metrics that are forecast from the first minutes

**Start it with nothing to configure:** `./demo.sh --forecast` (Windows: `.\demo.cmd -Forecast`). That is the
stack this demo needs and nothing else — Kafka and the demo topics, Schema Registry, the Explorer with MCP,
PostgreSQL, TimesFM, the live traffic and its seeded history — with every variable defaulted: no LLM key, no
SpectraLLM weights, no agent. `./demo.sh` alone (the whole suite) starts the same overlay among the rest.
The first run builds the Explorer and fetches the pinned TimesFM checkpoint; later runs start in seconds.
`./demo.sh --forecast down | reset | ps | logs [service] | probe` manage it.

The overlay, `compose/demo-forecast.yml`, is what both start. The rest of the demo's topics are
seeded once and then stand still, so a metric over them reads one value for ever and a forecast has
nothing to extend; these two are fed continuously, and their history is written ahead so a forecast does
not wait the two hours (128 recorded points) it would otherwise need.

| Metric | What it reads | Story | What to look at |
| --- | --- | --- | --- |
| `gauge_unshipped_demo_live_orders_minus_shipped` | orders taken and not yet shipped: records in `demo.live.orders` minus those in `demo.live.shipped` | the warehouse falls behind for three hours (about 20 towards 450 orders), then a bulk shipment drains it; a small hourly ripple | warning at 300 and critical at 400: for about half an hour before each, the card says **"Warning expected in ~N min"**, then "Critical expected". **Already forecast** when the page opens |
| `gauge_backlog_demo_live_requests_minus_processed` | records produced to `demo.live.requests` minus those produced to `demo.live.processed` | an hourly cycle between 10 and 130 records | the forecast **follows** the cycle rather than extrapolating a line; the history preview finds the hourly cycle; the warning at 110 is crossed near each peak and the critical at 135 never. **Not forecast yet**: press **Forecast** on its card |

Open **Metrics**: both cards already draw a curve, the unshipped-orders one with its forecast beside it.

1. **The unshipped-orders forecast** — a cyan dashed median and its 80 % band continue the curve; the card
   shows the predicted threshold ("Warning expected in ~12 min (≥ 300, median)") when the forecast gets there.
2. **Press Forecast on the backlog card** — nothing to fill in: it is created with the cycle its history
   shows, and its first forecast is computed at the next cycle, one minute later, because the history is
   already there. A card the demo cannot forecast (the raw-SQL examples) keeps a disabled button and says why.
3. **Previous forecast vs actual** in the Metrics Forecast panel, once a forecast has matured (one horizon,
   thirty minutes after the first): the earlier forecast drawn over what then happened, and how it did.

**Why a count, and why these two.** Only a measurement the Explorer records as *complete* can be forecast: a
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
