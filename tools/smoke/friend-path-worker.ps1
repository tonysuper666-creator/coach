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
    [string]$InstalledExe = '',
    [string]$BookTitle = '',
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
if ($InstalledExe -and ($Runtime -or $Background)) { throw '-InstalledExe requires the foreground installed client' }
if (-not $BookTitle) { $BookTitle = [IO.Path]::GetFileNameWithoutExtension($Book) }
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
function Get-HomeImportLabel($lines) {
    if (Find-Exact $lines '放进一本书') { return '放进一本书' }
    if (Has $lines @('导入文件', '单读一本书', '为一门课备考', '上次停在', '读到第', '我的库', '云端书架')) { return '导入文件' }
}

function Find-FirstTasteTry($lines) {
    $anchor = Has $lines @('想先试一道开胃题吗', '不再提示')
    if ($anchor) {
        $onRow = Find-OnRow $lines '来一道' $anchor 24
        if ($onRow) { return $onRow }
        $near = Find-Text $lines '来一道'
        if ($near -and [Math]::Abs($near.Y - $anchor.Y) -le 48) { return $near }
    }
    return (Find-Exact $lines '来一道')
}

function Get-TasteCardBox($lines, [bool]$isCompact = $false) {
    $r = Get-Rect
    $winW = [int]($r.R - $r.L); $winH = [int]($r.B - $r.T)
    if ($winW -le 0 -or $winH -le 0) { return $null }
    $scale = 1.0
    try { $dpi = [WD]::GetDpiForWindow($script:Win); if ($dpi -gt 0) { $scale = [Math]::Max(1.0, $dpi / 96.0) } } catch { }
    $padEnd = [int][Math]::Round(16 * $scale)
    $padBottom = [int][Math]::Round(56 * $scale)
    $cardW = [Math]::Min($winW, [int][Math]::Round(460 * $scale))
    $cardH = [Math]::Min([Math]::Max(1, $winH - $padBottom), [int][Math]::Round(460 * $scale))
    $margin = [int][Math]::Round(12 * $scale)
    $x2 = [Math]::Min($winW, $winW - $padEnd + $margin)
    $x1 = [Math]::Max(0, $winW - $padEnd - $cardW - $margin)
    $y2 = [Math]::Min($winH, $winH - $padBottom + $margin)
    $y1 = [Math]::Max(0, $winH - $padBottom - $cardH - $margin)
    if (-not $isCompact) {
        if ($winW -lt [int](520 * $scale)) { return $null }
        $compactHit = $false
        foreach ($needle in @('想先试一道开胃题吗', '不再提示', '允许这本书讲解', '还没开启讲解许可', '选一个答案', '看讲解', '开始读这本书')) {
            $m = Find-Text $lines $needle
            if ($m -and $m.X -ge [Math]::Max($x1, [int]($winW * 0.52)) -and $m.X -le $x2 -and $m.Y -ge $y1 -and $m.Y -le $y2) {
                $compactHit = $true; break
            }
        }
        if (-not $compactHit) { return $null }
    }
    [pscustomobject]@{ X1 = $x1; X2 = $x2; Y1 = $y1; Y2 = $y2 }
}

function Select-TasteCardLines($lines, $cardBox) {
    if (-not $cardBox) { return @($lines) }
    return @($lines | Where-Object {
        $b = Get-LineBox $_
        $cx = [int](($b.X1 + $b.X2) / 2)
        $b.X2 -ge $b.X1 -and $cx -ge $cardBox.X1 -and $cx -le $cardBox.X2 -and $b.Y -ge $cardBox.Y1 -and $b.Y -le $cardBox.Y2
    })
}

function Find-FirstTasteAllow($lines) {
    foreach ($s in '允许这本书讲解', '允许这本书') {
        $m = Find-Exact $lines $s
        if ($m) { return $m }
        $m = Find-Text $lines $s
        if ($m) { return $m }
    }
    $hint = Has $lines @('还没开启讲解许可', '开启讲解许可')
    if (-not $hint) { return $null }
    $best = $null
    foreach ($l in $lines) {
        $t = $l.Text -replace '\s', ''
        if ($t -notmatch '允许' -or $t -match '不允许|还没允许|允许后|许可') { continue }
        $b = Get-LineBox $l
        $y = [int](($b.Y1 + $b.Y2) / 2)
        if ($y -le $hint.Y) { continue }
        if (-not $best -or $y -lt $best.Y) {
            $best = [pscustomobject]@{ X = [int](($b.X1 + $b.X2) / 2); Y = $y; X1 = [int]$b.X1; X2 = [int]$b.X2; Y1 = [int]$b.Y1; Y2 = [int]$b.Y2 }
        }
    }
    return $best
}

function Get-TasteCardScrollPoint($lines, $cardBox) {
    if (-not $cardBox) { return (Scroll-Point 0.5 0.7) }
    $sx = [int](($cardBox.X1 + $cardBox.X2) / 2)
    $minY = $null; $maxY = $null
    foreach ($l in $lines) {
        $b = Get-LineBox $l
        if ($b.X2 -lt $b.X1) { continue }
        if ($null -eq $minY -or $b.Y1 -lt $minY) { $minY = $b.Y1 }
        if ($null -eq $maxY -or $b.Y2 -gt $maxY) { $maxY = $b.Y2 }
    }
    $topY = if ($null -ne $minY) { [Math]::Max($cardBox.Y1 + 24, $minY) } else { $cardBox.Y1 + 24 }
    $botY = if ($null -ne $maxY) { [Math]::Min($cardBox.Y2 - 24, [Math]::Max($maxY, $cardBox.Y2 - 48)) } else { $cardBox.Y2 - 24 }
    $sy = if ($botY -lt $topY) { [int](($cardBox.Y1 + $cardBox.Y2) / 2) } else { [int](($topY + $botY) / 2) }
    @($sx, $sy)
}

function Find-TasteOption($lines, $submit, $cardBox = $null) {
    $scoped = if ($cardBox) {
        @($lines | Where-Object {
            $b = Get-LineBox $_
            $cx = [int](($b.X1 + $b.X2) / 2)
            $b.X2 -ge $b.X1 -and $cx -ge $cardBox.X1 -and $cx -le $cardBox.X2 -and $b.Y -ge $cardBox.Y1 -and $b.Y -le $cardBox.Y2
        })
    } else { $lines }
    $hint = Find-Text $scoped '选一个答案'
    $lastRow = $null
    foreach ($line in $scoped) {
        $box = Get-LineBox $line
        if ($hint -and $box.Y1 -le $hint.Y2) { continue }
        if ($submit -and $box.Y2 -ge $submit.Y1) { continue }
        $text = $line.Text.Trim()
        if ($text -match '^[○◯〇●]\s*\S') { return $box }

        # 小字号下 OCR 会漏掉空心圆。在首题卡里，提交钮正上方最后一行仍在可点的选项内。
        if ($submit -and $text -match '[\p{L}\p{N}]' -and $box.Y1 -gt 40 -and
            (-not $lastRow -or $box.Y -gt $lastRow.Y)) { $lastRow = $box }
    }
    return $lastRow
}

function Find-TasteVerdict($lines) {
    foreach ($line in $lines) {
        if (($line.Text -replace '\s', '') -match '^(对了|这题选的是另一项)[。.!！]?$') { return (Get-LineBox $line) }
    }
}

function Test-TasteExplanation($lines, $verdict, $button, $cardBox = $null) {
    if (-not $verdict) { return $false }
    foreach ($line in $lines) {
        $box = Get-LineBox $line
        if ($cardBox) {
            $cx = [int](($box.X1 + $box.X2) / 2)
            if ($box.X2 -lt $box.X1 -or $cx -lt $cardBox.X1 -or $cx -gt $cardBox.X2 -or $box.Y -lt $cardBox.Y1 -or $box.Y -gt $cardBox.Y2) { continue }
        }
        if ($box.Y1 -le $verdict.Y2 -or ($button -and $box.Y2 -ge $button.Y1)) { continue }
        $rawText = ([string]$line.Text).Trim()
        $text = $rawText -replace '\s', ''
        if ($text -match '[\p{L}]' -and -not (Test-CoachWindowTitle $rawText) -and
            $text -notmatch '^(设置|LearningCoach(?:\d+(?:\.\d+)+(?:-[0-9A-Za-z.-]+)?|dev)?|开始读这本书)$') { return $true }
    }
    return $false
}

