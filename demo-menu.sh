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
#   ./demo-menu.sh --preset full --model openai/gpt-4o-mini   # one run on another OpenRouter model
#   ./demo-menu.sh --preset full --llm local                  # the Explorer AND the agent on the kex-llm image's model
#
# Output: colour only on a terminal, never with NO_COLOR set or --no-color, and never alone: every
# colour is backed by words ("[default]", "valid", "rejected"), and every answer is a number or a
# letter, so a screen reader or a log reads the same thing a terminal shows.
# A demo that is already running is detected first, and the menu starts from what you can do to it.
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
#
# Which model answers for the agent and the Explorer: OpenRouter (hosted, a key, prompts leave the host) or
# the local one the published kex-llm image carries (Gemma 4 E4B on llama.cpp: no key, nothing leaves, a
# ~5.5 GB image). Asked when a model is involved and nothing names one, the last choice first; a preset
# takes OpenRouter; DEMO_LLM in the environment or a .env is shown and not asked about. The local choice
# skips everything below: there is no key to check and no OpenRouter model to pick.
#
# The OpenRouter model: asked when a model is involved and nothing already names one, with the last
# choice first. Precedence is demo.sh's own (the environment, .env, .forecast-stack/.env), and
# --model beats them for one run. Before the start the menu checks the model against the public
# catalogue (does it declare tool calling, which the agent needs) and, if you agree, with one tiny
# request. DEMO_MENU_OPENROUTER_URL points those checks elsewhere (tests); it does not reroute the stack.
set -euo pipefail
cd "$(dirname "$0")"

state_dir=.forecast-stack
last_file=$state_dir/last-demo

usage() {
  cat >&2 <<'USAGE'
Usage: ./demo-menu.sh [--dry-run] [--yes] [--no-color] [--preset NAME] [--llm openrouter|local] [--model ID] | --presets | --help
USAGE
  exit "${1:-2}"
}

presets() {
  cat <<'PRESETS'
full                   Full suite: Kafka, Avro, MCP, forecasts, SpectraLLM, agent (published images)
full-no-spectra        Full suite without SpectraLLM (published images)
forecast               Forecast demo with the agent (published images)
forecast-no-agent      Forecast demo without the agent (published images)
forecast-offline       Forecast demo, models baked in, no download, no agent
full-build             Full suite, built from this checkout
full-no-spectra-build  Full suite without SpectraLLM, built from this checkout
forecast-build         Forecast demo with the agent, built from this checkout
PRESETS
}

dry_run=false
assume_yes=false
no_color=false
preset=''
cli_model=''
cli_llm=''
# One definition of a model id, shared by the flag, the typed answer and the remembered one: it ends
# up in a JSON body and a state file, so nothing outside this alphabet gets through.
valid_model() { [[ ${#1} -le 100 && $1 =~ ^[A-Za-z0-9][A-Za-z0-9._:/+-]*$ ]]; }
default_model=openrouter/free
openrouter_base=${DEMO_MENU_OPENROUTER_URL:-https://openrouter.ai/api/v1}
openrouter_base=${openrouter_base%/}
while [[ $# -gt 0 ]]; do
  case $1 in
    --dry-run) dry_run=true ;;
    --yes) assume_yes=true ;;
    --no-color) no_color=true ;;
    --preset) shift; preset=${1:-}; [[ -n $preset ]] || usage ;;
    --llm)
      shift; cli_llm=${1:-}
      [[ $cli_llm == openrouter || $cli_llm == local ]] || { echo "--llm expects openrouter or local, got '$cli_llm'." >&2; exit 2; }
      ;;
    --model)
      shift; cli_model=${1:-}
      valid_model "$cli_model" || { echo "--model expects an OpenRouter model id such as openai/gpt-4o-mini, got '$cli_model'." >&2; exit 2; }
      ;;
    --presets) presets; exit 0 ;;
    -h | --help) usage 0 ;;
    *) usage ;;
  esac
  shift
