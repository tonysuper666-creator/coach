# 离线验证桌面脏前景检查与 PID 清理核对（退出码：0 = 全部通过）。
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Win-Drive.ps1')

$checks = New-Object System.Collections.ArrayList
function Check([string]$name, [bool]$ok, [string]$detail = '') {
    [void]$checks.Add([pscustomobject]@{ Name = $name; Ok = $ok; Detail = $detail })
    $tag = if ($ok) { 'PASS' } else { 'FAIL' }
    "$tag $name$(if ($detail) { " ($detail)" })"
}

# 1. 桌面/任务栏/隐身/自身 PID 不算脏前台
$cleanCases = @(
    @{ Name = 'zero-hwnd'; Info = $null },
    @{ Name = 'invisible'; Info = [pscustomobject]@{ Hwnd = 101L; Pid = 9999; Visible = $false; Cloaked = $false; Class = 'Notepad'; Process = 'Notepad' } },
    @{ Name = 'cloaked-uwp'; Info = [pscustomobject]@{ Hwnd = 102L; Pid = 9999; Visible = $true; Cloaked = $true; Class = 'Windows.UI.Core.CoreWindow'; Process = 'SystemSettings' } },
    @{ Name = 'self-pid'; Info = [pscustomobject]@{ Hwnd = 103L; Pid = $PID; Visible = $true; Cloaked = $false; Class = 'ConsoleWindowClass'; Process = 'powershell' } }
)
foreach ($cls in @('Progman', 'WorkerW', 'Shell_TrayWnd', 'Shell_SecondaryTrayWnd', 'TaskListThumbnailWnd', 'MultitaskingViewFrame', 'ForegroundStaging')) {
    $cleanCases += @{ Name = "shell-$cls"; Info = [pscustomobject]@{ Hwnd = 200L; Pid = 8200; Visible = $true; Cloaked = $false; Class = $cls; Process = 'explorer' } }
}
foreach ($c in $cleanCases) {
    $got = Find-ForeignForegroundWindow -Probe { $c.Info }
    Check "clean-$($c.Name)" ($null -eq $got) $(if ($got) { $got.Summary } else { 'none' })
}

# 2. 外窗（记事本、终端、其他 Coach、浏览器）必须报出进程名与类，不带标题
$dirtyCases = @(
    @{ Name = 'notepad'; Info = [pscustomobject]@{ Hwnd = 301L; Pid = 26044; Visible = $true; Cloaked = $false; Class = 'Notepad'; Process = 'Notepad' }; Expect = 'Notepad pid=26044 class=Notepad' },
    @{ Name = 'terminal'; Info = [pscustomobject]@{ Hwnd = 302L; Pid = 13444; Visible = $true; Cloaked = $false; Class = 'CASCADIA_HOSTING_WINDOW_CLASS'; Process = 'WindowsTerminal' }; Expect = 'WindowsTerminal pid=13444 class=CASCADIA_HOSTING_WINDOW_CLASS' },
    @{ Name = 'coach'; Info = [pscustomobject]@{ Hwnd = 303L; Pid = 21116; Visible = $true; Cloaked = $false; Class = 'SunAwtFrame'; Process = 'java' }; Expect = 'java pid=21116 class=SunAwtFrame' },
    @{ Name = 'edge'; Info = [pscustomobject]@{ Hwnd = 304L; Pid = 20664; Visible = $true; Cloaked = $false; Class = 'Chrome_WidgetWin_1'; Process = 'msedge' }; Expect = 'msedge pid=20664 class=Chrome_WidgetWin_1' }
)
foreach ($c in $dirtyCases) {
    $got = Find-ForeignForegroundWindow -Probe { $c.Info }
    $ok = [bool]($got -and $got.Summary -eq $c.Expect -and -not ($got.PSObject.Properties.Name -contains 'Title'))
    Check "dirty-$($c.Name)" $ok $(if ($got) { $got.Summary } else { 'none' })
}

# 3. 实调用 Win32 前台探测不抛异常
$liveOk = $true; $liveNote = 'ok'
try { $null = Find-ForeignForegroundWindow } catch { $liveOk = $false; $liveNote = $_.Exception.Message }
Check 'live-win32-probe-runs' $liveOk $liveNote

