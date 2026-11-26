#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
#
# Picks the demo options for you and runs ./demo.sh with the matching flags.
#
#   ./demo-menu.sh                       # ask, recap, check the key, confirm, run
#   ./demo-menu.sh --dry-run             # the same, run nothing
#   ./demo-menu.sh --preset forecast     # skip the questions (--presets lists them)
#   ./demo-menu.sh --preset forecast --yes   # ...and the confirmation: for scripts
#
# Only coherent combinations are offered: SpectraLLM exists in the full suite only; published
# images and offline are forecast-only; offline cannot start the agent (its image is not baked in).
# Each question has a default (Enter). The flags are demo.sh's own (documented in its header), so
# nothing here is a second source of truth for what a demo is.
#
# Credentials: the launcher shows each token with where it comes from (environment, .env, generated
# in .forecast-stack/.env, or the dev-only default), masked unless you ask. It writes a secret only
# when you say so: the OpenRouter key into .env on request, the agent and MCP tokens on "renew".
# The state file .forecast-stack/last-demo holds the last choice and no secret.
set -euo pipefail
cd "$(dirname "$0")"

state_dir=.forecast-stack
last_file=$state_dir/last-demo

usage() {
  cat >&2 <<'USAGE'
Usage: ./demo-menu.sh [--dry-run] [--yes] [--preset NAME] | --presets | --help
USAGE
  exit "${1:-2}"
}

presets() {
  cat <<'PRESETS'
full               Full suite: Kafka, Avro, MCP, forecasts, SpectraLLM, agent
full-no-spectra    Full suite without SpectraLLM
forecast           Forecast demo, built from this checkout, with the agent
forecast-no-agent  Forecast demo, built from this checkout, without the agent
forecast-prebuilt  Forecast demo from published images, with the agent
forecast-offline   Forecast demo, models baked in, no download, no agent
PRESETS
}

dry_run=false
assume_yes=false
preset=''
while [[ $# -gt 0 ]]; do
  case $1 in
    --dry-run) dry_run=true ;;
    --yes) assume_yes=true ;;
    --preset) shift; preset=${1:-}; [[ -n $preset ]] || usage ;;
    --presets) presets; exit 0 ;;
    -h | --help) usage 0 ;;
    *) usage ;;
  esac
  shift
done

# ── Questions ────────────────────────────────────────────────────────────────

# ask "Question" default "label1" "label2" ... -> sets REPLY_INDEX (1-based)
ask() {
  local question=$1 default=$2 i answer n=1
  shift 2
  echo
  echo "$question"
  for i in "$@"; do
    printf '  %d) %s%s\n' "$n" "$i" "$([[ $n == "$default" ]] && echo '  [default]')"
    n=$((n + 1))
  done
  while true; do
    read -r -p "Choice [${default}]: " answer || answer=
    answer=${answer:-$default}
    if [[ $answer =~ ^[0-9]+$ ]] && ((answer >= 1 && answer <= $#)); then
      REPLY_INDEX=$answer
      return 0
    fi
    echo "Enter a number between 1 and $#."
  done
}

# ── What a demo is: mode (full|nospectra|forecast), source (build|prebuilt|offline), agent (yes|no)

mode=''
source_kind=build
agent=yes

preset_apply() {
  source_kind=build agent=yes
  case $1 in
    full) mode=full ;;
    full-no-spectra) mode=nospectra ;;
    forecast) mode=forecast ;;
    forecast-no-agent) mode=forecast agent=no ;;
    forecast-prebuilt) mode=forecast source_kind=prebuilt ;;
    forecast-offline) mode=forecast source_kind=offline agent=no ;;
    *) echo "Unknown preset '$1'. Known presets:" >&2; presets >&2; exit 2 ;;
  esac
}

