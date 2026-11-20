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
        'empty-shelf-upload.png' = '上传教材'
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
    $tool = @('选段提问', '看原页', '看文字') | ForEach-Object { Find-Text $lines $_ } | Where-Object { $_ } | Select-Object -First 1
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

function Test-BlockAndCitation {
    Send-Keys '{HOME}'
    Start-Sleep -Milliseconds 800
    # 1.1.1（TON-1766）删掉了每段的「讲解这段」：改成在文字视图里拖选一行，再点浮条上的「讲讲」。
    $lines = @(Read-TutorFrame '04-reader')
    $box = Find-TutorBodyLine $lines
    if (-not $box) { throw 'No selectable body line in 04-reader' }
    $selected = $null
    foreach ($attempt in 1..3) {
        Drag-Between ($box.X1 + 4) $box.Y ($box.X2 - 4) $box.Y
        try { $selected = Find-TutorLabel '04-selected' @('举个例子', '换个说法') 0 } catch { $selected = $null }
        if ($selected) { break }
    }
    if (-not $selected) { throw 'Dragging a line did not raise the selection toolbar' }
    $explain = Find-OnTutorRow @(Read-TutorFrame '04-selected') '讲讲' $selected
    if (-not $explain) { throw 'Selection toolbar has no 讲讲' }
    Click-At $explain.X $explain.Y
    Click-CapturedControl 'explain-consent.png' '04-consent.png'
    Click-CapturedControl 'block-reply-text.png' '04-block.png' -VerifyOnly -ScrollNotches -1
    Select-TutorLabel '07-citation' @('出处1', '出处') -2
    $null = Find-TutorLabel '07-original' @('关闭')
    Send-Keys '{ESC}'
}

function Test-PassageQuestion {
    Select-TutorLabel '04-ask-action' @('选段提问')
    Select-TutorLabel '04-ask-dialog' @('correlation')
    $field = Find-TutorLabel '04-question-field' @('想问什么') -4
    $question = 'What does this passage mean?'
    if ($Winbox) {
        $counter = Find-TutorLabel '04-question-field' @('0/500') -2
        $field = Find-TutorLabel '04-question-field' @('想问什么')
        if ($counter.Y -le $field.Y) { throw 'Question input is not fully visible' }
        Click-At ($field.X + 100) ([int](($field.Y + $counter.Y) / 2))
        Start-Sleep -Milliseconds 300
        Send-Keys $question
        $null = Find-TutorLabel '04-typed' @('Whatdoesthispassagemean')
        Select-TutorLabel '04-send' @('发送')
    } else {
        Click-At ($field.X + 35) ($field.Y + 34)
        Send-Keys $question
        Send-Keys '{ENTER}'
    }
    $null = Find-TutorLabel '04-answer' @('Passageanswer') -3
    $null = Find-TutorLabel '04-answer-source' @('出处') -3
    Send-Keys '{ESC}'
}

function Open-TutorShelf([string]$name) {
    # Dismiss any modal left by the previous path before using the app's Back action.
    Send-Keys '{ESC}'
    Start-Sleep -Milliseconds 300
    $lines = @(Read-TutorFrame $name)
    if (Find-Text $lines '上传教材') { return }
    Select-TutorLabel $name @('返回书架') 0
}

function Test-WholeLesson {
    Open-TutorShelf '05-shelf'
    Click-CapturedControl 'shelf-guided.png' '05-path.png'
    Click-CapturedControl 'guided-start.png' '05-open.png'
    $null = Find-TutorLabel '05-lesson' @('Lessonover','先阅读原文','再用回忆检查理解') -3
    Select-TutorLabel '05-lesson-source' @('出处') -3
    $null = Find-TutorLabel '05-original' @('返回学习')
    $view = @(Read-TutorFrame '05-original')
    $toggle = Find-Text $view '看原页'
    if ($toggle) { Click-At $toggle.X $toggle.Y }
    $null = Find-TutorLabel '05-pdf-page' @('看文字')
    $null = Find-TutorLabel '05-pdf-page' @('understanding','correlation')
    Click-CapturedControl 'pdf-page-1-of-3.png' '05-pdf-page.png' -VerifyOnly
    Select-TutorLabel '05-return' @('返回学习')
}

function Test-Practice {
    Open-TutorShelf '06-shelf'
    Click-CapturedControl 'shelf-practice.png' '06-practice-path.png'
    $null = Find-TutorLabel '06-question' @('Whichmethod','checksrecall') -3
    Select-TutorLabel '06-options' @('Recallbefore','beforechecking') -3
    $null = Find-TutorLabel '06-selected' @('你选择','你选了','本题答案一致') -2
    $null = Find-TutorLabel '06-feedback' @('Practicefeedback','feedback') -3
    $null = Find-TutorLabel '06-feedback-source' @('出处') -2
    # The summary heading can enter the viewport while the actual result still sits below it.
    Scroll-TutorView -24
    if ($Winbox) {
        $null = Find-TutorLabel '06-summary' @('题目和作答只保留在这次练习中') -3
        $null = Find-TutorLabel '06-summary' @('你选了「Recallbeforechecking」，与本题答案一致')
    } else {
        $summary = Find-TutorLabel '06-summary' @('这一节','小结','题目和作答') -3
        $null = Find-TutorLabel '06-summary' @('Recallbefore','beforechecking') 0 100 ($summary.Y - 45)
    }
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
