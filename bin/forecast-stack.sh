#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
set -euo pipefail
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_dir"
prepare_only=0
offline=0
for arg in "$@"; do
  case "$arg" in
    --prepare-only) prepare_only=1 ;;
    # The models come inside the image (compose/forecast-offline.yml): nothing is downloaded at start.
    --offline) offline=1 ;;
    *) echo 'Usage: bin/forecast-stack.sh [--prepare-only] [--offline]' >&2; exit 2 ;;
  esac
done
umask 077
state_dir=.forecast-stack
mkdir -p "$state_dir/config"
# Atomic no-clobber writes: subsequent runs retain credentials and reviewed configuration.
if [[ ! -e "$state_dir/.env" ]]; then
  command -v openssl >/dev/null || { echo 'openssl is required to generate credentials.' >&2; exit 1; }
  env_tmp=$(mktemp "$state_dir/.env.XXXXXX")
  trap 'rm -f "$env_tmp"' EXIT
  {
    echo "FORECAST_POSTGRES_PASSWORD=$(openssl rand -hex 32)"
    echo "TIMESFM_TOKEN=$(openssl rand -hex 32)"
    echo "EXPLORER_MCP_AUTH_TOKEN=$(openssl rand -hex 32)"
    echo 'FORECAST_ENVIRONMENTS=local'
  } > "$env_tmp"
  if ln "$env_tmp" "$state_dir/.env" 2>/dev/null; then :; fi
  rm -f "$env_tmp"
  trap - EXIT
fi
if [[ ! -e "$state_dir/config/forecasts.yml" ]]; then
  (set -o noclobber; printf '# Save the reviewed configuration assistant export here.\n{}\n' > "$state_dir/config/forecasts.yml") || true
fi
# The container runs as UID 10001; its mounted metadata must be readable. Secrets stay 0600
# under the private state directory and are never mounted as configuration.
chmod 755 "$state_dir/config"
chmod 644 "$state_dir/config/forecasts.yml"
if [[ $prepare_only == 1 ]]; then
  echo 'Prepared .forecast-stack/config/forecasts.yml and private credentials; no containers started.'
  exit 0
fi
command -v docker >/dev/null || { echo 'Docker with Compose v2 is required. Files are prepared; install Docker and rerun.' >&2; exit 1; }
files=(-f docker-compose.yml -f compose/forecasts.yml)
if [[ $offline == 1 ]]; then
  files+=(-f compose/forecast-offline.yml)
  echo 'Starting local forecasts from the offline image: TimesFM and Timestral are inside it, nothing is downloaded at start.'
  echo 'The Timestral weights are under a licence that excludes companies above USD 20 M monthly revenue: services/timesfm/licenses/NOTICE.md.'
else
  echo 'Starting local forecasts: first run downloads the pinned model; TimesFM defaults to 4 CPUs / 8 GiB.'
fi
echo 'Open http://localhost:8080, Metrics, and choose Prepare a forecast on a metric card; it starts without a restart.'
echo 'MCP URL: http://localhost:8080/mcp. The bearer credential is in .forecast-stack/.env.'
compose=(docker compose --env-file "$state_dir/.env" "${files[@]}")
if [[ $offline == 1 ]]; then
  # Not `--build`: it would rebuild the worker from its Dockerfile, which downloads the models from the Hub.
  # A tag that is not published yet is built locally by `up` instead, which needs the Hub once.
  "${compose[@]}" pull timesfm || echo 'The offline image could not be pulled; `up` will build it locally (this needs the Hub once).' >&2
  "${compose[@]}" up -d
else
  "${compose[@]}" up -d --build
fi
# Bind-file contents do not change Compose service hashes; Spring reads an exported forecasts.yml at startup.
exec "${compose[@]}" restart explorer
