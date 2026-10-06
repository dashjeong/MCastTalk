param([ValidateSet('amd64','arm64')][string]$Architecture='amd64')
$ErrorActionPreference='Stop'
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
  python scripts/sync-mobile-assets.py --verify
  if ($LASTEXITCODE -ne 0) { throw 'Committed mobile resource verification failed; synchronize explicitly and re-test before building' }
  go test ./...
  if ($LASTEXITCODE -ne 0) { throw 'Desktop regression tests failed' }
  New-Item -ItemType Directory -Force dist | Out-Null
  $env:CGO_ENABLED='0'; $env:GOOS='windows'; $env:GOARCH=$Architecture
  $name="dist/MCastTalk-0.2.49-desktop-windows-$Architecture.exe"
  go build -trimpath -ldflags '-s -w -H windowsgui' -o $name .
  if ($LASTEXITCODE -ne 0) { throw 'Executable build failed' }
  python scripts/verify-windows-manifest.py --manifest windows-app.manifest --exe $name --expect-arch $Architecture --out "$name.manifest-check.json"
  if ($LASTEXITCODE -ne 0) { throw 'Embedded Windows UTF-8/asInvoker manifest validation failed' }
  (Get-FileHash -Algorithm SHA256 $name).Hash.ToLowerInvariant() | Set-Content -Encoding ascii "$name.sha256"
  Write-Output "Built $name"
} finally { Pop-Location }
