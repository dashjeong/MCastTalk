$ErrorActionPreference = 'Stop'
if ([Security.Principal.WindowsIdentity]::GetCurrent().Name -notmatch '\\WDAGUtilityAccount$') {
    throw 'Guest-only status probe'
}
$taskProcesses = @(Get-Process | Where-Object { $_.ProcessName -match 'powershell|MCastTalk|python|java|llama|msedge|setup' } |
    Select-Object Id,ProcessName,CPU,WorkingSet64,StartTime)
$taskStatus = [ordered]@{
    checkedAt = [DateTimeOffset]::Now.ToString('o')
    processes = $taskProcesses
    installedExePresent = Test-Path 'C:\MCastTalkAcceptance\app\MCastTalk.exe'
    edgePresent = (Test-Path 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe') -or (Test-Path 'C:\Program Files\Microsoft\Edge\Application\msedge.exe')
    results = @(Get-ChildItem 'C:\MCastTalkResults' -File | Select-Object Name,Length,LastWriteTime)
}
$taskStatus | ConvertTo-Json -Depth 5 | Set-Content 'C:\MCastTalkResults\guest-status.json' -Encoding UTF8
