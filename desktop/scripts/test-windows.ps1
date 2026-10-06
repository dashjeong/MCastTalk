param([int]$Iterations=5,[string]$EvidenceDirectory="$env:TEMP/MCastTalk-desktop-evidence")
$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Force $EvidenceDirectory | Out-Null
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
  $report=@{started=(Get-Date).ToUniversalTime().ToString('o'); os=[Environment]::OSVersion.VersionString; architecture=$env:PROCESSOR_ARCHITECTURE; go=(go version); iterations=@()}
  for($attempt=1;$attempt -le $Iterations;$attempt++) {
    $log=Join-Path $EvidenceDirectory "windows-test-$attempt.jsonl"
    go test -json -count=1 ./... | Set-Content -Encoding utf8 $log
    $code=$LASTEXITCODE
    $report.iterations+=@{attempt=$attempt; exitCode=$code; log=$log; hash=(Get-FileHash $log -Algorithm SHA256).Hash}
    if($code -ne 0) { $report | ConvertTo-Json -Depth 10 | Set-Content -Encoding utf8 (Join-Path $EvidenceDirectory 'report.json'); throw "Regression failed at iteration $attempt" }
  }
  go vet ./...
  if($LASTEXITCODE -ne 0) { throw 'Go vet failed' }
  $report.finished=(Get-Date).ToUniversalTime().ToString('o')
  $report | ConvertTo-Json -Depth 10 | Set-Content -Encoding utf8 (Join-Path $EvidenceDirectory 'report.json')
} finally { Pop-Location }
