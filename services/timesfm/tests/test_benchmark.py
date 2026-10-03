# SPDX-License-Identifier: AGPL-3.0-or-later
import importlib.util
import json
import subprocess
import sys
from pathlib import Path

import pytest

spec = importlib.util.spec_from_file_location("benchmark", Path(__file__).parents[1] / "benchmark.py")
benchmark = importlib.util.module_from_spec(spec)
spec.loader.exec_module(benchmark)


def test_nearest_rank_percentiles_use_all_samples():
    assert benchmark.percentile(list(reversed(range(1, 21))), .5) == 10
    assert benchmark.percentile(list(range(1, 21)), .95) == 19


def test_failed_workers_remain_unmeasured_and_exit_nonzero(monkeypatch, tmp_path):
    output = tmp_path / "report.json"
    monkeypatch.setattr(sys, "argv", ["benchmark.py", "--cache-dir", str(tmp_path), "--output", str(output)])
    calls = []

    def fail(command, **kwargs):
        calls.append(command)
        raise subprocess.CalledProcessError(1, command, stderr="private dependency details")

    monkeypatch.setattr(benchmark.platform, "platform", lambda: "test-platform")
    monkeypatch.setattr(benchmark.subprocess, "run", fail)
    with pytest.raises(SystemExit) as stopped:
        benchmark.main()
    assert stopped.value.code == 1
    report = json.loads(output.read_text())
    assert [row["threads"] for row in report["results"]] == [1, 4, 8]
    assert all(row["status"] == "UNMEASURED" for row in report["results"])
    assert "private dependency details" not in output.read_text()
    assert len(calls) == 3


def test_rejects_unbounded_workload_before_spawning(monkeypatch, tmp_path):
    monkeypatch.setattr(sys, "argv", ["benchmark.py", "--cache-dir", str(tmp_path), "--runs", "101"])
    with pytest.raises(SystemExit) as stopped:
        benchmark.main()
    assert stopped.value.code == 2
