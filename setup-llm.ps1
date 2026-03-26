# setup-llm.ps1 - Automate LLM setup for Kafka SQL Explorer (Windows)
# Recommended model: Qwen 2.5-Coder 7B

$ModelName = "qwen2.5-coder:7b"

Write-Host "--- LLM Setup for Kafka SQL Explorer (Windows) ---" -ForegroundColor Cyan

# 1. Check if Ollama is installed
if (!(Get-Command ollama -ErrorAction SilentlyContinue)) {
    Write-Host "Ollama not found. Attempting to install via winget..." -ForegroundColor Yellow
    if (Get-Command winget -ErrorAction SilentlyContinue) {
        winget install Ollama.Ollama
        Write-Host "Ollama installed. Please restart this PowerShell window and run the script again." -ForegroundColor Green
        exit
    } else {
        Write-Host "winget not found. Please download and install Ollama manually from https://ollama.com/" -ForegroundColor Red
        exit
    }
} else {
    Write-Host "Ollama is already installed." -ForegroundColor Green
}

# 2. Check if Ollama server is running
Write-Host "Checking Ollama server..."
try {
    $response = Invoke-WebRequest -Uri "http://localhost:11434/api/tags" -UseBasicParsing -ErrorAction Stop
} catch {
    Write-Host "Ollama server is not running. Starting Ollama..." -ForegroundColor Yellow
    Start-Process "ollama" -ArgumentList "serve" -NoNewWindow
    Start-Sleep -Seconds 5
}

# 3. Pull the recommended model
Write-Host "Pulling model: $ModelName (this may take a while)..." -ForegroundColor Cyan
ollama pull $ModelName

# 4. Set environment variables (User scope for persistence)
Write-Host "Setting environment variables..." -ForegroundColor Cyan
[System.Environment]::SetEnvironmentVariable("CLAUDE_PROVIDER", "OPENAI_COMPATIBLE", "User")
[System.Environment]::SetEnvironmentVariable("CLAUDE_BASE_URL", "http://localhost:11434/v1", "User")
[System.Environment]::SetEnvironmentVariable("CLAUDE_MODEL", $ModelName, "User")

# Also set for current session
$env:CLAUDE_PROVIDER = "OPENAI_COMPATIBLE"
$env:CLAUDE_BASE_URL = "http://localhost:11434/v1"
$env:CLAUDE_MODEL = $ModelName

Write-Host ""
Write-Host "--- Setup Complete ---" -ForegroundColor Green
Write-Host "Environment variables have been set persistently for your user account."
Write-Host "They are also available in this current PowerShell session."
Write-Host ""
Write-Host "You can now start the application with: ./mvnw spring-boot:run"
