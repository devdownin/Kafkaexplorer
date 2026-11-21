#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
"""CI-only: the forecast demo's overlay against the real stack (Kafka, PostgreSQL, TimesFM, Explorer).

Run after `ci/forecast-stack.py`, in the same disposable `forecast-ci` Compose project. It starts
`compose/demo-forecast.yml` and asserts what `docs/metrics-demo-scenarios.md` promises of the demo:

  * the live generator holds the five topic pairs on their curves and the seeder creates the five metrics;
  * nine hours of history are written for each series — marked `demo-seed-<n>`, beside what the
    Explorer recorded itself — and the real preparer and model accept them;
  * the unshipped-orders metric's forecast is already running and produces a TimesFM result;
  * the backlog metric shows its hourly cycle, and the Forecast button's request for it (the one
    the page sends) is accepted and produces a result too;
  * the three others (a noisy climb, a noisy stationary series, a level that shifts) are forecast
    candidates with a history the preparer accepts, as the Metrics page's "Forecast all eligible" needs.

The seeded history is demo data by design, never a claim of realised model accuracy.
"""
import json
import re
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
COMPOSE = ['docker', 'compose', '-p', 'forecast-ci', '--env-file', '.forecast-stack/.env',
           '-f', 'docker-compose.yml', '-f', 'compose/forecasts.yml', '-f', 'compose/image.yml', '-f', 'compose/ci.yml',
           '-f', 'compose/demo-forecast.yml']
BASE = 'http://127.0.0.1:8080'
UNSHIPPED = 'demo-live-unshipped'
BACKLOG = 'demo-live-backlog'
CREEP = 'demo-live-creep'
JITTER = 'demo-live-jitter'
SHIFT = 'demo-live-shift'
# The curve each metric is held on, and how far a reading may be from it: the noisy ones change value
# at every minute, by up to twice their noise, so a reading a tick behind can be that far off.
CURVES = {UNSHIPPED: ('unshipped(t)', 25), BACKLOG: ('backlog(t)', 25), CREEP: ('creep(t)', 40),
          JITTER: ('jitter(t)', 75), SHIFT: ('shift(t)', 40)}


def compose(*args):
    return subprocess.run(COMPOSE + list(args), cwd=ROOT, text=True, stdout=subprocess.PIPE, check=True, timeout=300).stdout


def api(path, body=None):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(BASE + path, data=data, headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=45) as response:
        raw = response.read()
        return json.loads(raw) if raw else None


def wait_for(read, accept, label, seconds=300):
    deadline = time.monotonic() + seconds
    last = None
    while time.monotonic() < deadline:
        try:
            last = read()
            if accept(last):
                print(label + ': OK', flush=True)
                return last
        except (urllib.error.URLError, ConnectionError, TimeoutError, json.JSONDecodeError):
            pass
        time.sleep(3)
    raise AssertionError(f'{label} did not complete within {seconds} s (last: {str(last)[:300]})')


def psql(query):
    return compose('exec', '-T', 'forecast-postgres', 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'forecasts', '-d', 'forecasts', '-At', '-c', query).strip()


def series_row(metric_id):
    return next((row for row in api('/api/forecasts')['series'] if row['metricId'] == metric_id), None)


def curve(expression):
    """The curve the generator follows, now: the same awk functions the generator and the seeder read."""
    program = (ROOT / 'scripts/demo-forecast/signals.awk').read_text() + f'\nBEGIN {{ printf "%.3f", {expression} }}'
    return float(subprocess.run(['awk', '-v', f't={int(time.time())}', program], capture_output=True, text=True, check=True).stdout)


def timesfm_result(metric_id):
    row = series_row(metric_id)
    result = row and row.get('result')
    return result if result and result['state'] == 'READY' and result['strategy'] == 'TIMESFM' and result['forecast'] else None


