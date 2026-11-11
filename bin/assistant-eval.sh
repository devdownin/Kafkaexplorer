#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
#
# Lot 0 of EMBEDDED-LLM-SCOPE.md: run the SQL assistant eval once per candidate model, through a
# local Ollama, and put the reports side by side.
#
#   bin/assistant-eval.sh                         # the four candidates below
#   MODELS="qwen3:4b-instruct gemma4:e2b" bin/assistant-eval.sh
#   ASSISTANT_EVAL_CASES=fix,filter bin/assistant-eval.sh   # a subset, by family or id
#   OFFLINE=1 bin/assistant-eval.sh               # through ./verify-offline.sh (Confluent blocked)
#
# Needs an Ollama reachable at OLLAMA_URL — `docker compose -f docker-compose.yml -f
# compose/ollama.yml up -d ollama` is enough; no broker is needed, the eval brings its own tables.
# Each model is pulled first (several GB the first time). Run it on the machine the assistant
# will serve from: latency on a laptop with a GPU says nothing about a CPU-only server.
#
# Output: target/assistant-eval/<model>.{json,md}, one per model, and summary.md with every
# model's "all" row. The decision thresholds are in EMBEDDED-LLM-SCOPE.md §7 — this script reports,
# it does not decide.
set -euo pipefail
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_dir"

OLLAMA_URL=${OLLAMA_URL:-http://localhost:11434}
MODELS=${MODELS:-"qwen3:4b-instruct gemma4:e2b gemma4:e4b qwen2.5-coder:7b"}
out=target/assistant-eval
mkdir -p "$out"

if ! curl -fsS "$OLLAMA_URL/api/tags" >/dev/null; then
  echo "No Ollama answers at $OLLAMA_URL. Start one (compose/ollama.yml) or set OLLAMA_URL." >&2
  exit 1
fi

for model in $MODELS; do
  echo "==> $model: pulling"
  # The pull streams JSON lines; a missing tag ends on an "error" line rather than a status code.
  if curl -fsS "$OLLAMA_URL/api/pull" -d "{\"model\":\"$model\"}" | grep -q '"error"'; then
    echo "    could not pull $model — skipped (check the tag on ollama.com/library)" >&2
    continue
  fi
  echo "==> $model: evaluating"
  export ASSISTANT_EVAL_MODEL="$model" ASSISTANT_EVAL_PROVIDER=OLLAMA \
         ASSISTANT_EVAL_BASE_URL="$OLLAMA_URL/v1"
  if [[ "${OFFLINE:-}" == 1 ]]; then
    ./verify-offline.sh --include-tag=assistant-eval "--include-classname=.*AssistantEvalTest" \
      || echo "    the run for $model reported a failure — see the output above" >&2
  else
    ./mvnw -q test -P assistant-eval -Dtest=AssistantEvalTest -Dsurefire.failIfNoSpecifiedTests=false \
      || echo "    the run for $model reported a failure — see the output above" >&2
  fi
  # What the loaded model holds, as Ollama reports it: the memory half of the decision.
  curl -fsS "$OLLAMA_URL/api/ps" > "$out/${model//[^A-Za-z0-9._-]/_}.ps.json" || true
done

{
  echo "# Assistant eval — summary"
  echo
  echo "| model | cases | accepted | correct | valid but wrong | unvalidated | no statement / error | p50 s | p95 s |"
  echo "|---|---|---|---|---|---|---|---|---|"
  for report in "$out"/*.md; do
    [[ "$(basename "$report")" == summary.md ]] && continue
    model=$(head -1 "$report" | sed 's/^# Assistant eval — //')
    grep '^| \*\*all\*\* |' "$report" | sed "s/^| \*\*all\*\* |/| $model |/"
  done
} > "$out/summary.md"
cat "$out/summary.md"
