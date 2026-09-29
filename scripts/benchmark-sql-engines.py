#!/usr/bin/env python3
"""Compare two already-provisioned SQL slices through POST /api/query/run-sync.

The caller supplies equivalent SQL for the direct and bounded Flink tables. This script
does not create data or silently change a table. Use prepare-sql-benchmark.py and pass its
manifest to verify the exact fixture and both engines before collecting timings.
"""

import argparse
import concurrent.futures
import json
import math
import os
import threading
import time
import urllib.request
import uuid
from pathlib import Path


def post(url, payload, token, timeout):
    data = json.dumps(payload).encode()
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, data=data, headers=headers, method="POST")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.load(response)


def percentile(samples, p):
    ordered = sorted(samples)
    return ordered[math.ceil(len(ordered) * p / 100) - 1] if ordered else None


def prometheus_sample(url, token):
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=2) as response:
        lines = response.read().decode().splitlines()
    heap = 0.0
    threads = None
    for line in lines:
        if line.startswith("jvm_memory_used_bytes{") and 'area="heap"' in line:
            heap += float(line.rsplit(" ", 1)[-1])
        elif line.startswith("jvm_threads_live_threads "):
            threads = float(line.rsplit(" ", 1)[-1])
    return heap or None, threads


def monitor_metrics(url, token, stop, samples):
    while not stop.is_set():
        try:
            samples.append(prometheus_sample(url, token))
        except (OSError, ValueError):
            pass
        stop.wait(0.25)


def run_one(base, sql, mode, args, token):
    payload = {"sql": sql, "maxRows": args.rows, "timeout": args.timeout_ms,
               "queryId": str(uuid.uuid4()), "flinkOnly": mode == "FLINK",
               "directRead": mode == "KAFKA_DIRECT"}
    if mode == "KAFKA_DIRECT":
        payload["readMode"] = args.direct_offset
    timer = None
    if args.cancel_after_ms:
        def cancel():
            try:
                post(base + "/api/query/cancel/" + payload["queryId"], {}, token, 5)
            except (OSError, ValueError):
                pass
        timer = threading.Timer(args.cancel_after_ms / 1000, cancel)
        timer.start()
    started = time.perf_counter()
    try:
        result = post(base + "/api/query/run-sync", payload, token, args.timeout_ms / 1000 + 15)
        return {"clientMs": round((time.perf_counter() - started) * 1000, 2),
                "serverMs": result.get("durationMs"), "rows": len(result.get("rows") or []),
                "recordsFetched": (result.get("scanCoverage") or {}).get("recordsFetched"),
                "coverage": (result.get("scanCoverage") or {}).get("status"),
                "sourceCompleted": (result.get("changelog") or {}).get("sourceCompleted"),
                "error": result.get("error")}
    except (OSError, ValueError) as error:
        return {"clientMs": round((time.perf_counter() - started) * 1000, 2),
                "error": str(error)}
    finally:
        if timer:
            timer.cancel()


