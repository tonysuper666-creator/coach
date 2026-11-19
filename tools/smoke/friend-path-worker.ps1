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
    [switch]$Background,
    [string]$Runtime = '',
    [ValidatePattern('^[\p{L}\p{N}_-]{1,32}$')][string]$Marker = '',
    [int]$StartTimeoutSec = 150,
    [switch]$KeepGoing                          # 某步失败后仍尝试后面不依赖它的步骤（默认停在第一个失败）
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Win-Drive.ps1')
. (Join-Path $PSScriptRoot 'Smoke-Memory.ps1')
New-Item -ItemType Directory -Force $OutDir | Out-Null
$script:ForegroundTracePath = Join-Path $OutDir 'foreground.jsonl'
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
function Get-PassageServiceError($lines) {
    # Match the status row on current and older installed clients.
    foreach ($line in $lines) {
        $row = $line.Text -replace '\s', ''
        if ($row -match '^讲解这段((?:今天的|这个月的)?讲解额度用完了)(?:[，,。.]|$)') { return $Matches[1] }
        if ($row -match '^(?:再试一次)?(讲解服务暂时不可用)(?:[，,。.]|$)') { return $Matches[1] }
    }
}
function Get-AskReplyState($lines, [int]$page) {
    $title = Find-Text $lines '就这段问一句'
    $close = Find-Text $lines '回到阅读'
    if (-not $title -or -not $close) { return 'waiting' }
    # Only inspect text inside this dialog. The reader behind it has its own
    # citation and page number even while the question is pending or refused.
    $body = foreach ($line in $lines) {
        $words = foreach ($word in $line.Words) {
            $box = $word.BoundingRect
            if ($box.X -ge $title.X1 -and $box.X + $box.Width -le $close.X2 -and
                $box.Y -gt $title.Y2 -and $box.Y + $box.Height -lt $close.Y1) { $word.Text }
        }
        ($words -join '') -replace '\s', ''
    }
    if ($body -match '^(再试一次|重新配对)$' -or
        $body -match '尚未接通|无法回答|不能回答|尚未核实|连不上|超时|暂时读不到回答|讲解服务暂时不可用|额度(?:已)?用完') { return 'refused' }
    if ($body -match '正在读这段问题') { return 'waiting' }
    # bookPassageLabel adds the paragraph number when it can resolve the source block.
    if ($body -match "^出处[·•.．:：]?第${page}页(?:[·•.．:：]?第[1-9][0-9]*段)?$") { return 'answered' }
    return 'waiting'
}
function Await-Text([int]$n, [string]$tag, [string[]]$needles, [int]$sec) {
    $deadline = (Get-Date).AddSeconds($sec)
    do { $lines = Shot $n $tag; $m = Has $lines $needles; if ($m) { return $m }; Start-Sleep -Seconds 2 } while ((Get-Date) -lt $deadline)
    return $null
}
function Page-Of($lines) { foreach ($l in $lines) { $mm = [regex]::Match($l.Text, '(\d+)[/／](\d+)'); if ($mm.Success) { return "$($mm.Groups[1].Value)/$($mm.Groups[2].Value)" } }; return $null }
# At either edge of the book the disabled navigation label may disappear in OCR.
# Enlarge the counter beside its visible neighbour; keep the current page and total strictly numeric.
function Get-OriginalPageNumber($lines, [string]$png) {
    $prev = Find-Text $lines '上一页'
    $next = Find-Text $lines '下一页'
    if (-not $prev -and -not $next) { return }
    $anchor = if ($next) { $next } else { $prev }
    $height = $anchor.Y2 - $anchor.Y1
    $src = [Drawing.Bitmap]::new($png)
    try {
        $left = if ($prev) { $prev.X2 } else { [math]::Max(0, $next.X1 - 16 * $height) }
        $right = if ($next) { $next.X1 } else { [math]::Min($src.Width, $prev.X2 + 16 * $height) }
        $top = [math]::Max(0, $anchor.Y1 - $height)
        $bottom = [math]::Min($src.Height, $anchor.Y2 + $height)
        if ($left -ge $right -or $top -ge $bottom) { return }
        $rect = [Drawing.Rectangle]::new($left, $top, $right - $left, $bottom - $top)
        $zoom = [Drawing.Bitmap]::new($rect.Width * 3, $rect.Height * 3)
        $file = Join-Path (Split-Path -Parent $png) ('counter-' + [guid]::NewGuid() + '.png')
        try {
            $g = [Drawing.Graphics]::FromImage($zoom)
            try {
                $g.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                $g.DrawImage($src, [Drawing.Rectangle]::new(0, 0, $zoom.Width, $zoom.Height), $rect, [Drawing.GraphicsUnit]::Pixel)
            } finally { $g.Dispose() }
            $zoom.Save($file, [Drawing.Imaging.ImageFormat]::Png)
            $pages = @(foreach ($line in Read-Screen $file) {
                if (($line.Text -replace '\s', '') -match '^([1-9]\d*)[/／]([1-9]\d*)$') { $Matches[1] }
            })
            $unique = @($pages | Sort-Object -Unique)
            if ($unique.Count -eq 1) { return $unique[0] }
        } finally {
            $zoom.Dispose()
            Remove-Item -LiteralPath $file, ([IO.Path]::ChangeExtension($file, 'inv.png')) -ErrorAction SilentlyContinue
        }
    } finally { $src.Dispose() }
}
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
$env:COACH_LIBRARY_DIR = $lib; $env:COACH_CLIENT_DIR = $client; $env:COACH_TOKEN_FILE = (Join-Path $OutDir 'unused-token')
$env:COACH_URL = if ($Url) { $Url } else { 'http://127.0.0.1:18999' }
$serverPort = ([uri]$env:COACH_URL).Port
if ($serverPort -in 18791, 8791, 8790) { throw "拒绝：$serverPort 是生产端口，冒烟只用隔离测试端口。" }
$script:RepoMarker = $Repo
# Other Coach windows/heavy jobs may coexist; only input/capture is serialized.
Mark-ExistingClients
$runTask = ':desktopApp:run'
if ($Background) {
    $runTask = ':desktopApp:smokeRun'
    $env:COACH_SMOKE_ENABLED = '1'
    $env:COACH_SMOKE_CONTROL_FILE = Join-Path $OutDir 'control.private.json'
} else {
    $env:COACH_SMOKE_ENABLED = $null
    $env:COACH_SMOKE_CONTROL_FILE = $null
}
# A shared Gradle daemon is outside the interactive invocation's process job.
# Use a single-use daemon so even a timeout before AppPid discovery owns the tree.
$launchArgs = @($runTask, '-Pcoach.course=', '--console=plain', '--offline', '--no-daemon')
$memoryLease = Enter-SmokeMemory
try {
if ($ClientSeed) { Copy-Item -Recurse -Force (Join-Path $ClientSeed '*') $client }
$gradle = $null
if ($Runtime) {
    $Runtime = (Resolve-Path -LiteralPath $Runtime).Path
    $revision = (& git -C $Repo rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or (Get-Content (Join-Path $Runtime 'revision.txt') -Raw).Trim() -ne $revision) { throw 'Runtime source revision differs from checkout' }
    if ((Get-Content (Join-Path $Runtime 'dirty.txt') -Raw).Trim()) { throw 'Runtime was built from uncommitted source' }
    $classpath = ((Get-Content (Join-Path $Runtime 'classpath.txt')) | ForEach-Object { Join-Path $Runtime $_ }) -join ';'
    # Preparation is a separate Mac heavy call. The foreground path still runs
    # the production MainKt entry; only -Background selects development controls.
    $entry = if ($Background) { 'com.coach.desktop.smoke.SmokeMain' } else { 'com.coach.desktop.MainKt' }
    # javaw has no console for Windows Terminal to activate during background startup.
    $java = if ($Background) { 'javaw.exe' } else { 'java.exe' }
    $gradle = Start-Process "$env:JAVA_HOME/bin/$java" -ArgumentList ('-cp "' + $classpath + '" ' + $entry) -WorkingDirectory $Repo -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $OutDir 'client.out.log') -RedirectStandardError (Join-Path $OutDir 'client.err.log')
} else {
    $gradle = Start-Process (Join-Path $Repo 'gradlew.bat') -ArgumentList $launchArgs -WorkingDirectory $Repo `
    -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $OutDir 'client.out.log') -RedirectStandardError (Join-Path $OutDir 'client.err.log')
}
try {
    # ① 启动
    $deadline = (Get-Date).AddSeconds($StartTimeoutSec)
    while (-not (Find-CoachWindow) -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 2 }
    if ($script:Win -eq [IntPtr]::Zero) { Fail 1 "客户端窗口 $StartTimeoutSec 秒内没有出现（见 client.err.log）" }
    Set-SmokeMemoryClient $memoryLease $script:AppPid
    $m = Await-Text 1 'start' @('上传教材', '书架') 30
    if (-not $m) { Fail 1 '窗口出来了但没有看到书架' }
    Pass 1 '客户端窗口出现，书架可见'

    # ② 上传一本书
    Click-Label 2 @('上传教材') '找不到上传按钮'
    if (-not (Choose-File '上传教材' $Book)) { Fail 2 $script:ChooseFileWhy }
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
    Click-Label 4 @('看文字') '找不到「看文字」'
    if (-not (Await-Text 4 'text-mode' @('看原页') 15)) { Fail 4 '点击「看文字」后没有切换到文字视图' }
    Click-Label 4 @('讲解') '找不到「讲解」'
    if (-not (Await-Text 4 'explain-mode' @('回到原文') 15)) { Fail 4 '点击「讲解」后没有进入讲解模式' }
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
            $lines = Shot 4 "result$n-$view"
            $serviceError = Get-PassageServiceError $lines
            if ($serviceError) { Fail 4 "服务回了拒绝：$serviceError" }
            $seen += $lines
        }
        Scroll-At 900 700 20; Start-Sleep -Milliseconds 500
        $e = Has $seen @('无法讲解', '尚未核实', '尚未接通', '连不上', '超时', '没有得到可核对')
        if ($e) { $outcome = 'error'; $errText = (($seen | Where-Object { $_.Text -match '无法讲解|尚未核实|尚未接通|连不上|超时|没有得到可核对' } | Select-Object -First 1).Text) }
        elseif (Has $seen @('出处')) { $outcome = 'ok' }
    }
    if ($outcome -eq 'error') { Fail 4 "服务回了拒绝：$errText" }
    if ($outcome -ne 'ok') { Fail 4 '120 秒内既没有讲解也没有报错' }
    $explainOk = $true
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

    # ④（续）实际输入问题：选段提问 → 选中该段 → 粘贴问题（剪贴板，避开输入法）→ 点发送 → 等带出处的回答
    $askWhy = $null
    try {
        Click-Label 4 @('选段提问') '找不到「选段提问」'; Start-Sleep -Seconds 2
        $lines = Shot 4 'ask-dlg'
        $pp = Find-PickerParagraph $lines $words
        if (-not $pp) { throw '提问对话框里找不到可信选段所在的段落' }
        Click-At $pp.X $pp.Y; Start-Sleep -Seconds 2
        $lines = Shot 4 'ask-picked'
        # A long selected paragraph pushes the field below the dialog viewport.
        # Require both edges of the input region before clicking inside it.
        $ctr = $null; $lab = $null
        foreach ($scrollTry in 0..10) {
            $ctr = Has $lines @('0/500', '/500'); $lab = Has $lines @('想问什么')
            if ($ctr -and $lab) { break }
            $title = Has $lines @('就这段问一句')
            if (-not $title) { throw '找不到提问对话框' }
            Scroll-At ($title.X + 200) ($title.Y + 300) -3
            Start-Sleep -Milliseconds 700
            $lines = Shot 4 "ask-input-$scrollTry"
        }
        if (-not $ctr -or -not $lab -or $ctr.Y -le $lab.Y) { throw '找不到完整可见的提问输入框' }
        Click-At ($lab.X + 100) ([int](($lab.Y + $ctr.Y) / 2))
        $question = '用大白话解释一下，这里的专用人力资本是什么意思？'
        if ($Marker) { $question = "$Marker $question" }
        Paste-Text $question; Start-Sleep -Seconds 1
        if ($Marker) {
            $typed = Shot 4 'ask-marker'
            if (-not (Has $typed @($Marker))) { throw '本轮标记没有出现在自己的提问框中' }
        }
        # Locate the visible button after scrolling; the input may have reflowed.
        $sent = $false
        Scroll-At 1080 700 -3; Start-Sleep -Milliseconds 800   # 先滚一下让对话框布局稳定，再按截图定位
        foreach ($try in 1..4) {
            $lines = Shot 4 'ask-send'; $send = Has $lines @('发送')
            if (-not $send) { Scroll-At 1080 700 -3; Start-Sleep -Milliseconds 600; continue }
            Start-Sleep -Milliseconds 500
            Click-At $send.X $send.Y; $sent = $true; break
        }
        if (-not $sent) { throw '找不到「发送」按钮的位置' }
        $deadline = (Get-Date).AddSeconds(120); $got = $null; $i = 0
        while ((Get-Date) -lt $deadline -and -not $got) {
            Start-Sleep -Seconds 6; $i++
            Scroll-At 1080 600 -10; Start-Sleep -Milliseconds 600
            $lines = Shot 4 "ask-wait$i"
            $replyState = Get-AskReplyState $lines $physPage
            if ($replyState -eq 'refused') { throw '选段提问弹框显示请求失败，见本轮私有截图' }
            $got = $replyState -eq 'answered'
        }
        if (-not $got) { throw '120 秒内提问没有得到带出处的回答' }
        Send-Keys '{ESC}'; Start-Sleep -Seconds 1
    } catch { if ("$_" -like 'DESKTOP_BUSY*') { throw }; $askWhy = "$_"; try { Send-Keys '{ESC}' } catch { } }
    if ($askWhy) {
        try { Fail 4 "讲解这段可用，但实际提问失败：$askWhy" } catch { if ("$_" -ne 'STEP_FAILED') { throw }; if (-not $KeepGoing) { throw 'STEP_FAILED' } }
    } else { Pass 4 '选中一段能得到带出处的讲解，也能输入问题得到带出处的回答' }

    # ⑤ 边看边学出讲解
    $five = $null
    try {
        Click-Label 5 @('开始学') '找不到「开始学」'; Start-Sleep -Seconds 2
        Click-Label 5 @('边看边学') '找不到「边看边学」'; Start-Sleep -Seconds 1
        Click-Label 5 @('开始边看边学') '找不到「开始边看边学」'
        $deadline = (Get-Date).AddSeconds(90); $res = $null
        while ((Get-Date) -lt $deadline -and -not $res) {
            Start-Sleep -Seconds 5; $lines = Shot 5 'learn'
            $allow = Has $lines @('允许并讲解')
            if ($allow) { Click-At $allow.X $allow.Y; continue }
            $e = $lines | Where-Object { $_.Text -match '尚未接通|无法讲解|尚未核实|连不上|超时|没有可用的讲解|讲解暂时读不到|讲解服务暂时不可用|额度.*用完|没有可核对的出处|出处已过期' } | Select-Object -First 1
            if ($e) { $res = "拒绝：$($e.Text)"; continue }
            # 「本节原文」不是讲解的出处。讲解标题与带页码的出处之间必须有正文。
            $heading = $lines | Where-Object { ($_.Text -replace '\s', '') -eq '讲解' } | Select-Object -First 1
            $source = $lines | Where-Object { $_.Text -match '出处.*第\s*\d+\s*页' } | Select-Object -First 1
            if ($heading -and $source) {
                $headingBox = Find-Text @($heading) $heading.Text
                $sourceBox = Find-Text @($source) $source.Text
                $body = @($lines | Where-Object {
                    $box = Find-Text @($_) $_.Text
                    $box -and $box.Y -gt $headingBox.Y -and $box.Y -lt $sourceBox.Y -and
                        $_.Text -notmatch '正在读取|允许并讲解|发送原文'
                })
                if ($body.Count -gt 0) { $res = 'ok' }
            }
        }
        if ($res -eq 'ok') {
            $sourcePage = [regex]::Match($source.Text, '第\s*(\d+)\s*页').Groups[1].Value
            Click-At $sourceBox.X $sourceBox.Y
            $deadline = (Get-Date).AddSeconds(30)
            $verified = $false
            $switched = $false
            $problem = '出处没有回到标注页的原文（见 5-source.png）'
            do {
                $lines = Shot 5 'source'
                $returned = Find-Text $lines '返回学习'
                if ($returned) {
                    if (Has $lines @('无法确认原句', '出处已过期', '无法定位')) {
                        $problem = '出处页打开了，但原句未能核对（见 5-source.png）'
                        break
                    }
                    # A saved text view reports a reflowed range, sometimes spanning two PDF pages.
                    # Switch presentation once, without using the jump field or turning a page.
                    $original = Find-Text $lines '看原页'
                    if ($original -and -not $switched) {
                        Click-At $original.X $original.Y
                        $switched = $true
                    } elseif ((Find-Text $lines '看文字') -and (Get-OriginalPageNumber $lines (Join-Path $OutDir '5-source.png')) -eq $sourcePage) {
                        $verified = $true
                        break
                    }
                }
                Start-Sleep -Seconds 1
            } while ((Get-Date) -lt $deadline)
            if ($returned -and ($verified -or $KeepGoing)) { Click-At $returned.X $returned.Y }
            if (-not $verified) { Fail 5 $problem }
            Pass 5 '边看边学出了讲解正文和带页码的出处，点出处回到对应原文页'
        } elseif ($res) { Fail 5 "边看边学的讲解没出来，界面说「$($res.Substring(3))」" } else { Fail 5 '90 秒内没有同时看到讲解正文和带页码的出处（见 5-learn.png）' }
    } catch { if ("$_" -ne 'STEP_FAILED') { throw }; $five = 'failed' }
    if ($five -and -not $KeepGoing) { throw 'STEP_FAILED' }

    # ⑥ 朋友自己的书：按节现出题，真实点一个选项，再等答后讲解和小结；不依赖整理题库准入。
    $six = $null
    try {
        Scroll-At 900 700 30; Start-Sleep -Seconds 1
        Click-Label 6 @('返回书架') '无法从学习页返回书架'
        Click-Label 6 @('做题学') '书架没有做题学入口'
        # 已开启的书直接进入学习页；出题方式仍沿用这本书的设置。
        Click-Label 6 @('出题：', '出题:') '找不到出题设置'
        $settings = Await-Text 6 'settings' @('出题方式') 15
        if (-not $settings) { Fail 6 '出题方式对话框没有打开' }
        # 背景页也有「出题：选择」；只点击对话框标题下面的选项，避免点到背景而关闭弹窗。
        $choice = Find-All $script:Last '选择' | Where-Object { $_.Y -gt $settings.Y } | Select-Object -First 1
        if (-not $choice) { Fail 6 '出题方式对话框没有选择题选项' }
        Click-At $choice.X $choice.Y; Start-Sleep -Milliseconds 800
        Click-Label 6 @('确定') '无法确认选择题设置'
        $deadline = (Get-Date).AddSeconds(120); $question = $false; $picked = $null
        while ((Get-Date) -lt $deadline -and -not $question) {
            $lines = Shot 6 'question'
            $allow = Has $lines @('允许并出题')
            if ($allow) { Click-At $allow.X $allow.Y; Start-Sleep -Seconds 2; continue }
            if (Has $lines @('格式完整', '出题暂时读不到', '没有足够的正文', '需要连上', '尚未核实', '额度', '重新配对')) {
                Fail 6 '本节出题被拒绝或不可用，见 winbox 私有截图 6-question.png'
            }
            $anchor = Has $lines @('查看对应原文')
            if ((Has $lines @('本次练习')) -and $anchor) {
                # 只在题目原文按钮下找独立的 A 字母；不以固定屏幕位置或「猜的」芯片当成作答。
                foreach ($line in $lines) { foreach ($word in $line.Words) {
                    if ($word.Text -eq 'A' -and $word.BoundingRect.Y -gt $anchor.Y) {
                        $picked = $word.BoundingRect; break
                    }
                }; if ($picked) { break } }
                if ($picked) { $question = $true }
            }
            if (-not $question) { Start-Sleep -Seconds 3 }
        }
        if (-not $question) { Fail 6 '120 秒内没有出现可作答的本次练习选择题' }
        Click-At ([int]$picked.X + 45) ([int]$picked.Y + 12)
        Start-Sleep -Seconds 2
        $selected = $false; $explained = $false; $cited = $false; $summary = $false
        foreach ($view in 0..8) {
            $lines = Shot 6 "answered-$view"
            if (Has $lines @('你选择：', '你选择:')) { $selected = $true }
            if (Has $lines @('答后讲解')) { $explained = $true }
            if (Has $lines @('出处')) { $cited = $true }
            if (Has $lines @('小结')) { $summary = $true }
            if ($selected -and $explained -and $cited -and $summary) { break }
            Scroll-At 900 700 -2; Start-Sleep -Seconds 1
        }
        if (-not $selected) { Fail 6 '点击选项后没有看到已选答案' }
        if (-not $explained -or -not $cited) { Fail 6 '已作答，但没有看到带出处的答后讲解' }
        if (-not $summary) { Fail 6 '已作答并看到讲解，但没有小结' }
        Pass 6 '本节现出选择题，真实作答后出现已选答案、带出处的答后讲解和小结（6-question、6-answered 私有截图）'
    } catch { if ("$_" -ne 'STEP_FAILED') { throw }; $six = 'failed' }
    if ($six -and -not $KeepGoing) { throw 'STEP_FAILED' }

    if ($cardOk) { Fail 7 $cardOk } else { Pass 7 "点出处弹出第 $physPage 页原文卡" }
} catch {
    if ("$_" -like 'DESKTOP_BUSY*') { $n = $results.Count + 1; [void]$results.Add([pscustomobject]@{ Step = $n; Name = $steps[[Math]::Min($n - 1, 6)]; Ok = $false; Why = "$_" }); Note "$_" }
    elseif ("$_" -ne 'STEP_FAILED') { Note "脚本异常：$_"; [void]$results.Add([pscustomobject]@{ Step = $results.Count + 1; Name = $steps[[Math]::Min($results.Count, 6)]; Ok = $false; Why = "脚本异常：$_" }) }
} finally {
    $passed = @($results | Where-Object Ok).Count
    $summary = [pscustomobject]@{ passed = $passed; total = 7; marker = $Marker; time = (Get-Date -Format 'o'); outDir = $OutDir; server = $env:COACH_URL; steps = $results }
    $summary | ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 (Join-Path $OutDir 'result.json')
    Stop-MyClient
    # Kill only the exact launcher tree this worker created; its Java wrapper
    # must not survive a startup failure and launch an orphan window later.
    $launcher = Get-Process -Id $gradle.Id -ErrorAction SilentlyContinue
    if ($launcher -and $launcher.StartTime -eq $gradle.StartTime) {
        $killer = Start-Process taskkill.exe -ArgumentList "/PID $($gradle.Id) /T /F" -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $OutDir 'cleanup.out.log') -RedirectStandardError (Join-Path $OutDir 'cleanup.err.log')
        if (-not $killer.WaitForExit(10000)) {
            Stop-Process -Id $killer.Id -Force -ErrorAction SilentlyContinue
            throw 'Owned launcher cleanup timed out'
        }
    }
    Note "结果 $passed/7"
}
} finally {
    if ($ClientSeed) { Remove-Item -Recurse -Force $client -ErrorAction SilentlyContinue }   # 配对凭据只在运行期存在
    Exit-SmokeMemory $memoryLease
}
