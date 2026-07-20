param(
    [switch]$PrintCommand
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$launcher = Join-Path $root "build\install\klaude-core-java\bin\klaude-core-java.bat"
if ($PrintCommand) {
    Write-Output "runtime=java"
    Write-Output "command=$launcher"
    exit 0
}
if (-not (Test-Path -LiteralPath $launcher)) {
    & (Join-Path $root "gradlew.bat") --no-daemon installDist
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}
& $launcher @args
exit $LASTEXITCODE
