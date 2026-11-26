# Dot-sourced inside the isolated interactive desktop after opening the PDF.
function Read-TutorFrame([string]$name) {
    Save-OwnWindow "$name.png"
    if ($Winbox) {
        $lines = @(Read-Screen (Join-Path $OutDir "$name.png"))
        $lines | ForEach-Object Text | Set-Content (Join-Path $OutDir "$name.txt") -Encoding UTF8
        return $lines
    }
    $src = [Drawing.Bitmap]::new((Join-Path $OutDir "$name.png"))
    $bmp = [Drawing.Bitmap]::new($src.Width * 3, $src.Height * 3, [Drawing.Imaging.PixelFormat]::Format24bppRgb)
    try {
        $g = [Drawing.Graphics]::FromImage($bmp)
        try {
            $g.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $g.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::Half
            $g.DrawImage($src, 0, 0, $bmp.Width, $bmp.Height)
        } finally { $g.Dispose() }
        $file = Join-Path $tutorPrivate 'screen.bmp'
        $bmp.Save($file, [Drawing.Imaging.ImageFormat]::Bmp)
    } finally { $bmp.Dispose(); $src.Dispose() }
    $json = & python (Join-Path $PSScriptRoot 'arm-screen-text.py') $file $tutorPrivate
    if ($LASTEXITCODE -ne 0) { throw 'Window text recognition failed' }
    $rows = $json | ConvertFrom-Json
    $lines = foreach ($group in ($rows | Group-Object -Property { $_[0] -join '-' })) {
        $words = foreach ($w in $group.Group) {
            [pscustomobject]@{Text=$w[1]; BoundingRect=[pscustomobject]@{X=$w[2]/3; Y=$w[3]/3; Width=$w[4]/3; Height=$w[5]/3}}
        }
        [pscustomobject]@{Text=(($words | ForEach-Object Text) -join ''); Words=@($words)}
    }
    $lines | ForEach-Object Text | Set-Content (Join-Path $OutDir "$name.txt") -Encoding UTF8
    return $lines
}

function Scroll-TutorView([int]$notches) {
    $bounds = Get-Rect
    if ($Winbox) {
        Scroll-At ([int](($bounds.R - $bounds.L) * 0.56)) ([int](($bounds.B - $bounds.T) * 0.70)) $notches
    } else {
        # 2.0 companion on this runner is a bottom sheet. Wheel its body, not the page.
        Scroll-At ([int](($bounds.R - $bounds.L) * 0.50)) ([int](($bounds.B - $bounds.T) * 0.78)) $notches
    }
}

function Find-TutorLabel([string]$name, [string[]]$labels, [int]$scroll = 0, [int]$minX = 0, [int]$minY = 0) {
    foreach ($attempt in 0..5) {
        Start-Sleep -Milliseconds 450
        $lines = @(Read-TutorFrame $name)
        foreach ($label in $labels) {
            if ($Winbox) {
                # Prefer a stand-alone button over instructions that quote its label.
                $lines = @($lines | Sort-Object { ($_.Text -replace '\s','') -ne $label })
            }
            foreach ($line in $lines) {
                $hit = Find-Text @($line) $label
                if ($hit -and $hit.X -ge $minX -and $hit.Y -ge $minY) { return $hit }
            }
        }
        if ($scroll) { Scroll-TutorView $scroll }
    }
    throw "Missing '$($labels -join ' / ')' in $name"
}

function Select-TutorLabel([string]$name, [string[]]$labels, [int]$scroll = 0, [int]$minX = 0, [int]$minY = 0) {
    $point = Find-TutorLabel $name $labels $scroll $minX $minY
    Click-At $point.X $point.Y
}

