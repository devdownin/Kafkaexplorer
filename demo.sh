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
#   ./demo.sh --forecast                             # the forecast demo alone, builds local images
#   ./demo.sh --prebuilt                             # the full suite from published images, no build
#   ./demo.sh --forecast --prebuilt                  # the forecast demo from published images, no build
#   ./demo.sh --forecast --prebuilt --no-agent       # the forecast demo without the agent
#   ./demo.sh --prebuilt --local-llm                 # the Explorer AND the agent on the local model of the
#                                                    # kex-llm image (Gemma 4 E4B), no OpenRouter key (DEMO_LLM=local)
#   ./demo-forecast.sh [up|down|reset|ps|logs [service]|probe|doctor|verify|scenario [name]] # prebuilt shorthand
#   ./demo.sh [--no-spectra|--forecast|--prebuilt] down | reset | ps | logs [service] | probe | doctor | verify | scenario [name]
#
# --forecast (DEMO_MODE=forecast) is the stack the forecast demo needs and nothing else: Kafka and the
# demo topics, Schema Registry, the Explorer with MCP, PostgreSQL, TimesFM, the live traffic and its
# seeded history (compose/demo-forecast.yml), and the Kex-anHarness agent. No LLM key needed (the agent
# starts degraded without one), no SpectraLLM: every variable has a default, so `./demo.sh --forecast`
# is the whole command. See docs/metrics-demo-scenarios.md.
#
# The agent is on by default in the forecast demo (except --offline); --no-agent (DEMO_AGENT=false)
# drops it, --agent (DEMO_AGENT=true) forces it. It runs on OpenRouter, with the Explorer's LLM half (compose/demo-no-spectra.yml + compose/demo-llm.yml, the shape
# --no-spectra already runs). With --prebuilt, the agent follows `latest` like the Explorer and the
# forecast image, so nothing is compiled. The full suite already has the agent: --agent / --no-agent
# are for --forecast only.
#
# Precedence: exported variable > .env at the root > .forecast-stack/.env > default below.
# Requires Docker Compose v2, curl and openssl (credentials are generated on the first run).
set -euo pipefail

repo_dir=$(cd "$(dirname "$0")" && pwd)
cd "$repo_dir"

usage() {
  echo 'Usage: ./demo.sh [--no-spectra|--forecast|--prebuilt|--offline|--agent|--no-agent|--local-llm] [up|down|reset|ps|logs [service]|probe|doctor|verify|scenario [name]]' >&2
  exit 2
}

# Before the env files, so a flag outranks a DEMO_SPECTRA / DEMO_MODE they set. --forecast has no
# SpectraLLM to switch off: it is not in that stack at all.
while [[ ${1:-} == --* ]]; do
  case $1 in
    --no-spectra) export DEMO_SPECTRA=false ;;
    --forecast) export DEMO_MODE=forecast DEMO_SPECTRA=false ;;
    --prebuilt) export DEMO_PREBUILT=true DEMO_PUBLISHED_IMAGE=true ;;
    --offline) export DEMO_MODE=forecast DEMO_SPECTRA=false DEMO_PREBUILT=true DEMO_PUBLISHED_IMAGE=true DEMO_OFFLINE=true ;;
    --agent) export DEMO_AGENT=true ;;
    --no-agent) export DEMO_AGENT=false ;;
    --local-llm) export DEMO_LLM=local ;;
    *) usage ;;
  esac
  shift
done

