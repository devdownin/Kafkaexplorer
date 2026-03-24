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

echo ""
echo "--- Setup Complete ---"
echo "To use this model with Kafka SQL Explorer, update your application.yml or set environment variables:"
echo ""
echo "YAML Configuration (src/main/resources/application.yml):"
echo "claude:"
echo "  provider: OPENAI_COMPATIBLE"
echo "  base-url: http://localhost:11434/v1"
echo "  model: $MODEL_NAME"
echo ""
echo "Environment Variables:"
echo "export CLAUDE_PROVIDER=OPENAI_COMPATIBLE"
echo "export CLAUDE_BASE_URL=http://localhost:11434/v1"
echo "export CLAUDE_MODEL=$MODEL_NAME"
echo ""
echo "You can now restart the application to use the local LLM."
