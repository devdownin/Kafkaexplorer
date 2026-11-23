#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
"""Assert the prebuilt forecast demo's resolved Compose graph cannot trigger a build.

Run against the exact overlay order used by demo-forecast.sh / demo-forecast.cmd.
Optionally add --profile prefetch before 'config' to check the hidden helper as well.
"""
import json
import sys

config = json.load(sys.stdin)
services = config['services']

for name, service in services.items():
    assert not service.get('build'), f"{name}: a build context survived the prebuilt overlays"
    assert service.get('image'), f"{name}: no published image is defined"

worker = services['timesfm']
explorer = services['explorer']
assert worker['image'].startswith('ghcr.io/devdownin/kex-forecast:'), "TimesFM must use the baked-model image"
assert explorer['image'].startswith('compagnonsdudev/kafkaexplorer:'), "Explorer must use the published release"
assert worker['environment']['HF_HUB_OFFLINE'] == '1', "TimesFM may not download checkpoints at start"
assert 'forecast-model-prefetch' not in worker.get('depends_on', {}), "TimesFM must not wait for model downloads"
assert not any(m['target'] == '/models' for m in worker.get('volumes', [])), "No volume may hide baked checkpoints"
assert {'demo-live', 'demo-forecast-seed', 'forecast-postgres', 'schema-registry'} <= services.keys(), \
    "Prebuilt demo must retain live metrics, history, database and Avro topics"
if 'forecast-model-prefetch' not in services:
    print('Prebuilt forecast demo: all active services use published images; no build or model prefetch')
else:
    assert services['forecast-model-prefetch']['image'] == worker['image'], \
        'An enabled prefetch profile must also use the published image'
    print('Prebuilt forecast demo: even the optional prefetch profile has no build')
