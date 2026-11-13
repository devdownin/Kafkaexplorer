# setup-llm.ps1 - Automate LLM setup for Kafka SQL Explorer (Windows), on a local llama.cpp server
# Recommended model: Gemma 4 E4B - one model for Process Mining and the SQL assistant, the one
# measured best on both (EMBEDDED-LLM-SCOPE.md 7.1-7.2). The E2B repository on a small machine:
#   $env:LLAMA_CPP_HF_REPO = 'unsloth/gemma-4-E2B-it-GGUF:Q4_K_M'; $env:LLAMA_CPP_MODEL_NAME = 'gemma-4-e2b-it'

$HfRepo    = if ($env:LLAMA_CPP_HF_REPO)    { $env:LLAMA_CPP_HF_REPO }    else { "unsloth/gemma-4-E4B-it-GGUF:Q4_K_M" }
$ModelName = if ($env:LLAMA_CPP_MODEL_NAME) { $env:LLAMA_CPP_MODEL_NAME } else { "gemma-4-e4b-it" }
# Not llama-server's default, 8080: that port is the Explorer's.
$Port      = if ($env:LLAMA_CPP_PORT)       { $env:LLAMA_CPP_PORT }       else { "8083" }
# 16384 tokens for one slot: llama-server divides -c across its --parallel slots.
$Context   = if ($env:LLAMA_CPP_CONTEXT)    { $env:LLAMA_CPP_CONTEXT }    else { "16384" }
$BaseUrl   = "http://localhost:$Port"

Write-Host "--- LLM Setup for Kafka SQL Explorer (Windows) ---" -ForegroundColor Cyan

# 1. Check if llama.cpp is installed
if (!(Get-Command llama-server -ErrorAction SilentlyContinue)) {
    Write-Host "llama-server not found. Attempting to install llama.cpp via winget..." -ForegroundColor Yellow
    if (Get-Command winget -ErrorAction SilentlyContinue) {
        winget install llama.cpp
        Write-Host "llama.cpp installed. Please restart this PowerShell window and run the script again." -ForegroundColor Green
        exit
    } else {
        Write-Host "winget not found. Please install llama.cpp manually from https://github.com/ggml-org/llama.cpp/releases" -ForegroundColor Red
        exit
    }
} else {
    Write-Host "llama.cpp is already installed." -ForegroundColor Green
}

# 2. Start the server unless one already answers. The first start downloads the weights
#    (several GB) into llama.cpp's cache, so the wait below is long on purpose.
function Test-LlamaServer {
    try {
        Invoke-WebRequest -Uri "$BaseUrl/health" -UseBasicParsing -ErrorAction Stop | Out-Null
        return $true
    } catch {
        return $false
    }
}

if (Test-LlamaServer) {
    Write-Host "A llama-server already answers on port $Port." -ForegroundColor Green
} else {
    Write-Host "Starting llama-server on port $Port with $HfRepo ..." -ForegroundColor Yellow
    $server = Start-Process "llama-server" -PassThru -WindowStyle Minimized -ArgumentList @(
        "-hf", $HfRepo, "--alias", $ModelName, "--port", $Port, "-c", $Context, "--parallel", "1")
    Write-Host "Waiting for the model to be downloaded and loaded (this may take a while)..." -ForegroundColor Cyan
    while (!(Test-LlamaServer)) {
        if ($server.HasExited) {
            Write-Host "llama-server stopped. Run it by hand to read its error: llama-server -hf $HfRepo --port $Port" -ForegroundColor Red
            exit 1
        }
        Start-Sleep -Seconds 5
    }
}

# 3. Set environment variables (User scope for persistence)
Write-Host "Setting environment variables..." -ForegroundColor Cyan
# LLAMA_CPP rather than OPENAI_COMPATIBLE: same dialect, but claude.structured-output=AUTO only sends a
# JSON Schema where support is known, and constrained decoding is what a small local model needs most.
[System.Environment]::SetEnvironmentVariable("CLAUDE_PROVIDER", "LLAMA_CPP", "User")
[System.Environment]::SetEnvironmentVariable("CLAUDE_BASE_URL", "$BaseUrl/v1", "User")
[System.Environment]::SetEnvironmentVariable("CLAUDE_MODEL", $ModelName, "User")
# The window the server was started with, so the prompt budgets are capped to fit it.
[System.Environment]::SetEnvironmentVariable("CLAUDE_CONTEXT_WINDOW_TOKENS", $Context, "User")

# Also set for current session
$env:CLAUDE_PROVIDER = "LLAMA_CPP"
$env:CLAUDE_BASE_URL = "$BaseUrl/v1"
$env:CLAUDE_MODEL = $ModelName
$env:CLAUDE_CONTEXT_WINDOW_TOKENS = $Context

Write-Host ""
Write-Host "--- Setup Complete ---" -ForegroundColor Green
Write-Host "Environment variables have been set persistently for your user account."
Write-Host "They are also available in this current PowerShell session."
Write-Host ""
Write-Host "You can now start the application with: ./mvnw spring-boot:run"