done

# ── Output ───────────────────────────────────────────────────────────────────

c_bold='' c_dim='' c_green='' c_yellow='' c_off=''
if [[ -t 1 && -z ${NO_COLOR:-} && ${TERM:-} != dumb && $no_color != true ]]; then
  c_bold=$'\e[1m' c_dim=$'\e[2m' c_green=$'\e[32m' c_yellow=$'\e[33m' c_off=$'\e[0m'
fi
title() { printf '%s%s%s\n' "$c_bold" "$*" "$c_off"; }
ok() { printf '%s%s%s\n' "$c_green" "$*" "$c_off"; }
warn() { printf '%s%s%s\n' "$c_yellow" "$*" "$c_off"; }

# ── Questions ────────────────────────────────────────────────────────────────

# ask "Question" default "label1" "label2" ... -> sets REPLY_INDEX (1-based)
ask() {
  local question=$1 default=$2 i answer n=1
  shift 2
  echo
  title "$question"
  for i in "$@"; do
    printf '  %d) %s%s\n' "$n" "$i" "$([[ $n == "$default" ]] && printf '  %s[default]%s' "$c_dim" "$c_off")"
    n=$((n + 1))
  done
  while true; do
    read -r -p "Choice [${default}]: " answer || answer=
    answer=${answer:-$default}
    if [[ $answer =~ ^[0-9]+$ ]] && ((answer >= 1 && answer <= $#)); then
      REPLY_INDEX=$answer
      return 0
    fi
    warn "Enter a number between 1 and $# (or press Enter for the default)."
  done
}

# ── What a demo is: mode (full|nospectra|forecast), source (build|prebuilt|offline), agent (yes|no)

mode=''
source_kind=prebuilt
agent=yes
llm=openrouter   # openrouter | local: what answers for the agent and the Explorer, once a model is involved
llm_origin=''    # --llm | environment | .env | generated | chosen | last | default
model=''         # the OpenRouter model, once model_involved and chosen
model_origin=''  # --model | environment | .env | generated | chosen | default

preset_apply() {
  source_kind=prebuilt agent=yes
  case $1 in
    full) mode=full source_kind=prebuilt ;;
    full-no-spectra) mode=nospectra source_kind=prebuilt ;;
    forecast | forecast-prebuilt) mode=forecast source_kind=prebuilt ;;
    forecast-no-agent) mode=forecast source_kind=prebuilt agent=no ;;
    forecast-offline) mode=forecast source_kind=offline agent=no ;;
    full-build) mode=full source_kind=build ;;
    full-no-spectra-build) mode=nospectra source_kind=build ;;
    forecast-build) mode=forecast source_kind=build ;;
    *) echo "Unknown preset '$1'. Known presets:" >&2; presets >&2; exit 2 ;;
  esac
}

describe() { # mode source agent
  local s a
  case $2 in build) s='built here' ;; prebuilt) s='published images' ;; offline) s='offline' ;; esac
  case $1 in
    full) echo "Full suite with SpectraLLM, $s"; return ;;
    nospectra) echo "Full suite without SpectraLLM, $s"; return ;;
  esac
  [[ $3 == yes ]] && a='with the agent' || a='without the agent'
  echo "Forecast demo, $s, $a"
}

flags_for() { # fills the global array flags
  flags=()
  case $mode in
    nospectra) flags+=(--no-spectra) ;;
    forecast) flags+=(--forecast) ;;
  esac
  case $source_kind in prebuilt) flags+=(--prebuilt) ;; offline) flags+=(--offline) ;; esac
  [[ $mode != forecast || $source_kind == offline || $agent == yes ]] || flags+=(--no-agent)
  # Only where a model is involved: a demo with no agent and no Explorer LLM half has nothing to point.
  ! model_involved || [[ $llm != local ]] || flags+=(--local-llm)
}