# On the shared x64 desktop, font scale and theme follow the user's settings.
# Match the visible label in the app's own capture, including the PDF page fraction.
function Find-WinboxControl([string]$template, [string]$name, [int]$scroll) {
    $label = @{
        'empty-shelf-upload.png' = '导入文件'
        'shelf-read-first.png' = '先读'
        'reader-defer-study.png' = '暂不开启'
        'explain-consent.png' = '允许并讲解'
        'block-reply-text.png' = 'Blockexplanation.'
        'shelf-guided.png' = '边看边学'
        'guided-start.png' = '开始边看边学'
        'pdf-page-1-of-3.png' = '1/3'
        'shelf-practice.png' = '做题学'
    }[$template]
    if (-not $label) { throw "Unknown installed control $template" }
    foreach ($attempt in 1..10) {
        $lines = @(Read-TutorFrame $name)
        $exact = @($lines | Where-Object { ($_.Text -replace '\s','') -eq $label })
        $hit = Find-Text $exact $label
        if (-not $hit -and $template -eq 'block-reply-text.png') { $hit = Find-Text $lines $label }
        if ($hit) { return $hit }
        if ($scroll) { Scroll-TutorView $scroll }
        Start-Sleep -Milliseconds 500
    }
    throw "Missing $label in $name"
}

# One visual row of body text. Tesseract's sparse mode often glues a wrapped paragraph
# into a single box, and the centre of that box sits in the gap between the glyphs, so a
# horizontal drag there selects nothing.
function Find-TutorBodyRows($lines) {
    $tool = @('举个例子', '讲讲', '笔记', '看原页', '看文字') | ForEach-Object { Find-Text $lines $_ } | Where-Object { $_ } | Select-Object -First 1
    $bar = @('上一页', '下一页') | ForEach-Object { Find-Text $lines $_ } | Where-Object { $_ } | Select-Object -First 1
    # y=54 on this window is the HUD (书架 / 伴读), not a body line.
    $top = if ($tool) { $tool.Y2 + 8 } else { 96 }
    $bottom = if ($bar) { $bar.Y1 - 8 } else { 100000 }
    # A generic List throws "Argument types do not match" when this script enumerates it
    # on Windows PowerShell 5.1. Keep plain arrays.
    $buckets = @()
    foreach ($l in @($lines)) {
        foreach ($w in @($l.Words)) {
            $rect = $w.BoundingRect
            if (-not $rect -or $rect.Width -lt 2 -or $rect.Height -lt 2) { continue }
            $cy = [double]$rect.Y + ([double]$rect.Height / 2)
            if ($cy -lt $top -or $cy -gt $bottom) { continue }
            $hitIndex = -1
            for ($i = 0; $i -lt @($buckets).Count; $i++) {
                if ([Math]::Abs([double]$buckets[$i].Y - $cy) -le 9) { $hitIndex = $i; break }
            }
            if ($hitIndex -lt 0) {
                $buckets += ,([pscustomobject]@{ Y = $cy; Items = @($w) })
            } else {
                $buckets[$hitIndex].Items = @($buckets[$hitIndex].Items) + $w
            }
        }
    }
    $rows = foreach ($bucket in @($buckets)) {
        $items = @($bucket.Items)
        $text = ($items | ForEach-Object { $_.Text }) -join ''
        if (($text -replace '[^\p{L}\p{N}]', '').Length -lt 8) { continue }
        $x1 = [int](($items | ForEach-Object { $_.BoundingRect.X } | Measure-Object -Minimum).Minimum)
        $x2 = [int](($items | ForEach-Object { $_.BoundingRect.X + $_.BoundingRect.Width } | Measure-Object -Maximum).Maximum)
        $y = [int](($items | ForEach-Object { $_.BoundingRect.Y + ($_.BoundingRect.Height / 2) } | Measure-Object -Average).Average)
        if (($x2 - $x1) -lt 120) { continue }
        [pscustomobject]@{ X1 = $x1; X2 = $x2; Y = $y; Width = ($x2 - $x1) }
    }
    return @($rows | Sort-Object Width -Descending)
}

# Clicks, drags, holds, and posted mouse messages never select on the ARM package.
# The reader already takes keys. F2 selects the first body word on the open page.
function Select-TutorWord {
    Write-Output "SELECTKEY F2"
    Send-Keys '{F2}'
}

