# Metrics demo test scenarios

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

The fixture is deterministic in shape but uses the current time as its origin. It does not generate live traffic after seeding.