ask_demo() {
  ask 'Which demo?' 1 \
    'Full suite: Kafka, Avro, MCP, forecasts, SpectraLLM, agent (a model: an OpenRouter key or the local kex-llm; ~4.8 GB of SpectraLLM weights)' \
    'Full suite without SpectraLLM (a model: an OpenRouter key or the local kex-llm)' \
    'Forecast demo: live metrics and forecasts, the agent included'
  agent=yes
  case $REPLY_INDEX in 1) mode=full ;; 2) mode=nospectra ;; 3) mode=forecast ;; esac
  # Published images first: nothing is built, so no Maven, no Docker Hub base image, no TLS surprise
  # behind a proxy that re-signs certificates.
  local options=('Published images, nothing built (recommended)' \
    'Built from this checkout (Maven + base images from Docker Hub + the TimesFM download)')
  [[ $mode != forecast ]] || options+=('Offline: published images with the models baked in, no download, no agent')
  ask 'Where do the images come from?' 1 "${options[@]}"
  case $REPLY_INDEX in 1) source_kind=prebuilt ;; 2) source_kind=build ;; 3) source_kind=offline ;; esac
  [[ $mode == forecast ]] || return 0
  if [[ $source_kind == offline ]]; then
    agent=no
  else
    ask 'Start the Kex-anHarness agent?' 1 \
      'Yes (a model: an OpenRouter key, or the local kex-llm; degraded with neither)' \
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

# The model of the last interactive choice, empty when there is none or it is not a model id.
saved_model() {
  local m
  m=$(read_kv "$last_file" model)
  if valid_model "$m"; then printf '%s' "$m"; fi
}

# The model is rewritten only by an interactive choice: a one-off --model or a script's preset must not
# replace what the person picked last, and the state of a run that named no model keeps the old one.
remember_demo() {
  local m l
  m=$(saved_model)
  l=$(saved_llm)
  [[ $model_origin != chosen ]] || m=$model
  [[ $llm_origin != chosen ]] || l=$llm
  mkdir -p "$state_dir"
  printf 'mode=%s\nsource=%s\nagent=%s\n' "$mode" "$source_kind" "$agent" > "$last_file"
  if [[ -n $m ]]; then printf 'model=%s\n' "$m" >> "$last_file"; fi
  if [[ -n $l ]]; then printf 'llm=%s\n' "$l" >> "$last_file"; fi
}

# The last interactive choice of what answers: openrouter or local, empty when there is none.
saved_llm() {
  local v
  v=$(read_kv "$last_file" llm)
  if [[ $v == openrouter || $v == local ]]; then printf '%s' "$v"; fi
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
  title 'Credentials'
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
    echo 'No generated credentials yet: they are created at the first start. Start the demo once, then renew.'
    return 0
  fi
  command -v openssl > /dev/null || { echo 'openssl is required to generate a token: install it, then run this again.' >&2; return 1; }
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
  ok 'Renewed. Restart the demo (stop, then start) to apply them.'
}

# OpenRouter: ok | invalid | unknown. The key travels on stdin to curl, never on its command line.
check_key() {
  local code
  code=$(printf 'header = "Authorization: Bearer %s"\n' "$1" |
    curl -s -o /dev/null -w '%{http_code}' --max-time 8 -K - "$openrouter_base/auth/key" 2> /dev/null) || code=000
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
  ok 'Saved in .env (git-ignored).'
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
      ok) ok 'OpenRouter key checked: valid.'; break ;;
      unknown) warn 'OpenRouter key not checked (no answer from openrouter.ai): continuing.'; break ;;
      invalid)
        warn "OpenRouter rejected this key ($origin): it was refused with 401 or 403."
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
  title "Demo: $(describe "$mode" "$source_kind" "$agent")"
  echo 'What will start:'
  printf '  %-36s %s\n' 'Kafka + demo topics' "localhost:${KAFKA_PORT:-9092}"
  printf '  %-36s %s\n' 'Kafka SQL Explorer + MCP' "http://localhost:${EXPLORER_PORT:-8080}"
  printf '  %-36s %s\n' 'Schema Registry' "http://localhost:${SCHEMA_REGISTRY_PORT:-8085}"
  printf '  %-36s %s\n' 'PostgreSQL + TimesFM + live traffic' "${TIMESFM_CPUS:-4.0} CPUs / ${TIMESFM_MEM_LIMIT:-8g} for TimesFM"
  if [[ $agent == yes || $mode != forecast ]]; then
    printf '  %-36s %s\n' 'Kex-anHarness agent' "http://localhost:${KEX_AGENT_PORT:-8081}"
  fi
  if model_involved; then recap_llm; fi
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
# The OpenRouter half of that: its key, its model, its catalogue. The local model has none of them.
openrouter_involved() { model_involved && [[ $llm != local ]]; }

