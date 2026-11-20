#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
"""Hold the forecast demo to the story it tells.

`scripts/demo-forecast/` makes the demo's two live metrics (orders not yet shipped, degrading towards
their threshold; a request backlog on an hourly cycle) forecastable on the first visit. Three claims hold it up,
and each was a comment before it was a check:

1.  **The curves say what the docs say.** `signals.awk` is the one definition of both signals, read
    by the live generator and by the history seeder. The unshipped orders must reach the metric's warning
    and then its critical threshold once per three-hour cycle and then drop back; the backlog must
    repeat every hour, cross its warning near each peak and never reach its critical. The
    thresholds are not restated here: they are read from the two metric definitions in `seed.sh`,
    so changing one side without the other fails.
2.  **The generator's plumbing works.** `live.sh` is run for a few ticks against stub Kafka CLIs
    (the same technique as the seeder's decisions): each pair of topics is brought to its curve
    from whichever side is short.
3.  **Both platforms start it.** `demo.sh` and `demo.ps1` are twins that nothing else compares, so a
    file added to one and not the other is a stack that differs by platform.

No broker, no PostgreSQL, no build: awk, sh and the repository's own files. The history seeder's SQL
is exercised against a real PostgreSQL by the forecast-stack CI job, not here.
"""
import pathlib
import re
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
DIR = ROOT / 'scripts/demo-forecast'
SIGNALS = (DIR / 'signals.awk').read_text()
problems = []


def evaluate(expression, t):
    """The awk expression at epoch second t, through the functions of signals.awk."""
    program = SIGNALS + f'\nBEGIN {{ printf "%.6f", {expression} }}'
    out = subprocess.run(['awk', '-v', f't={t}', program], capture_output=True, text=True, check=True).stdout
    return float(out)


def thresholds(metric_id):
    seed = (DIR / 'seed.sh').read_text()
    block = re.search(r'\{"id":"' + metric_id + r'".*?\n(?:JSON|\})', seed, re.S)
    match = block and re.search(r'"warningThreshold":([0-9.]+),"criticalThreshold":([0-9.]+)', block.group(0))
    if not match:
        problems.append(f'seed.sh defines no warning/critical thresholds for {metric_id}')
        return None
    return float(match.group(1)), float(match.group(2))


def upward_crossings(series, level):
    return sum(1 for a, b in zip(series, series[1:]) if a < level <= b)


# A day's worth, every minute, from an arbitrary epoch: the claims must not depend on the hour.
START = 1_700_000_040
unshipped_pts = [(START + 60 * i, evaluate('unshipped(t)', START + 60 * i)) for i in range(0, 6 * 180)]
backlog_pts = [(START + 60 * i, evaluate('backlog(t)', START + 60 * i)) for i in range(0, 6 * 60)]
unshipped = [v for _, v in unshipped_pts]
backlog = [v for _, v in backlog_pts]

un = thresholds('demo-live-unshipped')
if un:
    warning, critical = un
    cycles = 6
    if upward_crossings(unshipped, warning) != cycles:
        problems.append(f'the unshipped orders must cross their {warning:g} warning once per 3 h cycle '
                        f'({cycles} cycles read, {upward_crossings(unshipped, warning)} crossings)')
    if upward_crossings(unshipped, critical) != cycles:
        problems.append(f'the unshipped orders must cross their {critical:g} critical once per 3 h cycle '
                        f'({cycles} cycles read, {upward_crossings(unshipped, critical)} crossings)')
    if min(unshipped) < 0:
        problems.append('the unshipped orders go negative: orders cannot ship before they are taken')
    if max(a - b for a, b in zip(unshipped, unshipped[1:])) < 200:
        problems.append('the unshipped orders never drain: a build-up with no recovery is a trend, not a cycle')

bk = thresholds('demo-live-backlog')
if bk:
    warning, critical = bk
    if upward_crossings(backlog, warning) != 6:
        problems.append(f'the backlog must cross its {warning:g} warning once per hour '
                        f'({upward_crossings(backlog, warning)} crossings in 6 h)')
    if max(backlog) >= critical:
        problems.append(f'the backlog reaches its {critical:g} critical ({max(backlog):.0f}): it is a cycle to follow, not a breach')
    if min(backlog) < 0:
        problems.append('the backlog goes negative: records cannot be consumed before they are produced')
