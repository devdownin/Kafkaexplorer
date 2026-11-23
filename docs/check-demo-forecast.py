#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
"""Hold the forecast demo to the story it tells.

`scripts/demo-forecast/` makes the demo's five live metrics (orders not yet shipped, degrading towards
their threshold; a request backlog on an hourly cycle; imports creeping up with noise; a stationary noisy
series; a level that shifts) forecastable on the first visit. Three claims hold it up, and each was a
comment before it was a check:

1.  **The curves say what the docs say.** `signals.awk` is the one definition of both signals, read
    by the live generator and by the history seeder. The unshipped orders must reach the metric's warning
    and then its critical threshold once per three-hour cycle and then drop back; the backlog must
    repeat every hour, cross its warning near each peak and never reach its critical. The three
    others each show one thing a forecast does differently — a noisy climb that reaches its thresholds
    every four hours, a noisy series that never nears its warning, a level that shifts every three
    hours — and the noise must be a function of the minute alone, or the seeded past and the live
    future would not be one signal. The thresholds are not restated here: they are read from the two metric definitions in `seed.sh`,
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
# The three signals that were added to show something different.
START_DAY = [START + 60 * i for i in range(0, 24 * 60)]
creep = [evaluate('creep(t)', t) for t in START_DAY]
jitter = [evaluate('jitter(t)', t) for t in START_DAY]
shift = [evaluate('shift(t)', t) for t in START_DAY]
cp = thresholds('demo-live-creep')
if cp:
    warning, critical = cp
    # Noise makes a threshold crossed more than once near it, so what is held is a *cycle* that reaches
    # each level: six climbs in a day, each ending above its critical and starting below its warning.
    climbs = sum(1 for a, b in zip(creep, creep[1:]) if a - b > 150)
    if climbs != 6:
        problems.append(f'the imports must climb and drain once per 4 h ({climbs} drains read in a day, expected 6)')
    if max(creep) < critical:
        problems.append(f'the imports never reach their {critical:g} critical ({max(creep):.0f})')
    if min(creep) > warning / 2:
        problems.append(f'the imports never start low ({min(creep):.0f}): there is no climb to forecast')
jt = thresholds('demo-live-jitter')
if jt:
    warning, _ = jt
    if max(jitter) >= warning:
        problems.append(f'the events reach their {warning:g} warning ({max(jitter):.0f}): that series is the one with nothing to announce')
    if max(jitter) - min(jitter) < 30:
        problems.append('the events barely move: a series without noise shows no band to speak of')
sh = thresholds('demo-live-shift')
if sh:
    warning, critical = sh
    if min(shift) > warning or max(shift) < warning:
        problems.append(f'the tickets must pass below and above their {warning:g} warning ({min(shift):.0f}..{max(shift):.0f})')
    if max(shift) >= critical:
        problems.append(f'the tickets reach their {critical:g} critical ({max(shift):.0f}): the shift is a change of level, not a breach')
    # Halfway between the two plateaus, far enough from both that the noise cannot cross it twice.
    handovers = upward_crossings(shift, (min(shift) + max(shift)) / 2)
    if handovers != 8:
        problems.append(f'the tickets must change level once per 3 h cycle ({handovers} handovers in a day, expected 8)')
# Noise is a function of the minute: the same minute reads the same, a second apart or a restart apart.
for t in (START + 7, START + 3600 * 5 + 31):
    a, b = evaluate('noise(t)', t), evaluate('noise(t)', t - t % 60 + 59)
    if abs(a - b) > 1e-9:
        problems.append(f'the noise differs inside one minute ({a:.6f} vs {b:.6f}): the seeded past and the live future would not agree')
    if not -1 <= a <= 1:
        problems.append(f'the noise leaves [-1, 1]: {a:.3f}')
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
    # Every first topic already holds more records than any curve asks for, so the generator has to
    # produce to the *second* one — the only way to see the keys it gives them.
    for left in ('orders', 'requests', 'imports', 'events', 'tickets'):
        (tmp / 'out' / f'demo.live.{left}').write_text(''.join(f'{left}-{i}|{{}}\n' for i in range(1, 601)))
    # Exercise the request/ack protocol under the stub broker as well as topic offsets.
    control = tmp / 'control'
    control.mkdir()
    (control / 'demo-scenario.conf').write_text('normal|test-revision\n')
    env = {'PATH': '/usr/bin:/bin', 'OUT': str(tmp / 'out'), 'KAFKA_BIN': str(tmp / 'bin'),
           'DEMO_LIVE_TICK': '1', 'DEMO_SCENARIO_FILE': str(control / 'demo-scenario.conf'),
           'DEMO_SCENARIO_APPLIED_FILE': str(control / 'demo-scenario-applied.conf')}
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

    receipt = control / 'demo-scenario-applied.conf'
    if not receipt.exists():
        problems.append('live.sh did not acknowledge a scenario after Kafka offsets reflected the completed tick')
    else:
        parts = receipt.read_text().strip().split('|')
        if len(parts) != 5 or parts[:3] != ['v2', 'normal', 'test-revision'] or not all(p.isdigit() for p in parts[3:]):
            problems.append('live.sh receipt does not identify the exact request and verified tick')

    ended = int(time.time())
    for left, right, curve in (('demo.live.orders', 'demo.live.shipped', 'unshipped(t)'),
                               ('demo.live.requests', 'demo.live.processed', 'backlog(t)'),
                               ('demo.live.imports', 'demo.live.indexed', 'creep(t)'),
                               ('demo.live.events', 'demo.live.archived', 'jitter(t)'),
                               ('demo.live.tickets', 'demo.live.resolved', 'shift(t)')):
        gap = len(lines(left)) - len(lines(right))
        # The curve moves a few records a minute while it runs, and the run is a few seconds. The noisy
        # ones change value at the minute: accept the curve at either end of the run, never neither.
        reached = [evaluate(curve, ended - lag) for lag in (0, 12)]
        if min(abs(gap - target) for target in reached) > 12:
            problems.append(f'live.sh left {left} - {right} at {gap} where the curve says {reached[0]:.0f}')
        # The two topics of a pair share their keys: the k-th of the second is the k-th of the first.
        first, second = lines(left), lines(right)
        shared = min(len(first), len(second))
        if not shared:
            problems.append(f'live.sh produced nothing to {right}: the keys of a pair could not be compared')
        elif [l.split('|')[0] for l in first[:shared]] != [l.split('|')[0] for l in second[:shared]]:
            problems.append(f'live.sh gave {left} and {right} different keys: a key traced in Stream Flow would not be found in both')

    # A constant overload must become verified even as unrelated pairs retain
    # their baseline oscillations. This reproduces the prebuilt smoke transition.
    (control / 'demo-scenario.conf').write_text('overload|test-overload\n')
    try:
        subprocess.run(['sh', str(DIR / 'live.sh'), 'stub:9092'], env=env,
                       capture_output=True, text=True, timeout=7)
    except subprocess.TimeoutExpired:
        pass
    time.sleep(1)
    if not receipt.exists() or not receipt.read_text().startswith('v2|overload|test-overload|'):
        problems.append('live.sh did not verify an overload transition against Kafka offsets')

# 3. Both platforms, and the compose registry, name the overlay — and layer the same files. The two
# scripts are twins that nothing else compares; the forecast-only mode (`--forecast` / `-Forecast`)
# is the newest thing they must agree about.
compose_files = {name: set(re.findall(r'compose/[\w.-]+\.yml', (ROOT / name).read_text())) for name in ('demo.sh', 'demo.ps1')}
if compose_files['demo.sh'] != compose_files['demo.ps1']:
    only_sh = sorted(compose_files['demo.sh'] - compose_files['demo.ps1'])
    only_ps = sorted(compose_files['demo.ps1'] - compose_files['demo.sh'])
    problems.append(f'demo.sh and demo.ps1 layer different compose files (only demo.sh: {only_sh}; only demo.ps1: {only_ps})')
for name, flag in (('demo.sh', '--forecast'), ('demo.ps1', '-Forecast')):
    text = (ROOT / name).read_text()
    if flag not in text or 'DEMO_MODE' not in text:
        problems.append(f'{name} has no {flag} mode (DEMO_MODE=forecast): the forecast demo must start with nothing to configure on both platforms')

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
print('The forecast demo holds: the five curves do what they say, the generator holds the five pairs on '
      'their curves under shared keys, and both platforms layer the overlay.')
