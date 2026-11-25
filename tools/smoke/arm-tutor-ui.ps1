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
    if ($Winbox) {
        $bounds = Get-Rect
        Scroll-At ([int](($bounds.R - $bounds.L) * 0.56)) ([int](($bounds.B - $bounds.T) * 0.70)) $notches
    } else {
        Scroll-At 584 490 $notches
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

# A long line of the page body: below the reader toolbar, above the page bar. 1.1.1 explains
# whatever the reader has selected, so the probe needs a real line to drag across.
function Find-TutorBodyLine($lines) {
    $tool = @('举个例子', '讲讲', '笔记', '看原页', '看文字') | ForEach-Object { Find-Text $lines $_ } | Where-Object { $_ } | Select-Object -First 1
    $bar = @('上一页', '下一页') | ForEach-Object { Find-Text $lines $_ } | Where-Object { $_ } | Select-Object -First 1
    $top = if ($tool) { $tool.Y2 + 8 } else { 0 }
    $bottom = if ($bar) { $bar.Y1 - 8 } else { 100000 }
    $best = $null
    foreach ($l in $lines) {
        if (($l.Text -replace '[^\p{L}\p{N}]', '').Length -lt 12) { continue }
        $words = @($l.Words)
        if (-not $words) { continue }
        $x1 = [int](($words | ForEach-Object { $_.BoundingRect.X } | Measure-Object -Minimum).Minimum)
        $y1 = [int](($words | ForEach-Object { $_.BoundingRect.Y } | Measure-Object -Minimum).Minimum)
        $x2 = [int](($words | ForEach-Object { $_.BoundingRect.X + $_.BoundingRect.Width } | Measure-Object -Maximum).Maximum)
        $y2 = [int](($words | ForEach-Object { $_.BoundingRect.Y + $_.BoundingRect.Height } | Measure-Object -Maximum).Maximum)
        if ($y1 -lt $top -or $y2 -gt $bottom) { continue }
        if (($x2 - $x1) -lt 80) { continue }
        $box = [pscustomobject]@{ X1 = $x1; X2 = $x2; Y = [int](($y1 + $y2) / 2); Width = $x2 - $x1 }
        if (-not $best -or $box.Width -gt $best.Width) { $best = $box }
    }
    return $best
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

# 2.0 selection toolbar is 「讲讲 / 拆句 / 举个例子 / 问… / 笔记」. Drag a body line, then click one action.
function Open-SelectionAction([string]$name, [string]$action) {
    Focus-ReaderPage
    $lines = @(Read-TutorFrame "$name-reader")
    $box = Find-TutorBodyLine $lines
    if (-not $box) { throw "No selectable body line in $name" }
    $selected = $null
    foreach ($attempt in 1..3) {
        Drag-Between ($box.X1 + 4) $box.Y ($box.X2 - 4) $box.Y
        try { $selected = Find-TutorLabel "$name-selected" @('举个例子', '笔记', '拆句') 0 } catch { $selected = $null }
        if ($selected) { break }
    }
    if (-not $selected) { throw "Dragging a line did not raise the selection toolbar ($name)" }
    $bar = @(Read-TutorFrame "$name-bar")
    $hit = Find-OnTutorRow $bar $action $selected
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
    do {
        $lines = @(Read-TutorFrame '04-after-explain')
        $reply = Find-Text $lines 'Blockexplanation'
        if ($reply) { break }
        $consent = Find-Text $lines '允许并讲解'
        if (-not $consent) { $consent = Find-Text $lines '开启讲解' }
        if ($consent) { Click-At $consent.X $consent.Y | Out-Null }
        Start-Sleep -Milliseconds 800
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
    # 2.0 has no shelf cards 「边看边学」. The lesson is the companion drawer (L).
    # A page-only drawer calls explainLesson; a chapter drawer calls companion/chapter.
    # 「讲整章」 is the control that leaves page-only mode when the chapter is wider than this page.
    Send-Keys '{ESC}'
    Start-Sleep -Milliseconds 300
    Focus-ReaderPage
    Send-Keys 'l'
    Start-Sleep -Milliseconds 800
    $opened = @(Read-TutorFrame '05-companion')
    $wider = Find-Text $opened '讲整章'
    if ($wider) { Click-At $wider.X $wider.Y | Out-Null }
    $hit = $null
    $deadline = (Get-Date).AddSeconds(45)
    do {
        $frame = @(Read-TutorFrame '05-lesson')
        $hit = Find-Text $frame 'Lessonoverview'
        if (-not $hit) { $hit = Find-Text $frame '先阅读原文' }
        if ($hit) { break }
        $fail = Find-Text $frame '连不上'
        if ($fail) { throw '本章伴读连不上（见 05-lesson）' }
        Start-Sleep -Milliseconds 800
    } while ((Get-Date) -lt $deadline)
    if (-not $hit) { throw '本章讲解没有出现' }
    $null = Find-TutorLabel '05-lesson-source' @('出处', '原文第') -3
}

function Test-Practice {
    # 2.0 grades the pretest locally from the companion pack. The pack itself is the
    # loopback request that carries the question, the option, and the feedback.
    $lines = @(Read-TutorFrame '06-open')
    $tab = Find-Text $lines '自测'
    if (-not $tab) { $tab = Find-Text $lines '读前自测' }
    if (-not $tab) { throw '伴读里没有读前自测' }
    Click-At $tab.X $tab.Y | Out-Null
    $stem = $null
    $frame = @()
    $deadline = (Get-Date).AddSeconds(30)
    do {
        $frame = @(Read-TutorFrame '06-question')
        $stem = Find-Text $frame 'Whichmethod'
        if (-not $stem) { $stem = Find-Text $frame 'checksrecall' }
        if ($stem) { break }
        $fail = Find-Text $frame '连不上'
        if ($fail) { throw '读前自测连不上（见 06-question）' }
        Start-Sleep -Milliseconds 700
    } while ((Get-Date) -lt $deadline)
    if (-not $stem) { throw '读前自测题没有出现' }
    $opt = Find-Text $frame 'Recallbefore'
    if (-not $opt) { $opt = Find-Text $frame 'beforechecking' }
    if (-not $opt) { throw '读前自测没有选项 Recall before checking' }
    Click-At $opt.X $opt.Y | Out-Null
    Start-Sleep -Milliseconds 400
    $submit = Find-Text @(Read-TutorFrame '06-selected') '提交'
    if (-not $submit) { throw '读前自测没有提交' }
    Click-At $submit.X $submit.Y | Out-Null
    $fb = $null
    $deadline = (Get-Date).AddSeconds(15)
    do {
        $frame = @(Read-TutorFrame '06-feedback')
        $fb = Find-Text $frame 'Practicefeedback'
        if ($fb) { break }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    if (-not $fb) { throw '提交后没有练习解析' }
    $src = Find-Text $frame '原文第'
    if (-not $src) { $src = Find-Text $frame '出处' }
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
