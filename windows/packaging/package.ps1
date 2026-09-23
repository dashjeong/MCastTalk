[CmdletBinding()]
param(
    [ValidateSet("exe", "app-image")][string]$Type = "exe",
    [ValidatePattern("^[0-9]+(\.[0-9]+){1,3}$")][string]$Version = "0.1.0",
    [string]$OutputDir,
    [switch]$SkipGradle
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
. (Join-Path $PSScriptRoot "toolchain.ps1")
$toolchain = Get-MCastTalkPackagingToolchain -Type $Type -ProjectRoot $projectRoot
if (-not $toolchain.ready) {
    $toolchain | ConvertTo-Json -Depth 5
    throw "Packaging prerequisites are missing. No tools are downloaded or installed by this script."
}

# Gradle must use the same pinned JDK that supplies jpackage.  This is
# particularly important when a machine-wide JAVA_HOME changes after reboot.
$env:JAVA_HOME = $toolchain.javaHome
$env:PATH = "$(Join-Path $toolchain.javaHome 'bin');$env:PATH"

if (-not $OutputDir) {
    $OutputDir = Join-Path $projectRoot "build\windows-packaging\$Type"
}
$OutputDir = [System.IO.Path]::GetFullPath($OutputDir)
New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

$hostProject = Join-Path $projectRoot "windows\host"
$installRoot = Join-Path $hostProject "build\install\host"
if (-not $SkipGradle) {
    Push-Location $projectRoot
    try {
        & $toolchain.gradleWrapper --offline ":windows:host:installDist"
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle installDist failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}
if (-not (Test-Path -LiteralPath (Join-Path $installRoot "lib\host.jar") -PathType Leaf)) {
    throw "Host distribution is missing: $installRoot. Run without -SkipGradle."
}

# jpackage uses WiX v3's candle/light. WiX v4's wix.exe is reported by the
# detector for diagnostics, but does not silently substitute for those tools.
if ($toolchain.wix.candle -and $toolchain.wix.light) {
    $wixDirectory = Split-Path -Parent $toolchain.wix.candle
    $env:PATH = "$wixDirectory;$env:PATH"
}

$workName = "work-$Type-" + [guid]::NewGuid().ToString("N")
$workDir = Join-Path $projectRoot "build\windows-packaging\$workName"
New-Item -ItemType Directory -Path $workDir -Force | Out-Null

$jpackageArguments = @(
    "--type", $Type,
    "--name", "MCastTalk",
    "--app-version", $Version,
    "--input", (Join-Path $installRoot "lib"),
    "--main-jar", "host.jar",
    "--main-class", "app.mcasttalk.windows.host.MainKt",
    "--dest", $OutputDir,
    "--temp", $workDir,
    "--vendor", "MCastTalk",
    "--description", "Offline-first multilingual room host",
    "--copyright", "MCastTalk contributors",
    "--java-options", "-Dfile.encoding=UTF-8"
)
if ($Type -eq "exe") {
    $jpackageArguments += @(
        "--win-per-user-install",
        "--win-dir-chooser",
        "--win-menu",
        "--win-menu-group", "MCastTalk",
        "--win-shortcut",
        "--win-upgrade-uuid", "9f1e16a8-5fb3-4e01-90c8-37db6cb4f2aa"
    )
}

& $toolchain.jpackage @jpackageArguments
if ($LASTEXITCODE -ne 0) {
    throw "jpackage failed with exit code $LASTEXITCODE"
}

$artifact = if ($Type -eq "exe") {
    Join-Path $OutputDir "MCastTalk-$Version.exe"
} else {
    Join-Path $OutputDir "MCastTalk"
}
if (-not (Test-Path -LiteralPath $artifact)) {
    throw "Expected jpackage artifact was not created: $artifact"
}
Write-Output $artifact
