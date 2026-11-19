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

# 3. 实调用 Win32 前台探测、空闲时间与串流探测不抛异常
$liveOk = $true; $liveNote = 'ok'
try {
    $null = Find-ForeignForegroundWindow
    $liveIdleMs = [WD]::GetLastInputIdleMs()
    $null = Get-ActiveStreamingSession
    if ($liveIdleMs -lt 0) { throw "GetLastInputIdleMs returned $liveIdleMs" }
    $liveNote = "idleMs=$liveIdleMs"
} catch { $liveOk = $false; $liveNote = $_.Exception.Message }
Check 'live-win32-probe-runs' $liveOk $liveNote

# 3a. 桌面占人判定（空闲时间 + Apollo/Moonlight 串流）与真正脏窗口（其他 Coach MainKt、登记的测试遗留外窗）
$regTmp = Join-Path ([IO.Path]::GetTempPath()) ('fp-ext-reg-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force $regTmp | Out-Null
try {
    $edgeFg = { [pscustomobject]@{ Hwnd = 2230432L; Pid = 20664; Visible = $true; Cloaked = $false; Class = 'Chrome_WidgetWin_1'; Process = 'msedge' } }
    $termFg = { [pscustomobject]@{ Hwnd = 721576L; Pid = 8924; Visible = $true; Cloaked = $false; Class = 'CASCADIA_HOSTING_WINDOW_CLASS'; Process = 'WindowsTerminal' } }
    $noCoach = { @() }
    $noStream = { $null }
    $idle15m = { 900.0 }

    $stampTmp = "$regTmp-driver-input.json"

    # Edge 或 WindowsTerminal 在前台，只要键鼠空闲 >=10分钟 且无串流、无其他 Coach/遗留外窗 -> 放行
    $pfEdge = Test-DesktopPreflight -MinIdleSeconds 600 -RegistryDir $regTmp -DriverInputFile $stampTmp -IdleProbe $idle15m -StreamProbe $noStream -CoachWindowProbe $noCoach -ForegroundProbe $edgeFg
    Check 'preflight-allows-idle-edge-foreground' ($pfEdge.Clean -and $pfEdge.Foreground.Process -eq 'msedge') $pfEdge.Reason
    $pfTerm = Test-DesktopPreflight -MinIdleSeconds 600 -RegistryDir $regTmp -DriverInputFile $stampTmp -IdleProbe $idle15m -StreamProbe $noStream -CoachWindowProbe $noCoach -ForegroundProbe $termFg
    Check 'preflight-allows-idle-terminal-foreground' ($pfTerm.Clean -and $pfTerm.Foreground.Process -eq 'WindowsTerminal') $pfTerm.Reason

    # 近 10 分钟内有键鼠输入且无匹配驱动注入记录 -> 拒绝（即使前景是桌面壳）
    $pfRecentInput = Test-DesktopPreflight -MinIdleSeconds 600 -RegistryDir $regTmp -DriverInputFile $stampTmp -IdleProbe { 120.0 } -StreamProbe $noStream -CoachWindowProbe $noCoach
    Check 'preflight-rejects-recent-human-input' (-not $pfRecentInput.Clean -and $pfRecentInput.Reason -like '*120*') $pfRecentInput.Reason

    # 连跑两轮之间（驱动自己注入的 SendInput 距 GetLastInputInfo 最后输入 < 2 秒）-> 不算真人，两轮之间不拦
    $tBase = [datetime]::SpecifyKind([datetime]'2026-11-19T18:27:01', [DateTimeKind]::Utc)
    Record-DriverInputTimestamp -UtcTime $tBase.AddSeconds(-5.0) -StampFile $stampTmp
    $pfBackToBackImmediate = Test-DesktopPreflight -MinIdleSeconds 600 -RegistryDir $regTmp -DriverInputFile $stampTmp -NowProbe { $tBase } -IdleProbe { 5.0 } -StreamProbe $noStream -CoachWindowProbe $noCoach -ForegroundProbe $edgeFg
    Record-DriverInputTimestamp -UtcTime $tBase.AddSeconds(-525.6) -StampFile $stampTmp
    $pfBackToBackDelayed = Test-DesktopPreflight -MinIdleSeconds 600 -RegistryDir $regTmp -DriverInputFile $stampTmp -NowProbe { $tBase } -IdleProbe { 525.6 } -StreamProbe $noStream -CoachWindowProbe $noCoach -ForegroundProbe $edgeFg
    Check 'preflight-allows-back-to-back-driver-input' ($pfBackToBackImmediate.Clean -and $pfBackToBackDelayed.Clean) "imm=$($pfBackToBackImmediate.Reason) del=$($pfBackToBackDelayed.Reason)"

    # 驱动注入时间与系统最后输入相差 1.2 秒（< 2 秒阈值）-> 放行；真人输入相差 >= 2 秒 -> 拒绝；正在串流仍拒绝
    Record-DriverInputTimestamp -UtcTime $tBase.AddSeconds(-120.0) -StampFile $stampTmp
    $pfWithin2s = Test-DesktopPreflight -MinIdleSeconds 600 -RegistryDir $regTmp -DriverInputFile $stampTmp -NowProbe { $tBase } -IdleProbe { 121.2 } -StreamProbe $noStream -CoachWindowProbe $noCoach -ForegroundProbe $edgeFg
    Check 'preflight-allows-driver-input-within-2s' $pfWithin2s.Clean $pfWithin2s.Reason

    Record-DriverInputTimestamp -UtcTime $tBase.AddSeconds(-525.6) -StampFile $stampTmp
    $pfHumanAfterDriver = Test-DesktopPreflight -MinIdleSeconds 600 -RegistryDir $regTmp -DriverInputFile $stampTmp -NowProbe { $tBase } -IdleProbe { 30.0 } -StreamProbe $noStream -CoachWindowProbe $noCoach -ForegroundProbe $edgeFg
    Check 'preflight-rejects-human-input-outside-2s' (-not $pfHumanAfterDriver.Clean -and $pfHumanAfterDriver.Reason -like '*30*') $pfHumanAfterDriver.Reason

    $pfDriverStream = Test-DesktopPreflight -MinIdleSeconds 600 -RegistryDir $regTmp -DriverInputFile $stampTmp -NowProbe { $tBase } -IdleProbe { 525.6 } -StreamProbe { [pscustomobject]@{ Tool = 'Apollo/Sunshine'; Pid = 12640; Ports = @(47998); Summary = 'sunshine pid=12640 streaming ports=47998' } } -CoachWindowProbe $noCoach
    Check 'preflight-rejects-driver-input-when-streaming' (-not $pfDriverStream.Clean -and $pfDriverStream.Reason -like '*Apollo/Moonlight*') $pfDriverStream.Reason

    # GetLastInputInfo 读取失败（<0）-> 保守拒绝
    $pfUnreadableIdle = Test-DesktopPreflight -MinIdleSeconds 600 -RegistryDir $regTmp -DriverInputFile $stampTmp -IdleProbe { -1.0 } -StreamProbe $noStream -CoachWindowProbe $noCoach
    Check 'preflight-rejects-unreadable-idle' (-not $pfUnreadableIdle.Clean) $pfUnreadableIdle.Reason

    # 键鼠空闲但 Apollo/Moonlight 正在串流 -> 拒绝
    $pfStream = Test-DesktopPreflight -MinIdleSeconds 600 -RegistryDir $regTmp -DriverInputFile $stampTmp -IdleProbe $idle15m -StreamProbe { [pscustomobject]@{ Tool = 'Apollo/Sunshine'; Pid = 12640; Ports = @(47998, 48010); Summary = 'sunshine pid=12640 streaming ports=47998,48010' } } -CoachWindowProbe $noCoach
    Check 'preflight-rejects-active-stream' (-not $pfStream.Clean -and $pfStream.Reason -like '*Apollo/Moonlight*') $pfStream.Reason

    # 本轮之外的其他 Coach MainKt 窗口存在 -> 即使空闲无串流也算脏
    $pfCoach = Test-DesktopPreflight -MinIdleSeconds 600 -RegistryDir $regTmp -DriverInputFile $stampTmp -IdleProbe $idle15m -StreamProbe $noStream -CoachWindowProbe { @([pscustomobject]@{ Kind = 'coach-window'; Pid = 21116; Process = 'java'; Summary = 'coach-window java pid=21116' }) } -ForegroundProbe $edgeFg
    Check 'preflight-rejects-foreign-coach' (-not $pfCoach.Clean -and $pfCoach.DirtyWindows.Count -eq 1 -and $pfCoach.DirtyWindows[0].Pid -eq 21116) $pfCoach.Reason

    # 测试登记的遗留外窗（PID + StartTime 对得上）-> 算脏；死掉/复用后的过期登记自动清理并放行
    $leftover = $null; $leftTicks = 0L
    try {
        $leftover = Start-Process powershell.exe -ArgumentList '-NoProfile -NonInteractive -Command "Start-Sleep -Seconds 60"' -PassThru -WindowStyle Hidden
        $leftTicks = Get-TrackedProcessStartTicks $leftover
        $null = Register-ExternalWindowPid -ProcessId $leftover.Id -StartTimeTicks $leftTicks -Kind 'notepad' -RegistryDir $regTmp
        $pfLeftover = Test-DesktopPreflight -MinIdleSeconds 600 -RegistryDir $regTmp -DriverInputFile $stampTmp -IdleProbe $idle15m -StreamProbe $noStream -CoachWindowProbe $noCoach -ForegroundProbe $edgeFg
        Check 'preflight-rejects-registered-leftover' (-not $pfLeftover.Clean -and $pfLeftover.DirtyWindows.Count -eq 1 -and $pfLeftover.DirtyWindows[0].Pid -eq $leftover.Id) $pfLeftover.Reason

        # 伪造一条同 PID 但 StartTime 不匹配的过期登记：必须被判定为复用/已退并清掉文件
        Unregister-ExternalWindowPid -ProcessId $leftover.Id -StartTimeTicks $leftTicks -RegistryDir $regTmp
        $null = Register-ExternalWindowPid -ProcessId $leftover.Id -StartTimeTicks ($leftTicks + 1L) -Kind 'stale-reused' -RegistryDir $regTmp
        $pfStale = Test-DesktopPreflight -MinIdleSeconds 600 -RegistryDir $regTmp -DriverInputFile $stampTmp -IdleProbe $idle15m -StreamProbe $noStream -CoachWindowProbe $noCoach -ForegroundProbe $edgeFg
        Check 'preflight-prunes-mismatched-registration' ($pfStale.Clean -and @(Get-ChildItem $regTmp -Filter '*.json').Count -eq 0) "files=$(@(Get-ChildItem $regTmp -Filter '*.json').Count)"
    } finally {
        if ($leftover -and $leftTicks -gt 0L) {
            Stop-TrackedPid -ProcessId $leftover.Id -StartTimeTicks $leftTicks -WaitMs 5000
            Unregister-ExternalWindowPid -ProcessId $leftover.Id -StartTimeTicks $leftTicks -RegistryDir $regTmp
        }
    }
} finally {
    Remove-Item -LiteralPath "$regTmp-driver-input.json" -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $regTmp -ErrorAction SilentlyContinue
}

# 4. 受管 PID 在 finally 关掉并核对已退出（含 StartTime 防误判校验）
# 收尾只走 Stop-TrackedPid：身份确认才停 1 次；已退出或 PID 被复用 0 次；启动时间读不到时 0 次并报 CLEANUP_UNVERIFIED。
function Invoke-TrackedDisposable([scriptblock]$Launch, [scriptblock]$Body, [int]$WaitMs = 5000) {
    $proc = $null; $ticks = 0L
    try {
        $proc = & $Launch
        $ticks = Get-TrackedProcessStartTicks $proc
        & $Body $proc $ticks
    } finally {
        if ($proc) { Stop-TrackedPid -ProcessId $proc.Id -StartTimeTicks $ticks -WaitMs $WaitMs }
    }
}
Invoke-TrackedDisposable -Launch {
    Start-Process powershell.exe -ArgumentList '-NoProfile -NonInteractive -Command "Start-Sleep -Seconds 60"' -PassThru -WindowStyle Hidden
} -Body {
    param($disposable, $startTicks)
    Check 'disposable-start-recorded' ($startTicks -gt 0L) "pid=$($disposable.Id)"
    Check 'disposable-alive-before-stop' (-not (Test-TrackedPidExited $disposable.Id $startTicks)) "pid=$($disposable.Id)"
    Check 'disposable-start-mismatch-ignored' (Test-TrackedPidExited $disposable.Id ($startTicks + 1)) "pid=$($disposable.Id)"
    Stop-TrackedPid -ProcessId $disposable.Id -StartTimeTicks $startTicks -WaitMs 5000
    Check 'disposable-exited-after-stop' (Test-TrackedPidExited $disposable.Id $startTicks) "pid=$($disposable.Id)"
}

# 4a. 整个异常退出路径（替身进程，不发真实停止）：正文里的停止和 finally 收尾合计的停止请求数
$script:TeardownSpy = $null
function New-SpyProc($startTime, [switch]$Unreadable) {
    $p = [pscustomobject]@{ Id = 42425 }
    if ($Unreadable) { $p | Add-Member ScriptProperty StartTime { throw 'Access is denied' } }
    else { $p | Add-Member NoteProperty StartTime $startTime }
    return $p
}
function Run-TeardownCase([string]$name, $launchProc, [scriptblock]$Body, [int]$expectStops, [string]$expectErr) {
    $script:TeardownSpy = @{ proc = $launchProc; stops = 0; killed = $false }
    function global:Get-Process([int]$Id, [string]$ErrorAction) {
        if ($Id -eq 42425 -and -not $script:TeardownSpy.killed) { return $script:TeardownSpy.proc }
        return $null
    }
    function global:Stop-Process([int]$Id, [switch]$Force, [string]$ErrorAction) {
        $script:TeardownSpy.stops++
        $script:TeardownSpy.killed = $true
    }
    $errMsg = ''
    try {
        Invoke-TrackedDisposable -Launch { $script:TeardownSpy.proc } -Body $Body -WaitMs 500
    } catch {
        $errMsg = $_.Exception.Message
    } finally {
        Remove-Item Function:\Get-Process -ErrorAction SilentlyContinue
        Remove-Item Function:\Stop-Process -ErrorAction SilentlyContinue
    }
    $ok = ($script:TeardownSpy.stops -eq $expectStops) -and $(if ($expectErr) { $errMsg -like "$expectErr*" } else { -not $errMsg })
    Check "teardown-$name" $ok "stops=$($script:TeardownSpy.stops)/$expectStops err=$errMsg"
}
$t0 = [datetime]::SpecifyKind([datetime]'2026-11-19T16:00:00', [DateTimeKind]::Utc)
$helperStop = { param($p, $t) Stop-TrackedPid -ProcessId $p.Id -StartTimeTicks $t -WaitMs 500 }
# 启动时就读不到：正文停止报错 → finally 再核对，仍 0 次停止，报 CLEANUP_UNVERIFIED
Run-TeardownCase 'unreadable-at-launch' (New-SpyProc -Unreadable) $helperStop 0 'CLEANUP_UNVERIFIED:'
# 记录到了，之后读不到（QA 替身场景）：正文和 finally 合计 0 次停止
Run-TeardownCase 'unreadable-later' (New-SpyProc $t0) {
    param($p, $t)
    $script:TeardownSpy.proc = New-SpyProc -Unreadable
    & $helperStop $p $t
} 0 'CLEANUP_UNVERIFIED:'
# 正文中途出错、身份可确认：finally 停 1 次，原错误照常抛出
Run-TeardownCase 'body-throws-verified' (New-SpyProc $t0) { throw 'BODY_FAILED' } 1 'BODY_FAILED'
# 正文已停、finally 不重复停
Run-TeardownCase 'helper-stopped-verified' (New-SpyProc $t0) $helperStop 1 ''

# 4b. 进程身份核对防误杀（替身验证：仅双方启动时间一致时停 1 次；不同或任一缺失均 0 次停止，缺失时间报 CLEANUP_UNVERIFIED）
$script:SpyState = $null
function Run-PidIdentityCase([string]$name, $mockProc, [long]$recordedTicks, [int]$expectStops, [bool]$expectThrow) {
    $script:SpyState = @{ proc = $mockProc; stops = 0; killed = $false }
    function global:Get-Process([int]$Id, [string]$ErrorAction) {
        if ($Id -eq 42424 -and -not $script:SpyState.killed) { return $script:SpyState.proc }
        return $null
    }
    function global:Stop-Process([int]$Id, [switch]$Force, [string]$ErrorAction) {
        $script:SpyState.stops++
        $script:SpyState.killed = $true
    }
    $threw = $false; $errMsg = ''
    try {
        Stop-TrackedPid -ProcessId 42424 -StartTimeTicks $recordedTicks -WaitMs 500
    } catch {
        $threw = $true
        $errMsg = $_.Exception.Message
    } finally {
        Remove-Item Function:\Get-Process -ErrorAction SilentlyContinue
        Remove-Item Function:\Stop-Process -ErrorAction SilentlyContinue
    }
    $ok = ($script:SpyState.stops -eq $expectStops) -and ($threw -eq $expectThrow) -and (-not $expectThrow -or $errMsg -like 'CLEANUP_UNVERIFIED:*')
    Check "pid-identity-$name" $ok "stops=$($script:SpyState.stops)/$expectStops threw=$threw/$expectThrow err=$errMsg"
}
$t1 = [datetime]::SpecifyKind([datetime]'2026-11-19T17:00:00', [DateTimeKind]::Utc)
$t2 = $t1.AddSeconds(10)
Run-PidIdentityCase 'both-match' ([pscustomobject]@{ Id = 42424; StartTime = $t1 }) $t1.Ticks 1 $false
Run-PidIdentityCase 'different-start' ([pscustomobject]@{ Id = 42424; StartTime = $t2 }) $t1.Ticks 0 $false
Run-PidIdentityCase 'missing-recorded-start' ([pscustomobject]@{ Id = 42424; StartTime = $t1 }) 0L 0 $true
Run-PidIdentityCase 'missing-observed-start' ([pscustomobject]@{ Id = 42424; StartTime = $null }) $t1.Ticks 0 $true

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
@{ timeoutSec = $TimeoutSec; script = $Script; scriptArgs = ($ScriptArgs -join ' ') } |
    ConvertTo-Json -Depth 3 | Set-Content -Encoding utf8 (Join-Path $outDir 'forwarded.json')
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

    # 6. friend-path.ps1 超时参数透传 + 默认外层 TimeoutSec = 600 + BuildTimeoutSec
    $env:FP_TEST_MODE = 'pass'
    $evFwd = Join-Path $tmp 'ev-fwd'
    New-Item -ItemType Directory -Force $evFwd | Out-Null
    $null = (& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $fp -Evidence $evFwd 2>&1 | Out-String)
    $fwdDefault = Get-Content -LiteralPath (Get-ChildItem $evFwd -Recurse -Filter 'forwarded.json' | Select-Object -First 1).FullName -Raw -Encoding UTF8 | ConvertFrom-Json
    Check 'forward-default-timeouts' ($LASTEXITCODE -eq 0 -and $fwdDefault.timeoutSec -eq 1500 -and $fwdDefault.scriptArgs -match '-StartTimeoutSec 150\b' -and $fwdDefault.scriptArgs -match '-BuildTimeoutSec 900\b') "timeout=$($fwdDefault.timeoutSec) args=$($fwdDefault.scriptArgs)"

    $evCustom = Join-Path $tmp 'ev-custom'
    New-Item -ItemType Directory -Force $evCustom | Out-Null
    $null = (& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $fp -Evidence $evCustom -StartTimeoutSec 90 -BuildTimeoutSec 420 2>&1 | Out-String)
    $fwdCustom = Get-Content -LiteralPath (Get-ChildItem $evCustom -Recurse -Filter 'forwarded.json' | Select-Object -First 1).FullName -Raw -Encoding UTF8 | ConvertFrom-Json
    Check 'forward-custom-timeouts' ($LASTEXITCODE -eq 0 -and $fwdCustom.timeoutSec -eq 1020 -and $fwdCustom.scriptArgs -match '-StartTimeoutSec 90\b' -and $fwdCustom.scriptArgs -match '-BuildTimeoutSec 420\b') "timeout=$($fwdCustom.timeoutSec) args=$($fwdCustom.scriptArgs)"

    $evExplicit = Join-Path $tmp 'ev-explicit'
    New-Item -ItemType Directory -Force $evExplicit | Out-Null
    $null = (& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $fp -Evidence $evExplicit -BuildTimeoutSec 420 -TimeoutSec 777 2>&1 | Out-String)
    $fwdExplicit = Get-Content -LiteralPath (Get-ChildItem $evExplicit -Recurse -Filter 'forwarded.json' | Select-Object -First 1).FullName -Raw -Encoding UTF8 | ConvertFrom-Json
    Check 'forward-explicit-outer-timeout' ($LASTEXITCODE -eq 0 -and $fwdExplicit.timeoutSec -eq 777) "timeout=$($fwdExplicit.timeoutSec)"

    # 7. friend-path-worker 编译超时与编译失败分流（桩 gradlew.bat，不真实编译）
    $buildRepo = Join-Path $tmp 'repo-stub'
    $buildSmoke = Join-Path $buildRepo 'tools\smoke'
    $evBuild = Join-Path $tmp 'ev-build'
    New-Item -ItemType Directory -Force $buildSmoke, $evBuild | Out-Null
    foreach ($name in @('friend-path.ps1', 'friend-path-worker.ps1', 'Win-Drive.ps1', 'Smoke-Memory.ps1', 'Lesson-Range.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $buildSmoke $name)
    }
    @'
param([string]$Script, [string[]]$ScriptArgs = @(), [int]$TimeoutSec = 600, [string]$LogFile)
$global:LASTEXITCODE = 0
$cmd = "& '" + $Script.Replace("'", "''") + "' " + ($ScriptArgs -join ' ')
Invoke-Expression $cmd *>&1 | Out-File -Encoding utf8 $LogFile
exit ([int]$LASTEXITCODE)
'@ | Set-Content -Encoding utf8 (Join-Path $buildSmoke 'Run-Interactive.ps1')

    $fpBuild = Join-Path $buildSmoke 'friend-path.ps1'
    $stubTag = 'coach-build-stub-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
    @"
@echo off
echo STUB_BUILD_ARGS %*
powershell -NoProfile -Command "Start-Sleep -Seconds 30 # $stubTag"
exit /b 0
"@ | Set-Content -Encoding ascii (Join-Path $buildRepo 'gradlew.bat')
    $evTimeout = Join-Path $evBuild 'timeout'
    $outTimeout = (& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $fpBuild -Evidence $evTimeout -BuildTimeoutSec 2 -StartTimeoutSec 15 2>&1 | Out-String)
    $codeTimeout = $LASTEXITCODE
    $runDirTimeout = Get-ChildItem -LiteralPath $evTimeout -Directory | Select-Object -First 1
    $dirtyTimeout = if ($runDirTimeout -and (Test-Path -LiteralPath (Join-Path $runDirTimeout.FullName 'environment-dirty.json'))) {
        Get-Content -LiteralPath (Join-Path $runDirTimeout.FullName 'environment-dirty.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    } else { $null }
    $logTimeout = if ($runDirTimeout -and (Test-Path -LiteralPath (Join-Path $runDirTimeout.FullName 'build.log'))) {
        Get-Content -LiteralPath (Join-Path $runDirTimeout.FullName 'build.log') -Raw -Encoding UTF8
    } else { '' }
    $strayStub = @(Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" | Where-Object { $_.CommandLine -like "*$stubTag*" }).Count
    Check 'build-timeout-dirty-exit2' (
        $codeTimeout -eq 2 -and
        $outTimeout -match 'ENVIRONMENT_DIRTY:\s*build-timeout' -and
        $dirtyTimeout -and $dirtyTimeout.reason -eq 'build-timeout' -and
        -not (Test-Path -LiteralPath (Join-Path $runDirTimeout.FullName 'result.json')) -and
        $logTimeout -match 'STUB_BUILD_ARGS :desktopApp:classes\b' -and
        $logTimeout -match 'timedOut=True' -and
        $strayStub -eq 0
    ) "code=$codeTimeout reason=$($dirtyTimeout.reason) stray=$strayStub"

    @"
@echo off
echo STUB_BUILD_ARGS %*
echo STUB_COMPILE_ERROR>&2
exit /b 7
"@ | Set-Content -Encoding ascii (Join-Path $buildRepo 'gradlew.bat')
    $evFail = Join-Path $evBuild 'fail'
    $outFail = (& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $fpBuild -Evidence $evFail -BuildTimeoutSec 30 -StartTimeoutSec 15 2>&1 | Out-String)
    $codeFail = $LASTEXITCODE
    $runDirFail = Get-ChildItem -LiteralPath $evFail -Directory | Select-Object -First 1
    $resFail = if ($runDirFail -and (Test-Path -LiteralPath (Join-Path $runDirFail.FullName 'result.json'))) {
        Get-Content -LiteralPath (Join-Path $runDirFail.FullName 'result.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    } else { $null }
    $logFail = if ($runDirFail -and (Test-Path -LiteralPath (Join-Path $runDirFail.FullName 'build.log'))) {
        Get-Content -LiteralPath (Join-Path $runDirFail.FullName 'build.log') -Raw -Encoding UTF8
    } else { '' }
    Check 'build-error-fail-step1' (
        $codeFail -eq 1 -and
        $outFail -match '0/7' -and
        -not (Test-Path -LiteralPath (Join-Path $runDirFail.FullName 'environment-dirty.json')) -and
        $resFail -and $resFail.passed -eq 0 -and $resFail.steps.Count -eq 1 -and
        $resFail.steps[0].Step -eq 1 -and (-not $resFail.steps[0].Ok) -and
        $resFail.steps[0].Why -eq '构建失败（见 build.log）' -and
        $logFail -match 'STUB_COMPILE_ERROR' -and $logFail -match 'exitCode=7'
    ) "code=$codeFail passed=$($resFail.passed) why=$($resFail.steps[0].Why)"

    @"
@echo off
if "%1"==":desktopApp:classes" (
    echo STUB_BUILD_ARGS %*
    powershell -NoProfile -Command "Start-Sleep -Seconds 3"
    exit /b 0
)
echo STUB_RUN_ARGS %*
exit /b 0
"@ | Set-Content -Encoding ascii (Join-Path $buildRepo 'gradlew.bat')
    $evOrder = Join-Path $evBuild 'order'
    $null = (& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $fpBuild -Evidence $evOrder -BuildTimeoutSec 15 -StartTimeoutSec 2 2>&1 | Out-String)
    $codeOrder = $LASTEXITCODE
    $runDirOrder = Get-ChildItem -LiteralPath $evOrder -Directory | Select-Object -First 1
    $logOrder = if ($runDirOrder -and (Test-Path -LiteralPath (Join-Path $runDirOrder.FullName 'build.log'))) {
        Get-Content -LiteralPath (Join-Path $runDirOrder.FullName 'build.log') -Raw -Encoding UTF8
    } else { '' }
    $clientOutOrder = if ($runDirOrder -and (Test-Path -LiteralPath (Join-Path $runDirOrder.FullName 'client.out.log'))) {
        Get-Content -LiteralPath (Join-Path $runDirOrder.FullName 'client.out.log') -Raw -Encoding UTF8
    } else { '' }
    $resOrder = if ($runDirOrder -and (Test-Path -LiteralPath (Join-Path $runDirOrder.FullName 'result.json'))) {
        Get-Content -LiteralPath (Join-Path $runDirOrder.FullName 'result.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    } else { $null }
    Check 'split-build-exceeds-start-timeout' (
        $codeOrder -eq 1 -and
        $logOrder -match 'STUB_BUILD_ARGS :desktopApp:classes' -and
        $logOrder -match 'exitCode=0\b' -and
        $logOrder -match 'timedOut=False' -and
        $clientOutOrder -match 'STUB_RUN_ARGS :desktopApp:run' -and
        $resOrder -and $resOrder.passed -eq 0 -and
        $resOrder.steps[0].Why -eq '客户端窗口 2 秒内没有出现（见 client.err.log）'
    ) "code=$codeOrder why=$($resOrder.steps[0].Why)"

    # 8. 重试用完分流：外部进程占前台写 foreground-contention 并退出 2（保留已通过步骤）；本轮 Coach 自窗照旧记 Fail
    $prevAppPid = $script:AppPid
    try {
        $script:AppPid = 55555
        $extErr = Resolve-ForegroundRetryTimeout -LastFocusLoss 'WIN_DRIVE_FOCUS_LOST: activation declined by Windows' -ForegroundProbe {
            [pscustomobject]@{ Hwnd = 683201L; Pid = 6832; Process = 'java'; Class = 'SunAwtFrame' }
        }
        Check 'retry-exhausted-external-throws-contention' (
            $extErr -and
            $extErr.Message -like 'WIN_DRIVE_FOREGROUND_CONTENTION:*' -and
            $extErr.Data['ForegroundPid'] -eq 6832 -and
            $extErr.Data['ForegroundProcess'] -eq 'java'
        ) $extErr.Message

        $ownErr = Resolve-ForegroundRetryTimeout -LastFocusLoss 'WIN_DRIVE_FOCUS_LOST: our client is no longer foreground' -ForegroundProbe {
            [pscustomobject]@{ Hwnd = 555502L; Pid = 55555; Process = 'java'; Class = 'SunAwtDialog' }
        }
        Check 'retry-exhausted-own-window-stays-timeout' (
            $ownErr -and
            $ownErr.Message -like 'WIN_DRIVE_TIMEOUT:*' -and
            $ownErr.Message -notlike 'WIN_DRIVE_FOREGROUND_CONTENTION*' -and
            $ownErr.Message -match 'this run'
        ) $ownErr.Message

        $zeroErr = Resolve-ForegroundRetryTimeout -LastFocusLoss 'WIN_DRIVE_FOCUS_LOST: activation declined by Windows' -ForegroundProbe {
            [pscustomobject]@{ Hwnd = 0L; Pid = 0; Process = ''; Class = '' }
        }
        Check 'retry-exhausted-zero-pid-stays-timeout' (
            $zeroErr -and
            $zeroErr.Message -like 'WIN_DRIVE_TIMEOUT:*'
        ) $zeroErr.Message
    } finally {
        $script:AppPid = $prevAppPid
    }

    $fgRepo = Join-Path $tmp 'repo-fg'
    $fgSmoke = Join-Path $fgRepo 'tools\smoke'
    $evFg = Join-Path $tmp 'ev-fg'
    New-Item -ItemType Directory -Force $fgSmoke, $evFg | Out-Null
    foreach ($name in @('friend-path.ps1', 'friend-path-worker.ps1', 'Win-Drive.ps1', 'Smoke-Memory.ps1', 'Lesson-Range.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $fgSmoke $name)
    }
    Copy-Item -LiteralPath (Join-Path $buildSmoke 'Run-Interactive.ps1') -Destination (Join-Path $fgSmoke 'Run-Interactive.ps1')
    @"
@echo off
if "%1"==":desktopApp:classes" (
    echo STUB_FG_BUILD %*
    exit /b 0
)
echo STUB_FG_RUN %*
powershell -NoProfile -Command "Start-Sleep -Seconds 30"
exit /b 0
"@ | Set-Content -Encoding ascii (Join-Path $fgRepo 'gradlew.bat')
    @'
function Test-DesktopPreflight { [pscustomobject]@{ Clean = $true; Reason = ''; DirtyWindows = @(); ActiveUser = $null; IdleSeconds = 900.0; Foreground = $null } }
function Mark-ExistingClients { }
function Stop-MyClient { $script:AppPid = 0; $script:AppStartTicks = 0L }
function Set-SmokeMemoryClient($lease, [int]$appPid) { }
function Find-CoachWindow { $script:AppPid = 55555; $script:AppStartTicks = 0L; $script:Win = [IntPtr]12345; return $true }
function Save-Shot([string]$path, [IntPtr]$Window = [IntPtr]::Zero) {
    if ([IO.Path]::GetFileName($path) -like '2-*') {
        if ($env:FP_FG_TEST_CASE -eq 'external') {
            throw (Resolve-ForegroundRetryTimeout -LastFocusLoss 'WIN_DRIVE_FOCUS_LOST: activation declined by Windows' -ForegroundProbe {
                [pscustomobject]@{ Hwnd = 683201L; Pid = 6832; Process = 'java'; Class = 'SunAwtFrame' }
            })
        }
        throw (Resolve-ForegroundRetryTimeout -LastFocusLoss 'WIN_DRIVE_FOCUS_LOST: our client is no longer foreground' -ForegroundProbe {
            [pscustomobject]@{ Hwnd = 555502L; Pid = 55555; Process = 'java'; Class = 'SunAwtDialog' }
        })
    }
    Set-Content -LiteralPath $path -Value 'stub' -Encoding ascii
}
function Read-Screen([string]$png) {
    @([pscustomobject]@{
        Text = '书架'
        Words = @([pscustomobject]@{
            Text = '书架'
            BoundingRect = [pscustomobject]@{ X = 10; Y = 10; Width = 40; Height = 20 }
        })
    })
}
'@ | Add-Content -Encoding utf8 (Join-Path $fgSmoke 'Lesson-Range.ps1')

    $fpFg = Join-Path $fgSmoke 'friend-path.ps1'
    $env:FP_FG_TEST_CASE = 'external'
    $evFgExt = Join-Path $evFg 'ext'
    $outFgExt = (& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $fpFg -Evidence $evFgExt -BuildTimeoutSec 30 -StartTimeoutSec 15 2>&1 | Out-String)
    $codeFgExt = $LASTEXITCODE
    $runDirFgExt = Get-ChildItem -LiteralPath $evFgExt -Directory | Select-Object -First 1
    $dirtyFgExt = if ($runDirFgExt -and (Test-Path -LiteralPath (Join-Path $runDirFgExt.FullName 'environment-dirty.json'))) {
        Get-Content -LiteralPath (Join-Path $runDirFgExt.FullName 'environment-dirty.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    } else { $null }
    $resFgExt = if ($runDirFgExt -and (Test-Path -LiteralPath (Join-Path $runDirFgExt.FullName 'result.json'))) {
        Get-Content -LiteralPath (Join-Path $runDirFgExt.FullName 'result.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    } else { $null }
    Check 'foreground-contention-external-exit2-keeps-passed' (
        $codeFgExt -eq 2 -and
        $outFgExt -match 'ENVIRONMENT_DIRTY:\s*foreground-contention' -and
        $outFgExt -match '不计 N/7' -and
        $dirtyFgExt -and $dirtyFgExt.reason -eq 'foreground-contention' -and
        $dirtyFgExt.foregroundPid -eq 6832 -and $dirtyFgExt.foregroundProcess -eq 'java' -and
        $dirtyFgExt.step -eq 2 -and
        $resFgExt -and $resFgExt.passed -eq 1 -and $resFgExt.stoppedAtStep -eq 2 -and
        @($resFgExt.steps).Count -eq 1 -and $resFgExt.steps[0].Step -eq 1 -and $resFgExt.steps[0].Ok
    ) "code=$codeFgExt reason=$($dirtyFgExt.reason) pid=$($dirtyFgExt.foregroundPid) passed=$($resFgExt.passed) stopped=$($resFgExt.stoppedAtStep)"

    $env:FP_FG_TEST_CASE = 'own-window'
    $evFgOwn = Join-Path $evFg 'own'
    $outFgOwn = (& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $fpFg -Evidence $evFgOwn -BuildTimeoutSec 30 -StartTimeoutSec 15 2>&1 | Out-String)
    $codeFgOwn = $LASTEXITCODE
    $runDirFgOwn = Get-ChildItem -LiteralPath $evFgOwn -Directory | Select-Object -First 1
    $resFgOwn = if ($runDirFgOwn -and (Test-Path -LiteralPath (Join-Path $runDirFgOwn.FullName 'result.json'))) {
        Get-Content -LiteralPath (Join-Path $runDirFgOwn.FullName 'result.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    } else { $null }
    Check 'foreground-contention-own-window-fails' (
        $codeFgOwn -eq 1 -and
        $outFgOwn -match '1/7' -and
        -not (Test-Path -LiteralPath (Join-Path $runDirFgOwn.FullName 'environment-dirty.json')) -and
        $resFgOwn -and $resFgOwn.passed -eq 1 -and @($resFgOwn.steps).Count -eq 2 -and
        $resFgOwn.steps[0].Step -eq 1 -and $resFgOwn.steps[0].Ok -and
        $resFgOwn.steps[1].Step -eq 2 -and (-not $resFgOwn.steps[1].Ok) -and
        $resFgOwn.steps[1].Why -like '*WIN_DRIVE_TIMEOUT:*'
    ) "code=$codeFgOwn passed=$($resFgOwn.passed) steps=$(@($resFgOwn.steps).Count) why=$($resFgOwn.steps[1].Why)"
} finally {
    Remove-Item Env:FP_FG_TEST_CASE -ErrorAction SilentlyContinue
    Remove-Item Env:FP_TEST_MODE -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}

$failed = @($checks | Where-Object { -not $_.Ok }).Count
"RESULT $($checks.Count - $failed)/$($checks.Count)"
exit $(if ($failed) { 1 } else { 0 })