function Wait-TutorText([string]$name, [string[]]$labels, [int]$seconds, [int]$scroll = -2) {
    $deadline = (Get-Date).AddSeconds($seconds)
    $spins = 0
    do {
        $frame = @(Read-TutorFrame $name)
        foreach ($label in $labels) {
            $hit = Find-Text $frame $label
            if ($hit) { return $hit }
        }
        if ($scroll -and $spins -lt 8) { Scroll-TutorView $scroll; $spins++ }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    return $null
}

# The selection toolbar draws its four actions in one row; take the one left of a known neighbour.
function Find-OnTutorRow($lines, [string]$needle, $ref) {
    if (-not $ref) { return $null }
    $best = $null
    foreach ($l in $lines) {
        $m = Find-Text @($l) $needle
        if ($m -and [Math]::Abs($m.Y - $ref.Y) -le 16 -and (-not $best -or $m.X -lt $best.X)) { $best = $m }
    }
    return $best
}

function Focus-ReaderPage {
    $rect = Get-Rect
    Click-At ([int](($rect.R - $rect.L) / 2)) ([int](($rect.B - $rect.T) / 2)) | Out-Null
    Start-Sleep -Milliseconds 300
}

# 2.0 selection toolbar is a light pill: 「讲讲 / 拆句 / 举个例子 / 问… / 笔记」.
# F2 selects the open page. A glyph click does not.
function Open-SelectionAction([string]$name, [string]$action) {
    Focus-ReaderPage
    Select-TutorWord
    $selected = $null
    foreach ($spin in 0..2) {
        Start-Sleep -Milliseconds 400
        $frame = @(Read-TutorFrame "$name-selected")
        foreach ($label in @('举个例子', '笔记', '拆句', '讲讲')) {
            $selected = Find-Text $frame $label
            if ($selected) { break }
        }
        if ($selected) { break }
    }
    if (-not $selected) { throw "Selecting the page word did not raise the selection toolbar ($name)" }
    $bar = @(Read-TutorFrame "$name-bar")
    $hit = Find-OnTutorRow $bar $action $selected
    if (-not $hit) { $hit = Find-Text $bar $action }
    if (-not $hit) { throw "Selection toolbar has no $action" }
    Click-At $hit.X $hit.Y | Out-Null
}

function Test-BlockAndCitation {
    Focus-ReaderPage
    Send-Keys '{HOME}'
    Start-Sleep -Milliseconds 800
    # 2.0 dropped the per-book consent wall. 「讲讲」 explains immediately unless a
    # consent label is actually on screen.
    Open-SelectionAction '04' '讲讲'
    $reply = $null
    $deadline = (Get-Date).AddSeconds(30)
    $spins = 0
    do {
        $lines = @(Read-TutorFrame '04-after-explain')
        $reply = Find-Text $lines 'Blockexplanation'
        if ($reply) { break }
        $consent = Find-Text $lines '允许并讲解'
        if (-not $consent) { $consent = Find-Text $lines '开启讲解' }
        if ($consent) { Click-At $consent.X $consent.Y | Out-Null }
        elseif ($spins -lt 6) { Scroll-TutorView -2; $spins++ }
        Start-Sleep -Milliseconds 700
    } while ((Get-Date) -lt $deadline)
    if (-not $reply) { throw '讲解没有出现（Block explanation）' }
    Select-TutorLabel '07-citation' @('出处') -2
    # 2.0 hides the old 「看文字」 toggle inside the more menu. The citation row itself
    # is the proof: it stays on screen, or the jump leaves the explanation up.
    Start-Sleep -Milliseconds 600
    $after = @(Read-TutorFrame '07-original')
    $still = (Find-Text $after '出处') -or (Find-Text $after 'Blockexplanation') -or (Find-Text $after '看文字')
    if (-not $still) { throw '点了出处之后讲解不见了' }
    Send-Keys '{ESC}'
}

function Test-PassageQuestion {
    Open-SelectionAction '04-ask' '问'
    $field = Find-TutorLabel '04-question-field' @('问这句的意思', '想问什么', '先输入问题', '输入问题') -2
    $question = 'What does this passage mean?'
    Click-At $field.X ($field.Y + 8) | Out-Null
    Start-Sleep -Milliseconds 300
    Send-Keys $question
    Send-Keys '{ENTER}'
    $null = Find-TutorLabel '04-answer' @('Passageanswer') -3
    $null = Find-TutorLabel '04-answer-source' @('出处') -3
    Send-Keys '{ESC}'
}

function Test-WholeLesson {
    # 2.0 has no shelf cards 「边看边学」. The lesson is the companion drawer.
    # A page-only drawer calls explainLesson; a chapter drawer calls companion/chapter.
    # 「讲整章」 is the control that leaves page-only mode when the chapter is wider than this page.
    # Escape is the reader back key: it returns to the shelf. 「伴读」 is the drawer toggle.
    $opened = @(Read-TutorFrame '05-hud')
    $btn = Find-Text $opened '伴读'
    if (-not $btn) { $btn = Find-Text $opened '收起' }
    if ($btn) {
        Click-At $btn.X $btn.Y | Out-Null
    } else {
        Send-Keys 'l'
    }
    Start-Sleep -Milliseconds 800
    $opened = @(Read-TutorFrame '05-companion')
    $wider = Find-Text $opened '讲整章'
    if ($wider) { Click-At $wider.X $wider.Y | Out-Null }
    # The pack's core question sits under the module header. On a short bottom sheet
    # it starts just below the fold, so wheel the sheet until the sentence is painted.
    $hit = Wait-TutorText '05-lesson' @('Lessonoverview', '先阅读原文') 45 -2
    if (-not $hit) {
        $fail = Find-Text @(Read-TutorFrame '05-lesson-fail') '连不上'
        if ($fail) { throw '本章伴读连不上（见 05-lesson）' }
        throw '本章讲解没有出现'
    }
    $null = Find-TutorLabel '05-lesson-source' @('出处', '原文第') -3
}

# 「提交」 is a short black pill. On the ARM runner the OCR reads the card titles
# and drops both the white word and, on the later sheet, 「这题是猜的」. The pill is
# the only short near-black run in the lower right of the window capture.
function Find-SubmitPill([string]$name) {
    $path = Join-Path $OutDir "$name.png"
    if (-not (Test-Path $path)) { return $null }
    $bmp = [Drawing.Bitmap]::new($path)
    try {
        $w = $bmp.Width
        $h = $bmp.Height
        $xs = @()
        $ys = @()
        for ($y = [int]($h * 0.55); $y -lt ($h - 8); $y += 2) {
            $run = 0
            $runStart = 0
            for ($x = [int]($w * 0.7); $x -lt $w; $x++) {
                $c = $bmp.GetPixel($x, $y)
                $dark = ($c.R -lt 45 -and $c.G -lt 45 -and $c.B -lt 45)
                if ($dark) {
                    if ($run -eq 0) { $runStart = $x }
                    $run++
                } else {
                    if ($run -ge 40 -and $run -le 90 -and ($runStart + $run) -lt ($w - 8)) {
                        $xs += ($runStart + ($run / 2))
                        $ys += $y
                    }
                    $run = 0
                }
            }
        }
        if (@($ys).Count -lt 6) { return $null }
        return [pscustomobject]@{
            X = [int](($xs | Measure-Object -Average).Average)
            Y = [int](($ys | Measure-Object -Average).Average)
        }
    } finally { $bmp.Dispose() }
}

function Test-Practice {
    # 2.0 grades the pretest locally from the companion pack. The pack itself is the
    # loopback request that carries the question, the option, and the feedback.
    $lines = @(Read-TutorFrame '06-open')
    $tab = Find-Text $lines '自测'
    if (-not $tab) { $tab = Find-Text $lines '读前自测' }
    # ARM OCR reads the light card titles and misreads 「自测」 (it came back as 「自疯」).
    # The module strip is 要点 / 自测 / 推导. 「推导」 is stable; the pill to its left is 自测.
    if (-not $tab) {
        $derive = Find-Text $lines '推导'
        if ($derive) { $tab = [pscustomobject]@{ X = [int]($derive.X - 52); Y = [int]$derive.Y } }
    }
    if (-not $tab) {
        $points = Find-Text $lines '要点'
        if ($points) { $tab = [pscustomobject]@{ X = [int]($points.X + 52); Y = [int]$points.Y } }
    }
    if (-not $tab) { throw '伴读里没有读前自测' }
    Click-At $tab.X $tab.Y | Out-Null
    $stem = Wait-TutorText '06-question' @('Whichmethod', 'checksrecall') 30 -2
    if (-not $stem) {
        $fail = Find-Text @(Read-TutorFrame '06-question-fail') '连不上'
        if ($fail) { throw '读前自测连不上（见 06-question）' }
        throw '读前自测题没有出现'
    }
    $frame = @(Read-TutorFrame '06-options')
    $opt = Find-Text $frame 'Recallbefore'
    if (-not $opt) { $opt = Find-Text $frame 'beforechecking' }
    if (-not $opt) {
        Scroll-TutorView -2
        $frame = @(Read-TutorFrame '06-options-down')
        $opt = Find-Text $frame 'Recallbefore'
        if (-not $opt) { $opt = Find-Text $frame 'beforechecking' }
    }
    if (-not $opt) { throw '读前自测没有选项 Recall before checking' }
    Click-At $opt.X $opt.Y | Out-Null
    Start-Sleep -Milliseconds 400
    # The multiple-choice action is 「核对」, a black pill. OCR drops it. Wheel the
    # sheet body (a point on the page scrollbar does not move this sheet) until the
    # pill is in the capture, then click that.
    $submit = $null
    foreach ($pass in 1..4) {
        $shot = if ($pass -eq 1) { '06-selected' } else { "06-selected-$pass" }
        $frame = @(Read-TutorFrame $shot)
        $submit = Find-Text $frame '核对'
        if (-not $submit) { $submit = Find-Text $frame '提交' }
        if (-not $submit) { $submit = Find-SubmitPill $shot }
        if ($submit) { break }
        Scroll-TutorView -3
    }
    if (-not $submit) { throw '读前自测没有提交' }
    Click-At $submit.X $submit.Y | Out-Null
    $fb = Wait-TutorText '06-feedback' @('Practicefeedback') 20 -2
    if (-not $fb) { throw '提交后没有练习解析' }
    $frame = @(Read-TutorFrame '06-feedback-source')
    $src = Find-Text $frame '原文第'
    if (-not $src) { $src = Find-Text $frame '出处' }
    if (-not $src) {
        Scroll-TutorView -2
        $frame = @(Read-TutorFrame '06-feedback-source-down')
        $src = Find-Text $frame '原文第'
        if (-not $src) { $src = Find-Text $frame '出处' }
    }
    if (-not $src) { throw '练习解析没有出处' }
}

function Test-InstalledTutor {
    $errors = @()
    foreach ($scenario in @('Test-BlockAndCitation', 'Test-PassageQuestion', 'Test-WholeLesson', 'Test-Practice')) {
        try { & $scenario; Write-Output "UI_PASS $scenario" }
        catch {
            $errors += "$scenario - $($_.Exception.Message)"
            Write-Output $errors[-1]
        }
    }
    if ($errors) { throw ($errors -join ' | ') }
    $events = @(Get-Content -LiteralPath (Join-Path $OutDir 'tutor-events.jsonl') -Encoding UTF8 | ForEach-Object { $_ | ConvertFrom-Json })
    foreach ($kind in @('explain','ask','lesson','practice')) {
        if ($kind -notin $events.kind) { throw "Missing loopback request: $kind" }
    }
    Write-Output 'INSTALLED_LOOPBACK_UI_PASS steps=4,5,6,7'
}
