$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
& (Join-Path $root "gradlew.bat") --no-daemon offlineDemo --quiet
exit $LASTEXITCODE
