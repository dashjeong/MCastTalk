$ErrorActionPreference = "Stop"

function Resolve-PackagingExecutable {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [string[]]$Hints = @()
    )

    foreach ($hint in $Hints) {
        if ($hint -and (Test-Path -LiteralPath $hint -PathType Leaf)) {
            return (Resolve-Path -LiteralPath $hint).Path
        }
    }
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    return $null
}

function Resolve-MCastTalkJdk {
    param(
        [Parameter(Mandatory = $true)][string]$ProjectRoot
    )

    # Keep java.exe and jpackage.exe on the same JDK.  The repository's
    # portable JDK is preferred so packaging remains reproducible after a
    # reboot even when JAVA_HOME or PATH points at a JRE or another JDK.
    $candidates = [System.Collections.Generic.List[object]]::new()
    if ($env:MCASTTALK_JDK_HOME) {
        $candidates.Add([pscustomobject]@{ home = $env:MCASTTALK_JDK_HOME; source = "MCASTTALK_JDK_HOME" })
    }

    $portableRoot = Join-Path $ProjectRoot ".tools\jdk17"
    if (Test-Path -LiteralPath $portableRoot -PathType Container) {
        Get-ChildItem -LiteralPath $portableRoot -Directory |
            Sort-Object -Property Name -Descending |
            ForEach-Object {
                $candidates.Add([pscustomobject]@{ home = $_.FullName; source = "repository-portable" })
            }
    }

    if ($env:JAVA_HOME) {
        $candidates.Add([pscustomobject]@{ home = $env:JAVA_HOME; source = "JAVA_HOME" })
    }

    foreach ($candidate in $candidates) {
        $java = Join-Path $candidate.home "bin\java.exe"
        $jpackage = Join-Path $candidate.home "bin\jpackage.exe"
        if ((Test-Path -LiteralPath $java -PathType Leaf) -and
            (Test-Path -LiteralPath $jpackage -PathType Leaf)) {
            return [pscustomobject]@{
                home = (Resolve-Path -LiteralPath $candidate.home).Path
                java = (Resolve-Path -LiteralPath $java).Path
                jpackage = (Resolve-Path -LiteralPath $jpackage).Path
                source = $candidate.source
            }
        }
    }

    $pathJava = Resolve-PackagingExecutable -Name "java.exe"
    $pathJpackage = Resolve-PackagingExecutable -Name "jpackage.exe"
    if ($pathJava -and $pathJpackage) {
        $javaHome = Split-Path -Parent (Split-Path -Parent $pathJpackage)
        $pairedJava = Join-Path $javaHome "bin\java.exe"
        if (-not (Test-Path -LiteralPath $pairedJava -PathType Leaf)) {
            return $null
        }
        return [pscustomobject]@{
            home = $javaHome
            java = (Resolve-Path -LiteralPath $pairedJava).Path
            jpackage = $pathJpackage
            source = "PATH"
        }
    }
    return $null
}

function Get-MCastTalkPackagingToolchain {
    param(
        [ValidateSet("exe", "app-image")][string]$Type = "exe",
        [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
    )

    $jdk = Resolve-MCastTalkJdk -ProjectRoot $ProjectRoot
    $java = if ($jdk) { $jdk.java } else { $null }
    $jpackage = if ($jdk) { $jdk.jpackage } else { $null }
    $gradle = Join-Path $ProjectRoot "gradlew.bat"
    if (-not (Test-Path -LiteralPath $gradle -PathType Leaf)) {
        $gradle = $null
    }

    $wixBin = $null
    foreach ($root in @($env:WIX_HOME, $env:WIX, $env:WIX_TOOLSET_PATH)) {
        if ($root) {
            $candidate = Join-Path $root "bin"
            if (Test-Path -LiteralPath $candidate -PathType Container) {
                $wixBin = $candidate
                break
            }
            if (Test-Path -LiteralPath $root -PathType Container) {
                $wixBin = $root
                break
            }
        }
    }
    $candleHints = @()
    $lightHints = @()
    if ($wixBin) {
        $candleHints = @((Join-Path $wixBin "candle.exe"))
        $lightHints = @((Join-Path $wixBin "light.exe"))
    }
    $candle = Resolve-PackagingExecutable -Name "candle.exe" -Hints $candleHints
    $light = Resolve-PackagingExecutable -Name "light.exe" -Hints $lightHints
    $wix4 = Resolve-PackagingExecutable -Name "wix.exe"

    $result = [ordered]@{
        projectRoot = $ProjectRoot
        javaHome = if ($jdk) { $jdk.home } else { $null }
        jdkSource = if ($jdk) { $jdk.source } else { $null }
        java = $java
        jpackage = $jpackage
        gradleWrapper = $gradle
        wix = [ordered]@{
            candle = $candle
            light = $light
            wix4 = $wix4
            usableByJpackage = [bool]($candle -and $light)
        }
        requestedType = $Type
        ready = [bool]($java -and $jpackage -and $gradle -and (($Type -eq "app-image") -or ($candle -and $light)))
    }
    return [pscustomobject]$result
}