describe() { # mode source agent
  case $1 in
    full) echo 'Full suite with SpectraLLM'; return ;;
    nospectra) echo 'Full suite without SpectraLLM'; return ;;
  esac
  local s a
  case $2 in build) s='built here' ;; prebuilt) s='published images' ;; offline) s='offline' ;; esac
  [[ $3 == yes ]] && a='with the agent' || a='without the agent'
  echo "Forecast demo, $s, $a"
}

flags_for() { # fills the global array flags
  flags=()
  case $mode in
    nospectra) flags+=(--no-spectra) ;;
    forecast)
      flags+=(--forecast)
      case $source_kind in prebuilt) flags+=(--prebuilt) ;; offline) flags+=(--offline) ;; esac
      [[ $source_kind == offline || $agent == yes ]] || flags+=(--no-agent)
      ;;
  esac
}

ask_demo() {
  ask 'Which demo?' 1 \
    'Full suite: Kafka, Avro, MCP, forecasts, SpectraLLM, agent (needs OPENROUTER_API_KEY, ~4.8 GB of weights)' \
    'Full suite without SpectraLLM (needs OPENROUTER_API_KEY)' \
    'Forecast demo: live metrics and forecasts, the agent included'
  source_kind=build agent=yes
  case $REPLY_INDEX in 1) mode=full ;; 2) mode=nospectra ;; 3) mode=forecast ;; esac
  [[ $mode == forecast ]] || return 0
  ask 'Where do the images come from?' 1 \
    'Built from this checkout (Maven + the TimesFM download on the first run)' \
    'Published images, nothing built (needs a pull)' \
    'Offline: published images with the models baked in, no download, no agent'
  case $REPLY_INDEX in 1) source_kind=build ;; 2) source_kind=prebuilt ;; 3) source_kind=offline ;; esac
  if [[ $source_kind == offline ]]; then
    agent=no
  else
    ask 'Start the Kex-anHarness agent?' 1 \
      'Yes (it starts degraded without OPENROUTER_API_KEY)' \
      'No'
    [[ $REPLY_INDEX == 1 ]] && agent=yes || agent=no
  fi
}

# "Same as last time" first, when there is a last time.
choose_demo() {
  local l_mode='' l_source='' l_agent='' line
  if [[ -f $last_file ]]; then
    while IFS= read -r line; do
      case $line in
        mode=*) l_mode=${line#mode=} ;;
        source=*) l_source=${line#source=} ;;
        agent=*) l_agent=${line#agent=} ;;
      esac
    done < "$last_file"
  fi
  if [[ $l_mode =~ ^(full|nospectra|forecast)$ && $l_source =~ ^(build|prebuilt|offline)$ && $l_agent =~ ^(yes|no)$ ]]; then
    ask 'Which demo?' 1 "Same as last time: $(describe "$l_mode" "$l_source" "$l_agent")" 'Choose again'
    if [[ $REPLY_INDEX == 1 ]]; then
      mode=$l_mode source_kind=$l_source agent=$l_agent
      return 0
    fi
  fi
  ask_demo
}

remember_demo() {
  mkdir -p "$state_dir"
  printf 'mode=%s\nsource=%s\nagent=%s\n' "$mode" "$source_kind" "$agent" > "$last_file"
}

# ── Credentials ──────────────────────────────────────────────────────────────

# read_kv FILE KEY -> the value of the first KEY=value line, quotes and CR stripped
read_kv() {
  [[ -f $1 ]] || return 0
  local line value
  line=$(grep -m1 "^$2=" "$1" 2>/dev/null || true)
  value=${line#*=}
  value=${value%$'\r'}
  value=${value#[\"\']}
  value=${value%[\"\']}
  printf '%s' "$value"
}

# The defaults demo.sh falls back to when nothing else sets the variable.
fallback_of() {
  case $1 in
    KEX_AGENT_API_KEY) echo dev-only-agent-key ;;
    EXPLORER_MCP_AUTH_TOKEN) echo dev-only-mcp-token ;;
    SPECTRA_API_KEY) echo dev-only-spectra-key ;;
  esac
}

# Same precedence as demo.sh: environment > .env > .forecast-stack/.env > default.
origin_of() {
  local key=$1
  if [[ -n ${!key:-} ]]; then echo 'environment'; return; fi
  if [[ -n $(read_kv .env "$key") ]]; then echo '.env'; return; fi
  if [[ -n $(read_kv "$state_dir/.env" "$key") ]]; then echo 'generated'; return; fi
  case $key in
    KEX_AGENT_API_KEY | EXPLORER_MCP_AUTH_TOKEN)
      if [[ ! -f $state_dir/.env ]]; then echo 'generated at the first start'; return; fi ;;
  esac
  if [[ -n $(fallback_of "$key") ]]; then echo 'dev-only default (keep the port private)'; else echo 'not set'; fi
}

