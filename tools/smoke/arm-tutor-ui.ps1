# Dot-sourced inside the isolated interactive desktop after opening the PDF.
function Read-TutorFrame([string]$name) {
    Save-OwnWindow "$name.png"
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

function Find-TutorLabel([string]$name, [string[]]$labels, [int]$scroll = 0, [int]$minX = 0, [int]$minY = 0) {
    foreach ($attempt in 0..5) {
        Start-Sleep -Milliseconds 450
        $lines = @(Read-TutorFrame $name)
        foreach ($label in $labels) {
            foreach ($line in $lines) {
                $hit = Find-Text @($line) $label
                if ($hit -and $hit.X -ge $minX -and $hit.Y -ge $minY) { return $hit }
            }
        }
        if ($scroll) { Scroll-At 584 490 $scroll }
    }
    throw "Missing '$($labels -join ' / ')' in $name"
}

function Select-TutorLabel([string]$name, [string[]]$labels, [int]$scroll = 0, [int]$minX = 0, [int]$minY = 0) {
    $point = Find-TutorLabel $name $labels $scroll $minX $minY
    Click-At $point.X $point.Y
}

function Test-BlockAndCitation {
    Send-Keys '{HOME}'
    Select-TutorLabel '04-reader' @('讲解') 0 675
    Select-TutorLabel '04-explain-action' @('讲解这段') -2 675 150
    Click-CapturedControl 'explain-consent.png' '04-consent.png'
    $null = Find-TutorLabel '04-block' @('Blockexplanation','explanation') -2
    Select-TutorLabel '07-citation' @('出处1','出处') -2
    $null = Find-TutorLabel '07-original' @('关闭')
    Send-Keys '{ESC}'
}

function Test-PassageQuestion {
    Select-TutorLabel '04-ask-action' @('选段提问')
    $hint = Find-TutorLabel '04-ask-dialog' @('点一段','再问一句')
    Click-At ($hint.X + 80) ($hint.Y + 46)
    $field = Find-TutorLabel '04-question-field' @('想问什么') -4
    Click-At ($field.X + 35) ($field.Y + 34)
    Send-Keys 'What does this passage mean?'
    Send-Keys '{ENTER}'
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
    Select-TutorLabel '05-open' @('开始边看边学','开始边看','边看边学') 0 550 400
    $null = Find-TutorLabel '05-lesson' @('Lessonoverview','overview') -4
    Select-TutorLabel '05-lesson-source' @('出处') -3
    $null = Find-TutorLabel '05-original' @('返回学习')
    Select-TutorLabel '05-return' @('返回学习')
}

function Test-Practice {
    Open-TutorShelf '06-shelf'
    Click-CapturedControl 'shelf-practice.png' '06-practice-path.png'
    $null = Find-TutorLabel '06-question' @('Whichmethod','checksrecall') -3
    Select-TutorLabel '06-options' @('Recallbefore','beforechecking') -3
    $null = Find-TutorLabel '06-selected' @('你选择','你选了') -2
    $null = Find-TutorLabel '06-feedback' @('Practicefeedback','feedback') -3
    $null = Find-TutorLabel '06-feedback-source' @('出处') -2
    $null = Find-TutorLabel '06-summary' @('这次练习中','题目和作答') -3
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
