# Run through tools/dev/heavy. The version must pass the normal MSI version gate.
param(
    [Parameter(Mandatory)][string]$Version,
    [Parameter(Mandatory)][string]$VersionCode
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$buildDir = Join-Path $root 'desktopApp\build'
$testDir = Join-Path $buildDir ('friend-url-test-' + [guid]::NewGuid().ToString('N'))
$fallback = 'https://fallback.example.invalid'
$override = 'https://override.example.invalid'
$config = Join-Path $testDir 'configured\Coach\config'
New-Item -ItemType Directory -Force $config,(Join-Path $testDir 'empty') | Out-Null
Set-Content -LiteralPath (Join-Path $config 'local.properties') -Encoding ASCII -Value "coach.friendUrl=$fallback"
$cfg = Join-Path $buildDir 'compose\binaries\main\app\LearningCoach\app\LearningCoach.cfg'
$friendProperty = 'ORG_GRADLE_PROJECT_coach.friendUrl'
$friendBefore = [Environment]::GetEnvironmentVariable($friendProperty, 'Process')
$prefix = 'java-options=-Dcoach.friendUrl='

function Test-Launcher([string]$Case, [string]$HomeDir, [string]$Expected, [string[]]$PropertyArgs = @()) {
    & (Join-Path $root 'gradlew.bat') :desktopApp:createDistributable --console=plain --no-daemon --priority=low --max-workers=2 @PropertyArgs "-Duser.home=$HomeDir" "-Pcoach.release.version=$Version" "-Pcoach.release.versionCode=$VersionCode"
    if ($LASTEXITCODE -ne 0) { throw "Launcher build failed for $Case." }
    $options = @(Get-Content -LiteralPath $cfg -Encoding UTF8 | Where-Object { $_.StartsWith($prefix, [StringComparison]::Ordinal) })
    if ($Expected) {
        if ($options.Count -ne 1 -or $options[0] -cne "$prefix$Expected") { throw "Friend URL did not match in $Case." }
    } elseif ($options.Count -ne 0) {
        throw 'Friend URL should be absent without configuration.'
    }
    Write-Host "PASS: $Case"
}

Push-Location $root
try {
    [Environment]::SetEnvironmentVariable($friendProperty, $null, 'Process')
    $configured = Join-Path $testDir 'configured'
    Test-Launcher 'Gradle property overrides local.properties' $configured $override @("-Pcoach.friendUrl=$override")
    Test-Launcher 'local.properties fallback' $configured $fallback
    Test-Launcher 'no config leaves URL absent' (Join-Path $testDir 'empty') ''
    [Environment]::SetEnvironmentVariable($friendProperty, $override, 'Process')
    Test-Launcher 'release packager environment overrides local.properties' $configured $override
} finally {
    [Environment]::SetEnvironmentVariable($friendProperty, $friendBefore, 'Process')
    Pop-Location
}