# 4. 受管 PID 在 finally 关掉并核对已退出（含 StartTime 防误判校验）
$disposable = $null; $startTicks = 0L
try {
    $disposable = Start-Process powershell.exe -ArgumentList '-NoProfile -NonInteractive -Command "Start-Sleep -Seconds 60"' -PassThru -WindowStyle Hidden
    $startTicks = $disposable.StartTime.ToUniversalTime().Ticks
    Check 'disposable-alive-before-stop' (-not (Test-TrackedPidExited $disposable.Id $startTicks)) "pid=$($disposable.Id)"
    Check 'disposable-start-mismatch-ignored' (Test-TrackedPidExited $disposable.Id ($startTicks + 1)) "pid=$($disposable.Id)"
    Stop-TrackedPid -ProcessId $disposable.Id -StartTimeTicks $startTicks -WaitMs 5000
    Check 'disposable-exited-after-stop' (Test-TrackedPidExited $disposable.Id $startTicks) "pid=$($disposable.Id)"
} finally {
    if ($disposable -and $startTicks -gt 0L -and -not (Test-TrackedPidExited $disposable.Id $startTicks)) {
        Stop-Process -Id $disposable.Id -Force -ErrorAction SilentlyContinue
    }
}

# 5. friend-path.ps1 退出码隔离：环境脏=2，产品失败=1，全过=0
$tmp = Join-Path ([IO.Path]::GetTempPath()) ('fp-preflight-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
$smokeTmp = Join-Path $tmp 'tools\smoke'
$evTmp = Join-Path $tmp 'evidence'
New-Item -ItemType Directory -Force $smokeTmp, $evTmp | Out-Null
try {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'friend-path.ps1') -Destination (Join-Path $smokeTmp 'friend-path.ps1')
    @'
param([string]$Script, [string[]]$ScriptArgs = @(), [int]$TimeoutSec = 600, [string]$LogFile)
$mode = $env:FP_TEST_MODE
$outDir = Split-Path -Parent $LogFile
if ($mode -eq 'dirty') {
    @{ dirty = $true; exitCode = 2; reason = '桌面存在非本轮拉起的前台窗口（Notepad pid=26044 class=Notepad）' } |
        ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 (Join-Path $outDir 'environment-dirty.json')
    exit 2
} elseif ($mode -eq 'product_fail') {
    @{ passed = 1; total = 7; steps = @(@{ Step = 1; Name = '启动'; Ok = $true; Why = 'ok' }, @{ Step = 2; Name = '上传'; Ok = $false; Why = 'fail' }) } |
        ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 (Join-Path $outDir 'result.json')
    exit 1
} else {
    @{ passed = 7; total = 7; steps = @(1..7 | ForEach-Object { @{ Step = $_; Name = "s$_"; Ok = $true; Why = 'ok' } }) } |
        ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 (Join-Path $outDir 'result.json')
    exit 0
}
'@ | Set-Content -Encoding utf8 (Join-Path $smokeTmp 'Run-Interactive.ps1')

    $fp = Join-Path $smokeTmp 'friend-path.ps1'
    foreach ($tc in @(
        @{ Mode = 'dirty'; ExpectExit = 2; Match = 'ENVIRONMENT_DIRTY:' },
        @{ Mode = 'product_fail'; ExpectExit = 1; Match = '1/7' },
        @{ Mode = 'pass'; ExpectExit = 0; Match = '7/7' }
    )) {
        $env:FP_TEST_MODE = $tc.Mode
        $outText = (& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $fp -Evidence $evTmp 2>&1 | Out-String)
        $code = $LASTEXITCODE
        Check "exitcode-$($tc.Mode)" ($code -eq $tc.ExpectExit -and $outText.Contains($tc.Match)) "exit=$code expected=$($tc.ExpectExit)"
    }
} finally {
    Remove-Item Env:FP_TEST_MODE -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}

$failed = @($checks | Where-Object { -not $_.Ok }).Count
"RESULT $($checks.Count - $failed)/$($checks.Count)"
exit $(if ($failed) { 1 } else { 0 })
