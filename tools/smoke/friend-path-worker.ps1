# Runs inside winbox's console session (started by friend-path.ps1). Drives the real Windows client through the seven friend-path steps.
# Stops at the first step that fails and says why. Screenshots hold book text: they stay in $OutDir on winbox and are never uploaded.
param(
    [Parameter(Mandatory)][string]$Repo,       # checkout to build and run
    [Parameter(Mandatory)][string]$OutDir,     # D:\agent\evidence\friend-path\<time>
    [Parameter(Mandatory)][string]$Book,       # PDF on winbox, kept out of Git
    [string]$Url = '',                          # isolated test server on the Mac (never the production 18791)
    [string]$PairCode = '',
    [string]$ClientSeed = '',                   # an already paired private client dir; copied per run, never modified
    [string]$Selection = '',                    # private JSON {selection, blocks[id=<sha>:p<page>:0]}; stays on winbox
    [int]$StartTimeoutSec = 150,
    [switch]$KeepGoing                          # 某步失败后仍尝试后面不依赖它的步骤（默认停在第一个失败）
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Win-Drive.ps1')
New-Item -ItemType Directory -Force $OutDir | Out-Null
$steps = @('启动', '上传一本书', '翻页', '选中就问出讲解', '边看边学出讲解', '做题（答完出讲解和小结）', '点出处弹原文卡')
$results = New-Object System.Collections.ArrayList
$script:Last = $null
function Note([string]$m) { "$(Get-Date -Format 'HH:mm:ss') $m" | Tee-Object -FilePath (Join-Path $OutDir 'run.log') -Append }
function Shot([int]$n, [string]$tag) {
    $png = Join-Path $OutDir ('{0}-{1}.png' -f $n, $tag); Save-Shot $png
    $lines = @(Read-Screen $png); $script:Last = $lines
    ($lines | ForEach-Object Text) -join "`n" | Set-Content -Encoding utf8 ([IO.Path]::ChangeExtension($png, 'txt'))
    return $lines
}
function Fail([int]$n, [string]$why) {
    [void]$results.Add([pscustomobject]@{ Step = $n; Name = $steps[$n - 1]; Ok = $false; Why = $why }); Note "步骤$n 失败：$why"; throw 'STEP_FAILED'
}
function Pass([int]$n, [string]$what) { [void]$results.Add([pscustomobject]@{ Step = $n; Name = $steps[$n - 1]; Ok = $true; Why = $what }); Note "步骤$n 通过：$what" }
function Has($lines, [string[]]$needles) { foreach ($s in $needles) { $m = Find-Text $lines $s; if ($m) { return $m } }; $null }
function Await-Text([int]$n, [string]$tag, [string[]]$needles, [int]$sec) {
    $deadline = (Get-Date).AddSeconds($sec)
    do { $lines = Shot $n $tag; $m = Has $lines $needles; if ($m) { return $m }; Start-Sleep -Seconds 2 } while ((Get-Date) -lt $deadline)
    return $null
}
function Page-Of($lines) { foreach ($l in $lines) { $mm = [regex]::Match($l.Text, '(\d+)[/／](\d+)'); if ($mm.Success) { return "$($mm.Groups[1].Value)/$($mm.Groups[2].Value)" } }; return $null }
# The lowest on-screen occurrence of a label (e.g. the last 「讲解这段」 button on a page).
function Find-Lowest($lines, [string]$needle) { $best = $null; foreach ($l in $lines) { $m = Find-Text @($l) $needle; if ($m -and (-not $best -or $m.Y -gt $best.Y)) { $best = $m } }; $best }
function Find-All($lines, [string]$needle) { $all = @(); foreach ($l in $lines) { $m = Find-Text @($l) $needle; if ($m) { $all += $m } }; $all | Sort-Object Y }
function Click-Label([int]$n, [string[]]$labels, [string]$why, [int]$sec = 15) {
    $m = Await-Text $n 'find' $labels $sec
    if (-not $m) { Fail $n "$why（屏幕上找不到「$($labels -join '」或「')」）" }
    Click-At $m.X $m.Y; Start-Sleep -Milliseconds 800
}

$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'; $env:GRADLE_USER_HOME = 'D:\agent\gradle-home'; $env:Path = "$env:JAVA_HOME\bin;$env:Path"
$lib = Join-Path $OutDir 'bookshelf'; $client = Join-Path $OutDir 'client'
New-Item -ItemType Directory -Force $lib, $client | Out-Null
if ($ClientSeed) { Copy-Item -Recurse -Force (Join-Path $ClientSeed '*') $client }
$env:COACH_LIBRARY_DIR = $lib; $env:COACH_CLIENT_DIR = $client; $env:COACH_TOKEN_FILE = (Join-Path $OutDir 'unused-token')
$env:COACH_URL = if ($Url) { $Url } else { 'http://127.0.0.1:18999' }
$serverPort = ([uri]$env:COACH_URL).Port
if ($serverPort -in 18791, 8791, 8790) { throw "拒绝：$serverPort 是生产端口，冒烟只用隔离测试端口。" }
Mark-ExistingClients
$gradle = Start-Process (Join-Path $Repo 'gradlew.bat') -ArgumentList ':desktopApp:run', '-Pcoach.course=', '--console=plain', '--offline' -WorkingDirectory $Repo `
    -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $OutDir 'client.out.log') -RedirectStandardError (Join-Path $OutDir 'client.err.log')
try {
    # ① 启动
    $deadline = (Get-Date).AddSeconds($StartTimeoutSec)
    while (-not (Find-CoachWindow) -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 2 }
    if ($script:Win -eq [IntPtr]::Zero) { Fail 1 "客户端窗口 $StartTimeoutSec 秒内没有出现（见 client.err.log）" }
    $m = Await-Text 1 'start' @('上传教材', '书架') 30
    if (-not $m) { Fail 1 '窗口出来了但没有看到书架' }
    Pass 1 '客户端窗口出现，书架可见'

    # ② 上传一本书
    Click-Label 2 @('上传教材') '找不到上传按钮'
    if (-not (Choose-File '上传教材' $Book)) { Fail 2 '没有弹出选书对话框' }
    $m = Await-Text 2 'shelf' @('教材已加入书架', '先读') 120
    if (-not $m) { Fail 2 '选了书但 120 秒内书架里没出现这本书' }
    Pass 2 '书出现在书架'

    # ③ 翻页
    Click-Label 3 @('先读', '继续看书') '找不到「先读」'
    $d = Await-Text 3 'dismiss' @('要开始学吗') 8
    if ($d) { Send-Keys '{ESC}'; Start-Sleep -Seconds 1 }   # 暂不开启：只读这本书
    $m = Await-Text 3 'reader' @('下一页') 60
    if (-not $m) { Fail 3 '没有进到阅读器' }
    $page0 = $null
    foreach ($try in 1..5) {
        $before = Shot 3 'page-before'
        $page0 = Page-Of $before
        if ($page0) { break }; Start-Sleep -Seconds 2
    }
    $page1 = $page0
    foreach ($try in 1..3) {
        Click-At $m.X $m.Y
        $stop = (Get-Date).AddSeconds(8)
        while ((Get-Date) -lt $stop -and (-not $page1 -or $page1 -eq $page0)) {
            Start-Sleep -Seconds 2; $after = Shot 3 'page-after'
            $page1 = Page-Of $after
        }
        if ($page1 -and $page1 -ne $page0) { break }
    }
    if (-not $page0 -or -not $page1 -or $page0 -eq $page1) { Fail 3 "点了下一页页码没变（前 $page0，后 $page1）" }
    Pass 3 "页码 $page0 → $page1"

    # ④ 前提：隔离测试服务必须连得上，连不上就停在这一步并说清楚
    if (-not $Url) { Fail 4 '没有给隔离测试服务地址（-Url），问讲解必须连 Mac 上的测试服务' }
    $u = [uri]$Url
    $tcp = New-Object Net.Sockets.TcpClient
    $reach = $false
    try { $reach = $tcp.ConnectAsync($u.Host, $u.Port).Wait(5000) -and $tcp.Connected } catch { }
    $tcp.Close()
    if (-not $reach) { Fail 4 "测试服务 $($u.Host):$($u.Port) 5 秒内连不上，讲解问不出去（端口写错或服务没起）" }
    if (-not $Selection -or -not (Test-Path $Selection)) { Fail 4 '没有可信选段文件（-Selection）' }
    # ④ 选中就问出讲解：跳到可信选段所在页 → 文字视图 → 点该段的「讲解这段」→ 同意发给 Mac → 等讲解或报错
    $sel = Get-Content $Selection -Raw -Encoding UTF8 | ConvertFrom-Json
    $physPage = [int]([regex]::Match($sel.blocks[0].id, ':p(\d+):').Groups[1].Value)
    $g = Await-Text 4 'goto' @('前往') 10
    if (-not $g) { Fail 4 '找不到页码跳转的「前往」' }
    Click-At ($g.X - 106) $g.Y; Send-Keys '^a'; Send-Keys "$physPage"; Start-Sleep -Milliseconds 500
    Click-At $g.X $g.Y; Start-Sleep -Seconds 3
    Click-Label 4 @('看文字') '找不到「看文字」'; Start-Sleep -Seconds 2
    Click-Label 4 @('讲解') '找不到「讲解」'; Start-Sleep -Seconds 3
    $lines = Shot 4 'blocks'
    # 可信选段所在的段落：用它开头的几个词在屏幕上定位，再取它下面最近的「讲解这段」
    $words = (($sel.selection -replace '[^A-Za-z ]', ' ').Trim() -split '\s+' | Select-Object -First 4) -join ''
    $para = $null; foreach ($l in $lines) { if ($l.Text -replace '[^A-Za-z]', '' -like "*$words*") { $para = Find-Text @($l) ($l.Text.Substring(0, 6)); break } }
    $buttons = @(Find-All $lines '讲解这段')
    $target = if ($para) { $buttons | Where-Object { $_.Y -gt $para.Y } | Select-Object -First 1 } else { $null }
    if (-not $target) { Fail 4 '讲解模式下找不到可信选段所在段落的「讲解这段」' }
    Click-At $target.X $target.Y
    $c = Await-Text 4 'consent' @('允许并讲解') 15
    if ($c) { Click-At $c.X $c.Y }       # 这本书第一次讲解的同意框（开发验收允许点）
    $deadline = (Get-Date).AddSeconds(120); $outcome = $null; $errText = $null; $n = 0
    while ((Get-Date) -lt $deadline -and -not $outcome) {
        Start-Sleep -Seconds 4; $n++
        $seen = @()
        foreach ($view in 0, 1) {                       # 讲解出现在原文段落下面：每轮看顶部和滚到底各一次，不累计滚动
            if ($view -eq 1) { Scroll-At 900 700 -10; Start-Sleep -Milliseconds 800 }
            $lines = Shot 4 "result$n-$view"; $seen += $lines
        }
        Scroll-At 900 700 20; Start-Sleep -Milliseconds 500
        $e = Has $seen @('无法讲解', '尚未核实', '尚未接通', '连不上', '超时', '没有得到可核对')
        if ($e) { $outcome = 'error'; $errText = (($seen | Where-Object { $_.Text -match '无法讲解|尚未核实|尚未接通|连不上|超时|没有得到可核对' } | Select-Object -First 1).Text) }
        elseif (Has $seen @('出处')) { $outcome = 'ok' }
    }
    if ($outcome -eq 'error') { Fail 4 "服务回了拒绝：$errText" }
    if ($outcome -ne 'ok') { Fail 4 '120 秒内既没有讲解也没有报错' }
    Pass 4 '选中一段后得到带出处的讲解'
    # ⑦ 先做（依赖第 4 步留在屏幕上的讲解）：点「出处」，应弹出原文卡
    $cardOk = $null
    # 出处可能在视口外：先看当前屏，找不到就先往下再往上有界滚动（最多 8 次）
    $o = $null
    foreach ($k in 0..8) {
        if ($k -gt 0) { Scroll-At 900 700 $(if ($k -le 4) { -5 } else { 5 }); Start-Sleep -Milliseconds 700 }
        $o = Await-Text 7 'cite' @('出处') 1
        if ($o) { break }
    }
    if (-not $o) { $cardOk = '讲解下面没有「出处」链接' }
    else {
        Click-At $o.X $o.Y; Start-Sleep -Seconds 3
        $lines = Shot 7 'card'
        if (-not (Has $lines @('原文')) -or -not (Has $lines @("第$physPage页", "第 $physPage 页"))) { $cardOk = '点了出处但没有弹出标着页码的原文卡' }
        Send-Keys '{ESC}'; Start-Sleep -Seconds 1
    }

    # ⑤ 边看边学出讲解
    $five = $null
    try {
        Click-Label 5 @('开始学') '找不到「开始学」'; Start-Sleep -Seconds 2
        Click-Label 5 @('边看边学') '找不到「边看边学」'; Start-Sleep -Seconds 1
        Click-Label 5 @('开始边看边学') '找不到「开始边看边学」'
        $deadline = (Get-Date).AddSeconds(90); $res = $null
        while ((Get-Date) -lt $deadline -and -not $res) {
            Start-Sleep -Seconds 5; $lines = Shot 5 'learn'
            $e = $lines | Where-Object { $_.Text -match '尚未接通|无法讲解|尚未核实|连不上|超时' } | Select-Object -First 1
            if ($e) { $res = "拒绝：$($e.Text)" } elseif (Has $lines @('出处')) { $res = 'ok' }
        }
        if ($res -eq 'ok') { Pass 5 '边看边学出了带出处的讲解' } elseif ($res) { Fail 5 "边看边学的讲解没出来，界面说「$($res.Substring(3))」" } else { Fail 5 '90 秒内边看边学既没有讲解也没有报错' }
    } catch { if ("$_" -ne 'STEP_FAILED') { throw }; $five = 'failed' }
    if ($five -and -not $KeepGoing) { throw 'STEP_FAILED' }

    # ⑥ 做题学：需要同一本书已准入的选择题；缺题不算通过
    $six = $null
    try { Fail 6 '尚未实现：做题（需要该书已准入的选择题，TON-1453 未签前缺题不记成功）' } catch { if ("$_" -ne 'STEP_FAILED') { throw }; $six = 'failed' }
    if (-not $KeepGoing) { throw 'STEP_FAILED' }

    if ($cardOk) { Fail 7 $cardOk } else { Pass 7 "点出处弹出第 $physPage 页原文卡" }
} catch {
    if ("$_" -ne 'STEP_FAILED') { Note "脚本异常：$_"; [void]$results.Add([pscustomobject]@{ Step = $results.Count + 1; Name = $steps[[Math]::Min($results.Count, 6)]; Ok = $false; Why = "脚本异常：$_" }) }
} finally {
    $passed = @($results | Where-Object Ok).Count
    $summary = [pscustomobject]@{ passed = $passed; total = 7; time = (Get-Date -Format 'o'); outDir = $OutDir; server = $env:COACH_URL; steps = $results }
    $summary | ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 (Join-Path $OutDir 'result.json')
    Stop-MyClient
    if ($ClientSeed) { Remove-Item -Recurse -Force $client -ErrorAction SilentlyContinue }   # 配对凭据只在运行期存在
    Stop-Process -Id $gradle.Id -Force -ErrorAction SilentlyContinue
    Note "结果 $passed/7"
}
