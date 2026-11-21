# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
#
# demo.ps1 - the Windows 11 twin of demo.sh: the whole suite in one command. Kafka (KRaft) + demo
# topics, Schema Registry + Avro topics, Kafka Explorer (MCP on, read-only), TimesFM forecasting
# + PostgreSQL history, SpectraLLM (local llama.cpp model, MCP on, indexing the demo topics) and
# the Kex-anHarness agent - the Explorer and the agent on OpenRouter. compose/demo.yml says how
# the files are layered; this script layers them exactly as demo.sh does.
#
#   $env:OPENROUTER_API_KEY = 'sk-or-...'; .\demo.cmd          # build, start, wait, print the endpoints
#   .\demo.cmd -NoSpectra                                      # the same without SpectraLLM
#   .\demo.cmd -Forecast                                       # the forecast demo alone, nothing to configure
#   .\demo.cmd [-NoSpectra|-Forecast] down | reset | ps | logs [service] | probe
#
# -Forecast (DEMO_MODE=forecast) is the stack the forecast demo needs and nothing else: Kafka and the
# demo topics, Schema Registry, the Explorer with MCP, PostgreSQL, TimesFM, the live traffic and its
# seeded history (compose/demo-forecast.yml). No LLM key, no SpectraLLM, no agent: every variable has
# a default, so `.\demo.cmd -Forecast` is the whole command. Same as `./demo.sh --forecast`.
#
# demo.cmd only runs this file with -ExecutionPolicy Bypass, which Windows 11's default policy
# would otherwise refuse. Runs on Windows PowerShell 5.1 and PowerShell 7; Docker Desktop with the
# WSL 2 backend is the only prerequisite - no Git Bash, no openssl, no curl.
#
# Precedence: environment variable > .env at the root > .forecast-stack/.env > default below.
#
# Kept ASCII on purpose: Windows PowerShell 5.1 reads a BOM-less script as the ANSI code page, so
# one em dash would turn into mojibake in every message that carries it.
[CmdletBinding()]
param(
    [switch] $NoSpectra,
    [switch] $Forecast,
    [Parameter(Position = 0)]
    [ValidateSet('up', 'down', 'reset', 'ps', 'logs', 'probe')]
    [string] $Command = 'up',
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)]
    [string[]] $Services = @()
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

# UTF-8 without a BOM, LF line endings: these files are also read by demo.sh and by Compose, and a
# BOM glued to the first key makes that key unreadable to both.
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Test-EnvSet([string] $Name) {
    -not [string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($Name, 'Process'))
}

