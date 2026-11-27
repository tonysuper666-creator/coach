# Five-persona retest for one packaged Windows build. One command:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\smoke\persona-retest.ps1 -Exe <expanded>\LearningCoach.exe
# Prints a comparison table. Screenshots stay under D:\agent\evidence\persona-retest\<time>\.
param(
    [string]$Exe = '',
    [string]$Evidence = 'D:\agent\evidence\persona-retest',
    [string]$Baseline = '',
    [string]$Personas = 'P1,P2,P3,P4,P5',
    [int]$TimeoutSec = 3000
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $Exe) {
    $Exe = Join-Path $repo 'desktopApp\build\compose\binaries\main\app\LearningCoach\LearningCoach.exe'
}
if (-not (Test-Path -LiteralPath $Exe)) { throw "PERSONA_RETEST_NO_EXE: pass -Exe for this version's expanded LearningCoach.exe" }
$Exe = (Resolve-Path -LiteralPath $Exe).Path
$machine = Join-Path $env:LOCALAPPDATA 'LearningCoach'
if ($Exe.StartsWith($machine, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'PERSONA_RETEST_REFUSED: this script does not drive the machine-wide install'
}
if (-not $Baseline) {
    $defaultBaseline = Join-Path $PSScriptRoot 'persona-retest-baseline.json'
    if (Test-Path -LiteralPath $defaultBaseline) { $Baseline = $defaultBaseline }
}
$version = 'unknown'
$cfg = Join-Path (Split-Path -Parent $Exe) 'app\LearningCoach.cfg'
if (Test-Path -LiteralPath $cfg) {
    foreach ($line in [IO.File]::ReadAllLines($cfg)) {
        if ($line -match 'jpackage\.app-version=([0-9A-Za-z._+-]+)') { $version = $Matches[1]; break }
    }
}
$commit = (& git -C $repo rev-parse --short=12 HEAD).Trim()
if ($LASTEXITCODE -ne 0) { $commit = '' }
$out = Join-Path $Evidence ((Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 6))
New-Item -ItemType Directory -Force -Path $out | Out-Null
$out = (Resolve-Path -LiteralPath $out).Path
$args2 = "-Repo '$repo' -OutDir '$out' -Exe '$($Exe.Replace("'", "''"))' -Version '$version' -Commit '$commit' -Personas '$Personas'"
if ($Baseline) { $args2 += " -Baseline '$($Baseline.Replace("'", "''"))'" }
& (Join-Path $PSScriptRoot 'Run-Interactive.ps1') -Script (Join-Path $PSScriptRoot 'persona-retest-worker.ps1') -ScriptArgs $args2 -LogFile (Join-Path $out 'worker.log') -TimeoutSec $TimeoutSec
$invocationExit = $LASTEXITCODE
$dirtyFile = Join-Path $out 'environment-dirty.json'
if ((Test-Path -LiteralPath $dirtyFile)) {
    Write-Output "ENVIRONMENT_DIRTY exit 2  $out"
    exit 2
}
$resultFile = Join-Path $out 'result.json'
if (-not (Test-Path -LiteralPath $resultFile)) {
    Write-Output "PERSONA_RETEST_NO_RESULT exit 2  $out"
    exit 2
}
$env:PYTHONIOENCODING = 'utf-8'
$py = Join-Path $PSScriptRoot 'persona_retest_table.py'
$tablePath = Join-Path $out 'table.txt'
$pyArgs = @($py, '--run', $resultFile, '--out', $tablePath)
if ($Baseline -and (Test-Path -LiteralPath $Baseline)) { $pyArgs += @('--baseline', $Baseline) }
& python @pyArgs
if ($LASTEXITCODE -ne 0) { Write-Output "PERSONA_RETEST_TABLE_FAILED exit 2  $out"; exit 2 }
Write-Output ([IO.File]::ReadAllText($tablePath))
Write-Output "evidence=$out"
$result = Get-Content -LiteralPath $resultFile -Raw -Encoding UTF8 | ConvertFrom-Json
$code = [int]$result.code
if ($code -eq 0 -and $invocationExit -ne 0) { exit $invocationExit }
exit $code
