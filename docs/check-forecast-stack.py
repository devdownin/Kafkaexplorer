#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
"""Exercise the real bootstrap in isolation: private secrets, container-readable metadata,
no-clobber reruns and correct Compose handoff. No Docker daemon or model download is needed.
Compose topology itself is resolved by CI's compose-lint job.
"""
from pathlib import Path
import os
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
with tempfile.TemporaryDirectory(prefix='forecast-stack-check-') as temporary:
    repo = Path(temporary)
    (repo / 'bin').mkdir()
    script = repo / 'bin/forecast-stack.sh'
    shutil.copy2(ROOT / 'bin/forecast-stack.sh', script)
    first = subprocess.run([str(script), '--prepare-only'], check=True, capture_output=True, text=True)
    state = repo / '.forecast-stack'
    credentials = (state / '.env').read_text()
    tokens = re.findall(r'^(?:FORECAST_POSTGRES_PASSWORD|TIMESFM_TOKEN|EXPLORER_MCP_AUTH_TOKEN)=([a-f0-9]{64})$', credentials, re.M)
    assert len(tokens) == 3 and len(set(tokens)) == 3, 'Three independent random secrets are required'
    assert (state / '.env').stat().st_mode & 0o777 == 0o600, 'Credentials must be private'
    assert state.stat().st_mode & 0o777 == 0o700, 'State directory must be private'
    assert (state / 'config').stat().st_mode & 0o777 == 0o755, 'Container UID must traverse the mounted directory'
    configuration = state / 'config/forecasts.yml'
    configuration.write_text('explorer: {forecasting: {pilot: {enabled: false}}}\n')
    original = configuration.read_bytes()
    second = subprocess.run([str(script), '--prepare-only'], check=True, capture_output=True, text=True)
    assert (state / '.env').read_text() == credentials, 'Reruns must preserve secrets'
    assert configuration.read_bytes() == original, 'Reruns must preserve reviewed configuration'
    assert configuration.stat().st_mode & 0o777 == 0o644, 'Non-root container must read the metadata file'
    assert all(token not in first.stdout + second.stdout for token in tokens), 'Do not print secrets'
    invalid = subprocess.run([str(script), '--unknown'], capture_output=True)
    assert invalid.returncode == 2, 'Unknown arguments must not launch anything'
    fake_bin = repo / 'fake-bin'; fake_bin.mkdir()
    args_file = repo / 'docker-arguments'
    fake_docker = fake_bin / 'docker'
    fake_docker.write_text('#!/bin/sh\nprintf "%s\\n" "$@" >> "$FORECAST_TEST_ARGUMENTS"\n')
    fake_docker.chmod(0o755)
    environment = dict(os.environ, PATH=str(fake_bin) + os.pathsep + os.environ['PATH'], FORECAST_TEST_ARGUMENTS=str(args_file))
    launched = subprocess.run([str(script)], env=environment, check=True, capture_output=True, text=True)
    assert args_file.read_text().splitlines() == ['compose', '--env-file', '.forecast-stack/.env',
        '-f', 'docker-compose.yml', '-f', 'compose/forecasts.yml', 'up', '-d', '--build',
        'compose', '--env-file', '.forecast-stack/.env', '-f', 'docker-compose.yml',
        '-f', 'compose/forecasts.yml', 'restart', 'explorer'], 'Compose must use the complete overlay and private environment'
    assert all(token not in launched.stdout + launched.stderr for token in tokens), 'Handoff must not print secrets'
print('Forecast bootstrap: credentials, rerun preservation, container readability and Compose handoff verified')
