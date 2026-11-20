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
    # 第⑤步要选的整节：目录里的序号 = 书架缓存 book_structures 里的节序号（1 起）。默认 113 是
    # 「6.6 Conclusion」，起止都是第 150 页，单页整节，讲解的出处只会是这一页，页码断言才站得住。
    [int]$LessonNumber = 113,
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
    # 讲解没拿到时，客户端在选段卡里给一句固定的说明（ExplainTrouble 的文案，不含状态码）。
    foreach ($line in $lines) {
        $row = $line.Text -replace '\s', ''
        if ($row -match '^(?:再试一次)?(讲解服务暂时连不上|讲解服务还没配对|讲解服务这会儿没顾上|这台电脑的连接已停用|这次没讲成)') { return $Matches[1] }
        # 这支字号下 OCR 会把「电脑」「连接」认歪（如「这台电的遵接已停甲」），按骨架再认一次。
        if ($row -match '^这台电.{0,3}的.{0,4}接已停') { return '这台电脑的连接已停用' }
    }
}
function Await-Text([int]$n, [string]$tag, [string[]]$needles, [int]$sec) {
    $deadline = (Get-Date).AddSeconds($sec)
    do { $lines = Shot $n $tag; $m = Has $lines $needles; if ($m) { return $m }; Start-Sleep -Seconds 2 } while ((Get-Date) -lt $deadline)
    return $null
}
function Page-Of($lines) { foreach ($l in $lines) { $mm = [regex]::Match($l.Text, '(\d+)[/／](\d+)'); if ($mm.Success) { return "$($mm.Groups[1].Value)/$($mm.Groups[2].Value)" } }; return $null }
# 底栏页号夹在「上一页」「下一页」之间。正文和别的卡片里也有「2/2」这种分数，只认这一行。
function Page-NearNav($lines) {
    $prev = Find-Text $lines '上一页'; $next = Find-Text $lines '下一页'
    if (-not $prev -or -not $next) { return $null }
    foreach ($l in $lines) {
        $mm = [regex]::Match(($l.Text -replace '\s', ''), '^(\d+)(?:[/／](\d+))?[^\d]{0,3}$')
        if (-not $mm.Success) { continue }
        $b = Get-LineBox $l
        if ([Math]::Abs($b.Y - $prev.Y) -le 16 -and $b.X1 -ge ($prev.X2 - 2) -and $b.X2 -le ($next.X1 + 2)) { return $mm.Groups[1].Value }
    }
    return $null
}
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
# The lowest on-screen occurrence of a label (e.g. the last button on a page).
function Find-Lowest($lines, [string]$needle) { $best = $null; foreach ($l in $lines) { $m = Find-Text @($l) $needle; if ($m -and (-not $best -or $m.Y -gt $best.Y)) { $best = $m } }; $best }
# 滚动落点按窗口实际大小算：winbox 的控制台会话不一定跑在大屏上（现在只有 800×600），
# 写死的 900/700 会落到窗口外，滚轮就滚到桌面上，列表一动不动。
function Scroll-Point([double]$fx, [double]$fy) {
    $r = Get-Rect
    @([int](($r.R - $r.L) * $fx), [int](($r.B - $r.T) * $fy))
}
# 学习页一屏放不下：整节正文和选项列表下面才是「上一节 / 继续下一节」。当前帧找不到就往下滚着找。
function Click-Folded([int]$n, [string[]]$labels, [string]$why) {
    foreach ($k in 1..8) {
        $m = Has $script:Last $labels
        if ($m) { Click-At $m.X $m.Y; Start-Sleep -Milliseconds 800; return }
        $sp = Scroll-Point 0.6 0.6; Scroll-At $sp[0] $sp[1] -4; Start-Sleep -Milliseconds 600
        $null = Shot $n "fold$k"
    }
    Fail $n $why
}
function Find-All($lines, [string]$needle) { $all = @(); foreach ($l in $lines) { $m = Find-Text @($l) $needle; if ($m) { $all += $m } }; $all | Sort-Object Y }
# Locate controls inside the open question-mode dialog, ignoring the background toolbar button and hint copy.
function Get-QuestionSettingsControls($lines) {
    $title = Find-Text $lines '出题方式'
    if (-not $title) { return $null }
    $modeHeaders = @(@(foreach ($l in $lines) {
        if ($l.Text -match '出题[:：]') { continue }
        $m = Find-Text @($l) '出题'
        if ($m -and $m.Y -gt $title.Y2 -and [Math]::Abs($m.X1 - $title.X1) -le 60) {
            [pscustomobject]@{ Box = $m; Exact = (($l.Text -replace '\s', '') -eq '出题') }
        }
    }) | Sort-Object { $_.Box.Y })
    $exactHeader = @($modeHeaders | Where-Object { $_.Exact } | Select-Object -First 1)
    $modeHeader = if ($exactHeader.Count -gt 0) { $exactHeader[0].Box } elseif ($modeHeaders.Count -gt 0) { $modeHeaders[0].Box } else { $null }
    $minChoiceY = if ($modeHeader) { $modeHeader.Y2 } else { $title.Y2 }
    $choices = @(@(foreach ($l in $lines) {
        if ($l.Text -match '出题[:：]' -or $l.Text -match '选择题') { continue }
        $m = Find-Text @($l) '选择'
        if ($m -and $m.Y -gt $minChoiceY -and $m.X1 -ge ($title.X1 - 40)) { $m }
    }) | Sort-Object Y)
    $choice = if ($choices.Count -gt 0) { $choices[0] } else { $null }
    $minConfirmY = if ($choice) { $choice.Y2 } else { $minChoiceY }
    $confirms = @(Find-All $lines '确定' | Where-Object {
        $_.Y -gt $minConfirmY -and $_.X1 -ge ($title.X1 - 40)
    })
    $confirm = if ($confirms.Count -gt 0) { $confirms[0] } else { $null }
    [pscustomobject]@{ Title = $title; ModeHeader = $modeHeader; Choice = $choice; Confirm = $confirm }
}
function Test-QuestionSettingsConfirmed($lines) {
    if (Find-Text $lines '出题方式') { return $false }
    foreach ($l in $lines) {
        if (($l.Text -replace '\s', '') -match '出题[:：]选择') { return $true }
    }
    return $false
}
# A whole OCR line's box: drag-selection needs both edges of the line, not just the first word.
function Get-LineBox($line) {
    $x1 = 1e9; $y1 = 1e9; $x2 = -1; $y2 = -1
    foreach ($w in $line.Words) {
        $b = $w.BoundingRect
        if ($b.X -lt $x1) { $x1 = [int]$b.X }
        if (($b.X + $b.Width) -gt $x2) { $x2 = [int]($b.X + $b.Width) }
        if ($b.Y -lt $y1) { $y1 = [int]$b.Y }
        if (($b.Y + $b.Height) -gt $y2) { $y2 = [int]($b.Y + $b.Height) }
    }
    [pscustomobject]@{ X1 = [int]$x1; X2 = [int]$x2; Y1 = [int]$y1; Y2 = [int]$y2; Y = [int](($y1 + $y2) / 2) }
}
# The occurrence of $needle on the same visual row as $ref (the floating toolbar draws its buttons in one row).
function Find-OnRow($lines, [string]$needle, $ref, [int]$tol = 14) {
    if (-not $ref) { return $null }
    $best = $null
    foreach ($l in $lines) {
        $m = Find-Text @($l) $needle
        if ($m -and [Math]::Abs($m.Y - $ref.Y) -le $tol -and (-not $best -or $m.X -lt $best.X)) { $best = $m }
    }
    return $best
}
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
            otherCoachWindows = @($preflight.OtherCoachWindows)
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
    $coexisting = @($preflight.OtherCoachWindows | Where-Object { $_ })
    if ($coexisting.Count -gt 0) {
        Note "并存的其他 Coach 客户端（不属于本轮，只作证据；点按逐动作校验目标窗归属）：$(($coexisting | ForEach-Object { "pid=$($_.Pid)" }) -join ', ')"
    }
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
    # 上传后的「这次怎么读」卡上点「先读」只读；导入收尾时按钮可能还没亮，点不动就再点，直到阅读器出来。
    $lines = Shot 3 'find'
    $m = Has $lines @('下一页')
    $deadline = (Get-Date).AddSeconds(90)
    while (-not $m -and (Get-Date) -lt $deadline) {
        $b = Has $lines @('先读', '继续看书')
        if ($b) { Click-At $b.X $b.Y; Start-Sleep -Seconds 1 }
        $d = Await-Text 3 'dismiss' @('要开始学吗') 2
        if ($d) { Send-Keys '{ESC}'; Start-Sleep -Seconds 1 }   # 暂不开启：只读这本书
        $lines = Shot 3 'reader'
        $m = Has $lines @('下一页')
    }
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
    # ④ 选中就问出讲解：跳到可信选段所在页 → 文字视图 → 拖选该段 → 浮条「讲讲」→ 同意发给 Mac → 等带出处的讲解
    # 1.1.1（TON-1766）删掉了每段的「讲解这段」：选中文字后浮出的工具条「讲讲」才是这条路径的入口。
    $sel = Get-Content $Selection -Raw -Encoding UTF8 | ConvertFrom-Json
    $physPage = [int]([regex]::Match($sel.blocks[0].id, ':p(\d+):').Groups[1].Value)
    if (-not (Await-Text 4 'goto' @('前往') 10)) { Fail 4 '找不到页码跳转的「前往」' }
    # 底栏页码框夹在「页码」标签和「前往」按钮之间，只有 64dp 宽，OCR 读不出框里刚敲的数字；
    # 所以以「点完「前往」页码真的变成这一页」为准，哪个落点跳不过去就换下一个落点再试。
    $go = Find-Lowest $script:Last '前往'
    $goX = [int]$go.X; $goY = [int]$go.Y; $goX1 = [int]$go.X1
    $cands = New-Object 'System.Collections.Generic.List[int]'
    $lbl = Find-Lowest $script:Last '页码'
    if ($lbl) { $cands.Add([int](([int]$lbl.X2 + $goX1) / 2)) }
    foreach ($off in 106, 70, 40) { $cands.Add($goX - $off) }
    $pageNow = $null
    foreach ($fx in $cands) {
        Click-At $fx $goY; Start-Sleep -Milliseconds 400
        Send-Keys '^a'; Send-Keys "$physPage"; Start-Sleep -Milliseconds 500
        $btn = Find-Lowest (Shot 4 'goto-typed') '前往'
        if ($btn) { Click-At ([int]$btn.X) ([int]$btn.Y) }
        foreach ($try in 1..5) {
            Start-Sleep -Seconds 2; $pageNow = Page-NearNav (Shot 4 'jumped')
            if ($pageNow -eq "$physPage") { break }
        }
        if ($pageNow -eq "$physPage") { Note "页码框点在第 $fx 像素处跳页成功"; break }
        if ($btn) { Send-Keys '^a'; Send-Keys '{BACKSPACE}'; Start-Sleep -Milliseconds 300 }
    }
    if ($pageNow -ne "$physPage") { Fail 4 "页码没跳到 $physPage（现在 $pageNow）" }
    Click-Label 4 @('看文字') '找不到「看文字」'
    if (-not (Await-Text 4 'text-mode' @('看原页') 15)) { Fail 4 '点击「看文字」后没有切换到文字视图' }
    # 文字视图一屏只放得下一页的一部分：这一页开头是页眉，可信选段在这页下半部分，往下滚才看得见。
    $words = (($sel.selection -replace '[^A-Za-z ]', ' ').Trim() -split '\s+' | Select-Object -First 4) -join ''
    $paraLine = $null; $lines = @()
    foreach ($round in 1..6) {
        if ($round -eq 1) { $lines = Shot 4 'blocks' } else { $lines = Shot 4 'blocks-down' }
        foreach ($l in $lines) { if ($l.Text -replace '[^A-Za-z]', '' -like "*$words*") { $paraLine = $l; break } }
        if ($paraLine) { break }
        Scroll-At 600 400 -3; Start-Sleep -Milliseconds 800
    }
    if (-not $paraLine) { Fail 4 '文字视图里找不到可信选段所在的段落（见 4-blocks.png）' }
    $box = Get-LineBox $paraLine
    if (($box.X2 - $box.X1) -lt 40) { Fail 4 '可信选段那一行太窄，拖选不出文字（见 4-blocks.png）' }
    $lineH = [Math]::Max(12, $box.Y2 - $box.Y1)
    # 拖选：浮条落在阅读区顶端；一种落点没选出文字就换一种再试
    $toolbar = $null
    foreach ($drag in 1..3) {
        switch ($drag) {
            1 { Drag-Between ($box.X1 + 4) $box.Y ($box.X2 - 4) $box.Y }
            2 { Drag-Between ($box.X1 + 4) $box.Y ($box.X2 - 4) ($box.Y + 2 * $lineH) }
            3 { Drag-Between ($box.X1 + 4) ([Math]::Max(1, $box.Y - $lineH)) ($box.X2 - 4) ($box.Y + 2 * $lineH) }
        }
        $toolbar = Await-Text 4 "selected$drag" @('举个例子', '换个说法') 8
        if ($toolbar) { break }
    }
    if (-not $toolbar) { Fail 4 '拖选这一行后没有浮出选中工具条（见 4-selected3.png）' }
    # 工具条一行是「讲讲」「举个例子」「换个说法」「问…」：取和「举个例子」同一行、在它左边的「讲讲」
    $explainBtn = Find-OnRow $script:Last '讲讲' $toolbar
    if (-not $explainBtn) { Fail 4 '选中工具条里找不到「讲讲」' }
    Click-At $explainBtn.X $explainBtn.Y; Start-Sleep -Milliseconds 800
    $c = Await-Text 4 'consent' @('允许并讲解') 15
    if ($c) { Click-At $c.X $c.Y }       # 这本书第一次讲解的同意框（开发验收允许点）
    $deadline = (Get-Date).AddSeconds(120); $outcome = $null; $errText = $null; $n = 0
    $failWords = @('无法讲解', '尚未核实', '尚未接通', '连不上', '超时', '没顾上', '没讲成', '没有得到可核对', '出处无法核对', '没有可核对的讲解', '连接已更换', '还没配对', '额度', '选中追问格式不对', '保存页', '这一节太长', '请先导入自己的书')
    $failPattern = '无法讲解|尚未核实|尚未接通|连不上|超时|没顾上|没讲成|没有得到可核对|出处无法核对|没有可核对的讲解|连接已更换|还没配对|额度|选中追问格式不对|保存页|这一节太长|请先导入自己的书'
    while ((Get-Date) -lt $deadline -and -not $outcome) {
        Start-Sleep -Seconds 3; $n++
        $lines = Shot 4 "result$n"
        $serviceError = Get-PassageServiceError $lines
        if ($serviceError) { Fail 4 "服务回了拒绝：$serviceError" }
        $e = Has $lines $failWords
        if ($e) { $outcome = 'error'; $errText = (($lines | Where-Object { $_.Text -match $failPattern } | Select-Object -First 1).Text) }
        elseif (Has $lines @('出处')) { $outcome = 'ok' }
        else {
            # 讲解比选段卡的可视高度长时，「出处」在卡片正文下面：在卡片里下滚一次再等下一轮
            $closeCard = Find-Text $lines '收起'
            if ($closeCard -and $n -ge 2) { $lim = Get-Rect; Scroll-At 600 ([Math]::Min($closeCard.Y + 150, $lim.B - $lim.T - 30)) -3; Start-Sleep -Milliseconds 600 }
        }
    }
    if ($outcome -eq 'error') { Fail 4 "服务回了拒绝：$errText" }
    if ($outcome -ne 'ok') { Fail 4 '120 秒内既没有讲解也没有报错' }
    $explainOk = $true
    # ⑦ 先做（依赖第 4 步留在屏幕上的选段卡）：点「出处」，应弹出原文卡
    $cardOk = $null
    # 出处可能在选段卡正文下方：先在卡片里往下找，再往上找，最多 8 次
    $o = $null
    foreach ($k in 0..8) {
        if ($k -gt 0) {
            $closeCard = Find-Text $script:Last '收起'
            $lim = Get-Rect; $y = if ($closeCard) { [Math]::Min($closeCard.Y + 150, $lim.B - $lim.T - 30) } else { [int](($lim.B - $lim.T) * 0.8) }
            Scroll-At 600 $y $(if ($k -le 4) { -3 } else { 3 }); Start-Sleep -Milliseconds 700
        }
        $o = Await-Text 7 'cite' @('出处') 1
        if ($o) { break }
    }
    if (-not $o) { $cardOk = '选段卡里没有「出处」链接（见 4-result*.png）' }
    else {
        # 1.1.1 的「出处」不弹原文卡：openPassage 把阅读器切回原页视图（按钮变回「看文字」）、
        # 跳到被引句那一页并高亮。所以这里校验「回到原页 + 页码对上」，再切回文字视图接着问。
        Click-At $o.X $o.Y
        $back = Await-Text 7 'original' @('看文字') 15
        if (-not $back) { $cardOk = '点了出处没有切回原页视图（见 7-card.png）' }
        else {
            $pg = Page-NearNav (Shot 7 'card')
            if ($pg -ne "$physPage") { $cardOk = "点了出处切回原页但页码不是 $physPage（现在 $pg，见 7-card.png）" }
            Click-At $back.X $back.Y; Start-Sleep -Milliseconds 600
            if (-not (Await-Text 4 'text-mode-again' @('看原页') 15)) { $cardOk = '从出处回跳后切不回文字视图' }
        }
        # 1.1.1 的 ESC 在阅读器里是「退出回书架」（TextbookWorkspace.returnFromReader），不能再用它关卡片。
    }
    # 1.1.1 没有「选段提问」按钮了：追问就在同一张选段卡底部的「继续追问」框里（讲解那轮是「就这段问一句」）。
    # 点「出处」只是切回原页视图再切回来，不关卡（openPassage 不动 inlineThread），所以这里不用再拖选一次。
    $askWhy = $null
    try {
        $field = $null; $lines = @()
        foreach ($k in 0..5) {
            $lines = if ($k -eq 0) { Shot 4 'ask-card' } else { Shot 4 "ask-card$k" }
            # 「继续追问」在这个字号下常被认成「组续追问」，所以只认后半截
            $field = $lines | Where-Object { ($_.Text -replace '\s', '') -match '(续追问|问一句|问一旬)' } | Select-Object -First 1
            if ($field) { break }
            Start-Sleep -Milliseconds 700
        }
        if (-not $field) { throw '从「出处」回到文字视图后，选段卡里没有「继续追问」输入框' }
        $question = '用大白话解释一下，这里的专用人力资本是什么意思？'
        # 框里的字太小，OCR 认不出来（截图上明明有字，识别结果是空的），所以不能用「框里有没有字」判断焦点；
        # 标记直接写进问题里，回答轮的标题是「问：<原问题>」，标记出现在那行才算这一问真的发出去了。
        $askText = if ($Marker) { "$Marker $question" } else { $question }
        $fb = Get-LineBox $field
        $hdr = Has $lines @('收起')
        $scrollY = if ($hdr) { [int](($hdr.Y + $fb.Y1) / 2) } else { [Math]::Max(60, [int]($fb.Y1) - 120) }
        # 框里的字 OCR 读不出来，但框里有字时「发送」会从灰变亮、OCR 就能认到 —— 拿它当焦点探针：
        # 标签底往下逐格试落点，敲一个字后「发送」亮了就说明这一格真的点进了输入框。
        $focusY = $null
        foreach ($dy in 14, 20, 26, 32, 38, 44, 50, 56) {
            $probeY = [int]$fb.Y2 + $dy
            Click-At ([int]$fb.X1 + 150) $probeY; Start-Sleep -Milliseconds 500
            if (-not $Marker) { $focusY = $probeY; break }
            Send-Keys $Marker; Start-Sleep -Milliseconds 600
            $probe = Shot 4 "ask-probe$dy"
            if (Has $probe @('发送')) { $focusY = $probeY; break }
            Send-Keys '^a'; Send-Keys '{BACKSPACE}'; Start-Sleep -Milliseconds 250
        }
        if (-not $focusY) { throw '点不进「继续追问」框：标签下八个落点都没能把「发送」点亮' }
        Note "追问框落点：标签底 +$($focusY - $fb.Y2)"
        # 探针敲进去的字要清掉，换成真问题
        Send-Keys '^a'; Send-Keys '{BACKSPACE}'; Start-Sleep -Milliseconds 250
        Paste-Text " $askText"; Start-Sleep -Milliseconds 900
        $typed = Shot 4 'ask-typed'
        $send = Has $typed @('发送')
        if (-not $send) { throw '「继续追问」框里没粘进问题（发送没有亮）' }
        # 回车和「发送」是同一条提交路径（onSubmitQuestion），点按钮的落点由 OCR 直接给出，比回车确定。
        Click-At $send.X $send.Y
        # 卡片正文最高 360dp，讲解本身就把滚动区撑满：新加的「问：…」和它的回答追加在下面，不滚卡片看不到。
        # 一格一格往下找，找到标题就停；走到第 9 格还没出现说明滚过头，再往回找一轮，避免标题被推出视野。
        $turn = $null; $cite = $null; $i = 0; $step = 0; $sawLoading = $false
        $deadline = (Get-Date).AddSeconds(150)
        while ((Get-Date) -lt $deadline -and -not $cite) {
            Start-Sleep -Seconds 4; $i++
            $lines = Shot 4 "ask-wait$i"
            $allow = Has $lines @('允许并讲解')
            if ($allow) { Click-At $allow.X $allow.Y; continue }
            $serviceError = Get-PassageServiceError $lines
            if ($serviceError) { throw "服务回了拒绝：$serviceError" }
            $bad = $lines | Where-Object { $_.Text -match '无法回答|不能回答|尚未接通|暂时读不到回答|选中追问格式不对|无法确认|无氵去确认' } | Select-Object -First 1
            if ($bad) { throw "提问被拒：$($bad.Text)" }
            if (Has $lines @('思考')) { $sawLoading = $true }
            $label = if ($Marker) { $lines | Where-Object { ($_.Text -replace '\s', '') -match '^问' -and $_.Text -like "*$Marker*" } | Select-Object -First 1 }
                     else { $lines | Where-Object { ($_.Text -replace '\s', '') -match '^问[：:]' } | Select-Object -First 1 }
            if ($label) { $turn = $label }
            if ($turn) {
                # 自己这一问的「出处」在它的标题下面；标题滚出视野以后，视野里的出处也只会是它的。
                $tb = if ($label) { Get-LineBox $label } else { $null }
                $cite = $lines | Where-Object { $_.Text -match '出处.*第\s*\d+\s*页' -and (-not $tb -or (Get-LineBox $_).Y -gt $tb.Y) } | Select-Object -Last 1
                if (-not $cite) { Scroll-At 600 $scrollY -2; Start-Sleep -Milliseconds 500 }
            } else {
                $dir = if ($step -lt 9) { -1 } else { 1 }
                Scroll-At 600 $scrollY $dir; Start-Sleep -Milliseconds 500; $step++
            }
        }
        if (-not $turn) { throw "问题发出去了，但 150 秒内没出现「问：<自己这一问>」（加载态 $sawLoading）" }
        if (-not $cite) { throw '提问有了回答，但没等到它自己的「出处 · 第 N 页」' }
        Note "提问的回答出处：$($cite.Text)"
        $closeCard = Await-Text 4 'ask-close' @('收起') 5
        if ($closeCard) { Click-At $closeCard.X $closeCard.Y; Start-Sleep -Milliseconds 600 }
    } catch { if ("$_" -like 'DESKTOP_BUSY*' -or "$_" -like 'WIN_DRIVE_FOREGROUND_CONTENTION*') { throw }; $askWhy = "$_"; try { $cc = Await-Text 4 'ask-cleanup' @('收起') 3; if ($cc) { Click-At $cc.X $cc.Y } } catch { } }
    if ($askWhy) {
        try { Fail 4 "选段讲解可用，但实际提问失败：$askWhy" } catch { if ("$_" -ne 'STEP_FAILED') { throw }; if (-not $KeepGoing) { throw 'STEP_FAILED' } }
    } else { Pass 4 '拖选一段点「讲讲」得到带出处的讲解，也能输入问题得到带出处的回答' }

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
        # 一屏只放得下八行左右，滚一格约 1.2 行：固定用 -8 时两帧之间能跨过十行，目标会从中间漏掉。
        # 离得远时大步赶路，接近后小步走；冲过头就按反方向退回几行。
        $sp = Scroll-Point 0.25 0.6
        for ($k = 1; $k -le 120; $k++) {
            $tocLines = Shot 5 "toc$k"
            foreach ($tl in $tocLines) { if (($tl.Text -replace '\s', '') -match ("^$LessonNumber" + '[\.．。,，、]')) { $entry = Find-Text @($tl) "$LessonNumber"; break } }
            if ($entry) { break }
            $nums = @(foreach ($tl in $tocLines) { if (($tl.Text -replace '\s', '') -match '^(\d{1,4})[\.．。,，、]') { [int]$Matches[1] } })
            $maxN = if ($nums.Count) { ($nums | Measure-Object -Maximum).Maximum } else { 0 }
            $minN = if ($nums.Count) { ($nums | Measure-Object -Minimum).Minimum } else { 0 }
            $notches = if ($minN -gt $LessonNumber) { 3 } elseif ($maxN -lt ($LessonNumber - 24)) { -8 } else { -3 }
            Scroll-At $sp[0] $sp[1] $notches; Start-Sleep -Milliseconds 500
        }
        if (-not $entry) { Fail 5 "目录里滚了 120 次也没找到第 $LessonNumber 条，没有发请求" }
        Click-At $entry.X $entry.Y; Start-Sleep -Seconds 3
        Click-Label 5 @('收起目录') '找不到「收起目录」'; Start-Sleep -Seconds 2
        $sectionLines = Shot 5 'section'
        $label = $sectionLines | Where-Object { $_.Text -match '本节原文' } | Select-Object -First 1
        $labelPage = if ($label) { [regex]::Match($label.Text, '第\s*(\d+)\s*页').Groups[1].Value } else { '' }
        if (-not $labelPage) { Fail 5 '选中节后界面没有「本节原文 · 第 N 页」标签，没有发请求' }
        if ([int]$labelPage -ne $LessonPage) { Fail 5 "选中的第 $LessonNumber 条落在第 $labelPage 页，不是授权页第 $LessonPage 页，没有发请求（见 5-section.png）" }
        $deadline = (Get-Date).AddSeconds(150); $res = $null; $sawBody = $false
        while ((Get-Date) -lt $deadline -and -not $res) {
            Start-Sleep -Seconds 5; $lines = Shot 5 'learn'
            $allow = Has $lines @('允许并讲解')
            if ($allow) { Click-At $allow.X $allow.Y; continue }
            $e = $lines | Where-Object { $_.Text -match '尚未接通|无法讲解|尚未核实|连不上|超时|没有可用的讲解|讲解暂时读不到|讲解服务暂时不可用|额度.*用完|没有可核对的出处|出处已过期' } | Select-Object -First 1
            if ($e) { $res = "拒绝：$($e.Text)"; continue }
            # 「本节原文」不是讲解的出处。先看正文有没有出来（这一屏的「讲解」小标题常被 OCR 漏掉，
            # 所以按长句行数判），正文出来以后再找它自己的带页码出处。
            $source = $lines | Where-Object { $_.Text -match '出处.*第\s*\d+\s*页' } | Select-Object -First 1
            $sourceBox = if ($source) { Find-Text @($source) $source.Text } else { $null }
            $body = @($lines | Where-Object {
                ($_.Text -replace '\s', '').Length -ge 20 -and
                    $_.Text -notmatch '正在读取|正在结合|允许并讲解|发送原文|本节原文|先理解讲解|LearningCoach|Coach目录'
            })
            if ($body.Count -ge 3) { $sawBody = $true }
            if ($sawBody -and $sourceBox) { $res = 'ok' }
            elseif ($sawBody) {
                # 出处行被正文挤到折叠线以下：往下滚，把它滚进视野再判。
                $sp = Scroll-Point 0.6 0.6; Scroll-At $sp[0] $sp[1] -3
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
        } elseif ($res) { Fail 5 "边看边学的讲解没出来，界面说「$($res.Substring(3))」" } else { Fail 5 '150 秒内没有同时看到讲解正文和带页码的出处（见 5-learn.png）' }
    } catch { if ("$_" -ne 'STEP_FAILED') { throw }; $five = 'failed' }
    if ($five -and -not $KeepGoing) { throw 'STEP_FAILED' }
    if ($StopAfter -le 5) { throw 'STEP_FAILED' }   # 按要求停在第 ⑤ 步之后；已记录的结果不变

    # ⑥ 朋友自己的书：从正常界面进入授权章节后按节现出题，真实点一个选项，再等答后讲解和单题小结；不依赖整理题库准入。
    $six = $null
    try {
        $sp = Scroll-Point 0.7 0.6; Scroll-At $sp[0] $sp[1] 30; Start-Sleep -Seconds 1
        Click-Label 6 @('返回书架') '无法从学习页返回书架'
        # 书架一屏放不下三条入口：做题学在折叠线以下，得先滚出来再点。
        $path = $null
        foreach ($k in 1..8) {
            $shelf = Shot 6 "path$k"
            $path = Has $shelf @('做题学', '继续做题学')
            if ($path) { break }
            $sp = Scroll-Point 0.7 0.6; Scroll-At $sp[0] $sp[1] -4; Start-Sleep -Milliseconds 600
        }
        if (-not $path) { Fail 6 '书架没有做题学入口' }
        Click-At $path.X $path.Y; Start-Sleep -Milliseconds 800
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
            # Only the foreground driver can lose focus mid-click. The background driver never owns
            # the foreground, so a foreign foreground is not evidence there: wait, never re-click.
            $sawFocusLoss = $false
            if (-not (Has-BackgroundDriver)) {
                $fgAfterClick = Get-ForegroundOwner
                $sawFocusLoss = -not $fgAfterClick.IsOwnCoach
            }
            Start-Sleep -Milliseconds 800
            if (-not (Has-BackgroundDriver)) {
                $fgSettled = Get-ForegroundOwner
                if (-not $fgSettled.IsOwnCoach) { $sawFocusLoss = $true }
            }
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
                    if ($dir -eq 'next') { Click-Folded 6 @('继续下一节') '做题学页面找不到「继续下一节」' } else { Click-Folded 6 @('上一节') '做题学页面找不到「上一节」' }
                    # 找按钮时把整节正文滚到了底下，切完节再滚回顶部，后面按标题和「本节原文」认新节才看得见。
                    $sp = Scroll-Point 0.6 0.6; Scroll-At $sp[0] $sp[1] 20; Start-Sleep -Milliseconds 400
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
        $ctrl = Get-QuestionSettingsControls $script:Last
        if (-not $ctrl -or -not $ctrl.Choice) { Fail 6 '出题方式对话框没有选择题选项' }
        Click-At $ctrl.Choice.X $ctrl.Choice.Y; Start-Sleep -Milliseconds 800
        # 点完「选择」后再截图核对对话框内的「确定」文案与坐标，防止误点背景关闭弹窗。
        $confirmLines = Shot 6 'settings-confirm'
        $ctrlConfirm = Get-QuestionSettingsControls $confirmLines
        if (-not $ctrlConfirm -or -not $ctrlConfirm.Confirm -or $ctrlConfirm.Confirm.Y -le $ctrl.Choice.Y -or ($ctrlConfirm.Choice -and $ctrlConfirm.Confirm.Y -le $ctrlConfirm.Choice.Y)) {
            Fail 6 '出题方式对话框找不到「确定」按钮'
        }
        $choiceBox = if ($ctrlConfirm.Choice) { $ctrlConfirm.Choice } else { $ctrl.Choice }
        Note "步骤6 设置确认：标题($($ctrlConfirm.Title.X),$($ctrlConfirm.Title.Y)) 选项「选择」($($choiceBox.X),$($choiceBox.Y)) 按钮「确定」($($ctrlConfirm.Confirm.X),$($ctrlConfirm.Confirm.Y))"
        Click-At $ctrlConfirm.Confirm.X $ctrlConfirm.Confirm.Y
        $confirmDeadline = (Get-Date).AddSeconds(10); $confirmed = $false
        while ((Get-Date) -lt $confirmDeadline -and -not $confirmed) {
            Start-Sleep -Milliseconds 500
            $afterLines = Shot 6 'settings-after'
            if (Test-QuestionSettingsConfirmed $afterLines) { $confirmed = $true }
        }
        if (-not $confirmed) { Fail 6 '点击「确定」后未关闭出题方式对话框或未确认为选择题' }
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
            $sp = Scroll-Point 0.7 0.6; Scroll-At $sp[0] $sp[1] -2; Start-Sleep -Seconds 1
        }
        if (-not $selected) { Fail 6 '点击选项后没有看到已选答案' }
        if (-not $explained -or -not $cited) { Fail 6 '已作答，但没有看到带出处的答后讲解' }
        if (-not $summary) { Fail 6 '已作答并看到讲解，但没有看到单题小结' }
        Pass 6 '从正常界面进入授权章节，现出选择题作答后出现已选答案、带出处的答后讲解和单题小结（6-question、6-answered 私有截图）'
    } catch { if ("$_" -ne 'STEP_FAILED') { throw }; $six = 'failed' }
    if ($six -and -not $KeepGoing) { throw 'STEP_FAILED' }

    if ($cardOk) { Fail 7 $cardOk } else { Pass 7 "点出处切回第 $physPage 页原页" }
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