def summarise(results, telemetry):
    successful = [r for r in results if not r.get("error")]
    client = [r["clientMs"] for r in successful]
    server = [r["serverMs"] for r in successful if isinstance(r.get("serverMs"), (int, float))]
    heap = [h for h, _ in telemetry if h is not None]
    threads = [t for _, t in telemetry if t is not None]
    return {"runs": len(results), "successes": len(successful),
            "errors": [r["error"] for r in results if r.get("error")],
            "cancellationRate": sum("cancel" in str(r.get("error", "")).lower() for r in results) / len(results),
            "clientMs": {"p50": percentile(client, 50), "p95": percentile(client, 95)},
            "serverMs": {"p50": percentile(server, 50), "p95": percentile(server, 95)},
            "recordsFetched": sorted(set(r["recordsFetched"] for r in successful
                                         if r.get("recordsFetched") is not None)),
            "coverage": sorted(set(r["coverage"] for r in successful if r.get("coverage"))),
            "sourceCompleted": sorted(set(r["sourceCompleted"] for r in successful
                                          if r.get("sourceCompleted") is not None)),
            "heapPeakBytes": max(heap, default=None), "liveThreadsPeak": max(threads, default=None),
            "telemetrySamples": len(telemetry)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True, help="Explorer base URL")
    parser.add_argument("--flink-sql-file", required=True, type=Path)
    parser.add_argument("--direct-sql-file", required=True, type=Path)
    parser.add_argument("--flink-explain-sql-file", type=Path,
                        help="Optional EXPLAIN query; measures a planning proxy, not pure planner time")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--dataset-records", required=True, type=int)
    parser.add_argument("--catalog-topics", required=True, type=int)
    parser.add_argument("--fixture-manifest", type=Path,
                        help="Verified manifest from prepare-sql-benchmark.py")
    parser.add_argument("--runs", type=int, default=30)
    parser.add_argument("--warmup", type=int, default=3)
    parser.add_argument("--concurrency", type=int, nargs="+", default=[1, 8])
    parser.add_argument("--rows", type=int, default=1000)
    parser.add_argument("--timeout-ms", type=int, default=30000)
    parser.add_argument("--cancel-after-ms", type=int,
                        help="Separate cancellation experiment: request Stop after this delay")
    parser.add_argument("--direct-offset", choices=["earliest-offset", "latest-offset"],
                        default="earliest-offset")
    args = parser.parse_args()
    if min(args.runs, args.rows, args.timeout_ms, args.dataset_records,
           args.catalog_topics, *args.concurrency) < 1:
        parser.error("runs, rows, dataset size, topic count and concurrency must be positive")
    if args.warmup < 0:
        parser.error("warmup must be nonnegative")
    if args.cancel_after_ms is not None and args.cancel_after_ms < 1:
        parser.error("cancel-after-ms must be positive")
    token = os.environ.get("KEX_BENCH_TOKEN")
    base = args.url.rstrip("/")
    sql = {"FLINK": args.flink_sql_file.read_text().strip(),
           "KAFKA_DIRECT": args.direct_sql_file.read_text().strip()}
    if not all(sql.values()):
        parser.error("SQL files must not be empty")
    fixture = None
    if args.fixture_manifest:
        fixture = json.loads(args.fixture_manifest.read_text())
        if (fixture["records"] != args.dataset_records
                or fixture["topics"] != args.catalog_topics
                or any(statement != fixture["sql"] for statement in sql.values())
                or args.direct_offset != "earliest-offset"):
            parser.error("fixture size, SQL or direct offset differs from verified manifest")
        for mode in ("KAFKA_DIRECT", "FLINK"):
            probe = run_one(base, sql[mode], mode, args, token)
            if probe.get("error") or probe.get("rows") != 0:
                parser.error(f"{mode} fixture preflight failed: {probe}")
            if mode == "KAFKA_DIRECT" and (probe["recordsFetched"] != fixture["records"]
                                            or probe["coverage"] != "COMPLETE"):
                parser.error(f"direct scan did not cover the exact fixture: {probe}")
    report = {"datasetRecords": args.dataset_records, "catalogTopics": args.catalog_topics,
              "fixture": fixture,
              "note": "Exact fixture preflight passed" if fixture else
                      "Labels supplied by caller; provision and verify the same offset slice.",
              "results": {}}
    for concurrency in args.concurrency:
        for mode in ("KAFKA_DIRECT", "FLINK"):
            for _ in range(args.warmup):
                run_one(base, sql[mode], mode, args, token)
            samples = []
            stop = threading.Event()
            monitor = threading.Thread(target=monitor_metrics,
                args=(base + "/actuator/prometheus", token, stop, samples), daemon=True)
            monitor.start()
            with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
                results = list(pool.map(lambda _: run_one(base, sql[mode], mode, args, token),
                                        range(args.runs)))
            stop.set()
            monitor.join(timeout=3)
            report["results"][f"{mode}/{concurrency}"] = summarise(results, samples)
    if args.flink_explain_sql_file:
        explain_sql = args.flink_explain_sql_file.read_text().strip()
        if not explain_sql.upper().startswith("EXPLAIN "):
            parser.error("flink-explain-sql-file must contain an EXPLAIN statement")
        results = [run_one(base, explain_sql, "FLINK", args, token) for _ in range(args.runs)]
        report["explainProxy"] = {"note": "End-to-end EXPLAIN duration includes HTTP and runtime setup.",
                                  **summarise(results, [])}
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(args.output)


if __name__ == "__main__":
    main()
