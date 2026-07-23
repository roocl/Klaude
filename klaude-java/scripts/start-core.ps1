param(
    [switch]$PrintCommand
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$launcher = Join-Path $root "build\install\klaude-core-java\bin\klaude-core-java.bat"

function Get-JavaMajor([string]$javaHome) {
    if ([string]::IsNullOrWhiteSpace($javaHome)) {
        return 0
    }
    $java = Join-Path $javaHome "bin\java.exe"
    if (-not (Test-Path -LiteralPath $java)) {
        return 0
    }
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $versionLine = (& $java -version 2>&1 | Select-Object -First 1) -as [string]
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($versionLine -match 'version "(?:1\.)?(\d+)') {
        return [int]$Matches[1]
    }
    return 0
}

function Find-Java21 {
    $candidates = [System.Collections.Generic.List[string]]::new()
    if ($env:KLAUDE_JAVA_HOME) { $candidates.Add($env:KLAUDE_JAVA_HOME) }
    if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }
    if ($env:JAVA_HOME) {
        $javaParent = Split-Path -Parent $env:JAVA_HOME
        Get-ChildItem -LiteralPath $javaParent -Directory -ErrorAction SilentlyContinue |
            ForEach-Object { $candidates.Add($_.FullName) }
    }
    $gradleJdks = Join-Path ([Environment]::GetFolderPath("UserProfile")) ".gradle\jdks"
    Get-ChildItem -LiteralPath $gradleJdks -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { $candidates.Add($_.FullName) }
    foreach ($candidate in $candidates) {
        if ((Get-JavaMajor $candidate) -ge 21) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    return $null
}

$javaHome = Find-Java21
if (-not $javaHome) {
    Write-Error "Klaude requires Java 21 or newer. Set KLAUDE_JAVA_HOME or JAVA_HOME to a JDK 21 installation."
    exit 2
}
$env:JAVA_HOME = $javaHome
if ($PrintCommand) {
    Write-Output "runtime=java"
    Write-Output "java_home=$javaHome"
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
