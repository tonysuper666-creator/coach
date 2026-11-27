# Runs in the logged-in console session. Drives one packaged LearningCoach.exe per persona.
# Logs step ids only. Does not print book text, credentials, service addresses, or pairing codes.
param(
    [Parameter(Mandatory)][string]$Repo,
    [Parameter(Mandatory)][string]$OutDir,
    [Parameter(Mandatory)][string]$Exe,
    [string]$Version = 'unknown',
    [string]$Commit = '',
    [string]$Baseline = '',
    [string]$Personas = 'P1,P2,P3,P4,P5'
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$env:PYTHONIOENCODING = 'utf-8'
$Shots = Join-Path $OutDir 'shots'
New-Item -ItemType Directory -Force -Path $Shots | Out-Null
trap {
    try {
        $info = $_.InvocationInfo.PositionMessage + "`r`n" + $_.ScriptStackTrace + "`r`n" + $_.Exception.ToString()
        [IO.File]::WriteAllText((Join-Path $OutDir 'crash.txt'), $info, (New-Object Text.UTF8Encoding $false))
    } catch {}
    break
}
$OffTopic = '晚饭是番茄炒蛋和米饭。吃完去楼下走了二十分钟，又看了一会儿新闻。'
$AskP2 = '这句在说什么'
$AskP3 = '这页的图在说什么'

function Note([string]$m) {
    $line = '{0:HH:mm:ss} {1}' -f (Get-Date), $m
    # Write-Host stays off the function return value. Write-Output would make a later return $false look true.
    Write-Host $line
    try { [IO.File]::AppendAllText((Join-Path $OutDir 'steps.log'), ($line + "`r`n"), (New-Object Text.UTF8Encoding $false)) } catch {}
}
function Note-Safe([string]$m) {
    if ($m -match 'https?://') { $m = 'redacted' }
    if ($m.Length -gt 160) { $m = $m.Substring(0, 160) }
    Note $m
}
function New-Bucket { @{ ok = 0; timeout = 0; quota = 0; refusal = 0; source = 0; other = 0 } }
function New-Persona([string]$id, [string]$name, [int]$total) {
    @{ id = $id; name = $name; opened = $false; steps_done = 0; steps_total = $total; stuck = $null
        explain = (New-Bucket); ask = (New-Bucket); crash = 0 }
}
function Pass-Step($p, [string]$name) { $p.steps_done++; Note "$($p.id) $name ok" }
function Fail-Step($p, [string]$name) {
    if ($p -isnot [System.Collections.IDictionary]) { throw ("bad-persona " + $(if ($p) { $p.GetType().FullName } else { 'null' })) }
    if (-not $p['stuck']) { $p['stuck'] = $name }
    Note "$($p['id']) $name fail"
}
function Set-Check([string]$key, [string]$status, [string]$why) { $script:Checks[$key] = @{ status = $status; stuck = $why } }
function Test-AppAlive { return [bool]($script:PersonaPid -and (Get-Process -Id $script:PersonaPid -ErrorAction SilentlyContinue)) }
function Update-Owner {
    if ($script:PersonaPid -and (Get-Process -Id $script:PersonaPid -ErrorAction SilentlyContinue)) {
        if (Find-CoachWindow) { return $true }
    }
    $deadline = (Get-Date).AddSeconds(5)
    do {
        $owner = 0
        foreach ($cand in @(Get-OwnedPids $script:LauncherPid)) {
            if (-not (Get-Process -Id $cand -ErrorAction SilentlyContinue)) { continue }
            $script:PersonaPid = [int]$cand
            if (Find-CoachWindow) { $owner = [int]$cand }
        }
        if ($owner) { $script:PersonaPid = $owner; Find-CoachWindow | Out-Null; return $true }
        Start-Sleep -Milliseconds 400
    } while ((Get-Date) -lt $deadline)
    return $false
}
function Mark-Dead($p) {
    if (-not $p.opened) { return $false }
    if (Update-Owner) { return $false }
    $p.crash = 1
    Fail-Step $p '进程退出'
    return $true
}

. (Join-Path $PSScriptRoot 'Win-Drive.ps1')
Add-Type -AssemblyName System.Web.Extensions
$preflight = Test-DesktopPreflight
if (-not $preflight.Clean) {
    @{ dirty = $true; exitCode = 2; reason = [string]$preflight.Reason; time = [DateTime]::UtcNow.ToString('o') } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutDir 'environment-dirty.json') -Encoding UTF8
    Note 'ENVIRONMENT_DIRTY'
    $global:LASTEXITCODE = 2
    exit 2
}
Note 'preflight ok'
function Get-ClientPids([switch]$Mine) {
    if ($script:PersonaPid -and (Get-Process -Id $script:PersonaPid -ErrorAction SilentlyContinue)) { @($script:PersonaPid) } else { @() }
}
$script:Known = @()
$script:PersonaPid = 0
$script:LauncherPid = 0

Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class RetestKeys {
  [StructLayout(LayoutKind.Sequential)] public struct MOUSEINPUT { public int dx, dy; public uint mouseData, dwFlags, time; public IntPtr dwExtraInfo; }
  [StructLayout(LayoutKind.Sequential)] public struct KEYBDINPUT { public ushort wVk, wScan; public uint dwFlags, time; public IntPtr dwExtraInfo; }
  [StructLayout(LayoutKind.Explicit)] public struct INPUTUNION { [FieldOffset(0)] public MOUSEINPUT mi; [FieldOffset(0)] public KEYBDINPUT ki; }
  [StructLayout(LayoutKind.Sequential)] public struct INPUT { public uint type; public INPUTUNION u; }
  [DllImport("user32.dll", SetLastError=true)] static extern uint SendInput(uint n, INPUT[] p, int cb);
  public static int Type(string text) {
    int sent = 0;
    foreach (char c in text) {
      INPUT down = new INPUT();
      down.type = 1;
      down.u.ki.wScan = c;
      down.u.ki.dwFlags = 0x0004;
      INPUT up = down;
      up.u.ki.dwFlags = 0x0004 | 0x0002;
      sent += (int)SendInput(2, new INPUT[] { down, up }, Marshal.SizeOf(typeof(INPUT)));
    }
    return sent;
  }
}
"@

