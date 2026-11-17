#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
"""Validate the resolved offline LLM stack: nothing in it may need the Hub at start.
Usage: docker compose -f docker-compose.yml -f compose/llama-cpp.yml -f compose/llama-cpp-offline.yml config --format json | python3 docs/llm-offline-check.py

Each assertion is a way the overlay stops being offline without anything failing at `config` time.
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
configuration = json.load(sys.stdin)
services = configuration['services']
server = services['llama-cpp']
explorer = services['explorer']

assert server['image'].startswith('ghcr.io/devdownin/kex-llm:'), 'The server must run the published offline image'
assert server['build']['context'] == str(ROOT), 'A local build must read the repository root, where serve.sh lives'
assert server['build']['dockerfile'] == 'services/llama-cpp/Dockerfile', 'A local build must make the same image'
# A volume on /models would hide the baked weights under an empty directory.
assert not any(v['target'] == '/models' for v in server.get('volumes', [])), 'No volume may cover the baked-in model directory'
assert not any(v['target'] == '/opt/llama-cpp/serve.sh' for v in server.get('volumes', [])), 'The entrypoint script is in the image'
# `config` leaves out a service in a profile nobody enabled, which is exactly the point.
assert 'llama-cpp-model' not in services, 'The one-shot download must be off by default'
assert server['environment']['LLAMA_CPP_MODEL_NAME'] == explorer['environment']['CLAUDE_MODEL'], \
    'The Explorer must ask for the alias the image serves'
assert explorer['environment']['CLAUDE_PROVIDER'] == 'LLAMA_CPP', 'Constrained decoding needs the LLAMA_CPP provider'
assert explorer['environment']['CLAUDE_BASE_URL'] == 'http://llama-cpp:8080/v1', 'The Explorer reaches the server by service name'
assert not server.get('ports') or all(p.get('host_ip') == '127.0.0.1' for p in server['ports']), \
    'llama-server has no authentication: loopback only'
print('Offline LLM stack: baked model, no download, no model volume, loopback-only server verified')
