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
#   .\demo-menu.cmd -Preset full -Model openai/gpt-4o-mini   # one run on another OpenRouter model
#   .\demo-menu.cmd -Preset full -Llm local                  # the Explorer AND the agent on the kex-llm image's model
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
# Which model answers for the agent and the Explorer: OpenRouter (hosted, a key, prompts leave the host) or
# the local one the published kex-llm image carries (Gemma 4 E4B on llama.cpp: no key, nothing leaves, a
# ~5.5 GB image). Asked when a model is involved and nothing names one, the last choice first; a preset
# takes OpenRouter; DEMO_LLM in the environment or a .env is shown and not asked about. The local choice
# skips everything below: there is no key to check and no OpenRouter model to pick.
#
# The OpenRouter model: asked when a model is involved and nothing already names one, with the last
# choice first. Precedence is demo.ps1's own (the environment, .env, .forecast-stack\.env), and -Model
# beats them for one run. Before the start the menu checks the model against the public catalogue
# (does it declare tool calling, which the agent needs) and, if you agree, with one tiny request.
# DEMO_MENU_OPENROUTER_URL points those checks elsewhere (tests); it does not reroute the stack.
#
# Kept ASCII on purpose: Windows PowerShell 5.1 reads a BOM-less script as the ANSI code page.
[CmdletBinding()]
param(
    [switch] $DryRun,
    [switch] $Yes,
    [switch] $NoColor,
    [string] $Preset = '',
    [switch] $Presets,
    [string] $Model = '',
    [ValidateSet('', 'openrouter', 'local')] [string] $Llm = ''
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

# UTF-8 without a BOM, LF line endings: these files are also read by demo.sh and by Compose.
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$StateDir = '.forecast-stack'
$LastFile = Join-Path $StateDir 'last-demo'
$StateEnv = Join-Path $StateDir '.env'

# One definition of a model id, shared by the switch, the typed answer and the remembered one: it ends
# up in a JSON body and a state file, so nothing outside this alphabet gets through.
function Test-ModelId([string] $Id) { $Id.Length -le 100 -and $Id -cmatch '^[A-Za-z0-9][A-Za-z0-9._:/+-]*$' }
$DefaultModel = 'openrouter/free'
$OpenRouterBase = if ($env:DEMO_MENU_OPENROUTER_URL) { $env:DEMO_MENU_OPENROUTER_URL.TrimEnd('/') } else { 'https://openrouter.ai/api/v1' }
if ($Model -and -not (Test-ModelId $Model)) {
    [Console]::Error.WriteLine("-Model expects an OpenRouter model id such as openai/gpt-4o-mini, got '$Model'.")
    exit 2
}
$script:LlmId = 'openrouter'  # openrouter | local: what answers for the agent and the Explorer, once a model is involved
$script:LlmOrigin = ''        # -Llm | environment | .env | generated | chosen | last | default
$script:ModelId = ''        # the OpenRouter model, once a model is involved and chosen
$script:ModelOrigin = ''    # -Model | environment | .env | generated | chosen | default

$UseColor = (-not $NoColor) -and (-not $env:NO_COLOR) -and [Environment]::UserInteractive -and (-not [Console]::IsOutputRedirected)
# Kind: title (bold-ish white), ok (green), warn (yellow); anything else is plain.
function Say([string] $Text, [string] $Kind = '') {
    $color = switch ($Kind) { 'title' { 'White' } 'ok' { 'Green' } 'warn' { 'Yellow' } default { '' } }
    if ($UseColor -and $color) { Write-Host $Text -ForegroundColor $color } else { Write-Host $Text }
}

function Show-Presets {
    @(
        'full                   Full suite: Kafka, Avro, MCP, forecasts, SpectraLLM, agent (published images)',
        'full-no-spectra        Full suite without SpectraLLM (published images)',
        'forecast               Forecast demo with the agent (published images)',
        'forecast-no-agent      Forecast demo without the agent (published images)',
        'forecast-offline       Forecast demo, models baked in, no download, no agent',
        'full-build             Full suite, built from this checkout',
        'full-no-spectra-build  Full suite without SpectraLLM, built from this checkout',
        'forecast-build         Forecast demo with the agent, built from this checkout'
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
$script:Source = 'prebuilt'
$script:Agent = 'yes'

function Set-FromPreset([string] $Name) {
    $script:Source = 'prebuilt'; $script:Agent = 'yes'
    switch ($Name) {
        'full' { $script:Mode = 'full' }
        'full-no-spectra' { $script:Mode = 'nospectra' }
        { $_ -in @('forecast', 'forecast-prebuilt') } { $script:Mode = 'forecast' }
        'forecast-no-agent' { $script:Mode = 'forecast'; $script:Agent = 'no' }
        'forecast-offline' { $script:Mode = 'forecast'; $script:Source = 'offline'; $script:Agent = 'no' }
        'full-build' { $script:Mode = 'full'; $script:Source = 'build' }
        'full-no-spectra-build' { $script:Mode = 'nospectra'; $script:Source = 'build' }
        'forecast-build' { $script:Mode = 'forecast'; $script:Source = 'build' }
        default {
            Write-Host "Unknown preset '$Name'. Known presets:"
            Show-Presets
            exit 2
        }
    }
}

function Get-DemoLabel([string] $M, [string] $S, [string] $A) {
    $where = switch ($S) { 'build' { 'built here' } 'prebuilt' { 'published images' } 'offline' { 'offline' } }
    if ($M -eq 'full') { return "Full suite with SpectraLLM, $where" }
    if ($M -eq 'nospectra') { return "Full suite without SpectraLLM, $where" }
    $agentText = if ($A -eq 'yes') { 'with the agent' } else { 'without the agent' }
    "Forecast demo, $where, $agentText"
}

# The switches demo.ps1 takes, from the choices above.
function Get-Switches {
    $list = @()
    if ($script:Mode -eq 'nospectra') { $list += 'NoSpectra' }
    if ($script:Mode -eq 'forecast') { $list += 'Forecast' }
    if ($script:Source -eq 'prebuilt') { $list += 'Prebuilt' }
    if ($script:Source -eq 'offline') { $list += 'Offline' }
    if ($script:Mode -eq 'forecast' -and $script:Source -ne 'offline' -and $script:Agent -ne 'yes') { $list += 'NoAgent' }
    # Only where a model is involved: a demo with no agent and no Explorer LLM half has nothing to point.
    if ((Test-ModelInvolved) -and $script:LlmId -eq 'local') { $list += 'LocalLlm' }
    # Not `, $list`: the comma wraps the array in a one-element array, so the caller's @(...) held ONE
    # element and every switch after the first printed, and was handed to demo.ps1, without its dash
    # ("-Forecast Prebuilt"). Left to unroll, @() gets one element per switch, and none for none.
    $list
}

function Ask-Demo {
    $m = Ask 'Which demo?' 1 @(
        'Full suite: Kafka, Avro, MCP, forecasts, SpectraLLM, agent (a model: an OpenRouter key or the local kex-llm; ~4.8 GB of SpectraLLM weights)',
        'Full suite without SpectraLLM (a model: an OpenRouter key or the local kex-llm)',
        'Forecast demo: live metrics and forecasts, the agent included')
    $script:Agent = 'yes'
    $script:Mode = @('full', 'nospectra', 'forecast')[$m - 1]
    # Published images first: nothing is built, so no Maven, no Docker Hub base image, no TLS
    # surprise behind a proxy that re-signs certificates.
    $options = @(
        'Published images, nothing built (recommended)',
        'Built from this checkout (Maven + base images from Docker Hub + the TimesFM download)')
    if ($script:Mode -eq 'forecast') { $options += 'Offline: published images with the models baked in, no download, no agent' }
    $s = Ask 'Where do the images come from?' 1 $options
    $script:Source = @('prebuilt', 'build', 'offline')[$s - 1]
    if ($script:Mode -ne 'forecast') { return }
    if ($script:Source -eq 'offline') { $script:Agent = 'no'; return }
    $a = Ask 'Start the Kex-anHarness agent?' 1 @(
        'Yes (a model: an OpenRouter key, or the local kex-llm; degraded with neither)',
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

# The model of the last interactive choice, empty when there is none or it is not a model id.
function Get-SavedModel {
    $m = Read-Kv $LastFile 'model'
    if ($m -and (Test-ModelId $m)) { $m } else { '' }
}

# The model is rewritten only by an interactive choice: a one-off -Model or a script's preset must not
# replace what the person picked last, and the state of a run that named no model keeps the old one.
# The last interactive choice of what answers: openrouter or local, empty when there is none.
function Get-SavedLlm {
    $v = Read-Kv $LastFile 'llm'
    if ($v -eq 'openrouter' -or $v -eq 'local') { $v } else { '' }
}

function Save-Demo {
    $m = Get-SavedModel
    if ($script:ModelOrigin -eq 'chosen') { $m = $script:ModelId }
    $l = Get-SavedLlm
    if ($script:LlmOrigin -eq 'chosen') { $l = $script:LlmId }
    New-Item -ItemType Directory -Force -Path $StateDir | Out-Null
    $text = "mode=$($script:Mode)`nsource=$($script:Source)`nagent=$($script:Agent)`n"
    if ($m) { $text += "model=$m`n" }
    if ($l) { $text += "llm=$l`n" }
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
        Invoke-WebRequest -Uri "$OpenRouterBase/auth/key" -Headers @{ Authorization = "Bearer $Key" } `
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
    if (Test-ModelInvolved) { Show-LlmRecap }
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
    if ((Test-ModelInvolved) -and $script:LlmId -ne 'local') { $names += 'OPENROUTER_API_KEY' }
    , $names
}

# -- What answers: OpenRouter or the local model ---------------------------------

# Where demo.ps1 would already read DEMO_LLM from, in its precedence. $null when none names a valid one.
function Get-NamedLlm {
    $v = Get-EnvValue 'DEMO_LLM'
    if ($v -eq 'openrouter' -or $v -eq 'local') { return @{ Value = $v; Origin = 'environment' } }
    $v = Read-Kv '.env' 'DEMO_LLM'
    if ($v -eq 'openrouter' -or $v -eq 'local') { return @{ Value = $v; Origin = '.env' } }
    $v = Read-Kv $StateEnv 'DEMO_LLM'
    if ($v -eq 'openrouter' -or $v -eq 'local') { return @{ Value = $v; Origin = 'generated' } }
    $null
}

function Get-LlmLabel([string] $Id) {
    if ($Id -eq 'local') { 'kex-llm: the local Gemma 4 E4B, no key, nothing leaves this machine (a ~5.5 GB image, slower on a CPU)' }
    else { 'OpenRouter: a hosted model, needs a key, prompts and tool results leave this machine' }
}

# Sets LlmId and LlmOrigin. Precedence is -Llm, then what the environment or a .env names, then a
# question to a person (the last choice first), then the default. A preset takes the default, so a
# script gives the same demo whatever was picked last. A verb that only names the stack (down, ps,
# logs) is not asked: it takes the last choice, since the files compose layers depend on it.
function Choose-Llm([string] $Verb) {
    if ($Llm) { $script:LlmId = $Llm; $script:LlmOrigin = '-Llm'; return }
    $named = Get-NamedLlm
    if ($named) { $script:LlmId = $named.Value; $script:LlmOrigin = $named.Origin; return }
    $last = Get-SavedLlm
    if ($Verb -ne 'up') {
        $script:LlmId = if ($last) { $last } else { 'openrouter' }; $script:LlmOrigin = 'last'
        return
    }
    $script:LlmId = 'openrouter'; $script:LlmOrigin = 'default'
    if ($Preset) { return }
    $first = if ($last) { $last } else { 'openrouter' }
    $values = @($first, $(if ($first -eq 'openrouter') { 'local' } else { 'openrouter' }))
    $options = @()
    foreach ($v in $values) {
        $label = Get-LlmLabel $v
        if ($last -and $v -eq $last) { $label += ', same as last time' }
        $options += $label
    }
    $c = Ask 'Which model answers for the agent and the Explorer?' 1 $options
    $script:LlmId = $values[$c - 1]; $script:LlmOrigin = 'chosen'
}

function Show-LlmRecap {
    if ($script:LlmId -ne 'local') { Show-ModelRecap; return }
    $where = switch ($script:LlmOrigin) {
        '-Llm' { '-Llm, this run only' }
        'environment' { 'DEMO_LLM, from the environment' }
        '.env' { 'DEMO_LLM, from .env' }
        'generated' { 'DEMO_LLM, from .forecast-stack\.env' }
        'chosen' { 'your choice' }
        'last' { 'the last choice' }
        default { 'default' }
    }
    Write-Host ("  {0,-36} {1}" -f 'LLM (agent and Explorer)', "kex-llm, Gemma 4 E4B on llama.cpp, local ($where)")
    Write-Host ("  {0,-36} {1}" -f '', 'a ~5.5 GB image, pulled once; no OpenRouter key; one slot serves both apps')
}

# -- The model -----------------------------------------------------------------

function Get-ModelLabel([string] $Id) {
    switch ($Id) {
        $DefaultModel { "$Id (router over the free models: no credits needed)" }
        'openai/gpt-4o-mini' { "$Id (paid: reliable tool calling)" }
        default { $Id }
    }
}

# Where demo.ps1 would already read a model from, with the same precedence: environment, .env, then
# .forecast-stack\.env. $null when none of them names one.
function Get-NamedModel {
    $v = Get-EnvValue 'OPENROUTER_MODEL'
    if ($v) { return @{ Value = $v; Origin = 'environment' } }
    $v = Read-Kv '.env' 'OPENROUTER_MODEL'
    if ($v) { return @{ Value = $v; Origin = '.env' } }
    $v = Read-Kv $StateEnv 'OPENROUTER_MODEL'
    if ($v) { return @{ Value = $v; Origin = 'generated' } }
    $null
}

# Sets ModelId and ModelOrigin. Asked only when a person is answering and nothing names a model; a
# preset takes the default, so a script gives the same demo whatever was picked last on this machine.
# -Again skips every shortcut: the person refused the model those gave.
function Choose-Model([switch] $Again) {
    if (-not $Again) {
        if ($Model) { $script:ModelId = $Model; $script:ModelOrigin = '--model'; return }
        $named = Get-NamedModel
        if ($named) { $script:ModelId = $named.Value; $script:ModelOrigin = $named.Origin; return }
        $script:ModelId = $DefaultModel; $script:ModelOrigin = 'default'
        if ($Preset) { return }
    }
    $last = Get-SavedModel
    $first = if ($last) { $last } else { $DefaultModel }
    $values = @($first)
    if ($first -ne $DefaultModel) { $values += $DefaultModel }
    if ($first -ne 'openai/gpt-4o-mini') { $values += 'openai/gpt-4o-mini' }
    $options = @()
    foreach ($v in $values) {
        $label = Get-ModelLabel $v
        if ($last -and $v -eq $last) { $label += ', same as last time' }
        $options += $label
    }
    $options += 'Another model (type its OpenRouter id)'
    while ($true) {
        $c = Ask 'Which OpenRouter model?' 1 $options
        if ($c -le $values.Count) { $script:ModelId = $values[$c - 1]; break }
        $typed = Read-Host 'Model id (provider/model, Enter to go back)'
        if (-not $typed) { continue }
        if (Test-ModelId $typed) { $script:ModelId = $typed; break }
        Say "'$typed' is not an OpenRouter model id (letters, digits and . _ : / + - only)." 'warn'
    }
    $script:ModelOrigin = 'chosen'
}

function Show-ModelRecap {
    $where = switch ($script:ModelOrigin) {
        '--model' { '-Model, this run only' }
        'environment' { 'from the environment' }
        '.env' { 'from .env' }
        'generated' { 'from .forecast-stack\.env' }
        'chosen' { 'your choice' }
        default { 'default' }
    }
    Write-Host ("  {0,-36} {1}" -f 'OpenRouter model', "$($script:ModelId) ($where)")
}

# Does the public catalogue say this model takes tool calls? tools | no-tools | missing | unknown.
# A measure that could not be taken is unknown, never "no": offline, nothing is claimed.
function Get-ModelTools([string] $Id) {
    $rows = $null
    try {
        $response = Invoke-WebRequest -Uri "$OpenRouterBase/models" -UseBasicParsing -TimeoutSec 8
        $rows = ($response.Content | ConvertFrom-Json).data
    } catch { return 'unknown' }
    if ($null -eq $rows) { return 'unknown' }
    $row = @($rows | Where-Object { $_.id -eq $Id -or $_.canonical_slug -eq $Id }) | Select-Object -First 1
    if (-not $row) { return 'missing' }
    if (@($row.supported_parameters) -contains 'tools') { 'tools' } else { 'no-tools' }
}

# One tiny completion. Result: ok | invalid-key | no-credits | unavailable | rate-limited | unknown;
# Detail: the provider's own words, stripped of anything unprintable, when it gave some.
function Test-ModelAnswer([string] $Key, [string] $Id) {
    $body = '{"model":"' + $Id + '","max_tokens":8,"messages":[{"role":"user","content":"ok"}]}'
    try {
        Invoke-WebRequest -Uri "$OpenRouterBase/chat/completions" -Method Post -Headers @{ Authorization = "Bearer $Key" } `
            -ContentType 'application/json' -Body $body -UseBasicParsing -TimeoutSec 30 | Out-Null
        return [pscustomobject]@{ Result = 'ok'; Detail = '' }
    } catch {
        $code = 0
        if ($_.Exception.Response) { $code = [int] $_.Exception.Response.StatusCode }
        $detail = ''
        try {
            $raw = $_.ErrorDetails.Message
            if (-not $raw -and $_.Exception.Response) {
                $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
                $raw = $reader.ReadToEnd()
            }
            if ($raw) { $detail = [string] ($raw | ConvertFrom-Json).error.message }
        } catch { $detail = '' }
        $detail = ($detail -replace '[^\x20-\x7E]', '')
        if ($detail.Length -gt 160) { $detail = $detail.Substring(0, 160) }
        $result = switch ($code) {
            { $_ -in 401, 403 } { 'invalid-key' }
            402 { 'no-credits' }
            404 { 'unavailable' }
            429 { 'rate-limited' }
            default { 'unknown' }
        }
        [pscustomobject]@{ Result = $result; Detail = $detail }
    }
}

# Checks the chosen model before the start. Findings warn; they never block, except that an
# interactive run is offered the way out. Returns $false when the person asked to choose another model.
function Confirm-Model {
    $id = $script:ModelId
    $bad = $false
    switch (Get-ModelTools $id) {
        'tools' { Say "Model checked: $id declares tool calling." 'ok' }
        'no-tools' { Say "$id does not declare tool calling on OpenRouter: the agent could not use its tools with it." 'warn'; $bad = $true }
        'missing' {
            if ($id.StartsWith('openrouter/')) { Write-Host "$id is a router: the model behind it changes, so tool calling is not checked." }
            else { Say "$id is not in the OpenRouter catalogue: check the spelling (provider/model)." 'warn'; $bad = $true }
        }
        default { Write-Host 'Model not checked against the catalogue (no answer from openrouter.ai): continuing.' }
    }
    $interactive = [Environment]::UserInteractive -and -not [Console]::IsInputRedirected -and -not $Preset -and -not $Yes -and -not $DryRun
    # The probe spends a little of the quota, so it is asked, never assumed, and never on a dry run.
    $key = Get-Value 'OPENROUTER_API_KEY'
    if (-not $bad -and $key -and $interactive) {
        $go = Read-Host "Send one tiny request to $id to see that it answers? [y/N]"
        if ($go -match '^[Yy]$') {
            $answer = Test-ModelAnswer $key $id
            switch ($answer.Result) {
                'ok' { Say "$id answered." 'ok' }
                'invalid-key' { Say 'OpenRouter refused the key for this request.' 'warn'; $bad = $true }
                'no-credits' { Say "$id needs credits this account does not have." 'warn'; $bad = $true }
                'unavailable' { Say "$id is not available to this account (404)." 'warn'; $bad = $true }
                'rate-limited' { Say "$id is rate-limited right now (429): free models often are. It may work a moment later." 'warn'; $bad = $true }
                default { Say "$id did not answer clearly: not checked further." 'warn' }
            }
            if ($answer.Detail) { Write-Host "  OpenRouter says: $($answer.Detail)" }
        }
    }
    if (-not $bad -or -not $interactive) { return $true }
    $c = Ask 'What now?' 1 @('Choose another model', 'Continue anyway', 'Cancel')
    if ($c -eq 1) { return $false }
    if ($c -eq 3) { Write-Host 'Cancelled.'; exit 0 }
    $true
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

# demo.ps1 runs in its own PowerShell process, exactly as demo.cmd starts it: switches by name,
# then the command and the service. Splatting into the script would depend on how this host binds
# parameters, and a stray element lands in demo.ps1's -Services ("Only 'logs' takes service names").
$childArguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $PSScriptRoot 'demo.ps1'))
# What answers is settled before the command is built: it adds -LocalLlm, and the compose files a demo
# layers (so what down, ps and logs name) depend on it.
if (Test-ModelInvolved) { Choose-Llm $verb }
elseif ($verb -eq 'up' -and $Llm) { Say "This demo calls no model: -Llm $Llm is ignored." 'warn' }
$switchNames = @(Get-Switches)
foreach ($name in $switchNames) { $childArguments += "-$name" }
if ($verb -ne 'up') { $childArguments += $verb }
if ($service) { $childArguments += $service }

$shown = @($switchNames | ForEach-Object { "-$_" })
if ($verb -ne 'up') { $shown += $verb }
if ($service) { $shown += $service }

$tokenNames = @()
if ($verb -eq 'up') {
    if ((Test-ModelInvolved) -and $script:LlmId -ne 'local') { Choose-Model }
    elseif ($Model) {
        if (Test-ModelInvolved) { Say "This demo runs on the local model, not OpenRouter: -Model $Model is ignored." 'warn' }
        else { Say "This demo calls no model: -Model $Model is ignored." 'warn' }
    }
    Show-Recap
    # Exported for a run that chose or named it on the command line: demo.ps1 reads the environment first,
    # so -Llm openrouter also overrides a DEMO_LLM=local the environment or .env set.
    if ($script:LlmOrigin -eq '-Llm' -or $script:LlmOrigin -eq 'chosen') { $env:DEMO_LLM = $script:LlmId }
    if ((Test-ModelInvolved) -and $script:LlmId -ne 'local') {
        Confirm-Key
        while (-not (Confirm-Model)) {
            Choose-Model -Again
            Show-ModelRecap
        }
        # Exported only for a model this run chose: demo.ps1 reads the environment first, so it also
        # overrides one the environment or .env named when the person took another.
        if ($script:ModelOrigin -eq '--model' -or $script:ModelOrigin -eq 'chosen') { $env:OPENROUTER_MODEL = $script:ModelId }
    }
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
$hostExe = (Get-Process -Id $PID).Path
& $hostExe @childArguments
exit $LASTEXITCODE