function Complete-FirstTaste {
    # 选书框关闭后首页可能仍在显示离线说明；说明正文里的「再试一次」不是重试按钮。
    $deadline = (Get-Date).AddSeconds(240)
    $tag = 'taste-after-picker'
    $isCompact = $false
    $cardBox = $null
    do {
        $lines = Shot 2 $tag
        $tryStrip = Find-FirstTasteTry $lines
        if ($tryStrip) {
            $isCompact = $true
            $cardBox = Get-TasteCardBox $lines $true
            Click-At $tryStrip.X $tryStrip.Y
            Start-Sleep -Milliseconds 700
            $tag = 'taste-question'
            continue
        }
        $cardBox = Get-TasteCardBox $lines $isCompact
        if ($cardBox) { $isCompact = $true }
        $cardLines = Select-TasteCardLines $lines $cardBox
        $allowTaste = Find-FirstTasteAllow $cardLines
        if ($allowTaste) {
            Click-At $allowTaste.X $allowTaste.Y
            Start-Sleep -Milliseconds 800
            $tag = 'taste-question'
            continue
        }
        if (-not (Has $cardLines @('还没开启讲解许可', '开启讲解许可'))) {
            foreach ($action in '再放一次', '再试一次', '先翻开这本书') {
                if (Find-Exact $cardLines $action) { Fail 2 "未进入可作答的首题，出现「$action」按钮，见 2-$tag.png" }
            }
        }
        if ((Find-Text $cardLines '选一个答案') -or (Find-Exact $cardLines '看讲解')) { break }
        if ((Get-Date) -ge $deadline) {
            if (Find-Exact $lines '放进一本书') { Fail 2 "选书后 240 秒仍在空书架，见 2-$tag.png" }
            Fail 2 "选书后 240 秒未进入可作答的首题，见 2-$tag.png"
        }
        Start-Sleep -Seconds 2
        $tag = 'taste-question'
    } while ($true)
    $option = $null
    foreach ($attempt in 1..16) {
        $lines = Shot 2 "taste-options$attempt"
        $cardBox = Get-TasteCardBox $lines $isCompact
        if ($cardBox) { $isCompact = $true }
        $cardLines = Select-TasteCardLines $lines $cardBox
        $submit = Find-Exact $cardLines '看讲解'
        $option = Find-TasteOption $cardLines $submit $cardBox
        if ($option) { break }
        $point = Get-TasteCardScrollPoint $cardLines $cardBox
        Scroll-At $point[0] $point[1] -3
        Start-Sleep -Milliseconds 500
    }
    if (-not $option) { Fail 2 '未识别到首题选项，见 2-taste-options*.png' }
    Click-At ([int](($option.X1 + $option.X2) / 2)) $option.Y
    foreach ($attempt in 1..16) {
        Start-Sleep -Milliseconds 500
        $lines = Shot 2 "taste-submit$attempt"
        $cardBox = Get-TasteCardBox $lines $isCompact
        if ($cardBox) { $isCompact = $true }
        $cardLines = Select-TasteCardLines $lines $cardBox
        $submit = Find-Exact $cardLines '看讲解'
        if ($submit) { break }
        $point = Get-TasteCardScrollPoint $cardLines $cardBox
        Scroll-At $point[0] $point[1] -3
    }
    if (-not $submit) { Fail 2 '选答后找不到「看讲解」，见 2-taste-submit*.png' }
    Click-At $submit.X $submit.Y
    Start-Sleep -Milliseconds 700

    # 提交时视口可能停在题卡底部。先找回判题结果，再沿正文向下找继续钮。
    $sawExplanation = $false
    foreach ($attempt in 1..20) {
        $lines = Shot 2 "taste-explanation$attempt"
        $cardBox = Get-TasteCardBox $lines $isCompact
        if ($cardBox) { $isCompact = $true }
        $cardLines = Select-TasteCardLines $lines $cardBox
        $verdict = Find-TasteVerdict $cardLines
        $button = Find-Exact $cardLines '开始读这本书'
        if (Test-TasteExplanation $cardLines $verdict $button $cardBox) { $sawExplanation = $true }
        if ($sawExplanation -and $button) {
            Click-At $button.X $button.Y
            return
        }
        $point = Get-TasteCardScrollPoint $cardLines $cardBox
        if ($sawExplanation -or $verdict) { Scroll-At $point[0] $point[1] -2 }
        else { Scroll-At $point[0] $point[1] 4 }
        Start-Sleep -Milliseconds 500
    }
    Fail 2 '未同时验证判题结果、讲解正文和「开始读这本书」，见 2-taste-explanation*.png'
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
# 比较输入框区域前后两张截图有多少像素发生了明显变化：不依赖深浅主题，也不怕占位提示语本身占像素。
function Get-FieldDiff([string]$pngA, [string]$pngB, [int]$x0, [int]$y0, [int]$x1, [int]$y1) {
    if (-not $pngA -or -not $pngB -or -not (Test-Path -LiteralPath $pngA) -or -not (Test-Path -LiteralPath $pngB)) { return 0 }
    $a = [System.Drawing.Bitmap]::FromFile($pngA)
    $b = [System.Drawing.Bitmap]::FromFile($pngB)
    try {
        $lx0 = [Math]::Max(0, $x0); $ly0 = [Math]::Max(0, $y0)
        $lx1 = [Math]::Min([Math]::Min($a.Width, $b.Width) - 2, $x1)
        $ly1 = [Math]::Min([Math]::Min($a.Height, $b.Height) - 2, $y1)
        if ($lx1 -le $lx0 -or $ly1 -le $ly0) { return 0 }
        $diff = 0
        for ($y = $ly0; $y -le $ly1; $y++) {
            for ($x = $lx0; $x -le $lx1; $x++) {
                $ca = $a.GetPixel($x, $y); $cb = $b.GetPixel($x, $y)
                if ([Math]::Abs([int]$ca.R - [int]$cb.R) + [Math]::Abs([int]$ca.G - [int]$cb.G) + [Math]::Abs([int]$ca.B - [int]$cb.B) -gt 45) { $diff++ }
            }
        }
        return $diff
    } finally { $a.Dispose(); $b.Dispose() }
}
# 「继续追问」框里的字太小，OCR 读不出来；粘完要靠框内区域的亮像素数确认「框里确实有字」。
# 空框只有深色底和一圈深色边框，加了字会多出成百上千个亮像素，和空框那张比一下就有数了。
function Get-FieldInk([string]$png, $label, [int]$rowY = 0) {
    $bmp = [System.Drawing.Bitmap]::FromFile($png)
    try {
        $x0 = [Math]::Max(0, [int]$label.X1 + 2)
        $y0 = if ($rowY -gt 0) { [Math]::Max(0, $rowY - 22) } else { [Math]::Max(0, [int]$label.Y2 + 10) }
        $x1 = [Math]::Min($x0 + 420, $bmp.Width - 2)
        $y1 = if ($rowY -gt 0) { [Math]::Min($rowY + 22, $bmp.Height - 2) } else { [Math]::Min($y0 + 42, $bmp.Height - 2) }
        $bg = $bmp.GetPixel($x0, $y0)
        $bgLum = [int](($bg.R * 299 + $bg.G * 587 + $bg.B * 114) / 1000)
        $ink = 0
        for ($y = $y0; $y -le $y1; $y++) {
            for ($x = $x0; $x -le $x1; $x++) {
                $c = $bmp.GetPixel($x, $y)
                $lum = [int](($c.R * 299 + $c.G * 587 + $c.B * 114) / 1000)
                if ([Math]::Abs($lum - $bgLum) -gt 45) { $ink++ }
            }
        }
        return $ink
    } finally { $bmp.Dispose() }
}
# 一条横带的粗略像素指纹（隔 3 个像素取一点、亮度分 8 档）：提问页签回答区只有 30 多 px 高，
# 同一行字能在视野里停好几步，靠 OCR 文本相同判断「滚到顶了」会误判，这里直接比像素。
function Get-BandSig([string]$png, [int]$y0, [int]$y1, [int]$x0 = 20, [int]$x1 = -1) {
    if (-not $png -or -not (Test-Path -LiteralPath $png)) { return '' }
    $bmp = [System.Drawing.Bitmap]::FromFile($png)
    try {
        $y0 = [Math]::Max(0, $y0); $y1 = [Math]::Min($bmp.Height - 1, $y1)
        $lx0 = [Math]::Max(0, $x0)
        $lx1 = if ($x1 -gt 0) { [Math]::Min($bmp.Width - 1, $x1) } else { $bmp.Width - 20 }
        $sb = New-Object System.Text.StringBuilder
        for ($y = $y0; $y -le $y1; $y += 3) {
            for ($x = $lx0; $x -lt $lx1; $x += 3) {
                $c = $bmp.GetPixel($x, $y)
                [void]$sb.Append([char](48 + [int](($c.R + $c.G + $c.B) / 96)))
            }
        }
        return $sb.ToString()
    } finally { $bmp.Dispose() }
}
# 真滚到底：一格滚轮在提问回答区只走几 px，-15 格常常只走一百来 px、离底还远（11-23 13:51 那轮
# 九遍都从中段开扫，最底下的「正在整理…」/新一问一次没露过面）。所以一直往下滚，直到那条横带
# 连着两次不再变，才算到底；只截图比像素，不跑 OCR。
function Scroll-AskBottom([int]$x, [int]$y) {
    $probe = Join-Path $OutDir '4-ask-bottom-probe.png'
    $prev = $null; $same = 0
    $bx0 = [Math]::Max(20, $x - 160); $bx1 = $x + 160
    for ($k = 0; $k -lt 30 -and $same -lt 2; $k++) {
        Scroll-At $x $y -15; Start-Sleep -Milliseconds 250
        Save-Shot $probe
        $sig = Get-BandSig $probe ($y - 12) ($y + 12) $bx0 $bx1
        if ($sig -eq $prev) { $same++ } else { $same = 0 }
        $prev = $sig
    }
}
# 伴读抽屉体（讲解页签下面的模块内容）滚一下，并按像素核对真的动了。
# 宽屏（>=1200px，如 Apollo 2160x1350）下伴读抽屉在右侧栏（约 0.72w..w），只滚右栏、只比右栏像素，
# 绝不拿 0.35w 去滚左侧书页（否则会把书页滚走几十页）。窄屏仍按底抽屉落点。
function Scroll-DrawerBody([int]$notches, [int]$winW, [int]$winH) {
    $probe = Join-Path $OutDir 'drawer-scroll-probe.png'
    $isWide = ($winW -ge 1200)
    $y0 = if ($isWide) { [int]($winH * 0.35) } else { [int]($winH * 0.68) }
    $y1 = if ($isWide) { [int]($winH * 0.88) } else { [int]($winH * 0.94) }
    $bx0 = if ($isWide) { [int]($winW * 0.74) } else { 20 }
    $bx1 = [int]($winW - 20)
    Save-Shot $probe
    $before = Get-BandSig $probe $y0 $y1 $bx0 $bx1
    $spots = if ($isWide) {
        @(
            @(([int]($winW * 0.85)), ([int]($winH * 0.62)), $false),
            @(([int]($winW * 0.85)), ([int]($winH * 0.62)), $true),
            @(([int]($winW * 0.90)), ([int]($winH * 0.72)), $true),
            @(([int]($winW * 0.78)), ([int]($winH * 0.55)), $true)
        )
    } else {
        @(
            @(([int]($winW * 0.65)), ([int]($winH * 0.72)), $false),
            @(([int]($winW * 0.65)), ([int]($winH * 0.72)), $true),
            @(([int]($winW * 0.35)), ([int]($winH * 0.78)), $true),
            @(([int]($winW * 0.90)), ([int]($winH * 0.78)), $true)
        )
    }
    for ($i = 0; $i -lt $spots.Count; $i++) {
        $sx = $spots[$i][0]; $sy = $spots[$i][1]
        if ($spots[$i][2]) { Hover $sx $sy; Start-Sleep -Milliseconds 400 }
        Scroll-At $sx $sy $notches; Start-Sleep -Milliseconds 500
        Save-Shot $probe
        if ((Get-BandSig $probe $y0 $y1 $bx0 $bx1) -ne $before) {
            if ($i -gt 0) { Note "抽屉体换第 $($i + 1) 个落点 ($sx,$sy) 才滚动" }
            return $true
        }
    }
    return $false
}
# #599 起「讲解」页签默认只讲当前这一页：讲解卡下面一条条「出处 N · 第 X 页 · 第 Y 段 · 开头几个字」，
# 点了回到那一段。OCR 常把中间的「·」读成「．」或吞掉，高分屏也常把「页」认成「贞 / 虫」。
function Find-PageCite($lines) {
    return ($lines | Where-Object { ($_.Text -replace '\s', '') -match '^出处\d.*第\d+[页頁贞貞虫]' } | Select-Object -First 1)
}
function Find-All($lines, [string]$needle) { $all = @(); foreach ($l in $lines) { $m = Find-Text @($l) $needle; if ($m) { $all += $m } }; $all | Sort-Object Y }
# 2.0（#570）的阅读器是单层满铺画布：没有常驻底栏，指针贴到窗口下缘 48dp 内才浮出来；
# 窄窗（读者窗口 752dp < 760dp）下两个翻页键只剩「‹」「›」，认字认不到，页号写成「3 / 620 · 0%」。
# 所以进没进阅读器、翻没翻页，都只看窗口最底下那一条能不能读出页号。
function Get-HudCounter([string]$png) {
    if (-not $png -or -not (Test-Path -LiteralPath $png)) { return $null }
    $src = [Drawing.Bitmap]::new($png)
    $file = Join-Path (Split-Path -Parent $png) ('hud-' + [guid]::NewGuid().ToString('N').Substring(0, 8) + '.png')
    try {
        $top = [int][Math]::Max(0, $src.Height - 64)
        $h = $src.Height - $top
        if ($h -lt 8) { return $null }
        $rect = [Drawing.Rectangle]::new(0, $top, $src.Width, $h)
        # 这个字号 4 倍会把「第154页/620」的「54」吞掉，认成「第1页/620」（11-21 10:43 那轮就是这么把 154 读成 1 的）；
        # 6 倍又常把这一行拆成「第154」「页」「/620」三段，逐行匹配一条都对不上。5 倍一般整行读全，
        # 所以先试 5 倍，再把各行的数字和斜杠拼起来兜底。
        # 高分屏（如 Apollo 2160x1350）直接乘 5 会超过 OcrEngine::MaxImageDimension(10000)，按上限夹紧倍率。
        $scales = @(@(5, 6, 4, 2) | ForEach-Object { Get-SafeOcrScale $src.Width $h $_ } | Select-Object -Unique)
        foreach ($scale in $scales) {
            $zoom = [Drawing.Bitmap]::new($src.Width * $scale, $h * $scale)
            try {
                $g = [Drawing.Graphics]::FromImage($zoom)
                try {
                    $g.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                    $g.DrawImage($src, [Drawing.Rectangle]::new(0, 0, $zoom.Width, $zoom.Height), $rect, [Drawing.GraphicsUnit]::Pixel)
                } finally { $g.Dispose() }
                $zoom.Save($file, [Drawing.Imaging.ImageFormat]::Png)
                # 2.0 底栏页号是「第 7 页 / 180 · 3%」（ImmersiveReaderChrome 的 label + percent）。
                # 先按行认：一帧里常常同时有一行完整的「第32页/620」和一行孤零零的百分比「5」，
                # 一行一行认就不会把那颗 5 粘到总页数后面（5 倍那轮曾拼成 32/6205）。
                $digits = @()
                foreach ($line in Read-Screen $file) {
                    $x = Convert-OcrDigits ($line.Text -replace '\s', '')
                    if ($x -notmatch '\d') { continue }
                    if ($x -match '(\d{1,4})(?!\d)[^\d/]{0,3}/(\d{1,4})(?!\d)') {
                        $cur = [int]$Matches[1]; $total = [int]$Matches[2]
                        # 总页数常常被 OCR 认花（620 认成「52包」），它只用来拼提示语，不参与判断；
                        # 当前页那一位是左边这串，认到就够。
                        if ($cur -ge 1) { return [pscustomobject]@{ Cur = $cur; Total = $total; Text = "$cur/$total" } }
                    }
                    $digits += $x
                }
                # 6 倍常把这一行拆成「第154」「页」「/620」三段，逐行一条都对不上：拼回去再认一次。
                # 拼起来百分比那位数字会粘在总页数后面（620 变 6205），拼之前先把「·3%」这一截切掉。
                $hud = ($digits -join '') -replace '[·・:：]?\d{1,2}[%％].*$', ''
                $mm = [regex]::Match($hud, '(\d{1,4})(?!\d)[^\d/]{0,3}/(\d{1,4})(?!\d)')
                if ($mm.Success) {
                    $cur = [int]$mm.Groups[1].Value; $total = [int]$mm.Groups[2].Value
                    if ($cur -ge 1) { return [pscustomobject]@{ Cur = $cur; Total = $total; Text = "$cur/$total" } }
                }
            } finally { $zoom.Dispose() }
        }
        return $null
    } finally {
        $src.Dispose()
        Remove-Item -LiteralPath $file -ErrorAction SilentlyContinue
    }
}
# 只挪指针不按键：底栏是「指针进到下缘」才浮出来的，点下去可能正落在滑杆上把页拖走。
# 阅读器只认 PointerEventType.Move 才浮 HUD（TextbookReader 的 awaitPointerEvent 那段）；
# 指针本来就停在目标点上时，SetCursorPos 到同一点不再产生 Move——导入结束书架换成阅读器那一刻
# 指针恰好压在下缘，就会出现「怎么等都不出底栏」的假死（11-21 12:18 那轮就这样）。
# 先挪开十六像素再落回，保证这一下真是一次移动。
function Hover([int]$x, [int]$y) {
    $r = Get-Rect
    [WD]::SetCursorPos($r.L + $x, $r.T + [Math]::Max(4, $y - 16)) | Out-Null
    Start-Sleep -Milliseconds 80
    [WD]::SetCursorPos($r.L + $x, $r.T + $y) | Out-Null
}
# 阅读器的顶栏（← 书架 / 目录 / … / 伴读）默认是隐的：指针移到顶边、点一下空白或按 Tab 才浮出来，
# 指针静置几秒又自己收回去（14:48 那轮第 6 步拍到的就是一帧只剩书页的窗口，导航全靠顶栏那几颗字）。
# 要顶栏上的按钮，先沿着顶边挪两下把它叫回来，认到「书架」或「目录」再把这一帧交出去。
function Show-ReaderTop([int]$n, [string]$tag, [int]$Tries = 6) {
    $r = Get-Rect
    $w = [int]($r.R - $r.L); $h = [int]($r.B - $r.T)
    $x = [int]($w / 2)
    $baseY = 36
    for ($k = 0; $k -lt $Tries; $k++) {
        Hover $x ($baseY + 8 * $k)
        Start-Sleep -Milliseconds 500
        $lines = Shot $n "$tag-top$k"
        if (Find-Step2ReaderBack $lines $w $h) { return $lines }
        $toc = Find-Text $lines '目录'
        if ($toc -and $toc.X -le ($w / 2) -and $toc.Y -le 280 -and ((Find-OnRow $lines '伴读' $toc 22) -or (Find-OnRow $lines '收起' $toc 22))) {
            return $lines
        }
        foreach ($needle in @('正在读取教材', '正在准备教材', '取消导入')) {
            $b = Find-Text $lines $needle
            if ($b -and $b.Y2 -ge $baseY -and $b.Y2 -lt 220) {
                $baseY = [Math]::Max($baseY, [int]($b.Y2 + 16))
                break
            }
        }
    }
    $null
}
function Find-Step2ReaderBack($lines, [int]$winW, [int]$winH = 0) {
    if (-not $lines) { return $null }
    $scale = 1.0
    try { $dpi = [WD]::GetDpiForWindow($script:Win); if ($dpi -gt 0) { $scale = [Math]::Max(1.0, $dpi / 96.0) } } catch { }
    $banner = $null
    $bannerMaxY = [int][Math]::Round(180 * $scale)
    foreach ($needle in @('正在读取教材', '正在准备教材', '取消导入')) {
        $b = Find-Text $lines $needle
        if ($b -and $b.Y -le $bannerMaxY) { $banner = $b; break }
    }
    $baseMaxY = [int][Math]::Round(150 * $scale)
    $maxY = if ($banner) {
        [Math]::Max($baseMaxY, [Math]::Min([int]($banner.Y2 + [Math]::Round(120 * $scale)), [int][Math]::Round(280 * $scale)))
    } else {
        $baseMaxY
    }
    if ($winH -gt 0) { $maxY = [Math]::Min($maxY, [int]($winH * 0.40)) }
    $cands = @()
    foreach ($l in $lines) {
        $m = Find-Text @($l) '书架'
        if (-not $m) { continue }
        if ($m.X -gt ($winW / 3)) { continue }
        if ($m.Y -gt $maxY) { continue }
        if ($banner -and $m.Y -le $banner.Y1) { continue }
        $compact = ($l.Text -replace '\s', '')
        $isToolbarLabel = ($compact -match '^(?:[←<+\-·•]*)?书架$') -or ($compact -match '书架.*(?:目录|伴读|收起)')
        if (-not $isToolbarLabel) { continue }
        $cands += $m
    }
    if (-not $cands.Count) { return $null }
    foreach ($c in ($cands | Sort-Object Y)) {
        foreach ($peer in @('目录', '伴读', '收起')) {
            $hit = Find-OnRow $lines $peer $c 22
            if ($hit -and $hit.X -gt $c.X) { return $c }
        }
    }
    return $null
}
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
# 但「找不到」也要分两种：按钮真的没了（页面变了）就停手；按钮还在、只是这十几秒被
# ui.busy 弄灰、标签被 OCR 读成「始边边下」这种，就不能当场 break——$stuckSec > 0 时
# 隔一拍重拍，等文字恢复或弹窗自己关掉（11-21 06:2x 三路就是这样只点了 1 次就判死）。
function Click-Until([int]$n, [scriptblock]$Find, [scriptblock]$Done, [string]$why, [int]$sec = 15, [int]$tries = 4, [int]$pauseMs = 0, [int]$stuckSec = 0, [scriptblock]$StallAction = $null) {
    if (& $Done (Shot $n 'entry-check')) { return }
    $m = $null; $deadline = (Get-Date).AddSeconds($sec)
    do {
        $m = & $Find (Shot $n 'entry-find')
        if (-not $m) { Start-Sleep -Seconds 2 }
    } while (-not $m -and (Get-Date) -lt $deadline)
    if (-not $m) { Fail $n $why }
    $lostAt = 0
    foreach ($try in 1..$tries) {
        Click-At $m.X $m.Y
        Start-Sleep -Milliseconds 1000
        $lines = Shot $n "entry$try"
        if (& $Done $lines) { return }
        # 按钮在、点了却没反应，多半是 ui.busy 把工具栏禁用了（第 4 步切回文字视图时后台写库，
        # 四路同写会 SQLITE_BUSY 卡十几秒）。这里等一拍再按新一帧重定位，别把重试额度在几百毫秒里烧光。
        # 等完这一拍也要判 $Done：弹窗本来就是慢半拍才画出来的，11-21 07:22 L1/L4 的
        # 5-entry5p/7p 已经开着「这次怎么读」，这里不判又去认「开始学」，下一轮就把弹窗点没了。
        if ($pauseMs -gt 0) {
            Start-Sleep -Milliseconds $pauseMs
            $lines = Shot $n "entry${try}p"
            if (& $Done $lines) { return }
        }
        # 入口点不动有时不是按钮的事，是屏幕当场才换的状态（例如写库失败后才挂出的
        # 「阅读视图暂未保存」）。$StallAction 在这一帧上处理一次，处理完按新一帧重来。
        if ($StallAction -and (& $StallAction $lines)) {
            Start-Sleep -Milliseconds 800
            $lines = Shot $n "entry${try}s"
            if (& $Done $lines) { return }
        }
        $again = & $Find $lines
        if (-not $again -and $stuckSec -gt 0) {
            $stuckStop = (Get-Date).AddSeconds($stuckSec); $k = 0
            while (-not $again -and (Get-Date) -lt $stuckStop) {
                $k++
                Start-Sleep -Milliseconds 800
                $lines = Shot $n "entry${try}w$k"
                if (& $Done $lines) { return }
                $again = & $Find $lines
            }
        }
        if (-not $again) {
            Start-Sleep -Milliseconds 800
            $lines = Shot $n "entry${try}b"
            if (& $Done $lines) { return }
            $again = & $Find $lines
        }
        if (-not $again) { $lostAt = $try; break }
        $m = $again
    }
    $last = (Shot $n 'entry-fail') | ForEach-Object { $_.Text }
    $detail = if ($lostAt -gt 0) { "点完第 $lostAt 次按钮就认不出来了（没敢按旧坐标再点）" } else { "点了 $try 次状态没变" }
    Fail $n "$why（$detail；屏上：$((@($last) -join ' / ')))"
}
# 点一下等一个原生窗口真的出现：$IsOpen 成立才算这一下生效。第 2 步「上传教材」在四路并行时会丢：
# 11-21 04:26 L4 点完交锁后 3 ms 就被别路抢走前台，选书框根本没开，Choose-File 只会干等 20 秒判失败。
# 所以没弹框就按新一帧重新定位再点；框开过以后的失败（路径没填进去、回车没关掉）不重点——
# 那种情况 Choose-File 自己会关框并报原因，再点一下反而可能点在残留的框上。
function Click-Until-Dialog([int]$n, [string]$label, [scriptblock]$IsOpen, [int]$sec = 40, [int]$tries = 4, [int]$findSec = 15, [int]$openSec = 6, [bool]$exact = $false) {
    $m = $null; $deadline = (Get-Date).AddSeconds($findSec)
    do {
        $lines = Shot $n 'find'; $m = if ($exact) { Find-Exact $lines $label } else { Find-Text $lines $label }
        if (-not $m) { Start-Sleep -Seconds 2 }
    } while (-not $m -and (Get-Date) -lt $deadline)
    if (-not $m) { Fail $n "屏幕上找不到「$label」" }
    $deadline = (Get-Date).AddSeconds($sec)
    foreach ($try in 1..$tries) {
        if (& $IsOpen) { return }
        if ($try -gt 1) {
            # 上一轮点空了：光标可能被别路挪走，按新一帧重新定位。
            $lines = Shot $n 'dialog-find'; $m = if ($exact) { Find-Exact $lines $label } else { Find-Text $lines $label }
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
function Click-Confirm([int]$n, [scriptblock]$Find, [scriptblock]$Done, [int]$tries = 4, [int]$waitSec = 5, [int]$pauseMs = 600, [scriptblock]$BeforePress = $null) {
    if (& $Done (Shot $n 'confirm')) { return $true }
    foreach ($try in 1..$tries) {
        $m = & $Find (Shot $n "confirm$try")
        if (-not $m) { return $false }
        # 按下前先确认这颗按钮还在原位（-BeforePress 里临时拍一帧），免得点在已经被换掉的画面上。
        $pressed = if ($BeforePress) { Click-At $m.X $m.Y -BeforePress $BeforePress } else { $null = Click-At $m.X $m.Y; $true }
        if (-not $pressed) {
            if (& $Done $script:Last) { return $true }
            if ($pauseMs -gt 0) { Start-Sleep -Milliseconds $pauseMs }
            continue
        }
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
# 顶部（或底部）一条裁出来放大再认：2.0 的选中浮条字太小（浮条高 32dp、标签 12sp），
# 原生分辨率下 Windows OCR 把「讲讲」读成「½²」（11-21 10:47 那一轮 x=238 的格子就是），
# 放大 3 倍才认得出「讲讲」「举个例子」「笔记」，落点也才准。返回的坐标已经按缩放比换回屏幕坐标，
# 可以直接交给 Find-Text / Click-At。
function Read-ZoomStrip([string]$png, [int]$top, [int]$bottom, [int]$scale = 3) {
    if (-not $png -or -not (Test-Path -LiteralPath $png)) { return @() }
    $src = [Drawing.Bitmap]::new($png)
    $file = Join-Path (Split-Path -Parent $png) ('zoom-' + [guid]::NewGuid().ToString('N').Substring(0, 8) + '.png')
    try {
        $bottom = [Math]::Min($bottom, $src.Height)
        $top = [Math]::Max(0, $top)
        $h = $bottom - $top
        if ($h -lt 8) { return @() }
        $rect = [Drawing.Rectangle]::new(0, $top, $src.Width, $h)
        $effectiveScale = Get-SafeOcrScale $src.Width $h $scale
        $zoom = [Drawing.Bitmap]::new($src.Width * $effectiveScale, $h * $effectiveScale)
        try {
            $g = [Drawing.Graphics]::FromImage($zoom)
            try {
                $g.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                $g.DrawImage($src, [Drawing.Rectangle]::new(0, 0, $zoom.Width, $zoom.Height), $rect, [Drawing.GraphicsUnit]::Pixel)
            } finally { $g.Dispose() }
            $zoom.Save($file, [Drawing.Imaging.ImageFormat]::Png)
        } finally { $zoom.Dispose() }
        $out = @()
        foreach ($line in Read-Screen $file) {
            $ws = @(foreach ($w in $line.Words) {
                $b = $w.BoundingRect
                [pscustomobject]@{ Text = $w.Text; BoundingRect = [pscustomobject]@{
                        X = $b.X / $effectiveScale; Y = ($b.Y / $effectiveScale) + $top
                        Width = $b.Width / $effectiveScale; Height = $b.Height / $effectiveScale } }
            })
            $out += [pscustomobject]@{ Text = (($ws | ForEach-Object { $_.Text }) -join ''); Words = $ws }
        }
        return $out
    } finally {
        $src.Dispose()
        Remove-Item -LiteralPath $file -ErrorAction SilentlyContinue
    }
}
function Find-ZoomText([string]$png, [int]$top, [int]$bottom, [string]$label, [int]$scale = 3) {
    return (Find-Text (Read-ZoomStrip $png $top $bottom $scale) $label)
}
function Find-AnchorChip($lines, [string]$png, [int]$winH) {
    $m = $lines | Where-Object { ($_.Text -replace '\s', '') -match '原文第\d+[页頁贞貞虫]' } | Select-Object -First 1
    if ($m) { return $m }
    if (-not $png -or $winH -le 0) { return $null }
    $zLines = Read-ZoomStrip $png ([int]($winH * 0.48)) ([int]($winH * 0.98)) 3
    return ($zLines | Where-Object { ($_.Text -replace '\s', '') -match '原文第\d+[页頁贞貞虫]' } | Select-Object -First 1)
}
# 点完「讲讲」算不算生效：2.0 不再另开一张卡——伴读抽屉（窄窗是半屏底部抽屉）打开并切到「提问」页，
# 顶上顶着「引文 · …」，先「正在整理…」，然后出正文和「出处」。第一次问还要先过同意框。
# 只认这几种看得见的状态——「点过一次」不算，四路交锁时那一下常常被别的窗口吃掉。
function Test-ExplainStarted($lines) {
    if (Test-ConsentUp $lines) { return $true }
    foreach ($s in '正在整理', '引文', '出处', '批注') {
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
# 第 4 步的同意框：按钮字小，Windows OCR 常认错字——11-21 05:58 那轮同一条按钮，
# 一帧读成「允许百甫」、一帧读成「允许并讲鱈」。所以「框在不在」「截到的是不是那一下」
# 都不能只认一个整词，也不能只拿一帧判死。
function Test-ConsentUp($lines) {
    foreach ($s in '允许这本书讲解', '还没允许讲解', '允许并讲解', '这段原文会发到哪里', '不允许', '允许并') {
        if (Find-Text $lines $s) { return $true }
    }
    return $false
}
# 同意按钮的落点：先认整词（含 PR #791 原地一键允许「允许这本书讲解」与旧版「允许并讲解」），
# 再认「允许这本书」「允许并」（吃掉末字错字），最后退到「标题下面含「允许」、又不是「不允许 / 还没允许」」的那一行。
function Find-ConsentAllow($lines) {
    foreach ($s in '允许这本书讲解', '允许这本书', '允许并讲解', '允许并') {
        $m = Find-Text $lines $s
        if ($m) { return $m }
    }
    $title = Find-Text $lines '这段原文会发到哪里'
    if (-not $title) { $title = Find-Text $lines '还没允许讲解' }
    # 退化到这一条时必须有标题做锚：没有标题就没法区分「框里的允许」和正文里的「允许」，
    # 宁可不点（外面 Click-Confirm 会重拍新帧再找）也不往正文上点。
    if (-not $title) { return $null }
    $best = $null
    foreach ($l in $lines) {
        $t = $l.Text -replace '\s', ''
        if ($t -notmatch '允许' -or $t -match '不允许|还没允许') { continue }
        $b = Get-LineBox $l
        $y = [int](($b.Y1 + $b.Y2) / 2)
        if ($y -le $title.Y) { continue }
        if (-not $best -or $y -gt $best.Y) {
            $best = [pscustomobject]@{ X = [int](($b.X1 + $b.X2) / 2); Y = $y; X1 = [int]$b.X1; X2 = [int]$b.X2; Y1 = [int]$b.Y1; Y2 = [int]$b.Y2 }
        }
    }
    return $best
}
# 同意框关没关：标题和两个按钮都不在才算关；已经进了加载态或出了回答也算（那时框必然没了）。
# 只看「允许并讲解」一个词会被错字骗——认不出来就当成框关了，其实框还开着，随后空等 120 秒。
# 这里不拿光秃秃的「允许」当证据：正文里出现「允许」也会命中，正常的阅读器页会被当成框还在。
function Test-ConsentGone($lines) {
    if (Find-Text $lines '思考') { return $true }
    if (Find-Text $lines '出处') { return $true }
    foreach ($s in '允许这本书讲解', '还没允许讲解', '允许并讲解', '这段原文会发到哪里', '不允许', '允许并') {
        if (Find-Text $lines $s) { return $false }
    }
    return $true
}
# 认按钮别拿单帧判死：弹窗淡出的过渡帧里标题还留着残影、按钮已经不在这一帧了
# （11-21 05:58 L3 的第 3 步就是这样，单帧找不到就被判成「认不出来」）。
# 隔一拍再拍，最多 $tries 帧；中途弹窗整个退掉（$Gone 成立）就直接返回 $null——那是自己散了，不用点。
function Find-AcrossFrames([int]$n, [string]$tag, [scriptblock]$Find, [scriptblock]$Gone, [int]$tries = 3, [int]$pauseMs = 400) {
    $lines = $script:Last
    foreach ($k in 0..($tries - 1)) {
        if ($k -gt 0) { Start-Sleep -Milliseconds $pauseMs; $lines = Shot $n "$tag$k" }
        $m = & $Find $lines
        if ($m) { return $m }
        if (& $Gone $lines) { return $null }
    }
    return $null
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
# 第 5 步底栏那个提交按钮在提交态会被 OCR 错读：11-21 06:2x 三路都把它读成「始边边下」「万始边边」
# （同帧「暂不开启」读成「鱈不启」「鱈不万启」）。所以认它不能只认整词：先认整词，再认「开始边看」
# （整词被截断）和「始边」残片，取「这次怎么读」下面的最低那一行（弹窗里的 chip 在上、底栏按钮在下）；
# 都不行再用放大两倍的暗字识别。'边看边' 这种残片不能用来兜底——弹窗里的 chip 就长这样，会点回 chip。
function Find-Step5Confirm($lines, [string]$png) {
    foreach ($s in '开始边看边学', '开始边看') {
        $m = Find-Text $lines $s
        if ($m) { return $m }
    }
    $top = Find-Text $lines '这次怎么读'
    $best = $null
    foreach ($l in $lines) {
        $t = $l.Text -replace '\s', ''
        if ($t -notmatch '始边') { continue }
        $b = Get-LineBox $l
        $y = [int](($b.Y1 + $b.Y2) / 2)
        if ($top -and $y -le $top.Y) { continue }
        if (-not $best -or $y -gt $best.Y) {
            $best = [pscustomobject]@{ X = [int](($b.X1 + $b.X2) / 2); Y = $y; X1 = [int]$b.X1; X2 = [int]$b.X2; Y1 = [int]$b.Y1; Y2 = [int]$b.Y2 }
        }
    }
    if ($best) { return $best }
    if ($png) { return Find-DimButton $png '开始边看边学' }
    return $null
}
# 第 5 步等讲解时，服务端偶尔回「回答缺少可核对的页码依据，暂时无法确认。」并给「再试一次」按钮。
# 原来的拒绝词只认「没有可核对的出处」，L1 既没报错也没重放，干等满 150 秒才报超时（11-21 06:37）。
# 认它不能只认整句：那一帧被 OCR 读成「依框，暫时无氵去确认」，所以按稳定残片 + 容错骨架认。
function Find-LectureRefusal($lines) {
    $pat = '尚未接通|无法讲解|尚未核实|连不上|超时|没有可用的讲解|讲解暂时读不到|讲解服务暂时不可用|额度.*用完|没有可核对的出处|出处已过期|缺少可核对|可核对的页码|[暂暫]时无.{0,3}确认'
    return $lines | Where-Object { $_.Text -match $pat } | Select-Object -First 1
}
# 拒答一次不算终局：界面还带「再试一次」就点它，把这一轮当重放（仍算在原来的 150 秒预算里）；
# 已经重放过还拒、或按钮不在，就把服务端原话带进失败信息，别让这条 Lane 干等到超时。
function Resolve-LectureRefusalAction($lines, [bool]$AlreadyRetried) {
    $e = Find-LectureRefusal $lines
    if (-not $e) { return [pscustomobject]@{ Action = 'none'; Reason = $null; Button = $null } }
    if (-not $AlreadyRetried) {
        $retry = Find-Text $lines '再试一次'
        if ($retry) { return [pscustomobject]@{ Action = 'retry'; Reason = $e.Text; Button = $retry } }
    }
    return [pscustomobject]@{ Action = 'fail'; Reason = $e.Text; Button = $null }
}
# 首页上同一列、紧挨书名的页码属于该书，书单标题和云端待下载卡都没有本地读到的页码。
function Normalize-Step2Title([string]$Title) {
    # Windows OCR 经常把 Health 的小写 l 识成大写 I。
    return ($Title.ToLowerInvariant() -replace '[il]', 'l' -replace '[^\p{L}\p{Nd}]', '')
}
function Test-Step2Home($lines) {
    $settings = Find-Text $lines '设置'
    # The native title bar also says LearningCoach. Only the in-app header has Settings on the same row.
    return [bool](Find-OnRow $lines 'LearningCoach' $settings 22)
}
function Find-Step2HomeBook($lines, [string]$Title) {
    if (-not (Test-Step2Home $lines)) { return $null }
    $titleKey = Normalize-Step2Title $Title
    if (-not $titleKey) { return $null }
    $titles = @($lines | Where-Object { (Normalize-Step2Title $_.Text) -eq $titleKey })
    $progress = @($lines | Where-Object {
        ($_.Text -replace '\s', '') -match '读到第|上次停在|第[\d一二三四五六七八九十巧OIl]+[页頁贞貞]'
    })
    # A focused book can render its title and page in one metadata line. The separate
    # title is sometimes lost to OCR, so require the matching title prefix and its CTA.
    foreach ($line in $progress) {
        $label = ($line.Text -split '[·•．。]', 2)[0]
        if ((Normalize-Step2Title $label) -ne $titleKey) { continue }
        $box = Get-LineBox $line
        $continueButton = Find-OnRow $lines '继续' $box 26
        if ($continueButton -and $continueButton.X -gt $box.X2) {
            return [pscustomobject]@{ Text = $line.Text; Click = $continueButton }
        }
    }
    foreach ($titleLine in $titles) {
        $titleBox = Get-LineBox $titleLine
        foreach ($line in $progress) {
            $box = Get-LineBox $line
            $dy = $box.Y - $titleBox.Y
            if ($dy -le 0 -or $dy -gt 62 -or [Math]::Abs($box.X1 - $titleBox.X1) -gt 24) { continue }
            $continueButton = Find-OnRow $lines '继续' $box 46
            if ($continueButton -and $continueButton.X -gt $box.X2 -and ($line.Text -notmatch '读到第')) {
                return [pscustomobject]@{ Text = $line.Text; Click = $continueButton }
            }
            if ($line.Text -match '读到第') {
                return [pscustomobject]@{ Text = $line.Text; Click = (Get-LineClick $titleLine) }
            }
        }
    }
    return $null
}
# 第 2 步：目标书必须真出现在首页上。阅读器底栏的页码不能代替这个检查。
# 阅读器底栏读出页号只证明书导进来了、阅读器开了，不证明首页上有这本书——退回首页那三下都没读到卡片时，
# 曾经走一条「导入后直接打开在阅读器」的 Pass，等于没验「书上首页」就过了（20:05 评审指出的假绿）。
function Resolve-Step2HomeVerdict($Row, [string]$ReaderPage) {
    if ($Row) {
        $when = if ($ReaderPage) { "导入后先打开在阅读器第 $ReaderPage 页" } else { '导入后没读到阅读器底栏页号，首页这一帧直接认出了书' }
        return [pscustomobject]@{ Fail = $null; Pass = "书在首页上（行：$($Row.Text)），$when" }
    }
    if ($ReaderPage) {
        return [pscustomobject]@{ Fail = "书导入后确实打开在阅读器（底栏读到第 $ReaderPage 页），但没有在首页确认目标书名和同卡进度（见 2-after-back*.png / 2-shelf-scroll*.png）"; Pass = $null }
    }
    return [pscustomobject]@{ Fail = '既没读到阅读器底栏页号，也没在首页确认目标书名和同卡进度'; Pass = $null }
}
# 阅读器顶栏/底栏的页码在 OCR 里可能被拆成「第 1 5 0 页」，高分屏也常把「页」认成「贞 / 虫」，先去空白再取数。
function Get-CitePageNumber([string]$CiteText) {
    $m = [regex]::Match(($CiteText -replace '\s', ''), '第(\d+)[页頁贞貞虫]')
    if ($m.Success) { return $m.Groups[1].Value }
    return $null
}
# Find-All / Find-Text 只回坐标、不带原文，第 7 步要核对出处那行自己写的页码就得回到同一帧的原始行。
# 优先取「出处 + 第 N 页」那行（抽屉里可能同时挂着引文行和出处行），没有就取最靠下的一行「出处」，
# 好把那一眼读到的原话带进失败信息。
function Find-CiteLine($lines) {
    $hits = @($lines | Where-Object { ($_.Text -replace '\s', '') -match '出处' })
    if (-not $hits.Count) { return $null }
    $withPage = @($hits | Where-Object { ($_.Text -replace '\s', '') -match '第\d+[页頁贞貞虫]' })
    if ($withPage.Count) { return $withPage[-1] }
    return $hits[-1]
}
# 第 7 步：出处那行自己写的页码必须解析得出来，且点完底栏得停在那一页。
# 解析不出页码时过去会跳过整段跳页断言、最后还能 Pass 7（20:05 评审指出的第二处假绿），现在一律算失败。
function Resolve-Step7PageVerdict([string]$CitePage, [string]$NowPage, [string]$CiteText = '') {
    if (-not $CitePage) {
        $said = ($CiteText -replace '\s', '')
        if (-not $said) { $said = '这一帧没读到「出处」那行的原文' }
        return "「出处」那行没解析出页码（读到：「$said」），跳页没法核对（见 7-cite.png）"
    }
    if (-not $NowPage) { return '点了出处底栏读不到页号（见 7-cite.png）' }
    if ($NowPage -ne $CitePage) { return "点了出处没跳到第 $CitePage 页（现在第 $NowPage 页，见 7-cite.png）" }
    return $null
}
# 收起伴读抽屉后仍要停在出处写的页；读不到页号也算没验成，不默认通过。
function Resolve-Step7AfterCloseVerdict([string]$CitePage, [string]$NowPage) {
    if (-not $CitePage) { return '「出处」那行没解析出页码，收起伴读后的页码没法核对（见 7-card.png）' }
    if (-not $NowPage) { return "收起伴读后底栏读不到页号，没法确认还停在第 $CitePage 页（见 7-card.png）" }
    if ($NowPage -ne $CitePage) { return "收起伴读后页码不是出处写的第 $CitePage 页（现在第 $NowPage 页，见 7-card.png）" }
    return $null
}
# 第 7 步要点的是「解析出页码的那一行」，不是屏幕上最靠下的「出处」。
# 同一抽屉里可以同时有第 154 页和第 153 页两行；页码取第一次解析的数字，重试也只重找这一页。
function Find-CiteLineForPage($lines, [string]$page) {
    if (-not $page) { return $null }
    $want = [regex]::Escape($page)
    $hits = @($lines | Where-Object {
        $c = $_.Text -replace '\s', ''
        $c -match '出处' -and $c -match "第$want[页頁贞貞虫]"
    })
    if (-not $hits.Count) { return $null }
    return $hits[-1]
}
function Get-LineClick($line) {
    if (-not $line) { return $null }
    $b = Get-LineBox $line
    if ($b.X2 -lt $b.X1) { return $null }
    [pscustomobject]@{ X = [int](($b.X1 + $b.X2) / 2); Y = [int]$b.Y; X1 = $b.X1; X2 = $b.X2; Y1 = $b.Y1; Y2 = $b.Y2 }
}
# 伴读页签在抽屉顶同一行。OCR 会把它们拆开，选中的「提问」有时整颗认丢，正文与菜单说明也会出现「讲解 / 批注」。
# 只认预期区域（宽屏右栏顶或窄窗底抽屉顶）同一行上的独立标签：左边是「讲解」，右边同一行有「批注」，
# 认得到「提问」时必须夹在两者中间；含「批注」的菜单说明、整串粘连词和正文长句都不算页签。
function Find-CompanionTabLabel($lines, [string]$label, [int]$tol = 18, [int]$winW = 0, [int]$winH = 0) {
    $order = @('讲解', '提问', '批注')
    if ($label -notin $order) { return $null }
    $cands = New-Object System.Collections.ArrayList
    $lineIdx = 0
    foreach ($l in @($lines)) {
        $lineIdx++
        $trimmed = ([string]$l.Text).Trim()
        $compact = $trimmed -replace '\s', ''
        if (-not $compact) { continue }
        $allowedTokens = @('讲解', '提问', '批注', '提问批注', '浮层', '并排', '×', 'x', 'X')
        if ($compact -notin @('讲解', '提问', '批注', '提问批注')) {
            $tokens = @($trimmed -split '\s+' | Where-Object { $_ })
            if ($tokens.Count -lt 2) { continue }
            if (@($tokens | Where-Object { $_ -notin $allowedTokens }).Count -gt 0) { continue }
        }
        $present = @($order | Where-Object { $compact.Contains($_) })
        if (-not $present.Count) { continue }
        foreach ($name in $present) {
            $hit = Find-Text @($l) $name
            if (-not $hit) { continue }
            [void]$cands.Add([pscustomobject]@{
                Label = $name; LineId = $lineIdx; TextIdx = $compact.IndexOf($name)
                X = $hit.X; Y = $hit.Y; X1 = $hit.X1; X2 = $hit.X2; Y1 = $hit.Y1; Y2 = $hit.Y2
            })
            if ($present.Count -eq 1) { break }
        }
    }
    $seeds = @($cands | Where-Object { $_.Label -eq '讲解' } | Sort-Object Y, X)
    foreach ($seed in $seeds) {
        $row = @($cands | Where-Object { [Math]::Abs($_.Y - $seed.Y) -le $tol })
        $explain = @($row | Where-Object { $_.Label -eq '讲解' } | Sort-Object X | Select-Object -First 1)
        if (-not $explain.Count) { continue }
        $notes = @($row | Where-Object { $_.Label -eq '批注' -and $_.X -gt $explain[0].X } | Sort-Object X | Select-Object -First 1)
        if (-not $notes.Count) { continue }
        $asks = @($row | Where-Object { $_.Label -eq '提问' })
        if ($asks.Count) {
            $between = @($asks | Where-Object {
                ($_.X -gt $explain[0].X -or ($_.LineId -eq $explain[0].LineId -and $_.TextIdx -gt $explain[0].TextIdx)) -and
                ($_.X -lt $notes[0].X -or ($_.LineId -eq $notes[0].LineId -and $_.TextIdx -lt $notes[0].TextIdx))
            })
            if (-not $between.Count) { continue }
        }
        if ($winW -gt 0 -and $winH -gt 0) {
            $rowY = $explain[0].Y
            $inSideDrawerTop = ($winW -ge 1000 -and $rowY -lt [int]([Math]::Max(90, $winH * 0.28)) -and $explain[0].X -ge [int]($winW * 0.50))
            $inBottomDrawerTop = ($rowY -ge [int]($winH * 0.30) -and $rowY -le [int]($winH * 0.85) -and $explain[0].X -lt [int]($winW * 0.65))
            if (-not ($inSideDrawerTop -or $inBottomDrawerTop)) { continue }
        }
        $picked = @($row | Where-Object { $_.Label -eq $label } | Sort-Object X | Select-Object -First 1)
        if ($picked.Count) { return $picked[0] }
    }
    return $null
}
# 阅读器顶栏右半区的独立「伴读」抽屉开关：去掉首尾空白后仅匹配 ^伴读$，排除「伴读：…」学法项与左半区/正文误命中。
function Find-ReaderCompanionToggle($lines, [int]$winW = 0, [int]$winH = 0) {
    $hits = New-Object System.Collections.ArrayList
    foreach ($l in @($lines)) {
        $trimmed = ([string]$l.Text).Trim()
        if (-not $trimmed) { continue }
        if ($trimmed -match '^伴读[：:]') { continue }
        if ($trimmed -notmatch '^伴读$') { continue }
        $click = Get-LineClick $l
        if (-not $click) { continue }
        if ($winH -gt 0 -and $click.Y -ge [int]([Math]::Max(80, $winH * 0.20))) { continue }
        if ($winW -gt 0 -and $click.X -lt [int]($winW * 0.50)) { continue }
        [void]$hits.Add($click)
    }
    if (-not $hits.Count) { return $null }
    return @($hits | Sort-Object Y, @{ Expression = { $_.X }; Descending = $true })[0]
}
# 打开伴读抽屉：先在预期区域核对同一页签行上的独立标签，未开时只点顶栏右半区独立「伴读」开关。
function Ensure-CompanionDrawerOpen(
    [int]$step,
    [int]$winW,
    [int]$winH,
    [int]$tries = 3,
    [string]$missTag = 'explain-tab0',
    [string]$openTagPrefix = 'explain-tab'
) {
    $lines = Show-ReaderTop $step 'top'
    if (-not $lines) { $lines = Shot $step $missTag }
    $tab = Find-CompanionTabLabel $lines '讲解' 18 $winW $winH
    $opened = 0
    while (-not $tab -and $opened -lt $tries) {
        $open = Find-ReaderCompanionToggle $lines $winW $winH
        if ($open) {
            Note "步骤$step 伴读抽屉是收起的，先点顶栏「伴读」把它打开"
            Hover $open.X $open.Y; Start-Sleep -Milliseconds 400
            $null = Click-At $open.X $open.Y
            Start-Sleep -Seconds 1
            $shotTag = if ($tries -eq 1) { $openTagPrefix } else { "$openTagPrefix$opened" }
            $lines = Shot $step $shotTag
        } else {
            Start-Sleep -Milliseconds 800
            $lines = Show-ReaderTop $step "$openTagPrefix-top$opened"
            if (-not $lines) {
                $shotTag = if ($tries -eq 1) { $openTagPrefix } else { "$openTagPrefix$opened" }
                $lines = Shot $step $shotTag
            }
        }
        $tab = Find-CompanionTabLabel $lines '讲解' 18 $winW $winH
        $opened++
    }
    [pscustomobject]@{ Tab = $tab; Lines = $lines }
}
# 只认抽屉模块条上与「自测」同排的「要点」（沿用 Find-Step6ModuleStrip 的纯模块词识别，兼容 OCR 粘连如「自测推导」「练习习题」），不能点击正文或顶栏中的同名文字。
function Find-Step5KeyPointTab($lines, [int]$winW, [int]$winH) {
    $keys = @(); $tests = @()
    foreach ($line in @($lines)) {
        $t = ([string]$line.Text -replace '\s', '')
        if (-not $t -or $t -notmatch '^(?:要点|自测|推导|练习|习题)+$') { continue }
        $box = Get-LineBox $line
        if ($box.X2 -lt $box.X1) { continue }
        foreach ($word in @('要点', '自测')) {
            if (-not $t.Contains($word)) { continue }
            $p = Find-Text @($line) $word
            if (-not $p) { continue }
            # 若 OCR 把多个模块词整块粘成同一个 Word 框，按字符区间切出目标词位置，保证「要点」点在左侧且同排顺序可判。
            if ($t.Length -gt $word.Length -and $p.X1 -eq $box.X1 -and $p.X2 -eq $box.X2) {
                $idx = $t.IndexOf($word)
                $span = $p.X2 - $p.X1
                $sx1 = [int]($p.X1 + $span * $idx / $t.Length)
                $sx2 = [int]($p.X1 + $span * ($idx + $word.Length) / $t.Length)
                $p = [pscustomobject]@{ X = [int](($sx1 + $sx2) / 2); Y = $p.Y; X1 = $sx1; X2 = $sx2; Y1 = $p.Y1; Y2 = $p.Y2 }
            }
            if ($winW -ge 1000 -and $p.X -lt ($winW * 0.5)) { continue }
            if ($p.Y -lt ($winH * 0.20) -or $p.Y -gt ($winH * 0.90)) { continue }
            if ($word -eq '要点') { $keys += $p } else { $tests += $p }
        }
    }
    foreach ($key in $keys) {
        foreach ($test in $tests) {
            if ([Math]::Abs($key.Y - $test.Y) -le 20 -and $test.X -gt $key.X -and
                ($test.X - $key.X) -le 180) { return $key }
        }
    }
    return $null
}
function Test-Step5KeyPointView($lines, [int]$winW, [int]$winH) {
    foreach ($line in @($lines)) {
        $b = Get-LineBox $line
        if ($winW -ge 1000 -and $b.X1 -lt ($winW * 0.5)) { continue }
        if ($b.Y -lt ($winH * 0.20)) { continue }
        $t = ([string]$line.Text -replace '\s', '')
        if ($t -match '^讲的是[:：]?第\d+[页頁贞貞虫]|^本章要点[·:：．]|^正在讲解这一页|^正在按节整理|^本章暂无提取要点|^当前页暂未归入正文小节|^登录后可使用本章伴读|^伴读整理已在设置中关闭|^讲解服务.*连不上|^本章伴读.*连不上') { return $true }
    }
    return $false
}
function Find-Step5ChapterPoint($lines, [int]$winW, [int]$winH) {
    $head = @($lines | Where-Object {
        $b = Get-LineBox $_
        ($winW -lt 1000 -or $b.X1 -ge ($winW * 0.5)) -and $b.Y -ge ($winH * 0.20) -and
            (($_.Text -replace '\s', '') -match '^本章要点[·:：．]')
    } | Select-Object -First 1)
    if (-not $head.Count) { return $null }
    $headY = (Get-LineBox $head[0]).Y
    return ($lines | Where-Object {
        $b = Get-LineBox $_
        ($winW -lt 1000 -or $b.X1 -ge ($winW * 0.5)) -and $b.Y -gt $headY -and
            (($_.Text -replace '\s', '') -match '^(?:核心[问同]题|\d+[.、．]\S+)')
    } | Select-Object -First 1)
}
# 步骤6「读前自测」从当前帧实时定位模块条、提交按钮与上方最后一个选项，避免滚动/抽屉高度变化后沿用旧帧坐标漏选。
function Find-Step6ModuleStrip($lines, [int]$winW = 0, [int]$maxY = [int]::MaxValue) {
    $stripNames = @('自测', '要点', '推导', '练习', '习题')
    $hits = New-Object System.Collections.ArrayList
    foreach ($l in @($lines)) {
        $t = ([string]$l.Text -replace '\s', '')
        if (-not $t -or $t -notmatch '^(?:要点|自测|推导|练习|习题)+$') { continue }
        $bb = Get-LineBox $l
        if ($bb.X2 -lt $bb.X1 -or $bb.Y -ge $maxY) { continue }
        $target = $null
        foreach ($name in $stripNames) {
            if ($t.Contains($name)) {
                $m = Find-Text @($l) $name
                if ($m) { $target = $m; break }
            }
        }
        if (-not $target) {
            $target = [pscustomobject]@{ X = [int](($bb.X1 + $bb.X2) / 2); Y = [int]$bb.Y; X1 = $bb.X1; X2 = $bb.X2; Y1 = $bb.Y1; Y2 = $bb.Y2 }
        }
        [void]$hits.Add($target)
    }
    if (-not $hits.Count) { return $null }
    if ($winW -ge 1200) {
        $right = @($hits | Where-Object { $_.X1 -ge [int]($winW * 0.50) })
        if ($right.Count) { return @($right | Sort-Object Y | Select-Object -Last 1)[0] }
    }
    return @($hits | Sort-Object Y | Select-Object -Last 1)[0]
}
function Find-Step6PretestState($lines, [int]$winW, [int]$winH, $fallbackStrip = $null) {
    $sub = $lines | Where-Object { ($_.Text -replace '\s', '') -match '^提交$' } | Select-Object -First 1
    $sb = if ($sub) { Get-LineBox $sub } else { $null }
    if (-not $sb) {
        $guessRow = $lines | Where-Object { ($_.Text -replace '\s', '') -match '这题是猜的' } | Select-Object -First 1
        if ($guessRow) {
            $gb = Get-LineBox $guessRow
            $sb = [pscustomobject]@{ X1 = [int]($winW - 96); X2 = [int]($winW - 24); Y1 = $gb.Y1; Y2 = $gb.Y2; Y = $gb.Y }
        }
    }
    if (-not $sb) { return [pscustomobject]@{ Submit = $null; Strip = $null; Option = $null } }
    $curStrip = Find-Step6ModuleStrip $lines $winW $sb.Y
    if (-not $curStrip -and $fallbackStrip -and $fallbackStrip.Y -lt ($sb.Y - 24)) {
        $curStrip = $fallbackStrip
    }
    $minY = if ($curStrip) { [int]$curStrip.Y + 24 } else { 0 }
    $optBandH = [Math]::Max(110, [int]($winH * 0.24))
    $requireRightPane = ($winW -ge 1200 -and (-not $curStrip -or $curStrip.X -ge [int]($winW * 0.50)))
    $above = @($lines | Where-Object {
        $t = ($_.Text -replace '\s', '')
        $bb = Get-LineBox $_
        $bb.Y -lt ($sb.Y - 6) -and $bb.Y -gt ($sb.Y - $optBandH) -and $bb.Y -gt $minY -and
        (-not $requireRightPane -or $bb.X1 -ge [int]($winW * 0.65)) -and
        $t -notmatch '这题是猜的|先猜一猜|^第\d+[/／]\d+[题題]$|提交|^(?:要点|自测|推导|练习|习题)+$'
    } | Sort-Object { (Get-LineBox $_).Y })
    $opt = if ($above.Count -gt 0) { $above[-1] } else { $null }
    [pscustomobject]@{ Submit = $sb; Strip = $curStrip; Option = $opt }
}
# 读前自测题卡出来了没有：先认题号行「第 i / N 题」（OCR 偶把「题」认成繁写「題」）。
# 题号字小，11-27 07:51 那帧整卡都在、题号却一行没认出来：这时同一帧有「这题是猜的」，
# 且按 Find-Step6PretestState 认得出模块条、提交和它上面的选项，也算题卡就绪。返回认到的那一行，没就绪返回 $null。
function Find-Step6QuestionCard($lines, [int]$winW, [int]$winH, $fallbackStrip = $null) {
    $q = $lines | Where-Object { ($_.Text -replace '\s', '') -match '^第\d+[/／]\d+[题題]' } | Select-Object -First 1
    if ($q) { return $q }
    $guess = $lines | Where-Object { ($_.Text -replace '\s', '') -match '这题是猜的' } | Select-Object -First 1
    if (-not $guess) { return $null }
    $st = Find-Step6PretestState $lines $winW $winH $fallbackStrip
    if ($st.Submit -and $st.Strip -and $st.Option) { return $st.Option }
    return $null
}
# 跳页框里的数字紧挨在「前往」左边。认不出返回 $null，调用方再用 Read-JumpFieldCrop 截框放大重认。
# 没读到目标页（认不出、截断、别的数）调用方都不得按回车。
function Get-JumpFieldText($lines) {
    $go = Find-Text $lines '前往'
    if (-not $go) { return $null }
    $best = $null
    foreach ($l in @($lines)) {
        $compact = $l.Text -replace '\s', ''
        if ($compact -match '^(\d{1,5})前往$') { return $Matches[1] }
        if ($compact -notmatch '^\d{1,5}$') { continue }
        $box = Get-LineBox $l
        $sameRow = [Math]::Abs($box.Y - $go.Y) -le 24
        $leftOfGo = $box.X2 -le ($go.X1 + 8)
        $near = ($go.X1 - $box.X2) -le 96
        if ($sameRow -and $leftOfGo -and $near -and $box.X2 -gt $box.X1) {
            if (-not $best -or $box.X2 -gt $best.Right) {
                $best = [pscustomobject]@{ Text = $compact; Right = $box.X2 }
            }
        }
    }
    if ($best) { return $best.Text }
    return $null
}
# 放大截图里认出的行换回整帧坐标：只要整行都是数字的那串，取最右边的（框紧挨着「前往」）。
# 光标那一竖常被认成「|」「l」「I」粘在数字后面，Alt 是去掉这一截的读法；真页码以 Text 为准。
function Select-JumpFieldDigits($lines, [int]$offX, [int]$offY, [double]$scale) {
    $best = $null
    foreach ($l in @($lines)) {
        $raw = $l.Text -replace '\s', ''
        $text = Convert-OcrDigits $raw
        if ($text -notmatch '^\d{1,5}$') { continue }
        $alt = Convert-OcrDigits ($raw -replace '[|lI丨]+$', '')
        $b = Get-LineBox $l
        $right = $offX + [int]($b.X2 / $scale)
        if (-not $best -or $right -gt $best.Right) {
            $best = [pscustomobject]@{
                Text = $text; Alt = $alt; Right = $right
                X = $offX + [int](($b.X1 + $b.X2) / 2 / $scale); Y = $offY + [int]($b.Y / $scale)
            }
        }
    }
    return $best
}
# 整屏 OCR 常认不出跳页框里那颗小字：TON-1861 11-27 09:32 那轮三帧框里都是 33，整屏一个框内数字都没读到。
# 只截「前往」左边那一小块，放大再认，返回 Select-JumpFieldDigits 的结果（含整帧里的中心点），认不出返回 $null。
function Read-JumpFieldCrop([string]$png, $go) {
    if (-not $png -or -not $go -or -not (Test-Path -LiteralPath $png)) { return $null }
    $src = [Drawing.Bitmap]::new($png)
    $file = Join-Path (Split-Path -Parent $png) ('jump-' + [guid]::NewGuid().ToString('N').Substring(0, 8) + '.png')
    try {
        $gh = [Math]::Max(12, $go.Y2 - $go.Y1); $gw = [Math]::Max(12, $go.X2 - $go.X1)
        $x1 = [int][Math]::Max(0, $go.X1 - [int](4.5 * $gw)); $x2 = [int][Math]::Min($src.Width, $go.X1 - 2)
        $y1 = [int][Math]::Max(0, $go.Y1 - 2 * $gh); $y2 = [int][Math]::Min($src.Height, $go.Y2 + 2 * $gh)
        if (($x2 - $x1) -lt 8 -or ($y2 - $y1) -lt 8) { return $null }
        $rect = [Drawing.Rectangle]::new($x1, $y1, $x2 - $x1, $y2 - $y1)
        foreach ($scale in @(4, 6, 3)) {
            $zoom = [Drawing.Bitmap]::new($rect.Width * $scale, $rect.Height * $scale)
            try {
                $g = [Drawing.Graphics]::FromImage($zoom)
                try {
                    $g.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                    $g.DrawImage($src, [Drawing.Rectangle]::new(0, 0, $zoom.Width, $zoom.Height), $rect, [Drawing.GraphicsUnit]::Pixel)
                } finally { $g.Dispose() }
                $zoom.Save($file, [Drawing.Imaging.ImageFormat]::Png)
            } finally { $zoom.Dispose() }
            $hit = Select-JumpFieldDigits @(Read-Screen $file) $x1 $y1 $scale
            if ($hit) { return $hit }
        }
        return $null
    } finally {
        $src.Dispose()
        Remove-Item -LiteralPath $file -ErrorAction SilentlyContinue
    }
}
# 点回跳页框的落点。框 64dp 宽，右边隔 10dp 间距和「前往」按钮的内边距：框中心约在「前往」字左边 56dp
# （11-27 那帧 1.5 倍缩放下实测 89px，56dp 落在框里偏右）。放大重认拿到框里数字的位置时用它，但只认「前往」同一行、左边百来 dp 内的。
function Get-JumpFieldPoint($go, $field, [double]$scale) {
    $s = [Math]::Max(1.0, $scale)
    if ($field -and $field.X -lt $go.X1 -and ($go.X1 - $field.X) -le [int](110 * $s) -and [Math]::Abs($field.Y - $go.Y) -le [int](20 * $s)) {
        return [pscustomobject]@{ X = [int]$field.X; Y = [int]$go.Y }
    }
    return [pscustomobject]@{ X = [int]($go.X1 - [Math]::Round(56 * $s)); Y = [int]$go.Y }
}
# 跳页框逐位送数字。先把页码收成字符串再拆开：直接在整数上调 ToCharArray 会在第一位发出去之前就失败。
function Send-PageDigits([int]$page) {
    foreach ($ch in ([string]$page).ToCharArray()) {
        Send-Keys "$ch"
        Start-Sleep -Milliseconds 160
    }
}

function Jump-VerifiedReaderPage([int]$step, [int]$physPage, [int]$midX, [int]$hoverY, [string]$tag) {
    # Ctrl+G 打开跳页框。逐位敲，先读回数字，再核对阅读器底栏的物理页。
    Send-Keys '^g'; Start-Sleep -Milliseconds 900
    $opened = [bool](Await-Text $step "$tag-goto" @('前往') 10)
    if (-not $opened) { Fail $step "Ctrl+G 没展开跳页框（读不到「前往」，见 $step-$tag-goto.png）" }
    # 一次把「154」送进父状态会丢位（框里只剩 15，回车就跳到目录页）。逐位敲，敲完先认框里的数字。
    # 第一遍照 Ctrl+G 给的焦点直接敲；没读到目标页（框里还是原页、截断、认不出）就在最新一帧重新找「前往」，
    # 点进框里再整段重敲（TON-1861 11-27 09:32：三帧框里都是 33，数字和回车都没进框）。框收起来了先重按 Ctrl+G。
    # 一共 4 遍，没确认是目标页就不按回车，带着截图失败。
    $scale = 1.0
    try { $dpi = [WD]::GetDpiForWindow($script:Win); if ($dpi -gt 0) { $scale = [Math]::Max(1.0, $dpi / 96.0) } } catch { }
    $typedSeen = $null
    $typedOk = $false
    $field = $null
    foreach ($attempt in 1..4) {
        if ($attempt -gt 1) {
            $go = Find-Text $script:Last '前往'
            if (-not $go) {
                Send-Keys '^g'; Start-Sleep -Milliseconds 900
                $null = Await-Text $step "$tag-goto$attempt" @('前往') 6
                $go = Find-Text $script:Last '前往'
                if (-not $go) { Note "跳页框收起来了，重按 Ctrl+G 也没展开（第 $attempt 遍）"; continue }
            }
            $pt = Get-JumpFieldPoint $go $field $scale
            $null = Click-At $pt.X $pt.Y
            Start-Sleep -Milliseconds 300
        }
        Send-Keys '^a'
        Start-Sleep -Milliseconds 150
        Send-Keys '{BACKSPACE}'
        Start-Sleep -Milliseconds 120
        Send-PageDigits $physPage
        Start-Sleep -Milliseconds 300
        $typedLines = Shot $step $(if ($attempt -eq 1) { "$tag-goto-typed" } else { "$tag-goto-typed$attempt" })
        $typedSeen = Get-JumpFieldText $typedLines
        if ($typedSeen -ne "$physPage") {
            $goNow = Find-Text $typedLines '前往'
            $crop = if ($goNow) { Read-JumpFieldCrop $script:LastPng $goNow } else { $null }
            if ($crop) {
                $field = $crop
                $typedSeen = if ($crop.Alt -eq "$physPage") { $crop.Alt } else { $crop.Text }
            }
        }
        if ($typedSeen -eq "$physPage") { $typedOk = $true; break }
        if ($typedSeen) { Note "跳页框读到 $typedSeen，要的是 $physPage，点回框里整段重敲（第 $attempt 遍）" }
        else { Note "跳页框里的数字这一帧没认出来（第 $attempt 遍），不提交，点回框里重敲" }
    }
    if (-not $typedOk) {
        $lastSeen = if ($typedSeen) { "最后读到 $typedSeen" } else { '框里的数字一直没认出来' }
        Fail $step "跳页框敲了 4 遍都没确认是 $physPage（$lastSeen），没按回车（见 $step-$tag-goto-typed*.png）"
    }
    Send-Keys '{ENTER}'; Start-Sleep -Seconds 1
    $pageNow = $null
    foreach ($try in 1..6) {
        Start-Sleep -Seconds 2
        Hover $midX $hoverY; Start-Sleep -Milliseconds 700
        $null = Shot $step "$tag-jumped"
        $c = Get-HudCounter $script:LastPng
        if ($c) { $pageNow = "$($c.Cur)" }
        if ($pageNow -eq "$physPage") { break }
    }
    if ($pageNow -ne "$physPage") { Fail $step "页码没跳到 $physPage（现在 $pageNow，见 $step-$tag-jumped.png）" }
}

function Click-Step2ImportPoint([int]$screenX, [int]$screenY) {
    $point = New-Object WD+POINT
    $point.X = $screenX; $point.Y = $screenY
    [WD]::SetCursorPos($screenX, $screenY) | Out-Null
    Start-Sleep -Milliseconds 120
    Assert-OurForeground
    $pidAtPoint = 0
    [void][WD]::GetWindowThreadProcessId([WD]::WindowFromPoint($point), [ref]$pidAtPoint)
    if ($pidAtPoint -ne $script:AppPid) { throw 'Import target does not belong to the smoke client' }
    [WD]::mouse_event(2, 0, 0, 0, [IntPtr]::Zero)
    try { Start-Sleep -Milliseconds 60 } finally { [WD]::mouse_event(4, 0, 0, 0, [IntPtr]::Zero) }
}

function Open-Step2ImportMenu($lines, [int]$attempt) {
    $settings = Find-Text $lines '设置'
    if (-not $settings -or -not (Test-Step2Home $lines)) { return }
    $dpi = [WD]::GetDpiForWindow($script:Win)
    $scale = [Math]::Max(1, $dpi / 96.0)
    # The add control sits just left of Settings. Its popup belongs to a separate
    # window, so keep the same foreground lease until the item has been clicked.
    Invoke-ForegroundStep -InjectsInput -Action {
        $rect = Get-Rect
        $x = $rect.L + $settings.X - [int](62 * $scale)
        $y = $rect.T + $settings.Y
        Click-Step2ImportPoint $x $y
        Start-Sleep -Milliseconds 550
        Assert-OurForeground
        $left = [Math]::Max($rect.L, $x - [int](160 * $scale))
        $top = $y + [int](15 * $scale)
        $height = [Math]::Min($rect.B - $top, [int](160 * $scale))
        if ($height -le 0 -or $left -ge $rect.R) { return }
        $png = Join-Path $OutDir "2-import-menu$attempt.png"
        $image = [Drawing.Bitmap]::new(($rect.R - $left), $height)
        try {
            $graphics = [Drawing.Graphics]::FromImage($image)
            try { $graphics.CopyFromScreen($left, $top, 0, 0, $image.Size) } finally { $graphics.Dispose() }
            $image.Save($png, [Drawing.Imaging.ImageFormat]::Png)
        } finally { $image.Dispose() }
        $menu = @(Read-Screen $png)
        $item = Find-Text $menu '导入文件'
        if ($item -and (Find-Text $menu '新建书单')) { Click-Step2ImportPoint ($left + $item.X) ($top + $item.Y) }
    }
}

$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'; $env:GRADLE_USER_HOME = 'D:\agent\gradle-home'; $env:Path = "$env:JAVA_HOME\bin;$env:Path"
$lib = Join-Path $OutDir 'bookshelf'; $client = Join-Path $OutDir 'client'; $incidents = Join-Path $OutDir 'incidents'
New-Item -ItemType Directory -Force $lib, $client, $incidents | Out-Null
$env:COACH_LIBRARY_DIR = $lib; $env:COACH_CLIENT_DIR = $client; $env:COACH_INCIDENT_DIR = $incidents; $env:COACH_TOKEN_FILE = (Join-Path $OutDir 'unused-token')
$env:COACH_URL = if ($Url) { $Url } else { 'http://127.0.0.1:18999' }
if ($InstalledExe) {
    $InstalledExe = (Resolve-Path -LiteralPath $InstalledExe).Path
    if ([IO.Path]::GetFileName($InstalledExe) -ne 'LearningCoach.exe' -or -not (Test-Path -LiteralPath $InstalledExe -PathType Leaf)) {
        throw '-InstalledExe must point to an installed LearningCoach.exe file'
    }
    $env:COACH_COURSE = $null
    $isolatedJavaHome = Join-Path $OutDir 'java-home'
    New-Item -ItemType Directory -Force -Path $isolatedJavaHome | Out-Null
    $env:JAVA_TOOL_OPTIONS = "-Duser.home=`"$isolatedJavaHome`""
}
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
if (-not $Runtime -and -not $InstalledExe) {
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
            streaming = $preflight.Streaming
            streamingSummary = $preflight.StreamingSummary
            idleSeconds = $preflight.IdleSeconds
            driverInputMatched = $preflight.DriverInputMatched
            foreground = $preflight.Foreground
            time = (Get-Date).ToUniversalTime().ToString('o')
        } | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 (Join-Path $OutDir 'environment-dirty.json')
        Note "ENVIRONMENT_DIRTY：$($preflight.Reason)"
        $global:LASTEXITCODE = 2
        exit 2
    }
    $streamNote = if ($preflight.Streaming) { "Apollo/Moonlight 正在串流（仅作证据：$($preflight.Streaming.Summary)）" } else { 'Apollo 无活动串流' }
    Note "预检通过：$streamNote（GetLastInputInfo 空闲 $($preflight.IdleSeconds) 秒，driverInputMatched=$($preflight.DriverInputMatched)，仅作证据）"
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
    if ($InstalledExe) {
        $launchSw = [Diagnostics.Stopwatch]::StartNew()
        $gradle = Start-Process -FilePath $InstalledExe -WorkingDirectory (Split-Path -Parent $InstalledExe) -PassThru
        $script:InstalledClientPid = $gradle.Id
        $script:InstalledClientStartTicks = Get-TrackedProcessStartTicks $gradle
        $script:InstalledClientPath = $InstalledExe
        Note "启动安装版 LearningCoach（$((Get-Item -LiteralPath $InstalledExe).VersionInfo.FileVersion)）"
    } elseif ($Runtime) {
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
    $deadline = (Get-Date).AddSeconds(30)
    do {
        $homeEntry = Get-HomeImportLabel (Shot 1 'start')
        if (-not $homeEntry) { Start-Sleep -Seconds 2 }
    } while (-not $homeEntry -and (Get-Date) -lt $deadline)
    if (-not $homeEntry) {
        # 服务端拒过一次（配对被吊销 / 401）以后客户端会退回登录页：那一帧是「登录 LearningCoach /
        # Sable 账号登录 / 没有 sable 账号？用配对码」，首页的一个锚点都没有。这不是首页认错了，
        # 是这台机器得重新配对——照实报出来，别让人再去调首页的选择器（11-21 19:55 那轮就这样）。
        if ((Find-Text $script:Last '登录') -or (Find-Text $script:Last '配对码')) {
            Fail 1 '客户端停在登录页：窗口里是「登录 LearningCoach / 没有 sable 账号？用配对码」，没有 2.0 首页可认（要先在这台机器上配对）'
        }
        Fail 1 '窗口出来了，但 30 秒内没读出首页（放进一本书 / 导入文件 / 单读一本书 / 为一门课备考）'
    }
    if ($launchSw) { Note ("起窗完成：启动后 {0:N2} 秒" -f $launchSw.Elapsed.TotalSeconds) }
    Pass 1 '客户端窗口出现，2.0 首页可见'

    # ② 导入一本书
    # 云端同步可以在步骤 1 之后替换整个首页。每次尝试都按当前帧选入口；
    # 首题标记以真正打开选书框的入口为准，不沿用步骤 1 的快照。
    $firstOpen = $false; $opened = $false
    foreach ($attempt in 1..4) {
        $homeFrame = Shot 2 "import-home$attempt"
        $entry = Get-HomeImportLabel $homeFrame
        if (-not $entry) { Start-Sleep -Seconds 2; continue }
        $isFirst = $entry -eq '放进一本书'
        if ($InstalledExe -and -not $isFirst) {
            Open-Step2ImportMenu $homeFrame $attempt
        } else {
            $target = if ($isFirst) { Find-Exact $homeFrame $entry } else { Find-Text $homeFrame $entry }
            if (-not $target) { Start-Sleep -Seconds 2; continue }
            $pressed = Click-At $target.X $target.Y -BeforePress {
                $current = Shot 2 "import-confirm$attempt"
                $same = if ($isFirst) { Find-Exact $current $entry } else { Find-Text $current $entry }
                return [bool]($same -and (Get-HomeImportLabel $current) -eq $entry -and
                    $target.X -ge $same.X1 -and $target.X -le $same.X2 -and
                    $target.Y -ge $same.Y1 -and $target.Y -le $same.Y2)
            }
            if (-not $pressed) { continue }
        }
        $until = (Get-Date).AddSeconds(6)
        do {
            if ((Find-AppWindowByTitle '导入文件') -ne [IntPtr]::Zero) { $opened = $true; $firstOpen = $isFirst; break }
            Start-Sleep -Milliseconds 400
        } while ((Get-Date) -lt $until)
        if ($opened) { break }
    }
    if (-not $opened) { Fail 2 '当前首页入口未打开原生选书对话框（见 2-import-home*.png / 2-import-confirm*.png / 2-import-menu*.png）' }
    if (-not (Choose-File '导入文件' $Book)) { Fail 2 $script:ChooseFileWhy }
    if ($firstOpen) { Complete-FirstTaste }
    # 导入通常先打开阅读器。还要回首页验证本地书卡，课程和书单可能把它推到首屏以下。
    $r = Get-Rect; $winW = [int]($r.R - $r.L); $winH = [int]($r.B - $r.T)
    $hoverY = $winH - 10
    # 画布的上缘从客户区算，客户区上面还压着窗口标题栏；指针得真落进画布顶上那条 48dp 带子里
    # 才会发出 Move、把顶栏浮出来，落在标题栏上是白等。
    $topY = 60
    $midX = [int]($winW / 2); $midY = [int]($winH / 2)
    $readerPage = $null
    $shelfHit = $null
    $deadline = (Get-Date).AddSeconds(150)
    $tries = 0
    while (-not $readerPage -and -not $shelfHit -and (Get-Date) -lt $deadline) {
        $tries++
        Hover $midX $hoverY; Start-Sleep -Milliseconds 600
        $lines = Shot 2 $(if ($tries -eq 1) { 'imported' } else { "imported$tries" })
        # 只有云端书卡时，顶栏导入仍可能触发首次做题引导；以选书后的真实页面为准。
        if (-not $firstOpen -and ((Find-FirstTasteTry $lines) -or (Find-FirstTasteAllow $lines) -or
            (Find-Text $lines '选一个答案') -or (Find-Text $lines '正在用开头几章') -or
            (Find-Exact $lines '看讲解') -or (Find-Exact $lines '再放一次') -or
            (Find-Exact $lines '再试一次') -or (Find-Exact $lines '先翻开这本书'))) {
            $firstOpen = $true
            Complete-FirstTaste
            $deadline = (Get-Date).AddSeconds(150)
            continue
        }
        $c = Get-HudCounter $script:LastPng
        if ($c) { $readerPage = $c.Text } elseif (-not $firstOpen) { $shelfHit = Find-Step2HomeBook $lines $BookTitle }
        if (-not $readerPage -and -not $shelfHit) { Start-Sleep -Seconds 2 }
    }
    if (-not $readerPage -and -not $shelfHit) {
        if ($firstOpen) { Fail 2 '首题讲解后 150 秒内未确认阅读器底栏页码，见 2-imported*.png' }
        Fail 2 '选了书但 150 秒内既没进阅读器，书架里也没出现这本书'
    }
    $row = $shelfHit
    if ($readerPage) {
        $homeFrame = $null
        foreach ($try in 1..3) {
            $frame = Shot 2 "before-back$try"
            if (Test-Step2Home $frame) { $homeFrame = $frame; break }
            $topLines = Show-ReaderTop 2 "back$try" 3
            $back = Find-Step2ReaderBack $topLines $winW $winH
            if (-not $back) { Start-Sleep -Seconds 1; continue }
            Click-At ([int]$back.X) ([int]$back.Y); Start-Sleep -Milliseconds 1200
            $frame = Shot 2 "after-back$try"
            if (Test-Step2Home $frame) { $homeFrame = $frame; break }
        }
        if ($homeFrame) {
            $row = Find-Step2HomeBook $homeFrame $BookTitle
            foreach ($scroll in 1..8) {
                if ($row) { break }
                Scroll-At $midX ([int]($winH * 0.75)) -2
                Start-Sleep -Milliseconds 500
                $row = Find-Step2HomeBook (Shot 2 "shelf-scroll$scroll") $BookTitle
            }
        }
    }
    $v2 = Resolve-Step2HomeVerdict $row $readerPage
    if ($v2.Fail) { Fail 2 $v2.Fail }
    Pass 2 $v2.Pass

    # ③ 翻页
    # 从刚验证过的目标书卡回到阅读器。焦点卡的「继续」也可能指向别的书或书单。
    $page0 = $null
    $deadline = (Get-Date).AddSeconds(150)
    $tries = 0
    # 进没进阅读器不靠按钮字（箭头认不出来），只认底栏那条页号：读出来就是在阅读器里了。
    while (-not $page0 -and (Get-Date) -lt $deadline) {
        $tries++
        $lines = Shot 3 $(if ($tries -eq 1) { 'find' } else { "find$tries" })
        $c = Get-HudCounter $script:LastPng
        if (-not $c) { $c = Get-PageCounter $lines $script:LastPng }
        if ($c) { $page0 = $c.Text; break }
        $bookCard = Find-Step2HomeBook $lines $BookTitle
        if ($bookCard) {
            $pressed = Click-At $bookCard.Click.X $bookCard.Click.Y; Start-Sleep -Milliseconds 1500
            Note "第 $tries 次点了目标书卡，按下=$pressed"
        }
        Hover $midX $hoverY; Start-Sleep -Milliseconds 700
        $lines = Shot 3 $(if ($tries -eq 1) { 'reader' } else { "reader$tries" })
        $c0 = Get-HudCounter $script:LastPng
        if (-not $c0) { $c0 = Get-PageCounter $lines $script:LastPng }
        if ($c0) { $page0 = $c0.Text; break }
        # 还在首页说明这一下没生效，把认到的字记进日志，下一轮重定位再来。
        if ($tries -le 3) { Note ('这一帧读到：' + (($lines | ForEach-Object { $_.Text }) -join ' / ')) }
    }
    if (-not $page0) { Fail 3 '首页目标书卡未打开阅读器，或底栏读不到页号（见 3-find*.png / 3-reader*.png）' }
    # 2.0 的翻页键只在阅读区真拿到焦点时才生效，先点正文中间把焦点交给页面，再按 →。
    Click-At $midX $midY; Start-Sleep -Milliseconds 400
    $page1 = $page0
    foreach ($try in 1..4) {
        Send-Keys '{RIGHT}'; Start-Sleep -Seconds 2
        Hover $midX $hoverY; Start-Sleep -Milliseconds 700
        $after = Shot 3 "page-after$try"
        $c1 = Get-HudCounter $script:LastPng
        if (-not $c1) { $c1 = Get-PageCounter $after $script:LastPng }
        if ($c1) { $page1 = $c1.Text }
        if ($page1 -ne $page0) { break }
        Click-At $midX $midY; Start-Sleep -Milliseconds 300
    }
    if ($page0 -eq $page1) { Fail 3 "按了 → 页码没变（前 $page0，后 $page1，见 3-page-after*.png）" }
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
    Jump-VerifiedReaderPage 4 $physPage $midX $hoverY 'initial'
    # V 在阅读器内切换原页和文字。避开 OCR 未识别的菜单和会返回首页的 Esc。
    Hover $midX $hoverY; Start-Sleep -Milliseconds 400
    Send-Keys 'v'; Start-Sleep -Seconds 1
    Jump-VerifiedReaderPage 4 $physPage $midX $hoverY 'after-mode'
    $null = Shot 4 'text-mode'
    # 文字视图一屏只放得下一页的一部分：这一页开头是页眉，可信选段在这页下半部分，常在下一张纸上。
    $words = (($sel.selection -replace '[^A-Za-z ]', ' ').Trim() -split '\s+' | Select-Object -First 4) -join ''
    $paraLine = $null; $lines = @()
    # 文字视图按窗口大小排成一张张「纸」（BookPageView 的 laid page），跳到第 N 页落在的那张纸可能是「第 N-1–N 页」，
    # 选段在下一张纸上。翻纸只用 →：滚轮在这里时灵时不灵——11-23 上午几轮一格都滚不动，14:41 起一格就越过
    # 「第 154 页」那张纸直接到「tract nurses…」，把选段整段跳过去；→ 每轮都正好落在下一张纸上。
    # → 后新纸偶尔还没画出来（14:39 那轮），下一帧跟按前一样：先等一帧再认，别连按两次跳过头。
    # 连按 4 次 → 还没认到才退回滚轮一格一格找。
    $nextSheets = 0
    foreach ($pass in 1..2) {
        $prevFrame = ''; $lastAct = ''
        foreach ($round in 1..10) {
            if ($round -eq 1) { $lines = Shot 4 'blocks' } else { $lines = Shot 4 "blocks-down$pass-$round" }
            foreach ($l in $lines) { if ($l.Text -replace '[^A-Za-z]', '' -like "*$words*") { $paraLine = $l; break } }
            if ($paraLine) { break }
            $frame = (@($lines | Where-Object { $_.Text -match '[A-Za-z]{4}' } | ForEach-Object { $_.Text }) -join '|')
            if ($lastAct -eq 'next' -and $frame -eq $prevFrame) {
                Start-Sleep -Milliseconds 1200; $lastAct = 'wait'
            } elseif ($nextSheets -lt 4) {
                $nextSheets++
                Note "步骤4 这张纸上没有选段，按 → 翻到下一张纸找（第 $nextSheets 次）"
                Hover $midX ([int]($winH * 0.5)); Send-Keys '{RIGHT}'; Start-Sleep -Milliseconds 1200
                $lastAct = 'next'
            } else {
                Scroll-At 600 400 -1; Start-Sleep -Milliseconds 700
                $lastAct = 'wheel'
            }
            $prevFrame = $frame
        }
        if ($paraLine -or $pass -eq 2) { break }
        # V 是切换：要是按之前本来就在文字视图，上面那一下把它切回了原页，再按一次切回来。
        Note '按 V 后没找到段落，再按一次 V 再找'
        Send-Keys 'v'; Start-Sleep -Seconds 1
        Jump-VerifiedReaderPage 4 $physPage $midX $hoverY 'retry-mode'
        $nextSheets = 0
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
        # 2.0 的浮条是「讲讲 举个例子 问… 笔记 ×」，没有「换个说法」了：认「举个例子」「笔记」都算浮条出来了。
        $toolbar = Await-Text 4 "selected$drag" @('举个例子', '笔记') 8
        if ($toolbar) { break }
    }
    if (-not $toolbar) { Fail 4 '拖选这一行后没有浮出选中工具条（见 4-selected3.png）' }
    # 「讲讲」和随后的「允许并讲解」原来各只点一下：四路交锁时前台被别路接走，这两下都会落空。
    # 11-21 那轮 L1/L4 点了「讲讲」等于没点；10:47 那一轮点完浮条直接消失、什么都没有——落点偏了半格，
    # 那一下算点在浮条外面，浮条被点收起来了。所以每一下都认状态，没起来就重新拖选把浮条叫回来再点。
    $started = $false
    # 浮条钉在阅读区顶端，但它的 top 跟着顶栏走：顶栏没浮出来时 14dp，浮出来时 54dp（TextbookReader.kt:1231）。
    # 鼠标一进顶边 48dp 顶栏就浮出来、浮条同时下移 40dp——照 14dp 那一帧算好落点再挪过去，按下时它已经
    # 挪到下面去了，那一下正好落在浮条底下的正文上，选中被清掉（11:01 那一轮三下都是这个结果）。
    # 所以先把指针停在顶边把顶栏钉出来，等它稳住，再照这一帧（浮条已在 54dp）定位去点；
    # 54dp 还在「移回顶边才收顶栏」的迟滞区间里（>96dp 才收，TextbookReader.kt:801），点完不会又翻回去。
    foreach ($try in 1..3) {
        if ($try -gt 1) {
            Drag-Between ($box.X1 + 4) $box.Y ($box.X2 - 4) $box.Y; Start-Sleep -Milliseconds 900
        }
        Hover $midX $topY; Start-Sleep -Milliseconds 900
        $null = Shot 4 "confirm$try"
        $btn = Find-ZoomText $script:LastPng 0 200 '讲讲' 3
        if (-not $btn) { $btn = Find-OnRow $script:Last '讲讲' $toolbar }
        if (-not $btn) { Note "第 4 步第 $try 次没找到「讲讲」，重新拖选后再找"; continue }
        Click-At $btn.X $btn.Y
        $stop = (Get-Date).AddSeconds(8)
        while ((Get-Date) -lt $stop) {
            Start-Sleep -Milliseconds 900
            if (Test-ExplainStarted (Shot 4 "confirm${try}done")) { $started = $true; break }
        }
        if ($started) { break }
        Note "第 4 步第 $try 次点「讲讲」后伴读抽屉没打开，重定位再点"
    }
    # 同意框有时要十几秒才弹（后台先把这段原文送出去），点完状态没变也再宽限一次，别比原来等得还短。
    if (-not $started) { $started = [bool](Await-Text 4 'explain-late' @('允许这本书讲解', '还没允许讲解', '允许并讲解', '这段原文会发到哪里', '不允许', '正在整理', '引文') 15) }
    if (-not $started) { Fail 4 '点了三次「讲讲」伴读抽屉都没打开（见 4-confirm*.png）' }
    # 这一帧是不是同意框：按钮标签会被 OCR 认错字（11-21 05:58 L1 在这一帧读成「允许并讲鱈」，
    # 只认「允许并讲解」就整段跳过、后面空等 120 秒），所以标题和「不允许」也认。
    if (Test-ConsentUp (Shot 4 'consent')) {
        # 这本书第一次讲解要先同意；框没关就是没点上，按新一帧重定位再点（开发验收允许点）。
        # 落点用 Find-ConsentAllow（兼容「允许这本书讲解」与旧版「允许并讲解」），
        # 「关没关」用 Test-ConsentGone（标题和两个按钮都不在），两边都不靠单个整词。
        $allowed = Click-Confirm 4 { param($l) Find-ConsentAllow $l } { param($l) Test-ConsentGone $l } -tries 3 -waitSec 6 -pauseMs 800
        if (-not $allowed) { Fail 4 '同意框里的「允许这本书讲解 / 允许并讲解」点了三次框都没关（见 4-consent*.png）' }
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
            # 2.0 的讲解在伴读抽屉里（窄窗是半屏底部抽屉），正文比抽屉高时「出处」压在下面：
            # 在抽屉的正文区（窗口下半截中间）下滚一次，下一轮再看。
            if ($n -ge 2) {
                $lim = Get-Rect
                Scroll-At ([int](($lim.R - $lim.L) / 2)) ([int](($lim.B - $lim.T) * 0.72)) -3
                Start-Sleep -Milliseconds 700
            }
        }
    }
    if ($outcome -eq 'error') { Fail 4 "服务回了拒绝：$errText" }
    if ($outcome -ne 'ok') { Fail 4 '120 秒内既没有讲解也没有报错' }
    $explainOk = $true
    # 2.0 的追问就在伴读抽屉最底下（CompanionAskPane 的 inline-followup-input + 「发送」），有两个坑：
    # 1) 底栏 HUD 的浮现区是窗口最下面 48px，输入框正好长在那条带上。指针一进去 HUD 就盖住框体下半截；
    #    如果按下那一刻它还在浮现，那一下会落在 HUD 上、框没拿到焦点，粘进去的字全丢——11:18 那轮四张
    #    4-ask-typed*.png 框里都是空的，四枪全栽在这上面。所以先把指针停在框体那一行把 HUD 叫出来，
    #    等它停稳，再照这一帧点框体最上面那几行：按下时布局不动，落点也还在 HUD 上沿之上。
    # 2) 框里的字被 HUD 挡住看不出来（「第 154 页 / 620」正压在框体上），所以粘完把指针挪回中间带让 HUD
    #    收掉，再数框体那块亮像素：字真进去了才按回车，没进去就换个落点重来。
    # 提示语放大 4 倍认得准（3 倍把「推导」读成「推寺」），「发送」也是 4 倍才读得到。
    $askWhy = $null
    try {
        $lim = Get-Rect; $winW = [int]($lim.R - $lim.L); $winH = [int]($lim.B - $lim.T)
        $isWide4 = ($winW -ge 1200)
        $scrollX = if ($isWide4) { [int]($winW * 0.85) } else { [int]($winW / 2) }
        $scrollY = if ($isWide4) { [int]($winH * 0.55) } else { [int]($winH * 0.72) }
        # 阅读区中间带：底抽屉开着时画布只剩上半截，指针落在这里 HUD 一定收回去。
        $midY = [int]($winH * 0.30)
        $fieldBandTop = [Math]::Max(0, $winH - [Math]::Max(130, [int]($winH * 0.22)))
        $field = $null; $fieldIsPlaceholder = $false
        foreach ($k in 0..3) {
            if ($k -gt 0) { Start-Sleep -Milliseconds 700 }
            Hover $midX $midY; Start-Sleep -Milliseconds 700
            $null = Shot 4 "ask-field$k"
            # 先在整帧和底部放大带里找框内占位语（「问这句」「公式推导」），它们就在输入框正中；
            # 找不到再退回框上方的状态提示「输入问题」。
            foreach ($hint in '问这句', '公式推导', '输入问题') {
                $hit = Find-Text $script:Last $hint
                if (-not $hit -or $hit.Y -lt $fieldBandTop) {
                    $hit = Find-ZoomText $script:LastPng $fieldBandTop $winH $hint 4
                }
                if ($hit) {
                    $field = $hit
                    $fieldIsPlaceholder = ($hint -ne '输入问题')
                    break
                }
            }
            if ($field) { break }
        }
        $quoteRow = Find-Text $script:Last '引文'
        $hintRow = Has $script:Last @('先输入', '问这句')
        if (-not $hintRow -and $field) { $hintRow = $field }
        if ($quoteRow) {
            $scrollX = [Math]::Min($winW - 60, [int]$quoteRow.X + 80)
        } elseif ($field) {
            $scrollX = [Math]::Min($winW - 60, [int]$field.X + 80)
        }
        if ($quoteRow -and $hintRow -and $hintRow.Y -gt $quoteRow.Y + 20) {
            $scrollY = [int](($quoteRow.Y + $hintRow.Y) / 2)
            Note "提问回答区滚动点：引文行 $($quoteRow.Y) 与提示行 $($hintRow.Y) 之间，($scrollX,$scrollY)"
        }
        $clickX = if ($field) { [int]$field.X1 + 30 } elseif ($isWide4) { [int]($winW * 0.80) } else { [int]($winW * 0.3) }
        $rowY = if ($field) {
            if ($fieldIsPlaceholder) { [int]$field.Y } else { [int]$field.Y2 + 34 }
        } else { [int]($winH - 46) }
        $send = Find-Text $script:Last '发送'
        if (-not $send -or $send.Y -lt $fieldBandTop) {
            $send = Find-ZoomText $script:LastPng $fieldBandTop $winH '发送' 4
        }
        if ($send) { $rowY = [int]$send.Y }
        if ($field) { Note "追问输入框：提示语在第 $($field.Y) 行（placeholder=$fieldIsPlaceholder），这一行中线 $rowY，落点 x=$clickX" }
        else { Note "抽屉底栏没读到输入框提示语，按 ($clickX,$rowY) 试" }
        $boxX0 = [Math]::Max(0, $clickX - 24)
        $boxX1 = [Math]::Min($winW - 2, $boxX0 + 380)
        $boxY0 = [Math]::Max(0, $rowY - 24)
        # 宽屏 HiDPI 下提示语「问这句的意思…」在输入框上沿，输入正文落在其下方 ~25..110px 处；
        # 像素差检测盒向下覆盖到 $rowY + 120，才能比出探针字与粘贴中文的差异。
        $boxY1 = [Math]::Min($winH - 2, $rowY + 120)
        $emptyPng = $script:LastPng
        $question = '用大白话解释一下，这里的专用人力资本是什么意思？'
        # 标记直接写进问题里，回答轮的标题是「问：<原问题>」，标记出现在那行才算这一问真的发出去了。
        $askText = if ($Marker) { "$Marker $question" } else { $question }
        # 粘贴走不通时的替补：纯 ASCII，只用击键、不碰剪贴板也不碰输入法，标记照样带上。
        $keyText = if ($Marker) { "$Marker what does the quoted sentence mean in plain words" } else { 'what does the quoted sentence mean in plain words' }
        $turn = $null; $cite = $null; $typed = $false
        # 落点相对 $clickX 偏移（宽屏右侧抽屉和窄屏底抽屉都能命中输入框）。
        $spots = @(
            [pscustomobject]@{ X = $clickX; Y = $rowY },
            [pscustomobject]@{ X = [Math]::Min($winW - 40, $clickX + 100); Y = $rowY },
            [pscustomobject]@{ X = [Math]::Min($winW - 40, $clickX + 40); Y = $rowY + 12 },
            [pscustomobject]@{ X = $clickX; Y = [Math]::Max(10, $rowY - 12) }
        )
        $spot = 0
        foreach ($sp in $spots) {
            $spot++
            $spotX = [int]$sp.X; $spotY = [int]$sp.Y
            Click-At $spotX $spotY; Start-Sleep -Milliseconds 500
            # 六个字母探焦点：对比点击前空框与敲完后的像素变化，兼容深浅主题与占位提示语。
            Send-Keys 'zzzzzz'; Start-Sleep -Milliseconds 400
            Hover $midX $midY; Start-Sleep -Milliseconds 700
            $null = Shot 4 "ask-click$spot"
            $probePng = $script:LastPng
            $probeDiff = Get-FieldDiff $emptyPng $probePng $boxX0 $boxY0 $boxX1 $boxY1
            $probe = if ($field) { [Math]::Max($probeDiff, (Get-FieldInk $probePng $field $rowY)) } else { $probeDiff }
            Note "第 $spot 次点 ($spotX,$spotY)：探针字后框内变化像素 diff=$probeDiff ink=$probe"
            if ($probeDiff -lt 40) {
                Send-Keys '^a'; Send-Keys '{BACKSPACE}'
                continue
            }
            # 焦点进来了：先清掉探针字，再整句粘；粘贴不成（剪贴板那条路失败）就改用击键打 ASCII 那句。
            Send-Keys '^a'; Send-Keys '{BACKSPACE}'; Start-Sleep -Milliseconds 300
            $textIn = $false
            Paste-Text " $askText"; Start-Sleep -Milliseconds 900
            Hover $midX $midY; Start-Sleep -Milliseconds 700
            $null = Shot 4 "ask-typed$spot"
            $typedPng = $script:LastPng
            $typedDiffEmpty = Get-FieldDiff $emptyPng $typedPng $boxX0 $boxY0 $boxX1 $boxY1
            $typedDiffProbe = Get-FieldDiff $probePng $typedPng $boxX0 $boxY0 $boxX1 $boxY1
            $ink = if ($field) { Get-FieldInk $typedPng $field $rowY } else { $typedDiffEmpty }
            Note "第 $spot 次粘字：diffEmpty=$typedDiffEmpty diffProbe=$typedDiffProbe ink=$ink"
            if ($typedDiffEmpty -ge 80 -and $typedDiffProbe -ge 60) { $textIn = $true }
            if (-not $textIn) {
                Send-Keys '^a'; Send-Keys '{BACKSPACE}'
                Send-Keys $keyText; Start-Sleep -Milliseconds 900
                Hover $midX $midY; Start-Sleep -Milliseconds 700
                $null = Shot 4 "ask-keyed$spot"
                $typedPng = $script:LastPng
                $typedDiffEmpty = Get-FieldDiff $emptyPng $typedPng $boxX0 $boxY0 $boxX1 $boxY1
                $typedDiffProbe = Get-FieldDiff $probePng $typedPng $boxX0 $boxY0 $boxX1 $boxY1
                $ink = if ($field) { Get-FieldInk $typedPng $field $rowY } else { $typedDiffEmpty }
                Note "第 $spot 次击键：diffEmpty=$typedDiffEmpty diffProbe=$typedDiffProbe ink=$ink"
                if ($typedDiffEmpty -ge 80 -and $typedDiffProbe -ge 60) { $textIn = $true; $askText = $keyText }
            }
            if (-not $textIn) { continue }
            $typed = $true
            # 抽屉里已经有一轮（选段讲解）的「出处」：记下它，兜底路径用它排除「把上一轮的出处当这一问的」。
            $preCite = $script:Last | Where-Object { $_.Text -match '出处.*第\s*\d+\s*[页頁贞貞虫]' } | Select-Object -Last 1
            # 字在框里了：回车提交（CompanionAskPane 的 onPreviewKeyEvent Enter）。提交后 draft 会被清空，
            # 所以框内像素变回空框同样算这一下发出去了，交给下面那段长等待认回答。
            $sent = $false
            foreach ($press in 1..2) {
                Send-Keys '{ENTER}'; Start-Sleep -Milliseconds 900
                $wait = (Get-Date).AddSeconds(6)
                while ((Get-Date) -lt $wait -and -not $sent) {
                    Start-Sleep -Milliseconds 900
                    $lines = Shot 4 "ask-sent$spot-$press"
                    if (Has $lines @('正在整理')) { $sent = $true; $turn = 'sent'; break }
                    $label = Find-AskTurnLabel $lines $Marker
                    if ($label) { $sent = $true; $turn = $label; break }
                }
                if ($sent) { break }
                $afterDiffTyped = Get-FieldDiff $typedPng $script:LastPng $boxX0 $boxY0 $boxX1 $boxY1
                if ($afterDiffTyped -ge 80) { $sent = $true; break }
            }
            if ($sent) { Note "追问落点：抽屉底栏 ($spotX,$spotY)"; break }
            Send-Keys '^a'; Send-Keys '{BACKSPACE}'
            Note "第 $spot 次回车后没看到「问：…」标题、框里字也没清，换个落点再粘"
        }
        if (-not $typed) {
            # 点不到框就分不清是「输入框不吃点击」还是「整个抽屉收不到点击」，先点一下「批注」页签：
            # 内容换成批注面板（认「写批注」/「导出笔记」）就说明点击到了抽屉里，再点回「提问」（认「引文」）。
            $tabNotes = Find-Text $script:Last '批注'
            if ($tabNotes -and $tabNotes.Y -gt 270 -and $tabNotes.Y -lt 340) {
                Click-At ([int]$tabNotes.X) ([int]$tabNotes.Y); Start-Sleep -Milliseconds 900
                $probe = Shot 4 'ask-probe-notes'
                Note ("抽屉页签探针：点「批注」后 " + $(if (Has $probe @('写批注', '导出笔记')) { '内容换成了批注面板（点击通到抽屉里）' } else { '内容没换（抽屉里的点击也不生效）' }))
                $tabAsk = Find-Text $probe '提问'
                if ($tabAsk) { Click-At ([int]$tabAsk.X) ([int]$tabAsk.Y); Start-Sleep -Milliseconds 900; $null = Shot 4 'ask-probe-back' }
            } else { Note '抽屉页签探针：没读到「批注」页签，跳过' }
            throw '追问框点了四次都没拿到焦点，粘不进字（见 4-ask-click*.png / 4-ask-typed*.png / 4-ask-probe-*.png）'
        }
        # 抽屉正文区比一屏矮：新加的「问：…」和它的回答追加在下面，靠滚动才能看全，
        # 一格一格往下找，找到标题就停；走到第 9 格还没出现说明可能滚过头，再往回找一轮。
        # 见过「正在整理…」以后回答只会在下面追加，往回滚等于把新内容推出视野，所以那时只往下。
        # #599 以后小窗（752×552）回答区只剩 30 多 px 高，回答卡末尾「出处」下面还有约 30px 留白：
        # 滚到底时视野里只有那段留白，出处永远差一点露不出来（11-23 13:29 那轮回答 3 秒就回来了，
        # 脚本却在底部空等 3 分钟）。所以改成「从底往上扫」：新一问总在对话最底下，
        # 先滚到底（还在「正在整理…」就停在底部等），然后每次往上两格（一格约 10px，不会跳过一整行），
        # 往上遇到的第一份出处就是最新一轮的；再往上见到带标记的「问：…」标题，才确认这一轮是这一问。
        # 扫到顶都没见标题（回答还没追加进来，或者看到的是上一轮），回到底部重来。
        $i = 0; $sweeps = 0; $upSteps = 0; $sawLoading = $false; $labelSeen = $false
        $newestCite = $null; $prevSig = $null; $sameFrames = 0
        $deadline = (Get-Date).AddSeconds(180)
        Scroll-AskBottom $scrollX $scrollY
        while ((Get-Date) -lt $deadline -and -not $cite) {
            Start-Sleep -Milliseconds $(if ($upSteps -gt 0) { 400 } else { 2000 }); $i++
            $lines = Shot 4 "ask-wait$i"
            $allow = Has $lines @('允许这本书讲解', '允许并讲解')
            if ($allow) { Click-At $allow.X $allow.Y; continue }
            $serviceError = Get-PassageServiceError $lines
            if ($serviceError) { throw "服务回了拒绝：$serviceError" }
            $bad = $lines | Where-Object { $_.Text -match '无法回答|不能回答|尚未接通|暂时读不到回答|选中追问格式不对|无法确认|无氵去确认' } | Select-Object -First 1
            if ($bad) { throw "提问被拒：$($bad.Text)" }
            # 回答失败时对话底下是一行说明加「重试」（inline-explain-error），把同一行的说明带出来。
            $retry = Find-Text $lines '重试'
            if ($retry -and $retry.Y -gt 330 -and $retry.Y -lt 470) {
                $why = ($lines | Where-Object { [Math]::Abs((Get-LineBox $_).Y - $retry.Y) -lt 12 -and $_.Text -notmatch '^\s*重试\s*$' } | ForEach-Object Text) -join ' '
                throw "提问出错（底下出现「重试」）：$why"
            }
            if (Has $lines @('正在整理')) {
                # 还在等回答：「正在整理…」就挂在对话最底下，留在底部等它变成回答。
                $sawLoading = $true; $newestCite = $null; $upSteps = 0
                Scroll-AskBottom $scrollX $scrollY
                continue
            }
            $label = Find-AskTurnLabel $lines $Marker
            $cites = @($lines | Where-Object { $_.Text -match '出处.*第\s*\d+\s*[页頁贞貞虫]' })
            if ($label) {
                $labelSeen = $true; $turn = $label
                $tb = Get-LineBox $label
                # 同框时取标题下面最近的那份；不同框时用往上扫路上先碰到的那份（它在标题下面、属于这一轮）。
                $below = $cites | Where-Object { (Get-LineBox $_).Y -gt $tb.Y } | Select-Object -First 1
                if ($below) { $cite = $below; break }
                if ($newestCite) { $cite = $newestCite; break }
                # 标题见到了但路上没碰到出处（回答里本来就没有出处时也会这样）：回底部再扫一遍。
                Note "提问：见到本问标题，但它下面还没认到出处，回底部再扫"
                $upSteps = 0; $sweeps++
                Scroll-AskBottom $scrollX $scrollY
                continue
            }
            if ($cites.Count -gt 0 -and -not $newestCite) { $newestCite = $cites[-1] }
            # 回答区那条横带的像素和上一帧一模一样（往上滚了却没动），连着三次就当扫到顶了。
            # 不能比 OCR 文本：一格只走几 px，同一行字会在视野里停好几步，文本相同不代表没动（11-23 13:44 那轮误判过）。
            $sig = Get-BandSig $script:LastPng ($scrollY - 12) ($scrollY + 12)
            if ($upSteps -gt 0 -and $sig -eq $prevSig) { $sameFrames++ } else { $sameFrames = 0 }
            $prevSig = $sig
            if ($sameFrames -ge 3 -or $upSteps -ge 80) {
                $sweeps++
                Note "提问：第 $sweeps 遍从底往上扫到顶（$upSteps 步）没见到本问标题，回底部重来"
                $upSteps = 0; $newestCite = $null; $sameFrames = 0; $prevSig = $null
                Scroll-AskBottom $scrollX $scrollY
                continue
            }
            Scroll-At $scrollX $scrollY 2; $upSteps++
        }
        if (-not $cite) { throw "问题发出去了，但 180 秒内没等到它自己的「出处 · 第 N 页」（标题 $labelSeen，加载态 $sawLoading，扫了 $sweeps 遍）" }
        Note "提问的回答出处：$($cite.Text)"
    } catch { if ("$_" -like 'DESKTOP_BUSY*' -or "$_" -like 'WIN_DRIVE_FOREGROUND_CONTENTION*') { throw }; $askWhy = "$_" }

    # ⑦ 点回答里的「出处」：2.0 在窄窗是半屏底部抽屉，点出处只换页（只有窄浮层模式才顺手收起抽屉），
    # 所以按出处那一行自己写的页码核对跳页，再点顶栏「收起」把抽屉关掉，确认关掉后还停在同一页。
    # 这几下在四路交锁时都可能落空（点了但窗口没处理），所以每下都认状态；
    # 失败不在这里 throw——后面几步不依赖它，留到最后统一报，免得少记几步通过。
    if (-not $scrollX) { $lr = Get-Rect; $scrollX = [int](($lr.R - $lr.L) / 2); $scrollY = [int](($lr.B - $lr.T) * 0.72) }
    $cardOk = $null
    $cite = $null
    # #599 以后小窗回答区只有 30 多 px 高，往下滚 3 格×7 次只走 150px 左右，到不了最新那轮的「出处」
    # （11-23 14:10 那轮七帧都停在线程顶上的引文和第一段讲解）。先真滚到底，再一小格一小格往上找：
    # 最新一轮回答在最底下，往上遇到的第一颗带页码的「出处」就是它。
    Scroll-AskBottom $scrollX $scrollY
    foreach ($k in 0..14) {
        if ($k -gt 0) { Scroll-At $scrollX $scrollY 2; Start-Sleep -Milliseconds 500 }
        $o = Await-Text 7 'cite' @('出处') 1
        if ($o) { $cite = (@(Find-All $script:Last '出处') | Select-Object -Last 1); break }
    }
    if (-not $cite) { $cardOk = '伴读抽屉里找不到「出处」链接（见 4-result*.png）' }
    else {
        $citeLine = Find-CiteLine $script:Last
        $citePage = Get-CitePageNumber $(if ($citeLine) { $citeLine.Text } else { '' })
        # 四路交锁时这一下也会被吞：点完底栏还停在第 33 页（11-21 04:46 L3 就这样）。
        # 点的必须是解析出 $citePage 的那一行。同一帧里更靠下的另一行「出处」可能是别的页
        # （第 154 页上面、第 153 页下面），按最靠下的「出处」点就会跳错，页码却仍按第一次的数字核对。
        # 重试只重找同一页码的那一行，不改 $citePage，也不改去点别的出处。最多 3 次。
        # 出处那行解析不出页码时一下都不点：没有目标页，点了也无从核对（过去会跳过整段断言，见 20:05 评审）。
        $c7 = $null
        if ($citePage) {
            foreach ($try in 1..3) {
                $pageLine = Find-CiteLineForPage $script:Last $citePage
                if (-not $pageLine) {
                    Note "第 $try 次这一帧没有写着第 $citePage 页的「出处」，不再改点别的出处"
                    break
                }
                $citeLine = $pageLine
                $pt = Get-LineClick $pageLine
                $pressed = Click-At $pt.X $pt.Y -BeforePress {
                    [bool](Find-CiteLineForPage (Shot 7 'cite-guard') $citePage)
                }
                if (-not $pressed) { Note "第 $try 次按下前那一帧已经没有第 $citePage 页的「出处」了，不再点" }
                Start-Sleep -Seconds 2
                Hover $midX $hoverY; Start-Sleep -Milliseconds 800
                $null = Shot 7 $(if ($try -eq 1) { 'cite' } else { "cite$try" })
                $c7 = Get-HudCounter $script:LastPng
                if (-not $pressed -or -not $c7 -or "$($c7.Cur)" -eq "$citePage") { break }
                Note "第 $try 次点第 $citePage 页的「出处」后停在第 $($c7.Cur) 页，按同一页码重定位再点"
            }
        }
        # 抽屉半屏高，常常正好压住底栏：点完读不出页号是常态，不算没跳页，关掉抽屉再认一次。
        # 只有两种情形当场算失败：出处那行根本没页码；或者读到了页号、但跟出处对不上。
        $openVerdict = Resolve-Step7PageVerdict $citePage $(if ($c7) { "$($c7.Cur)" } else { $null }) $(if ($citeLine) { $citeLine.Text } else { '' })
        $cardOk = if ($c7 -or -not $citePage) { $openVerdict } else { $null }
        if ($cardOk) { Note "第 7 步：出处写第 $citePage 页，点完底栏读 $(if ($c7) { '第 ' + $c7.Cur + ' 页' } else { '不出页号' })——$cardOk" }
        else { Note "第 7 步：出处写第 $citePage 页，点完底栏读 $(if ($c7) { '第 ' + $c7.Cur + ' 页' } else { '不出页号' })，关掉抽屉再核" }
        if (-not $cardOk) {
            # 抽屉半屏高、盖着正文：把它关掉再看页码没被带走。三种关法按把握从大到小试，每次点完都认状态：
            #   a) 顶栏那颗「收起」= immersive-companion-toggle（ImmersiveReaderChrome 252-266 行，companionOpen 时
            #      窄窗写「收起」、宽窗写「收起伴读」）。OCR 认不出这两个字（21 日六帧全缺），但顶栏那一行
            #      「原页 文字 查找 Aa 收起 ⋯」的 y 认得出来，按右沿内固定偏移点：752 宽窗口里字心在 x=648。
            #   b) 抽屉头最右的「×」（testTag companion-close，头行 padding 12dp、按钮 contentPadding 6dp），
            #      752 宽窗口里字心实测在 x=702、跟页签同一行。
            #   c) 阅读器的 Key.L 是伴读开关（TextbookReader 的 onPreviewKeyEvent）。按下前先切到「讲解」页签：
            #      页签一变 LaunchedEffect(companionOpen, companionTab) 就把 askInputFocused 清掉，L 才进得了阅读器，
            #      否则会敲进问句输入框。Esc 不行——2.0 的 Esc 被外壳接走（TextbookWorkspace 直接 returnFromReader）。
            $closed = $false
            foreach ($try in 1..6) {
                Hover $midX $topY; Start-Sleep -Milliseconds 600
                $null = Shot 7 "close$try"
                $lines7 = $script:Last
                $drawer7 = Has $lines7 @('提问', '批注', '讲解')
                $bar = Has $lines7 @('原页', '文字', '查找', 'Aa')
                # #599 起顶栏只剩「书架 / 目录 / 书名 / 收起 / ⋯」，原页/文字/查找收进了「⋯」，上面那组认不到；
                # 「收起」本身读得到就直接点它。
                $fold7 = Find-Text $lines7 '收起'
                if ($fold7 -and $fold7.Y -ge [int]($winH * 0.2)) { $fold7 = $null }
                if (-not $drawer7 -and -not (Find-Text $lines7 '引文')) { $closed = $true; break }
                if ($try -le 2 -and $fold7) {
                    Note "第 $try 次：顶栏「收起」($($fold7.X),$($fold7.Y))"
                    Hover $fold7.X $fold7.Y; Start-Sleep -Milliseconds 400
                    $null = Click-At $fold7.X $fold7.Y
                } elseif ($try -le 2 -and $bar) {
                    $tx = [int]($winW - $(if ($try -eq 1) { 104 } else { 112 })); $ty = [int](@($bar)[0].Y)
                    Note "第 $try 次：顶栏同一行右侧 ($tx,$ty) 点「收起」"
                    Hover $tx $ty; Start-Sleep -Milliseconds 400
                    $null = Click-At $tx $ty
                } elseif ($try -le 4 -and $drawer7) {
                    $dy = @($drawer7)[0]
                    $fx = [int]($winW - 48); $fy = [int]$dy.Y
                    Note "第 $try 次：抽屉头同一行右侧 ($fx,$fy) 点「×」"
                    Hover $fx $fy; Start-Sleep -Milliseconds 400
                    $null = Click-At $fx $fy
                } else {
                    $tab7 = Find-Text $lines7 '讲解'
                    if ($tab7) { $null = Click-At $tab7.X $tab7.Y; Start-Sleep -Milliseconds 800 }
                    # 切页签已经让输入框失焦；再按 L 就是阅读器自己的伴读开关。
                    Note "第 $try 次：切到「讲解」页签失焦后按 L 收伴读"
                    Send-Keys 'l'
                }
                Start-Sleep -Seconds 1
                $null = Shot 7 "closed$try"
                $after = $script:Last
                if (-not (Has $after @('提问', '批注')) -and -not (Find-Text $after '引文')) { $closed = $true; break }
                Note "第 $try 次点完抽屉没收起，换落点再点"
            }
            if (-not $closed) { $cardOk = '顶栏「收起」、抽屉头的「×」和键盘 L 都没把伴读抽屉关掉（见 7-close*.png / 7-closed*.png）' }
            else {
                $pg = $null
                for ($try = 0; $try -lt 4 -and -not $pg; $try++) {
                    Start-Sleep -Milliseconds 500
                    Hover $midX $hoverY; Start-Sleep -Milliseconds 500
                    $null = Shot 7 'card'
                    $p = Get-HudCounter $script:LastPng
                    if ($p) { $pg = "$($p.Cur)" }
                }
                $cardOk = Resolve-Step7AfterCloseVerdict $citePage $pg
            }
        }
    }
    if ($askWhy) {
        try { Fail 4 "选段讲解可用，但实际提问失败：$askWhy" } catch { if ("$_" -ne 'STEP_FAILED') { throw }; if (-not $KeepGoing) { throw 'STEP_FAILED' } }
    } else { Pass 4 '拖选一段点「讲讲」得到带出处的讲解，也能输入问题得到带出处的回答' }

    # ⑤ 边看边学出讲解：2.0 没有单独的「学习」页，本节讲解在伴读抽屉的「讲解」页签里——
    # 顶上一排模块（本章要点 / 读前自测 / 推导看图模板 / 选做练习 / 原书章末习题三级提示），
    # 默认「本章要点」按节给出核心问题与要点，每条要点后面挂「原文第 N 页」，点了跳回那一页。
    # #574 之后旧的「讲讲这一节」按钮随 BookExplainPanel 一起下线（explainingSelection 不再被赋值），
    # 所以第 5 步改认单书伴读这套：本节的要点真从书里整理出来了，且要点能定位回原文页。
    # ④ 已经在这本书上点过「允许并讲解」，这里不撤同意、也不再走目录选节。
    $five = $null
    try {
        $lr = Get-Rect
        $winW = [int]($lr.R - $lr.L); $winH = [int]($lr.B - $lr.T)
        $midX = [int]($winW / 2); $midY = [int]($winH / 2)
        $hoverY = [int]($winH - 10)
        $scrollX = $midX; $scrollY = [int]($winH * 0.72)
        # 上一步的追问出处可能指向扉页等没有正文小节的页。回到可信选段页，
        # 让本章讲解与读前自测都从本来要验的正文位置开始。
        $chapterPage = if ($physPage -gt 0) { $physPage } else { $LessonPage }
        Note "步骤5 回到章内物理页 $chapterPage 再打开伴读"
        Jump-VerifiedReaderPage 5 $chapterPage $midX $hoverY 'chapter-start'
        # 抽屉页签：讲解 / 提问 / 批注，排在抽屉头最左边。④ 点「出处」跳到原文页时顶栏会隐去，
        # 帧里可能一条页签都没有：先沿顶边把顶栏叫出来，再点顶栏右边的「伴读」把抽屉叫回来。
        $drawer5 = Ensure-CompanionDrawerOpen 5 $winW $winH -tries 3 -missTag 'explain-tab0' -openTagPrefix 'explain-tab'
        $lines = $drawer5.Lines
        $tab = $drawer5.Tab
        if (-not $tab) { Fail 5 '点开顶栏「伴读」后抽屉头里仍读不到「讲解 / 提问 / 批注」页签（见 5-explain-tab*.png）' }
        # 抽屉会记住上次停的页签（第 7 步在「提问」上留过草稿），所以必须显式点回「讲解」。
        # 正文和提问线程里也有「讲解」「讲的是」，不能拿它们当页签。点抽屉顶那一行的「讲解」：
        # 同一行右边有「批注」。选中的「提问」被漏识时这一行仍然算数。
        # 提问框还在（「问这句的意思」）时，第一下常常只是让输入框失焦：先在同一落点再点一次，
        # 仍没切走才按新一帧的坐标往左挪一点。认不出页签行就停，不拿旧坐标猜。
        $askPlaceholder = '问这句的意思'
        $switched = $false
        foreach ($try in 1..3) {
            $hit = Find-CompanionTabLabel $lines '讲解' 18 $winW $winH
            if (-not $hit) { Fail 5 '抽屉头里读到了页签字，但没在抽屉顶认到和「批注」同一行的「讲解」（见 5-explain-tab*.png）' }
            $tabX = [int]$hit.X; $tabY = [int]$hit.Y
            Note "步骤5 第 $try 次点「讲解」页签 ($tabX,$tabY)"
            Click-At $tabX $tabY
            Start-Sleep -Seconds 1
            $lines = Shot 5 $(if ($try -eq 1) { 'explain-tab-on' } else { "explain-tab-on$try" })
            if (-not (Has $lines @($askPlaceholder))) { $switched = $true; break }
            Note '步骤5 点完仍停在「提问」，同一落点再点一次'
            Click-At $tabX $tabY
            Start-Sleep -Seconds 1
            $lines = Shot 5 "explain-tab-retry$try"
            if (-not (Has $lines @($askPlaceholder))) { $switched = $true; break }
            $again = Find-CompanionTabLabel $lines '讲解' 18 $winW $winH
            if (-not $again) { Fail 5 '点了「讲解」仍停在「提问」，新一帧也没再认到抽屉顶的「讲解」（见 5-explain-tab*.png）' }
            $tabX = [Math]::Max(16, [int]$again.X - 8); $tabY = [int]$again.Y
            Note "步骤5 同一落点仍停在「提问」，按新帧往左补点 ($tabX,$tabY)"
            Click-At $tabX $tabY
            Start-Sleep -Seconds 1
            $lines = Shot 5 "explain-tab-nudge$try"
            if (-not (Has $lines @($askPlaceholder))) { $switched = $true; break }
        }
        if (-not $switched) { Fail 5 '点了抽屉里的「讲解」页签，内容还停在「提问」（见 5-explain-tab-on*.png）' }
        Note '步骤5 已切到伴读抽屉的「讲解」页签'
        # 「讲解」会保留上次的模块（例如自测）；先确认已在「要点」，否则点模块条的「要点」并确认内容切过去。
        $keyView = $false
        foreach ($attempt in 1..3) {
            Hover $midX ([int]($winH * 0.50)); Start-Sleep -Milliseconds 700
            $lines = Shot 5 "key-tab-find$attempt"
            if (Test-Step5KeyPointView $lines $winW $winH) { $keyView = $true; break }
            $keyTab = Find-Step5KeyPointTab $lines $winW $winH
            if (-not $keyTab) { Start-Sleep -Seconds 1; continue }
            $null = Click-At $keyTab.X $keyTab.Y
            foreach ($wait in 1..4) {
                Start-Sleep -Milliseconds 700
                $lines = Shot 5 "key-tab-on${attempt}-$wait"
                if (Test-Step5KeyPointView $lines $winW $winH) { $keyView = $true; break }
            }
            if ($keyView) { break }
        }
        if (-not $keyView) { Fail 5 '未能从抽屉模块条切到「要点」（见 5-key-tab-*.png）' }
        # 讲解页的内容由「本章伴读包」驱动，六种状态并存，边等边认：
        #   就绪：标题「本章要点 · <本章>」+ 若干「N. 要点」+ 每条后面「原文第 N 页」锚点；
        #   空：本章暂无提取要点；未归类：当前页暂未归入正文小节；未登录：登录后可使用本章伴读；
        #   关闭：伴读整理已在设置中关闭；出错：卡上那句说明。2.0 的出错文案有两层：
        #   服务回了 non-2xx 是「本章伴读暂时连不上」，传输层没通（ExplainTrouble.UNREACHABLE）是
        #   「讲解服务暂时连不上，读书和做题照常。」，旁边带「重试」。两种都算终态，别再干等满。
        # 出错时像人一样点一次「重试」（最多两次），服务补上就直接往下走；补不上就把卡上那句话原样带出来。
        # 620 页的书按节整理要点要时间，最多等 180 秒；认出任何一种终态就往下走，不干等满。
        # OCR 把「讲解服务」读成「讠爿解务」、「暂」读成「暫」，只有「连不上」三个字一直干净：
        # 认这句就认「连不上」，别再依赖它前面的主语（20:02 那轮因此把「服务连不上」当成「界面变了」）。
        $why5re = '本章暂无提取要点|当前页暂未归入正文小节|登录后可使用本章伴读|伴读整理已在设置中关闭|去连接服务|连不上|没顾上|这次没讲成|不可用'
        $transient5 = '连不上|没顾上|这次没讲成|不可用'
        $deadline = (Get-Date).AddSeconds(180); $kp = $null; $why5 = $null; $waited = 0; $retries5 = 0; $pageMode5 = $false
        while ((Get-Date) -lt $deadline -and -not $kp -and -not $why5) {
            Start-Sleep -Seconds 2; $waited++
            $lines = Shot 5 "explain-wait$waited"
            # 先认终态：出错和加载态顶部都照样顶着「本章要点 · <本章>」这个标题，先看标题会把加载或失败当成成功。
            $why5 = $lines | Where-Object { ($_.Text -replace '\s', '') -match $why5re } | Select-Object -First 1
            if ($why5 -and ($why5.Text -replace '\s', '') -match $transient5 -and $retries5 -lt 2) {
                $retry5 = Has $lines @('重试')
                if ($retry5) {
                    $retries5++
                    Note "步骤5 伴读包没拿到（$($why5.Text)），点卡上的「重试」第 $retries5 次"
                    Hover $retry5.X $retry5.Y; Start-Sleep -Milliseconds 300
                    Click-At $retry5.X $retry5.Y; Start-Sleep -Seconds 2
                    $why5 = $null; continue
                }
            }
            if ($why5) { break }
            # #599 起「要点」默认只讲当前这一页（SingleBookCompanionUi 的 pageOnly）：标题「讲的是：第 N 页 · …」，
            # 下面一颗「讲整章」，再下面是这一页的讲解卡和「出处 N · 第 N 页」。朋友打开抽屉先看到的就是它，
            # 第 5 步认这张卡：讲解出来了、出处能点回原文页。「讲整章」走 /api/books/<id>/modes/<mode>，
            # 冒烟门只转发 /v1/library/* 那几条，点了只会出「这次没讲成，先看原文」，所以这里不点它。
            $page5 = $lines | Where-Object { ($_.Text -replace '\s', '') -match '^讲的是[:：]?第\d+[页頁贞貞虫]' } | Select-Object -First 1
            if ($page5) {
                $busy5 = $lines | Where-Object { ($_.Text -replace '\s', '') -match '正在讲解这一页|正在打开讲解' } | Select-Object -First 1
                $kp = Find-PageCite $lines
                if (-not $kp -and -not $busy5) {
                    # 讲解卡出来了（标题下面有成段的字），出处在卡底，交给下面的滚动去找。
                    $hy = (Get-LineBox $page5).Y
                    $body5 = $lines | Where-Object { (Get-LineBox $_).Y -gt ($hy + 40) -and ($_.Text -replace '\s', '').Length -ge 16 } | Select-Object -First 1
                    if ($body5) { $kp = $page5 }
                }
                if ($kp) { $pageMode5 = $true; break }
                continue
            }
            $loading5 = $lines | Where-Object { ($_.Text -replace '\s', '') -match '正在按节整理' } | Select-Object -First 1
            if (-not $loading5) {
                $kp = Find-Step5ChapterPoint $lines $winW $winH
                if (-not $kp) { $kp = Find-AnchorChip $lines $script:LastPng $winH }
            }
            if ($kp) { break }
            # 伴读包还没回来时页签行会被加载态顶下去：顺手确认没被挤回「提问」页。
            if (Has $lines @('问这句的意思')) {
                Note '步骤5 等本章要点时被切回了「提问」页签，重新点「讲解」'
                Click-At $tabX $tabY; Start-Sleep -Milliseconds 800
            }
        }
        if (-not $kp) {
            if ($why5) {
                # 「讲解服务暂时连不上，读书和做题照常。」是 ExplainTrouble.UNREACHABLE：客户端连一页 JSON 都没拿到
                # （伴读包走 POST /v1/library/companion/chapter，隔离门没这条 location 时回的就是 nginx 的 404 页）。
                # 这句原样带出来，再点明该查哪儿——别让人以为界面又改了。
                $said5 = ($why5.Text -replace '\s', '')
                if ($said5 -match '连不上') {
                    Fail 5 "伴读包没拿到：卡上写「$said5」——客户端没收到 JSON 回复。先查 $($env:COACH_URL) 这道门有没有转发 POST /v1/library/companion/chapter（只转 status 和 explain 时它回 nginx 404），门通了再跑（见 5-explain-wait*.png）"
                }
                Fail 5 "伴读「讲解」页签没整理出本节要点：$said5（见 5-explain-wait*.png）"
            }
            Fail 5 '切到「讲解」页签后 180 秒内没等到本章要点（见 5-explain-wait*.png）'
        }
        # 要点标题和锚点不一定同屏：标题认到了还看不到「原文第 N 页」，就往下滚抽屉体把它滚进视野；
        # 避开底边 48dp HUD 触发区（y > winH*0.80），避免 Hover/Click 被底部进度条劫持。
        # 滚动用 Scroll-DrawerBody：按像素确认抽屉体真的动了，没动就换落点；连着两次滚不动就当到底了。
        $chip = $null; $lastChip = $null; $stillB = 0
        $what5 = if ($pageMode5) { '这一页讲解的「出处 N · 第 N 页」' } else { '「原文第 N 页」锚点' }
        Hover $midX ([int]($winH * 0.50)); Start-Sleep -Milliseconds 250
        $deadline = (Get-Date).AddSeconds(60)
        while ((Get-Date) -lt $deadline) {
            $lines = $script:Last
            $cand = if ($pageMode5) { Find-PageCite $lines } else { Find-AnchorChip $lines $script:LastPng $winH }
            if ($cand) {
                $lastChip = $cand
                $cy = (Get-LineBox $cand).Y
                if ($cy -le [int]($winH * 0.80) -or $stillB -ge 2) {
                    $chip = $cand
                    break
                }
            }
            $delta = if ($cand) { -2 } else { -3 }
            if (Scroll-DrawerBody $delta $winW $winH) { $stillB = 0 } else { $stillB++ }
            if ($stillB -ge 3) { $chip = $lastChip; break }
            Start-Sleep -Milliseconds 400
            $null = Shot 5 'explain-full'
        }
        if (-not $chip) { Fail 5 "讲解出来了，但滚到底也读不到$what5（见 5-explain-wait*.png / 5-explain-full.png）" }
        $chipPage = [regex]::Match(($chip.Text -replace '\s', ''), '第(\d+)[页頁贞貞虫]').Groups[1].Value
        Note "步骤5 讲解的出处：$($chip.Text)"
        $bx = Get-LineBox $chip
        $bxc = [int](($bx.X1 + $bx.X2) / 2); $byc = [int]$bx.Y
        if ($byc -le [int]($winH * 0.82)) {
            Hover $bxc $byc; Start-Sleep -Milliseconds 300
        } else {
            Hover $midX ([int]($winH * 0.45)); Start-Sleep -Milliseconds 250
        }
        $null = Click-At $bxc $byc; Start-Sleep -Seconds 2
        # 窄窗（winbox 752×552）是底部半屏抽屉：点锚点只跳页、不自动收抽屉，堆在底缘的页号条压在抽屉上沿之上，
        # 所以抽屉开着就能核对页码；宽屏浮层会自动收起抽屉，页号同样在底缘。
        $pg = $null
        foreach ($k in 1..10) {
            Start-Sleep -Milliseconds 700
            Hover $midX $hoverY; Start-Sleep -Milliseconds 400
            $null = Shot 5 "explain-cite$k"
            $p = Get-HudCounter $script:LastPng
            if (-not $p) { $p = Get-PageCounter $script:Last $script:LastPng }
            if ($p) { $pg = "$($p.Cur)"; if ($pg -eq "$chipPage") { break } }
        }
        # 11-23 14:36 那轮：出处点了、原文那段已高亮，可半屏抽屉正好压住底栏，十帧都读不出页号。
        # 跟第 7 步同一个办法：读不出就先收起抽屉再读（顶栏「收起」，认不到就切「讲解」页签失焦后按 L）。
        # 第 6 步开头会自己从顶栏「伴读」把抽屉打开。
        if (-not $pg) {
            $lines = Show-ReaderTop 5 'cite-close'; if (-not $lines) { $lines = Shot 5 'cite-close' }
            $fold5 = Find-Text $lines '收起'
            if ($fold5 -and $fold5.Y -lt [int]($winH * 0.2)) {
                Note "步骤5 抽屉压住了底栏页号，点顶栏「收起」($($fold5.X),$($fold5.Y)) 再读"
                Hover $fold5.X $fold5.Y; Start-Sleep -Milliseconds 400
                $null = Click-At $fold5.X $fold5.Y
            } else {
                Note '步骤5 抽屉压住了底栏页号，切「讲解」页签失焦后按 L 收起再读'
                $null = Click-At $tabX $tabY; Start-Sleep -Milliseconds 800
                Send-Keys 'l'
            }
            Start-Sleep -Seconds 1
            foreach ($k in 1..4) {
                Hover $midX $hoverY; Start-Sleep -Milliseconds 500
                $null = Shot 5 "explain-cite-closed$k"
                $p = Get-HudCounter $script:LastPng
                if (-not $p) { $p = Get-PageCounter $script:Last $script:LastPng }
                if ($p) { $pg = "$($p.Cur)"; break }
            }
        }
        if ("$pg" -ne "$chipPage") { Fail 5 "点了讲解里的出处（第 $chipPage 页），页码没跳过去（现在 $pg，见 5-explain-cite*.png）" }
        if ($pageMode5) { Pass 5 "伴读抽屉「讲解」讲了当前这一页，讲解末尾的出处指向第 $chipPage 页，点了停在原文这一页" }
        else { Pass 5 "伴读抽屉「讲解」页签整理出本章要点，要点后面挂着「原文第 $chipPage 页」锚点，点了能跳回原文页" }
    } catch { if ("$_" -ne 'STEP_FAILED') { throw }; $five = 'failed' }
    if ($five -and -not $KeepGoing) { throw 'STEP_FAILED' }
    if ($StopAfter -le 5) { throw 'STEP_FAILED' }   # 按要求停在第 ⑤ 步之后；已记录的结果不变

    # ⑥ 做题（答完出讲解和小结）：2.0 没有单独的「学习」页——#570 删了书架右栏的「做题学」，
    # #573 的单栏首页也只留阅读（LibraryHome 的 onLearn / onPath 都是 UNUSED_PARAMETER，
    # 阅读器里 showLearnAction=false，controller.learn 只剩测试在用）。所以「按节出题 → 答后讲解 → 出处」
    # 这套改在伴读抽屉的「读前自测」模块里验（同一份章节伴读包的另一面）：
    # 点一个选项 → 点「提交」→ 卡上出「回答准确。/ 猜对了，已记入今日复习巩固。/ 带着悬念去读原文。」，
    # 下面是解析，末尾「原文第 N 页」锚点。产品少了从首页进学习页的入口这件事写进回执，不在这里改产品。
    $six = $null
    try {
        $lr6 = Get-Rect
        $winW = [int]($lr6.R - $lr6.L); $winH = [int]($lr6.B - $lr6.T)
        $midX = [int]($winW / 2); $hoverY = [int]($winH - 10)
        $tabX6 = 30
        # ⑤ 之后抽屉一般还开着；要是收起了（⑤ 失败或曾为读页号收起时），先点顶栏「伴读」把它打开。
        $drawer6 = Ensure-CompanionDrawerOpen 6 $winW $winH -tries 1 -missTag 'top0' -openTagPrefix 'drawer'
        $lines = $drawer6.Lines
        $tab6 = $drawer6.Tab
        if (-not $tab6) { Fail 6 '伴读抽屉打不开，没法做「读前自测」（见 6-top*.png / 6-drawer.png）' }
        $tabY6 = [int]$tab6.Y
        # 抽屉会记住上次停的页签：先确认在「讲解」（模块条只在讲解页上）。
        if (Has $lines @('问这句的意思')) {
            $ex6 = Find-CompanionTabLabel $lines '讲解' 18 $winW $winH
            if ($ex6 -and [Math]::Abs($ex6.Y - $tabY6) -le 24) { $tabX6 = [int]$ex6.X }
            else { $tabX6 = [int]$tab6.X1 - 30 }
            Click-At $tabX6 $tabY6; Start-Sleep -Seconds 1
            $lines = Shot 6 'explain-tab'
        }
        # 先把指针移出顶栏让浮层自动收回，再截稳态帧定位「自测」：否则宽屏顶栏收回时抽屉整体上移 ~68px 会点偏。
        Hover $midX ([int]($winH * 0.50)); Start-Sleep -Milliseconds 700
        $lines = Shot 6 'strip'
        # 模块条：要点 / 自测 / 推导 / 练习 / 习题，都是两个字的短标签。
        # 「自测」认不出就拿「要点」往右退一格（三格间距一致，退一格正好落在「自测」上）。
        $strip = Find-Text $lines '自测'
        if (-not $strip) {
            $kp6 = Find-Text $lines '要点'
            if ($kp6) { $strip = [pscustomobject]@{ X = [int]($kp6.X + 52); Y = [int]$kp6.Y } }
        }
        if (-not $strip) { Fail 6 '伴读抽屉的模块条上找不到「自测」（见 6-*.png）' }
        foreach ($stTry in 1..2) {
            if ($stTry -gt 1) {
                $againStrip = Find-Text $script:Last '自测'
                if ($againStrip) { $strip = $againStrip }
            }
            $null = Click-At $strip.X $strip.Y; Start-Sleep -Seconds 1
            $lines = Shot 6 $(if ($stTry -eq 1) { 'pretest' } else { "pretest-click$stTry" })
            if (($lines | Where-Object { ($_.Text -replace '\s', '') -match '读前自测|^第\d+[/／]\d+[题題]|本章暂无读前自测' } | Select-Object -First 1)) { break }
        }
        # 等题目卡：题号行，或题号没认出来但答题控件齐全（见 Find-Step6QuestionCard）；空卡直接写「本章暂无读前自测题。」。
        $deadline = (Get-Date).AddSeconds(60); $q = $null
        while ((Get-Date) -lt $deadline -and -not $q) {
            $lines = $script:Last
            $q = Find-Step6QuestionCard $lines $winW $winH $strip
            if ($q) { Note "步骤6 题卡就绪：$($q.Text)"; break }
            # 伴读包没拿到时同一句话顶在模块下面（和第 ⑤ 步同一个 ExplainTrouble.UNREACHABLE）：
            # 这不是「界面变了」，是客户端没收到 JSON（门没转发 companion/chapter 时回的是 nginx 404 页）。
            $why6 = $lines | Where-Object { ($_.Text -replace '\s', '') -match '连不上' } | Select-Object -First 1
            if ($why6) { Fail 6 "伴读包没拿到，读前自测出不来：卡上写「$($why6.Text -replace '\s', '')」——先查 $($env:COACH_URL) 这道门有没有转发 POST /v1/library/companion/chapter（见 6-pretest*.png）" }
            $noQ = $lines | Where-Object { ($_.Text -replace '\s', '') -match '本章暂无读前自测[题題]' } | Select-Object -First 1
            if ($noQ) { Fail 6 "读前自测是空的：$($noQ.Text)（见 6-pretest*.png）" }
            Start-Sleep -Seconds 1; $null = Shot 6 'pretest-wait'
        }
        if (-not $q) { Fail 6 '切到「读前自测」后 60 秒内没有出现题目卡（见 6-pretest*.png）' }
        # 题目卡比抽屉高：先把「提交」滚进视野——它和「这题是猜的」同一行，在最下面。
        # 找到「提交」后，它正上方那几行里最低的一行就是最后一个选项：点它 → 点「提交」→ 看反馈。
        # 没选中时提交是灰的、按了不出反馈，所以按「点选项 → 点提交 → 认反馈」闭环，最多换 6 个落点。
        Hover $midX ([int]($winH * 0.50)); Start-Sleep -Milliseconds 250
        $answered = $false; $fb = $null; $tries6 = 0; $prev6 = ''; $still6 = 0; $push6 = 0
        $deadline = (Get-Date).AddSeconds(90)
        while ((Get-Date) -lt $deadline -and -not $answered) {
            $lines = $script:Last
            $state6 = Find-Step6PretestState $lines $winW $winH $strip
            $sb = $state6.Submit
            # 11-23 14:22 那轮滚轮落点没让抽屉体动（OCR 帧一模一样 5 次就判了没「提交」）：
            # 改用 Scroll-DrawerBody 按像素确认真滚了、没滚就换落点；连着 3 次滚不动才算卡底。
            if (-not $sb) {
                if (Scroll-DrawerBody -4 $winW $winH) { $still6 = 0 } else { $still6++ }
                if ($still6 -ge 3) { Fail 6 '题目卡滚到底也没看到「提交」按钮（抽屉体换了 4 个落点都滚不动，见 6-pretest*.png）' }
                Start-Sleep -Milliseconds 400
                $null = Shot 6 'pretest-scroll'
                continue
            }
            if ($sb.Y -gt [int]($winH * 0.80) -and $push6 -lt 2) {
                $push6++
                $null = Scroll-DrawerBody -2 $winW $winH
                Start-Sleep -Milliseconds 400
                $null = Shot 6 'pretest-scroll'
                continue
            }
            # 「提交」被滚到模块条底下时，它上面就只剩模块条（14:37 那轮把「习题」当成选项点了，切走了自测）：
            # 选项只认当前帧模块条以下、提交按钮以上的行，认不到就往回滚。
            $opt6 = $state6.Option
            if (-not $opt6) {
                $null = Scroll-DrawerBody 3 $winW $winH
                Start-Sleep -Milliseconds 700
                $null = Shot 6 'pretest-up'
                continue
            }
            $ob = Get-LineBox $opt6
            $tries6++
            Note "步骤6 第 $tries6 次点选项「$($opt6.Text)」再点「提交」"
            Hover ([int](($ob.X1 + $ob.X2) / 2)) ([int]$ob.Y); Start-Sleep -Milliseconds 300
            Click-At ([int](($ob.X1 + $ob.X2) / 2)) ([int]$ob.Y); Start-Sleep -Milliseconds 500
            Click-At ([int](($sb.X1 + $sb.X2) / 2)) ([int]$sb.Y); Start-Sleep -Seconds 1
            $null = Shot 6 "pretest-submit$tries6"
            # OCR 常把「带着悬念」的「着」读成「后 / 看」（11-23 14:47 两次都读成「带后悬念去读原文」），只认「带?悬念去读原文」。
            $fb = $script:Last | Where-Object { ($_.Text -replace '\s', '') -match '^(回答准确|猜对了|带.?悬念去读原文)' } | Select-Object -First 1
            if ($fb) { $answered = $true; break }
            if ($tries6 -ge 6) { break }
            $null = Scroll-DrawerBody 3 $winW $winH
            Start-Sleep -Milliseconds 600
            $null = Shot 6 'pretest-retry'
        }
        if (-not $answered) { Fail 6 '点了选项和「提交」，题目卡没出「回答准确 / 猜对了 / 带着悬念去读原文」这类反馈（见 6-pretest-submit*.png）' }
        Note "步骤6 提交后的反馈：$($fb.Text)"
        # 反馈下面是解析和「原文第 N 页」锚点，往下滚把它们找齐：解析是答后讲解，锚点就是出处。
        $chip6 = $null; $prevE = ''; $stillE = 0
        $deadline = (Get-Date).AddSeconds(40)
        while ((Get-Date) -lt $deadline -and -not $chip6) {
            $lines = $script:Last
            $chip6 = Find-AnchorChip $lines $script:LastPng $winH
            if ($chip6) { break }
            $frame = (@($lines | ForEach-Object { $_.Text }) -join '|')
            if ($frame -eq $prevE) { $stillE++ } else { $stillE = 0 }
            $prevE = $frame
            if ($stillE -ge 4) { break }
            Scroll-At ([int]($winW * 0.65)) ([int]($winH * 0.78)) -3
            Start-Sleep -Milliseconds 700
            $null = Shot 6 'pretest-full'
        }
        if (-not $chip6) { Fail 6 '提交后看到了反馈，但反馈下面没有读出「原文第 N 页」锚点（见 6-pretest-submit*.png / 6-pretest-full.png）' }
        Note "步骤6 答后讲解的出处：$($chip6.Text)"
        Pass 6 "伴读抽屉「读前自测」里选一个选项并提交，卡上回了「$($fb.Text -replace '\s', '')」，解析后面挂着「$($chip6.Text)」锚点（6-pretest-submit、6-pretest-full 截图）"
    } catch { if ("$_" -ne 'STEP_FAILED') { throw }; $six = 'failed' }
    if ($six -and -not $KeepGoing) { throw 'STEP_FAILED' }

    if ($cardOk) { Fail 7 $cardOk } else { Pass 7 '点回答里的「出处」跳到它写的页，收起伴读后仍停在该页' }
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
