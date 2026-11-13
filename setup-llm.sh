#!/bin/bash

# setup-llm.sh - Automate LLM setup for Kafka SQL Explorer, on a local llama.cpp server
# Recommended model: Gemma 4 E4B — one model for Process Mining and the SQL assistant, the one
# measured best on both (EMBEDDED-LLM-SCOPE.md §7.1-7.2). The E2B repository on a small machine:
#   LLAMA_CPP_HF_REPO=unsloth/gemma-4-E2B-it-GGUF:Q4_K_M LLAMA_CPP_MODEL_NAME=gemma-4-e2b-it
#
# No container? `docker compose -f docker-compose.yml -f compose/llama-cpp.yml up -d` does the
# same with nothing to install.

HF_REPO="${LLAMA_CPP_HF_REPO:-unsloth/gemma-4-E4B-it-GGUF:Q4_K_M}"
MODEL_NAME="${LLAMA_CPP_MODEL_NAME:-gemma-4-e4b-it}"
# Not llama-server's default, 8080: that port is the Explorer's.
PORT="${LLAMA_CPP_PORT:-8083}"
# 16 384 tokens for one slot: llama-server divides -c across its --parallel slots.
CONTEXT="${LLAMA_CPP_CONTEXT:-16384}"

echo "--- LLM Setup for Kafka SQL Explorer ---"

# 1. Check for curl
if ! command -v curl >/dev/null 2>&1; then
  echo "Error: curl is required but not installed. Please install it first."
  exit 1
fi

# 2. Install llama.cpp if not present
if ! command -v llama-server >/dev/null 2>&1; then
  if command -v brew >/dev/null 2>&1; then
    echo "llama-server not found. Installing llama.cpp with Homebrew..."
    brew install llama.cpp
  else
    echo "Error: llama-server is not on the PATH and Homebrew is not available to install it."
    echo "Install llama.cpp (https://github.com/ggml-org/llama.cpp#quick-start) and run this again,"
    echo "or use the container stack: docker compose -f docker-compose.yml -f compose/llama-cpp.yml up -d"
    exit 1
  fi
else
  echo "llama.cpp is already installed."
fi

# 3. Start the server unless one already answers. The first start downloads the weights
#    (several GB) into llama.cpp's cache, so the wait below is long on purpose.
if ! curl -sf "http://localhost:${PORT}/health" >/dev/null 2>&1; then
  echo "Starting llama-server on port ${PORT} with ${HF_REPO} (log: llama-server.log)..."
  nohup llama-server -hf "$HF_REPO" --alias "$MODEL_NAME" --port "$PORT" \
    -c "$CONTEXT" --parallel 1 > llama-server.log 2>&1 &
  echo "Waiting for the model to be downloaded and loaded (this may take a while)..."
  until curl -sf "http://localhost:${PORT}/health" >/dev/null 2>&1; do
    if ! pgrep -x llama-server >/dev/null 2>&1; then
      echo "Error: llama-server stopped. See llama-server.log."
      exit 1
    fi
    sleep 5
  done
else
  echo "A llama-server already answers on port ${PORT}."
fi

# 4. Export environment variables
# LLAMA_CPP rather than OPENAI_COMPATIBLE: both speak the same OpenAI dialect, but claude.structured-output
# defaults to AUTO, which sends a JSON Schema only where support is known — and an unnamed gateway is
# not. Constrained decoding is what keeps a small local model from wrapping its JSON in prose.
export CLAUDE_PROVIDER=LLAMA_CPP
export CLAUDE_BASE_URL=http://localhost:${PORT}/v1
export CLAUDE_MODEL=$MODEL_NAME
# The window the server was started with, so the prompt budgets are capped to fit it.
export CLAUDE_CONTEXT_WINDOW_TOKENS=$CONTEXT

echo ""
echo "--- Setup Complete ---"
echo "Environment variables have been set for this script session."

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  echo ""
  echo "IMPORTANT: To use these variables in your current terminal, please run:"
  echo "source ./setup-llm.sh"
else
  echo "Variables have been automatically exported to your current shell session."
fi

echo ""
echo "You can now start the application with: ./mvnw spring-boot:run"
