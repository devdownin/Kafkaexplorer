#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
"""Validate the resolved full profile, including paths rebased by Compose extends.
Usage: docker compose -f docker-compose.yml -f compose/forecasts.yml config --format json | python3 docs/forecast-topology-check.py
"""
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent
configuration = json.load(sys.stdin)
services = configuration['services']
worker = services['timesfm']
prefetch = services['forecast-model-prefetch']
postgres = services['forecast-postgres']
explorer = services['explorer']
assert set(worker['networks']) == {'timesfm_private'}, 'Inference must stay on the internal network'
assert 'default' in prefetch['networks'], 'The one-shot download requires outbound access'
assert configuration['networks']['timesfm_private']['internal'], 'The model network must be internal'
for service in [worker, prefetch]:
    assert service['build']['context'] == str(ROOT / 'services/timesfm'), 'extends must not rebase the model context to compose/services'
    assert not service.get('ports'), 'No model ports may be published'
assert not postgres.get('ports'), 'PostgreSQL must not publish a host port'
assert worker['depends_on']['forecast-model-prefetch']['condition'] == 'service_completed_successfully'
assert explorer['depends_on']['timesfm']['condition'] == 'service_healthy'
assert explorer['depends_on']['forecast-postgres']['condition'] == 'service_healthy'
assert any(v['source'] == 'forecast_postgres' for v in postgres['volumes']), 'Database storage must persist'
assert any(v['source'] == 'timesfm_models' for v in worker['volumes']), 'Model weights must persist'
assert explorer['environment']['SPRING_CONFIG_ADDITIONAL_LOCATION'] == 'optional:file:/app/forecast-config/forecasts.yml'
print('Forecast topology: model paths, isolated inference, persistent storage and readiness dependencies verified')
