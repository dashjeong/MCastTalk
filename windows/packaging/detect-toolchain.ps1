[CmdletBinding()]
param(
    [switch]$AsJson,
    [ValidateSet("exe", "app-image")][string]$Type = "exe"
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "toolchain.ps1")
$toolchain = Get-MCastTalkPackagingToolchain -Type $Type
if ($AsJson) {
    $toolchain | ConvertTo-Json -Depth 5
} else {
    $toolchain | Format-List
}
if (-not $toolchain.ready) {
    exit 2
}
