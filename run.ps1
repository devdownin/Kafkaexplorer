# SPDX-License-Identifier: AGPL-3.0-or-later
# Windows launcher for KafkaExplorer + Kafka + PostgreSQL + TimesFM.
# Docker Desktop (Compose v2) is the only required installation.
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('up', 'down', 'ps', 'logs', 'config')]
    [string] $Command = 'up',
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)]
    [string[]] $Services = @()
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Test-Set([string] $Name) {
    -not [string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($Name, 'Process'))
}
function Import-Env([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    foreach ($line in [IO.File]::ReadAllLines((Resolve-Path -LiteralPath $Path).Path)) {
        if ($line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { continue }
        $name = $Matches[1]
        $value = $Matches[2].Trim()
        if ($value -match '^"(.*)"$' -or $value -match "^'(.*)'$") { $value = $Matches[1] }
        if (-not (Test-Set $name)) {
            [Environment]::SetEnvironmentVariable($name, $value, 'Process')
        }
    }
}
function Set-Default([string] $Name, [string] $Value) {
    if (-not (Test-Set $Name)) {
        [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
    }
}
function New-Secret {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    -join ($bytes | ForEach-Object { $_.ToString('x2') })
}
function Initialize-Forecast {
    $configDir = Join-Path $PSScriptRoot '.forecast-stack\config'
    [IO.Directory]::CreateDirectory($configDir) | Out-Null
    $secretFile = Join-Path $PSScriptRoot '.forecast-stack\.env'
    if (-not [IO.File]::Exists($secretFile)) {
        $content = "FORECAST_POSTGRES_PASSWORD=$(New-Secret)`nTIMESFM_TOKEN=$(New-Secret)`n" +
            "EXPLORER_MCP_AUTH_TOKEN=$(New-Secret)`nFORECAST_ENVIRONMENTS=local`n"
        # CreateNew prevents replacing secrets on a concurrent start.
        try {
            $stream = New-Object IO.FileStream($secretFile, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
            try {
                $writer = New-Object IO.StreamWriter($stream, $utf8)
                try { $writer.Write($content) } finally { $writer.Dispose() }
            } finally { $stream.Dispose() }
        } catch [IO.IOException] {
            if (-not [IO.File]::Exists($secretFile)) { throw }
        }
    }
    $approvalFile = Join-Path $configDir 'forecasts.yml'
    if (-not [IO.File]::Exists($approvalFile)) {
        try {
            $stream = New-Object IO.FileStream($approvalFile, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
            try {
                $writer = New-Object IO.StreamWriter($stream, $utf8)
                try { $writer.Write("# Reviewed forecast approvals (optional).`n{}`n") } finally { $writer.Dispose() }
            } finally { $stream.Dispose() }
        } catch [IO.IOException] {
            if (-not [IO.File]::Exists($approvalFile)) { throw }
        }
    }
}
function Invoke-Compose([string[]] $Arguments) {
    & docker compose @script:Files @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose failed (exit code $LASTEXITCODE)." }
}
try {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw 'Docker Desktop is required: https://www.docker.com/products/docker-desktop/'
    }
    & docker compose version *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose v2 is required.' }

    Initialize-Forecast
    # Explicit process environment > repository .env > generated secrets > defaults.
    Import-Env '.env'
    Import-Env '.forecast-stack\.env'
    Set-Default COMPOSE_PROJECT_NAME 'kafkaexplorer'
    Set-Default BIND_ADDR '127.0.0.1'
    Set-Default EXPLORER_PORT '8080'
    Set-Default KAFKA_PORT '9092'
    Set-Default KAFKA_BOOTSTRAP_SERVERS 'kafka:29092'
    Set-Default FORECAST_ENVIRONMENTS 'local'
    Set-Default FORECAST_CLUSTER_ID 'local'
    Set-Default FORECAST_COLLECTOR_ID 'kafkaexplorer'
    Set-Default FORECAST_RUNTIME_APPROVAL 'true'
    Set-Default FORECAST_CONFIG_DIR './.forecast-stack/config'
    Set-Default TIMESFM_THREADS '4'
    Set-Default TIMESFM_CPUS '4.0'
    Set-Default TIMESFM_MEM_LIMIT '8g'
    Set-Default TIMESFM_TIMEOUT '35s'
    Set-Default TIMESTRAL_SHADOW_ENABLED 'false'
    Set-Default TIMESTRAL_REVISION ''
    Set-Default TIMESTRAL_SHA256 ''
    Set-Default TIMESTRAL_THREADS '1'
    Set-Default TIMESTRAL_TIMEOUT '15s'
    Set-Default EXPLORER_MCP_READONLY 'true'

    foreach ($name in @('FORECAST_POSTGRES_PASSWORD', 'TIMESFM_TOKEN', 'EXPLORER_MCP_AUTH_TOKEN')) {
        if (-not (Test-Set $name)) { throw "Missing $name. Check .forecast-stack\.env or the process environment." }
    }
    if ($Services.Count -and $Command -ne 'logs') {
        throw 'Service names are supported only with: run.bat logs [service]'
    }
    $script:Files = @('--env-file', '.forecast-stack/.env', '-f', 'docker-compose.yml',
        '-f', 'compose/forecasts.yml')
    switch ($Command) {
        'up' {
            Write-Host 'Starting KafkaExplorer, Kafka, PostgreSQL and TimesFM (Docker Compose).'
            Write-Host 'First start downloads the pinned TimesFM model; budget 4 CPUs / 8 GiB for TimesFM.'
            Invoke-Compose -Arguments @('config', '--quiet')
            Invoke-Compose -Arguments @('up', '-d', '--build')
            # The mounted approval YAML is read at startup; a later edit requires restart.
            Write-Host "KafkaExplorer: http://localhost:$($env:EXPLORER_PORT)"
            Write-Host 'Metrics -> Prepare a forecast. History must accumulate before a prediction is available.'
            Write-Host 'Status: run.bat ps    Logs: run.bat logs explorer timesfm forecast-postgres'
        }
        'down'   { Invoke-Compose -Arguments @('down') }
        'ps'     { Invoke-Compose -Arguments @('ps') }
        'logs'   { Invoke-Compose -Arguments (@('logs', '--tail', '200') + $Services) }
        'config' { Invoke-Compose -Arguments @('config', '--quiet'); Write-Host 'Compose configuration valid.' }
    }
} catch {
    Write-Error $_
    exit 1
}