tokens_for_demo() { # fills the global array token_names
  token_names=(EXPLORER_MCP_AUTH_TOKEN)
  if [[ $agent == yes || $mode != forecast ]]; then token_names+=(KEX_AGENT_API_KEY); fi
  if [[ $mode == full ]]; then token_names+=(SPECTRA_API_KEY); fi
  if openrouter_involved; then token_names+=(OPENROUTER_API_KEY); fi
}

# ── What answers: OpenRouter or the local model ──────────────────────────────

# Where demo.sh would already read DEMO_LLM from, in its precedence. Prints "value origin"; nothing when
# none names a valid one.
named_llm() {
  local v
  v=${DEMO_LLM:-}
  if [[ $v == openrouter || $v == local ]]; then echo "$v environment"; return; fi
  v=$(read_kv .env DEMO_LLM)
  if [[ $v == openrouter || $v == local ]]; then echo "$v .env"; return; fi
  v=$(read_kv "$state_dir/.env" DEMO_LLM)
  if [[ $v == openrouter || $v == local ]]; then echo "$v generated"; fi
}

llm_label() {
  case $1 in
    openrouter) echo 'OpenRouter: a hosted model, needs a key, prompts and tool results leave this machine' ;;
    local) echo 'kex-llm: the local Gemma 4 E4B, no key, nothing leaves this machine (a ~5.5 GB image, slower on a CPU)' ;;
  esac
}

# Sets llm and llm_origin. Precedence is --llm, then what the environment or a .env names, then a
# question to a person (the last choice first), then the default. A preset takes the default, so a script
# gives the same demo whatever was picked last. A verb that only names the stack (down, ps, logs) is not
# asked: it takes the last choice, since the files compose layers depend on it.
choose_llm() {
  local named last first other options=() values=() v
  if [[ -n $cli_llm ]]; then llm=$cli_llm llm_origin=--llm; return 0; fi
  named=$(named_llm)
  if [[ -n $named ]]; then llm=${named%% *} llm_origin=${named#* }; return 0; fi
  last=$(saved_llm)
  if [[ $1 != up ]]; then llm=${last:-openrouter} llm_origin=last; return 0; fi
  llm=openrouter llm_origin=default
  [[ -z $preset ]] || return 0
  first=${last:-openrouter}
  [[ $first == openrouter ]] && other=local || other=openrouter
  values=("$first" "$other")
  for v in "${values[@]}"; do
    if [[ -n $last && $v == "$last" ]]; then options+=("$(llm_label "$v"), same as last time")
    else options+=("$(llm_label "$v")"); fi
  done
  ask 'Which model answers for the agent and the Explorer?' 1 "${options[@]}"
  llm=${values[REPLY_INDEX - 1]} llm_origin=chosen
}

recap_llm() {
  local where
  if [[ $llm != local ]]; then recap_model; return 0; fi
  case $llm_origin in
    --llm) where='--llm, this run only' ;;
    environment) where='DEMO_LLM, from the environment' ;;
    .env) where='DEMO_LLM, from .env' ;;
    generated) where='DEMO_LLM, from .forecast-stack/.env' ;;
    chosen) where='your choice' ;;
    last) where='the last choice' ;;
    *) where='default' ;;
  esac
  printf '  %-36s %s\n' 'LLM (agent and Explorer)' "kex-llm, Gemma 4 E4B on llama.cpp, local ($where)"
  printf '  %-36s %s\n' '' 'a ~5.5 GB image, pulled once; no OpenRouter key; one slot serves both apps'
}

