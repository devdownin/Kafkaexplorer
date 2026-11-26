# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
#
# Picks the demo options for you and runs demo.ps1 with the matching switches. Twin of
# demo-menu.sh: same questions, same coherent combinations, same credential handling.
#
#   .\demo-menu.cmd                          # ask, recap, check the key, confirm, run
#   .\demo-menu.cmd -DryRun                  # the same, run nothing
#   .\demo-menu.cmd -Preset forecast         # skip the questions (-Presets lists them)
#   .\demo-menu.cmd -Preset forecast -Yes    # ...and the confirmation: for scripts
#
# Output: colour only on a terminal, never with NO_COLOR set or -NoColor, and never alone: every
# colour is backed by words ("[default]", "valid", "rejected"), and every answer is a number or a
# letter. A demo that is already running is detected first, and the menu starts from what you can
# do to it.
#
# Credentials: each token is shown with where it comes from (environment, .env, generated in
# .forecast-stack\.env, or the dev-only default), masked unless you ask. A secret is written only
# when you say so: the OpenRouter key into .env on request, the agent and MCP tokens on "renew".
# .forecast-stack\last-demo holds the last choice and no secret.
#
# Kept ASCII on purpose: Windows PowerShell 5.1 reads a BOM-less script as the ANSI code page.
[CmdletBinding()]
param(
    [switch] $DryRun,
    [switch] $Yes,
    [switch] $NoColor,
    [string] $Preset = '',
    [switch] $Presets
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

# UTF-8 without a BOM, LF line endings: these files are also read by demo.sh and by Compose.
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$StateDir = '.forecast-stack'
$LastFile = Join-Path $StateDir 'last-demo'
$StateEnv = Join-Path $StateDir '.env'

$UseColor = (-not $NoColor) -and (-not $env:NO_COLOR) -and [Environment]::UserInteractive -and (-not [Console]::IsOutputRedirected)
# Kind: title (bold-ish white), ok (green), warn (yellow); anything else is plain.
function Say([string] $Text, [string] $Kind = '') {
    $color = switch ($Kind) { 'title' { 'White' } 'ok' { 'Green' } 'warn' { 'Yellow' } default { '' } }
    if ($UseColor -and $color) { Write-Host $Text -ForegroundColor $color } else { Write-Host $Text }
}

function Show-Presets {
    @(
        'full               Full suite: Kafka, Avro, MCP, forecasts, SpectraLLM, agent',
        'full-no-spectra    Full suite without SpectraLLM',
        'forecast           Forecast demo, built from this checkout, with the agent',
        'forecast-no-agent  Forecast demo, built from this checkout, without the agent',
        'forecast-prebuilt  Forecast demo from published images, with the agent',
        'forecast-offline   Forecast demo, models baked in, no download, no agent'
    ) | ForEach-Object { Write-Host $_ }
}
if ($Presets) { Show-Presets; exit 0 }

# Returns the 1-based index of the chosen option; Enter takes the default.
function Ask([string] $Question, [int] $Default, [string[]] $Options) {
    Write-Host ''
    Say $Question 'title'
    for ($i = 0; $i -lt $Options.Count; $i++) {
        $mark = if (($i + 1) -eq $Default) { '  [default]' } else { '' }
        Write-Host ("  {0}) {1}{2}" -f ($i + 1), $Options[$i], $mark)
    }
    while ($true) {
        $answer = Read-Host "Choice [$Default]"
        if ([string]::IsNullOrWhiteSpace($answer)) { return $Default }
        $n = 0
        if ([int]::TryParse($answer, [ref] $n) -and $n -ge 1 -and $n -le $Options.Count) { return $n }
        Say "Enter a number between 1 and $($Options.Count) (or press Enter for the default)." 'warn'
    }
}

# -- What a demo is: Mode (full|nospectra|forecast), Source (build|prebuilt|offline), Agent (yes|no)
$script:Mode = ''
$script:Source = 'build'
$script:Agent = 'yes'

function Set-FromPreset([string] $Name) {
    $script:Source = 'build'; $script:Agent = 'yes'
    switch ($Name) {
        'full' { $script:Mode = 'full' }
        'full-no-spectra' { $script:Mode = 'nospectra' }
        'forecast' { $script:Mode = 'forecast' }
        'forecast-no-agent' { $script:Mode = 'forecast'; $script:Agent = 'no' }
        'forecast-prebuilt' { $script:Mode = 'forecast'; $script:Source = 'prebuilt' }
        'forecast-offline' { $script:Mode = 'forecast'; $script:Source = 'offline'; $script:Agent = 'no' }
        default {
            Write-Host "Unknown preset '$Name'. Known presets:"
            Show-Presets
            exit 2
        }
    }
}

function Get-DemoLabel([string] $M, [string] $S, [string] $A) {
    if ($M -eq 'full') { return 'Full suite with SpectraLLM' }
    if ($M -eq 'nospectra') { return 'Full suite without SpectraLLM' }
    $where = switch ($S) { 'build' { 'built here' } 'prebuilt' { 'published images' } 'offline' { 'offline' } }
    $agentText = if ($A -eq 'yes') { 'with the agent' } else { 'without the agent' }
    "Forecast demo, $where, $agentText"
}

# The switches demo.ps1 takes, from the choices above.
function Get-Switches {
    $list = @()
    if ($script:Mode -eq 'nospectra') { $list += 'NoSpectra' }
    if ($script:Mode -eq 'forecast') {
        $list += 'Forecast'
        if ($script:Source -eq 'prebuilt') { $list += 'Prebuilt' }
        if ($script:Source -eq 'offline') { $list += 'Offline' }
        if ($script:Source -ne 'offline' -and $script:Agent -ne 'yes') { $list += 'NoAgent' }
    }
    , $list
}

function Ask-Demo {
    $m = Ask 'Which demo?' 1 @(
        'Full suite: Kafka, Avro, MCP, forecasts, SpectraLLM, agent (needs OPENROUTER_API_KEY, ~4.8 GB of weights)',
        'Full suite without SpectraLLM (needs OPENROUTER_API_KEY)',
        'Forecast demo: live metrics and forecasts, the agent included')
    $script:Source = 'build'; $script:Agent = 'yes'
    $script:Mode = @('full', 'nospectra', 'forecast')[$m - 1]
    if ($script:Mode -ne 'forecast') { return }
    $s = Ask 'Where do the images come from?' 1 @(
        'Built from this checkout (Maven + the TimesFM download on the first run)',
        'Published images, nothing built (needs a pull)',
        'Offline: published images with the models baked in, no download, no agent')
    $script:Source = @('build', 'prebuilt', 'offline')[$s - 1]
    if ($script:Source -eq 'offline') { $script:Agent = 'no'; return }
    $a = Ask 'Start the Kex-anHarness agent?' 1 @(
        'Yes (it starts degraded without OPENROUTER_API_KEY)',
        'No')
    $script:Agent = if ($a -eq 1) { 'yes' } else { 'no' }
}

# "Same as last time" first, when there is a last time.
function Choose-Demo {
    if (Test-Path -LiteralPath $LastFile) {
        $saved = @{}
        foreach ($line in [IO.File]::ReadAllLines((Join-Path $PWD $LastFile))) {
            if ($line -match '^(mode|source|agent)=(.*)$') { $saved[$Matches[1]] = $Matches[2].Trim() }
        }
        if ($saved['mode'] -match '^(full|nospectra|forecast)$' -and
            $saved['source'] -match '^(build|prebuilt|offline)$' -and
            $saved['agent'] -match '^(yes|no)$') {
            $c = Ask 'Which demo?' 1 @(
                ("Same as last time: " + (Get-DemoLabel $saved['mode'] $saved['source'] $saved['agent'])),
                'Choose again')
            if ($c -eq 1) {
                $script:Mode = $saved['mode']; $script:Source = $saved['source']; $script:Agent = $saved['agent']
                return
            }
        }
    }
    Ask-Demo
}

function Save-Demo {
    New-Item -ItemType Directory -Force -Path $StateDir | Out-Null
    $text = "mode=$($script:Mode)`nsource=$($script:Source)`nagent=$($script:Agent)`n"
    [IO.File]::WriteAllText((Join-Path $PWD $LastFile), $text, $Utf8NoBom)
}

# -- Credentials ---------------------------------------------------------------

function Read-Kv([string] $File, [string] $Key) {
    if (-not (Test-Path -LiteralPath $File)) { return '' }
    foreach ($line in [IO.File]::ReadAllLines((Join-Path $PWD $File))) {
        if ($line.StartsWith("$Key=")) {
            $value = $line.Substring($Key.Length + 1).Trim()
            if ($value.Length -ge 2 -and ($value[0] -eq '"' -or $value[0] -eq "'") -and $value[-1] -eq $value[0]) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            return $value
        }
    }
    ''
}

function Get-EnvValue([string] $Key) { [Environment]::GetEnvironmentVariable($Key, 'Process') }

# The defaults demo.ps1 falls back to when nothing else sets the variable.
function Get-Fallback([string] $Key) {
    switch ($Key) {
        'KEX_AGENT_API_KEY' { 'dev-only-agent-key' }
        'EXPLORER_MCP_AUTH_TOKEN' { 'dev-only-mcp-token' }
        'SPECTRA_API_KEY' { 'dev-only-spectra-key' }
        default { '' }
    }
}

# Same precedence as demo.ps1: environment > .env > .forecast-stack\.env > default.
function Get-Origin([string] $Key) {
    if (Get-EnvValue $Key) { return 'environment' }
    if (Read-Kv '.env' $Key) { return '.env' }
    if (Read-Kv $StateEnv $Key) { return 'generated' }
    if (($Key -in @('KEX_AGENT_API_KEY', 'EXPLORER_MCP_AUTH_TOKEN')) -and -not (Test-Path -LiteralPath $StateEnv)) {
        return 'generated at the first start'
    }
    if (Get-Fallback $Key) { return 'dev-only default (keep the port private)' }
    'not set'
}

function Get-Value([string] $Key) {
    $v = Get-EnvValue $Key
    if (-not $v) { $v = Read-Kv '.env' $Key }
    if (-not $v) { $v = Read-Kv $StateEnv $Key }
    if (-not $v) { $v = Get-Fallback $Key }
    $v
}

function Get-Masked([string] $v) {
    if (-not $v) { return '-' }
    if ($v.Length -gt 12) { return $v.Substring(0, 4) + '...' + $v.Substring($v.Length - 4) }
    '****'
}

function Show-Tokens([bool] $Reveal, [string[]] $Names) {
    Write-Host ''
    Say 'Credentials' 'title'
    foreach ($name in $Names) {
        $v = Get-Value $name
        $shown = if (-not $v) { '-' } elseif ($Reveal) { $v } else { Get-Masked $v }
        Write-Host ("  {0,-24} {1,-20} ({2})" -f $name, $shown, (Get-Origin $name))
    }
}

function New-Token {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    -join ($bytes | ForEach-Object { $_.ToString('x2') })
}

function Renew-Tokens {
    if (-not (Test-Path -LiteralPath $StateEnv)) {
        Write-Host 'No generated credentials yet: they are created at the first start. Start the demo once, then renew.'
        return
    }
    $elsewhere = @()
    foreach ($key in @('KEX_AGENT_API_KEY', 'EXPLORER_MCP_AUTH_TOKEN')) {
        if ((Get-Origin $key) -ne 'generated') { $elsewhere += $key }
    }
    Write-Host ''
    Write-Host 'This replaces KEX_AGENT_API_KEY and EXPLORER_MCP_AUTH_TOKEN in .forecast-stack\.env.'
    Write-Host 'The PostgreSQL and TimesFM credentials are left alone: the database volume holds the'
    Write-Host 'password, and only "reset" starts over. Restart the demo so the agent and the Explorer'
    Write-Host 'pick the new values up.'
    if ($elsewhere) { Write-Host ("Note: set elsewhere, so a new generated value will NOT apply: " + ($elsewhere -join ' ')) }
    $go = Read-Host 'Renew them? [y/N]'
    if ($go -notmatch '^[Yy]$') { Write-Host 'Nothing changed.'; return }
    $new = @{ 'KEX_AGENT_API_KEY' = (New-Token); 'EXPLORER_MCP_AUTH_TOKEN' = (New-Token) }
    $out = New-Object System.Collections.Generic.List[string]
    $seen = @{}
    foreach ($line in [IO.File]::ReadAllLines((Join-Path $PWD $StateEnv))) {
        $key = ($line -split '=', 2)[0]
        if ($new.ContainsKey($key)) { $out.Add("$key=$($new[$key])"); $seen[$key] = $true } else { $out.Add($line) }
    }
    foreach ($key in $new.Keys) { if (-not $seen[$key]) { $out.Add("$key=$($new[$key])") } }
    [IO.File]::WriteAllText((Join-Path $PWD $StateEnv), (($out -join "`n") + "`n"), $Utf8NoBom)
    Say 'Renewed. Restart the demo (stop, then start) to apply them.' 'ok'
}

# OpenRouter: ok | invalid | unknown.
function Test-Key([string] $Key) {
    try {
        Invoke-WebRequest -Uri 'https://openrouter.ai/api/v1/auth/key' -Headers @{ Authorization = "Bearer $Key" } `
            -UseBasicParsing -TimeoutSec 8 | Out-Null
        return 'ok'
    } catch {
        $code = 0
        if ($_.Exception.Response) { $code = [int] $_.Exception.Response.StatusCode }
        if ($code -eq 401 -or $code -eq 403) { return 'invalid' }
        return 'unknown'
    }
}

function Save-Key([string] $Key) {
    $existing = ''
    if (Test-Path -LiteralPath '.env') {
        $existing = [IO.File]::ReadAllText((Join-Path $PWD '.env'))
        if ($existing.Length -gt 0 -and -not $existing.EndsWith("`n")) { $existing += "`n" }
    }
    [IO.File]::WriteAllText((Join-Path $PWD '.env'), "${existing}OPENROUTER_API_KEY=$Key`n", $Utf8NoBom)
    Say 'Saved in .env (git-ignored).' 'ok'
}

# Asks for the key when a model is involved and none is set, checks it, offers to keep it.
function Confirm-Key {
    $key = Get-EnvValue 'OPENROUTER_API_KEY'
    $origin = 'environment'
    if (-not $key) { $key = Read-Kv '.env' 'OPENROUTER_API_KEY'; $origin = '.env' }
    $interactive = [Environment]::UserInteractive -and -not [Console]::IsInputRedirected
    $result = ''
    while ($true) {
        if (-not $key) {
            if (-not $interactive) { return }
            $secure = Read-Host 'OPENROUTER_API_KEY (Enter to skip: the agent and Process Mining start degraded)' -AsSecureString
            $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
            try { $key = [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr) }
            finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
            if (-not $key) { return }
            $origin = 'typed'
        }
        $result = Test-Key $key
        if ($result -eq 'ok') { Say 'OpenRouter key checked: valid.' 'ok'; break }
        if ($result -eq 'unknown') { Say 'OpenRouter key not checked (no answer from openrouter.ai): continuing.' 'warn'; break }
        Say "OpenRouter rejected this key ($origin): it was refused with 401 or 403." 'warn'
        if (-not $interactive) { break }
        $c = Ask 'What now?' 1 @('Enter another key', 'Continue anyway (the agent and Process Mining start degraded)', 'Cancel')
        if ($c -eq 1) { $key = ''; continue }
        if ($c -eq 2) { if ($origin -eq 'typed') { $key = '' }; break }
        Write-Host 'Cancelled.'; exit 0
    }
    if ($origin -eq 'typed' -and $key) {
        $env:OPENROUTER_API_KEY = $key
        if ($interactive -and $result -ne 'invalid') {
            $go = Read-Host 'Save it in .env for next time? [y/N]'
            if ($go -match '^[Yy]$') { Save-Key $key }
        }
    }
}

# -- Recap ---------------------------------------------------------------------

function Get-Or([string] $Name, [string] $Default) {
    $v = Get-EnvValue $Name
    if ($v) { $v } else { $Default }
}

function Show-Recap {
    Write-Host ''
    Say ("Demo: " + (Get-DemoLabel $script:Mode $script:Source $script:Agent)) 'title'
    Write-Host 'What will start:'
    $rows = @(
        @('Kafka + demo topics', ('localhost:' + (Get-Or 'KAFKA_PORT' '9092'))),
        @('Kafka SQL Explorer + MCP', ('http://localhost:' + (Get-Or 'EXPLORER_PORT' '8080'))),
        @('Schema Registry', ('http://localhost:' + (Get-Or 'SCHEMA_REGISTRY_PORT' '8085'))),
        @('PostgreSQL + TimesFM + live traffic', ((Get-Or 'TIMESFM_CPUS' '4.0') + ' CPUs / ' + (Get-Or 'TIMESFM_MEM_LIMIT' '8g') + ' for TimesFM'))
    )
    if ($script:Agent -eq 'yes' -or $script:Mode -ne 'forecast') {
        $rows += , @('Kex-anHarness agent', ('http://localhost:' + (Get-Or 'KEX_AGENT_PORT' '8081')))
    }
    if ($script:Mode -eq 'full') {
        $rows += , @('SpectraLLM', ('UI :' + (Get-Or 'SPECTRA_UI_PORT' '8088') + ', API :' + (Get-Or 'SPECTRA_API_PORT' '8089') + ' - ~4.8 GB of weights, ~16 GB of RAM for the 7B model'))
    }
    foreach ($row in $rows) { Write-Host ("  {0,-36} {1}" -f $row[0], $row[1]) }
    switch ($script:Source) {
        'build' { Write-Host '  First start: Maven build of the Explorer and the TimesFM checkpoint download.' }
        'prebuilt' { Write-Host '  Pulls the published images; nothing is built.' }
        'offline' { Write-Host '  No download: the images must already be on this machine.' }
    }
}

function Test-ModelInvolved {
    $script:Mode -ne 'forecast' -or ($script:Agent -eq 'yes' -and $script:Source -ne 'offline')
}

function Get-TokenNames {
    $names = @('EXPLORER_MCP_AUTH_TOKEN')
    if ($script:Agent -eq 'yes' -or $script:Mode -ne 'forecast') { $names += 'KEX_AGENT_API_KEY' }
    if ($script:Mode -eq 'full') { $names += 'SPECTRA_API_KEY' }
    if (Test-ModelInvolved) { $names += 'OPENROUTER_API_KEY' }
    , $names
}

# -- A demo that is already running -----------------------------------------------

# The running services, comma separated; nothing when there is no daemon or no demo.
function Get-RunningServices {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { return '' }
    $name = Get-EnvValue 'COMPOSE_PROJECT_NAME'
    if (-not $name) { $name = Read-Kv '.env' 'COMPOSE_PROJECT_NAME' }
    if (-not $name) { $name = 'kafka-explorer-demo' }
    try {
        $lines = & docker ps --filter "label=com.docker.compose.project=$name" --format '{{.Label "com.docker.compose.service"}}' 2>$null
        if ($LASTEXITCODE -ne 0 -or -not $lines) { return '' }
        (@($lines) | Sort-Object) -join ', '
    } catch { '' }
}

# -- Main ----------------------------------------------------------------------

$verb = 'up'
$allTokens = @('KEX_AGENT_API_KEY', 'EXPLORER_MCP_AUTH_TOKEN', 'SPECTRA_API_KEY', 'OPENROUTER_API_KEY')
$running = Get-RunningServices
if ($Preset) {
    Set-FromPreset $Preset
    if ($running) { Say "A demo is already running ($running): start will reconcile it with these options." 'warn' }
} else {
    $choice = 0
    if ($running) {
        $r = Ask "A demo is already running ($running). What do you want to do?" 1 @(
            'Show the services (ps)',
            'Follow the logs',
            'Stop it (keeps the data)',
            'Reset it (stops and deletes the data)',
            'Start it again with other options',
            'Something else (the full menu)')
        # The index of the same action in the full menu below.
        if ($r -le 5) { $choice = @(4, 5, 2, 3, 1)[$r - 1] }
    }
    if ($choice -eq 0) {
        $choice = Ask 'What do you want to do?' 1 @(
            'Start the demo',
            'Stop it (keeps the data)',
            'Reset it (stops and deletes the data)',
            'Show the services (ps)',
            'Follow the logs',
            'Show the credentials',
            'Renew the agent and MCP tokens')
    }
    $verb = @('up', 'down', 'reset', 'ps', 'logs', 'creds', 'renew')[$choice - 1]
    if ($verb -eq 'creds') {
        Show-Tokens $false $allTokens
        $go = Read-Host 'Reveal them? [y/N]'
        if ($go -match '^[Yy]$') { Show-Tokens $true $allTokens }
        exit 0
    }
    if ($verb -eq 'renew') { Renew-Tokens; exit 0 }
    # down, reset, ps and logs name the same stack the demo was started with.
    Choose-Demo
}

$service = ''
if ($verb -eq 'logs' -and -not $Preset) { $service = Read-Host 'Service name (empty = all)' }

# A hashtable, not an array: splatting an array hands '-Forecast' over as a positional string,
# where a hashtable binds it as the switch it names.
$switchNames = @(Get-Switches)
$parameters = @{}
foreach ($name in $switchNames) { $parameters[$name] = $true }
if ($verb -ne 'up') { $parameters['Command'] = $verb }
if ($service) { $parameters['Services'] = @($service) }

$shown = @($switchNames | ForEach-Object { "-$_" })
if ($verb -ne 'up') { $shown += $verb }
if ($service) { $shown += $service }

$tokenNames = @()
if ($verb -eq 'up') {
    Show-Recap
    if (Test-ModelInvolved) { Confirm-Key }
    $tokenNames = Get-TokenNames
    Show-Tokens $false $tokenNames
}

Write-Host ''
Write-Host ("Command: .\demo.ps1 " + ($shown -join ' '))
if ($DryRun) { Write-Host '(dry run: nothing started)'; exit 0 }

if (-not $Yes) {
    while ($true) {
        $prompt = if ($verb -eq 'up') { 'Run it? [Y/n, r = reveal the tokens, c = copy the agent token]' } else { 'Run it? [Y/n]' }
        $go = Read-Host $prompt
        if (-not $go -or $go -match '^[Yy]$') { break }
        if ($go -match '^[Nn]$') { Write-Host 'Cancelled.'; exit 0 }
        if ($go -match '^[Rr]$' -and $verb -eq 'up') { Show-Tokens $true $tokenNames; continue }
        if ($go -match '^[Cc]$' -and $verb -eq 'up') {
            Set-Clipboard -Value (Get-Value 'KEX_AGENT_API_KEY')
            Say 'Agent token copied.' 'ok'
            continue
        }
        Say 'Answer y to run, n to cancel, r to reveal the tokens, c to copy the agent token.' 'warn'
    }
}

if ($verb -eq 'up') { Save-Demo }
& (Join-Path $PSScriptRoot 'demo.ps1') @parameters
exit $LASTEXITCODE