value_of() {
  local key=$1 v
  v=${!key:-}
  [[ -n $v ]] || v=$(read_kv .env "$key")
  [[ -n $v ]] || v=$(read_kv "$state_dir/.env" "$key")
  [[ -n $v ]] || v=$(fallback_of "$key")
  printf '%s' "$v"
}

mask() {
  local v=$1
  if [[ -z $v ]]; then printf '%s' '-'
  elif ((${#v} > 12)); then printf '%s...%s' "${v:0:4}" "${v: -4}"
  else printf '%s' '****'; fi
}

# show_tokens reveal KEY...
show_tokens() {
  local reveal=$1 key v shown
  shift
  echo
  echo 'Credentials'
  for key in "$@"; do
    v=$(value_of "$key")
    if [[ -z $v ]]; then shown='-'
    elif [[ $reveal == true ]]; then shown=$v
    else shown=$(mask "$v"); fi
    printf '  %-24s %-20s %s\n' "$key" "$shown" "($(origin_of "$key"))"
  done
}

copy_to_clipboard() { # the value goes through stdin, never through the argument list
  local v=$1
  if command -v pbcopy > /dev/null; then printf '%s' "$v" | pbcopy
  elif command -v wl-copy > /dev/null; then printf '%s' "$v" | wl-copy
  elif command -v xclip > /dev/null; then printf '%s' "$v" | xclip -selection clipboard
  elif command -v clip.exe > /dev/null; then printf '%s' "$v" | clip.exe
  else return 1; fi
}

random_token() { openssl rand -hex 32; }

renew_tokens() {
  local f=$state_dir/.env tmp key note=''
  if [[ ! -f $f ]]; then
    echo 'No generated credentials yet: they are created at the first start.'
    return 0
  fi
  command -v openssl > /dev/null || { echo 'openssl is required to generate a token.' >&2; return 1; }
  for key in KEX_AGENT_API_KEY EXPLORER_MCP_AUTH_TOKEN; do
    [[ $(origin_of "$key") == generated ]] || note="$note $key"
  done
  echo
  echo 'This replaces KEX_AGENT_API_KEY and EXPLORER_MCP_AUTH_TOKEN in .forecast-stack/.env.'
  echo 'The PostgreSQL and TimesFM credentials are left alone: the database volume holds the'
  echo 'password, and only "reset" starts over. Restart the demo so the agent and the Explorer'
  echo 'pick the new values up.'
  [[ -z $note ]] || echo "Note: set elsewhere, so a new generated value will NOT apply:$note"
  local go
  read -r -p 'Renew them? [y/N] ' go || go=n
  [[ $go =~ ^[Yy]$ ]] || { echo 'Nothing changed.'; return 0; }
  tmp=$(umask 077; mktemp "$state_dir/.env.XXXXXX")
  local agent_token mcp_token
  agent_token=$(random_token) mcp_token=$(random_token)
  awk -v a="$agent_token" -v m="$mcp_token" 'BEGIN { FS = OFS = "=" }
    $1 == "KEX_AGENT_API_KEY" { print $1, a; seen_a = 1; next }
    $1 == "EXPLORER_MCP_AUTH_TOKEN" { print $1, m; seen_m = 1; next }
    { print }
    END { if (!seen_a) print "KEX_AGENT_API_KEY=" a; if (!seen_m) print "EXPLORER_MCP_AUTH_TOKEN=" m }' "$f" > "$tmp"
  chmod 600 "$tmp"
  mv "$tmp" "$f"
  echo 'Renewed. Restart the demo (stop, then start) to apply them.'
}

# OpenRouter: ok | invalid | unknown. The key travels on stdin to curl, never on its command line.
check_key() {
  local code
  code=$(printf 'header = "Authorization: Bearer %s"\n' "$1" |
    curl -s -o /dev/null -w '%{http_code}' --max-time 8 -K - https://openrouter.ai/api/v1/auth/key 2> /dev/null) || code=000
  case $code in
    200) echo ok ;;
    401 | 403) echo invalid ;;
    *) echo unknown ;;
  esac
}

save_key() {
  local last
  if [[ ! -e .env ]]; then (umask 077; : > .env)
  else
    last=$(tail -c1 .env 2> /dev/null || true)
    [[ -z $last ]] || printf '\n' >> .env
  fi
  printf 'OPENROUTER_API_KEY=%s\n' "$1" >> .env
  echo 'Saved in .env (git-ignored).'
}

# Asks for the key when a model is involved and none is set, checks it, offers to keep it.
ensure_key() {
  local key=${OPENROUTER_API_KEY:-} origin=environment result go
  [[ -n $key ]] || { key=$(read_kv .env OPENROUTER_API_KEY); origin=.env; }
  while true; do
    if [[ -z $key ]]; then
      [[ -t 0 ]] || return 0
      read -r -s -p 'OPENROUTER_API_KEY (Enter to skip: the agent and Process Mining start degraded): ' key || key=
      echo
      [[ -n $key ]] || return 0
      origin=typed
    fi
    result=$(check_key "$key")
    case $result in
      ok) echo 'OpenRouter key checked: valid.'; break ;;
      unknown) echo 'OpenRouter key not checked (no answer from openrouter.ai): continuing.'; break ;;
      invalid)
        echo "OpenRouter rejected this key ($origin)."
        [[ -t 0 ]] || break
        ask 'What now?' 1 'Enter another key' 'Continue anyway (the agent and Process Mining start degraded)' 'Cancel'
        case $REPLY_INDEX in
          1) key=''; continue ;;
          2) [[ $origin != typed ]] || key=''; break ;;
          3) echo 'Cancelled.'; exit 0 ;;
        esac
        ;;
    esac
  done
  if [[ $origin == typed && -n $key ]]; then
    export OPENROUTER_API_KEY=$key
    if [[ -t 0 && $result != invalid ]]; then
      read -r -p 'Save it in .env for next time? [y/N] ' go || go=n
      [[ ! $go =~ ^[Yy]$ ]] || save_key "$key"
    fi
  fi
}

