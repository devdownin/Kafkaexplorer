#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
#
# Picks the demo options for you and runs ./demo.sh with the matching flags.
#
#   ./demo-menu.sh            # ask, show the command, confirm, run
#   ./demo-menu.sh --dry-run  # ask, print the command, run nothing
#
# Only coherent combinations are offered: SpectraLLM exists in the full suite only; published
# images and offline are forecast-only; offline cannot start the agent (its image is not baked in).
# Each question has a default (Enter). The flags are demo.sh's own, so nothing here is a second
# source of truth: the flags are documented in demo.sh's header.
set -euo pipefail
cd "$(dirname "$0")"

dry_run=false
[[ ${1:-} != --dry-run ]] || dry_run=true

# ask "Question" default "label1" "label2" ... -> sets $REPLY_INDEX (1-based)
ask() {
  local question=$1 default=$2 i answer
  shift 2
  echo
  echo "$question"
  local n=1
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

flags=()

ask 'What do you want to do?' 1 \
  'Start the demo' \
  'Stop it (keeps the data)' \
  'Reset it (stops and deletes the data)' \
  'Show the services (ps)' \
  'Follow the logs'
action=$REPLY_INDEX

case $action in
  1) ;;
  2) verb=down ;;
  3) verb=reset ;;
  4) verb=ps ;;
  5) verb=logs ;;
esac

# Down, reset, ps and logs must name the same stack the demo was started with: the compose files
# it layered decide which services exist.
ask 'Which demo?' 1 \
  'Full suite: Kafka, Avro, MCP, forecasts, SpectraLLM, agent (needs OPENROUTER_API_KEY, ~4.8 GB of weights)' \
  'Full suite without SpectraLLM (needs OPENROUTER_API_KEY)' \
  'Forecast demo: live metrics and forecasts, the agent included'
mode=$REPLY_INDEX

case $mode in
  1) ;;
  2) flags+=(--no-spectra) ;;
  3) flags+=(--forecast) ;;
esac

if [[ $mode == 3 ]]; then
  ask 'Where do the images come from?' 1 \
    'Built from this checkout (Maven + the TimesFM download on the first run)' \
    'Published images, nothing built (needs a pull)' \
    'Offline: published images with the models baked in, no download, no agent'
  case $REPLY_INDEX in
    1) ;;
    2) flags+=(--prebuilt) ;;
    3) flags+=(--offline) ;;
  esac
  if [[ $REPLY_INDEX != 3 ]]; then
    ask 'Start the Kex-anHarness agent?' 1 \
      'Yes (it starts degraded without OPENROUTER_API_KEY)' \
      'No'
    [[ $REPLY_INDEX == 1 ]] || flags+=(--no-agent)
  fi
fi

if [[ ${verb:-} == logs ]]; then
  read -r -p 'Service name (empty = all): ' service || service=
fi

command=(./demo.sh "${flags[@]}")
[[ -z ${verb:-} ]] || command+=("$verb")
[[ -z ${service:-} ]] || command+=("$service")

# The key matters when a model is involved: the full suite, or the forecast demo with the agent.
if [[ $action == 1 && -z ${OPENROUTER_API_KEY:-} ]] && [[ $mode != 3 || " ${flags[*]} " != *' --no-agent '* && " ${flags[*]} " != *' --offline '* ]]; then
  echo
  read -r -s -p 'OPENROUTER_API_KEY (Enter to skip: the agent and Process Mining start degraded): ' key || key=
  echo
  [[ -z $key ]] || export OPENROUTER_API_KEY=$key
fi

echo
echo "Command: ${command[*]}"
if [[ $dry_run == true ]]; then
  echo '(dry run: nothing started)'
  exit 0
fi
read -r -p 'Run it? [Y/n] ' go || go=n
[[ ${go:-Y} =~ ^[Yy]?$ ]] || { echo 'Cancelled.'; exit 0; }
exec "${command[@]}"
