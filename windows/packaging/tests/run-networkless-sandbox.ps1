[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
# Never install or remove anything on the developer/user host by mistake.
if ([Security.Principal.WindowsIdentity]::GetCurrent().Name -notmatch '\\WDAGUtilityAccount$') {
    throw 'This acceptance script is only for Windows Sandbox WDAGUtilityAccount.'
}
$taskRoot = 'C:\MCastTalkAcceptance'
$taskOutput = 'C:\MCastTalkResults'
$taskInstalled = Join-Path $taskRoot 'app'
$taskResults = [ordered]@{
    schemaVersion = 1
    passed = $false
    scope = 'Network-disabled clean Windows Sandbox; explicit CPU engine probe; automatic-backend browser interpretation and synthetic loopback media, not physical GPU/LAN acceptance'
    startedAt = [DateTimeOffset]::Now.ToString('o')
    guestComputerName = $env:COMPUTERNAME
    checks = @()
}
$taskServer = $null
$env:PYTHONDONTWRITEBYTECODE = '1'
New-Item -ItemType Directory -Path $taskRoot -Force | Out-Null
Start-Transcript -Path (Join-Path $taskOutput 'transcript.txt') -Force | Out-Null
. (Join-Path $PSScriptRoot 'networkless-assertions.ps1')
function Assert-OfflineNetwork {
    $taskTopology = Get-OfflineNetworkTopology
    $taskChecks = @()
    foreach ($taskAddress in @('1.1.1.1','8.8.8.8')) {
        $taskClient = New-Object Net.Sockets.TcpClient
        try {
            $taskConnection = $taskClient.BeginConnect($taskAddress,443,$null,$null)
            if ($taskConnection.AsyncWaitHandle.WaitOne(2500)) {
                try { $taskClient.EndConnect($taskConnection) } catch {}
            }
            if ($taskClient.Connected) { throw "Unexpected public TCP connectivity: $taskAddress" }
            $taskChecks += @{ address = $taskAddress; connected = $false }
        } finally { $taskClient.Dispose() }
    }
    return @{ defaultRoutes = $taskTopology.defaultRoutes; activeAdapters = $taskTopology.activeAdapters; publicTcp = $taskChecks }
}
try {
    $taskResults.networkBefore = Assert-OfflineNetwork
    $taskResults.checks += 'No adapter/default route/public TCP before installation'
    $taskInstaller = 'C:\MCastTalkInput\MCastTalk-0.4.1-Offline-Preview.exe'
    $taskExpected = (Get-Content -LiteralPath 'C:\MCastTalkTestKit\installer.sha256' -Raw).Trim()
    if ((Get-FileHash -LiteralPath $taskInstaller -Algorithm SHA256).Hash -ne $taskExpected) { throw 'Installer SHA mismatch' }
    $taskResults.installerSha256 = $taskExpected.ToLowerInvariant()
    $taskInstall = Start-Process -FilePath $taskInstaller -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART','/NOICONS',('/DIR="' + $taskInstalled + '"'),('/LOG="' + $taskOutput + '\install.log"')) -WindowStyle Hidden -PassThru -Wait
    if ($taskInstall.ExitCode -ne 0) { throw "Installation failed: $($taskInstall.ExitCode)" }
    $taskResults.checks += 'Fresh installation succeeded with no internet'
    if (-not (Test-Path -LiteralPath (Join-Path $taskInstalled 'legal/THIRD_PARTY_LICENSES.html'))) { throw 'Missing offline license viewer' }
    $taskResults.checks += 'Offline license viewer included'
    $taskPython = Join-Path $taskInstalled 'offline/python/python.exe'
    & $taskPython 'C:\MCastTalkTestKit\verify-offline-payload.py' --app $taskInstalled --output (Join-Path $taskOutput 'payload.json')
    if ($LASTEXITCODE -ne 0) { throw 'Installed payload verification failed' }
    $taskResults.checks += 'All installed offline files match bundle hashes'
    & $taskPython 'C:\MCastTalkTestKit\verify_native_dependencies.py' --assets (Join-Path $taskInstalled 'offline/assets') --output (Join-Path $taskOutput 'native-dependencies.json')
    if ($LASTEXITCODE -ne 0) { throw 'Installed native dependency check failed' }
    $taskResults.checks += 'Static inference binaries do not require an installed VC++ runtime'
    Set-Location -LiteralPath $taskRoot
    $taskWorkspace = Join-Path $taskRoot '.run/fresh-workspace'
    # jpackage strips native launchers; MCastTalk.exe embeds the shipped JVM.
    # Run fixture helpers from an isolated COPY of that runtime plus the exact
    # matching JDK launcher. Do not add a hidden dependency to the installed app.
    $taskFixtureRuntime = Join-Path $taskRoot 'fixture-runtime'
    $taskBundledVersion = Get-Content (Join-Path $taskInstalled 'runtime/release') | Where-Object { $_ -match '^JAVA_VERSION=' }
    $taskLauncherVersion = Get-Content 'C:\MCastTalkTestKit\java-release' | Where-Object { $_ -match '^JAVA_VERSION=' }
    if ($taskBundledVersion -ne $taskLauncherVersion) { throw 'Fixture Java launcher version mismatch' }
    if ((Get-FileHash 'C:\MCastTalkTestKit\java.exe').Hash -ne (Get-Content 'C:\MCastTalkTestKit\java-launcher.sha256' -Raw).Trim()) { throw 'Fixture Java launcher hash mismatch' }
    Copy-Item -LiteralPath (Join-Path $taskInstalled 'runtime') -Destination $taskFixtureRuntime -Recurse
    Copy-Item -LiteralPath 'C:\MCastTalkTestKit\java.exe' -Destination (Join-Path $taskFixtureRuntime 'bin/java.exe')
    $taskJava = Join-Path $taskFixtureRuntime 'bin/java.exe'
    $taskClasspath = 'C:\MCastTalkTestKit\fixtures;' + $taskInstalled + '\app\*'
    $taskPasswordBytes = New-Object byte[] 6
    $taskRng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $taskRng.GetBytes($taskPasswordBytes) } finally { $taskRng.Dispose() }
    $env:MCASTTALK_TEST_PASSWORD = [Convert]::ToBase64String($taskPasswordBytes)
    & $taskJava -cp $taskClasspath app.mcasttalk.windows.host.AccountFixtureMain $taskWorkspace
    if ($LASTEXITCODE -ne 0) { throw 'Fresh test account fixture failed' }
    & $taskJava -cp $taskClasspath app.mcasttalk.windows.host.BundleFixtureMain (Join-Path $taskInstalled 'offline') $taskWorkspace
    if ($LASTEXITCODE -ne 0) { throw 'First-run offline model preparation failed' }
    $taskResults.checks += 'Fresh test accounts and production bundle preparation (not manual setup UI)'
    $taskResults.fixtureRuntime = 'Copy of installed JVM plus matching test-only java.exe; installed application unchanged'
    $taskProbe = Start-Process -FilePath $taskPython -ArgumentList @('C:\MCastTalkTestKit\offline_engine_probe.py','--app',$taskInstalled,'--output',(Join-Path $taskOutput 'engine')) -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput (Join-Path $taskOutput 'engine-stdout.log') -RedirectStandardError (Join-Path $taskOutput 'engine-stderr.log')
    if ($taskProbe.ExitCode -ne 0) { throw 'Real installed STT/translation/TTS probe failed' }
    $taskResults.checks += 'Real CPU STT -> four-language translation and TTS (synthetic input)'
    $taskServer = Start-Process -FilePath (Join-Path $taskInstalled 'MCastTalk.exe') -ArgumentList @(("--data-dir=" + $taskWorkspace),'--bind=127.0.0.1','--port=18791','--no-browser') -WindowStyle Hidden -PassThru
    $taskReady = $false
    for ($taskAttempt = 0; $taskAttempt -lt 60; $taskAttempt++) {
        if ($taskServer.HasExited) { throw 'Installed host exited' }
        try {
            $taskHealth = Invoke-RestMethod -Uri 'http://127.0.0.1:18791/health/ready' -TimeoutSec 2
            $taskReady = $true
            break
        } catch { Start-Sleep -Seconds 1 }
    }
    if (-not $taskReady) { throw 'Installed host did not become ready' }
    $taskPage = Invoke-WebRequest -Uri 'http://127.0.0.1:18791/' -UseBasicParsing
    if ($taskPage.StatusCode -ne 200 -or $taskPage.Content -notmatch 'login-view') { throw 'Local meeting login page unavailable' }
    $taskResults.checks += 'Installed EXE health and local meeting page without internet'
    $env:MCASTTALK_TEST_URL = 'http://127.0.0.1:18791'
    $env:MCASTTALK_TEST_OUTPUT = Join-Path $taskOutput 'browser'
    $env:MCASTTALK_TEST_AUDIO_ROOT = Join-Path $taskOutput 'engine'
    $env:MCASTTALK_TEST_LOOPBACK_WEBRTC = '1'
    $taskResults.mediaFixture = 'Synthetic camera/audio; explicit Chromium loopback ICE flag because this guest has no network adapter. Not normal LAN ICE acceptance.'
    New-Item -ItemType Directory -Path $env:MCASTTALK_TEST_OUTPUT | Out-Null
    & 'C:\MCastTalkTestKit\node.exe' 'C:\MCastTalkTestKit\networkless-browser.cjs'
    if ($LASTEXITCODE -ne 0) { throw 'Offline browser account/navigation probe failed' }
    $taskResults.checks += 'Guest Edge login, eight-character password, admin and guest permissions, invitation and offline license rendering'
    & 'C:\MCastTalkTestKit\node.exe' 'C:\MCastTalkTestKit\inference-browser.cjs'
    if ($LASTEXITCODE -ne 0) { throw 'Offline browser interpretation/media probe failed' }
    $taskResults.checks += 'Four-browser real interpretation, public/private chat, captions, playback and synthetic video'
    # Owned test process only. Check persistence, relaunch, and guest-only removal.
    & taskkill.exe /PID $taskServer.Id /T /F | Out-Null
    $taskServer.WaitForExit(15000) | Out-Null
    if (-not $taskServer.HasExited) { throw 'Owned server did not stop' }
    $taskConfigHashes = @(Get-ChildItem -LiteralPath (Join-Path $taskWorkspace 'config') -File -Recurse | Get-FileHash)
    $taskServer = Start-Process -FilePath (Join-Path $taskInstalled 'MCastTalk.exe') -ArgumentList @(("--data-dir=" + $taskWorkspace),'--bind=127.0.0.1','--port=18791','--no-browser') -WindowStyle Hidden -PassThru
    $taskReady = $false
    for ($taskAttempt = 0; $taskAttempt -lt 60; $taskAttempt++) {
        try { $taskHealth = Invoke-RestMethod -Uri 'http://127.0.0.1:18791/health/ready' -TimeoutSec 2; $taskReady = $true; break } catch { Start-Sleep -Seconds 1 }
    }
    if (-not $taskReady) { throw 'Installed EXE relaunch failed' }
    $taskResults.checks += 'Installed EXE relaunches using the saved test workspace'
    & taskkill.exe /PID $taskServer.Id /T /F | Out-Null
    $taskServer.WaitForExit(15000) | Out-Null
    if (-not $taskServer.HasExited) { throw 'Owned server did not stop before uninstall' }
    $taskUninstall = Start-Process -FilePath (Join-Path $taskInstalled 'unins000.exe') -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART',('/LOG="' + $taskOutput + '\uninstall.log"')) -WindowStyle Hidden -PassThru -Wait
    if ($taskUninstall.ExitCode -ne 0 -or (Test-Path (Join-Path $taskInstalled 'MCastTalk.exe'))) { throw 'Guest uninstall failed' }
    foreach ($taskHash in $taskConfigHashes) {
        if ((Get-FileHash -LiteralPath $taskHash.Path).Hash -ne $taskHash.Hash) { throw 'Guest workspace was changed by relaunch/uninstall' }
    }
    $taskResults.checks += 'Guest uninstall succeeds and preserves test workspace configuration/account hashes'
    $taskResults.networkAfter = Assert-OfflineNetwork
    $taskResults.checks += 'Network still disabled after execution'
    $taskResults.passed = $true
} catch {
    $taskResults.error = $_.Exception.Message
} finally {
    $env:MCASTTALK_TEST_PASSWORD = $null
    if ($taskServer -and -not $taskServer.HasExited) { & taskkill.exe /PID $taskServer.Id /T /F | Out-Null }
    $taskResults.completedAt = [DateTimeOffset]::Now.ToString('o')
    $taskResults | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $taskOutput 'acceptance.json') -Encoding UTF8
    Stop-Transcript | Out-Null
}
# No reboot, OS feature changes, host network changes, automatic VM close, or human UAT claim.
if (-not $taskResults.passed) { exit 1 }