# A variable already set (by the caller, or by an earlier file) is kept: first value wins.
function Import-EnvFile([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    foreach ($line in [IO.File]::ReadAllLines((Resolve-Path -LiteralPath $Path).Path)) {
        if ($line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { continue }
        $name = $Matches[1]
        $value = $Matches[2].Trim() -replace '^["'']', '' -replace '["'']$', ''
        if (-not (Test-EnvSet $name)) { [Environment]::SetEnvironmentVariable($name, $value, 'Process') }
    }
}

function Set-Default([string] $Name, [string] $Value) {
    if (-not (Test-EnvSet $Name)) { [Environment]::SetEnvironmentVariable($Name, $Value, 'Process') }
}

function New-Secret {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    -join ($bytes | ForEach-Object { $_.ToString('x2') })
}

# What `bin/forecast-stack.sh --prepare-only` does, without bash or openssl: credentials generated
# once and never overwritten, so the two scripts share .forecast-stack/ and either can start the
# stack the other prepared.
function Initialize-ForecastStack {
    $config = Join-Path '.forecast-stack' 'config'
    New-Item -ItemType Directory -Force -Path $config | Out-Null
    $envFile = Join-Path '.forecast-stack' '.env'
    if (-not (Test-Path -LiteralPath $envFile)) {
        $content = "FORECAST_POSTGRES_PASSWORD=$(New-Secret)`nTIMESFM_TOKEN=$(New-Secret)`n" +
            "EXPLORER_MCP_AUTH_TOKEN=$(New-Secret)`nFORECAST_ENVIRONMENTS=local`n"
        [IO.File]::WriteAllText((Join-Path $PWD $envFile), $content, $Utf8NoBom)
    }
    $forecasts = Join-Path $config 'forecasts.yml'
    if (-not (Test-Path -LiteralPath $forecasts)) {
        [IO.File]::WriteAllText((Join-Path $PWD $forecasts),
            "# Save the reviewed configuration assistant export here.`n{}`n", $Utf8NoBom)
    }
}

function Invoke-Compose {
    & docker compose @script:ComposeFiles @args
    if ($LASTEXITCODE -ne 0) { throw "docker compose $($args -join ' ') failed (exit $LASTEXITCODE)." }
}

function Wait-Http([string] $Name, [string] $Url, [int] $Seconds, [hashtable] $Headers = @{}) {
    Write-Host -NoNewline "Waiting for $Name "
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ($true) {
        try {
            Invoke-WebRequest -Uri $Url -Headers $Headers -UseBasicParsing -TimeoutSec 10 | Out-Null
            Write-Host ' ok'
            return $true
        } catch {
            if ((Get-Date) -ge $deadline) {
                Write-Host " - not ready after $Seconds s. See: .\demo.cmd logs"
                return $false
            }
            Write-Host -NoNewline '.'
            Start-Sleep -Seconds 5
        }
    }
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker Desktop is required (https://www.docker.com/products/docker-desktop/).'
}
& docker compose version *> $null
if ($LASTEXITCODE -ne 0) { throw 'Docker Compose v2 is required: update Docker Desktop.' }

# Before the env files, so a switch outranks a DEMO_SPECTRA / DEMO_MODE they set.
if ($NoSpectra) { $env:DEMO_SPECTRA = 'false' }
if ($Forecast) { $env:DEMO_MODE = 'forecast' }

Initialize-ForecastStack
Import-EnvFile '.env'
Import-EnvFile (Join-Path '.forecast-stack' '.env')

# After the env files, so a DEMO_MODE they set is honoured; full is the whole suite.
Set-Default DEMO_MODE 'full'
if ($env:DEMO_MODE -notin @('full', 'forecast')) {
    throw "DEMO_MODE must be full or forecast, not '$($env:DEMO_MODE)'."
}
$forecastOnly = $env:DEMO_MODE -eq 'forecast'
if ($forecastOnly) { $env:DEMO_SPECTRA = 'false' }

# -- Defaults: the same values as demo.sh ---------------------------------------------------
Set-Default BIND_ADDR '127.0.0.1'
Set-Default EXPLORER_PORT '8080'
Set-Default KAFKA_PORT '9092'
Set-Default KEX_AGENT_PORT '8081'
# 8081 belongs to the agent in the shared port plan; Schema Registry moves aside.
Set-Default SCHEMA_REGISTRY_PORT '8085'

# LLM - OpenRouter on both sides. An empty key stays unset: Windows has no empty variable.
Set-Default OPENROUTER_MODEL 'openai/gpt-4o-mini'
Set-Default KEX_AGENT_LLM_PROVIDER 'openai'
# The SQL & metric assistant, on the same OpenRouter model (false turns it off). It needs that model,
# which the forecast demo does not have: off there.
Set-Default EXPLORER_ASSISTANT_ENABLED $(if ($forecastOnly) { 'false' } else { 'true' })

# Agent and MCP. The MCP token defaults to the generated one from .forecast-stack/.env.
Set-Default KEX_AGENT_IMAGE_NAMESPACE 'compagnonsdudev'
Set-Default KEX_AGENT_IMAGE_TAG '0.10.0'
Set-Default KEX_AGENT_API_KEY 'dev-only-agent-key'
Set-Default EXPLORER_MCP_AUTH_TOKEN 'dev-only-mcp-token'
Set-Default EXPLORER_MCP_READONLY 'true'

# SpectraLLM. Its MCP server refuses to run without SPECTRA_API_KEY, and with one set its own UI
# answers 401 (it sends no key); the agent and the API are unaffected.
Set-Default SPECTRA_API_KEY 'dev-only-spectra-key'
Set-Default SPECTRA_IMAGE_NAMESPACE 'compagnonsdudev'
Set-Default SPECTRA_IMAGE_TAG '0.12.3'
Set-Default SPECTRA_UI_PORT '8088'
Set-Default SPECTRA_API_PORT '8089'
# The three must agree (.env.example); a laptop wants the 3B named there.
Set-Default LLM_CHAT_MODEL_NAME 'qwen2.5-7b-instruct'
Set-Default LLM_CHAT_MODEL_FILE 'Qwen2.5-7B-Instruct-Q4_K_M.gguf'
Set-Default SPECTRA_AUTO_INSTALL_MODELS 'true'

# Forecasting.
Set-Default FORECAST_ENVIRONMENTS 'local'
Set-Default FORECAST_CLUSTER_ID 'local'
Set-Default FORECAST_COLLECTOR_ID 'kafkaexplorer'
Set-Default FORECAST_RUNTIME_APPROVAL 'true'
Set-Default TIMESFM_THREADS '4'
Set-Default TIMESFM_CPUS '4.0'
Set-Default TIMESFM_MEM_LIMIT '8g'
Set-Default TIMESFM_TIMEOUT '35s'

# Explorer image: built from this checkout by default; DEMO_PUBLISHED_IMAGE=true pulls the release.
Set-Default DEMO_PUBLISHED_IMAGE 'false'
Set-Default EXPLORER_IMAGE_NAMESPACE 'compagnonsdudev'
Set-Default EXPLORER_IMAGE_TAG '2.3.3'

# SpectraLLM on by default; false drops its seven services and their ~4.8 GB first download.
Set-Default DEMO_SPECTRA 'true'
if ($env:DEMO_SPECTRA -notin @('true', 'false')) {
    throw "DEMO_SPECTRA must be true or false, not '$($env:DEMO_SPECTRA)'."
}
$spectraOn = $env:DEMO_SPECTRA -eq 'true'

Set-Default COMPOSE_PROJECT_NAME 'kafka-explorer-demo'

$script:ComposeFiles = @('-f', 'docker-compose.yml', '-f', 'compose/schema-registry.yml',
    '-f', 'compose/mcp.yml', '-f', 'compose/forecasts.yml', '-f', 'compose/demo-forecast.yml')
if ($env:DEMO_PUBLISHED_IMAGE -eq 'true') { $script:ComposeFiles += @('-f', 'compose/image.yml') }
# The forecast demo stops here: the agent (compose/demo*.yml, ecosystem) and the hosted model
# (demo-llm.yml) are what want a key, and nothing it shows uses them.
if (-not $forecastOnly) {
    if ($spectraOn) {
        $script:ComposeFiles += @('-f', 'compose/demo.yml', '-f', 'compose/spectra-hub.mcp.yml',
            '-f', 'compose/spectra-hub.ingest.yml', '-f', 'compose/ecosystem.yml')
    } else {
        $script:ComposeFiles += @('-f', 'compose/demo-no-spectra.yml')
    }
    $script:ComposeFiles += @('-f', 'compose/demo-llm.yml')
}

function Start-Demo {
    if (-not $forecastOnly -and -not (Test-EnvSet 'OPENROUTER_API_KEY')) {
        Write-Warning ('OPENROUTER_API_KEY is empty - Process Mining answers without its LLM half and the ' +
            "agent starts DEGRADED. Set it (`$env:OPENROUTER_API_KEY = 'sk-or-...', or in .env) and rerun.")
    }
    Write-Host "First run: Maven build of the Explorer and the pinned TimesFM checkpoint ($($env:TIMESFM_CPUS) CPUs / $($env:TIMESFM_MEM_LIMIT))."
    if ($spectraOn) { Write-Host 'SpectraLLM adds ~4.8 GB of weights; its 7B chat model wants ~16 GB of RAM.' }
    # Docker Desktop gives the WSL 2 VM half the host's memory by default; TimesFM alone is capped
    # at 8 GB, so on a 16 GB machine raise `memory=` in %UserProfile%\.wslconfig or pass -NoSpectra.
    Write-Host 'Docker Desktop memory: see the [wsl2] memory= setting in %UserProfile%\.wslconfig. Later runs start in seconds.'
    if ($env:DEMO_PUBLISHED_IMAGE -eq 'true') { Invoke-Compose pull --quiet --ignore-buildable }
    # --remove-orphans: switching to -NoSpectra stops the SpectraLLM containers a previous run left.
    Invoke-Compose up -d --build --remove-orphans

    Wait-Http 'Kafka Explorer' "http://127.0.0.1:$($env:EXPLORER_PORT)/actuator/health" 900 | Out-Null
    if ($forecastOnly) {
        Write-Host @"

Kafka SQL Explorer .... http://localhost:$($env:EXPLORER_PORT)   -> Metrics
  MCP ................. http://localhost:$($env:EXPLORER_PORT)/mcp   (Bearer in .forecast-stack\.env or EXPLORER_MCP_AUTH_TOKEN)
Schema Registry ....... http://localhost:$($env:SCHEMA_REGISTRY_PORT)
Kafka ................. localhost:$($env:KAFKA_PORT)
LLM, agent, SpectraLLM  not started (-Forecast): nothing to configure

The forecast demo: Metrics has five live metrics (demo.live.*: orders, requests, imports, events, tickets; each minus its second topic).
  Their history is seeded in the first minutes (.\demo.cmd -Forecast logs demo-forecast-seed), then:
  - the unshipped-orders one is already forecast, with its "Warning expected in ~N min" badge;
  - press Forecast on another (or "Forecast all eligible"): the backlog's hourly cycle is found, the imports climb, the events
    stay flat with a wide band, the tickets change level and no forecast sees it coming - the Previous forecast vs actual view shows it.
  - Stream Flow: trace the key orders-1 (or any orders-N): it is found in demo.live.orders and in demo.live.shipped.
  .\demo.cmd -Forecast down | reset | ps | logs [service] | probe     (docs/metrics-demo-scenarios.md)
"@
        return
    }
    Wait-Http 'Kex-anHarness' "http://127.0.0.1:$($env:KEX_AGENT_PORT)/api/agent/mcp/servers" 300 `
        @{ Authorization = "Bearer $($env:KEX_AGENT_API_KEY)" } | Out-Null
    # spectra-api waits for the embedding model and the demo seed; its chat answers once llm-chat
    # has its weights, which `.\demo.cmd logs spectra-models llm-chat` follows.
    if ($spectraOn) {
        Wait-Http 'SpectraLLM API' "http://127.0.0.1:$($env:SPECTRA_API_PORT)/actuator/health/readiness" 600 | Out-Null
    }

    $spectra = if ($spectraOn) {
        "SpectraLLM UI ......... http://localhost:$($env:SPECTRA_UI_PORT)     (401 while SPECTRA_API_KEY is set)`n" +
        "SpectraLLM API ........ http://localhost:$($env:SPECTRA_API_PORT)/swagger-ui   (/mcp, X-API-Key $($env:SPECTRA_API_KEY)) - $($env:LLM_CHAT_MODEL_NAME) local"
    } else {
        'SpectraLLM ............ off (-NoSpectra); the agent reports its MCP connection unreachable'
    }
    Write-Host @"

Kafka SQL Explorer .... http://localhost:$($env:EXPLORER_PORT)
  MCP ................. http://localhost:$($env:EXPLORER_PORT)/mcp   (Bearer in .forecast-stack\.env or EXPLORER_MCP_AUTH_TOKEN)
Kex-anHarness ......... http://localhost:$($env:KEX_AGENT_PORT)     (Bearer $($env:KEX_AGENT_API_KEY))
$spectra
Schema Registry ....... http://localhost:$($env:SCHEMA_REGISTRY_PORT)
Kafka ................. localhost:$($env:KAFKA_PORT)
LLM ................... OpenRouter $($env:OPENROUTER_MODEL) (Explorer, agent)
SQL assistant ......... $($env:EXPLORER_ASSISTANT_ENABLED) (editor ask bar, Suggest a fix, Metrics: Describe the metric; table and column names go to OpenRouter)

Demo topics (JSON + Avro) are seeded in the background: .\demo.cmd logs demo-setup
Forecast: Metrics -> the five demo.live.* metrics have 9 h of seeded history (live traffic keeps them moving):
  the unshipped-orders one is already forecast; press Forecast on the others, or Forecast all eligible. .\demo.cmd logs demo-forecast-seed
"@
}

if ($Services.Count -gt 0 -and $Command -ne 'logs') {
    throw "Only 'logs' takes service names. Usage: .\demo.cmd [-NoSpectra|-Forecast] [up|down|reset|ps|logs [service]|probe]"
}

switch ($Command) {
    'up'    { Start-Demo }
    'down'  { Invoke-Compose down --remove-orphans }
    # Volumes included: topics, forecast history, agent state, downloaded models. Credentials stay.
    'reset' { Invoke-Compose down -v --remove-orphans }
    'ps'    { Invoke-Compose ps }
    'logs'  { Invoke-Compose logs -f --tail 200 @Services }
    'probe' {
        Invoke-Compose --profile probe run --rm mcp-probe
        if ($spectraOn) { Invoke-Compose --profile probe run --rm spectra-mcp-probe }
    }
}
