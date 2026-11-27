#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
"""Validate the resolved demo on the local model: both apps must answer from the kex-llm image.
Usage: docker compose <the files ./demo.sh --local-llm layers> config --format json | python3 docs/demo-kex-llm-check.py

compose/demo-kex-llm.yml is an overlay of overlays and resolves cleanly while pointing an app somewhere
else: an agent left on OpenRouter, an Explorer whose bearer went missing, an image that is built
instead of pulled. None of those fails at `config` time, so each is asserted here, on the resolved model.
"""
import json
import sys

services = json.load(sys.stdin)['services']
server = services['llama-cpp']
explorer = services['explorer']['environment']
agent = services['agent']['environment']
alias = server['environment']['LLAMA_CPP_MODEL_NAME']
url = 'http://llama-cpp:8080/v1'

# The model is the one the package carries, pulled rather than built (a build would download 5 GB of weights).
assert server['image'].startswith('ghcr.io/devdownin/kex-llm:'), 'The server must run the published kex-llm image'
assert 'build' not in server, 'No build: `up --build` would fetch the weights from the Hub'
assert not any(v['target'] == '/models' for v in server.get('volumes', [])), 'A volume would hide the baked weights'
assert 'llama-cpp-model' not in services, 'The one-shot download must stay off'

# One window, declared once, for the server and for the Explorer's budgets.
assert server['environment']['LLAMA_CPP_CONTEXT'] == explorer['CLAUDE_CONTEXT_WINDOW_TOKENS'], \
    'The Explorer must be told the window the server serves'

# The Explorer.
assert explorer['CLAUDE_PROVIDER'] == 'LLAMA_CPP', 'Constrained decoding needs the LLAMA_CPP provider'
assert explorer['CLAUDE_BASE_URL'] == url and explorer['CLAUDE_MODEL'] == alias, 'The Explorer must reach this server, by alias'
assert not explorer.get('CLAUDE_API_KEY'), "SpectraLLM's key must not be sent to llama-server as a bearer"
assert explorer.get('EXPLORER_MCP_AUTH_TOKEN'), 'forecasts.yml leaves the MCP bearer empty: the agent would be refused'

# The agent: the OpenAI dialect, at the same server, and a key that exists (the provider refuses an empty one).
assert agent['KEX_AGENT_LLM_PROVIDER'] == 'openai', 'The agent speaks the OpenAI dialect to llama-server'
assert agent['OPENROUTER_BASE_URL'] == url and agent['OPENROUTER_MODEL'] == alias, 'The agent must reach this server, by alias'
assert agent.get('OPENROUTER_API_KEY'), 'An empty key stops the agent from starting its provider'
assert 'llama-cpp' in services['agent'].get('depends_on', {}), 'The agent starts after the server'
# Shipped: 120 s and 150 s, sized for a hosted model.
assert agent['KEX_AGENT_REQUESTTIMEOUT'] and agent['SPRING_MVC_ASYNC_REQUESTTIMEOUT'], 'A model on CPU needs the longer timeouts'
print('· the Explorer and the agent both answer from the kex-llm image')
