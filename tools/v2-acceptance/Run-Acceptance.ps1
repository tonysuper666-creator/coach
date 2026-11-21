<#
.SYNOPSIS
One-command real-window & ImageComposeScene acceptance runner on winbox (TON-1785).
Runs:
  1. Unified area/nesting/occlusion measurement on 1.1.1 and proto/v2-reader (both scene & win32 real window)
  2. 5 blocking bug regression checks on 1.1.1 (expects 5/5 fail) and proto/v2-reader / main
  3. Login 4-path real-window verification on main (TON-1782)
Automatically enters winbox Session 1 interactive console when invoked from Session 0 (SSH).
Non-focus-stealing and isolated per run (compatible with TON-1690 parallel winbox windows).
#>
param(
    [string]$Runtime111 = 'D:\agent\baseline-111\desktopApp\build\outputs\smoke-runtime',
    [string]$RuntimeV2 = 'D:\agent\proto-v2-reader\desktopApp\build\outputs\smoke-runtime',
    [string]$RuntimeMain = '',
    [string]$LibSeed = 'D:\agent\evidence\b-reader-lib',
    [string]$Book = 'D:\agent\materials\Health-Economics-620p.pdf',
    [string]$OutDir = '',
    [ValidateSet('all', 'measure', 'bugs', 'login')][string]$Suite = 'all',
    [switch]$Direct
)
$ErrorActionPreference = 'Continue'
$PSNativeCommandUseErrorActionPreference = $false
[Console]::OutputEncoding = [Text.Encoding]::UTF8
try { [System.Diagnostics.Process]::GetCurrentProcess().PriorityClass = 'Normal' } catch {}

$scriptDir = $PSScriptRoot
$repoRoot = (Resolve-Path (Join-Path $scriptDir '..\..')).Path
if ([string]::IsNullOrWhiteSpace($RuntimeMain)) {
    $candidate = Join-Path $repoRoot 'desktopApp\build\outputs\smoke-runtime'
    if (Test-Path (Join-Path $candidate 'classpath.txt')) {
        $RuntimeMain = $candidate
    } elseif (Test-Path 'D:\agent\coach-smoke-runtime-main\classpath.txt') {
        $RuntimeMain = 'D:\agent\coach-smoke-runtime-main'
    } elseif ($Suite -in 'all', 'login') {
        throw 'Missing RuntimeMain; pass -RuntimeMain or build :desktopApp:smokeRuntime'
    }
}
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $stamp = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 6)
    $OutDir = "D:\agent\evidence\2.0-W1-C\$stamp"
}
New-Item -ItemType Directory -Force $OutDir | Out-Null
$OutDir = (Resolve-Path $OutDir).Path

