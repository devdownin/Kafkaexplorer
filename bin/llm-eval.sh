#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
#
# Both consumers of the model, measured on the same candidates in one run: the SQL & metric
# assistant (AssistantEvalTest, 50 cases run and executed) and Process Mining
# (LlmAnalysisEvalTest#reportsTheAnalysisForComparison, the seeded order pipeline, N runs), through
# a local Ollama. One model per line of the summary, both consumers side by side — which is the
# question "can one model serve both?" asks.
#
#   bin/llm-eval.sh                                    # the candidates below, both consumers
#   MODELS="gemma4:e4b gemma4:e2b" bin/llm-eval.sh
#   ONLY=assistant bin/llm-eval.sh                     # or ONLY=process-mining
#   ASSISTANT_EVAL_CASES=fix,filter RUNS=5 bin/llm-eval.sh
#   OFFLINE=1 bin/llm-eval.sh                          # through ./verify-offline.sh (Confluent blocked)
#
# Needs an Ollama reachable at OLLAMA_URL — `docker compose -f docker-compose.yml -f
# compose/ollama.yml up -d ollama` is enough; no broker is needed, both evals bring their own data.
# Each model is pulled first. Run it on the machine that will serve: latency measured elsewhere
# says nothing about it. The decision thresholds are in EMBEDDED-LLM-SCOPE.md §7 — this reports.
#
# Output: target/assistant-eval/<model>.{json,md}, target/llm-eval/<model>.json and
# target/llm-eval/summary.md.
set -euo pipefail
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_dir"

OLLAMA_URL=${OLLAMA_URL:-http://localhost:11434}
MODELS=${MODELS:-"gemma4:e4b gemma4:e2b qwen2.5-coder:7b"}
ONLY=${ONLY:-both}
RUNS=${RUNS:-3}
case $ONLY in both|assistant|process-mining) ;; *) echo "ONLY must be both, assistant or process-mining" >&2; exit 2 ;; esac
mkdir -p target/assistant-eval target/llm-eval

if ! curl -fsS "$OLLAMA_URL/api/tags" >/dev/null; then
  echo "No Ollama answers at $OLLAMA_URL. Start one (compose/ollama.yml) or set OLLAMA_URL." >&2
  exit 1
fi

# The two evals read their model from different variables, for history; one model sets both, and
# the timeout and window are the Ollama stack's own, so the measurement is of what ships.
run_eval() {
  local profile=$1 tag=$2 test=$3 method_filter=$4
  if [[ "${OFFLINE:-}" == 1 ]]; then
    ./verify-offline.sh "--include-tag=$tag" "--include-classname=.*${test}" $method_filter \
      || echo "    $tag reported a failure for $ASSISTANT_EVAL_MODEL — see the output above" >&2
  else
    ./mvnw -q test -P "$profile" "-Dtest=$test${method_filter:+#reportsTheAnalysisForComparison}" \
      -Dsurefire.failIfNoSpecifiedTests=false "-Dllm.eval.runs=$RUNS" \
      || echo "    $tag reported a failure for $ASSISTANT_EVAL_MODEL — see the output above" >&2
  fi
}

for model in $MODELS; do
  echo "==> $model: pulling"
  # The pull streams JSON lines; a missing tag ends on an "error" line rather than a status code.
  if curl -fsS "$OLLAMA_URL/api/pull" -d "{\"model\":\"$model\"}" | grep -q '"error"'; then
    echo "    could not pull $model — skipped (check the tag on ollama.com/library)" >&2
    continue
  fi
  export ASSISTANT_EVAL_MODEL="$model" ASSISTANT_EVAL_PROVIDER=OLLAMA ASSISTANT_EVAL_BASE_URL="$OLLAMA_URL/v1"
  export CLAUDE_MODEL="$model" CLAUDE_PROVIDER=OLLAMA CLAUDE_BASE_URL="$OLLAMA_URL/v1"
  export CLAUDE_REQUEST_TIMEOUT_SECONDS=${CLAUDE_REQUEST_TIMEOUT_SECONDS:-300}
  export ASSISTANT_EVAL_TIMEOUT_SECONDS=${ASSISTANT_EVAL_TIMEOUT_SECONDS:-$CLAUDE_REQUEST_TIMEOUT_SECONDS}
  if [[ $ONLY != process-mining ]]; then
    echo "==> $model: the assistant's 50 cases"
    run_eval assistant-eval assistant-eval AssistantEvalTest ""
  fi
  if [[ $ONLY != assistant ]]; then
    echo "==> $model: Process Mining, $RUNS runs"
    run_eval llm-eval llm-eval LlmAnalysisEvalTest "--include-methodname=.*reportsTheAnalysisForComparison"
  fi
  # What the loaded model holds, as Ollama reports it: the memory half of the decision.
  curl -fsS "$OLLAMA_URL/api/ps" > "target/llm-eval/${model//[^A-Za-z0-9._-]/_}.ps.json" || true
done

python3 -I - "$repo_dir/target" <<'PY'
import json, pathlib, sys
target = pathlib.Path(sys.argv[1])
rows = {}
for f in sorted((target / "assistant-eval").glob("*.json")):
    d = json.loads(f.read_text())
    o = d["overall"]; n = o["cases"]
    rows.setdefault(d["model"], {})["assistant"] = (
        f"{o['correct']}/{n} correct, {o['validButWrong']} wrong, p95 {o['p95Seconds']} s")
for f in sorted((target / "llm-eval").glob("*.json")):
    if f.name.endswith(".ps.json"):
        continue
    d = json.loads(f.read_text()); runs = d["runs"]; n = len(runs)
    held = sum(r["formatHeld"] for r in runs)
    named = sum(r["flowchartNamesPipeline"] for r in runs)
    facts = sum(sum(r["plantedFactsFound"].values()) for r in runs)
    durations = sorted(r["durationMs"] for r in runs if r["durationMs"])
    median = f"{durations[len(durations) // 2] / 1000:.0f} s" if durations else "n/a"
    rows.setdefault(d["model"], {})["pm"] = (
        f"format {held}/{n}, pipeline {named}/{n}, planted facts {facts}/{4 * n}, median {median}")
out = ["# LLM eval — both consumers, one model per line", "",
       "| model | SQL & metric assistant | Process Mining |", "|---|---|---|"]
for model, r in rows.items():
    out.append(f"| {model} | {r.get('assistant', '—')} | {r.get('pm', '—')} |")
(target / "llm-eval" / "summary.md").write_text("\n".join(out) + "\n")
print("\n".join(out))
PY
