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

    # 按 11-19 20:48 主会话决定改期望：近 10 分钟内有键鼠输入且无串流 -> 放行，IdleSeconds 仅记作证据
    $pfRecentInput = Test-DesktopPreflight -MinIdleSeconds 600 -RegistryDir $regTmp -DriverInputFile $stampTmp -IdleProbe { 120.0 } -StreamProbe $noStream -CoachWindowProbe $noCoach
    Check 'preflight-rejects-recent-human-input' ($pfRecentInput.Clean -and $pfRecentInput.IdleSeconds -eq 120.0 -and (-not $pfRecentInput.DriverInputMatched)) "clean=$($pfRecentInput.Clean) idle=$($pfRecentInput.IdleSeconds)"

    # 连跑两轮之间（驱动自己注入的 SendInput 距 GetLastInputInfo 最后输入 < 2 秒）-> 放行且标记 DriverInputMatched 证据
    $tBase = [datetime]::SpecifyKind([datetime]'2026-11-19T18:27:01', [DateTimeKind]::Utc)
    Record-DriverInputTimestamp -UtcTime $tBase.AddSeconds(-5.0) -StampFile $stampTmp
    $pfBackToBackImmediate = Test-DesktopPreflight -MinIdleSeconds 600 -RegistryDir $regTmp -DriverInputFile $stampTmp -NowProbe { $tBase } -IdleProbe { 5.0 } -StreamProbe $noStream -CoachWindowProbe $noCoach -ForegroundProbe $edgeFg
    Record-DriverInputTimestamp -UtcTime $tBase.AddSeconds(-525.6) -StampFile $stampTmp
    $pfBackToBackDelayed = Test-DesktopPreflight -MinIdleSeconds 600 -RegistryDir $regTmp -DriverInputFile $stampTmp -NowProbe { $tBase } -IdleProbe { 525.6 } -StreamProbe $noStream -CoachWindowProbe $noCoach -ForegroundProbe $edgeFg
    Check 'preflight-allows-back-to-back-driver-input' ($pfBackToBackImmediate.Clean -and $pfBackToBackDelayed.Clean -and $pfBackToBackImmediate.DriverInputMatched -and $pfBackToBackDelayed.DriverInputMatched) "imm=$($pfBackToBackImmediate.IdleSeconds) del=$($pfBackToBackDelayed.IdleSeconds)"

    # 按 11-19 20:48 主会话决定改期望：驱动注入时间比对仅作证据（DriverInputMatched），30 秒人工输入也不再拦人；正在串流仍拒绝
    Record-DriverInputTimestamp -UtcTime $tBase.AddSeconds(-120.0) -StampFile $stampTmp
    $pfWithin2s = Test-DesktopPreflight -MinIdleSeconds 600 -RegistryDir $regTmp -DriverInputFile $stampTmp -NowProbe { $tBase } -IdleProbe { 121.2 } -StreamProbe $noStream -CoachWindowProbe $noCoach -ForegroundProbe $edgeFg
    Check 'preflight-allows-driver-input-within-2s' ($pfWithin2s.Clean -and $pfWithin2s.DriverInputMatched) $pfWithin2s.Reason

    Record-DriverInputTimestamp -UtcTime $tBase.AddSeconds(-525.6) -StampFile $stampTmp
    $pfHumanAfterDriver = Test-DesktopPreflight -MinIdleSeconds 600 -RegistryDir $regTmp -DriverInputFile $stampTmp -NowProbe { $tBase } -IdleProbe { 30.0 } -StreamProbe $noStream -CoachWindowProbe $noCoach -ForegroundProbe $edgeFg
    Check 'preflight-rejects-human-input-outside-2s' ($pfHumanAfterDriver.Clean -and $pfHumanAfterDriver.IdleSeconds -eq 30.0 -and (-not $pfHumanAfterDriver.DriverInputMatched)) "clean=$($pfHumanAfterDriver.Clean) idle=$($pfHumanAfterDriver.IdleSeconds)"

    $pfDriverStream = Test-DesktopPreflight -MinIdleSeconds 600 -RegistryDir $regTmp -DriverInputFile $stampTmp -NowProbe { $tBase } -IdleProbe { 525.6 } -StreamProbe { [pscustomobject]@{ Tool = 'Apollo/Sunshine'; Pid = 12640; Ports = @(47998); Summary = 'sunshine pid=12640 streaming ports=47998' } } -CoachWindowProbe $noCoach
    Check 'preflight-rejects-driver-input-when-streaming' (-not $pfDriverStream.Clean -and $pfDriverStream.Reason -like '*Apollo/Moonlight*') $pfDriverStream.Reason

    # 按 11-19 20:48 主会话决定改期望：GetLastInputInfo 读取失败（<0）不拦人，IdleSeconds 记 -1 作证据
    $pfUnreadableIdle = Test-DesktopPreflight -MinIdleSeconds 600 -RegistryDir $regTmp -DriverInputFile $stampTmp -IdleProbe { -1.0 } -StreamProbe $noStream -CoachWindowProbe $noCoach
    Check 'preflight-rejects-unreadable-idle' ($pfUnreadableIdle.Clean -and $pfUnreadableIdle.IdleSeconds -eq -1.0) "clean=$($pfUnreadableIdle.Clean) idle=$($pfUnreadableIdle.IdleSeconds)"

    # 键鼠空闲但 Apollo/Moonlight 正在串流 -> 拒绝
    $pfStream = Test-DesktopPreflight -MinIdleSeconds 600 -RegistryDir $regTmp -DriverInputFile $stampTmp -IdleProbe $idle15m -StreamProbe { [pscustomobject]@{ Tool = 'Apollo/Sunshine'; Pid = 12640; Ports = @(47998, 48010); Summary = 'sunshine pid=12640 streaming ports=47998,48010' } } -CoachWindowProbe $noCoach
    Check 'preflight-rejects-active-stream' (-not $pfStream.Clean -and $pfStream.Reason -like '*Apollo/Moonlight*') $pfStream.Reason

    # 3b. Apollo 端口 + sunshine.log 双依据串流检测（合成日志用 192.0.2.x，不写真实设备名）
    $apolloStartLocal = [datetime]'2026-11-19 20:00:00'
    $apolloHost = { @([pscustomobject]@{ Id = 12640; ProcessName = 'sunshine'; StartTime = $apolloStartLocal }) }
    $noPorts = { [pscustomobject]@{ Udp = @(); Tcp = @() } }
    $apolloLog = Join-Path $regTmp 'sunshine.log'
    try {
        # Apollo 没在跑 -> 放行
        $pfNoApollo = Test-DesktopPreflight -RegistryDir $regTmp -DriverInputFile $stampTmp -IdleProbe { 5.0 } -HostProbe { @() } -PortProbe $noPorts -ApolloLogPath (Join-Path $regTmp 'nonexistent.log') -CoachWindowProbe $noCoach -ForegroundProbe $edgeFg
        Check 'stream-apollo-not-running' $pfNoApollo.Clean $pfNoApollo.Reason

        # 有连接、没断开（在当前进程启动之后）-> 占用
        @(
            '[2026-11-19 20:00:01.100]: Info: Apollo version: 0.4.6'
            '[2026-11-19 20:01:10.250]: Info: New streaming session started [active sessions: 1]'
            '[2026-11-19 20:01:10.500]: Info: Expecting incoming session connections from 192.0.2.10'
            '[2026-11-19 20:01:11.012]: Info: CLIENT CONNECTED'
        ) | Set-Content -Encoding utf8 $apolloLog
        $pfLogConn = Test-DesktopPreflight -RegistryDir $regTmp -DriverInputFile $stampTmp -IdleProbe $idle15m -HostProbe $apolloHost -PortProbe $noPorts -ApolloLogPath $apolloLog -CoachWindowProbe $noCoach -ForegroundProbe $edgeFg
        Check 'stream-log-connected-no-disconnect' (-not $pfLogConn.Clean -and $pfLogConn.ActiveUser.Streaming.Source -eq 'log') $pfLogConn.Reason

        # 连接后又断开 -> 放行
        @(
            '[2026-11-19 20:00:01.100]: Info: Apollo version: 0.4.6'
            '[2026-11-19 20:01:11.012]: Info: CLIENT CONNECTED'
            '[2026-11-19 20:05:30.400]: Info: CLIENT DISCONNECTED'
        ) | Set-Content -Encoding utf8 $apolloLog
        $pfLogDisc = Test-DesktopPreflight -RegistryDir $regTmp -DriverInputFile $stampTmp -IdleProbe { 3.0 } -HostProbe $apolloHost -PortProbe $noPorts -ApolloLogPath $apolloLog -CoachWindowProbe $noCoach -ForegroundProbe $edgeFg
        Check 'stream-log-connected-then-disconnected' ($pfLogDisc.Clean -and $pfLogDisc.IdleSeconds -eq 3.0) $pfLogDisc.Reason

        # 连接发生在当前进程启动之前（Apollo 重启过）-> 放行
        @(
            '[2026-11-19 19:40:00.000]: Info: CLIENT CONNECTED'
            '[2026-11-19 20:00:00.500]: Info: Apollo version: 0.4.6'
            '[2026-11-19 20:00:02.000]: Info: Configuration UI available at [https://localhost:47990]'
        ) | Set-Content -Encoding utf8 $apolloLog
        $pfLogStale = Test-DesktopPreflight -RegistryDir $regTmp -DriverInputFile $stampTmp -IdleProbe { 12.0 } -HostProbe $apolloHost -PortProbe $noPorts -ApolloLogPath $apolloLog -CoachWindowProbe $noCoach -ForegroundProbe $edgeFg
        Check 'stream-log-connected-before-process-restart' $pfLogStale.Clean $pfLogStale.Reason

        # 日志里没有会话、端口也没绑 -> 放行
        @(
            '[2026-11-19 20:00:00.500]: Info: Registered Apollo mDNS service'
            '[2026-11-19 20:00:01.200]: Info: System tray created'
        ) | Set-Content -Encoding utf8 $apolloLog
        $pfLogIdle = Test-DesktopPreflight -RegistryDir $regTmp -DriverInputFile $stampTmp -IdleProbe { 1.0 } -HostProbe $apolloHost -PortProbe $noPorts -ApolloLogPath $apolloLog -CoachWindowProbe $noCoach -ForegroundProbe $edgeFg
        Check 'stream-log-empty-and-no-ports' $pfLogIdle.Clean $pfLogIdle.Reason

        # UDP 47998 已绑定（即使日志没记录）-> 占用
        $pfUdpBound = Test-DesktopPreflight -RegistryDir $regTmp -DriverInputFile $stampTmp -IdleProbe $idle15m -HostProbe $apolloHost -PortProbe { [pscustomobject]@{ Udp = @(47998); Tcp = @() } } -ApolloLogPath $apolloLog -CoachWindowProbe $noCoach -ForegroundProbe $edgeFg
        Check 'stream-udp-47998-bound' (-not $pfUdpBound.Clean -and $pfUdpBound.ActiveUser.Streaming.Source -eq 'ports' -and ($pfUdpBound.ActiveUser.Streaming.Ports -contains 47998)) $pfUdpBound.Reason

        # Apollo 在跑，但端口查询报错、日志也没有 -> 占用
        $pfIndet = Test-DesktopPreflight -RegistryDir $regTmp -DriverInputFile $stampTmp -IdleProbe $idle15m -HostProbe $apolloHost -PortProbe { throw 'NetTCPIP CIM failed' } -ApolloLogPath (Join-Path $regTmp 'missing-sunshine.log') -CoachWindowProbe $noCoach -ForegroundProbe $edgeFg
        Check 'stream-apollo-running-ports-error-no-log' (-not $pfIndet.Clean -and $pfIndet.ActiveUser.Streaming.Source -eq 'indeterminate') $pfIndet.Reason

        # 独立查询 UDP / TCP（真实 Test-DesktopPreflight 端口枚举路径，无 -PortProbe）：
        # UDP 47998 已绑定但 TCP 枚举报错 -> 保留已查到的 UDP 端口并判占用
        $fakeHost = { @([pscustomobject]@{ Id = 44444; ProcessName = 'sunshine'; StartTime = $apolloStartLocal }) }
        $emptyLog = Join-Path $regTmp 'empty-sunshine.log'
        Set-Content -Encoding utf8 -Path $emptyLog -Value @('[2026-11-19 20:00:01.100]: Info: Apollo version: 0.4.6')
        $pfUdpKeepWhenTcpErr = & {
            function Get-NetUDPEndpoint { param($ErrorAction) @([pscustomobject]@{ OwningProcess = 44444; LocalPort = 47998 }) }
            function Get-NetTCPConnection { param($ErrorAction) throw 'TCP CIM failed' }
            Test-DesktopPreflight -RegistryDir $regTmp -DriverInputFile $stampTmp -IdleProbe $idle15m -HostProbe $fakeHost -ApolloLogPath $emptyLog -CoachWindowProbe $noCoach -ForegroundProbe $edgeFg
        }
        Check 'stream-udp-47998-when-tcp-query-fails' (-not $pfUdpKeepWhenTcpErr.Clean -and $pfUdpKeepWhenTcpErr.ActiveUser.Streaming.Source -eq 'ports' -and ($pfUdpKeepWhenTcpErr.ActiveUser.Streaming.Ports -contains 47998)) $pfUdpKeepWhenTcpErr.Reason

        # UDP 枚举报错但 TCP 48010 已建立 -> TCP 不被跳过并判占用
        $pfTcpKeepWhenUdpErr = & {
            function Get-NetUDPEndpoint { param($ErrorAction) throw 'UDP CIM failed' }
            function Get-NetTCPConnection { param($ErrorAction) @([pscustomobject]@{ OwningProcess = 44444; State = 'Established'; LocalPort = 48010 }) }
            Test-DesktopPreflight -RegistryDir $regTmp -DriverInputFile $stampTmp -IdleProbe $idle15m -HostProbe $fakeHost -ApolloLogPath $emptyLog -CoachWindowProbe $noCoach -ForegroundProbe $edgeFg
        }
        Check 'stream-tcp-48010-when-udp-query-fails' (-not $pfTcpKeepWhenUdpErr.Clean -and $pfTcpKeepWhenUdpErr.ActiveUser.Streaming.Source -eq 'ports' -and ($pfTcpKeepWhenUdpErr.ActiveUser.Streaming.Ports -contains 48010)) $pfTcpKeepWhenUdpErr.Reason

        # 两次连接后仅断开一次（仍有一路在线）-> 占用；两次均断开 -> 放行
        @(
            '[2026-11-19 20:00:01.100]: Info: Apollo version: 0.4.6'
            '[2026-11-19 20:01:11.012]: Info: CLIENT CONNECTED'
            '[2026-11-19 20:02:05.330]: Info: CLIENT CONNECTED'
            '[2026-11-19 20:03:10.400]: Info: CLIENT DISCONNECTED'
        ) | Set-Content -Encoding utf8 $apolloLog
        $pfTwoConnOneDisc = Test-DesktopPreflight -RegistryDir $regTmp -DriverInputFile $stampTmp -IdleProbe $idle15m -HostProbe $apolloHost -PortProbe $noPorts -ApolloLogPath $apolloLog -CoachWindowProbe $noCoach -ForegroundProbe $edgeFg
        Check 'stream-log-two-connected-one-disconnected' (-not $pfTwoConnOneDisc.Clean -and $pfTwoConnOneDisc.ActiveUser.Streaming.Source -eq 'log') $pfTwoConnOneDisc.Reason

        @(
            '[2026-11-19 20:00:01.100]: Info: Apollo version: 0.4.6'
            '[2026-11-19 20:01:11.012]: Info: CLIENT CONNECTED'
            '[2026-11-19 20:02:05.330]: Info: CLIENT CONNECTED'
            '[2026-11-19 20:03:10.400]: Info: CLIENT DISCONNECTED'
            '[2026-11-19 20:04:22.900]: Info: CLIENT DISCONNECTED'
        ) | Set-Content -Encoding utf8 $apolloLog
        $pfTwoConnTwoDisc = Test-DesktopPreflight -RegistryDir $regTmp -DriverInputFile $stampTmp -IdleProbe $idle15m -HostProbe $apolloHost -PortProbe $noPorts -ApolloLogPath $apolloLog -CoachWindowProbe $noCoach -ForegroundProbe $edgeFg
        Check 'stream-log-two-connected-both-disconnected' $pfTwoConnTwoDisc.Clean $pfTwoConnTwoDisc.Reason
    } finally {
        Remove-Item -LiteralPath $apolloLog -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath (Join-Path $regTmp 'empty-sunshine.log') -ErrorAction SilentlyContinue
    }

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

        $zeroAct = [WD]::Activate([IntPtr]::Zero)
        Check 'activate-zero-hwnd-fails-without-input' (
            (-not $zeroAct) -and
            (-not [WD]::LastActivationInjectedInput) -and
            ([WD]::LastActivationFailure -eq 'WM_NULL confirmation failed')
        ) "ok=$zeroAct injected=$([WD]::LastActivationInjectedInput) fail=$([WD]::LastActivationFailure)"

        # 8a-0. 标题栏点击分支替身回归：实际落点经目标 WM_NCHITTEST 确认为 HTCAPTION 才点；移动失败 / 落点不可读 / 裁出目标 / 裁到关闭按钮或客户区 / 命中测试无应答均零输入
        if (-not ('CoachCaptionGuardCheck' -as [type])) {
            $wdSrc = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'Win-Drive.ps1')
            $cgStart = $wdSrc.IndexOf('    if (ForegroundAtConfirmation != h && IsWindowEnabled(h)) {')
            $cgEnd = $wdSrc.IndexOf('    if (ForegroundAtConfirmation != h) {', $cgStart + 1)
            if ($cgStart -lt 0 -or $cgEnd -le $cgStart) { throw 'Caption guard block not found in Win-Drive.ps1' }
            $cgBlock = $wdSrc.Substring($cgStart, $cgEnd - $cgStart)
            $cgDef = @"
