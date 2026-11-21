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
. (Join-Path $PSScriptRoot 'Page-Counter.ps1')
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
function Page-NearNav($lines) {
    $prev = Find-PageNav $lines '上一页'; $next = Find-PageNav $lines '下一页'
    if (-not $prev -or -not $next) { return $null }
    foreach ($l in $lines) {
        $mm = [regex]::Match(($l.Text -replace '\s', ''), '^(\d+)(?:[/／](\d+))?[^\d]{0,3}$')
        if (-not $mm.Success) { continue }
        $b = Get-LineBox $l
        if ([Math]::Abs($b.Y - $prev.Y) -le 16 -and $b.X1 -ge ($prev.X2 - 2) -and $b.X2 -le ($next.X1 + 2)) { return $mm.Groups[1].Value }
    }
    return $null
}
# 讲到的原文页：书首书尾时一边的翻页按钮会消失，认不出按钮就放弃（不猜）。
function Get-OriginalPageNumber($lines, [string]$png) {
    $c = Get-PageCounter $lines $png
    if ($c) { return "$($c.Cur)" }
    return $null
}
# 「暂不开启」是暗底上最暗的一档字（实测字 46–68、底色 32，放大两倍也认不出来）。
# 把对话框底栏那一条抠出来，按它自己的底色二值化成白底黑字再放大三倍，才能认出按钮；坐标换算回原图。
function Find-DimButton([string]$png, [string]$needle) {
    $src = [Drawing.Bitmap]::new($png)
    $file = Join-Path (Split-Path -Parent $png) ('dim-' + [guid]::NewGuid().ToString('N').Substring(0, 8) + '.png')
    try {
        $scale = 3
        $left = [int]($src.Width / 2) - 230; $right = [Math]::Min($src.Width, $left + 460)
        $top = [Math]::Max(0, $src.Height - 90); $bottom = $src.Height - 6
        $w = $right - $left; $h = $bottom - $top
        if ($w -le 0 -or $h -le 0) { return $null }
        $lum = New-Object 'int[,]' $w, $h
        $hist = @{}
        for ($y = 0; $y -lt $h; $y++) {
            for ($x = 0; $x -lt $w; $x++) {
                $c = $src.GetPixel($left + $x, $top + $y)
                $v = [int](($c.R * 299 + $c.G * 587 + $c.B * 114) / 1000)
                $lum[$x, $y] = $v; $hist[$v] = 1 + [int]$hist[$v]
            }
        }
        # 这一条里出现最多的灰度就是对话框底色；比它亮 8 个灰度以上算字。
        $bg = [int](($hist.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 1).Name)
        $bmp = [Drawing.Bitmap]::new($w * $scale, $h * $scale)
        try {
            $black = [Drawing.Color]::Black; $white = [Drawing.Color]::White
            for ($y = 0; $y -lt $h; $y++) {
                for ($x = 0; $x -lt $w; $x++) {
                    $col = if ($lum[$x, $y] -ge ($bg + 8)) { $black } else { $white }
                    for ($dy = 0; $dy -lt $scale; $dy++) {
                        for ($dx = 0; $dx -lt $scale; $dx++) { $bmp.SetPixel($x * $scale + $dx, $y * $scale + $dy, $col) }
                    }
                }
            }
            $bmp.Save($file, [Drawing.Imaging.ImageFormat]::Png)
        } finally { $bmp.Dispose() }
        $m = Find-Text @(Read-Bitmap $file) $needle
        if (-not $m) { return $null }
        return [pscustomobject]@{ X = [int]($left + $m.X / $scale); Y = [int]($top + $m.Y / $scale); X1 = [int]($left + $m.X1 / $scale); X2 = [int]($left + $m.X2 / $scale); Y1 = [int]($top + $m.Y1 / $scale); Y2 = [int]($top + $m.Y2 / $scale) }
    } finally {
        $src.Dispose()
        Remove-Item -LiteralPath $file -ErrorAction SilentlyContinue
    }
}
# 标签行必须整行就是这个词，避免书架说明文字里的「看书」把点击带偏。
function Find-Exact($lines, [string]$needle) {
    foreach ($l in $lines) {
        if (($l.Text -replace '\s', '') -eq $needle) {
            $b = Get-LineBox $l
            return [pscustomobject]@{ X = [int](($b.X1 + $b.X2) / 2); Y = [int](($b.Y1 + $b.Y2) / 2); X1 = [int]$b.X1; X2 = [int]$b.X2; Y1 = [int]$b.Y1; Y2 = [int]$b.Y2 }
        }
    }
    return $null
}
# The lowest on-screen occurrence of a label (e.g. the last button on a page).
function Find-Lowest($lines, [string]$needle) { $best = $null; foreach ($l in $lines) { $m = Find-Text @($l) $needle; if ($m -and (-not $best -or $m.Y -gt $best.Y)) { $best = $m } }; $best }
# 光标停在底栏按钮上时 Compose 会浮出「下一页 → PageDown」的 tooltip，它比底栏还高一行；
# Find-Text 从上往下取首命中就会点到浮层上（11-21 03:12 L3 三次重试都戳在正文区，页码一直 1/62）。
# 认翻页按钮的 Find-PageNav 在 Page-Counter.ps1（第 3 步读页号也要用它算底栏那一条的边界）。
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
# 「继续追问」框里的字太小，OCR 读不出来；粘完要靠框内区域的亮像素数确认「框里确实有字」。
# 空框只有深色底和一圈深色边框，加了字会多出成百上千个亮像素，和空框那张比一下就有数了。
function Get-FieldInk([string]$png, $label) {
    $bmp = [System.Drawing.Bitmap]::FromFile($png)
    try {
        $x0 = [int]$label.X1 + 2; $y0 = [int]$label.Y2 + 10
        $x1 = [Math]::Min($x0 + 420, $bmp.Width - 2); $y1 = [Math]::Min($y0 + 42, $bmp.Height - 2)
        $ink = 0
        for ($y = $y0; $y -le $y1; $y++) {
            for ($x = $x0; $x -le $x1; $x++) {
                $c = $bmp.GetPixel($x, $y)
                if ($c.R -gt 140 -and $c.G -gt 140 -and $c.B -gt 140) { $ink++ }
            }
        }
        return $ink
    } finally { $bmp.Dispose() }
}
function Find-All($lines, [string]$needle) { $all = @(); foreach ($l in $lines) { $m = Find-Text @($l) $needle; if ($m) { $all += $m } }; $all | Sort-Object Y }
# Locate controls inside the open question-mode dialog, ignoring the background toolbar button and hint copy.
function Get-QuestionSettingsControls($lines) {
    $title = Find-Text $lines '出题方式'
    if (-not $title) { return $null }
    $modeHeaders = @(@(foreach ($l in $lines) {
        if ($l.Text -match '(出题|题型)[:：]') { continue }
        $m = Find-Text @($l) '出题'
        if ($m -and $m.Y -gt $title.Y2 -and [Math]::Abs($m.X1 - $title.X1) -le 60) {
            [pscustomobject]@{ Box = $m; Exact = (($l.Text -replace '\s', '') -eq '出题') }
        }
    }) | Sort-Object { $_.Box.Y })
    $exactHeader = @($modeHeaders | Where-Object { $_.Exact } | Select-Object -First 1)
    $modeHeader = if ($exactHeader.Count -gt 0) { $exactHeader[0].Box } elseif ($modeHeaders.Count -gt 0) { $modeHeaders[0].Box } else { $null }
    $minChoiceY = if ($modeHeader) { $modeHeader.Y2 } else { $title.Y2 }
    $choices = @(@(foreach ($l in $lines) {
        if ($l.Text -match '(出题|题型)[:：]' -or $l.Text -match '选择题') { continue }
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
        if (($l.Text -replace '\s', '') -match '(出题|题型)[:：]选择') { return $true }
    }
    return $false
}
# Windows OCR at 12-13px frequently misreads the leading 「问：」 as 「同：」/「间：」/「向：」.
function Find-AskTurnLabel($lines, [string]$marker) {
    foreach ($l in $lines) {
        $compact = $l.Text -replace '\s', ''
        if ($marker) {
            if ($compact -match '^(?:问|同|间|向)[：:.．]?' -and $l.Text -like "*$marker*") { return $l }
        } elseif ($compact -match '^(?:问|同|间|向)[：:.．].*(?:用大白话|专用人力资本)') {
            return $l
        }
    }
    return $null
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
# 点一下再等结果：$Done 拿到一帧 OCR 行，返回 $true 才算这一下真的生效。
# 第 5 步入口那三下（开始学 → 边看边学 → 开始边看边学）在四路并行时各只点一次就会丢：
# 11-21 03:26 L3 点完「开始学」弹窗没开、L1 点了「边看边学」底按钮仍是「开始做题学」、
# L2 底按钮已变「开始边看边学」但点完弹窗没提交，而 L4 独跑时三下都一次过。
# 所以「点过一次」不算数，只认这一帧的状态；状态没变就按新一帧重定位再点，
# 连位置都找不到了就停手（用旧坐标再点一下只会点在别的控件上）。
function Click-Until([int]$n, [scriptblock]$Find, [scriptblock]$Done, [string]$why, [int]$sec = 15, [int]$tries = 4, [int]$pauseMs = 0) {
    if (& $Done (Shot $n 'entry-check')) { return }
    $m = $null; $deadline = (Get-Date).AddSeconds($sec)
    do {
        $m = & $Find (Shot $n 'entry-find')
        if (-not $m) { Start-Sleep -Seconds 2 }
    } while (-not $m -and (Get-Date) -lt $deadline)
    if (-not $m) { Fail $n $why }
    foreach ($try in 1..$tries) {
        Click-At $m.X $m.Y
        Start-Sleep -Milliseconds 1000
        $lines = Shot $n "entry$try"
        if (& $Done $lines) { return }
        # 按钮在、点了却没反应，多半是 ui.busy 把工具栏禁用了（第 4 步切回文字视图时后台写库，
        # 四路同写会 SQLITE_BUSY 卡十几秒）。这里等一拍再按新一帧重定位，别把重试额度在几百毫秒里烧光。
        if ($pauseMs -gt 0) { Start-Sleep -Milliseconds $pauseMs; $lines = Shot $n "entry${try}p" }
        $again = & $Find $lines
        if (-not $again) {
            Start-Sleep -Milliseconds 800
            $lines = Shot $n "entry${try}b"
            if (& $Done $lines) { return }
            $again = & $Find $lines
        }
        if (-not $again) { break }
        $m = $again
    }
    $last = (Shot $n 'entry-fail') | ForEach-Object { $_.Text }
    Fail $n "$why（点了 $try 次状态没变；屏上：$((@($last) -join ' / ')))"
}
# 点一下等一个原生窗口真的出现：$IsOpen 成立才算这一下生效。第 2 步「上传教材」在四路并行时会丢：
# 11-21 04:26 L4 点完交锁后 3 ms 就被别路抢走前台，选书框根本没开，Choose-File 只会干等 20 秒判失败。
# 所以没弹框就按新一帧重新定位再点；框开过以后的失败（路径没填进去、回车没关掉）不重点——
# 那种情况 Choose-File 自己会关框并报原因，再点一下反而可能点在残留的框上。
function Click-Until-Dialog([int]$n, [string]$label, [scriptblock]$IsOpen, [int]$sec = 40, [int]$tries = 4, [int]$findSec = 15, [int]$openSec = 6) {
    $m = $null; $deadline = (Get-Date).AddSeconds($findSec)
    do {
        $m = Find-Text (Shot $n 'find') $label
        if (-not $m) { Start-Sleep -Seconds 2 }
    } while (-not $m -and (Get-Date) -lt $deadline)
    if (-not $m) { Fail $n "屏幕上找不到「$label」" }
    $deadline = (Get-Date).AddSeconds($sec)
    foreach ($try in 1..$tries) {
        if (& $IsOpen) { return }
        if ($try -gt 1) {
            # 上一轮点空了：光标可能被别路挪走，按新一帧重新定位。
            $m = Find-Text (Shot $n 'dialog-find') $label
            if (-not $m) { Fail $n "点了 $($try - 1) 次「$label」，选书框都没出来，按钮也找不到了" }
        }
        Click-At $m.X $m.Y
        $stop = (Get-Date).AddSeconds($openSec); if ($stop -gt $deadline) { $stop = $deadline }
        while ((Get-Date) -lt $stop) {
            if (& $IsOpen) { return }
            Start-Sleep -Milliseconds 400
        }
        Note ("第 {0} 步第 {1} 次点「{2}」没弹出选书框，重定位后再点" -f $n, $try, $label)
    }
    Fail $n "点了 $tries 次「$label」，选书框都没出来"
}
# 点一下、等状态变：状态没变就按新一帧重定位再点。第 7 步那三下（出处→切回原页、收起→露出底栏、
# 看文字→切回文字视图）在四路交锁时都会落空：11-21 04:46 L3 抬完手 1.0 ms 前台就被别路接走，
# 点出处后仍停在「看原页」；L2 点收起只留下 hover 底色，卡片没动。这两下都是幂等切换，
# 看到状态没变就再点一次是安全的。返回 $true 表示看到了状态；按钮找不到、或点满 $tries 次
# 都没变，都返回 $false，怎么报由调用方决定（第 7 步要留着后面几步跑完，不能在这里 throw）。
function Click-Confirm([int]$n, [scriptblock]$Find, [scriptblock]$Done, [int]$tries = 4, [int]$waitSec = 5, [int]$pauseMs = 600) {
    if (& $Done (Shot $n 'confirm')) { return $true }
    foreach ($try in 1..$tries) {
        $m = & $Find (Shot $n "confirm$try")
        if (-not $m) { return $false }
        Click-At $m.X $m.Y
        $stop = (Get-Date).AddSeconds($waitSec)
        while ((Get-Date) -lt $stop) {
            if (& $Done (Shot $n "confirm${try}done")) { return $true }
            Start-Sleep -Milliseconds 800
        }
        Note ("第 {0} 步第 {1} 次点完状态没变，重定位再点" -f $n, $try)
        if ($pauseMs -gt 0) { Start-Sleep -Milliseconds $pauseMs }
    }
    return $false
}
# 第 4 步「讲讲」在第几：工具条一行是「讲讲」「举个例子」「换个说法」「问…」，
# 只认和「举个例子」同一行、在它左边的那个，免得点到正文里别的「讲讲」。
function Find-ExplainButton($lines) {
    $row = Find-Text $lines '举个例子'
    if (-not $row) { $row = Find-Text $lines '换个说法' }
    if (-not $row) { return $null }
    return (Find-OnRow $lines '讲讲' $row)
}
# 点完「讲讲」算不算生效：同意框弹出、或者已经进了加载态（「思考」）、或者回答已经出来（「出处」）。
# 只认这几种看得见的状态——「点过一次」不算，四路交锁时那一下常常被别的窗口吃掉。
function Test-ExplainStarted($lines) {
    foreach ($s in '允许并讲解', '这段原文会发到哪里', '思考', '出处') {
        if (Find-Text $lines $s) { return $true }
    }
    return $false
}
# 追问时卡片正文往下找标题的方向：回答只会追加在下面，所以见过「思考」就只往下滚——
# 11-21 那轮 L2/L3 在第 9 格倒滚回页顶，剩下的 150 秒一直停在页顶等。没见加载态时仍按
# 「下扫 9 格 → 上扫 9 格 → 再下扫」的来回找，避免标题被推出视野后一直找不到。
function Get-AskScrollDir([bool]$sawLoading, [int]$step) {
    if ($sawLoading) { return -1 }
    if ($step -lt 9) { return -1 }
    if ($step -lt 18) { return 1 }
    return -1
}
# 书架右栏本来就有「看书 / 边看边学 / 做题学」三行，弹窗盖上去以后 OCR 两处都读得到，
# 从上往下取首命中会点回书架上（弹窗底下那层被遮罩吃掉，点了没反应）。
# 弹窗里这三个是并排的三个 chip（同一行），书架里是上下三行，所以先认和另外两个同排的那一个；
# 认不出来就退一步认「这次怎么读」正下方、底部按钮之上的那一个。
function Find-DialogPath($lines, [string]$needle) {
    foreach ($l in $lines) {
        $m = Find-Text @($l) $needle
        if ($m -and (Find-OnRow $lines '看书' $m) -and (Find-OnRow $lines '做题学' $m)) { return $m }
    }
    $top = Find-Text $lines '这次怎么读'
    if (-not $top) { return $null }
    $bottom = $null
    foreach ($s in @('开始边看边学', '开始做题学', '开始看书')) {
        $c = Find-Lowest $lines $s
        if ($c -and (-not $bottom -or $c.Y -lt $bottom.Y)) { $bottom = $c }
    }
    $best = $null
    foreach ($l in $lines) {
        $m = Find-Text @($l) $needle
        if (-not $m -or $m.Y -le $top.Y -or ($m.Y - $top.Y) -gt 160) { continue }
        if ($bottom -and $m.Y -ge $bottom.Y) { continue }
        if (-not $best -or $m.Y -lt $best.Y) { $best = $m }
    }
    return $best
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
        # 运行目录可以放在 <检出>/desktopApp/build 之外（并行几条泳道时各拷一份 smoke-runtime）：
        # 起窗前把它登记成本轮的归属锚点，Win-Drive 才认得出这个客户端是自己的。
        # 一份运行目录只能给一条泳道用——两条泳道的命令行一样，就分不清是谁的窗了。
        $script:OwnedClientRoot = $Runtime
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
    # 只认原生选书框弹出来：四路同时开框时这一下可能在交锁后 3 ms 被别路抢前台吞掉（11-21 04:26 L4），
    # 框根本没开，Choose-File 干等 20 秒才判失败。没弹框就重定位再点，弹过框以后的失败交给 Choose-File 报。
    Click-Until-Dialog 2 '上传教材' { (Find-AppWindowByTitle '上传教材') -ne [IntPtr]::Zero }
    if (-not (Choose-File '上传教材' $Book)) { Fail 2 $script:ChooseFileWhy }
    $m = Await-Text 2 'shelf' @('教材已加入书架', '先读') 120
    if (-not $m) { Fail 2 '选了书但 120 秒内书架里没出现这本书' }
    Pass 2 '书出现在书架'

    # ③ 翻页
    # 上传后的「这次怎么读」卡上点「先读」进阅读器；导入收尾时按钮可能还没亮，点不动就再点。
    # 提示卡自己收起或点空了会停在书架卡上（11-21 00:47 那轮就是这样），这时「看书」那一行才是进阅读器的入口，两条路都试。
    $lines = Shot 3 'find'
    $m = Find-PageNav $lines '下一页'
    $deadline = (Get-Date).AddSeconds(90)
    while (-not $m -and (Get-Date) -lt $deadline) {
        $b = Has $lines @('先读')
        if ($b) { Click-At $b.X $b.Y; Start-Sleep -Seconds 1 }
        else {
            $r = Find-Exact $lines '看书'
            if (-not $r) { $r = Find-Exact $lines '继续看书' }
            if ($r) { Click-At $r.X $r.Y; Start-Sleep -Seconds 1 }
        }
        $d = Await-Text 3 'dismiss' @('要开始学吗') 2
        if ($d) {
            # 只读这本书：点「暂不开启」而不是按 ESC（1.1.1 的 ESC 会退回书架）。按钮是暗底灰字，放大两倍才认得出来；
            # 阅读器刚打开时它可能还是灰的（ui.busy），点不动就下一轮再点，循环有 90 秒。
            $off = Find-Text $script:Last '暂不开启'
            if (-not $off) { $off = Find-DimButton $script:LastPng '暂不开启' }
            if (-not $off) { Fail 3 '「要开始学吗」上的「暂不开启」认不出来（见 3-dismiss.png）' }
            Click-At ([int]$off.X) ([int]$off.Y); Start-Sleep -Seconds 1
        }
        $lines = Shot 3 'reader'
        $m = Find-PageNav $lines '下一页'
    }
    if (-not $m) { Fail 3 '没有进到阅读器' }
    # 页号只从底栏「上一页」「下一页」之间那一条读：整帧 OCR 会把「2 / 620」拆成「2」+「/62@」
    # （11-21 03:49 的 2b 四路全栽在这，页其实已经翻过去了）。读不出就一直重拍到 10 秒。
    $page0 = $null
    foreach ($try in 1..5) {
        $before = Shot 3 'page-before'
        $c0 = Get-PageCounter $before $script:LastPng
        if ($c0) { $page0 = $c0.Text; break }; Start-Sleep -Seconds 2
    }
    $page1 = $page0
    foreach ($try in 1..3) {
        # 点空了先重拍再点：光标停在底栏时浮层/布局都可能变，沿用上一轮的旧坐标只会继续点在原地。
        if ($try -gt 1) {
            $again = Find-PageNav (Shot 3 'page-retry') '下一页'
            if ($again) { $m = $again }
        }
        Click-At $m.X $m.Y
        $stop = (Get-Date).AddSeconds(8)
        while ((Get-Date) -lt $stop -and (-not $page1 -or $page1 -eq $page0)) {
            Start-Sleep -Seconds 2; $after = Shot 3 'page-after'
            $c1 = Get-PageCounter $after $script:LastPng
            if ($c1) { $page1 = $c1.Text }
        }
        if ($page1 -and $page1 -ne $page0) { break }
    }
    if (-not $page0 -or -not $page1 -or $page0 -eq $page1) { Fail 3 "点了下一页页码没变（前 $page0，后 $page1，最后落点 $($m.X),$($m.Y)）" }
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
    # 「讲讲」和随后的「允许并讲解」原来各只点一下：四路交锁时前台被别路接走，这两下都会落空。
    # 11-21 那轮 L1/L4 点了「讲讲」等于没点，同意框既没出来也没进加载态；L3 同意框出来了、
    # 「允许并讲解」点空，框一直开着——三路都在这里空等 120 秒报「既没有讲解也没有报错」。
    # 所以两下都认状态：状态没变就按新一帧重定位再点，点了三次还没动就当场说清是哪一下没成。
    $started = Click-Confirm 4 { param($l) Find-ExplainButton $l } { param($l) Test-ExplainStarted $l } -tries 3 -waitSec 6 -pauseMs 800
    # 同意框有时要十几秒才弹（后台先把这段原文送出去），点完状态没变也再宽限一次，别比原来等得还短。
    if (-not $started) { $started = [bool](Await-Text 4 'explain-late' @('允许并讲解', '这段原文会发到哪里', '思考', '出处') 15) }
    if (-not $started) { Fail 4 '点了三次「讲讲」都没有同意框、加载态或讲解出现（见 4-confirm*.png）' }
    if (Find-Text (Shot 4 'consent') '允许并讲解') {
        # 这本书第一次讲解要先同意；框没关就是没点上，按新一帧重定位再点（开发验收允许点）。
        $allowed = Click-Confirm 4 { param($l) Find-Text $l '允许并讲解' } { param($l) -not (Find-Text $l '允许并讲解') } -tries 3 -waitSec 6 -pauseMs 800
        if (-not $allowed) { Fail 4 '同意框里的「允许并讲解」点了三次框都没关（见 4-consent*.png）' }
    }
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
    # 1.1.1 没有「选段提问」按钮了：追问就在同一张选段卡底部的「继续追问」框里；先完成追问，再在⑦里点「出处」并「收起」卡片，
    # 避免 800×600 窗口里 360dp 的选段卡把底栏「上一页 / 下一页」推到屏幕外。
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
        if (-not $field) { throw '选段卡里没有「继续追问」输入框' }
        $question = '用大白话解释一下，这里的专用人力资本是什么意思？'
        # 标记直接写进问题里，回答轮的标题是「问：<原问题>」，标记出现在那行才算这一问真的发出去了。
        $askText = if ($Marker) { "$Marker $question" } else { $question }
        $fb = Get-LineBox $field
        $hdr = Has $lines @('收起')
        $scrollY = if ($hdr) { [int](($hdr.Y + $fb.Y1) / 2) } else { [Math]::Max(60, [int]($fb.Y1) - 120) }
        # 框里的字太小，OCR 认不出来（截图上明明有字、识别结果是空的），所以「焦点」和「框里有字」都不能靠读字：
        #   · 焦点：点进框里敲两个 ASCII 哨兵字符，「发送」会从灰变亮、OCR 就能认到，这是焦点确实落进框里的证据；
        #   · 有字：粘完数一遍框内区域的亮像素，和空框那张比多出上百个，才算真粘进去了。
        # 哨兵与 -Marker 无关：以前没传 -Marker 时会跳过探针直接点标签底 +14，那一格在框上边沿外，
        # 粘贴全丢，只剩「发送没亮」一条看不出原因的失败（2026-11-21 00:18 那轮 6/7 就是这么挂的）。
        # 实测 +20 是框内第一行，+14 落在框上边沿，所以按 +20 先试。
        $sentinel = 'zk'
        $focus = $null
        foreach ($dy in 20, 14, 26, 32, 38, 44, 50, 56) {
            $probeY = [int]$fb.Y2 + $dy
            Click-At ([int]$fb.X1 + 150) $probeY; Start-Sleep -Milliseconds 400
            Send-Keys $sentinel; Start-Sleep -Milliseconds 700
            $probe = Shot 4 "ask-probe$dy"
            if (Has $probe @('发送')) { $focus = @{ Y = $probeY; Dy = $dy }; break }
            Send-Keys '^a'; Send-Keys '{BACKSPACE}'; Start-Sleep -Milliseconds 250
        }
        if (-not $focus) { throw '点不进「继续追问」框：标签下八个落点敲哨兵都没能把「发送」点亮' }
        Note "追问框落点：标签底 +$($focus.Dy)（哨兵点亮了「发送」）"
        # 清掉哨兵，留一张空框基线，粘贴后拿它当对照
        Send-Keys '^a'; Send-Keys '{BACKSPACE}'; Start-Sleep -Milliseconds 300
        $null = Shot 4 'ask-empty'
        $emptyInk = Get-FieldInk $script:LastPng $fb
        # 粘完要验两件事：框里有字（亮像素明显多于空框）且「发送」亮。粘不进去就回框里再点一次重粘，最多三次。
        $pasted = $false; $send = $null; $ink = $emptyInk
        foreach ($attempt in 1..3) {
            if ($attempt -gt 1) {
                Send-Keys '^a'; Send-Keys '{BACKSPACE}'; Start-Sleep -Milliseconds 200
                Click-At ([int]$fb.X1 + 150) $focus.Y; Start-Sleep -Milliseconds 400
            }
            Paste-Text " $askText"; Start-Sleep -Milliseconds 900
            $typed = Shot 4 $(if ($attempt -eq 1) { 'ask-typed' } else { "ask-typed$attempt" })
            $send = Has $typed @('发送')
            $ink = Get-FieldInk $script:LastPng $fb
            Note "粘入第 $attempt 次：框内亮像素 $ink（空框 $emptyInk），发送 $(if ($send) { '亮' } else { '灰' })"
            if ($send -and $ink -gt ($emptyInk + 40)) { $pasted = $true; break }
        }
        if (-not $pasted) { throw "「继续追问」框粘了三次都没成：框内亮像素 $ink（空框 $emptyInk），发送 $(if ($send) { '亮' } else { '灰' })（见 4-ask-typed*.png）" }
        # 回车和「发送」是同一条提交路径（onSubmitQuestion），点按钮的落点由 OCR 直接给出，比回车确定。
        Click-At $send.X $send.Y
        # 卡片正文最高 360dp，讲解本身就把滚动区撑满：新加的「问：…」和它的回答追加在下面，不滚卡片看不到。
        # 一格一格往下找，找到标题就停；走到第 9 格还没出现说明可能滚过头，再往回找一轮，避免标题被推出视野。
        # 但见过「思考」以后回答只会在下面追加，往回滚等于把新内容推出视野，所以那时只往下（见 Get-AskScrollDir）。
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
            $label = Find-AskTurnLabel $lines $Marker
            if ($label) { $turn = $label }
            if ($turn) {
                # 自己这一问的「出处」在它的标题下面；标题滚出视野以后，视野里的出处也只会是它的。
                $tb = if ($label) { Get-LineBox $label } else { $null }
                $cite = $lines | Where-Object { $_.Text -match '出处.*第\s*\d+\s*页' -and (-not $tb -or (Get-LineBox $_).Y -gt $tb.Y) } | Select-Object -Last 1
                if (-not $cite) { Scroll-At 600 $scrollY -2; Start-Sleep -Milliseconds 500 }
            } else {
                $dir = Get-AskScrollDir $sawLoading $step
                Scroll-At 600 $scrollY $dir; Start-Sleep -Milliseconds 500; $step++
            }
        }
        if (-not $turn) { throw "问题发出去了，但 150 秒内没出现「问：<自己这一问>」（加载态 $sawLoading）" }
        if (-not $cite) { throw '提问有了回答，但没等到它自己的「出处 · 第 N 页」' }
        Note "提问的回答出处：$($cite.Text)"
    } catch { if ("$_" -like 'DESKTOP_BUSY*' -or "$_" -like 'WIN_DRIVE_FOREGROUND_CONTENTION*') { throw }; $askWhy = "$_" }

    # ⑦ 依赖选段卡：点「出处」切回原页（按钮变回「看文字」），再点「收起」露出底栏核对页码，最后切回文字视图。
    # 这三下在四路交锁时都可能落空（点了但窗口没处理），所以每下都认状态，没变就重定位再点；
    # 失败不在这里 throw——后面几步不依赖它，留到最后统一报，免得少记几步通过。
    $cardOk = $null
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
        # 切回原页的标志是右侧按钮从「看原页」变成「看文字」。只有还停在「看原页」才重点同一个链接；
        # 认不出当前视图（两个都没有）就停手，别把点击打到已经变过的界面上。
        $citeAt = $o
        $clicked = Click-Confirm 7 { param($l) if (Find-Text $l '看原页') { $citeAt } } { param($l) [bool](Find-Text $l '看文字') }
        $back = if ($clicked) { Has $script:Last @('看文字') } else { $null }
        if (-not $back) { $cardOk = '点了出处没有切回原页视图（见 7-card.png）' }
        else {
            # 收起选段卡底栏才露出来：L2 那次鼠标确实落在按钮正中（hover 底色还在）卡片却没动，
            # 所以以「收起」消失为准，没消失就再点。
            $closed = Click-Confirm 7 { param($l) Find-Text $l '收起' } { param($l) -not (Find-Text $l '收起') } -tries 4 -waitSec 4
            $pg = $null
            for ($try = 0; $try -lt 4 -and $pg -ne "$physPage"; $try++) {
                if ($try -gt 0) { Start-Sleep -Milliseconds 500 }
                $pg = Page-NearNav (Shot 7 'card')
            }
            if ($pg -ne "$physPage") {
                $cardOk = if ($closed) { "点了出处切回原页但页码不是 $physPage（现在 $pg，见 7-card.png）" } else { '点了「收起」选段卡没收起，底栏页码读不到（见 7-card.png）' }
            }
            # 切回文字视图：按钮这时叫「看文字」，切过去以后变成「看原页」，同样认状态。
            $backNow = Has $script:Last @('看文字')
            if (-not $backNow) { $backNow = $back }
            $okBack = Click-Confirm 7 { param($l) $b = Find-Text $l '看文字'; if ($b) { $b } else { $backNow } } { param($l) [bool](Find-Text $l '看原页') } -tries 3
            if (-not $okBack) { $cardOk = '从出处回跳后切不回文字视图' }
        }
    }
    try { $cc = Has $script:Last @('收起'); if ($cc) { Click-At $cc.X $cc.Y; Start-Sleep -Milliseconds 400 } } catch { }
    if ($askWhy) {
        try { Fail 4 "选段讲解可用，但实际提问失败：$askWhy" } catch { if ("$_" -ne 'STEP_FAILED') { throw }; if (-not $KeepGoing) { throw 'STEP_FAILED' } }
    } else { Pass 4 '拖选一段点「讲讲」得到带出处的讲解，也能输入问题得到带出处的回答' }

    # ⑤ 边看边学出讲解
    # 客户端对「已同意」的书，进入或切换整节就会自动发讲解请求（BookLessonExplanation）。④ 已经替这本书同意过，
    # 所以先撤掉本轮测试书架里这本书的同意：默认首节和选错的节只显示同意卡，不发请求；核准落点页之后才由驱动点同意，发一次。
    try { Clear-ExplainConsent $lib } catch { Fail 5 "没能撤掉这本测试书的同意（$_），不进默认节，避免自动发送" }
    $five = $null
    try {
        # 第 4 步从「看原页」切回「看文字」时，客户端会在后台把视图状态写进 library.sqlite。四路同写会
        # SQLITE_BUSY：弹「阅读视图暂未保存。切换视图后会再试一次。」、顶栏进度条亮着、工具栏全灰
        # （11-21 04:15 L1/L2 有这条提示，L3/L4 只剩进度条）。这段窗口里点「开始学」不算数，
        # 先等提示条消失（最多 20 秒）再点；没有提示条时也借这一帧确认工具栏已经画回来。
        $settleStop = (Get-Date).AddSeconds(20)
        while ((Get-Date) -lt $settleStop) {
            $null = Shot 5 'settle'
            if (-not (Has $script:Last @('阅读视图暂未保存'))) { break }
            Start-Sleep -Seconds 2
        }
        # 入口三下都要看到状态才走下一句：弹窗开了（「这次怎么读」）、底按钮换成「开始边看边学」、弹窗关掉。
        # 弹窗标题「开始学这本教材」里也有「开始学」，所以认按钮时跳过这一行，别把标题当按钮点。
        # 写库没完时按钮是灰的、点了不算数，所以每下给 8 次机会、每次隔 2 秒。
        Click-Until 5 { param($l) foreach ($line in $l) { if ($line.Text -match '这本教材') { continue }; $m = Find-Text @($line) '开始学'; if ($m) { return $m } } } { param($l) [bool](Find-Text $l '这次怎么读') } '点了「开始学」但开始学的弹窗一直没开' -tries 8 -pauseMs 2000
        Click-Until 5 { param($l) Find-DialogPath $l '边看边学' } { param($l) [bool](Find-Text $l '开始边看边学') } '点了「边看边学」但底按钮没换成「开始边看边学」' -tries 8 -pauseMs 2000
        Click-Until 5 { param($l) Find-Text $l '开始边看边学' } { param($l) -not (Find-Text $l '这次怎么读') } '点了「开始边看边学」但弹窗没关' -tries 8 -pauseMs 2000
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
        # 点条目后那一下「收起目录」会被切节的加载吞掉（11-21 01:51 L1 就是这样：抽屉一直盖在右栏上，
        # 「本节原文 · 第 N 页」被挡住连 OCR 都读不到）。所以这里一直点到这一帧里真的看见标签或抽屉不在为止。
        $shut = (Get-Date).AddSeconds(20)
        while ((Get-Date) -lt $shut) {
            $sectionLines = Shot 5 'section'
            $shutBtn = Has $sectionLines @('收起目录')
            # 抽屉还开着时右栏被盖住，抽屉头里的「本节原文」不算数：先真的收起目录，再读右栏的标签。
            if (-not $shutBtn -and ($sectionLines | Where-Object { $_.Text -match '本节原文' })) { break }
            if (-not $shutBtn) { Start-Sleep -Milliseconds 800; continue }
            Click-At $shutBtn.X $shutBtn.Y; Start-Sleep -Seconds 1
        }
        $label = $sectionLines | Where-Object { $_.Text -match '本节原文' } | Select-Object -First 1
        $labelPage = if ($label) { [regex]::Match($label.Text, '第\s*(\d+)\s*页').Groups[1].Value } else { '' }
        if (-not $labelPage) { Fail 5 '选中节后界面没有「本节原文 · 第 N 页」标签，没有发请求' }
        if ([int]$labelPage -ne $LessonPage) { Fail 5 "选中的第 $LessonNumber 条落在第 $labelPage 页，不是授权页第 $LessonPage 页，没有发请求（见 5-section.png）" }
        $deadline = (Get-Date).AddSeconds(150); $res = $null; $sawBody = $false
        $prevFrame = ''; $still = 0; $scrolls = 0
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
                # 但滚完必须核对这一帧真的变了。11-21 02:21 四路并行里同一点同方向的滚轮连发 11 次、
                # 学习页一次没动（见 5-learn.png），150 秒全烧在这上面，最后只会报超时，看不出是驱动没滚动。
                # 所以给次数上限：前四次在前半页中心滚，还不动就换低一点的落点，七次仍不动直接判失败。
                $frame = (@($lines | ForEach-Object { $_.Text }) -join '|')
                if ($frame -eq $prevFrame) { $still++ } else { $still = 0 }
                $prevFrame = $frame
                $scrolls++
                if ($scrolls -gt 7) {
                    Fail 5 "讲解正文已出，但滚轮滚了 $scrolls 次、连续 $still 帧画面没变，出处行一直在折叠线以下（见 5-learn.png）"
                }
                $sp = if ($still -ge 3) { Scroll-Point 0.45 0.62 } else { Scroll-Point 0.6 0.6 }
                Scroll-At $sp[0] $sp[1] -3
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
        # 第⑤步收尾点出处后停在原文页，顶栏是「返回学习」而不是「返回书架」：先退回做题学再回书架。
        if (Has (Shot 6 'leave') @('返回学习')) {
            Click-Label 6 @('返回学习') '无法从本节原文返回做题学页面'
        }
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
                if (Await-Text 6 "range-$tag" @('对应教材第') 25) {
                    $r = Get-LessonSourceRange $script:Last
                    if ($r) { break }
                    # 原文页只闪一下就退回做题学、那一帧没读全页段：再开一次，不拿空页段往下走。
                    continue
                }
                $actAfter = Resolve-SourcePageRangeAction (Get-LessonLearningState $script:Last $script:LastPng)
                if ($actAfter.Action -eq 'return_range') { $r = $actAfter.Range; break }
                if ($actAfter.Action -eq 'wait_range_fade') { $r = & $waitRangeFade "$tag-after-$try"; if ($r) { break } }
            }
            $ret = Find-Text $script:Last '返回学习'
            if (-not $ret) { $ret = Await-Text 6 'find' @('返回学习') 8 }
            if (-not $r) {
                $r = Get-LessonSourceRange $script:Last
                if (-not $r) { $r = & $waitRangeFade "late-$tag" }
            }
            if (-not $ret) {
                # 原文页读完页段后有时会自己退回做题学（切节后立刻再开一次最容易碰上）：页段已经拿到、
                # 屏幕上已经是做题学，就算回到了原处，不必再点「返回学习」。
                if ($r) { return $r }
                Fail 6 '本节原文页没有「返回学习」，页段也没读到（见 6-find.png）'
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
        # 1.1.1 起学习页标签写作「题型：选择题」（#547/TON-1733），旧版是「出题：选择」；两种都认。
        Click-Label 6 @('题型：', '题型:', '出题：', '出题:') '找不到题型设置'
        $settings = Await-Text 6 'settings' @('出题方式') 15
        if (-not $settings) { Fail 6 '出题方式对话框没有打开' }
        $ctrl = Get-QuestionSettingsControls $script:Last
        if (-not $ctrl -or -not $ctrl.Choice) { Fail 6 '出题方式对话框没有选择题选项' }
        Click-At $ctrl.Choice.X $ctrl.Choice.Y; Start-Sleep -Milliseconds 800
        # 点完「选择」触发 mode() 落盘期间 ui.busy=true 会暂时置灰「确定」；轮询等对话框内「确定」恢复可认再点。
        $confirmDeadline = (Get-Date).AddSeconds(15); $ctrlConfirm = $null
        while ((Get-Date) -lt $confirmDeadline) {
            $confirmLines = Shot 6 'settings-confirm'
            $cand = Get-QuestionSettingsControls $confirmLines
            if (-not $cand -or -not $cand.Title) { $ctrlConfirm = $null; break }
            if ($cand.Confirm -and $cand.Confirm.Y -gt $ctrl.Choice.Y -and (-not $cand.Choice -or $cand.Confirm.Y -gt $cand.Choice.Y)) {
                $ctrlConfirm = $cand; break
            }
            Start-Sleep -Milliseconds 500
        }
        if (-not $ctrlConfirm) { Fail 6 '出题方式对话框找不到「确定」按钮' }
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
        $deadline = (Get-Date).AddSeconds(120); $question = $false; $picked = $null; $sawQuestion = $false; $optAnchorX1 = 0; $qScroll = 0
        while ((Get-Date) -lt $deadline -and -not $question) {
            $lines = Shot 6 'question'
            $allow = Has $lines @('允许并出题')
            if ($allow) { Click-At $allow.X $allow.Y; Start-Sleep -Seconds 2; continue }
            if (Has $lines @('格式完整', '出题暂时读不到', '没有足够的正文', '需要连上', '尚未核实', '额度', '重新配对', '暂时无法确认')) {
                Fail 6 '本节出题被拒绝或不可用，见 winbox 私有截图 6-question.png'
            }
            $anchor = Has $lines @('查看对应原文')
            if ((Has $lines @('本次练习')) -and $anchor) { $sawQuestion = $true; $optAnchorX1 = [int]$anchor.X1 }
            if ($sawQuestion) {
                # 只在题目原文按钮下找选项：优先认单字母 A–D，深色 800x600 OCR 整列漏读字母时兜底点右侧缩进的选项正文。
                $minOptY = if ($anchor) { $anchor.Y + 10 } else { 180 }
                $picked = Find-PracticeOptionClick $lines $optAnchorX1 $minOptY
                if ($picked) { $question = $true }
            }
            if (-not $question) {
                if ($sawQuestion) {
                    $sp = Scroll-Point 0.7 0.6
                    $dir = if (($qScroll % 10) -lt 5) { -2 } else { 2 }
                    Scroll-At $sp[0] $sp[1] $dir; $qScroll++
                    Start-Sleep -Seconds 1
                } else {
                    Start-Sleep -Seconds 3
                }
            }
        }
        if (-not $question) { Fail 6 '120 秒内没有出现可作答的本次练习选择题' }
        Note "步骤6 选定选项（$($picked.Mode)）：($($picked.X),$($picked.Y))"
        Click-At $picked.X $picked.Y
        Start-Sleep -Seconds 2
        $selected = $false; $explained = $false; $cited = $false; $summary = $false; $downScrolls = 0
        foreach ($view in 0..14) {
            $lines = Shot 6 "answered-$view"
            if (Has $lines @('你选择：', '你选择:')) { $selected = $true }
            if (Has $lines @('答后讲解')) { $explained = $true }
            if (Has $lines @('出处')) { $cited = $true }
            if (Has $lines @('题目和作答只保留在这次练习中', '要回看时再打开', '小结')) { $summary = $true }
            if ($selected -and $explained -and $cited -and $summary) { break }
            $sp = Scroll-Point 0.7 0.6
            if (-not ($explained -and $cited -and $summary) -and $downScrolls -lt 10) { $dir = -2; $downScrolls++ } else { $dir = 2 }
            Scroll-At $sp[0] $sp[1] $dir; Start-Sleep -Seconds 1
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
