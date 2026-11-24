#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
"""Validate the published-image launcher's scenarios against *live* Kafka metrics.

Runs only after ./demo-forecast.sh up / verify in the disposable prebuilt CI stack.
No build tools are used by the launcher. Use the application's own metric refresh
endpoint rather than assuming how quickly Kafka producers flush their records.
"""
import json
import pathlib
import subprocess
import time
import urllib.error
import urllib.request


def launcher(*args):
    return subprocess.run(["./demo-forecast.sh", *args], text=True, capture_output=True,
                          check=True, timeout=45).stdout.strip()


def read_metric(metric_id):
    base = "http://127.0.0.1:8080"
    request = urllib.request.Request(base + "/api/metrics/" + metric_id + "/refresh",
                                     data=b"{}", headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=45):
        pass
    with urllib.request.urlopen(base + "/api/metrics", timeout=10) as response:
        metrics = json.load(response)
    return next(row["lastValue"] for row in metrics if row["id"] == metric_id)


def wait_for_verified_scenario(mode, seconds=180):
    """Do not start the UI timeout until the generator has verified the Kafka offsets."""
    path = pathlib.Path(".forecast-stack/config/demo-control/demo-scenario-applied.conf")
    deadline = time.monotonic() + seconds
    last = None
    while time.monotonic() < deadline:
        if path.exists():
            last = path.read_text().strip()
            if last.startswith(f"v2|{mode}|"):
                return
        time.sleep(5)
    raise AssertionError(f"generator never verified scenario {mode} in {seconds}s (last receipt={last})")


def assert_live(label, metric_id, check, seconds=120):
    deadline = time.monotonic() + seconds
    last = None
    while time.monotonic() < deadline:
        try:
            last = read_metric(metric_id)
            if last is not None and check(last):
                print(f"{label}: {last:g} (live Kafka metric): OK", flush=True)
                return
        except (urllib.error.URLError, TimeoutError, ValueError, StopIteration):
            pass
        time.sleep(5)
    raise AssertionError(f"{label} did not reach its scenario target in {seconds}s (last={last})")


def scenario(mode):
    print(launcher("scenario", mode), flush=True)
    actual = launcher("scenario")
    assert actual == mode, f"Scenario control not persisted: expected {mode}, got {actual}"


def main():
    try:
        scenario("overload")
        wait_for_verified_scenario("overload")
        assert_live("overloaded orders", "demo-live-unshipped", lambda value: value >= 460)
        assert_live("overloaded requests", "demo-live-backlog", lambda value: value >= 165)
        scenario("incident")
        wait_for_verified_scenario("incident")
        assert_live("incident tickets", "demo-live-shift", lambda value: value >= 175)
        assert_live("incident events", "demo-live-jitter", lambda value: value >= 155)
        scenario("recover")
        wait_for_verified_scenario("recover")
        assert_live("recovered requests", "demo-live-backlog", lambda value: value <= 40)
        assert_live("recovered tickets", "demo-live-shift", lambda value: value <= 70)
        scenario("seasonal")
        # Unlike static overrides, seasonality changes by wall-clock phase; the
        # scenario's control and live producer remain attached across that switch.
        time.sleep(20)
        launcher("verify")
        print("Enhanced seasonality selected and demo remained healthy: OK", flush=True)
    finally:
        scenario("normal")
    launcher("verify")
    print("Prebuilt forecast scenarios: OK", flush=True)


if __name__ == "__main__":
    main()
