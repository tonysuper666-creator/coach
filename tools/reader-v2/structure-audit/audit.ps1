#Requires -Version 5.1
# Re-run the Windows structure baseline. Book paths come from the caller.
$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
Set-Location $RepoRoot

if (-not $env:JAVA_HOME) {
    $adoptium = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
    if (Test-Path (Join-Path $adoptium 'bin\java.exe')) { $env:JAVA_HOME = $adoptium }
}
if (-not $env:GRADLE_USER_HOME -and (Test-Path 'D:\agent\gradle-home')) {
    $env:GRADLE_USER_HOME = 'D:\agent\gradle-home'
}

$revision = (& git -C $RepoRoot rev-parse HEAD).Trim()
if (-not $revision) { throw 'git rev-parse returned nothing' }

$forward = New-Object System.Collections.Generic.List[string]
$seenRevision = $false
foreach ($arg in $args) {
    if ($arg -eq '--revision') { $seenRevision = $true }
    $forward.Add($arg)
}
if (-not $seenRevision) {
    $forward.Add('--revision')
    $forward.Add($revision)
}

$argLine = ($forward | ForEach-Object {
    if ($_ -match '\s') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ }
}) -join ' '

& (Join-Path $RepoRoot 'gradlew.bat') ':desktopApp:structureAudit' "--args=$argLine"
exit $LASTEXITCODE
