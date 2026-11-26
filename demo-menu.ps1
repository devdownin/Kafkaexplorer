# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
#
# Picks the demo options for you and runs demo.ps1 with the matching switches. Twin of
# demo-menu.sh: same questions, same coherent combinations.
#
#   .\demo-menu.cmd            # ask, show the command, confirm, run
#   .\demo-menu.cmd -DryRun    # ask, print the command, run nothing
#
# Kept ASCII on purpose: Windows PowerShell 5.1 reads a BOM-less script as the ANSI code page.
[CmdletBinding()]
param([switch] $DryRun)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

# Returns the 1-based index of the chosen option; Enter takes the default.
function Ask([string] $Question, [int] $Default, [string[]] $Options) {
    Write-Host ''
    Write-Host $Question
    for ($i = 0; $i -lt $Options.Count; $i++) {
        $mark = if (($i + 1) -eq $Default) { '  [default]' } else { '' }
        Write-Host ("  {0}) {1}{2}" -f ($i + 1), $Options[$i], $mark)
    }
    while ($true) {
        $answer = Read-Host "Choice [$Default]"
        if ([string]::IsNullOrWhiteSpace($answer)) { return $Default }
        $n = 0
        if ([int]::TryParse($answer, [ref] $n) -and $n -ge 1 -and $n -le $Options.Count) { return $n }
        Write-Host "Enter a number between 1 and $($Options.Count)."
    }
}

$switches = @()   # switch names, as demo.ps1 spells them
$verb = ''

$action = Ask 'What do you want to do?' 1 @(
    'Start the demo',
    'Stop it (keeps the data)',
    'Reset it (stops and deletes the data)',
    'Show the services (ps)',
    'Follow the logs')
$verb = @('', 'down', 'reset', 'ps', 'logs')[$action - 1]

# Down, reset, ps and logs must name the same stack the demo was started with.
$mode = Ask 'Which demo?' 1 @(
    'Full suite: Kafka, Avro, MCP, forecasts, SpectraLLM, agent (needs OPENROUTER_API_KEY, ~4.8 GB of weights)',
    'Full suite without SpectraLLM (needs OPENROUTER_API_KEY)',
    'Forecast demo: live metrics and forecasts, the agent included')
if ($mode -eq 2) { $switches += '-NoSpectra' }
if ($mode -eq 3) { $switches += '-Forecast' }

$agentOn = $mode -ne 3
if ($mode -eq 3) {
    $source = Ask 'Where do the images come from?' 1 @(
        'Built from this checkout (Maven + the TimesFM download on the first run)',
        'Published images, nothing built (needs a pull)',
        'Offline: published images with the models baked in, no download, no agent')
    if ($source -eq 2) { $switches += '-Prebuilt' }
    if ($source -eq 3) { $switches += '-Offline' }
    $agentOn = $false
    if ($source -ne 3) {
        $agent = Ask 'Start the Kex-anHarness agent?' 1 @(
            'Yes (it starts degraded without OPENROUTER_API_KEY)',
            'No')
        if ($agent -eq 1) { $agentOn = $true } else { $switches += '-NoAgent' }
    }
}

$service = ''
if ($verb -eq 'logs') { $service = Read-Host 'Service name (empty = all)' }

# The key matters when a model is involved: the full suite, or the forecast demo with the agent.
if ($action -eq 1 -and $agentOn -and [string]::IsNullOrEmpty($env:OPENROUTER_API_KEY)) {
    Write-Host ''
    $secure = Read-Host 'OPENROUTER_API_KEY (Enter to skip: the agent and Process Mining start degraded)' -AsSecureString
    $plain = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
    if ($plain) { $env:OPENROUTER_API_KEY = $plain }
}

# A hashtable, not an array: splatting an array hands '-Forecast' over as a positional string,
# where a hashtable binds it as the switch it names.
$parameters = @{}
foreach ($name in $switches) { $parameters[$name.TrimStart('-')] = $true }
if ($verb) { $parameters['Command'] = $verb }
if ($service) { $parameters['Services'] = @($service) }

$shown = @($switches)
if ($verb) { $shown += $verb }
if ($service) { $shown += $service }
Write-Host ''
Write-Host ("Command: .\demo.ps1 " + ($shown -join ' '))
if ($DryRun) { Write-Host '(dry run: nothing started)'; exit 0 }
$go = Read-Host 'Run it? [Y/n]'
if ($go -and $go -notmatch '^[Yy]') { Write-Host 'Cancelled.'; exit 0 }
& (Join-Path $PSScriptRoot 'demo.ps1') @parameters
exit $LASTEXITCODE
