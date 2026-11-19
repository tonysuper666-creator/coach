# 第⑥步核对落点：学习页「本节原文」打开后，产品写着「对应教材第 F–L 页」（TextbookReader.kt 的 source 行）。
# 只认这行写出的页段，不按起始页远近猜。读不出页段返回 $null，由调用方明确报错。
function Get-LessonSourceRange($lines) {
    $rows = @(foreach ($l in $lines) { $l.Text -replace '\s', '' })
    foreach ($t in @($rows) + @($rows -join '')) {
        $m = [regex]::Match($t, '对应教材第(\d+)[^\d页]{1,3}(\d+)页')
        if ($m.Success) {
            $first = [int]$m.Groups[1].Value; $last = [int]$m.Groups[2].Value
            if ($first -gt 0 -and $first -le $last) { return [pscustomobject]@{ First = $first; Last = $last } }
        }
    }
    return $null
}
function Test-RangeHasPage($range, [int]$page) {
    return [bool]($range -and $page -ge $range.First -and $page -le $range.Last)
}
# 只点「本节原文 · 第X页」按钮；题干「根据本节原文，补全这句话」和「正在按本节原文出题」也含这四个字，不能拿来点。
function Find-LessonSourceButton($lines) {
    foreach ($l in $lines) {
        # OCR 偶尔把上方提示和按钮并成一行，所以只要求按钮文字在行尾。
        if (($l.Text -replace '\s', '') -match '(本节原文[^第，,]{0,3}第.{1,8}[页贞頁])$') { $m = Find-Text @($l) $Matches[1]; if ($m) { return $m } }
    }
    # OCR 也会把同一颗按钮拆成「本节原文 ·」和「第X页」两块：按位置拼回去。
    # 前一块必须以「本节原文」加至多两个符号收尾（排除「正在按本节原文出题」），后一块只能是「第X页」（OCR 会把「页」读成「贞」），两块同一行、紧挨着。
    $box = { param($l) $x1 = 1e9; $y1 = 1e9; $x2 = -1; $y2 = -1
        foreach ($w in $l.Words) { $b = $w.BoundingRect
            if ($b.X -lt $x1) { $x1 = $b.X }; if ($b.Y -lt $y1) { $y1 = $b.Y }
            if (($b.X + $b.Width) -gt $x2) { $x2 = $b.X + $b.Width }; if (($b.Y + $b.Height) -gt $y2) { $y2 = $b.Y + $b.Height } }
        [pscustomobject]@{ X1 = $x1; Y1 = $y1; X2 = $x2; Y2 = $y2 } }
    foreach ($l in $lines) {
        if (($l.Text -replace '\s', '') -notmatch '(本节原文[^\p{L}\p{N}，,]{0,2})$') { continue }
        $label = Find-Text @($l) $Matches[1]; if (-not $label) { continue }
        foreach ($p in $lines) {
            if ($p -eq $l -or ($p.Text -replace '\s', '') -notmatch '^[^\p{L}\p{N}]{0,2}第\d{1,4}[页贞頁]?$') { continue }
            $pb = & $box $p
            $sameRow = $pb.Y1 -lt $label.Y2 -and $pb.Y2 -gt $label.Y1
            $gap = $pb.X1 - $label.X2
            if ($sameRow -and $gap -ge -8 -and $gap -le 120) {
                $x1 = [int]$label.X1; $x2 = [int][math]::Max($label.X2, $pb.X2)
                $y1 = [int][math]::Min($label.Y1, $pb.Y1); $y2 = [int][math]::Max($label.Y2, $pb.Y2)
                return [pscustomobject]@{ X = [int](($x1 + $x2) / 2); Y = [int](($y1 + $y2) / 2); X1 = $x1; X2 = $x2; Y1 = $y1; Y2 = $y2 }
            }
        }
    }
    return $null
}
function Get-RegionContrast($Bitmap, $Box) {
    if (-not $Bitmap -or -not $Box) { return 0 }
    $bx = [Math]::Max(0, [Math]::Min($Bitmap.Width - 1, [int]$Box.X1 - 12))
    $by = [Math]::Max(0, [Math]::Min($Bitmap.Height - 1, [int](($Box.Y1 + $Box.Y2) / 2)))
    $bg = $Bitmap.GetPixel($bx, $by)
    $bgLum = 0.299 * $bg.R + 0.587 * $bg.G + 0.114 * $bg.B
    $xStart = [Math]::Max(0, [int]$Box.X1); $xEnd = [Math]::Min($Bitmap.Width - 1, [int]$Box.X2)
    $yStart = [Math]::Max(0, [int]$Box.Y1); $yEnd = [Math]::Min($Bitmap.Height - 1, [int]$Box.Y2)
    $maxDiff = 0.0
    for ($y = $yStart; $y -le $yEnd; $y++) {
        for ($x = $xStart; $x -le $xEnd; $x++) {
            $px = $Bitmap.GetPixel($x, $y)
            $lum = 0.299 * $px.R + 0.587 * $px.G + 0.114 * $px.B
            $diff = [Math]::Abs($lum - $bgLum)
            if ($diff -gt $maxDiff) { $maxDiff = $diff }
        }
    }
    return [int][Math]::Round($maxDiff)
}
function Test-LessonSourceButtonEnabled($lines, [string]$PngPath, $Button = $null) {
    if (-not $Button) { $Button = Find-LessonSourceButton $lines }
    if (-not $Button) { return $false }
    if (-not $PngPath -or -not (Test-Path -LiteralPath $PngPath)) { return $true }
    $bmp = [Drawing.Bitmap]::new($PngPath)
    try {
        $btnContrast = Get-RegionContrast $bmp $Button
        $guide = Find-Text $lines '先试着回答'
        if (-not $guide) { $guide = Find-Text $lines '先理解讲解' }
        $guideContrast = if ($guide) { Get-RegionContrast $bmp $guide } else { 0 }
        if ($guideContrast -gt 0) {
            return [bool]($btnContrast -ge 70 -and ($btnContrast / $guideContrast) -ge 0.75)
        }
        return [bool]($btnContrast -ge 90)
    } finally { $bmp.Dispose() }
}
function Get-LessonLearningState($lines, [string]$PngPath = '') {
    $onSource = [bool](Find-Text $lines '返回学习')
    $sourceRange = if ($onSource) { Get-LessonSourceRange $lines } else { $null }
    $busy = [bool](Find-Text $lines '正在按本节原文出题')
    $btn = Find-LessonSourceButton $lines
    $btnEnabled = if ($btn) { Test-LessonSourceButtonEnabled $lines $PngPath $btn } else { $false }
    $btnPage = 0
    if ($btn) {
        $joined = (@(foreach ($l in $lines) {
            foreach ($w in $l.Words) {
                $b = $w.BoundingRect
                if ($b.X -ge ($btn.X1 - 8) -and $b.X -le ($btn.X2 + 8) -and
                    $b.Y -ge ($btn.Y1 - 6) -and ($b.Y + $b.Height) -le ($btn.Y2 + 6)) { $w.Text }
            }
        }) -join '') -replace '\s', ''
        if ($joined -match '第(\d{1,4})[页贞頁]?') { $btnPage = [int]$Matches[1] }
    }
    $title = ''
    $guide = Find-Text $lines '先试着回答'
    if (-not $guide) { $guide = Find-Text $lines '先理解讲解' }
    if ($guide) {
        $bestY = -1
        foreach ($l in $lines) {
            if (-not $l.Words -or $l.Words.Count -eq 0) { continue }
            $minX = ($l.Words | ForEach-Object { $_.BoundingRect.X } | Measure-Object -Minimum).Minimum
            $minY = ($l.Words | ForEach-Object { $_.BoundingRect.Y } | Measure-Object -Minimum).Minimum
            $maxY = ($l.Words | ForEach-Object { $_.BoundingRect.Y + $_.BoundingRect.Height } | Measure-Object -Maximum).Maximum
            if ($maxY -lt $guide.Y1 -and $minY -ge ($guide.Y1 - 160) -and $minX -ge ($guide.X1 - 40)) {
                if ($maxY -gt $bestY) { $bestY = $maxY; $title = ($l.Text -replace '\s', '') }
            }
        }
    }
    [pscustomobject]@{
        OnSourcePage = $onSource
        SourceRange = $sourceRange
        BusyGenerating = $busy
        HasSourceButton = [bool]($null -ne $btn)
        Button = $btn
        ButtonEnabled = $btnEnabled
        ButtonPage = $btnPage
        Title = $title
    }
}
function Test-AdjacentLessonReady($BeforeState, $NowState) {
    if (-not $NowState -or -not $NowState.HasSourceButton -or -not $NowState.ButtonEnabled -or $NowState.BusyGenerating) {
        return $false
    }
    if ($BeforeState) {
        if ($BeforeState.Title -and $NowState.Title) {
            if ($NowState.Title -ne $BeforeState.Title) { return $true }
            if ($BeforeState.ButtonPage -gt 0 -and $NowState.ButtonPage -gt 0 -and $NowState.ButtonPage -ne $BeforeState.ButtonPage) { return $true }
            return $false
        }
        if ($BeforeState.ButtonPage -gt 0 -and $NowState.ButtonPage -gt 0) {
            return [bool]($NowState.ButtonPage -ne $BeforeState.ButtonPage)
        }
    }
    return $true
}
function Resolve-SourcePageRangeAction($State) {
    if ($State.SourceRange) {
        return [pscustomobject]@{ Action = 'return_range'; Range = $State.SourceRange; Button = $null }
    }
    if ($State.OnSourcePage) {
        return [pscustomobject]@{ Action = 'wait_range_fade'; Range = $null; Button = $null }
    }
    if ($State.HasSourceButton -and $State.ButtonEnabled) {
        return [pscustomobject]@{ Action = 'click_source'; Range = $null; Button = $State.Button }
    }
    return [pscustomobject]@{ Action = 'wait_learning'; Range = $null; Button = $null }
}
