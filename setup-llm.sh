#!/bin/bash

# setup-llm.sh - Automate LLM setup for Kafka SQL Explorer
# Recommended model: Qwen 2.5-Coder 7B

MODEL_NAME="qwen2.5-coder:7b"

echo "--- LLM Setup for Kafka SQL Explorer ---"

# 1. Check for curl
if ! command -v curl >/dev/null 2>&1; then
  echo "Error: curl is required but not installed. Please install it first."
  exit 1
fi

# 2. Install Ollama if not present
if ! command -v ollama >/dev/null 2>&1; then
  echo "Ollama not found. Installing Ollama..."
  curl -fsSL https://ollama.com/install.sh | sh
else
  echo "Ollama is already installed."
fi

# 3. Check if Ollama server is running
echo "Checking Ollama server..."
if ! curl -s http://localhost:11434/api/tags >/dev/null 2>&1; then
  echo "Ollama server is not running. Attempting to start it..."
  if command -v systemctl >/dev/null 2>&1; then
    sudo systemctl start ollama
  else
    ollama serve > /dev/null 2>&1 &
    sleep 5
  fi
fi

# 4. Pull the recommended model
echo "Pulling model: $MODEL_NAME (this may take a while)..."
ollama pull "$MODEL_NAME"

# 5. Export environment variables
# OLLAMA rather than OPENAI_COMPATIBLE: both speak the same OpenAI dialect, but claude.structured-output
# defaults to AUTO, which sends a JSON Schema only where support is known — and an unnamed gateway is
# not. Constrained decoding is what keeps a small local model from wrapping its JSON in prose.
export CLAUDE_PROVIDER=OLLAMA
export CLAUDE_BASE_URL=http://localhost:11434/v1
export CLAUDE_MODEL=$MODEL_NAME

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
