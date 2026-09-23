[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$EvidenceDir)
$ErrorActionPreference = 'Stop'
$taskRepo = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$taskEvidence = [IO.Path]::GetFullPath($EvidenceDir)
if (-not $taskEvidence.StartsWith((Join-Path $taskRepo '.run') + '\',[StringComparison]::OrdinalIgnoreCase)) {
    throw 'Evidence must be in source/.run'
}
New-Item -ItemType Directory -Path $taskEvidence -Force | Out-Null
$taskAdmin = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $taskAdmin) { throw 'Run this approved preparation step with administrator elevation. Never bypass UAC.' }
$taskResult = [ordered]@{ requestedFeature = 'Containers-DisposableClientVM'; automaticRestart = $false; started = (Get-Date).ToString('o'); completed = $false }
try {
    $taskResult.before = (Get-WindowsOptionalFeature -Online -FeatureName Containers-DisposableClientVM).State.ToString()
    $taskEnabled = Enable-WindowsOptionalFeature -Online -FeatureName Containers-DisposableClientVM -All -NoRestart -LogPath (Join-Path $taskEvidence 'enable-sandbox-dism.log')
    $taskResult.restartNeeded = [bool]$taskEnabled.RestartNeeded
    $taskResult.after = (Get-WindowsOptionalFeature -Online -FeatureName Containers-DisposableClientVM).State.ToString()
    $taskResult.completed = $true
} catch {
    $taskResult.error = $_.Exception.Message
} finally {
    $taskResult.finished = (Get-Date).ToString('o')
    $taskResult | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $taskEvidence 'sandbox-enable-result.json') -Encoding UTF8
}
if (-not $taskResult.completed) { exit 1 }
