#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
#
# The fully local stack of compose/local-ai.yml: TimesFM with the Timestral shadow, and Qwen 2.5
# on llama.cpp. Read that file's header first — starting it accepts the Timestral licence.
#
#   bin/local-ai-stack.sh              # up -d --build
#   bin/local-ai-stack.sh ps           # any other compose command, on the same four files
#   bin/local-ai-stack.sh logs -f llama-cpp-model
#   bin/local-ai-stack.sh down
set -euo pipefail
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_dir"
# The secrets (PostgreSQL, TimesFM, MCP bearer) and the forecast configuration directory, once.
bin/forecast-stack.sh --prepare-only >/dev/null
compose=(docker compose --env-file .forecast-stack/.env -f docker-compose.yml
  -f compose/forecasts.yml -f compose/llama-cpp.yml -f compose/local-ai.yml)
if [[ $# -gt 0 ]]; then
  exec "${compose[@]}" "$@"
fi
echo 'Starting the local AI stack: the first run downloads TimesFM, Timestral and a 4.7 GB GGUF.'
echo 'Explorer: http://localhost:8080 - the model answers once llama-cpp-model has finished:'
echo '  bin/local-ai-stack.sh logs -f llama-cpp-model'
exec "${compose[@]}" up -d --build
