[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$Installer,
    [Parameter(Mandatory=$true)][string]$OutputDir,
    [ValidateRange(4096,16384)][int]$MemoryInMB = 10240,
    [Parameter(Mandatory=$true)][string]$JavaLauncher,
    [Parameter(Mandatory=$true)][string]$NodeExecutable,
    [Parameter(Mandatory=$true)][string]$PlaywrightModules
)
$ErrorActionPreference = 'Stop'
$taskRepo = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$taskInstaller = (Resolve-Path -LiteralPath $Installer).Path
$taskJavaLauncher = (Resolve-Path -LiteralPath $JavaLauncher).Path
$taskNode = (Resolve-Path -LiteralPath $NodeExecutable).Path
$taskModules = (Resolve-Path -LiteralPath $PlaywrightModules).Path
$taskDestination = [IO.Path]::GetFullPath($OutputDir)
$taskAllowed = Join-Path $taskRepo '.run'
if (-not $taskDestination.StartsWith($taskAllowed + '\',[StringComparison]::OrdinalIgnoreCase)) {
    throw 'Evidence must be in a dedicated source/.run subdirectory'
}
if (Test-Path -LiteralPath $taskDestination) { throw 'Refusing to overwrite a prepared acceptance run' }
$taskKit = Join-Path $taskDestination 'kit'
$taskResults = Join-Path $taskDestination 'results'
New-Item -ItemType Directory -Path $taskKit,$taskResults | Out-Null
foreach ($taskName in @('run-networkless-sandbox.ps1','networkless-assertions.ps1','networkless-browser.cjs','offline_engine_probe.py','verify-offline-payload.py','get-networkless-status.ps1')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $taskName) -Destination $taskKit
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../verify_native_dependencies.py') -Destination $taskKit
# Test-only tools, never copied into the installed application or committed.
Copy-Item -LiteralPath $taskJavaLauncher -Destination (Join-Path $taskKit 'java.exe')
Copy-Item -LiteralPath (Join-Path (Split-Path (Split-Path $taskJavaLauncher -Parent) -Parent) 'release') -Destination (Join-Path $taskKit 'java-release')
(Get-FileHash -LiteralPath $taskJavaLauncher -Algorithm SHA256).Hash | Set-Content -LiteralPath (Join-Path $taskKit 'java-launcher.sha256') -Encoding ASCII
Copy-Item -LiteralPath $taskNode -Destination (Join-Path $taskKit 'node.exe')
$taskBrowserModules = Join-Path $taskKit 'node_modules'
New-Item -ItemType Directory -Path $taskBrowserModules | Out-Null
foreach ($taskName in @('playwright','playwright-core')) {
    Copy-Item -LiteralPath (Join-Path $taskModules $taskName) -Destination $taskBrowserModules -Recurse
}
Copy-Item -LiteralPath (Join-Path $taskRepo 'windows/host/tests/inference-browser.cjs') -Destination $taskKit
$taskFixtureDir = Join-Path $taskKit 'fixtures/app/mcasttalk/windows/host'
New-Item -ItemType Directory -Path $taskFixtureDir -Force | Out-Null
foreach ($taskName in @('AccountFixtureMain.class','BundleFixtureMain.class')) {
    Copy-Item -LiteralPath (Join-Path $taskRepo ('windows/host/build/classes/kotlin/test/app/mcasttalk/windows/host/' + $taskName)) -Destination $taskFixtureDir
}
(Get-FileHash -LiteralPath $taskInstaller -Algorithm SHA256).Hash | Set-Content -LiteralPath (Join-Path $taskKit 'installer.sha256') -Encoding ASCII
$taskInputXml = [Security.SecurityElement]::Escape((Split-Path -Parent $taskInstaller))
$taskKitXml = [Security.SecurityElement]::Escape($taskKit)
$taskResultsXml = [Security.SecurityElement]::Escape($taskResults)
$taskXml = @"
<Configuration>
  <Networking>Disable</Networking>
  <vGPU>Disable</vGPU>
  <MemoryInMB>$MemoryInMB</MemoryInMB>
  <AudioInput>Disable</AudioInput>
  <VideoInput>Disable</VideoInput>
  <ClipboardRedirection>Disable</ClipboardRedirection>
  <PrinterRedirection>Disable</PrinterRedirection>
  <MappedFolders>
    <MappedFolder><HostFolder>$taskInputXml</HostFolder><SandboxFolder>C:\MCastTalkInput</SandboxFolder><ReadOnly>true</ReadOnly></MappedFolder>
    <MappedFolder><HostFolder>$taskKitXml</HostFolder><SandboxFolder>C:\MCastTalkTestKit</SandboxFolder><ReadOnly>true</ReadOnly></MappedFolder>
    <MappedFolder><HostFolder>$taskResultsXml</HostFolder><SandboxFolder>C:\MCastTalkResults</SandboxFolder><ReadOnly>false</ReadOnly></MappedFolder>
  </MappedFolders>
  <LogonCommand><Command>powershell.exe -NoProfile -ExecutionPolicy Bypass -File C:\MCastTalkTestKit\run-networkless-sandbox.ps1</Command></LogonCommand>
</Configuration>
"@
$taskConfig = Join-Path $taskDestination 'MCastTalk-network-disabled.wsb'
[IO.File]::WriteAllText($taskConfig,$taskXml,[Text.UTF8Encoding]::new($false))
Write-Output $taskConfig
# This only prepares files. It does not enable Windows features, start a VM or reboot.
