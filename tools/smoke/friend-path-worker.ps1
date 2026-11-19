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
    [int]$BuildTimeoutSec = 900,
    [int]$LessonNumber = 181,                   # 第⑤步要选的整节：目录里的序号（181 = 零基 180，物理第 150 页，在已授权的 131–156 页内）
    [int]$StopAfter = 7,                        # 只跑到这一步（5 = 只占第 ⑤ 步区段，不进第 ⑥ 步）
    [int]$LessonPage = 150,                     # 该节应落的物理页；界面标签不是这页就不发请求

    [switch]$KeepGoing                          # 某步失败后仍尝试后面不依赖它的步骤（默认停在第一个失败）
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Win-Drive.ps1')
. (Join-Path $PSScriptRoot 'Smoke-Memory.ps1')
. (Join-Path $PSScriptRoot 'Lesson-Range.ps1')
New-Item -ItemType Directory -Force $OutDir | Out-Null
$script:ForegroundTracePath = Join-Path $OutDir 'foreground.jsonl'
$steps = @('启动', '上传一本书', '翻页', '选中就问出讲解', '边看边学出讲解', '做题（答完出讲解和小结）', '点出处弹原文卡')
$results = New-Object System.Collections.ArrayList
$script:Last = $null
$script:LastPng = ''
function Note([string]$m) { "$(Get-Date -Format 'HH:mm:ss') $m" | Tee-Object -FilePath (Join-Path $OutDir 'run.log') -Append }
function Shot([int]$n, [string]$tag) {
    $png = Join-Path $OutDir ('{0}-{1}.png' -f $n, $tag); Save-Shot $png
    $lines = @(Read-Screen $png); $script:Last = $lines; $script:LastPng = $png
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

function Invoke-FriendBuild([string]$BuildRepo, [string]$BuildOutDir, [bool]$IsBackground, [int]$TimeoutSec) {
    [string[]]$tasks = if ($IsBackground) { @(':desktopApp:classes', ':desktopApp:smokeClasses') } else { @(':desktopApp:classes') }
    [string[]]$buildArgs = @($tasks) + @('-Pcoach.course=', '--console=plain', '--offline', '--no-daemon')
    $buildOut = Join-Path $BuildOutDir 'build.out.log'
    $buildErr = Join-Path $BuildOutDir 'build.err.log'
    $buildLog = Join-Path $BuildOutDir 'build.log'
    $proc = Start-Process (Join-Path $BuildRepo 'gradlew.bat') -ArgumentList $buildArgs -WorkingDirectory $BuildRepo `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput $buildOut -RedirectStandardError $buildErr
    $null = $proc.Handle
    $startTicks = Get-TrackedProcessStartTicks $proc
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $timedOut = $false
    try {
        while (-not $proc.WaitForExit(1000)) {
            if ($sw.Elapsed.TotalSeconds -ge $TimeoutSec) {
                $timedOut = $true
                break
            }
        }
        if ($timedOut) {
            Stop-TrackedPid -ProcessId $proc.Id -StartTimeTicks $startTicks -WaitMs 10000 -Tree `
                -StdoutPath (Join-Path $BuildOutDir 'build.cleanup.out.log') -StderrPath (Join-Path $BuildOutDir 'build.cleanup.err.log')
        } else {
            $proc.WaitForExit()
        }
    } catch {
        if (-not $timedOut -and $startTicks -gt 0L) {
            try { Stop-TrackedPid -ProcessId $proc.Id -StartTimeTicks $startTicks -WaitMs 10000 -Tree } catch { }
        }
        throw
    } finally {
        $sw.Stop()
    }
    $parts = New-Object System.Collections.Generic.List[string]
    if (Test-Path -LiteralPath $buildOut) {
        $outRaw = Get-Content -LiteralPath $buildOut -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
        if ($outRaw) { [void]$parts.Add($outRaw.TrimEnd("`r", "`n")) }
        Remove-Item -LiteralPath $buildOut -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath $buildErr) {
        $errRaw = Get-Content -LiteralPath $buildErr -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
        if ($errRaw -and $errRaw.Trim()) {
            [void]$parts.Add("[stderr]`n" + $errRaw.TrimEnd("`r", "`n"))
        }
        Remove-Item -LiteralPath $buildErr -ErrorAction SilentlyContinue
    }
    $exitCode = if ($timedOut) { 2 } elseif ($null -ne $proc.ExitCode) { [int]$proc.ExitCode } else { 1 }
    $elapsedSec = [math]::Round($sw.Elapsed.TotalSeconds, 2)
    [void]$parts.Add("[coach-build] exitCode=$(if ($timedOut) { 'timeout' } else { $exitCode }) seconds=$elapsedSec timedOut=$timedOut")
    ($parts -join "`n") + "`n" | Set-Content -LiteralPath $buildLog -Encoding UTF8
    [pscustomobject]@{
        TimedOut = $timedOut
        ExitCode = $exitCode
        Seconds = $sw.Elapsed.TotalSeconds
    }
}

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
$buildResult = $null
if (-not $Runtime) {
    Note '构建开始：gradlew :desktopApp:classes'
    $buildResult = Invoke-FriendBuild -BuildRepo $Repo -BuildOutDir $OutDir -IsBackground ([bool]$Background) -TimeoutSec $BuildTimeoutSec
    if ($buildResult.TimedOut) {
        @{
            dirty = $true
            exitCode = 2
            reason = 'build-timeout'
            buildSeconds = [math]::Round($buildResult.Seconds, 2)
            buildTimeoutSec = $BuildTimeoutSec
            time = (Get-Date).ToUniversalTime().ToString('o')
        } | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 (Join-Path $OutDir 'environment-dirty.json')
        Note "ENVIRONMENT_DIRTY：build-timeout（$([math]::Round($buildResult.Seconds, 2)) 秒）"
        $global:LASTEXITCODE = 2
        exit 2
    }
    if ($buildResult.ExitCode -eq 0) {
        Note ("构建完成：耗时 {0:N2} 秒" -f $buildResult.Seconds)
    } else {
        Note ("构建失败：退出码 {0}，耗时 {1:N2} 秒（见 build.log）" -f $buildResult.ExitCode, $buildResult.Seconds)
    }
}
if (-not $Background) {
    $preflight = Test-DesktopPreflight
    if (-not $preflight.Clean) {
        @{
            dirty = $true
            exitCode = 2
            reason = $preflight.Reason
            dirtyWindows = $preflight.DirtyWindows
            activeUser = $preflight.ActiveUser
            idleSeconds = $preflight.IdleSeconds
            driverInputMatched = $preflight.DriverInputMatched
            foreground = $preflight.Foreground
            time = (Get-Date).ToUniversalTime().ToString('o')
        } | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 (Join-Path $OutDir 'environment-dirty.json')
        Note "ENVIRONMENT_DIRTY：$($preflight.Reason)"
        $global:LASTEXITCODE = 2
        exit 2
    }
    Note "预检通过：Apollo 无活动串流（GetLastInputInfo 空闲 $($preflight.IdleSeconds) 秒，driverInputMatched=$($preflight.DriverInputMatched)，仅作证据）"
}
# Other Coach windows/heavy jobs may coexist; only input/capture is serialized.
Mark-ExistingClients
$gradle = $null
$gradleStartTicks = 0L
$launchSw = $null
if (-not $buildResult -or $buildResult.ExitCode -eq 0) {
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
        $launchSw = [Diagnostics.Stopwatch]::StartNew()
        $gradle = Start-Process "$env:JAVA_HOME/bin/$java" -ArgumentList ('-cp "' + $classpath + '" ' + $entry) -WorkingDirectory $Repo -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $OutDir 'client.out.log') -RedirectStandardError (Join-Path $OutDir 'client.err.log')
    } else {
        $launchSw = [Diagnostics.Stopwatch]::StartNew()
        $gradle = Start-Process (Join-Path $Repo 'gradlew.bat') -ArgumentList $launchArgs -WorkingDirectory $Repo `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $OutDir 'client.out.log') -RedirectStandardError (Join-Path $OutDir 'client.err.log')
    }
    if ($gradle) { $gradleStartTicks = Get-TrackedProcessStartTicks $gradle }
}
$dirtyContention = $null
try {
    # ① 启动
    if ($buildResult -and $buildResult.ExitCode -ne 0) { Fail 1 '构建失败（见 build.log）' }
    $deadline = (Get-Date).AddSeconds($StartTimeoutSec)
    while (-not (Find-CoachWindow) -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 2 }
    if ($script:Win -eq [IntPtr]::Zero) { Fail 1 "客户端窗口 $StartTimeoutSec 秒内没有出现（见 client.err.log）" }
    Set-SmokeMemoryClient $memoryLease $script:AppPid
    $m = Await-Text 1 'start' @('上传教材', '书架') 30
    if (-not $m) { Fail 1 '窗口出来了但没有看到书架' }
    if ($launchSw) { Note ("起窗完成：编译结束后 {0:N2} 秒" -f $launchSw.Elapsed.TotalSeconds) }
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
    } catch { if ("$_" -like 'DESKTOP_BUSY*' -or "$_" -like 'WIN_DRIVE_FOREGROUND_CONTENTION*') { throw }; $askWhy = "$_"; try { Send-Keys '{ESC}' } catch { } }
    if ($askWhy) {
        try { Fail 4 "讲解这段可用，但实际提问失败：$askWhy" } catch { if ("$_" -ne 'STEP_FAILED') { throw }; if (-not $KeepGoing) { throw 'STEP_FAILED' } }
    } else { Pass 4 '选中一段能得到带出处的讲解，也能输入问题得到带出处的回答' }

    # ⑤ 边看边学出讲解
    # 客户端对「已同意」的书，进入或切换整节就会自动发讲解请求（BookLessonExplanation）。④ 已经替这本书同意过，
    # 所以先撤掉本轮测试书架里这本书的同意：默认首节和选错的节只显示同意卡，不发请求；核准落点页之后才由驱动点同意，发一次。
    try { Clear-ExplainConsent $lib } catch { Fail 5 "没能撤掉这本测试书的同意（$_），不进默认节，避免自动发送" }
    $five = $null
    try {
        Click-Label 5 @('开始学') '找不到「开始学」'; Start-Sleep -Seconds 2
        Click-Label 5 @('边看边学') '找不到「边看边学」'; Start-Sleep -Seconds 1
        Click-Label 5 @('开始边看边学') '找不到「开始边看边学」'
        # 选中经授权的整节：目录 → 滚到序号 → 点 → 收起；核对「本节原文 · 第 N 页」就是授权页，才允许发请求
        Start-Sleep -Seconds 4
        Click-Label 5 @('目录') '找不到「目录」'; Start-Sleep -Seconds 3
        $entry = $null
        foreach ($k in 1..60) {
            $tocLines = Shot 5 "toc$k"
            foreach ($tl in $tocLines) { if ($tl.Text -match ("^$LessonNumber" + '[\.．。,，、]')) { $entry = Find-Text @($tl) $tl.Text.Substring(0, "$LessonNumber".Length); break } }
            if ($entry) { break }
            Scroll-At 300 700 -8; Start-Sleep -Milliseconds 700
        }
        if (-not $entry) { Fail 5 "目录里滚了 60 次也没找到第 $LessonNumber 条，没有发请求" }
        Click-At $entry.X $entry.Y; Start-Sleep -Seconds 3
        Click-Label 5 @('收起目录') '找不到「收起目录」'; Start-Sleep -Seconds 2
        $sectionLines = Shot 5 'section'
        $label = $sectionLines | Where-Object { $_.Text -match '本节原文' } | Select-Object -First 1
        $labelPage = if ($label) { [regex]::Match($label.Text, '第\s*(\d+)\s*页').Groups[1].Value } else { '' }
        if (-not $labelPage) { Fail 5 '选中节后界面没有「本节原文 · 第 N 页」标签，没有发请求' }
        if ([int]$labelPage -ne $LessonPage) { Fail 5 "选中的第 $LessonNumber 条落在第 $labelPage 页，不是授权页第 $LessonPage 页，没有发请求（见 5-section.png）" }
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
            if ([int]$sourcePage -ne $LessonPage) { Fail 5 "讲解自己的出处是第 $sourcePage 页，不是本节页第 $LessonPage 页" }
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
            Pass 5 "授权节（目录第 $LessonNumber 条，第 $LessonPage 页）出了讲解正文和自己的第 $sourcePage 页出处，点出处回到对应原文页"
        } elseif ($res) { Fail 5 "边看边学的讲解没出来，界面说「$($res.Substring(3))」" } else { Fail 5 '90 秒内没有同时看到讲解正文和带页码的出处（见 5-learn.png）' }
    } catch { if ("$_" -ne 'STEP_FAILED') { throw }; $five = 'failed' }
    if ($five -and -not $KeepGoing) { throw 'STEP_FAILED' }
    if ($StopAfter -le 5) { throw 'STEP_FAILED' }   # 按要求停在第 ⑤ 步之后；已记录的结果不变

    # ⑥ 朋友自己的书：从正常界面进入授权章节后按节现出题，真实点一个选项，再等答后讲解和单题小结；不依赖整理题库准入。
    $six = $null
    try {
        Scroll-At 900 700 30; Start-Sleep -Seconds 1
        Click-Label 6 @('返回书架') '无法从学习页返回书架'
        Click-Label 6 @('做题学', '继续做题学') '书架没有做题学入口'
        $enter = Await-Text 6 'learn-enter' @('本节原文') 15
        if (-not $enter) { Fail 6 '做题学页面没有显示「本节原文」' }
        $readTotalLessons = { param($lines)
            foreach ($l in $lines) {
                $t = $l.Text -replace '\s', ''
                if ($t -match '第\d+[/／](\d+)节') { return [int]$Matches[1] }
            }
            return 0
        }
        # 落点只认产品自己写出的页段：点「本节原文」，读「对应教材第 F–L 页」，再「返回学习」。
        # 本节正在出题时「本节原文」「上一节」「继续下一节」都是禁用的（TextbookLearning.kt enabled = !busy），先等出题结束再点。
        $waitIdle = { param([string]$tag)
            $idle = (Get-Date).AddSeconds(90)
            while ((Get-Date) -lt $idle -and (Has (Shot 6 "busy-$tag") @('正在按本节原文出题'))) { Start-Sleep -Seconds 2 }
        }
        # 上一次点击可能慢一步才打开原文页（两窗争用时 15 秒内没等到）：已在原文页且读得出页段，就直接用这一帧。
        $waitRangeFade = { param([string]$tag)
            $fadeStop = (Get-Date).AddSeconds(10)
            while ((Get-Date) -lt $fadeStop -and (Find-Text $script:Last '返回学习')) {
                $found = Get-LessonSourceRange $script:Last
                if ($found) { return $found }
                Start-Sleep -Seconds 1
                $null = Shot 6 "fade-$tag"
            }
            if (Find-Text $script:Last '返回学习') { return (Get-LessonSourceRange $script:Last) }
            return $null
        }
        $readRange = { param([string]$tag)
            $r = $null
            foreach ($try in 1..2) {
                & $waitIdle $tag
                $act = Resolve-SourcePageRangeAction (Get-LessonLearningState $script:Last $script:LastPng)
                if ($act.Action -eq 'return_range') { $r = $act.Range; break }
                if ($act.Action -eq 'wait_range_fade') { $r = & $waitRangeFade "$tag-$try"; if ($r) { break } }
                $btn = if ($act.Action -eq 'click_source') { $act.Button } else { $null }
                # 换节后的过渡帧可能还没画好或按钮仍处于 ui.busy 灰态，重新取图等按钮可用再点。
                foreach ($again in 1..6) {
                    if ($btn) { break }
                    Start-Sleep -Milliseconds 1500
                    $lines = Shot 6 "btn-$tag-$try-$again"
                    $act = Resolve-SourcePageRangeAction (Get-LessonLearningState $lines $script:LastPng)
                    if ($act.Action -eq 'return_range') { $r = $act.Range; break }
                    if ($act.Action -eq 'wait_range_fade') { $r = & $waitRangeFade "$tag-$try-$again"; if ($r) { break } }
                    if ($act.Action -eq 'click_source') { $btn = $act.Button }
                }
                if ($r) { break }
                if (-not $btn) { Fail 6 '做题学页面找不到可用的「本节原文 · 第X页」按钮' }
                Click-At $btn.X $btn.Y; Start-Sleep -Milliseconds 800
                if (Await-Text 6 "range-$tag" @('对应教材第') 25) { $r = Get-LessonSourceRange $script:Last; break }
                $actAfter = Resolve-SourcePageRangeAction (Get-LessonLearningState $script:Last $script:LastPng)
                if ($actAfter.Action -eq 'return_range') { $r = $actAfter.Range; break }
                if ($actAfter.Action -eq 'wait_range_fade') { $r = & $waitRangeFade "$tag-after-$try"; if ($r) { break } }
            }
            $ret = Await-Text 6 'find' @('返回学习') 15
            if (-not $ret) { Fail 6 '本节原文页没有「返回学习」（屏幕上找不到「返回学习」）' }
            if (-not $r) {
                $r = Get-LessonSourceRange $script:Last
                if (-not $r) { $r = & $waitRangeFade "late-$tag" }
            }
            Click-At $ret.X $ret.Y
            $fgAfterClick = Get-ForegroundOwner
            $sawFocusLoss = -not $fgAfterClick.IsOwnCoach
            Start-Sleep -Milliseconds 800
            $fgSettled = Get-ForegroundOwner
            if (-not $fgSettled.IsOwnCoach) { $sawFocusLoss = $true }
            $linesNow = Shot 6 "back-$tag"
            $backFound = [bool](Find-Text $linesNow '本节原文')
            $retNow = if (-not $backFound) { Find-Text $linesNow '返回学习' } else { $null }
            $retAct = Resolve-SourceReturnAction -ReturnedToLearning $backFound -SawFocusLoss $sawFocusLoss -ReturnButton $retNow
            if ($retAct.Action -eq 'reclick') {
                Note "步骤6 点返回学习后失焦且仍在原文页，按当前帧坐标重点击 ($($retAct.Button.X),$($retAct.Button.Y))"
                Click-At $retAct.Button.X $retAct.Button.Y
                Start-Sleep -Milliseconds 800
                $backFound = [bool](Await-Text 6 "back-$tag" @('本节原文') 15)
            } elseif ($retAct.Action -eq 'wait_learning') {
                $backFound = [bool](Await-Text 6 "back-$tag" @('本节原文') 15)
            }
            if (-not $backFound) { Fail 6 '从本节原文返回后没有回到做题学页面' }
            return $r
        }
        $totalLessons = & $readTotalLessons $script:Last
        $totalPages = if ($page1 -match '[/／](\d+)$') { [int]$Matches[1] } elseif ($page0 -match '[/／](\d+)$') { [int]$Matches[1] } else { 0 }
        $range = if ($physPage -gt 0) { & $readRange '0' } else { $null }
        if ($physPage -gt 0 -and -not (Test-RangeHasPage $range $physPage)) {
            # 目录估算只用来跳到附近；每跳之后用页段收窄上下界。离目标页 4 页以内改用「上一节 / 继续下一节」逐节核对，
            # 直到某节页段真的含目标页；相邻两节把目标页夹在中间却都不含它，就明确报错。
            $cap = if ($totalLessons -gt 0) { $totalLessons } else { 9999 }
            $scale = if ($totalLessons -gt 0 -and $totalPages -gt 0) { $totalLessons / $totalPages } else { 1.25 }
            $lo = 1; $hi = $cap
            $targetIdx = [Math]::Max(1, [Math]::Min($cap, [int][Math]::Round($physPage * $scale)))
            $landed = $false; $stepDir = $null; $chosenNum = 0
            for ($hop = 1; $hop -le 12 -and -not $landed; $hop++) {
                $dir = if ($range -and $range.Last -lt $physPage -and $physPage - $range.Last -le 4) { 'next' }
                       elseif ($range -and $range.First -gt $physPage -and $range.First - $physPage -le 4) { 'prev' } else { $null }
                if ($dir) {
                    if ($stepDir -and $stepDir -ne $dir) {
                        Fail 6 "没有哪一节的页段含第 $physPage 页：相邻两节分别是第 $($prev.First)–$($prev.Last) 页和第 $($range.First)–$($range.Last) 页"
                    }
                    & $waitIdle "step-$hop"
                    $beforeLesson = Get-LessonLearningState $script:Last $script:LastPng
                    if ($dir -eq 'next') { Click-Label 6 @('继续下一节') '做题学页面找不到「继续下一节」' } else { Click-Label 6 @('上一节') '做题学页面找不到「上一节」' }
                    $secDeadline = (Get-Date).AddSeconds(30)
                    $secReady = $false
                    while ((Get-Date) -lt $secDeadline) {
                        $secLines = Shot 6 "sec-$hop"
                        $nowLesson = Get-LessonLearningState $secLines $script:LastPng
                        if (Test-AdjacentLessonReady $beforeLesson $nowLesson) { $secReady = $true; break }
                        Start-Sleep -Seconds 2
                    }
                    if (-not $secReady) { Fail 6 '切换到相邻一节后页面没有切到新节或「本节原文」按钮仍不可用' }
                    if ($chosenNum -gt 0) { $chosenNum += $(if ($dir -eq 'next') { 1 } else { -1 }) }
                    $stepDir = $dir; $prev = $range
                    $range = & $readRange "$hop"
                    if (-not $range) { Fail 6 '无法确认相邻一节的页段：本节原文页没有读到「对应教材第 F–L 页」' }
                    Note "步骤6 第 $hop 跳（$(if ($dir -eq 'next') { '下一节' } else { '上一节' })）：第 $($range.First)–$($range.Last) 页"
                    if (Test-RangeHasPage $range $physPage) { $landed = $true }
                    continue
                }
                $stepDir = $null
                Click-Label 6 @('目录') '做题学页面找不到「目录」'
                Start-Sleep -Milliseconds 500
                $closeDir = Await-Text 6 "dir-$hop" @('收起目录') 10
                if (-not $closeDir) { Fail 6 '点击「目录」后学习路线抽屉没有打开' }
                $lines = $script:Last
                $chosenNum = 0
                for ($sc = 0; $sc -lt 10; $sc++) {
                    $items = @(foreach ($l in $lines) {
                        if ($l.Text -match '^\s*(\d+)[.．]') {
                            $num = [int]$Matches[1]
                            $box = Find-Text @($l) $Matches[0]
                            if ($box -and [Math]::Abs($box.X1 - $closeDir.X1) -lt 80 -and $box.Y1 -gt $closeDir.Y) {
                                [pscustomobject]@{ Num = $num; X = $box.X; Y = $box.Y }
                            }
                        }
                    }) | Sort-Object Num
                    if ($items.Count -gt 0) {
                        $minNum = $items[0].Num; $maxNum = $items[$items.Count - 1].Num
                        if ($targetIdx -ge $minNum -and $targetIdx -le $maxNum) {
                            # 只在已确认的上下界内挑；OCR 漏读个别编号时不会退回已排除的那一节。
                            $inBounds = @($items | Where-Object { $_.Num -ge $lo -and $_.Num -le $hi })
                            $pool = if ($inBounds.Count -gt 0) { $inBounds } else { $items }
                            $pick = $pool | Sort-Object { [Math]::Abs($_.Num - $targetIdx) } | Select-Object -First 1
                            $chosenNum = $pick.Num
                            Click-At $pick.X $pick.Y
                            Start-Sleep -Milliseconds 400
                            break
                        }
                        if ($sc -eq 9) {
                            $pick = if ($targetIdx -lt $minNum) { $items[0] } else { $items[$items.Count - 1] }
                            $chosenNum = $pick.Num
                            Click-At $pick.X $pick.Y
                            Start-Sleep -Milliseconds 400
                            break
                        }
                        $notches = if ($targetIdx -gt $maxNum) {
                            -[Math]::Min(60, [Math]::Max(4, [int][Math]::Ceiling(($targetIdx - $maxNum) / 2.2)))
                        } else {
                            [Math]::Min(60, [Math]::Max(4, [int][Math]::Ceiling(($minNum - $targetIdx) / 2.2)))
                        }
                    } else {
                        $notches = -20
                    }
                    Scroll-At $closeDir.X ($closeDir.Y + 250) $notches
                    Start-Sleep -Milliseconds 400
                    $lines = Shot 6 "dir-$hop-$sc"
                }
                Click-Label 6 @('收起目录') '选中学习路线条目后无法收起目录'
                $afterSec = Await-Text 6 "sec-$hop" @('本节原文') 15
                if (-not $afterSec) { Fail 6 '切换学习路线章节后页面没有显示「本节原文」' }
                $range = & $readRange "$hop"
                if (-not $range) { Fail 6 "无法确认第 $chosenNum 节的页段：本节原文页没有读到「对应教材第 F–L 页」" }
                Note "步骤6 第 $hop 跳（目录第 $chosenNum 条）：第 $($range.First)–$($range.Last) 页"
                if (Test-RangeHasPage $range $physPage) { $landed = $true; break }
                if ($chosenNum -le 0) { Fail 6 '学习路线里没有找到可点的章节条目' }
                if ($range.Last -lt $physPage) { $lo = [Math]::Max($lo, $chosenNum + 1); $delta = [Math]::Max(1, [int][Math]::Round(($physPage - $range.Last) * $scale)) }
                else { $hi = [Math]::Min($hi, $chosenNum - 1); $delta = [Math]::Min(-1, [int][Math]::Round(($physPage - $range.First) * $scale)) }
                if ($lo -gt $hi) { Fail 6 "没有哪一节的页段含第 $physPage 页（最后确认的一节是第 $($range.First)–$($range.Last) 页）" }
                $targetIdx = [Math]::Max($lo, [Math]::Min($hi, $chosenNum + $delta))
            }
            $seen = if ($range) { "第 $($range.First)–$($range.Last) 页" } else { '未读到页段' }
            if (-not $landed) { Fail 6 "未能切到含第 $physPage 页的章节（最后确认：$seen）" }
        }
        if ($range) { Note "步骤6 落点：本节对应教材第 $($range.First)–$($range.Last) 页，目标第 $physPage 页" }
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
            if (Has $lines @('格式完整', '出题暂时读不到', '没有足够的正文', '需要连上', '尚未核实', '额度', '重新配对', '暂时无法确认')) {
                Fail 6 '本节出题被拒绝或不可用，见 winbox 私有截图 6-question.png'
            }
            $anchor = Has $lines @('查看对应原文')
            if ((Has $lines @('本次练习')) -and $anchor) {
                # 只在题目原文按钮下找独立的选项字母；不以固定屏幕位置或「猜的」芯片当成作答。
                # 深色下的选项字母对比度低，OCR 常漏读 A、B，或把 C 读成 c：取最靠上的那个单字母选项。
                foreach ($line in $lines) { foreach ($word in $line.Words) {
                    $b = $word.BoundingRect
                    if ($word.Text -cmatch '^[A-Da-d]$' -and $b.Y -gt $anchor.Y + 10 -and (-not $picked -or $b.Y -lt $picked.Y)) { $picked = $b }
                } }
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
            if (Has $lines @('题目和作答只保留在这次练习中', '要回看时再打开', '小结')) { $summary = $true }
            if ($selected -and $explained -and $cited -and $summary) { break }
            Scroll-At 900 700 -2; Start-Sleep -Seconds 1
        }
        if (-not $selected) { Fail 6 '点击选项后没有看到已选答案' }
        if (-not $explained -or -not $cited) { Fail 6 '已作答，但没有看到带出处的答后讲解' }
        if (-not $summary) { Fail 6 '已作答并看到讲解，但没有看到单题小结' }
        Pass 6 '从正常界面进入授权章节，现出选择题作答后出现已选答案、带出处的答后讲解和单题小结（6-question、6-answered 私有截图）'
    } catch { if ("$_" -ne 'STEP_FAILED') { throw }; $six = 'failed' }
    if ($six -and -not $KeepGoing) { throw 'STEP_FAILED' }

    if ($cardOk) { Fail 7 $cardOk } else { Pass 7 "点出处弹出第 $physPage 页原文卡" }
} catch {
    if ("$_" -like 'WIN_DRIVE_FOREGROUND_CONTENTION*') {
        $stopStep = [Math]::Min($results.Count + 1, 7)
        $fgHwnd = 0L; $fgPid = 0; $fgProcess = ''; $fgClass = ''; $fgSummary = ''
        $ex = $_.Exception
        if ($ex -and $ex.Data -and $ex.Data.Contains('ForegroundPid')) {
            if ($ex.Data.Contains('ForegroundHwnd')) { $fgHwnd = [long]$ex.Data['ForegroundHwnd'] }
            $fgPid = [int]$ex.Data['ForegroundPid']
            $fgProcess = [string]$ex.Data['ForegroundProcess']
            $fgClass = [string]$ex.Data['ForegroundClass']
            if ($ex.Data.Contains('ForegroundSummary')) { $fgSummary = [string]$ex.Data['ForegroundSummary'] }
        } elseif ("$_" -match 'foreground:\s*(.*?)\s+pid=(\d+)\s+class=([^\s)]*)(?:\s+(this run|other))?') {
            $fgProcess = if ($Matches[1]) { $Matches[1] } else { 'unknown' }
            $fgPid = [int]$Matches[2]
            $fgClass = $Matches[3]
            $fgSummary = "$fgProcess pid=$fgPid class=$fgClass $(if ($Matches[4]) { $Matches[4] } else { 'other' })"
        }
        if (-not $fgProcess) { $fgProcess = 'unknown' }
        if (-not $fgSummary) { $fgSummary = "$fgProcess pid=$fgPid class=$fgClass other" }
        $dirtyContention = [pscustomobject]@{
            step = $stopStep
            hwnd = $fgHwnd
            pid = $fgPid
            process = $fgProcess
            class = $fgClass
            summary = $fgSummary
            detail = "$_"
        }
        @{
            dirty = $true
            exitCode = 2
            reason = 'foreground-contention'
            step = $stopStep
            foregroundHwnd = $fgHwnd
            foregroundPid = $fgPid
            foregroundProcess = $fgProcess
            foregroundSummary = $fgSummary
            foreground = @{
                hwnd = $fgHwnd
                pid = $fgPid
                process = $fgProcess
                class = $fgClass
                summary = $fgSummary
            }
            detail = "$_"
            time = (Get-Date).ToUniversalTime().ToString('o')
        } | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 (Join-Path $OutDir 'environment-dirty.json')
        Note "ENVIRONMENT_DIRTY：foreground-contention（步骤$stopStep 前台被 $fgSummary 占用，hwnd=$fgHwnd，不计 N/7）"
    } elseif ("$_" -like 'DESKTOP_BUSY*') { $n = $results.Count + 1; [void]$results.Add([pscustomobject]@{ Step = $n; Name = $steps[[Math]::Min($n - 1, 6)]; Ok = $false; Why = "$_" }); Note "$_" }
    elseif ("$_" -ne 'STEP_FAILED') { Note "脚本异常：$_"; [void]$results.Add([pscustomobject]@{ Step = $results.Count + 1; Name = $steps[[Math]::Min($results.Count, 6)]; Ok = $false; Why = "脚本异常：$_" }) }
} finally {
    $passed = @($results | Where-Object Ok).Count
    $summary = [ordered]@{ passed = $passed; total = 7; marker = $Marker; time = (Get-Date -Format 'o'); outDir = $OutDir; server = $env:COACH_URL; steps = $results }
    if ($dirtyContention) {
        $summary.stoppedAtStep = $dirtyContention.step
        $summary.stoppedReason = 'foreground-contention'
    }
    [pscustomobject]$summary | ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 (Join-Path $OutDir 'result.json')
    $cleanupErr = $null
    try { Stop-MyClient } catch { $cleanupErr = $_ }
    # Kill only the exact launcher tree this worker created; its Java wrapper
    # must not survive a startup failure and launch an orphan window later.
    try {
        if ($gradle) {
            Stop-TrackedPid -ProcessId $gradle.Id -StartTimeTicks $gradleStartTicks -WaitMs 10000 -Tree `
                -StdoutPath (Join-Path $OutDir 'cleanup.out.log') -StderrPath (Join-Path $OutDir 'cleanup.err.log')
        }
    } catch { if (-not $cleanupErr) { $cleanupErr = $_ } }
    if ($dirtyContention) {
        Note "已通过 $passed/7 步，停在步骤$($dirtyContention.step)（环境被占，不计 N/7）"
    } else {
        Note "结果 $passed/7"
    }
    if ($cleanupErr) { throw $cleanupErr }
}
} finally {
    if ($ClientSeed) { Remove-Item -Recurse -Force $client -ErrorAction SilentlyContinue }   # 配对凭据只在运行期存在
    Exit-SmokeMemory $memoryLease
}
if ($dirtyContention) {
    $global:LASTEXITCODE = 2
    exit 2
}