# ── Recap ────────────────────────────────────────────────────────────────────

recap() {
  echo
  echo "Demo: $(describe "$mode" "$source_kind" "$agent")"
  echo 'What will start'
  printf '  %-36s %s\n' 'Kafka + demo topics' "localhost:${KAFKA_PORT:-9092}"
  printf '  %-36s %s\n' 'Kafka SQL Explorer + MCP' "http://localhost:${EXPLORER_PORT:-8080}"
  printf '  %-36s %s\n' 'Schema Registry' "http://localhost:${SCHEMA_REGISTRY_PORT:-8085}"
  printf '  %-36s %s\n' 'PostgreSQL + TimesFM + live traffic' "${TIMESFM_CPUS:-4.0} CPUs / ${TIMESFM_MEM_LIMIT:-8g} for TimesFM"
  if [[ $agent == yes || $mode != forecast ]]; then
    printf '  %-36s %s\n' 'Kex-anHarness agent' "http://localhost:${KEX_AGENT_PORT:-8081}"
  fi
  if [[ $mode == full ]]; then
    printf '  %-36s %s\n' 'SpectraLLM' "UI :${SPECTRA_UI_PORT:-8088}, API :${SPECTRA_API_PORT:-8089} - ~4.8 GB of weights, ~16 GB of RAM for the 7B model"
  fi
  case $source_kind in
    build) echo '  First start: Maven build of the Explorer and the TimesFM checkpoint download.' ;;
    prebuilt) echo '  Pulls the published images; nothing is built.' ;;
    offline) echo '  No download: the images must already be on this machine.' ;;
  esac
}

