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
#   .\demo.cmd -Forecast                                       # forecast demo with local builds
#   .\demo-forecast.cmd                                        # forecast demo from images, without builds
#   .\demo-forecast.cmd -NoAgent                               # the same without the agent
#   .\demo.cmd [-NoSpectra|-Forecast] down | reset | ps | logs [service] | probe
#
# -Forecast (DEMO_MODE=forecast) is the stack the forecast demo needs and nothing else: Kafka and the
# demo topics, Schema Registry, the Explorer with MCP, PostgreSQL, TimesFM, the live traffic and its
# seeded history (compose/demo-forecast.yml), and the Kex-anHarness agent. No LLM key needed (the agent
# starts degraded without one), no SpectraLLM: every variable has a default, so `.\demo.cmd -Forecast`
# is the whole command. Same as `./demo.sh --forecast`.
#
# The agent is on by default in the forecast demo (except -Offline); -NoAgent (DEMO_AGENT=false) drops
# it, -Agent (DEMO_AGENT=true) forces it. It runs on OpenRouter, with the Explorer's LLM half (compose/demo-no-spectra.yml + compose/demo-llm.yml, the shape
# -NoSpectra already runs). With -Prebuilt the agent follows `latest` like the Explorer and the
# forecast image, so nothing is compiled: `.\demo.cmd -Forecast -Prebuilt -Agent`, or
# `.\demo-forecast.cmd`. The full suite already has the agent: -Agent / -NoAgent are for -Forecast only.
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
    [switch] $Prebuilt,
    [switch] $Offline,
    [switch] $Agent,
    [switch] $NoAgent,
    [Parameter(Position = 0)]
    [ValidateSet('up', 'down', 'reset', 'ps', 'logs', 'probe', 'doctor', 'verify', 'scenario')]
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
            "EXPLORER_MCP_AUTH_TOKEN=$(New-Secret)`nKEX_AGENT_API_KEY=$(New-Secret)`nFORECAST_ENVIRONMENTS=local`n"
        [IO.File]::WriteAllText((Join-Path $PWD $envFile), $content, $Utf8NoBom)
    }
    # A state file written before the agent joined the demos has no bearer for it: add one, once.
    if (-not (Select-String -LiteralPath $envFile -Pattern '^KEX_AGENT_API_KEY=' -Quiet)) {
        $existing = [IO.File]::ReadAllText((Join-Path $PWD $envFile))
        if ($existing.Length -gt 0 -and -not $existing.EndsWith("`n")) { $existing += "`n" }
        [IO.File]::WriteAllText((Join-Path $PWD $envFile), "${existing}KEX_AGENT_API_KEY=$(New-Secret)`n", $Utf8NoBom)
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
if ($Prebuilt) { $env:DEMO_PREBUILT = 'true'; $env:DEMO_PUBLISHED_IMAGE = 'true' }
if ($Offline) { $env:DEMO_MODE = 'forecast'; $env:DEMO_PREBUILT = 'true'; $env:DEMO_PUBLISHED_IMAGE = 'true'; $env:DEMO_OFFLINE = 'true' }
if ($Agent) { $env:DEMO_AGENT = 'true' }
if ($NoAgent) { $env:DEMO_AGENT = 'false' }

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
Set-Default DEMO_PREBUILT 'false'
Set-Default DEMO_OFFLINE 'false'
if ($env:DEMO_OFFLINE -notin @('true', 'false')) { throw 'DEMO_OFFLINE must be true or false.' }
if ($env:DEMO_OFFLINE -eq 'true' -and $env:DEMO_PREBUILT -ne 'true') { throw 'Offline mode requires prebuilt forecast demo.' }
if ($env:DEMO_PREBUILT -notin @('true', 'false')) { throw 'DEMO_PREBUILT must be true or false.' }
$prebuilt = $env:DEMO_PREBUILT -eq 'true'
$offline = $env:DEMO_OFFLINE -eq 'true'
if ($prebuilt) { $env:DEMO_PUBLISHED_IMAGE = 'true' }
# The forecast demo loads the Kex-anHarness agent by default (no key needed: it starts degraded
# without one); -NoAgent / DEMO_AGENT=false drops it. -Offline keeps it off: its image is not baked.
Set-Default DEMO_AGENT $(if ($forecastOnly -and -not $offline) { 'true' } else { 'false' })
if ($env:DEMO_AGENT -notin @('true', 'false')) { throw 'DEMO_AGENT must be true or false.' }
$agentOn = $env:DEMO_AGENT -eq 'true'
if ($agentOn -and -not $forecastOnly) { throw '-Agent is for the forecast demo (-Forecast); the full suite already runs the agent.' }

# -- What the demo sets: the same lines as demo.sh ------------------------------------------
# Two kinds of line live here, and only two: an override of the compose files' own default
# (OpenRouter rather than Anthropic, Schema Registry beside the agent, the shadow off), and a value
# this script reads itself, for a message or a request. Every other default - image tags, thread
# counts, timeouts, forecast identities - is the compose files' `${VAR:-...}`, written once. A copy
# here is a second value that drifts.
Set-Default EXPLORER_PORT '8080'
Set-Default KAFKA_PORT '9092'
Set-Default KEX_AGENT_PORT '8081'
# 8081 belongs to the agent in the shared port plan; Schema Registry moves aside.
Set-Default SCHEMA_REGISTRY_PORT '8085'

# LLM - OpenRouter on both sides. An empty key stays unset: Windows has no empty variable.
Set-Default OPENROUTER_MODEL 'openai/gpt-4o-mini'
Set-Default KEX_AGENT_LLM_PROVIDER 'openai'
# The SQL & metric assistant, on the same OpenRouter model (false turns it off). It needs that model,
# which the forecast demo has only with -Agent: off there otherwise.
Set-Default EXPLORER_ASSISTANT_ENABLED $(if ($forecastOnly -and -not $agentOn) { 'false' } else { 'true' })

# Agent and MCP. The MCP token defaults to the generated one from .forecast-stack/.env.
Set-Default KEX_AGENT_IMAGE_NAMESPACE 'compagnonsdudev'
Set-Default KEX_AGENT_API_KEY 'dev-only-agent-key'
Set-Default EXPLORER_MCP_AUTH_TOKEN 'dev-only-mcp-token'

# SpectraLLM. Its MCP server refuses to run without SPECTRA_API_KEY, and with one set its own UI
# answers 401 (it sends no key); the agent and the API are unaffected.
Set-Default SPECTRA_API_KEY 'dev-only-spectra-key'
Set-Default SPECTRA_UI_PORT '8088'
Set-Default SPECTRA_API_PORT '8089'
# The name and the file must agree (.env.example); a laptop wants the 3B named there.
Set-Default LLM_CHAT_MODEL_NAME 'qwen2.5-7b-instruct'

# A prebuilt demo follows the published images (the agent too, with -Agent).
# Other stacks keep the compose files' versioned pins; explicit tags from the environment/.env win.
if ($prebuilt) {
    Set-Default EXPLORER_IMAGE_TAG 'latest'
    Set-Default FORECAST_IMAGE_TAG 'latest'
    if ($agentOn) { Set-Default KEX_AGENT_IMAGE_TAG 'latest' }
}

# Forecasting.
Set-Default FORECAST_CONFIG_DIR './.forecast-stack/config'
Set-Default TIMESFM_CPUS '4.0'
Set-Default TIMESFM_MEM_LIMIT '8g'
Set-Default TIMESTRAL_SHADOW_ENABLED 'false'

# Explorer image: built from this checkout by default; DEMO_PUBLISHED_IMAGE=true pulls the release.
Set-Default DEMO_PUBLISHED_IMAGE 'false'
Set-Default EXPLORER_IMAGE_NAMESPACE 'compagnonsdudev'

# SpectraLLM on by default; false drops its seven services and their ~4.8 GB first download.
Set-Default DEMO_SPECTRA 'true'
if ($env:DEMO_SPECTRA -notin @('true', 'false')) {
    throw "DEMO_SPECTRA must be true or false, not '$($env:DEMO_SPECTRA)'."
}
$spectraOn = $env:DEMO_SPECTRA -eq 'true'

Set-Default COMPOSE_PROJECT_NAME 'kafka-explorer-demo'

$script:ComposeFiles = @('-f', 'docker-compose.yml', '-f', 'compose/schema-registry.yml',
    '-f', 'compose/mcp.yml', '-f', 'compose/forecasts.yml', '-f', 'compose/demo-forecast.yml')
if ($prebuilt) {
    $script:ComposeFiles += @('-f', 'compose/forecast-offline.yml', '-f', 'compose/demo-forecast-prebuilt.yml')
}
if ($env:DEMO_PUBLISHED_IMAGE -eq 'true') { $script:ComposeFiles += @('-f', 'compose/image.yml') }
# The forecast demo stops here unless -Agent: the agent (compose/demo*.yml, ecosystem) and the
# hosted model (demo-llm.yml) are what want a key, and nothing else it shows uses them.
if ($agentOn) {
    $script:ComposeFiles += @('-f', 'compose/demo-no-spectra.yml', '-f', 'compose/demo-agent-no-spectra.yml', '-f', 'compose/demo-llm.yml')
} elseif (-not $forecastOnly) {
    if ($spectraOn) {
        $script:ComposeFiles += @('-f', 'compose/demo.yml', '-f', 'compose/spectra-hub.mcp.yml',
            '-f', 'compose/spectra-hub.ingest.yml', '-f', 'compose/ecosystem.yml')
    } else {
        $script:ComposeFiles += @('-f', 'compose/demo-no-spectra.yml', '-f', 'compose/demo-agent-no-spectra.yml')
    }
    $script:ComposeFiles += @('-f', 'compose/demo-llm.yml')
}


# No Java, Python, jq, Node or local build is required on Windows.
Set-Default DEMO_FORECAST_READY_TIMEOUT '900'

function Test-ForecastPreflight {
    if (-not $prebuilt) { throw 'doctor requires a prebuilt demo (-Prebuilt, -Forecast -Prebuilt).' }
    $versionText = [string]((& docker compose version --short) | Select-Object -First 1)
    if ($versionText -notmatch '^v?(\d+)\.(\d+)') { throw "Cannot identify Docker Compose version: $versionText" }
    $major = [int]$Matches[1]; $minor = [int]$Matches[2]
    if ($major -lt 2 -or ($major -eq 2 -and $minor -lt 24)) { throw "Docker Compose 2.24+ required (found $versionText)." }
    & docker info *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Engine is not running.' }
    Invoke-Compose config --quiet
    foreach ($secret in @('FORECAST_POSTGRES_PASSWORD', 'TIMESFM_TOKEN', 'EXPLORER_MCP_AUTH_TOKEN')) {
        if (-not (Test-EnvSet $secret)) { throw "Required variable is empty: $secret" }
    }
    $publishedPorts = @($env:EXPLORER_PORT, $env:SCHEMA_REGISTRY_PORT, $env:KAFKA_PORT)
    if ($agentOn) { $publishedPorts += $env:KEX_AGENT_PORT }
    if (@($publishedPorts | Sort-Object -Unique).Count -ne $publishedPorts.Count) {
        throw 'Explorer, Schema Registry, Kafka (and agent) host ports must be different.'
    }
    $memoryText = [string]((& docker info --format '{{.MemTotal}}') | Select-Object -First 1)
    $memoryMb = [math]::Floor(([double]$memoryText) / 1MB)
    Write-Host "Docker: Compose $versionText; $memoryMb MiB RAM (TimesFM limit $($env:TIMESFM_MEM_LIMIT))."
    if ($memoryMb -lt 8192) { Write-Warning 'Less than 8 GiB assigned to Docker; forecasting may be slow or OOM.' }
    $running = @((Invoke-Compose ps -q) | Where-Object { $_ }).Count -gt 0
    foreach ($portText in $publishedPorts) {
        $port = 0
        if (-not [int]::TryParse($portText, [ref]$port) -or $port -lt 1 -or $port -gt 65535) {
            throw "Invalid port: $portText"
        }
        $client = New-Object System.Net.Sockets.TcpClient
        try {
            $pending = $client.BeginConnect('127.0.0.1', $port, $null, $null)
            $occupied = $pending.AsyncWaitHandle.WaitOne(250) -and $client.Connected
        } catch { $occupied = $false } finally { $client.Close() }
        if ($occupied -and -not $running) { throw "Port $port is occupied (no running container in this Compose project)." }
        if ($occupied) { Write-Warning "Port $port is occupied; an existing demo may own it." }
    }
    foreach ($image in @((Invoke-Compose config --images) | Sort-Object -Unique)) {
        if ([string]::IsNullOrWhiteSpace($image)) { continue }
        & docker image inspect $image *> $null
        if ($LASTEXITCODE -ne 0) {
            if ($offline) { throw "Offline image missing: $image (docker load or pull before starting)." }
            Write-Host "Image to pull: $image"
        }
    }
    if ($offline) { Write-Host 'Offline check: all images are local.' }
    Write-Host "Demo images: $($env:EXPLORER_IMAGE_NAMESPACE)/kafkaexplorer:$($env:EXPLORER_IMAGE_TAG); ghcr.io/devdownin/kex-forecast:$($env:FORECAST_IMAGE_TAG)"
    if ($agentOn) { Write-Host "Agent image: $($env:KEX_AGENT_IMAGE_NAMESPACE)/kex-agent-ai:$($env:KEX_AGENT_IMAGE_TAG)" }
    Write-Host 'Preflight OK.'
}

function Assert-ForecastReady {
    if (-not $forecastOnly) { throw 'verify is available in forecast mode only.' }
    $cid = [string]((Invoke-Compose ps -aq demo-forecast-seed) | Select-Object -First 1)
    if (-not $cid) { throw 'Seeder has not started.' }
    $state = [string]((& docker inspect --format '{{.State.Status}}' $cid) | Select-Object -First 1)
    $exitCode = [string]((& docker inspect --format '{{.State.ExitCode}}' $cid) | Select-Object -First 1)
    if ($state -ne 'exited') { throw "Seeder is still $state." }
    if ($exitCode -ne '0') { throw "Seeder failed ($exitCode): inspect logs demo-forecast-seed." }
    foreach ($service in @('kafka', 'demo-live', 'forecast-postgres', 'timesfm', 'explorer')) {
        $id = [string]((Invoke-Compose ps -q $service) | Select-Object -First 1)
        if (-not $id) { throw "Service $service is not running." }
        $isRunning = [string]((& docker inspect --format '{{.State.Running}}' $id) | Select-Object -First 1)
        if ($isRunning -ne 'true') { throw "Service $service is stopped." }
    }
    $timesfm = [string]((Invoke-Compose ps -q timesfm) | Select-Object -First 1)
    $health = [string]((& docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' $timesfm) | Select-Object -First 1)
    if ($health -ne 'healthy') { throw 'TimesFM has not become healthy.' }
    $sql = "SELECT count(*) FROM (SELECT payload::jsonb->>'metricId' AS metric_id FROM kex_metric_observation_v1 WHERE payload::jsonb->>'collectorRunId' LIKE 'demo-seed-%' AND payload::jsonb->>'component'='value' GROUP BY 1 HAVING count(*) >= 500) seeded;"
    $count = [string]((Invoke-Compose exec -T forecast-postgres psql -U forecasts -d forecasts -At -c $sql) | Select-Object -First 1)
    if ($count.Trim() -ne '5') { throw "Only $count/5 metrics have >=500 historical observations." }
    $metrics = @(Invoke-RestMethod -Uri "http://127.0.0.1:$($env:EXPLORER_PORT)/api/metrics" -TimeoutSec 10)
    foreach ($name in @('unshipped', 'backlog', 'creep', 'jitter', 'shift')) {
        if (-not @($metrics | Where-Object { $_.id -eq "demo-live-$name" }).Count) { throw "Metric demo-live-$name is missing." }
    }
    $forecast = Invoke-RestMethod -Uri "http://127.0.0.1:$($env:EXPLORER_PORT)/api/forecasts" -TimeoutSec 10
    $series = @($forecast.series | Where-Object { $_.metricId -eq 'demo-live-unshipped' })
    if ($series.Count -eq 0 -or -not $series[0].result -or
        $series[0].result.state -ne 'READY' -or $series[0].result.strategy -ne 'TIMESFM' -or
        -not $series[0].result.forecast) { throw 'Unshipped-orders TimesFM forecast is not READY.' }
    Write-Host 'Ready: Kafka, PostgreSQL, Explorer, TimesFM, live traffic, five histories and TimesFM forecast.'
}

function Wait-ForecastReady {
    Write-Host 'Waiting for live data, five histories and first READY TimesFM forecast...'
    $deadline = (Get-Date).AddSeconds([int]$env:DEMO_FORECAST_READY_TIMEOUT)
    while ($true) {
        try { Assert-ForecastReady; return } catch {
            $message = $_.Exception.Message
            if ((Get-Date) -ge $deadline) { throw "Demo never became fully ready: $message (logs demo-forecast-seed / timesfm)." }
            Start-Sleep -Seconds 5
        }
    }
}

function Set-ForecastScenario([string] $Name) {
    if (-not $forecastOnly) { throw 'scenario is available in forecast mode only.' }
    $controlDirectory = Join-Path $env:FORECAST_CONFIG_DIR 'demo-control'
    $file = Join-Path $controlDirectory 'demo-scenario.conf'
    if (-not $Name) {
        if (Test-Path -LiteralPath $file) { Write-Host (([IO.File]::ReadAllText((Resolve-Path $file).Path).Trim() -split '\|', 2)[0]) }
        else { Write-Host 'normal' }
        return
    }
    if ($Name -notin @('normal', 'overload', 'incident', 'recover', 'seasonal')) {
        throw 'Valid scenarios: normal, overload, incident, recover, seasonal.'
    }
    New-Item -ItemType Directory -Path $controlDirectory -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path (Resolve-Path $controlDirectory).Path 'demo-scenario.conf'),
        "$Name`n", $Utf8NoBom)
    Write-Host "Live Kafka scenario: $Name (applied at the next tick, default 15 s)."
}

function Start-Demo {
    if ($forecastOnly) {
        # Isolate the writable scenario receipts from the private forecast config.
        New-Item -ItemType Directory -Path (Join-Path $env:FORECAST_CONFIG_DIR 'demo-control') -Force | Out-Null
    }
    # The agent's bearer, before the long part (build, pulls): it is what the Control Center asks for.
    if (-not $forecastOnly -or $agentOn) {
        Write-Host "Kex-anHarness bearer (KEX_AGENT_API_KEY): $($env:KEX_AGENT_API_KEY) -- http://localhost:$($env:KEX_AGENT_PORT)"
    }
    if ((-not $forecastOnly -or $agentOn) -and -not (Test-EnvSet 'OPENROUTER_API_KEY')) {
        Write-Warning ('OPENROUTER_API_KEY is empty - Process Mining answers without its LLM half and the ' +
            "agent starts DEGRADED. Set it (`$env:OPENROUTER_API_KEY = 'sk-or-...', or in .env) and rerun.")
    }
    if ($prebuilt) {
        foreach ($name in @('FORECAST_POSTGRES_PASSWORD', 'TIMESFM_TOKEN', 'EXPLORER_MCP_AUTH_TOKEN')) {
            if (-not (Test-EnvSet $name)) { throw "Missing $name. Check .env or .forecast-stack/.env." }
        }
        Test-ForecastPreflight
        Write-Host "Prebuilt demo: published images$(if ($agentOn -or -not $forecastOnly) { ' (agent included)' }), no build or model download."
        Write-Host "TimesFM resources: $($env:TIMESFM_CPUS) CPUs / $($env:TIMESFM_MEM_LIMIT). Licence: services/timesfm/licenses/NOTICE.md"
        Invoke-Compose config --quiet
        if (-not $offline) { Invoke-Compose pull --quiet --ignore-buildable }
        Invoke-Compose up -d --no-build --pull never --remove-orphans
    } else {
        Write-Host "First run: Maven build of the Explorer and the pinned TimesFM checkpoint ($($env:TIMESFM_CPUS) CPUs / $($env:TIMESFM_MEM_LIMIT))."
        if ($spectraOn) { Write-Host 'SpectraLLM adds ~4.8 GB of weights; its 7B chat model wants ~16 GB of RAM.' }
        Write-Host 'Docker Desktop memory: see the [wsl2] memory= setting in %UserProfile%\.wslconfig. Later runs start in seconds.'
        if ($env:DEMO_PUBLISHED_IMAGE -eq 'true') { Invoke-Compose pull --quiet --ignore-buildable }
        Invoke-Compose up -d --build --remove-orphans
    }

    $explorerReady = Wait-Http 'Kafka Explorer' "http://127.0.0.1:$($env:EXPLORER_PORT)/actuator/health" 900
    if ($forecastOnly -and -not $explorerReady) { throw 'Kafka Explorer did not become healthy. See: .\demo-forecast.cmd logs explorer timesfm' }
    if ($forecastOnly) {
        Wait-ForecastReady
        $agentLine = 'LLM, agent, SpectraLLM  not started (-NoAgent / -Offline): nothing to configure'
        if ($agentOn) {
            Wait-Http 'Kex-anHarness' "http://127.0.0.1:$($env:KEX_AGENT_PORT)/api/agent/mcp/servers" 300 `
                @{ Authorization = "Bearer $($env:KEX_AGENT_API_KEY)" } | Out-Null
            $agentLine = "Kex-anHarness ......... http://localhost:$($env:KEX_AGENT_PORT)     (Bearer $($env:KEX_AGENT_API_KEY))`n" +
                "LLM ................... OpenRouter $($env:OPENROUTER_MODEL) (Explorer, agent); SpectraLLM not started"
        }
        Write-Host @"

Kafka SQL Explorer .... http://localhost:$($env:EXPLORER_PORT)   -> Metrics
  MCP ................. http://localhost:$($env:EXPLORER_PORT)/mcp   (Bearer in .forecast-stack\.env or EXPLORER_MCP_AUTH_TOKEN)
Schema Registry ....... http://localhost:$($env:SCHEMA_REGISTRY_PORT)
Kafka ................. localhost:$($env:KAFKA_PORT)
$agentLine

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
        'SpectraLLM ............ off (-NoSpectra); the agent declares no SpectraLLM connection'
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

if ($Services.Count -gt 0 -and $Command -notin @('logs', 'scenario')) {
    throw "Only 'logs' takes service names. Usage: .\demo.cmd [-NoSpectra|-Forecast [-Prebuilt] [-Agent]] [up|down|reset|ps|logs [service]|probe]"
}

switch ($Command) {
    'up'    { Start-Demo }
    'down'  { Invoke-Compose down --remove-orphans }
    # Volumes included: topics, forecast history, agent state, downloaded models. Credentials stay.
    'reset' { Invoke-Compose down -v --remove-orphans }
    'ps'    { Invoke-Compose ps }
    'logs'  { Invoke-Compose logs -f --tail 200 @Services }
    'doctor' { Test-ForecastPreflight }
    'verify' { Assert-ForecastReady }
    'scenario' {
        if ($Services.Count -gt 1) { throw 'scenario accepts at most one name.' }
        $name = if ($Services.Count) { $Services[0] } else { '' }
        Set-ForecastScenario $name
    }
    'probe' {
        Invoke-Compose --profile probe run --rm mcp-probe
        if ($spectraOn) { Invoke-Compose --profile probe run --rm spectra-mcp-probe }
    }
}