def main():
    compose('up', '-d', 'demo-live', 'demo-forecast-seed')
    print('Demo overlay started', flush=True)

    # The seeder is a one-shot: wait for it to finish, and for it to say it succeeded.
    def seeder():
        out = compose('ps', '-a', '--format', 'json', 'demo-forecast-seed')
        rows = [json.loads(line) for line in out.splitlines() if line.strip().startswith('{')] or \
               (json.loads(out) if out.strip().startswith('[') else [])
        return rows[0] if rows else None
    done = wait_for(seeder, lambda row: row is not None and row['State'] == 'exited', 'Seeder finished', 900)
    logs = compose('logs', '--no-color', 'demo-forecast-seed')
    assert done['ExitCode'] == 0, 'The seeder failed:\n' + logs[-1500:]
    for metric in CURVES:
        assert metric in logs, f'The seeder says nothing of {metric}:\n' + logs[-1500:]

    # What the seeder wrote, beside what the Explorer recorded itself.
    for metric in CURVES:
        seeded = int(psql("SELECT COUNT(*) FROM kex_metric_observation_v1 WHERE payload::jsonb->>'metricId'='%s' "
                          "AND payload::jsonb->>'collectorRunId' LIKE 'demo-seed-%%'" % metric))
        real = int(psql("SELECT COUNT(*) FROM kex_metric_observation_v1 WHERE payload::jsonb->>'metricId'='%s' "
                        "AND payload::jsonb->>'collectorRunId' NOT LIKE 'demo-seed-%%'" % metric))
        assert seeded >= 500 and real >= 1, f'{metric}: {seeded} seeded and {real} recorded observations'
        print(f'{metric}: {seeded} seeded + {real} recorded observations: OK', flush=True)

    # Both are the template whose source the Explorer can name, and the live value is the curve the
    # history was written from: the seeded past and the live future are one signal.
    metrics = {row['id']: row for row in api('/api/metrics')}
    assert all(metric in metrics for metric in CURVES), 'The seeder did not create the five metrics'
    for metric, (expression, tolerance) in CURVES.items():
        assert metrics[metric]['templateType'] == 'TOPIC_COUNT_DELTA', f'{metric} is not a count-delta template'
        api(f'/api/metrics/{metric}/refresh', {})
        live = next(row for row in api('/api/metrics') if row['id'] == metric)['lastValue']
        expected = curve(expression)
        # Up to a tick behind a curve that moves a few records a minute, and a producer that batches.
        assert live is not None and abs(live - expected) <= tolerance, f'{metric} reads {live} where the curve says {expected:.0f}'
        print(f'{metric} reads {live:g} (curve {expected:.0f}): OK', flush=True)

    # The unshipped-orders forecast is already running, from the seeded history, and TimesFM answers it.
    wait_for(lambda: timesfm_result(UNSHIPPED), lambda value: value is not None, 'The unshipped-orders forecast has a TimesFM result', 600)

    # The backlog's is the Forecast button: the page's own request, the cycle its history shows.
    catalogue = api('/api/forecasts/candidates')
    candidate = next(m for m in catalogue['metrics'] if m['metricId'] == BACKLOG)
    assert candidate['eligible'] and candidate['enrolled'], f'The backlog is not a forecast candidate: {candidate}'
    preview = api(f'/api/forecasts/candidates/{BACKLOG}/history?stepMillis=60000')
    assert preview['status'] == 'READY' and preview['observedPoints'] >= 128, f'The backlog history is not ready: {preview}'
    assert preview['suggestedSeasonality'] == 'HOURLY', f"The hourly cycle was not found: {preview['suggestedSeasonality']}"
    print('The backlog history shows its hourly cycle: OK', flush=True)
    assert series_row(BACKLOG) is None, 'The backlog forecast is left to the Forecast button'
    api('/api/forecasts/series', {'metricId': BACKLOG, 'definitionVersion': candidate['definitionVersion'], 'environment': 'local',
        'unit': candidate['unit'], 'clusterId': catalogue['clusterId'], 'collectorId': catalogue['collectorId'],
        'topics': candidate['topics'], 'groups': candidate['groups'], 'stepMillis': candidate['suggestedStepMillis'],
        'horizon': 30, 'threshold': None, 'direction': 'ABOVE', 'confirmed': True, 'seasonality': 'HOURLY'})
    wait_for(lambda: timesfm_result(BACKLOG), lambda value: value is not None, 'The backlog forecast has a TimesFM result', 600)

    # The three others are left to the Forecast button too: each is a candidate whose history the
    # preparer accepts. (Their forecasts are not asked here — what they show is the demo's, not the model's.)
    catalogue = api('/api/forecasts/candidates')
    for metric in (CREEP, JITTER, SHIFT):
        candidate = next(m for m in catalogue['metrics'] if m['metricId'] == metric)
        assert candidate['eligible'] and candidate['enrolled'], f'{metric} is not a forecast candidate: {candidate}'
        preview = api(f'/api/forecasts/candidates/{metric}/history?stepMillis=60000')
        assert preview['status'] == 'READY' and preview['observedPoints'] >= 128, f'{metric} history is not ready: {preview}'
        assert series_row(metric) is None, f'{metric} is left to the Forecast button'
        print(f'{metric} is a forecast candidate with {preview["observedPoints"]} points: OK', flush=True)
    print('Forecast demo smoke passed; the seeded history is demo data, not a measure of model accuracy', flush=True)


if __name__ == '__main__':
    main()