model_involved() { [[ $mode != forecast || ( $agent == yes && $source_kind != offline ) ]]; }

tokens_for_demo() { # fills the global array token_names
  token_names=(EXPLORER_MCP_AUTH_TOKEN)
  if [[ $agent == yes || $mode != forecast ]]; then token_names+=(KEX_AGENT_API_KEY); fi
  if [[ $mode == full ]]; then token_names+=(SPECTRA_API_KEY); fi
  if model_involved; then token_names+=(OPENROUTER_API_KEY); fi
}

# ── Main ─────────────────────────────────────────────────────────────────────

verb=up
if [[ -n $preset ]]; then
  preset_apply "$preset"
else
  ask 'What do you want to do?' 1 \
    'Start the demo' \
    'Stop it (keeps the data)' \
    'Reset it (stops and deletes the data)' \
    'Show the services (ps)' \
    'Follow the logs' \
    'Show the credentials' \
    'Renew the agent and MCP tokens'
  verb=$(printf '%s\n' up down reset ps logs creds renew | sed -n "${REPLY_INDEX}p")
  case $verb in
    creds)
      show_tokens false KEX_AGENT_API_KEY EXPLORER_MCP_AUTH_TOKEN SPECTRA_API_KEY OPENROUTER_API_KEY
      read -r -p 'Reveal them? [y/N] ' go || go=n
      [[ ! $go =~ ^[Yy]$ ]] || show_tokens true KEX_AGENT_API_KEY EXPLORER_MCP_AUTH_TOKEN SPECTRA_API_KEY OPENROUTER_API_KEY
      exit 0
      ;;
    renew) renew_tokens; exit 0 ;;
  esac
  # down, reset, ps and logs name the same stack the demo was started with: the compose files it
  # layered decide which services exist.
  choose_demo
fi

service=''
if [[ $verb == logs && -z $preset ]]; then
  read -r -p 'Service name (empty = all): ' service || service=
fi

flags_for
command=(./demo.sh "${flags[@]}")
[[ $verb == up ]] || command+=("$verb")
[[ -z $service ]] || command+=("$service")

if [[ $verb == up ]]; then
  recap
  if model_involved; then ensure_key; fi
  tokens_for_demo
  show_tokens false "${token_names[@]}"
fi

echo
echo "Command: ${command[*]}"
if [[ $dry_run == true ]]; then
  echo '(dry run: nothing started)'
  exit 0
fi

if [[ $assume_yes != true ]]; then
  while true; do
    if [[ $verb == up ]]; then
      prompt='Run it? [Y/n, r = reveal the tokens, c = copy the agent token] '
    else
      prompt='Run it? [Y/n] '
    fi
    read -r -p "$prompt" go || go=n
    case ${go:-y} in
      y | Y) break ;;
      n | N) echo 'Cancelled.'; exit 0 ;;
      r | R) [[ $verb != up ]] || show_tokens true "${token_names[@]}" ;;
      c | C)
        if [[ $verb == up ]] && copy_to_clipboard "$(value_of KEX_AGENT_API_KEY)"; then
          echo 'Agent token copied.'
        else
          echo 'No clipboard tool found (pbcopy, wl-copy, xclip, clip.exe).'
        fi
        ;;
      *) echo 'Answer y, n, r or c.' ;;
    esac
  done
fi

[[ $verb != up ]] || remember_demo
exec "${command[@]}"