# First value wins: a variable already set (exported by the caller, or by an earlier file) is kept.
load_env_file() {
  [[ -f $1 ]] || return 0
  local key value
  while IFS='=' read -r key value || [[ -n $key ]]; do
    [[ $key =~ ^[A-Za-z_][A-Za-z0-9_]*$ && -z ${!key:-} ]] || continue
    value=${value%$'\r'}
    value=${value#[\"\']}
    value=${value%[\"\']}
    [[ -n $value ]] || continue
    export "$key=$value"
  done < "$1"
}

command -v docker >/dev/null && docker compose version >/dev/null 2>&1 \
  || { echo 'Docker with Compose v2 is required.' >&2; exit 1; }

# Generates FORECAST_POSTGRES_PASSWORD, TIMESFM_TOKEN and EXPLORER_MCP_AUTH_TOKEN once, kept 0600.
bin/forecast-stack.sh --prepare-only >/dev/null
load_env_file .env
load_env_file .forecast-stack/.env

# After the env files, so a DEMO_MODE they set is honoured; full is the whole suite.
export DEMO_MODE=${DEMO_MODE:-full}
[[ $DEMO_MODE == full || $DEMO_MODE == forecast ]] || { echo "DEMO_MODE must be full or forecast, not '$DEMO_MODE'." >&2; exit 2; }
[[ $DEMO_MODE == full ]] || export DEMO_SPECTRA=false
export DEMO_PREBUILT=${DEMO_PREBUILT:-false}
export DEMO_OFFLINE=${DEMO_OFFLINE:-false}
[[ $DEMO_OFFLINE == true || $DEMO_OFFLINE == false ]] || { echo 'DEMO_OFFLINE must be true or false.' >&2; exit 2; }
if [[ $DEMO_OFFLINE == true && $DEMO_PREBUILT != true ]]; then
  echo 'Offline mode requires a prebuilt forecast demo (--forecast --prebuilt --offline).' >&2
  exit 2
fi
[[ $DEMO_PREBUILT == true || $DEMO_PREBUILT == false ]] || { echo 'DEMO_PREBUILT must be true or false.' >&2; exit 2; }
# --prebuilt works for the full suite too: the forecast overlays that build from this checkout are
# the ones compose/forecast-offline.yml and compose/demo-forecast-prebuilt.yml retarget to the
# published image, and SpectraLLM and the agent are published images already.
if [[ $DEMO_PREBUILT == true ]]; then
  export DEMO_PUBLISHED_IMAGE=true
fi
# The forecast demo loads the Kex-anHarness agent by default (tokens defaulted below, no key needed:
# without OPENROUTER_API_KEY it starts degraded and says so). --no-agent / DEMO_AGENT=false drops it.
# --offline keeps it off: the agent image is not baked into the forecast image, so it needs a pull.
if [[ $DEMO_MODE == forecast && $DEMO_OFFLINE != true ]]; then
  export DEMO_AGENT=${DEMO_AGENT:-true}
else
  export DEMO_AGENT=${DEMO_AGENT:-false}
fi
[[ $DEMO_AGENT == true || $DEMO_AGENT == false ]] || { echo 'DEMO_AGENT must be true or false.' >&2; exit 2; }
if [[ $DEMO_AGENT == true && $DEMO_MODE != forecast ]]; then
  echo '--agent is for the forecast demo (--forecast); the full suite already runs the agent.' >&2
  exit 2
fi

# ── What the demo sets ───────────────────────────────────────────────────────────
# Two kinds of line live here, and only two: an override of the compose files' own default
# (OpenRouter rather than Anthropic, Schema Registry beside the agent, the shadow off), and a value
# this script reads itself, for a message or a request. Every other default — image tags, thread
# counts, timeouts, forecast identities — is the compose files' `${VAR:-…}`, written once. A copy
# here is a second value that drifts: this block kept the agent at 0.10.0 and SpectraLLM at 0.12.3
# for a month after the compose files moved to 0.20.0, and nothing said so.
export EXPLORER_PORT=${EXPLORER_PORT:-8080}
export KAFKA_PORT=${KAFKA_PORT:-9092}
export KEX_AGENT_PORT=${KEX_AGENT_PORT:-8081}
# 8081 belongs to the agent in the shared port plan; Schema Registry moves aside.
export SCHEMA_REGISTRY_PORT=${SCHEMA_REGISTRY_PORT:-8085}

# LLM — OpenRouter on both sides.
export OPENROUTER_API_KEY=${OPENROUTER_API_KEY:-}
# The demo runs on the OpenRouter router for free models: no credits needed to try it.
export OPENROUTER_MODEL=${OPENROUTER_MODEL:-openrouter/free}
export KEX_AGENT_LLM_PROVIDER=${KEX_AGENT_LLM_PROVIDER:-openai}
# The SQL & metric assistant, on the same OpenRouter model (false turns it off). It needs that model,
# which the forecast demo has only with --agent: off there otherwise.
if [[ $DEMO_MODE == forecast && $DEMO_AGENT != true ]]; then
  export EXPLORER_ASSISTANT_ENABLED=${EXPLORER_ASSISTANT_ENABLED:-false}
else
  export EXPLORER_ASSISTANT_ENABLED=${EXPLORER_ASSISTANT_ENABLED:-true}
fi

# Agent and MCP. The MCP token defaults to the generated one from .forecast-stack/.env.
export KEX_AGENT_IMAGE_NAMESPACE=${KEX_AGENT_IMAGE_NAMESPACE:-compagnonsdudev}
export KEX_AGENT_API_KEY=${KEX_AGENT_API_KEY:-dev-only-agent-key}
export EXPLORER_MCP_AUTH_TOKEN=${EXPLORER_MCP_AUTH_TOKEN:-dev-only-mcp-token}

# SpectraLLM. Its MCP server refuses to run without SPECTRA_API_KEY, and with one set its own UI
# answers 401 (it sends no key); the agent and the API are unaffected.
export SPECTRA_API_KEY=${SPECTRA_API_KEY:-dev-only-spectra-key}
export SPECTRA_UI_PORT=${SPECTRA_UI_PORT:-8088}
export SPECTRA_API_PORT=${SPECTRA_API_PORT:-8089}
# The name and the file must agree (.env.example); a laptop wants the 3B named there.
export LLM_CHAT_MODEL_NAME=${LLM_CHAT_MODEL_NAME:-qwen2.5-7b-instruct}

# A prebuilt demo follows the published images (the agent too, with --agent).
# Other stacks keep the compose files' versioned pins; an explicit environment/.env tag always wins.
# Keep this outside Compose so the repository's production image-pin checks stay strict.
if [[ $DEMO_PREBUILT == true ]]; then
  export EXPLORER_IMAGE_TAG=${EXPLORER_IMAGE_TAG:-latest}
  export FORECAST_IMAGE_TAG=${FORECAST_IMAGE_TAG:-latest}
  [[ $DEMO_AGENT != true ]] || export KEX_AGENT_IMAGE_TAG=${KEX_AGENT_IMAGE_TAG:-latest}
fi

# Forecasting.
export FORECAST_CONFIG_DIR=${FORECAST_CONFIG_DIR:-./.forecast-stack/config}
export TIMESFM_CPUS=${TIMESFM_CPUS:-4.0}
export TIMESFM_MEM_LIMIT=${TIMESFM_MEM_LIMIT:-8g}
export TIMESTRAL_SHADOW_ENABLED=${TIMESTRAL_SHADOW_ENABLED:-false}

# Explorer image: built from this checkout by default; DEMO_PUBLISHED_IMAGE=true pulls the release.
export DEMO_PUBLISHED_IMAGE=${DEMO_PUBLISHED_IMAGE:-false}
export EXPLORER_IMAGE_NAMESPACE=${EXPLORER_IMAGE_NAMESPACE:-compagnonsdudev}

# SpectraLLM on by default; false drops its seven services and their ~4.8 GB first download.
export DEMO_SPECTRA=${DEMO_SPECTRA:-true}
[[ $DEMO_SPECTRA == true || $DEMO_SPECTRA == false ]] || { echo "DEMO_SPECTRA must be true or false, not '$DEMO_SPECTRA'." >&2; exit 2; }

export COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME:-kafka-explorer-demo}

# Which model answers for the Explorer and the agent: OpenRouter (hosted, needs a key) or the local one
# the kex-llm image carries. --local-llm sets it for one run.
export DEMO_LLM=${DEMO_LLM:-openrouter}
[[ $DEMO_LLM == openrouter || $DEMO_LLM == local ]] || { echo "DEMO_LLM must be openrouter or local, not '$DEMO_LLM'." >&2; exit 2; }
llm_files=(-f compose/demo-llm.yml)
[[ $DEMO_LLM != local ]] || llm_files=(-f compose/llama-cpp.yml -f compose/llama-cpp-offline.yml -f compose/demo-kex-llm.yml)

files=(-f docker-compose.yml -f compose/schema-registry.yml -f compose/mcp.yml -f compose/forecasts.yml
  -f compose/demo-forecast.yml)
if [[ $DEMO_PREBUILT == true ]]; then
  files+=(-f compose/forecast-offline.yml -f compose/demo-forecast-prebuilt.yml)
fi
[[ $DEMO_PUBLISHED_IMAGE == true ]] && files+=(-f compose/image.yml)
# The forecast demo stops here unless --agent: the agent (compose/demo*.yml, ecosystem) and the
# hosted model (demo-llm.yml) are what want a key, and nothing else it shows uses them.
if [[ $DEMO_AGENT == true ]]; then
  files+=(-f compose/demo-no-spectra.yml -f compose/demo-agent-no-spectra.yml "${llm_files[@]}")
elif [[ $DEMO_MODE == full ]]; then
  if [[ $DEMO_SPECTRA == true ]]; then
    files+=(-f compose/demo.yml -f compose/spectra-hub.mcp.yml -f compose/spectra-hub.ingest.yml
      -f compose/ecosystem.yml)
  else
    files+=(-f compose/demo-no-spectra.yml -f compose/demo-agent-no-spectra.yml)
  fi
  files+=("${llm_files[@]}")
fi

compose() { docker compose "${files[@]}" "$@"; }

# Diagnostics never compile or pull images. Docker owns the resource view, not host free(1).
doctor() {
  [[ $DEMO_PREBUILT == true ]] ||
    { echo 'doctor is for the prebuilt demos (--prebuilt, --forecast --prebuilt).' >&2; return 2; }
  local version major minor ram_mb image port busy=0
  version=$(docker compose version --short | sed 's/^v//')
  if [[ $version =~ ^([0-9]+)\.([0-9]+) ]]; then
    major=${BASH_REMATCH[1]} minor=${BASH_REMATCH[2]}
    (( major > 2 || (major == 2 && minor >= 24) )) ||
      { echo "ERROR: Docker Compose 2.24+ required (found $version)." >&2; return 1; }
  else
    echo "ERROR: cannot determine Docker Compose version: $version" >&2; return 1
  fi
  docker info >/dev/null 2>&1 || { echo 'ERROR: Docker Engine is not running.' >&2; return 1; }
  compose config --quiet || { echo 'ERROR: invalid Compose configuration.' >&2; return 1; }
  for secret in FORECAST_POSTGRES_PASSWORD TIMESFM_TOKEN EXPLORER_MCP_AUTH_TOKEN; do
    [[ -n ${!secret:-} ]] || { echo "ERROR: missing required variable $secret." >&2; return 1; }
  done
  [[ $EXPLORER_PORT != "$KAFKA_PORT" && $EXPLORER_PORT != "$SCHEMA_REGISTRY_PORT" && $KAFKA_PORT != "$SCHEMA_REGISTRY_PORT" ]] ||
    { echo 'ERROR: Explorer, Schema Registry and Kafka host ports must be different.' >&2; return 1; }
  ram_mb=$(( $(docker info --format '{{.MemTotal}}') / 1024 / 1024 ))
  echo "Docker: Compose $version; ${ram_mb} MiB RAM (TimesFM limit $TIMESFM_MEM_LIMIT)."
  (( ram_mb >= 8192 )) || echo 'WARN: less than 8 GiB allocated to Docker; forecasting may be slow or OOM.' >&2
  local ports=("$EXPLORER_PORT" "$SCHEMA_REGISTRY_PORT" "$KAFKA_PORT")
  if [[ $DEMO_AGENT == true ]]; then
    [[ $KEX_AGENT_PORT != "$EXPLORER_PORT" && $KEX_AGENT_PORT != "$SCHEMA_REGISTRY_PORT" && $KEX_AGENT_PORT != "$KAFKA_PORT" ]] ||
      { echo 'ERROR: the agent host port must differ from the Explorer, Schema Registry and Kafka ports.' >&2; return 1; }
    ports+=("$KEX_AGENT_PORT")
  fi
  for port in "${ports[@]}"; do
    [[ $port =~ ^[0-9]+$ ]] && (( port >= 1 && port <= 65535 )) ||
      { echo "ERROR: invalid port $port." >&2; return 1; }
    if (echo >/dev/tcp/127.0.0.1/"$port") >/dev/null 2>&1; then
      if [[ -z $(compose ps -q) ]]; then
        echo "ERROR: port $port is occupied (no containers in this Compose project)." >&2
        busy=1
      else
        echo "WARN: port $port is occupied; an existing demo may own it." >&2
      fi
    fi
  done
  (( busy == 0 )) || return 1
  local missing=0
  while IFS= read -r image; do
    [[ -n $image ]] || continue
    if ! docker image inspect "$image" >/dev/null 2>&1; then
      if [[ $DEMO_OFFLINE == true ]]; then
        echo "ERROR: offline image missing: $image (load it with docker load or pull it beforehand)." >&2
        missing=1
      else
        echo "Image to pull: $image"
      fi
    fi
  done < <(compose config --images | sort -u)
  (( missing == 0 )) || return 1
  [[ $DEMO_OFFLINE != true ]] || echo 'Offline check: all images are available locally.'
  echo "Demo images: ${EXPLORER_IMAGE_NAMESPACE}/kafkaexplorer:${EXPLORER_IMAGE_TAG}; ghcr.io/devdownin/kex-forecast:${FORECAST_IMAGE_TAG}"
  [[ $DEMO_AGENT != true ]] || echo "Agent image: ${KEX_AGENT_IMAGE_NAMESPACE}/kex-agent-ai:${KEX_AGENT_IMAGE_TAG}"
  echo 'Preflight OK.'
}

# Uses the same model result and seeded PostgreSQL observations as the Metrics page.
# No jq, Python, Node or local build is needed on the user's machine.
verify_forecast_once() {
  local cid state code count metrics forecasts id
  cid=$(compose ps -aq demo-forecast-seed | head -n 1)
  [[ -n $cid ]] || { echo 'Seeder has not started.'; return 1; }
  state=$(docker inspect -f '{{.State.Status}}' "$cid")
  code=$(docker inspect -f '{{.State.ExitCode}}' "$cid")
  [[ $state == exited ]] || { echo "Seeder is still $state."; return 1; }
  [[ $code == 0 ]] || { echo "Seeder exited with $code; inspect: ./demo-forecast.sh logs demo-forecast-seed"; return 1; }
  for id in kafka demo-live forecast-postgres timesfm explorer; do
    cid=$(compose ps -q "$id" | head -n 1)
    [[ -n $cid && $(docker inspect -f '{{.State.Running}}' "$cid") == true ]] ||
      { echo "Service $id is not running."; return 1; }
  done
  cid=$(compose ps -q timesfm | head -n 1)
  [[ $(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$cid") == healthy ]] ||
    { echo 'TimesFM is not healthy.'; return 1; }
  count=$(compose exec -T forecast-postgres psql -U forecasts -d forecasts -At -c \
    "SELECT count(*) FROM (SELECT payload::jsonb->>'metricId' AS metric_id FROM kex_metric_observation_v1 WHERE payload::jsonb->>'collectorRunId' LIKE 'demo-seed-%' AND payload::jsonb->>'component'='value' GROUP BY 1 HAVING count(*) >= 500) seeded;" 2>/dev/null) ||
    { echo 'Cannot query seeded forecast history.'; return 1; }
  count=$(printf '%s' "$count" | tr -d '\r\n ')
  [[ $count == 5 ]] || { echo "Only ${count:-0}/5 metrics have >=500 historical observations."; return 1; }
  metrics=$(curl --max-time 10 -fsS "http://127.0.0.1:${EXPLORER_PORT}/api/metrics") ||
    { echo 'Metrics API not available.'; return 1; }
  for id in unshipped backlog creep jitter shift; do
    [[ $metrics == *"\"id\":\"demo-live-$id\""* ]] ||
      { echo "Metric demo-live-$id is missing."; return 1; }
  done
  forecasts=$(curl --max-time 10 -fsS "http://127.0.0.1:${EXPLORER_PORT}/api/forecasts") ||
    { echo 'Forecast API not available.'; return 1; }
  [[ $forecasts == *'"metricId":"demo-live-unshipped"'* ]] ||
    { echo 'Unshipped-orders series is absent.'; return 1; }
  [[ $forecasts == *'"state":"READY"'* ]] ||
    { echo 'No forecast result is READY yet.'; return 1; }
  [[ $forecasts == *'"strategy":"TIMESFM"'* ]] ||
    { echo 'TimesFM has not returned a forecast.'; return 1; }
  echo 'Ready: Kafka, PostgreSQL, Explorer, TimesFM, live traffic, five histories (>=500 points) and TimesFM forecast.'
}
verify_forecast() {
  [[ $DEMO_MODE == forecast ]] || { echo 'verify is available in forecast mode only.' >&2; return 2; }
  verify_forecast_once
}
wait_forecast_ready() {
  local deadline=$((SECONDS + ${DEMO_FORECAST_READY_TIMEOUT:-900})) reason
  echo 'Waiting for live data, five seeded histories and first READY TimesFM forecast...'
  while :; do
    if reason=$(verify_forecast_once); then echo "$reason"; return 0; fi
    if (( SECONDS >= deadline )); then
      echo "ERROR: demo did not become fully ready: $reason" >&2
      echo 'Check: ./demo-forecast.sh logs demo-forecast-seed; ./demo-forecast.sh logs timesfm' >&2
      return 1
    fi
    sleep 5
  done
}
set_scenario() {
  [[ $DEMO_MODE == forecast ]] || { echo 'scenario is available in forecast mode only.' >&2; return 2; }
  local name=${1:-} file="${FORECAST_CONFIG_DIR}/demo-control/demo-scenario.conf"
  if [[ -z $name ]]; then
    if [[ -f $file ]]; then IFS='|' read -r current _ < "$file" || true; echo "${current:-normal}"; else echo normal; fi
    return 0
  fi
  case $name in normal|overload|incident|recover|seasonal) ;; *)
    echo 'Usage: scenario [normal|overload|incident|recover|seasonal]' >&2; return 2 ;; esac
  mkdir -p "${FORECAST_CONFIG_DIR}/demo-control"
  chmod 0777 "${FORECAST_CONFIG_DIR}/demo-control"
  printf '%s\n' "$name" > "$file"
  echo "Live Kafka scenario set to $name (next generator tick, default 15 s)."
}

wait_for_http() {
  local name=$1 url=$2 seconds=$3 deadline=$((SECONDS + $3))
  shift 3
  printf 'Waiting for %s ' "$name"
  until curl -fsS -o /dev/null "$@" "$url" 2>/dev/null; do
    if ((SECONDS >= deadline)); then
      echo "— not ready after ${seconds} s. See: ./demo.sh logs"
      return 1
    fi
    printf '.'
    sleep 5
  done
  echo ' ok'
}

up() {
  # What the banners say about the model, from the one place that decides it.
  local llm_line assistant_where
  if [[ $DEMO_LLM == local ]]; then
    llm_line="kex-llm ${LLAMA_CPP_MODEL_NAME:-gemma-4-e4b-it} on llama.cpp, local (Explorer, agent); http://localhost:${LLAMA_CPP_PORT:-8083}"
    assistant_where='table and column names stay on this machine'
  else
    llm_line="OpenRouter ${OPENROUTER_MODEL} (Explorer, agent)"
    assistant_where='table and column names go to OpenRouter'
  fi
  # The agent's bearer, before the long part (build, pulls): it is what the Control Center asks for.
  if [[ $DEMO_MODE == full || $DEMO_AGENT == true ]]; then
    echo "Kex-anHarness bearer (KEX_AGENT_API_KEY): ${KEX_AGENT_API_KEY} -- http://localhost:${KEX_AGENT_PORT}"
  fi
  if [[ ($DEMO_MODE == full || $DEMO_AGENT == true) && $DEMO_LLM == openrouter && -z $OPENROUTER_API_KEY ]]; then
    echo 'WARN: OPENROUTER_API_KEY is empty — Process Mining answers without its LLM half and the' >&2
    echo '      agent starts DEGRADED. Export it (or put it in .env) and rerun ./demo.sh.' >&2
  fi
  if [[ $DEMO_LLM == local && ($DEMO_MODE == full || $DEMO_AGENT == true) ]]; then
    echo 'kex-llm adds a ~5.5 GB image (Gemma 4 E4B inside), pulled once: the Explorer and the agent both answer from it.'
  fi
  if [[ $DEMO_MODE == forecast ]]; then
    # A dedicated writable demo-only mount survives forecast-stack.sh --prepare-only,
    # which intentionally restores mode 0755 on the *parent* config directory for
    # every CLI invocation (including "scenario" and "verify").
    mkdir -p "${FORECAST_CONFIG_DIR}/demo-control"
    chmod 0777 "${FORECAST_CONFIG_DIR}/demo-control"
  fi
  if [[ $DEMO_PREBUILT == true ]]; then
    for secret in FORECAST_POSTGRES_PASSWORD TIMESFM_TOKEN EXPLORER_MCP_AUTH_TOKEN; do
      [[ -n ${!secret:-} ]] || { echo "Missing $secret. Check .env and .forecast-stack/.env." >&2; exit 1; }
    done
    doctor
    local with_agent=''
    [[ $DEMO_AGENT != true && $DEMO_MODE != full ]] || with_agent=' and the agent'
    echo "Prebuilt demo: published KafkaExplorer and TimesFM images${with_agent}; no local build or model download."
    echo "TimesFM resources: ${TIMESFM_CPUS} CPUs / ${TIMESFM_MEM_LIMIT}. Model licence: services/timesfm/licenses/NOTICE.md"
    compose config --quiet
    if [[ $DEMO_OFFLINE != true ]]; then compose pull --quiet --ignore-buildable; fi
    compose up -d --no-build --pull never --remove-orphans
  else
    echo "First run: Maven build of the Explorer and the pinned TimesFM checkpoint (${TIMESFM_CPUS} CPUs / ${TIMESFM_MEM_LIMIT})."
    [[ $DEMO_SPECTRA != true ]] || echo 'SpectraLLM adds ~4.8 GB of weights; its 7B chat model wants ~16 GB of RAM.'
    echo 'Later runs start in seconds.'
    [[ $DEMO_PUBLISHED_IMAGE != true ]] || compose pull --quiet --ignore-buildable
    # --remove-orphans: switching to --no-spectra stops the SpectraLLM containers a previous run left.
    compose up -d --build --remove-orphans
  fi
  wait_for_http 'Kafka Explorer' "http://127.0.0.1:${EXPLORER_PORT}/actuator/health" 900
  # The server is healthy once the weights are loaded: until then the first answer of either app would
  # only queue. Informational, like SpectraLLM's wait below.
  if [[ $DEMO_LLM == local && ($DEMO_MODE == full || $DEMO_AGENT == true) ]]; then
    wait_for_http 'kex-llm' "http://127.0.0.1:${LLAMA_CPP_PORT:-8083}/health" 600 || true
  fi
  if [[ $DEMO_MODE == forecast ]]; then
    wait_forecast_ready
    local agent_line='LLM, agent, SpectraLLM  not started (--no-agent / --offline): nothing to configure'
    if [[ $DEMO_AGENT == true ]]; then
      wait_for_http 'Kex-anHarness' "http://127.0.0.1:${KEX_AGENT_PORT}/api/agent/mcp/servers" 300 \
        -H "Authorization: Bearer ${KEX_AGENT_API_KEY}" || true
      agent_line="Kex-anHarness ......... http://localhost:${KEX_AGENT_PORT}     (Bearer ${KEX_AGENT_API_KEY})
LLM ................... ${llm_line}; SpectraLLM not started"
    fi
    cat <<EOF

Kafka SQL Explorer .... http://localhost:${EXPLORER_PORT}   -> Metrics
  MCP ................. http://localhost:${EXPLORER_PORT}/mcp   (Bearer in .forecast-stack/.env or EXPLORER_MCP_AUTH_TOKEN)
Schema Registry ....... http://localhost:${SCHEMA_REGISTRY_PORT}
Kafka ................. localhost:${KAFKA_PORT}
${agent_line}

The forecast demo: Metrics has five live metrics (demo.live.*: orders, requests, imports, events, tickets; each minus its second topic).
  Their history is seeded in the first minutes (./demo.sh --forecast logs demo-forecast-seed), then:
  - the unshipped-orders one is already forecast, with its "Warning expected in ~N min" badge;
  - press Forecast on another (or "Forecast all eligible"): the backlog's hourly cycle is found, the imports climb, the events
    stay flat with a wide band, the tickets change level and no forecast sees it coming — the Previous forecast vs actual view shows it.
  - Stream Flow: trace the key orders-1 (or any orders-N): it is found in demo.live.orders and in demo.live.shipped.
  ./demo-forecast.sh doctor | verify | scenario [normal|overload|incident|recover|seasonal] | down | reset | ps | logs [service] | probe     (docs/metrics-demo-scenarios.md)
EOF
    return 0
  fi
  wait_for_http 'Kex-anHarness' "http://127.0.0.1:${KEX_AGENT_PORT}/api/agent/mcp/servers" 300 \
    -H "Authorization: Bearer ${KEX_AGENT_API_KEY}" || true
  # spectra-api waits for the embedding model and the demo seed; its chat answers once llm-chat
  # has its weights, which `./demo.sh logs spectra-models llm-chat` follows.
  if [[ $DEMO_SPECTRA == true ]]; then
    wait_for_http 'SpectraLLM API' "http://127.0.0.1:${SPECTRA_API_PORT}/actuator/health/readiness" 600 || true
  fi
  local spectra='SpectraLLM ............ off (--no-spectra); the agent declares no SpectraLLM connection'
  [[ $DEMO_SPECTRA != true ]] || spectra="SpectraLLM UI ......... http://localhost:${SPECTRA_UI_PORT}     (401 while SPECTRA_API_KEY is set)
SpectraLLM API ........ http://localhost:${SPECTRA_API_PORT}/swagger-ui   (/mcp, X-API-Key ${SPECTRA_API_KEY}) · ${LLM_CHAT_MODEL_NAME} local"
  cat <<EOF

Kafka SQL Explorer .... http://localhost:${EXPLORER_PORT}
  MCP ................. http://localhost:${EXPLORER_PORT}/mcp   (Bearer in .forecast-stack/.env or EXPLORER_MCP_AUTH_TOKEN)
Kex-anHarness ......... http://localhost:${KEX_AGENT_PORT}     (Bearer ${KEX_AGENT_API_KEY})
${spectra}
Schema Registry ....... http://localhost:${SCHEMA_REGISTRY_PORT}
Kafka ................. localhost:${KAFKA_PORT}
LLM ................... ${llm_line}
SQL assistant ......... ${EXPLORER_ASSISTANT_ENABLED} (editor ask bar, Suggest a fix, Metrics: Describe the metric; ${assistant_where})

Demo topics (JSON + Avro) are seeded in the background: ./demo.sh logs demo-setup
Forecast: Metrics → the five demo.live.* metrics have 9 h of seeded history (live traffic keeps them moving):
  the unshipped-orders one is already forecast; press Forecast on the others, or Forecast all eligible. ./demo.sh logs demo-forecast-seed
EOF
}

case ${1:-up} in
  up) [[ $# -le 1 ]] || usage; up ;;
  down) compose down --remove-orphans ;;
  # Volumes included: topics, forecast history, agent state, downloaded models. Credentials stay.
  reset) compose down -v --remove-orphans ;;
  ps) compose ps ;;
  logs) shift; compose logs -f --tail 200 "$@" ;;
  doctor) [[ $# -eq 1 ]] || usage; doctor ;;
  verify) [[ $# -eq 1 ]] || usage; verify_forecast ;;
  scenario) [[ $# -le 2 ]] || usage; set_scenario "${2:-}" ;;
  probe)
    compose --profile probe run --rm mcp-probe
    [[ $DEMO_SPECTRA != true ]] || compose --profile probe run --rm spectra-mcp-probe
    ;;
  *) usage ;;
esac