function Get-OwnedPids([int]$root) {
    $ids = New-Object System.Collections.Generic.List[int]
    if ($root -gt 0) { $ids.Add($root) }
    foreach ($child in @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$root" -ErrorAction SilentlyContinue)) {
        $ids.Add([int]$child.ProcessId)
        foreach ($grand in @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($child.ProcessId)" -ErrorAction SilentlyContinue)) {
            $ids.Add([int]$grand.ProcessId)
        }
    }
    return $ids
}
function Stop-App {
    foreach ($procId in @($script:PersonaPid, $script:LauncherPid)) {
        if (-not $procId) { continue }
        $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
        if (-not $proc) { continue }
        try { $proc.CloseMainWindow() | Out-Null } catch { }
        if (-not $proc.WaitForExit(8000)) { Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue }
    }
    $script:PersonaPid = 0
    $script:LauncherPid = 0
    $script:Win = [IntPtr]::Zero
    $script:AppPid = 0
}
# $HOME is a read-only automatic variable, so this parameter is not named home.
function Start-App([string]$personaHome) {
    Stop-App
    $psi = New-Object Diagnostics.ProcessStartInfo $Exe
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.WorkingDirectory = Split-Path -Parent $Exe
    foreach ($key in @($psi.EnvironmentVariables.Keys)) { if ($key -like 'COACH_*') { [void]$psi.EnvironmentVariables.Remove($key) } }
    $psi.EnvironmentVariables['JAVA_TOOL_OPTIONS'] = "-Duser.home=$personaHome"
    $proc = [Diagnostics.Process]::Start($psi)
    $script:LauncherPid = $proc.Id
    $deadline = (Get-Date).AddSeconds(90)
    $ownerPid = 0
    while ($true) {
        $ownerPid = 0
        foreach ($cand in @(Get-OwnedPids $proc.Id)) {
            $script:PersonaPid = [int]$cand
            if (Find-CoachWindow) { $ownerPid = [int]$cand }
        }
        if ($ownerPid) { break }
        if ((Get-Date) -gt $deadline) { throw 'window-timeout' }
        if ($proc.HasExited -and -not (Get-CimInstance Win32_Process -Filter "ParentProcessId=$($proc.Id)" -ErrorAction SilentlyContinue)) { throw 'window-exited' }
        Start-Sleep -Milliseconds 500
    }
    $script:PersonaPid = $ownerPid
    if (-not (Update-Owner)) { throw 'window-exited' }
    [WD]::ShowWindow($script:Win, 9) | Out-Null
    [WD]::SetWindowPos($script:Win, [IntPtr]::Zero, 40, 40, 820, 620, 0x0014) | Out-Null
    Start-Sleep -Milliseconds 500
    $rect = Get-Rect
    Note ("size " + ($rect.R - $rect.L) + "x" + ($rect.B - $rect.T))
}
function Get-Lines([string]$name) {
    $png = Join-Path $Shots ($name + '.png')
    Save-Shot $png
    return @(Read-Screen $png)
}
function Get-Blob($lines) {
    $parts = New-Object System.Collections.Generic.List[string]
    $seen = @{}
    foreach ($line in @($lines)) {
        if (-not $line) { continue }
        $text = [string]$line.Text
        if (-not $text -or $seen.ContainsKey($text)) { continue }
        $seen[$text] = $true
        $parts.Add($text)
    }
    return ($parts -join "`n")
}
function Test-Has($lines, [string]$needle) { return [bool](Find-Text $lines $needle) }
function Find-Top($lines, [string]$needle) {
    $best = $null
    $flat = ($needle -replace '\s', '')
    foreach ($line in @($lines)) {
        $text = ([string]$line.Text) -replace '\s', ''
        if (-not $text.Contains($flat)) { continue }
        $hit = Find-Text @($line) $needle
        if ($hit -and ((-not $best) -or ($hit.Y -lt $best.Y))) { $best = $hit }
    }
    return $best
}
function Click-Needle($lines, [string]$needle) {
    $hit = Find-Top $lines $needle
    if (-not $hit) { return $false }
    return [bool](Click-At $hit.X $hit.Y)
}
function Find-Scrolled([string]$shot, [string]$needle, [int]$scrolls) {
    for ($i = 0; $i -le $scrolls; $i++) {
        if (-not (Test-AppAlive)) { return $null }
        $lines = @(Get-Lines ("{0}-{1}" -f $shot, $i))
        if (Test-Has $lines $needle) { return $lines }
        if ($i -lt $scrolls) { Scroll-At 410 300 -3; Start-Sleep -Milliseconds 400 }
    }
    return $null
}
function Clear-Incident {
    $lines = @(Get-Lines 'incident')
    if (Test-Has $lines '不发') {
        Click-Needle $lines '不发' | Out-Null
        $script:DeclinedIncident = $true
        Start-Sleep -Milliseconds 500
        return $true
    }
    return $false
}
function Test-Login($lines) {
    $blob = Get-Blob $lines
    return ($blob -match '配对码' -and $blob -notmatch '今天' -and $blob -notmatch '距考试')
}
function Wait-Shell($p) {
    $deadline = (Get-Date).AddSeconds(25)
    while ($true) {
        if (Mark-Dead $p) { return $false }
        Clear-Incident | Out-Null
        $lines = @(Get-Lines ($p.id + '-shell'))
        if (Test-Login $lines) { Fail-Step $p '登录'; return $false }
        if ((Test-Has $lines '今天') -or (Test-Has $lines '继续') -or (Test-Has $lines '距考试') -or (Test-Has $lines '我的库') -or (Test-Has $lines '伴读') -or (Test-Has $lines '讲解') -or (Test-Has $lines '目录')) { return $true }
        if ((Get-Date) -gt $deadline) { Fail-Step $p '首页没出来'; return $false }
        Start-Sleep -Seconds 2
    }
}
function Open-Session($p, [string]$personaHome) {
    try { Start-App $personaHome } catch { Note-Safe ("launch " + $_.Exception.Message); Fail-Step $p '打开'; return $false }
    $p.opened = $true
    if (-not (Wait-Shell $p)) { return $false }
    Pass-Step $p '打开'
    return $true
}
function Get-FailCode([string]$blob) {
    if ($blob -match '超时') { return 'timeout' }
    if ($blob -match '额度|配额') { return 'quota' }
    if ($blob -match '依据不足|无法确认|拒答') { return 'refusal' }
    if ($blob -match '对不上') { return 'source' }
    if ($blob -match '连不上|暂时不可用') { return 'other' }
    return $null
}
function Wait-Ai([string]$shot, [int]$seconds) {
    $start = Get-Blob (Get-Lines ($shot + '-0'))
    $saw = $start -match '正在'
    $deadline = (Get-Date).AddSeconds($seconds)
    $last = $start
    while ($true) {
        if (-not (Test-AppAlive)) { return 'dead' }
        $last = Get-Blob (Get-Lines $shot)
        $code = Get-FailCode $last
        if ($code) { return $code }
        if ($last -match '正在') { $saw = $true }
        elseif ($saw -and $last.Length -gt 30) { return 'ok' }
        if ((Get-Date) -gt $deadline) { break }
        Start-Sleep -Seconds 3
    }
    if ($saw) { return 'ok' }
    if ($last.Length -gt ($start.Length + 24)) { return 'ok' }
    return 'timeout'
}
function Add-Count($bucket, [string]$code) {
    if ($code -eq 'dead') { return }
    if (-not $bucket.ContainsKey($code)) { $code = 'other' }
    $bucket[$code] = [int]$bucket[$code] + 1
}
function Type-Unicode([string]$text) {
    Invoke-ForegroundStep -InjectsInput -Action { [RetestKeys]::Type($text) | Out-Null }
}
function Open-Companion {
    $already = @(Get-Lines 'companion-0')
    if ((Test-Has $already '提问') -or (Test-Has $already '讲解')) { return $true }
    Send-Keys 'l'
    Start-Sleep -Seconds 1
    $lines = @(Get-Lines 'companion')
    if ((Test-Has $lines '提问') -or (Test-Has $lines '讲解') -or (Test-Has $lines '讲讲')) { return $true }
    $hit = Find-Top $lines '伴读'
    if ($hit) { Click-At $hit.X $hit.Y | Out-Null; Start-Sleep -Milliseconds 800 }
    $lines = @(Get-Lines 'companion-2')
    return ((Test-Has $lines '提问') -or (Test-Has $lines '讲解') -or (Test-Has $lines '讲讲'))
}
function Open-Book {
    for ($i = 0; $i -lt 5; $i++) {
        if (-not (Test-AppAlive)) { return $false }
        $lines = @(Get-Lines ("book-{0}" -f $i))
        if ((Test-Has $lines '伴读') -or (Test-Has $lines '讲讲') -or (Test-Has $lines '收起伴读') -or (Test-Has $lines '讲解')) { return $true }
        if (Click-Needle $lines '继续') { Start-Sleep -Seconds 2; continue }
        $moved = $false
        foreach ($label in @('返回课程', '返回模考主页', '返回书架', '关闭')) {
            if (Click-Needle $lines $label) { $moved = $true; Start-Sleep -Milliseconds 700; break }
        }
        if (-not $moved) { Scroll-At 410 240 -3; Start-Sleep -Milliseconds 400 }
    }
    return $false
}
function Send-Ask([string]$text) {
    $lines = Find-Scrolled 'ask-tab' '提问' 2
    if (-not $lines) { return $false }
    Click-Needle $lines '提问' | Out-Null
    Start-Sleep -Milliseconds 600
    $lines = @(Get-Lines 'ask-field')
    $send = Find-Text $lines '发送'
    if (-not $send) { return $false }
    Click-At ([Math]::Max(20, $send.X - 120)) $send.Y | Out-Null
    Start-Sleep -Milliseconds 200
    Type-Unicode $text
    Start-Sleep -Milliseconds 400
    $lines = @(Get-Lines 'ask-typed')
    $head = $text.Substring(0, [Math]::Min(4, $text.Length))
    if (-not (Test-Has $lines $head)) { return $false }
    return (Click-Needle $lines '发送')
}
function Set-ExamDay([string]$db, [int]$days) {
    $py = Join-Path $Repo 'tools\smoke\persona_retest_table.py'
    $raw = & python $py --set-exam $db --days $days
    if ($LASTEXITCODE -ne 0) { throw 'set-exam-failed' }
    $text = (($raw | Out-String).Trim())
    if (-not $text -or $text -eq 'null') { return $null }
    return ($text | ConvertFrom-Json)
}
function Copy-P1Home([string]$dest) {
    $src = 'D:\agent\personas\P1\home-r2'
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    & robocopy $src $dest /E /NFL /NDL /NJH /NJS /nc /ns /np /R:1 /W:1 | Out-Null
    if ($LASTEXITCODE -ge 8) { throw 'copy-failed' }
}
function Count-Needle($lines, [string]$needle) {
    $flat = $needle -replace '\s', ''
    $seen = @{}
    $count = 0
    foreach ($line in @($lines)) {
        $text = ([string]$line.Text) -replace '\s', ''
        if (-not $text -or $seen.ContainsKey($text)) { continue }
        $seen[$text] = $true
        if ($text.Contains($flat)) { $count++ }
    }
    return $count
}
function Invoke-P1($p) {
    $personaHome = Join-Path $OutDir 'homes\P1'
    Copy-P1Home $personaHome
    $db = Join-Path $personaHome 'Coach\books\library.sqlite'
    if (-not (Test-Path -LiteralPath $db)) { Fail-Step $p '打开'; Set-Check 'countdown' '卡住' '没有课程或没有考试日'; return }
    $first = Set-ExamDay $db 14
    if (-not $first) { Fail-Step $p '打开'; Set-Check 'countdown' '卡住' '没有课程或没有考试日'; return }
    Note ("exam " + $first.after)
    if (-not (Open-Session $p $personaHome)) { Set-Check 'countdown' '未测' '—'; return }
    $lines = Find-Scrolled 'p1-count' '距考试14天' 4
    $task = $false
    if ($lines) { $task = (Test-Has $lines '个考点') -or (Test-Has $lines '新学') }
    if ($lines -and $task) {
        Pass-Step $p '倒计时'
        Set-Check 'countdown' '通过' '—'
        Stop-App
        $second = Set-ExamDay $db 11
        if (-not $second) { Fail-Step $p '拨日期'; Set-Check 'dateshift' '卡住' '没有课程或没有考试日' }
        else {
            Note ("exam " + $second.after)
            try { Start-App $personaHome } catch { Note-Safe $_.Exception.Message; Fail-Step $p '拨日期'; Set-Check 'dateshift' '卡住' '倒计时那一步没过' }
        }
    } else {
        Fail-Step $p '倒计时'
        Set-Check 'countdown' '卡住' '没有课程或没有考试日'
        Set-Check 'dateshift' '未测' '倒计时那一步没过'
    }
    if ($script:Checks['countdown'].status -eq '通过' -and $script:Checks['dateshift'].status -eq '未测' -and (Test-AppAlive)) {
        if (-not (Wait-Shell $p)) { Fail-Step $p '拨日期'; Set-Check 'dateshift' '卡住' '倒计时那一步没过' }
        else {
            $lines = Find-Scrolled 'p1-shift' '距考试11天' 4
            $one = $false
            if ($lines) {
                $learn = Count-Needle $lines '新学'
                $one = ((Test-Has $lines '个考点') -or ($learn -ge 1)) -and ($learn -le 1) -and (Test-Has $lines '每天最多')
            }
            if ($lines -and $one) { Pass-Step $p '拨日期'; Set-Check 'dateshift' '通过' '—' }
            else { Fail-Step $p '拨日期'; Set-Check 'dateshift' '卡住' '倒计时那一步没过' }
        }
    }
    if (Mark-Dead $p) { Set-Check 'mock' '未测' '—'; Set-Check 'offtopic' '未测' '—'; return }
    $course = Find-Scrolled 'p1-course' '课程' 5
    if ($course) {
        $hit = Find-Top $course '课程'
        if ($hit) { Click-At $hit.X ($hit.Y + 52) | Out-Null; Start-Sleep -Seconds 1 }
    }
    $mockLines = Find-Scrolled 'p1-mock' '模考' 3
    if ($mockLines) { Click-Needle $mockLines '模考' | Out-Null; Start-Sleep -Seconds 1 }
    $panel = Find-Scrolled 'p1-timed' '计时作答' 3
    if (-not $panel) {
        $look = @(Get-Lines 'p1-mock-look')
        $why = '入口不在'
        if ((Test-Has $look '生成并开始限时模考') -or (Test-Has $look '用题库组一套计时卷') -or (Test-Has $look '考前限时综合练习')) { $why = '只有按课程时长出的卷' }
        elseif ((Test-Has $look '继续未交卷') -or (Test-Has $look '还有一套未交卷') -or (Test-Has $look '还有一份未交卷')) { $why = '还有未交卷' }
        Fail-Step $p '模考'
        Set-Check 'mock' '卡住' $why
    } else {
        Click-Needle $panel '计时作答' | Out-Null
        Start-Sleep -Milliseconds 400
        $minutes = @(Get-Lines 'p1-minutes')
        if (Click-Needle $minutes '时长') {
            Start-Sleep -Milliseconds 200
            Send-Keys '^a'
            Send-Keys '1'
            Start-Sleep -Milliseconds 200
        }
        $startLines = @(Get-Lines 'p1-start')
        if (-not (Click-Needle $startLines '开始作答')) { Fail-Step $p '模考'; Set-Check 'mock' '卡住' '入口不在' }
        else {
            $deadline = (Get-Date).AddSeconds(95)
            $done = $false
            while ((Get-Date) -lt $deadline) {
                if (Mark-Dead $p) { break }
                $now = @(Get-Lines 'p1-autosubmit')
                if (Test-Has $now '计时结束，已自动交卷') { $done = $true; break }
                Start-Sleep -Seconds 3
            }
            if ($done) { Pass-Step $p '模考'; Set-Check 'mock' '通过' '—' }
            elseif (-not $p.stuck -or $p.stuck -ne '进程退出') { Fail-Step $p '模考'; Set-Check 'mock' '卡住' '没看到自动交卷' }
        }
    }
    if (Mark-Dead $p) { Set-Check 'offtopic' '未测' '开放题没出来'; return }
    if (-not (Open-Book)) { Fail-Step $p '跑题'; Set-Check 'offtopic' '卡住' '开放题没出来'; return }
    Open-Companion | Out-Null
    $field = Find-Scrolled 'p1-open' '用自己的话讲这一章' 6
    if (-not $field) { Fail-Step $p '跑题'; Set-Check 'offtopic' '卡住' '开放题没出来'; return }
    Click-Needle $field '用自己的话讲这一章' | Out-Null
    Start-Sleep -Milliseconds 300
    Paste-Text $OffTopic
    Start-Sleep -Milliseconds 300
    $submit = @(Get-Lines 'p1-submit')
    if (-not (Click-Needle $submit '提交')) { Fail-Step $p '跑题'; Set-Check 'offtopic' '卡住' '开放题没出来'; return }
    $deadline = (Get-Date).AddSeconds(90)
    $blob = ''
    while ((Get-Date) -lt $deadline) {
        if (Mark-Dead $p) { Set-Check 'offtopic' '未测' '开放题没出来'; return }
        $blob = Get-Blob (Get-Lines 'p1-grade')
        if ($blob -match '没答到' -or $blob -match '这一点答到了' -or (Get-FailCode $blob)) { break }
        Start-Sleep -Seconds 3
    }
    if (($blob -match '没答到') -and ($blob -notmatch '这一点答到了')) { Pass-Step $p '跑题'; Set-Check 'offtopic' '通过' '—' }
    elseif ($blob -match '这一点答到了') { Fail-Step $p '跑题'; Set-Check 'offtopic' '卡住' '判成了答到' }
    else { Fail-Step $p '跑题'; Set-Check 'offtopic' '卡住' '开放题没出来' }
}
function Invoke-ReadAsk($p, [string]$personaHome, [string]$question, [switch]$Explain) {
    if (-not (Open-Session $p $personaHome)) { return }
    if (-not (Open-Book)) { Fail-Step $p '进书'; return }
    Pass-Step $p '进书'
    if (-not (Open-Companion)) { if ($Explain) { Fail-Step $p '讲解' }; Fail-Step $p '提问'; return }
    if ($Explain) {
        $lines = @(Get-Lines 'explain')
        if (Test-Has $lines '讲讲') { Click-Needle $lines '讲讲' | Out-Null; Start-Sleep -Seconds 1 }
        $code = Wait-Ai 'explain-wait' 50
        if ($code -eq 'dead') { Mark-Dead $p; return }
        Add-Count $p.explain $code
        if ($code -eq 'ok') { Pass-Step $p '讲解' } else { Fail-Step $p '讲解' }
    }
    if (Mark-Dead $p) { return }
    if (-not (Send-Ask $question)) { Fail-Step $p '提问'; return }
    $code = Wait-Ai 'ask-wait' 50
    if ($code -eq 'dead') { Mark-Dead $p; return }
    Add-Count $p.ask $code
    if ($code -eq 'ok') { Pass-Step $p '提问' } else { Fail-Step $p '提问' }
}
function Leave-Reader {
    for ($i = 0; $i -lt 3; $i++) {
        $lines = @(Get-Lines ("leave-{0}" -f $i))
        if ((Test-Has $lines '我的库') -or (Test-Has $lines '今天')) { return $true }
        if (Test-Has $lines '返回书架') { Click-Needle $lines '返回书架' | Out-Null; Start-Sleep -Milliseconds 800; continue }
        if ((Test-Has $lines '伴读') -or (Test-Has $lines '目录')) { Send-Keys '{ESC}'; Start-Sleep -Milliseconds 800; continue }
        return $false
    }
    return $false
}
function Invoke-P4($p) {
    $personaHome = 'D:\agent\personas\P4\home-r2'
    if (-not (Open-Session $p $personaHome)) { Set-Check 'cite' '未测' '—'; return }
    Leave-Reader | Out-Null
    $lines = Find-Scrolled 'p4-lib' '我的库' 6
    if (-not $lines -or -not (Click-Needle $lines '我的库')) { Fail-Step $p '进库'; Set-Check 'cite' '卡住' '库里没有对比'; return }
    Start-Sleep -Seconds 1
    Pass-Step $p '进库'
    $chip = Find-Scrolled 'p4-chip' '对比' 2
    if (-not $chip -or -not (Click-Needle $chip '对比')) { Fail-Step $p '点对比'; Set-Check 'cite' '卡住' '库里没有对比'; return }
    Start-Sleep -Seconds 1
    $listed = @(Get-Lines 'p4-list')
    $blob = Get-Blob $listed
    $count = $null
    foreach ($line in @($listed)) {
        $text = ([string]$line.Text) -replace '\s', ''
        if ($text -match '共(\d+)条') { $count = [int]$Matches[1]; break }
    }
    if ($blob -match '还没有对得上' -or $count -eq 0) { Fail-Step $p '点对比'; Set-Check 'cite' '卡住' '库里没有对比'; return }
    $anchor = Find-Text $listed '条'
    if (-not $anchor) { $anchor = Find-Text $listed '对比' }
    if (-not $anchor) { Fail-Step $p '点对比'; Set-Check 'cite' '卡住' '库里没有对比'; return }
    Click-At $anchor.X ($anchor.Y + 70) | Out-Null
    Start-Sleep -Seconds 2
    $reader = @(Get-Lines 'p4-cite')
    if ((Test-Has $reader '伴读') -or (Test-Has $reader '书架') -or (Test-Has $reader '收起伴读')) {
        Pass-Step $p '点对比'
        Set-Check 'cite' '进了书，高亮见截图' '—'
    } else {
        Fail-Step $p '点对比'
        Set-Check 'cite' '卡住' '没进书'
    }
}
function Invoke-P5($p) {
    $personaHome = 'D:\agent\personas\P5\home-r2'
    if (-not (Open-Session $p $personaHome)) { return }
    $script:DeclinedIncident = $false
    $pidToKill = $script:PersonaPid
    $launcher = $script:LauncherPid
    Stop-Process -Id $pidToKill -Force -ErrorAction SilentlyContinue
    if ($launcher) { Stop-Process -Id $launcher -Force -ErrorAction SilentlyContinue }
    $script:PersonaPid = 0
    $script:LauncherPid = 0
    $script:Win = [IntPtr]::Zero
    Start-Sleep -Seconds 1
    try { Start-App $personaHome } catch { Fail-Step $p '重开'; $p.crash = 1; return }
    $clicked = $false
    $deadline = (Get-Date).AddSeconds(12)
    while ((Get-Date) -lt $deadline) {
        $lines = @(Get-Lines 'p5-incident')
        if (Test-Has $lines '不发') { Click-Needle $lines '不发' | Out-Null; $script:DeclinedIncident = $true; $clicked = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not (Wait-Shell $p)) { Fail-Step $p '重开'; return }
    if ($clicked -or $script:DeclinedIncident) { Pass-Step $p '强杀重开' } else { Fail-Step $p '重开' }
}

$script:Checks = @{
    countdown = @{ status = '未测'; stuck = '—' }
    dateshift = @{ status = '未测'; stuck = '—' }
    mock = @{ status = '未测'; stuck = '—' }
    cite = @{ status = '未测'; stuck = '—' }
    offtopic = @{ status = '未测'; stuck = '—' }
}
$names = @{ P1 = '备考'; P2 = '朋友'; P3 = '睡前'; P4 = '综述'; P5 = '挑刺' }
$totals = @{ P1 = 5; P2 = 4; P3 = 3; P4 = 3; P5 = 2 }
# $Personas is a [string] parameter. $script:Personas is that same variable, so a List assigned there is coerced back to text and later enumerated as characters.
$wanted = @($Personas.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ -match '^P[1-5]$' })
$script:PersonaRows = New-Object System.Collections.Generic.List[object]
function Save-Run([int]$code) {
    $rows = $script:PersonaRows.ToArray()
    foreach ($persona in $rows) { if (-not $persona['stuck']) { $persona['stuck'] = '—' } }
    $body = @{ version = $Version; commit = $Commit; code = $code; personas = $rows; checks = $script:Checks }
    $serializer = New-Object System.Web.Script.Serialization.JavaScriptSerializer
    $serializer.MaxJsonLength = 2000000
    $json = $serializer.Serialize($body)
    [IO.File]::WriteAllText((Join-Path $OutDir 'result.json'), $json, (New-Object Text.UTF8Encoding $false))
}
try {
    foreach ($id in $wanted) {
        $persona = New-Persona $id $names[$id] $totals[$id]
        $script:PersonaRows.Add($persona)
        Note "$id begin"
        try {
            switch ($id) {
                'P1' { Invoke-P1 $persona }
                'P2' { Invoke-ReadAsk $persona 'D:\agent\personas\P2\home-r2' $AskP2 -Explain }
                'P3' { Invoke-ReadAsk $persona 'D:\agent\personas\P3\home-r2' $AskP3 }
                'P4' { Invoke-P4 $persona }
                'P5' { Invoke-P5 $persona }
            }
        } catch { Note-Safe ("$id " + $_.Exception.Message); Fail-Step $persona '中断' }
        Stop-App
        Save-Run 1
    }
} finally { Stop-App }
$passed = $true
foreach ($persona in $script:PersonaRows.ToArray()) { if ([int]$persona.steps_done -ne [int]$persona.steps_total) { $passed = $false } }
foreach ($key in @($script:Checks.Keys)) {
    $status = [string]$script:Checks[$key].status
    if ($status -ne '通过' -and $status -ne '进了书，高亮见截图') { $passed = $false }
}
$anyOpened = $false
foreach ($persona in $script:PersonaRows.ToArray()) { if ($persona.opened) { $anyOpened = $true } }
$code = 1
if ($passed) { $code = 0 }
elseif (-not $anyOpened) { $code = 2 }
Save-Run $code
$py = Join-Path $Repo 'tools\smoke\persona_retest_table.py'
$tablePath = Join-Path $OutDir 'table.txt'
$tableArgs = @($py, '--run', (Join-Path $OutDir 'result.json'), '--out', $tablePath)
if ($Baseline -and (Test-Path -LiteralPath $Baseline)) { $tableArgs += @('--baseline', $Baseline) }
& python @tableArgs
if ($LASTEXITCODE -ne 0) { Note 'table-failed' }
Note "code $code"
exit $code