# ── The model ────────────────────────────────────────────────────────────────

model_label() { # the line a menu entry shows for a model
  case $1 in
    "$default_model") echo "$1 (router over the free models: no credits needed)" ;;
    openai/gpt-4o-mini) echo "$1 (paid: reliable tool calling)" ;;
    *) echo "$1" ;;
  esac
}

# Where demo.sh would already read a model from, with the same precedence: environment, .env, then
# .forecast-stack/.env. Prints "value origin"; nothing when none of them names one.
named_model() {
  local v
  v=${OPENROUTER_MODEL:-}
  if [[ -n $v ]]; then echo "$v environment"; return; fi
  v=$(read_kv .env OPENROUTER_MODEL)
  if [[ -n $v ]]; then echo "$v .env"; return; fi
  v=$(read_kv "$state_dir/.env" OPENROUTER_MODEL)
  if [[ -n $v ]]; then echo "$v generated"; fi
}

# Sets model and model_origin. Asked only when a person is answering and nothing names a model; a
# preset takes the default, so a script gives the same demo whatever was picked last on this machine.
# `choose_model again` skips every shortcut: the person refused the model those gave.
choose_model() {
  local named last first options=() values=() typed
  if [[ ${1:-} != again ]]; then
    if [[ -n $cli_model ]]; then model=$cli_model model_origin=--model; return 0; fi
    named=$(named_model)
    if [[ -n $named ]]; then model=${named%% *} model_origin=${named#* }; return 0; fi
    model=$default_model model_origin=default
    [[ -z $preset ]] || return 0
  fi
  last=$(saved_model)
  first=${last:-$default_model}
  values+=("$first")
  [[ $first == "$default_model" ]] || values+=("$default_model")
  [[ $first == openai/gpt-4o-mini ]] || values+=(openai/gpt-4o-mini)
  for typed in "${values[@]}"; do
    if [[ -n $last && $typed == "$last" ]]; then options+=("$(model_label "$typed"), same as last time")
    else options+=("$(model_label "$typed")"); fi
  done
  options+=('Another model (type its OpenRouter id)')
  while true; do
    ask 'Which OpenRouter model?' 1 "${options[@]}"
    if ((REPLY_INDEX <= ${#values[@]})); then model=${values[REPLY_INDEX - 1]}; break; fi
    read -r -p 'Model id (provider/model, Enter to go back): ' typed || typed=
    [[ -n $typed ]] || continue
    if valid_model "$typed"; then model=$typed; break; fi
    warn "'$typed' is not an OpenRouter model id (letters, digits and . _ : / + - only)."
  done
  model_origin=chosen
}

# Does the public catalogue say this model takes tool calls? tools | no-tools | missing | unknown.
# A measure that could not be taken is unknown, never "no": offline, or without python3, nothing is claimed.
model_tools() {
  local json
  command -v python3 > /dev/null || { echo unknown; return; }
  json=$(curl -fsS --max-time 8 "$openrouter_base/models" 2> /dev/null) || { echo unknown; return; }
  printf '%s' "$json" | python3 -c '
import json, sys
try:
    rows = json.load(sys.stdin)["data"]
    row = next((r for r in rows if r.get("id") == sys.argv[1] or r.get("canonical_slug") == sys.argv[1]), None)
    if row is None:
        print("missing")
    else:
        print("tools" if "tools" in (row.get("supported_parameters") or []) else "no-tools")
except Exception:
    print("unknown")
' "$1" 2> /dev/null || echo unknown
}

# One tiny completion. ok | invalid-key | no-credits | unavailable | rate-limited | unknown, and the
# provider's own words, stripped of anything unprintable, on a second line when it gave some.
probe_model() {
  local key=$1 body code='' message=''
  body=$(mktemp)
  code=$(printf 'header = "Authorization: Bearer %s"\nheader = "Content-Type: application/json"\n' "$key" |
    curl -s -o "$body" -w '%{http_code}' --max-time 30 -K - \
      -d "{\"model\":\"$model\",\"max_tokens\":8,\"messages\":[{\"role\":\"user\",\"content\":\"ok\"}]}" \
      "$openrouter_base/chat/completions" 2> /dev/null) || code=000
  if command -v python3 > /dev/null; then
    message=$(python3 -c '
import json, sys
try:
    m = json.load(open(sys.argv[1]))["error"]["message"]
    print("".join(c for c in str(m) if c.isprintable())[:160])
except Exception:
    pass
' "$body" 2> /dev/null || true)
  fi
  rm -f "$body"
  case $code in
    200) echo ok ;;
    401 | 403) echo invalid-key ;;
    402) echo no-credits ;;
    404) echo unavailable ;;
    429) echo rate-limited ;;
    *) echo unknown ;;
  esac
  [[ -z $message ]] || echo "$message"
}