using System;
public static class CoachCaptionGuardCheck {
    struct RECT { public int L,T,R,B; }
    struct POINT { public int X,Y; }
    static readonly IntPtr Target = (IntPtr)10, Other = (IntPtr)20;
    static IntPtr ForegroundAtConfirmation;
    static string LastActivationFailure;
    static bool LastActivationAccepted, LastActivationInjectedInput;
    static string mode;
    static int cursorX, cursorY;
    public static int Events, OtherEvents;
    public static bool InjectedInput;
    static bool IsWindowEnabled(IntPtr h) { return true; }
    static void GetWindowThreadProcessId(IntPtr h, out uint pid) { pid = 1; }
    static bool GetWindowRect(IntPtr h, out RECT r) { r = new RECT { L = 200, T = 200, R = 1000, B = 900 }; return true; }
    static bool SetWindowPos(IntPtr h, IntPtr z, int x, int y, int w, int b, uint flags) { return true; }
    // 与 QA 在自建隐藏窗口上实测的命中结果一致：窗口 (200,200)-(1000,900)，标题栏行 y<230，
    // 右上角 x>=940 是关闭按钮 HTCLOSE(20)，标题栏其余是 HTCAPTION(2)，下面是客户区 HTCLIENT(1)。
    static int HitTest(int x, int y) {
        if (WindowFromPoint(new POINT { X = x, Y = y }) != Target) return 0;
        if (y < 230) return (x >= 940) ? 20 : 2;
        return 1;
    }
    static IntPtr SendMessageTimeout(IntPtr h, uint m, UIntPtr w, IntPtr p, uint flags, uint ms, out UIntPtr result) {
        result = UIntPtr.Zero;
        if (m == 0x84) {
            if (h != Target || mode == "hittest-hung") return IntPtr.Zero;
            int lp = (int)p.ToInt64();
            result = (UIntPtr)(uint)HitTest((short)(lp & 0xFFFF), (short)((lp >> 16) & 0xFFFF));
        }
        return (IntPtr)1;
    }
    static IntPtr WindowFromPoint(POINT p) { return (p.X >= 200 && p.X < 1000 && p.Y >= 200 && p.Y < 900) ? Target : Other; }
    static IntPtr GetAncestor(IntPtr h, uint flags) { return h; }
    static bool SetCursorPos(int x, int y) {
        if (mode == "failed-move") return false;
        if (mode == "normal" || mode == "unreadable-pos" || mode == "hittest-hung") { cursorX = x; cursorY = y; }
        else if (mode == "clipped-client") { cursorX = x; cursorY = 500; }
        else if (mode == "clipped-close") { cursorX = 980; cursorY = 218; }
        else if (mode == "clipped-client-band") { cursorX = 466; cursorY = 244; }
        return true;
    }
    static bool GetCursorPos(out POINT p) {
        p = new POINT { X = cursorX, Y = cursorY };
        return mode != "unreadable-pos";
    }
    static void mouse_event(uint flags, int x, int y, uint data, IntPtr extra) {
        Events++;
        if (HitTest(cursorX, cursorY) != 2) OtherEvents++;
    }
    static IntPtr ReadForegroundSettled(bool accepted) { return (OtherEvents == 0 && Events > 0) ? Target : Other; }
    public static bool Run(string scenario) {
        mode = scenario; cursorX = 0; cursorY = 0; Events = 0; OtherEvents = 0;
        var h = Target; ForegroundAtConfirmation = Other; UIntPtr ignored; bool responsive = true;
        LastActivationAccepted = false; LastActivationInjectedInput = false; LastActivationFailure = null;
$cgBlock
        InjectedInput = LastActivationInjectedInput;
        if (ForegroundAtConfirmation != h) {
            LastActivationFailure = LastActivationAccepted ? "activation accepted but target was not foreground" : "activation declined by Windows";
            return false;
        }
        LastActivationFailure = null;
        return true;
    }
}
"@
            Add-Type -TypeDefinition $cgDef
        }
        foreach ($cgCase in @(
            @{ Name = 'caption-guard-normal-move'; Scenario = 'normal'; ExpectOk = $true; ExpectEvents = 2; ExpectInjected = $true }
            @{ Name = 'caption-guard-failed-move'; Scenario = 'failed-move'; ExpectOk = $false; ExpectEvents = 0; ExpectInjected = $false }
            @{ Name = 'caption-guard-unreadable-cursor-pos'; Scenario = 'unreadable-pos'; ExpectOk = $false; ExpectEvents = 0; ExpectInjected = $false }
            @{ Name = 'caption-guard-clipped-off-target'; Scenario = 'clipped-move'; ExpectOk = $false; ExpectEvents = 0; ExpectInjected = $false }
            @{ Name = 'caption-guard-clipped-outside-caption'; Scenario = 'clipped-client'; ExpectOk = $false; ExpectEvents = 0; ExpectInjected = $false }
            @{ Name = 'caption-guard-clipped-to-close-button'; Scenario = 'clipped-close'; ExpectOk = $false; ExpectEvents = 0; ExpectInjected = $false }
            @{ Name = 'caption-guard-clipped-to-client-in-top-band'; Scenario = 'clipped-client-band'; ExpectOk = $false; ExpectEvents = 0; ExpectInjected = $false }
            @{ Name = 'caption-guard-hittest-unconfirmed'; Scenario = 'hittest-hung'; ExpectOk = $false; ExpectEvents = 0; ExpectInjected = $false }
        )) {
            $cgOk = [CoachCaptionGuardCheck]::Run($cgCase.Scenario)
            Check $cgCase.Name (
                $cgOk -eq $cgCase.ExpectOk -and
                [CoachCaptionGuardCheck]::Events -eq $cgCase.ExpectEvents -and
                [CoachCaptionGuardCheck]::OtherEvents -eq 0 -and
                [CoachCaptionGuardCheck]::InjectedInput -eq $cgCase.ExpectInjected
            ) "ok=$cgOk events=$([CoachCaptionGuardCheck]::Events) off=$([CoachCaptionGuardCheck]::OtherEvents) inj=$([CoachCaptionGuardCheck]::InjectedInput)"
        }

        # 8a. Invoke-ForegroundStep 真实 20 次重试 + 放锁后双向切窗回归（覆盖激活被拒与动作中失焦两个入口）
        function Invoke-SwapRegressionCase([string]$name, [ValidateSet('entry1','entry2')][string]$entry, $stateA, $stateB, [bool]$expectContention) {
            $mainWin = [IntPtr]555501L
            $ownMain = [pscustomobject]@{ Hwnd = 555501L; Pid = 55555; Process = 'java'; Class = 'SunAwtFrame' }
            $script:swapState = @{ attempt = 0; phase = 'init' }
            $targetProbe = { param($w) 55555 }
            $actProbe = {
                param($w, $attemptNum)
                $script:swapState.attempt = [int]$attemptNum
                if ($entry -eq 'entry1') {
                    $script:swapState.phase = 'at-activate-fail'
                    return [pscustomobject]@{ Ready = $false; ObservedHwnd = [long]$stateA.Hwnd; Failure = 'activation declined by Windows'; Accepted = $false }
                }
                $script:swapState.phase = 'pre-action'
                return [pscustomobject]@{ Ready = $true; ObservedHwnd = 555501L; Failure = ''; Accepted = $true }
            }
            $fgProbe = {
                if ($entry -eq 'entry1') {
                    if ($script:swapState.phase -eq 'at-activate-fail') {
                        if ($script:swapState.attempt -ge 20) { $script:swapState.phase = 'post-unlock' }
                        return $stateA
                    }
                    return $stateB
                }
                if ($script:swapState.phase -eq 'pre-action') {
                    $script:swapState.phase = 'in-action'
                    return $ownMain
                }
                if ($script:swapState.phase -eq 'in-action') {
                    if ($script:swapState.attempt -ge 20) { $script:swapState.phase = 'post-unlock' }
                    return $stateA
                }
                return $stateB
            }
            $thrown = $null
            try {
                Invoke-ForegroundStep -Window $mainWin -TargetOwnerProbe $targetProbe -ActivateProbe $actProbe -ForegroundProbe $fgProbe -Action {
                    Assert-OurForeground -Expected $mainWin -ForegroundProbe $fgProbe
                }
            } catch {
                $thrown = $_.Exception
            }
            $msg = if ($thrown) { $thrown.Message } else { '' }
            $ok = if ($expectContention) {
                [bool]($thrown -and
                    $msg -like 'WIN_DRIVE_FOREGROUND_CONTENTION:*' -and
                    $thrown.Data['ForegroundHwnd'] -eq [long]$stateA.Hwnd -and
                    $thrown.Data['ForegroundPid'] -eq [int]$stateA.Pid -and
                    $thrown.Data['ForegroundProcess'] -eq [string]$stateA.Process -and
                    $thrown.Data['ForegroundSummary'] -match "pid=$([int]$stateA.Pid)\b")
            } else {
                [bool]($thrown -and
                    $msg -like 'WIN_DRIVE_TIMEOUT:*' -and
                    $msg -notlike 'WIN_DRIVE_FOREGROUND_CONTENTION*' -and
                    $msg -match 'this run')
            }
            Check $name $ok "attempts=$($script:swapState.attempt) phase=$($script:swapState.phase) msg=$msg"
        }

        $extObj = [pscustomobject]@{ Hwnd = 683201L; Pid = 6832; Process = 'java'; Class = 'SunAwtFrame' }
        $ownObj = [pscustomobject]@{ Hwnd = 555502L; Pid = 55555; Process = 'java'; Class = 'SunAwtDialog' }
        Invoke-SwapRegressionCase 'invoke-entry1-ext-to-own-after-unlock' 'entry1' $extObj $ownObj $true
        Invoke-SwapRegressionCase 'invoke-entry1-own-to-ext-after-unlock' 'entry1' $ownObj $extObj $false
        Invoke-SwapRegressionCase 'invoke-entry2-ext-to-own-after-unlock' 'entry2' $extObj $ownObj $true
        Invoke-SwapRegressionCase 'invoke-entry2-own-to-ext-after-unlock' 'entry2' $ownObj $extObj $false
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
function Find-CoachWindow { $script:AppPid = 55555; $script:AppStartTicks = 0L; $script:Win = [IntPtr]555501L; return $true }
function Save-Shot([string]$path, [IntPtr]$Window = [IntPtr]::Zero) {
    if ([IO.Path]::GetFileName($path) -like '2-*') {
        $mainWin = [IntPtr]555501L
        $ownMain = [pscustomobject]@{ Hwnd = 555501L; Pid = 55555; Process = 'java'; Class = 'SunAwtFrame' }
        $extObj = [pscustomobject]@{ Hwnd = 683201L; Pid = 6832; Process = 'java'; Class = 'SunAwtFrame' }
        $ownObj = [pscustomobject]@{ Hwnd = 555502L; Pid = 55555; Process = 'java'; Class = 'SunAwtDialog' }
        $isExt = ($env:FP_FG_TEST_CASE -eq 'external')
        $script:e2eSwap = @{
            attempt = 0
            phase = 'init'
            isExt = $isExt
            ownMain = $ownMain
            stateA = $(if ($isExt) { $extObj } else { $ownObj })
            stateB = $(if ($isExt) { $ownObj } else { $extObj })
        }
        $targetProbe = { param($w) 55555 }
        $actProbe = {
            param($w, $attemptNum)
            $script:e2eSwap.attempt = [int]$attemptNum
            if ($script:e2eSwap.isExt) {
                $script:e2eSwap.phase = 'at-activate-fail'
                return [pscustomobject]@{ Ready = $false; ObservedHwnd = [long]$script:e2eSwap.stateA.Hwnd; Failure = 'activation declined by Windows'; Accepted = $false }
            }
            $script:e2eSwap.phase = 'pre-action'
            return [pscustomobject]@{ Ready = $true; ObservedHwnd = 555501L; Failure = ''; Accepted = $true }
        }
        $fgProbe = {
            if ($script:e2eSwap.isExt) {
                if ($script:e2eSwap.phase -eq 'at-activate-fail') {
                    if ($script:e2eSwap.attempt -ge 20) { $script:e2eSwap.phase = 'post-unlock' }
                    return $script:e2eSwap.stateA
                }
                return $script:e2eSwap.stateB
            }
            if ($script:e2eSwap.phase -eq 'pre-action') {
                $script:e2eSwap.phase = 'in-action'
                return $script:e2eSwap.ownMain
            }
            if ($script:e2eSwap.phase -eq 'in-action') {
                if ($script:e2eSwap.attempt -ge 20) { $script:e2eSwap.phase = 'post-unlock' }
                return $script:e2eSwap.stateA
            }
            return $script:e2eSwap.stateB
        }
        Invoke-ForegroundStep -Window $mainWin -TargetOwnerProbe $targetProbe -ActivateProbe $actProbe -ForegroundProbe $fgProbe -Action {
            Assert-OurForeground -Expected $mainWin -ForegroundProbe $fgProbe
        }
        return
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
        $dirtyFgExt.foregroundHwnd -eq 683201 -and
        $dirtyFgExt.foregroundPid -eq 6832 -and $dirtyFgExt.foregroundProcess -eq 'java' -and
        $dirtyFgExt.foregroundSummary -eq 'java pid=6832 class=SunAwtFrame other' -and
        $dirtyFgExt.foreground.hwnd -eq 683201 -and $dirtyFgExt.foreground.pid -eq 6832 -and
        $dirtyFgExt.step -eq 2 -and
        $resFgExt -and $resFgExt.passed -eq 1 -and $resFgExt.stoppedAtStep -eq 2 -and
        @($resFgExt.steps).Count -eq 1 -and $resFgExt.steps[0].Step -eq 1 -and $resFgExt.steps[0].Ok
    ) "code=$codeFgExt reason=$($dirtyFgExt.reason) hwnd=$($dirtyFgExt.foregroundHwnd) pid=$($dirtyFgExt.foregroundPid) passed=$($resFgExt.passed) stopped=$($resFgExt.stoppedAtStep)"

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
