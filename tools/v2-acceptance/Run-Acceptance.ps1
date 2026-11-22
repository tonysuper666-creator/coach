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
    [int]$BugsRepeat = 3,
    [switch]$SkipLegacy,
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
        '-BugsRepeat', $BugsRepeat,
        '-OutDir', "'$($OutDir.Replace("'", "''"))'",
        '-Runtime111', "'$($Runtime111.Replace("'", "''"))'",
        '-RuntimeV2', "'$($RuntimeV2.Replace("'", "''"))'",
        '-LibSeed', "'$($LibSeed.Replace("'", "''"))'",
        '-Book', "'$($Book.Replace("'", "''"))'"
    )
    if (-not [string]::IsNullOrWhiteSpace($RuntimeMain)) {
        $childArgs += @('-RuntimeMain', "'$($RuntimeMain.Replace("'", "''"))'")
    }
    if ($SkipLegacy) {
        $childArgs += '-SkipLegacy'
    }
    $logFile = Join-Path $OutDir 'worker.log'
    & $runInteractive -Script $PSCommandPath -ScriptArgs $childArgs -TimeoutSec 1800 -LogFile $logFile
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
    if (-not $SkipLegacy -and (Test-Path (Join-Path $Runtime111 'classpath.txt'))) {
        $cp111 = Compile-Harness $Runtime111 (Join-Path $clsRoot '111')
        Invoke-Harness $cp111 @('measure', '111', $LibSeed, $shotsDir, 'scene', '32') 'measure-111-scene'
        Invoke-Harness $cp111 @('measure', '111', $LibSeed, $shotsDir, 'win32', '32') 'measure-111-win32'
    }
    if (-not [string]::IsNullOrWhiteSpace($RuntimeMain) -and (Test-Path (Join-Path $RuntimeMain 'classpath.txt'))) {
        $cpMainM = Compile-Harness $RuntimeMain (Join-Path $clsRoot 'main')
        Invoke-Harness $cpMainM @('measure', 'main', $LibSeed, $shotsDir, 'scene', '32') 'measure-main-scene'
        Invoke-Harness $cpMainM @('measure', 'main', $LibSeed, $shotsDir, 'win32', '32') 'measure-main-win32'
    }
    if (-not $SkipLegacy -and (Test-Path (Join-Path $RuntimeV2 'classpath.txt'))) {
        $cpV2 = Compile-Harness $RuntimeV2 (Join-Path $clsRoot 'v2')
        Invoke-Harness $cpV2 @('measure', 'v2', $LibSeed, $shotsDir, 'scene', '34') 'measure-v2-scene'
        Invoke-Harness $cpV2 @('measure', 'v2', $LibSeed, $shotsDir, 'win32', '34') 'measure-v2-win32'
    }
    $measureJson = Join-Path $OutDir 'measure-report.json'
    & python (Join-Path $scriptDir 'measure_reader.py') $shotsDir --json-out $measureJson
    if ($LASTEXITCODE -ne 0) { throw 'measure_reader.py failed' }
}

function Invoke-BugsWithStability([string]$rt, [string]$flavor, [string]$dirName) {
    $cp = Compile-Harness $rt (Join-Path $clsRoot $flavor)
    $baseDir = Join-Path $OutDir $dirName
    New-Item -ItemType Directory -Force $baseDir | Out-Null
    $rounds = @()
    $repeat = [Math]::Max(1, $BugsRepeat)
    for ($i = 1; $i -le $repeat; $i++) {
        $rDir = Join-Path $baseDir "round-$i"
        Invoke-Harness $cp @('bugs', $flavor, $LibSeed, $rDir) "$dirName-r$i"
        $rJson = Get-Content (Join-Path $rDir 'bugs-report.json') -Raw -Encoding UTF8 | ConvertFrom-Json
        $rounds += $rJson
    }
    Copy-Item -Force (Join-Path $baseDir 'round-1\bugs-report.json') (Join-Path $baseDir 'bugs-report.json')

    $checkIds = @($rounds[0].checks | ForEach-Object { $_.id })
    $aggChecks = @()
    $consistentPass = 0
    $flakyCount = 0
    foreach ($cid in $checkIds) {
        $pCount = 0
        $fCount = 0
        $details = @()
        foreach ($r in $rounds) {
            $match = $r.checks | Where-Object { $_.id -eq $cid } | Select-Object -First 1
            if ($match.passed) { $pCount++ } else { $fCount++ }
            $details += $match.detail
        }
        $status = if ($pCount -eq $repeat) { $consistentPass++; 'PASS' } elseif ($fCount -eq $repeat) { 'FAIL' } else { $flakyCount++; 'FLAKY' }
        $aggChecks += [PSCustomObject]@{
            id = $cid
            status = $status
            pass_count = $pCount
            fail_count = $fCount
            runs = $repeat
            detail = $details[-1]
            round_details = $details
        }
    }
    $report = [PSCustomObject]@{
        flavor = $flavor
        runs = $repeat
        stable = ($flakyCount -eq 0)
        passed = $consistentPass
        flaky = $flakyCount
        total = $checkIds.Count
        round_passed = @($rounds | ForEach-Object { $_.passed })
        checks = $aggChecks
    }
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText((Join-Path $baseDir 'stability-report.json'), ($report | ConvertTo-Json -Depth 6), $utf8NoBom)
    return $report
}

