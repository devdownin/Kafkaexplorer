#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
#
# The whole suite in one command: Kafka (KRaft) + demo topics, Schema Registry + Avro topics,
# Kafka Explorer (MCP on, read-only), TimesFM forecasting + PostgreSQL history, SpectraLLM
# (local llama.cpp model, MCP on, indexing the demo topics) and the Kex-anHarness agent — the
# Explorer and the agent on OpenRouter. See compose/demo.yml for how the files are layered.
#
#   OPENROUTER_API_KEY=sk-or-... ./demo.sh          # build, start, wait, print the endpoints
#   ./demo.sh --no-spectra                           # the same without SpectraLLM (DEMO_SPECTRA=false)
#   ./demo.sh [--no-spectra] down | reset | ps | logs [service] | probe
#
# Precedence: exported variable > .env at the root > .forecast-stack/.env > default below.
# Requires Docker Compose v2, curl and openssl (credentials are generated on the first run).
set -euo pipefail

repo_dir=$(cd "$(dirname "$0")" && pwd)
cd "$repo_dir"

usage() {
  echo 'Usage: ./demo.sh [--no-spectra] [up|down|reset|ps|logs [service]|probe]' >&2
  exit 2
}

# Before the env files, so the flag outranks a DEMO_SPECTRA they set.
if [[ ${1:-} == --no-spectra ]]; then
  export DEMO_SPECTRA=false
  shift
fi

