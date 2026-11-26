#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
"""Validate the resolved offline forecast stack: nothing in it may need the Hub at start.
Usage: docker compose -f docker-compose.yml -f compose/forecasts.yml -f compose/forecast-offline.yml config --format json | python3 docs/forecast-offline-check.py

`docs/forecast-topology-check.py` pins the default stack, which downloads its models; this one
pins the overlay that bakes them in, and each assertion is a way that overlay stops being offline
without anything failing at `config` time.
"""
import json
import sys

configuration = json.load(sys.stdin)
services = configuration['services']
worker = services['timesfm']
explorer = services['explorer']

assert worker['image'].startswith('ghcr.io/devdownin/kex-forecast:'), 'The worker must run the published offline image'
assert worker['build']['target'] == 'offline', 'A local build must make the same image: the offline target'
# A volume on /models would hide the baked checkpoints under an empty directory.
assert not any(v['target'] == worker['environment']['TIMESFM_CACHE_DIR'] for v in worker.get('volumes', [])), \
    'No volume may cover the baked-in model directory'
assert 'forecast-model-prefetch' not in worker.get('depends_on', {}), 'The worker must not wait for a download'
assert 'forecast-postgres' in worker.get('depends_on', {}), 'The tripwire that refuses this overlay without compose/forecasts.yml'
# `config` leaves out a service in a profile nobody enabled, which is exactly the point.
assert 'forecast-model-prefetch' not in services, 'The one-shot download must be off by default'
assert worker['environment']['HF_HUB_OFFLINE'] == '1', 'The Hub client must be refused, not merely unused'
# The pin is the image's ENV: a value here would override the checkpoint the image carries.
assert 'TIMESTRAL_REVISION' not in worker['environment'], 'The shadow revision is the image\'s own'
assert 'TIMESTRAL_SHA256' not in worker['environment'], 'The shadow digest is the image\'s own'
assert worker['environment']['TIMESTRAL_MODEL'] == explorer['environment']['EXPLORER_FORECASTING_INFERENCE_SHADOW_MODEL_ID'], \
    'The worker and the Explorer name the same Timestral'
assert set(worker['networks']) == {'timesfm_private'} and configuration['networks']['timesfm_private']['internal'], \
    'Inference stays on the internal network'
assert explorer['environment']['EXPLORER_FORECASTING_INFERENCE_SHADOW_ENABLED'] == 'true', 'The baked shadow is scored by default'
assert worker.get('read_only') is True, 'The container root stays read-only'
print('Offline forecast stack: baked models, no download, no model volume, internal-only inference verified')