period_error = max(abs(evaluate('backlog(t)', START + s) - evaluate('backlog(t)', START + s + 3600)) for s in range(0, 3600, 300))
if period_error > 0.01:
    problems.append(f'the backlog is not hourly (differs by {period_error:.2f} after 3600 s)')

# 2. The generator, against stub CLIs: one tick of the loop is enough to see both halves.
with tempfile.TemporaryDirectory() as tmp:
    tmp = pathlib.Path(tmp)
    (tmp / 'bin').mkdir()
    (tmp / 'out').mkdir()
    stubs = {
        'kafka-broker-api-versions.sh': 'exit 0',
        'kafka-topics.sh': 'exit 0',
        'kafka-console-producer.sh': 'while [ $# -gt 0 ]; do [ "$1" = --topic ] && t=$2; shift; done\n'
                                     'touch "$OUT/$t"\n'
                                     'while IFS= read -r line; do printf "%s\\n" "$line" >> "$OUT/$t"; done',
        'kafka-get-offsets.sh': 'for f in "$OUT"/*; do [ -f "$f" ] && printf "%s:0:%s\\n" "$(basename "$f")" "$(wc -l < "$f")"; done; exit 0',
    }
    for name, body in stubs.items():
        path = tmp / 'bin' / name
        path.write_text('#!/bin/sh\n' + body + '\n')
        path.chmod(0o755)
    env = {'PATH': '/usr/bin:/bin', 'OUT': str(tmp / 'out'), 'KAFKA_BIN': str(tmp / 'bin'), 'DEMO_LIVE_TICK': '1'}
    try:
        subprocess.run(['sh', str(DIR / 'live.sh'), 'stub:9092'], env=env, capture_output=True, text=True, timeout=5)
    except subprocess.TimeoutExpired:
        pass  # the loop never ends on its own; five seconds is the run
    # The producers drain their pipes after the generator is stopped.
    import time
    time.sleep(1)

    def lines(topic):
        path = tmp / 'out' / topic
        return path.read_text().splitlines() if path.exists() else []

    now = int(time.time())
    for left, right, curve in (('demo.live.orders', 'demo.live.shipped', 'unshipped(t)'),
                               ('demo.live.requests', 'demo.live.processed', 'backlog(t)')):
        gap = len(lines(left)) - len(lines(right))
        target = evaluate(curve, now)
        # The curve moves a few records a minute while it runs, and the run is a few seconds.
        if abs(gap - target) > 12:
            problems.append(f'live.sh left {left} - {right} at {gap} where the curve says {target:.0f}')

# 3. Both platforms, and the compose registry, name the overlay.
for name in ('demo.sh', 'demo.ps1'):
    if 'compose/demo-forecast.yml' not in (ROOT / name).read_text():
        problems.append(f'{name} does not layer compose/demo-forecast.yml: the two scripts must start the same stack')
if 'compose/demo-forecast.yml' not in (ROOT / '.github/workflows/ci.yml').read_text():
    problems.append('ci.yml does not name compose/demo-forecast.yml among the overlays it resolves')
overlay = (ROOT / 'compose/demo-forecast.yml').read_text()
for script in ('live.sh', 'seed.sh', 'signals.awk'):
    if not (DIR / script).is_file():
        problems.append(f'scripts/demo-forecast/{script} is missing')
for script in ('live.sh', 'seed.sh'):
    if f'/tmp/demo-forecast/{script}' not in overlay:
        problems.append(f'compose/demo-forecast.yml does not run {script}')
    if subprocess.run(['sh', '-n', str(DIR / script)], capture_output=True).returncode:
        problems.append(f'scripts/demo-forecast/{script} does not parse')

if problems:
    print('The forecast demo no longer tells the story it documents:\n')
    for p in problems:
        print('  ✗', p)
    sys.exit(1)
print('The forecast demo holds: both curves cross what they should, the generator holds both pairs on '
      'their curves, and both platforms layer the overlay.')