$sessionId = (Get-Process -Id $PID).SessionId
if ($sessionId -eq 0 -and -not $Direct) {
    $runInteractive = Join-Path $repoRoot 'tools\smoke\Run-Interactive.ps1'
    if (-not (Test-Path $runInteractive)) {
        $runInteractive = 'D:\agent\proto-v2-reader\tools\smoke\Run-Interactive.ps1'
    }
    $childArgs = @(
        '-Direct',
        '-Suite', $Suite,
        '-OutDir', "'$($OutDir.Replace("'", "''"))'",
        '-Runtime111', "'$($Runtime111.Replace("'", "''"))'",
        '-RuntimeV2', "'$($RuntimeV2.Replace("'", "''"))'",
        '-LibSeed', "'$($LibSeed.Replace("'", "''"))'",
        '-Book', "'$($Book.Replace("'", "''"))'"
    )
    if (-not [string]::IsNullOrWhiteSpace($RuntimeMain)) {
        $childArgs += @('-RuntimeMain', "'$($RuntimeMain.Replace("'", "''"))'")
    }
    $logFile = Join-Path $OutDir 'worker.log'
    & $runInteractive -Script $PSCommandPath -ScriptArgs $childArgs -TimeoutSec 900 -LogFile $logFile
    $rc = $LASTEXITCODE
    if (Test-Path $logFile) { Get-Content -LiteralPath $logFile -Encoding UTF8 }
    exit $rc
}

function Get-RuntimeClasspath([string]$rt) {
    $lines = Get-Content (Join-Path $rt 'classpath.txt') | Where-Object { $_.Trim() -ne '' }
    return ($lines | ForEach-Object { Join-Path $rt $_ }) -join ';'
}

function Compile-Harness([string]$rt, [string]$clsDir) {
    New-Item -ItemType Directory -Force $clsDir | Out-Null
    $cp = Get-RuntimeClasspath $rt
    & javac -encoding UTF-8 -cp $cp -d $clsDir (Join-Path $scriptDir 'ReaderAcceptanceHarness.java')
    if ($LASTEXITCODE -ne 0) { throw "javac failed for $rt" }
    return "$clsDir;$cp"
}

function Invoke-Harness([string]$cp, [string[]]$hArgs, [string]$label) {
    $jvmArgs = @('-Xmx2g', '-Dfile.encoding=UTF-8', '-Dcoach.windows.theme=light')
    $errLog = Join-Path $OutDir ("$label.stderr.log")
    & java @jvmArgs -cp $cp ReaderAcceptanceHarness @hArgs 2>$errLog
    if ($LASTEXITCODE -ne 0) {
        if (Test-Path $errLog) { Get-Content -LiteralPath $errLog -Tail 30 | Write-Host }
        throw "$label failed (exit=$LASTEXITCODE)"
    }
}

$clsRoot = Join-Path $OutDir '_cls'
$shotsDir = Join-Path $OutDir 'shots'
New-Item -ItemType Directory -Force $shotsDir | Out-Null

$ranMeasure = $false
$bugs111Report = $null
$bugsMainReport = $null
$bugsV2Report = $null
$loginReport = $null

if ($Suite -in 'all', 'measure') {
    $ranMeasure = $true
    if (Test-Path (Join-Path $Runtime111 'classpath.txt')) {
        $cp111 = Compile-Harness $Runtime111 (Join-Path $clsRoot '111')
        Invoke-Harness $cp111 @('measure', '111', $LibSeed, $shotsDir, 'scene', '32') 'measure-111-scene'
        Invoke-Harness $cp111 @('measure', '111', $LibSeed, $shotsDir, 'win32', '32') 'measure-111-win32'
    }
    if (-not [string]::IsNullOrWhiteSpace($RuntimeMain) -and (Test-Path (Join-Path $RuntimeMain 'classpath.txt'))) {
        $cpMainM = Compile-Harness $RuntimeMain (Join-Path $clsRoot 'main')
        Invoke-Harness $cpMainM @('measure', 'main', $LibSeed, $shotsDir, 'scene', '32') 'measure-main-scene'
        Invoke-Harness $cpMainM @('measure', 'main', $LibSeed, $shotsDir, 'win32', '32') 'measure-main-win32'
    }
    if (Test-Path (Join-Path $RuntimeV2 'classpath.txt')) {
        $cpV2 = Compile-Harness $RuntimeV2 (Join-Path $clsRoot 'v2')
        Invoke-Harness $cpV2 @('measure', 'v2', $LibSeed, $shotsDir, 'scene', '34') 'measure-v2-scene'
        Invoke-Harness $cpV2 @('measure', 'v2', $LibSeed, $shotsDir, 'win32', '34') 'measure-v2-win32'
    }
    $measureJson = Join-Path $OutDir 'measure-report.json'
    & python (Join-Path $scriptDir 'measure_reader.py') $shotsDir --json-out $measureJson
    if ($LASTEXITCODE -ne 0) { throw 'measure_reader.py failed' }
}

if ($Suite -in 'all', 'bugs') {
    if (Test-Path (Join-Path $Runtime111 'classpath.txt')) {
        $cp111 = Compile-Harness $Runtime111 (Join-Path $clsRoot '111')
        $b111Dir = Join-Path $OutDir 'bugs-111'
        Invoke-Harness $cp111 @('bugs', '111', $LibSeed, $b111Dir) 'bugs-111'
        $bugs111Report = Get-Content (Join-Path $b111Dir 'bugs-report.json') -Raw | ConvertFrom-Json
    }
    if (-not [string]::IsNullOrWhiteSpace($RuntimeMain) -and (Test-Path (Join-Path $RuntimeMain 'classpath.txt'))) {
        $cpMainB = Compile-Harness $RuntimeMain (Join-Path $clsRoot 'main')
        $bMainDir = Join-Path $OutDir 'bugs-main'
        Invoke-Harness $cpMainB @('bugs', 'main', $LibSeed, $bMainDir) 'bugs-main'
        $bugsMainReport = Get-Content (Join-Path $bMainDir 'bugs-report.json') -Raw | ConvertFrom-Json
    }
    if (Test-Path (Join-Path $RuntimeV2 'classpath.txt')) {
        $cpV2 = Compile-Harness $RuntimeV2 (Join-Path $clsRoot 'v2')
        $bV2Dir = Join-Path $OutDir 'bugs-v2'
        Invoke-Harness $cpV2 @('bugs', 'v2', $LibSeed, $bV2Dir) 'bugs-v2'
        $bugsV2Report = Get-Content (Join-Path $bV2Dir 'bugs-report.json') -Raw | ConvertFrom-Json
    }
}

if ($Suite -in 'all', 'login') {
    $cpMain = Compile-Harness $RuntimeMain (Join-Path $clsRoot 'main')
    $loginDir = Join-Path $OutDir 'login-main'
    Invoke-Harness $cpMain @('login', $Book, $loginDir) 'login-main'
    $loginReport = Get-Content (Join-Path $loginDir 'login-report.json') -Raw | ConvertFrom-Json
}

Write-Host "=== ACCEPTANCE SUMMARY ($OutDir) ==="
if ($ranMeasure) {
    $rows = Get-Content (Join-Path $OutDir 'measure-report.json') -Raw | ConvertFrom-Json
    foreach ($r in $rows) {
        Write-Host ("MEASURE [{0}] {1} | {2} ({3}) | layers={4} vp={5}% body={6}% ink={7}px occ={8}px" -f `
            $r.flavor, $r.state_name, $r.surface_label, $r.resolution, $r.nesting_layers, $r.viewport_pct, $r.body_bounds_pct, $r.body_nonbg_ink_px, $r.drawer_occluded_body_px)
    }
}
if ($null -ne $bugs111Report) {
    Write-Host ("BUGS-111: {0}/{1} passed (expected 0/5 on unpatched 1.1.1)" -f $bugs111Report.passed, $bugs111Report.total)
    foreach ($c in $bugs111Report.checks) { Write-Host ("  [{0}] {1}: {2}" -f $(if ($c.passed) {'PASS'} else {'FAIL'}), $c.id, $c.detail) }
}
if ($null -ne $bugsMainReport) {
    Write-Host ("BUGS-MAIN: {0}/{1} passed" -f $bugsMainReport.passed, $bugsMainReport.total)
    foreach ($c in $bugsMainReport.checks) { Write-Host ("  [{0}] {1}: {2}" -f $(if ($c.passed) {'PASS'} else {'FAIL'}), $c.id, $c.detail) }
}
if ($null -ne $bugsV2Report) {
    Write-Host ("BUGS-V2: {0}/{1} passed" -f $bugsV2Report.passed, $bugsV2Report.total)
    foreach ($c in $bugsV2Report.checks) { Write-Host ("  [{0}] {1}: {2}" -f $(if ($c.passed) {'PASS'} else {'FAIL'}), $c.id, $c.detail) }
}
if ($null -ne $loginReport) {
    Write-Host ("LOGIN-MAIN: {0}/{1} passed" -f $loginReport.passed, $loginReport.total)
    foreach ($c in $loginReport.checks) { Write-Host ("  [{0}] {1}: {2}" -f $(if ($c.passed) {'PASS'} else {'FAIL'}), $c.id, $c.detail) }
}

if ($null -ne $loginReport -and $loginReport.passed -lt $loginReport.total) { exit 1 }
exit 0
