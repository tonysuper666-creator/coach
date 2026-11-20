# 离线回归：两套自有范围分开的驱动同时点按（退出码：0 = 全部通过）。由 test-dirty-preflight.ps1 以子进程调用。
# 两个子进程各自加载真实 Win-Drive.ps1，各有自己的 AppPid 和目标窗，经真实 Click-At / Invoke-ForegroundStep
# 和同一把逐动作短锁点按。屏幕、窗口归属和鼠标由探针模拟，不碰真实桌面、不占真实驱动用的锁名、不开端口。
# 核对：点按只落在本轮目标窗；锁只在单个动作内持有，动作之间归还，两边交替拿到；
# 目标窗不属于本轮就拒绝且不点；外窗占住前台只让这一步失败，下一步照常。
param(
    [ValidateSet('parent', 'child')][string]$Role = 'parent',
    [string]$Name = '',
    [int]$FakePid = 0,
    [long]$Hwnd = 0,
    [long]$OtherHwnd = 0,
    [string]$StateDir = '',
    [string]$MutexName = '',
    [int]$Actions = 12,
    [int]$ContentionAt = 0
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Win-Drive.ps1')

Add-Type @'
using System;
using System.Threading;
public static class CoexistMutexProbe {
    // Probe from a separate thread: the driver's own thread could re-enter its mutex.
    public static bool FreeFromOtherThread(string name) {
        bool got = false;
        var t = new Thread(() => {
            using (var m = new Mutex(false, name)) {
                try { got = m.WaitOne(0); } catch (AbandonedMutexException) { got = true; }
                if (got) m.ReleaseMutex();
            }
        });
        t.Start(); t.Join();
        return got;
    }
}
'@

# 合成桌面：窗口 -> 进程归属表，前台写在共享文件里（谁激活谁的窗，前台就是谁）。
$owners = @{ 710010L = 71001; 720010L = 72001; 790010L = 79001 }
$classes = @{ 710010L = 'SunAwtFrame'; 720010L = 'SunAwtFrame'; 790010L = 'Chrome_WidgetWin_1' }

if ($Role -eq 'child') {
    $script:AppPid = $FakePid
    $script:AppStartTicks = 0L
    $script:Win = [IntPtr]$Hwnd
    $script:ForegroundMutexName = $MutexName
    $script:DriverInputStampPath = Join-Path $StateDir "$Name-driver-input.json"
    $script:ForegroundTracePath = Join-Path $StateDir "$Name-foreground.jsonl"
    $env:COACH_SMOKE_ENABLED = '0'
    $fgFile = Join-Path $StateDir 'foreground.txt'
    $log = Join-Path $StateDir "$Name-actions.jsonl"
    function Write-Fg([long]$h) {
        for ($i = 0; $i -lt 50; $i++) {
            try { [IO.File]::WriteAllText($fgFile, "$h"); return } catch { Start-Sleep -Milliseconds 5 }
        }
        throw 'foreground state not writable'
    }
    function Read-Fg {
        for ($i = 0; $i -lt 50; $i++) {
            try { return [long]([IO.File]::ReadAllText($fgFile).Trim()) } catch { Start-Sleep -Milliseconds 5 }
        }
        throw 'foreground state not readable'
    }
    $ownerOf = { param($w) $h = [long]$w; if ($owners.ContainsKey($h)) { $owners[$h] } else { 0 } }
    $fgProbe = {
        param($h)
        $cur = if ($null -ne $h) { [long]$h } else { Read-Fg }
        [pscustomobject]@{ Hwnd = $cur; Pid = $(if ($owners.ContainsKey($cur)) { $owners[$cur] } else { 0 }); Process = $(if ($cur -eq 790010L) { 'msedge' } else { 'java' }); Class = $classes[$cur] }
    }
    $activate = { param($w, $attempt) Write-Fg ([long]$w); [pscustomobject]@{ Ready = $true; ObservedHwnd = [long]$w; Failure = ''; Accepted = $true } }
    # 外窗一直占着前台、我们的激活被挡：不改共享前台，只让本轮这一步看见外窗。
    $blocked = { param($w, $attempt) [pscustomobject]@{ Ready = $false; ObservedHwnd = 790010L; Failure = 'foreground held by another window'; Accepted = $false } }
    $script:cur = $null
    $rect = { [pscustomobject]@{ L = 100; T = 100 } }
    $mouse = {
        param($what, $x, $y)
        if ($what -eq 'move') {
            $script:cur.enter = [Diagnostics.Stopwatch]::GetTimestamp()
            $script:cur.lockHeldInAction = -not [CoexistMutexProbe]::FreeFromOtherThread($MutexName)
        }
        if ($what -eq 'down') {
            $fgNow = Read-Fg
            $script:cur.downHwnd = $fgNow
            $script:cur.downPid = $(if ($owners.ContainsKey($fgNow)) { $owners[$fgNow] } else { 0 })
        }
        if ($what -eq 'up') {
            Start-Sleep -Milliseconds 120
            $script:cur.exit = [Diagnostics.Stopwatch]::GetTimestamp()
        }
    }
    $gate = Join-Path $StateDir 'go'
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    while (-not (Test-Path -LiteralPath $gate) -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 20 }
    for ($i = 1; $i -le $Actions; $i++) {
        $script:cur = [ordered]@{ name = $Name; idx = $i; kind = 'click'; targetHwnd = $Hwnd; enter = 0L; exit = 0L; downHwnd = 0L; downPid = 0; lockHeldInAction = $false; outcome = 'ok' }
        $act = if ($ContentionAt -eq $i) { $script:cur.kind = 'contention'; $blocked } else { $activate }
        try {
            Click-At 10 10 -TargetOwnerProbe $ownerOf -ActivateProbe $act -ForegroundProbe $fgProbe -RectProbe $rect -MouseProbe $mouse
        } catch { $script:cur.outcome = $_.Exception.Message }
        ($script:cur | ConvertTo-Json -Compress) | Add-Content -LiteralPath $log -Encoding UTF8
        Start-Sleep -Milliseconds 80
    }
    # 目标窗是另一套客户端的：必须在点之前拒绝，不点、不留锁。
    $script:cur = [ordered]@{ name = $Name; idx = $Actions + 1; kind = 'foreign-target'; targetHwnd = $OtherHwnd; enter = 0L; exit = 0L; downHwnd = 0L; downPid = 0; lockHeldInAction = $false; outcome = 'ok' }
    $script:Win = [IntPtr]$OtherHwnd
    try {
        Click-At 10 10 -TargetOwnerProbe $ownerOf -ActivateProbe $activate -ForegroundProbe $fgProbe -RectProbe $rect -MouseProbe $mouse
    } catch { $script:cur.outcome = $_.Exception.Message }
    ($script:cur | ConvertTo-Json -Compress) | Add-Content -LiteralPath $log -Encoding UTF8
    exit 0
}

# ---- parent ----
$results = New-Object System.Collections.ArrayList
function Check([string]$name, [bool]$ok, [string]$detail = '') {
    [void]$results.Add($ok)
    $tag = if ($ok) { 'PASS' } else { 'FAIL' }
    "$tag $name$(if ($detail) { " ($detail)" })"
}
$state = Join-Path ([IO.Path]::GetTempPath()) ("coach-coexist-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force $state | Out-Null
$mutexName = 'Local\Coach.WinDrive.Foreground.coexist-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
[IO.File]::WriteAllText((Join-Path $state 'foreground.txt'), '790010')
$procs = @()
try {
    $self = $PSCommandPath
    $common = "-StateDir `"$state`" -MutexName `"$mutexName`" -Actions 12"
    $procs += Start-Process powershell.exe -PassThru -WindowStyle Hidden -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$self`" -Role child -Name A -FakePid 71001 -Hwnd 710010 -OtherHwnd 720010 $common"
    $procs += Start-Process powershell.exe -PassThru -WindowStyle Hidden -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$self`" -Role child -Name B -FakePid 72001 -Hwnd 720010 -OtherHwnd 710010 -ContentionAt 9 $common"
    Start-Sleep -Milliseconds 2500   # 两边都加载完 Win-Drive.ps1 后同时开始
    New-Item -ItemType File -Force (Join-Path $state 'go') | Out-Null
    $allExited = $true
    foreach ($p in $procs) {
        if (-not $p.WaitForExit(180000)) { $allExited = $false; try { $p.Kill() } catch { } }
    }
    $codes = ($procs | ForEach-Object { $_.ExitCode }) -join ','
    Check 'coexist-children-exit-0' ($allExited -and @($procs | Where-Object { $_.ExitCode -ne 0 }).Count -eq 0) "exit=$codes"

    $rows = @()
    foreach ($n in 'A', 'B') {
        $f = Join-Path $state "$n-actions.jsonl"
        if (Test-Path -LiteralPath $f) { $rows += @(Get-Content -LiteralPath $f -Encoding UTF8 | Where-Object { $_ } | ForEach-Object { $_ | ConvertFrom-Json }) }
    }
    $clicks = @($rows | Where-Object { $_.kind -eq 'click' })
    $ownPid = @{ A = 71001; B = 72001 }
    $ownHwnd = @{ A = 710010L; B = 720010L }
    $okClicks = @($clicks | Where-Object { $_.outcome -eq 'ok' })
    Check 'coexist-all-normal-clicks-pass' ($clicks.Count -eq 23 -and $okClicks.Count -eq 23) "clicks=$($clicks.Count) ok=$($okClicks.Count)"

    $wrongTarget = @($okClicks | Where-Object { [long]$_.downHwnd -ne $ownHwnd[$_.name] -or [int]$_.downPid -ne $ownPid[$_.name] })
    Check 'coexist-click-lands-only-on-own-window' ($okClicks.Count -gt 0 -and $wrongTarget.Count -eq 0) "A->pid=$(@($okClicks | Where-Object name -eq 'A' | ForEach-Object downPid | Select-Object -Unique) -join '/') B->pid=$(@($okClicks | Where-Object name -eq 'B' | ForEach-Object downPid | Select-Object -Unique) -join '/') wrong=$($wrongTarget.Count)"

    $notHeld = @($okClicks | Where-Object { -not $_.lockHeldInAction })
    Check 'coexist-lock-held-during-each-action' ($okClicks.Count -gt 0 -and $notHeld.Count -eq 0) "notHeld=$($notHeld.Count)"

    $sorted = @($okClicks | Sort-Object { [long]$_.enter })
    $overlaps = 0
    for ($i = 1; $i -lt $sorted.Count; $i++) {
        if ([long]$sorted[$i].enter -lt [long]$sorted[$i - 1].exit) { $overlaps++ }
    }
    $switches = 0
    for ($i = 1; $i -lt $sorted.Count; $i++) { if ($sorted[$i].name -ne $sorted[$i - 1].name) { $switches++ } }
    # 整轮持锁时两边只会各成一整段（最多 1 次切换）；逐动作归还时两边交替拿锁。
    Check 'coexist-no-overlapping-actions' ($sorted.Count -gt 0 -and $overlaps -eq 0) "overlaps=$overlaps"
    Check 'coexist-lock-returned-per-action-interleaves' ($switches -ge 6) "switches=$switches"

    $cont = @($rows | Where-Object { $_.kind -eq 'contention' })
    $bAfter = @($okClicks | Where-Object { $_.name -eq 'B' -and [int]$_.idx -gt 9 })
    Check 'coexist-foreground-contention-only-this-step' (
        $cont.Count -eq 1 -and $cont[0].name -eq 'B' -and $cont[0].outcome -like 'WIN_DRIVE_FOREGROUND_CONTENTION:*' -and
        [long]$cont[0].downHwnd -eq 0 -and $bAfter.Count -eq 3 -and @($okClicks | Where-Object name -eq 'A').Count -eq 12
    ) "B9=$(if ($cont) { ($cont[0].outcome -split ':')[0] }) B-after-ok=$($bAfter.Count) A-ok=$(@($okClicks | Where-Object name -eq 'A').Count)"

    $foreign = @($rows | Where-Object { $_.kind -eq 'foreign-target' })
    Check 'coexist-foreign-target-rejected-no-click' (
        $foreign.Count -eq 2 -and @($foreign | Where-Object { $_.outcome -like 'WIN_DRIVE_TARGET:*' -and [long]$_.downHwnd -eq 0 -and [long]$_.enter -eq 0 }).Count -eq 2
    ) "rejected=$(@($foreign | Where-Object { $_.outcome -like 'WIN_DRIVE_TARGET:*' }).Count)"

    $traces = @()
    foreach ($n in 'A', 'B') {
        $f = Join-Path $state "$n-foreground.jsonl"
        if (Test-Path -LiteralPath $f) { $traces += @(Get-Content -LiteralPath $f -Encoding UTF8 | Where-Object { $_ } | ForEach-Object { $_ | ConvertFrom-Json | Add-Member -NotePropertyName who -NotePropertyValue $n -PassThru }) }
    }
    $unreleased = @($traces | Where-Object { -not $_.releasedUtc })
    $badTarget = @($traces | Where-Object { [int]$_.targetPid -ne $ownPid[$_.who] })
    Check 'coexist-trace-every-attempt-released-own-target' ($traces.Count -ge 43 -and $unreleased.Count -eq 0 -and $badTarget.Count -eq 0) "attempts=$($traces.Count) unreleased=$($unreleased.Count) badTarget=$($badTarget.Count)"

    Check 'coexist-lock-free-after-round' ([CoexistMutexProbe]::FreeFromOtherThread($mutexName)) ''
} finally {
    foreach ($p in $procs) { if (-not $p.HasExited) { try { $p.Kill() } catch { } } }
    Remove-Item -Recurse -Force $state -ErrorAction SilentlyContinue
}
$failed = @($results | Where-Object { -not $_ }).Count
"RESULT $($results.Count - $failed)/$($results.Count)"
exit $(if ($failed) { 1 } else { 0 })
