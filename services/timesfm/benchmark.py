# SPDX-License-Identifier: AGPL-3.0-or-later
"""Real CPU-only benchmark. Each setting is isolated; pinned weights must already be cached.
Run: uv run python benchmark.py --cache-dir /models --output benchmark.json
No download, inference queue, or GPU decision is implicit.
"""
import argparse
import json
import math
import os
import platform
import resource
import subprocess
import sys
import time
from pathlib import Path


def percentile(values, fraction):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


def worker(args):
    from kex_timesfm.contract import ForecastRequest, MODEL_ID, MODEL_REVISION
    from kex_timesfm.model import TimesFmModel
    started = time.monotonic()
    model = TimesFmModel(args.cache_dir, args.threads)
    load = time.monotonic() - started
    request = ForecastRequest.model_validate({
        "schemaVersion": 1, "requestId": "00000000-0000-0000-0000-000000000001", "horizonPoints": 60,
        "series": [{"seriesId": format(i, "064x"),
                    "values": [10 + math.sin(j / 12) for j in range(512)]} for i in range(args.batch)],
    })
    started = time.monotonic()
    central, quantiles = model.forecast(request)
    first = time.monotonic() - started
    assert central.shape == (args.batch, 60) and quantiles.shape == (args.batch, 60, 10)
    samples = []
    for _ in range(args.runs):
        started = time.monotonic()
        model.forecast(request)
        samples.append(time.monotonic() - started)
    return {"threads": args.threads, "batch": args.batch, "context": 512, "horizon": 60,
            "loadSeconds": load, "firstInferenceSeconds": first, "coldStartSeconds": load + first,
            "p50Seconds": percentile(samples, .50), "p95Seconds": percentile(samples, .95),
            "peakRssMiB": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024,
            "modelId": MODEL_ID, "modelRevision": MODEL_REVISION, "samplesSeconds": samples}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache-dir", required=True)
    parser.add_argument("--output", default="benchmark.json")
    parser.add_argument("--runs", type=int, default=20)
    parser.add_argument("--batch", type=int, default=1)
    parser.add_argument("--threads", type=int, choices=(1, 4, 8))
    parser.add_argument("--worker", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args()
    if not 5 <= args.runs <= 100 or not 1 <= args.batch <= 4:
        parser.error("runs must be 5..100 and batch 1..4")
    if args.worker:
        if args.threads is None:
            parser.error("worker requires threads")
        print(json.dumps(worker(args)))
        return
    report = {"platform": platform.platform(), "machine": platform.machine(),
              "availableCpuCount": os.cpu_count(), "python": platform.python_version(), "results": []}
    for threads in (1, 4, 8):
        command = [sys.executable, __file__, "--worker", "--threads", str(threads), "--cache-dir", args.cache_dir,
                   "--runs", str(args.runs), "--batch", str(args.batch)]
        started = time.monotonic()
        try:
            result = subprocess.run(command, capture_output=True, text=True, timeout=900, check=True)
            row = json.loads(result.stdout.strip().splitlines()[-1])
            row["processWallSeconds"] = time.monotonic() - started
            row["status"] = "MEASURED"
        except (subprocess.SubprocessError, ValueError) as error:
            row = {"threads": threads, "status": "UNMEASURED", "failure": type(error).__name__}
        report["results"].append(row)
        Path(args.output).write_text(json.dumps(report, indent=2) + "\n")
    if any(row["status"] != "MEASURED" for row in report["results"]):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