if ($Suite -in 'all', 'bugs') {
    if (-not $SkipLegacy -and (Test-Path (Join-Path $Runtime111 'classpath.txt'))) {
        $bugs111Report = Invoke-BugsWithStability $Runtime111 '111' 'bugs-111'
    }
    if (-not [string]::IsNullOrWhiteSpace($RuntimeMain) -and (Test-Path (Join-Path $RuntimeMain 'classpath.txt'))) {
        $bugsMainReport = Invoke-BugsWithStability $RuntimeMain 'main' 'bugs-main'
    }
    if (-not $SkipLegacy -and (Test-Path (Join-Path $RuntimeV2 'classpath.txt'))) {
        $bugsV2Report = Invoke-BugsWithStability $RuntimeV2 'v2' 'bugs-v2'
    }
}

if ($Suite -in 'all', 'login') {
    $cpMain = Compile-Harness $RuntimeMain (Join-Path $clsRoot 'main')
    $loginDir = Join-Path $OutDir 'login-main'
    Invoke-Harness $cpMain @('login', $Book, $loginDir) 'login-main'
    $loginReport = Get-Content (Join-Path $loginDir 'login-report.json') -Raw -Encoding UTF8 | ConvertFrom-Json
}

Write-Host "=== ACCEPTANCE SUMMARY ($OutDir) ==="
if ($ranMeasure) {
    $rows = Get-Content (Join-Path $OutDir 'measure-report.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($r in $rows) {
        Write-Host ("MEASURE [{0}] {1} | {2} ({3}) | layers={4} vp={5}% body={6}% ink={7}px occ={8}px" -f `
            $r.flavor, $r.state_name, $r.surface_label, $r.resolution, $r.nesting_layers, $r.viewport_pct, $r.body_bounds_pct, $r.body_nonbg_ink_px, $r.drawer_occluded_body_px)
    }
}
$anyFlaky = $false
foreach ($pair in @(@('BUGS-111', $bugs111Report), @('BUGS-MAIN', $bugsMainReport), @('BUGS-V2', $bugsV2Report))) {
    $tag = $pair[0]
    $rep = $pair[1]
    if ($null -eq $rep) { continue }
    if (-not $rep.stable) { $anyFlaky = $true }
    $note = if ($tag -eq 'BUGS-111') { ' (expected 0/5 on unpatched 1.1.1)' } else { '' }
    Write-Host ("{0} ({1}x, rounds=[{2}], stable={3}): {4}/{5} consistent pass, flaky={6}{7}" -f `
        $tag, $rep.runs, ($rep.round_passed -join ','), $rep.stable, $rep.passed, $rep.total, $rep.flaky, $note)
    foreach ($c in $rep.checks) {
        Write-Host ("  [{0}] {1} (P={2}/F={3}): {4}" -f $c.status, $c.id, $c.pass_count, $c.fail_count, $c.detail)
    }
}
if ($null -ne $loginReport) {
    Write-Host ("LOGIN-MAIN: {0}/{1} passed" -f $loginReport.passed, $loginReport.total)
    foreach ($c in $loginReport.checks) { Write-Host ("  [{0}] {1}: {2}" -f $(if ($c.passed) {'PASS'} else {'FAIL'}), $c.id, $c.detail) }
}

if ($anyFlaky) { Write-Host 'FAILED: flaky bugs check detected'; exit 1 }
if ($null -ne $bugsMainReport -and $bugsMainReport.passed -lt $bugsMainReport.total) { exit 1 }
if ($null -ne $loginReport -and $loginReport.passed -lt $loginReport.total) { exit 1 }
exit 0