# Checks the chosen model before the start. Findings warn; they never block, except that an
# interactive run is offered the way out. Returns 1 when the person asked to choose another model.
verify_model() {
  local tools key result detail verdict='' go
  tools=$(model_tools "$model")
  case $tools in
    tools) ok "Model checked: $model declares tool calling." ;;
    no-tools) warn "$model does not declare tool calling on OpenRouter: the agent could not use its tools with it."; verdict=bad ;;
    missing)
      if [[ $model == openrouter/* ]]; then
        echo "$model is a router: the model behind it changes, so tool calling is not checked."
      else
        warn "$model is not in the OpenRouter catalogue: check the spelling (provider/model)."; verdict=bad
      fi ;;
    *) echo 'Model not checked against the catalogue (no answer from openrouter.ai, or no python3): continuing.' ;;
  esac
  # The probe spends a little of the quota, so it is asked, never assumed, and never on a dry run.
  key=$(value_of OPENROUTER_API_KEY)
  if [[ -z $verdict && -n $key && -t 0 && $assume_yes != true && $dry_run != true && -z $preset ]]; then
    read -r -p "Send one tiny request to $model to see that it answers? [y/N] " go || go=n
    if [[ $go =~ ^[Yy]$ ]]; then
      result=$(probe_model "$key")
      detail=$(printf '%s\n' "$result" | sed -n 2p)
      result=$(printf '%s\n' "$result" | sed -n 1p)
      case $result in
        ok) ok "$model answered." ;;
        invalid-key) warn 'OpenRouter refused the key for this request.'; verdict=bad ;;
        no-credits) warn "$model needs credits this account does not have."; verdict=bad ;;
        unavailable) warn "$model is not available to this account (404)."; verdict=bad ;;
        rate-limited) warn "$model is rate-limited right now (429): free models often are. It may work a moment later."; verdict=bad ;;
        *) warn "$model did not answer clearly: not checked further." ;;
      esac
      [[ -z $detail ]] || echo "  OpenRouter says: $detail"
    fi
  fi
  [[ $verdict == bad ]] || return 0
  [[ -t 0 && -z $preset ]] || return 0
  ask 'What now?' 1 'Choose another model' 'Continue anyway' 'Cancel'
  case $REPLY_INDEX in
    1) return 1 ;;
    3) echo 'Cancelled.'; exit 0 ;;
  esac
}

recap_model() {
  local where
  case $model_origin in
    --model) where='--model, this run only' ;;
    environment) where='from the environment' ;;
    .env) where='from .env' ;;
    generated) where='from .forecast-stack/.env' ;;
    chosen) where='your choice' ;;
    *) where='default' ;;
  esac
  printf '  %-36s %s\n' 'OpenRouter model' "$model ($where)"
}

# ── A demo that is already running ───────────────────────────────────────────

# Prints the running services, comma separated; nothing when there is no daemon or no demo.
running_services() {
  command -v docker > /dev/null || return 0
  local name=${COMPOSE_PROJECT_NAME:-$(read_kv .env COMPOSE_PROJECT_NAME)}
  name=${name:-kafka-explorer-demo}
  docker ps --filter "label=com.docker.compose.project=$name" \
    --format '{{.Label "com.docker.compose.service"}}' 2> /dev/null | sort | paste -sd, - | sed 's/,/, /g' || true
}

# ── Main ─────────────────────────────────────────────────────────────────────

verb=up
running=$(running_services)
if [[ -n $preset ]]; then
  preset_apply "$preset"
  [[ -z $running ]] || warn "A demo is already running ($running): start will reconcile it with these options."
else
  menu_index=''
  if [[ -n $running ]]; then
    ask "A demo is already running ($running). What do you want to do?" 1 \
      'Show the services (ps)' \
      'Follow the logs' \
      'Stop it (keeps the data)' \
      'Reset it (stops and deletes the data)' \
      'Start it again with other options' \
      'Something else (the full menu)'
    case $REPLY_INDEX in
      1) menu_index=4 ;;
      2) menu_index=5 ;;
      3) menu_index=2 ;;
      4) menu_index=3 ;;
      5) menu_index=1 ;;
    esac
  fi
  if [[ -n $menu_index ]]; then
    REPLY_INDEX=$menu_index
  else
    ask 'What do you want to do?' 1 \
      'Start the demo' \
      'Stop it (keeps the data)' \
      'Reset it (stops and deletes the data)' \
      'Show the services (ps)' \
      'Follow the logs' \
      'Show the credentials' \
      'Renew the agent and MCP tokens'
  fi
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

# What answers is settled before the command is built: it adds --local-llm, and the compose files
# a demo layers (so what down, ps and logs name) depend on it.
if model_involved; then
  choose_llm "$verb"
elif [[ $verb == up && -n $cli_llm ]]; then
  warn "This demo calls no model: --llm $cli_llm is ignored."
fi
flags_for
command=(./demo.sh "${flags[@]}")
[[ $verb == up ]] || command+=("$verb")
[[ -z $service ]] || command+=("$service")

if [[ $verb == up ]]; then
  if openrouter_involved; then
    choose_model
  elif [[ -n $cli_model ]]; then
    if model_involved; then warn "This demo runs on the local model, not OpenRouter: --model $cli_model is ignored."
    else warn "This demo calls no model: --model $cli_model is ignored."; fi
  fi
  recap
  # Exported for a run that chose or named it on the command line: demo.sh reads the environment first,
  # so --llm openrouter also overrides a DEMO_LLM=local the environment or .env set.
  if [[ $llm_origin == --llm || $llm_origin == chosen ]]; then export DEMO_LLM=$llm; fi
  if openrouter_involved; then
    ensure_key
    while ! verify_model; do
      choose_model again
      recap_model
    done
    # Exported only for a model this run chose: demo.sh reads the environment first, so it also
    # overrides one the environment or .env named when the person took another.
    if [[ $model_origin == --model || $model_origin == chosen ]]; then export OPENROUTER_MODEL=$model; fi
  fi
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
          ok 'Agent token copied.'
        else
          warn 'No clipboard tool found (pbcopy, wl-copy, xclip, clip.exe): press r, then copy the token by hand.'
        fi
        ;;
      *) warn 'Answer y to run, n to cancel, r to reveal the tokens, c to copy the agent token.' ;;
    esac
  done
fi

[[ $verb != up ]] || remember_demo
exec "${command[@]}"