# First value wins: a variable already set (exported by the caller, or by an earlier file) is kept.
load_env_file() {
  [[ -f $1 ]] || return 0
  local key value
  while IFS='=' read -r key value || [[ -n $key ]]; do
    [[ $key =~ ^[A-Za-z_][A-Za-z0-9_]*$ && ! -v $key ]] || continue
    value=${value%$'\r'}
    value=${value#[\"\']}
    value=${value%[\"\']}
    export "$key=$value"
  done < "$1"
}

command -v docker >/dev/null && docker compose version >/dev/null 2>&1 \
  || { echo 'Docker with Compose v2 is required.' >&2; exit 1; }

# Generates FORECAST_POSTGRES_PASSWORD, TIMESFM_TOKEN and EXPLORER_MCP_AUTH_TOKEN once, kept 0600.
bin/forecast-stack.sh --prepare-only >/dev/null
load_env_file .env
load_env_file .forecast-stack/.env

# ── Defaults ─────────────────────────────────────────────────────────────────────
export BIND_ADDR=${BIND_ADDR:-127.0.0.1}
export EXPLORER_PORT=${EXPLORER_PORT:-8080}
export KAFKA_PORT=${KAFKA_PORT:-9092}
export KEX_AGENT_PORT=${KEX_AGENT_PORT:-8081}
# 8081 belongs to the agent in the shared port plan; Schema Registry moves aside.
export SCHEMA_REGISTRY_PORT=${SCHEMA_REGISTRY_PORT:-8085}

# LLM — OpenRouter on both sides.
export OPENROUTER_API_KEY=${OPENROUTER_API_KEY:-}
export OPENROUTER_MODEL=${OPENROUTER_MODEL:-openai/gpt-4o-mini}
export KEX_AGENT_LLM_PROVIDER=${KEX_AGENT_LLM_PROVIDER:-openai}
export ANTHROPIC_API_KEY=${ANTHROPIC_API_KEY:-}

# Agent and MCP. The MCP token defaults to the generated one from .forecast-stack/.env.
export KEX_AGENT_IMAGE_NAMESPACE=${KEX_AGENT_IMAGE_NAMESPACE:-compagnonsdudev}
export KEX_AGENT_IMAGE_TAG=${KEX_AGENT_IMAGE_TAG:-0.10.0}
export KEX_AGENT_API_KEY=${KEX_AGENT_API_KEY:-dev-only-agent-key}
export EXPLORER_MCP_AUTH_TOKEN=${EXPLORER_MCP_AUTH_TOKEN:-dev-only-mcp-token}
export EXPLORER_MCP_READONLY=${EXPLORER_MCP_READONLY:-true}

# SpectraLLM. Its MCP server refuses to run without SPECTRA_API_KEY, and with one set its own UI
# answers 401 (it sends no key); the agent and the API are unaffected.
export SPECTRA_API_KEY=${SPECTRA_API_KEY:-dev-only-spectra-key}
export SPECTRA_IMAGE_NAMESPACE=${SPECTRA_IMAGE_NAMESPACE:-compagnonsdudev}
export SPECTRA_IMAGE_TAG=${SPECTRA_IMAGE_TAG:-0.12.3}
export SPECTRA_UI_PORT=${SPECTRA_UI_PORT:-8088}
export SPECTRA_API_PORT=${SPECTRA_API_PORT:-8089}
# The three must agree (.env.example); a laptop wants the 3B named there.
export LLM_CHAT_MODEL_NAME=${LLM_CHAT_MODEL_NAME:-qwen2.5-7b-instruct}
export LLM_CHAT_MODEL_FILE=${LLM_CHAT_MODEL_FILE:-Qwen2.5-7B-Instruct-Q4_K_M.gguf}
export SPECTRA_AUTO_INSTALL_MODELS=${SPECTRA_AUTO_INSTALL_MODELS:-true}

# Forecasting.
export FORECAST_ENVIRONMENTS=${FORECAST_ENVIRONMENTS:-local}
export FORECAST_CLUSTER_ID=${FORECAST_CLUSTER_ID:-local}
export FORECAST_COLLECTOR_ID=${FORECAST_COLLECTOR_ID:-kafkaexplorer}
export FORECAST_RUNTIME_APPROVAL=${FORECAST_RUNTIME_APPROVAL:-true}
export TIMESFM_THREADS=${TIMESFM_THREADS:-4}
export TIMESFM_CPUS=${TIMESFM_CPUS:-4.0}
export TIMESFM_MEM_LIMIT=${TIMESFM_MEM_LIMIT:-8g}
export TIMESFM_TIMEOUT=${TIMESFM_TIMEOUT:-35s}

# Explorer image: built from this checkout by default; DEMO_PUBLISHED_IMAGE=true pulls the release.
export DEMO_PUBLISHED_IMAGE=${DEMO_PUBLISHED_IMAGE:-false}
export EXPLORER_IMAGE_NAMESPACE=${EXPLORER_IMAGE_NAMESPACE:-compagnonsdudev}
export EXPLORER_IMAGE_TAG=${EXPLORER_IMAGE_TAG:-2.3.2}

# SpectraLLM on by default; false drops its seven services and their ~4.8 GB first download.
export DEMO_SPECTRA=${DEMO_SPECTRA:-true}
[[ $DEMO_SPECTRA == true || $DEMO_SPECTRA == false ]] || { echo "DEMO_SPECTRA must be true or false, not '$DEMO_SPECTRA'." >&2; exit 2; }

export COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME:-kafka-explorer-demo}

files=(-f docker-compose.yml -f compose/schema-registry.yml -f compose/mcp.yml -f compose/forecasts.yml)
[[ $DEMO_PUBLISHED_IMAGE == true ]] && files+=(-f compose/image.yml)
if [[ $DEMO_SPECTRA == true ]]; then
  files+=(-f compose/demo.yml -f compose/spectra-hub.mcp.yml -f compose/spectra-hub.ingest.yml
    -f compose/ecosystem.yml)
else
  files+=(-f compose/demo-no-spectra.yml)
fi
files+=(-f compose/demo-llm.yml)

compose() { docker compose "${files[@]}" "$@"; }

wait_for_http() {
  local name=$1 url=$2 deadline=$((SECONDS + $3))
  shift 3
  printf 'Waiting for %s ' "$name"
  until curl -fsS -o /dev/null "$@" "$url" 2>/dev/null; do
    if ((SECONDS >= deadline)); then
      echo "— not ready after $3 s. See: ./demo.sh logs"
      return 1
    fi
    printf '.'
    sleep 5
  done
  echo ' ok'
}

up() {
  if [[ -z $OPENROUTER_API_KEY ]]; then
    echo 'WARN: OPENROUTER_API_KEY is empty — Process Mining answers without its LLM half and the' >&2
    echo '      agent starts DEGRADED. Export it (or put it in .env) and rerun ./demo.sh.' >&2
  fi
  echo "First run: Maven build of the Explorer and the pinned TimesFM checkpoint (${TIMESFM_CPUS} CPUs / ${TIMESFM_MEM_LIMIT})."
  [[ $DEMO_SPECTRA != true ]] || echo 'SpectraLLM adds ~4.8 GB of weights; its 7B chat model wants ~16 GB of RAM.'
  echo 'Later runs start in seconds.'
  [[ $DEMO_PUBLISHED_IMAGE != true ]] || compose pull --quiet --ignore-buildable
  # --remove-orphans: switching to --no-spectra stops the SpectraLLM containers a previous run left.
  compose up -d --build --remove-orphans
  wait_for_http 'Kafka Explorer' "http://127.0.0.1:${EXPLORER_PORT}/actuator/health" 900
  wait_for_http 'Kex-anHarness' "http://127.0.0.1:${KEX_AGENT_PORT}/api/agent/mcp/servers" 300 \
    -H "Authorization: Bearer ${KEX_AGENT_API_KEY}" || true
  # spectra-api waits for the embedding model and the demo seed; its chat answers once llm-chat
  # has its weights, which `./demo.sh logs spectra-models llm-chat` follows.
  if [[ $DEMO_SPECTRA == true ]]; then
    wait_for_http 'SpectraLLM API' "http://127.0.0.1:${SPECTRA_API_PORT}/actuator/health/readiness" 600 || true
  fi
  local spectra='SpectraLLM ............ off (--no-spectra); the agent reports its MCP connection unreachable'
  [[ $DEMO_SPECTRA != true ]] || spectra="SpectraLLM UI ......... http://localhost:${SPECTRA_UI_PORT}     (401 while SPECTRA_API_KEY is set)
SpectraLLM API ........ http://localhost:${SPECTRA_API_PORT}/swagger-ui   (/mcp, X-API-Key ${SPECTRA_API_KEY}) · ${LLM_CHAT_MODEL_NAME} local"
  cat <<EOF

Kafka SQL Explorer .... http://localhost:${EXPLORER_PORT}
  MCP ................. http://localhost:${EXPLORER_PORT}/mcp   (Bearer in .forecast-stack/.env or EXPLORER_MCP_AUTH_TOKEN)
Kex-anHarness ......... http://localhost:${KEX_AGENT_PORT}     (Bearer ${KEX_AGENT_API_KEY})
${spectra}
Schema Registry ....... http://localhost:${SCHEMA_REGISTRY_PORT}
Kafka ................. localhost:${KAFKA_PORT}
LLM ................... OpenRouter ${OPENROUTER_MODEL} (Explorer, agent)

Demo topics (JSON + Avro) are seeded in the background: ./demo.sh logs demo-setup
Forecast: Metrics → "Prepare a forecast" on a metric card.
EOF
}

case ${1:-up} in
  up) [[ $# -le 1 ]] || usage; up ;;
  down) compose down --remove-orphans ;;
  # Volumes included: topics, forecast history, agent state, downloaded models. Credentials stay.
  reset) compose down -v --remove-orphans ;;
  ps) compose ps ;;
  logs) shift; compose logs -f --tail 200 "$@" ;;
  probe)
    compose --profile probe run --rm mcp-probe
    [[ $DEMO_SPECTRA != true ]] || compose --profile probe run --rm spectra-mcp-probe
    ;;
  *) usage ;;
esac
