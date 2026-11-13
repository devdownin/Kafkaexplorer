#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
#
# Both consumers of the model, measured on the same candidates in one run: the SQL & metric
# assistant (AssistantEvalTest, 50 cases run and executed) and Process Mining
# (LlmAnalysisEvalTest#reportsTheAnalysisForComparison, the seeded order pipeline, N runs), through
# a local llama.cpp server. One model per line of the summary, both consumers side by side — which
# is the question "can one model serve both?" asks.
#
#   bin/llm-eval.sh                                    # the candidates below, both consumers
#   MODELS="unsloth/gemma-4-E4B-it-GGUF:Q4_K_M unsloth/gemma-4-E2B-it-GGUF:Q4_K_M" bin/llm-eval.sh
#   ONLY=assistant bin/llm-eval.sh                     # or ONLY=process-mining
#   ASSISTANT_EVAL_CASES=fix,filter RUNS=5 bin/llm-eval.sh
#   OFFLINE=1 bin/llm-eval.sh                          # through ./verify-offline.sh (Confluent blocked)
#
# Needs `llama-server` on the PATH (llama.cpp): a model is a Hugging Face GGUF repository, written
# `owner/repo:QUANT`, and llama-server serves one model per process — so this script starts one
# server per candidate, on LLAMA_CPP_PORT, and stops it before the next. The weights are fetched
# into llama.cpp's own cache on first use. No broker is needed, both evals bring their own data.
# Run it on the machine that will serve: latency measured elsewhere says nothing about it. The
# decision thresholds are in EMBEDDED-LLM-SCOPE.md §7 — this reports.
#
# Output: target/assistant-eval/<model>.{json,md}, target/llm-eval/<model>.json and
# target/llm-eval/summary.md.
set -euo pipefail
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_dir"

LLAMA_CPP_PORT=${LLAMA_CPP_PORT:-8083}
LLAMA_CPP_URL="http://localhost:$LLAMA_CPP_PORT"
# The window of compose/llama-cpp.yml, on one slot: the measurement is of what ships.
LLAMA_CPP_CONTEXT=${LLAMA_CPP_CONTEXT:-16384}
MODELS=${MODELS:-"unsloth/gemma-4-E4B-it-GGUF:Q4_K_M unsloth/gemma-4-E2B-it-GGUF:Q4_K_M unsloth/Qwen3-4B-Instruct-2507-GGUF:Q4_K_M"}
ONLY=${ONLY:-both}
RUNS=${RUNS:-3}
case $ONLY in both|assistant|process-mining) ;; *) echo "ONLY must be both, assistant or process-mining" >&2; exit 2 ;; esac
mkdir -p target/assistant-eval target/llm-eval

if ! command -v llama-server >/dev/null; then
  echo "llama-server is not on the PATH. Install llama.cpp (https://github.com/ggml-org/llama.cpp#quick-start)." >&2
  exit 1
fi
if curl -fsS "$LLAMA_CPP_URL/health" >/dev/null 2>&1; then
  echo "Something already answers at $LLAMA_CPP_URL. Stop it or set LLAMA_CPP_PORT: this script starts its own server per model." >&2
  exit 1
fi

server_pid=""
stop_server() {
  if [[ -n "$server_pid" ]]; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
    server_pid=""
  fi
}
trap stop_server EXIT

# The two evals read their model from different variables, for history; one model sets both, and
# the timeout and window are the llama.cpp stack's own, so the measurement is of what ships.
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

for spec in $MODELS; do
  # unsloth/gemma-4-E4B-it-GGUF:Q4_K_M -> gemma-4-e4b-it, the alias the server answers under and
  # the name the reports are filed under.
  model=${spec##*/}; model=${model%%:*}; model=${model%-GGUF}; model=${model,,}
  log="target/llm-eval/$model.server.log"
  echo "==> $model: starting llama-server on $spec (log: $log)"
  llama-server -hf "$spec" --alias "$model" --port "$LLAMA_CPP_PORT" \
    -c "$LLAMA_CPP_CONTEXT" --parallel 1 > "$log" 2>&1 &
  server_pid=$!
  # Covers the download on a first run, so it waits on the process rather than on a clock.
  until curl -fsS "$LLAMA_CPP_URL/health" >/dev/null 2>&1; do
    if ! kill -0 "$server_pid" 2>/dev/null; then
      echo "    llama-server stopped for $spec — skipped (see $log; check the repository and quantisation)" >&2
      server_pid=""
      continue 2
    fi
    sleep 5
  done
  export ASSISTANT_EVAL_MODEL="$model" ASSISTANT_EVAL_PROVIDER=LLAMA_CPP ASSISTANT_EVAL_BASE_URL="$LLAMA_CPP_URL/v1"
  export CLAUDE_MODEL="$model" CLAUDE_PROVIDER=LLAMA_CPP CLAUDE_BASE_URL="$LLAMA_CPP_URL/v1"
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
  # What the server process holds once the model has answered: the memory half of the decision.
  ps -o rss= -p "$server_pid" | awk '{printf "{\"rssKb\": %d}\n", $1}' \
    > "target/llm-eval/$model.ps.json" || true
  stop_server
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
