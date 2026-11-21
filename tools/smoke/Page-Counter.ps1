# 底栏页码：阅读器底栏「上一页  2 / 620  下一页」，字号小、又紧挨着「下一页」的浮层提示。
# Windows.Media.Ocr 整帧读时会把「620」认成「62@」，或者把页号拆成「2」+「/62@」两行；
# 11-21 03:49 的 2b 四路全栽在这里（页其实已经翻过去了，脚本以为页码没变）。
# 所以页号只从底栏「上一页」右沿到「下一页」左沿那一条里读：裁出来放大四倍单独认，
# 再把 OCR 常认错的字符换回来、按行拼起来匹配，拆行也能接上。
# 「3 / 620」这种分数在正文里也有，所以整帧兜底时仍要求一行就是页号，不做整帧拼接。
function Find-PageNav($lines, [string]$needle) {
    $best = $null; $any = $null
    foreach ($l in $lines) {
        $m = Find-Text @($l) $needle
        if (-not $m) { continue }
        if (-not $any -or $m.Y -gt $any.Y) { $any = $m }
        if (($l.Text -replace '\s', '') -match '(?i)page\s*(down|up)|pgdn|pgup') { continue }
        if (-not $best -or $m.Y -gt $best.Y) { $best = $m }
    }
    if ($best) { return $best }
    return $any
}
# 0 常被认成 @ / O / o，1 常被认成 l / I / |，全角斜杠也统一。
function Convert-OcrDigits([string]$t) {
    # 底栏字号小，实测 0 会被认成 @（11-21 03:49 L2-L4 都是「62@」），全角斜杠也常见。
    return ($t -replace '[OoóòÓÒQ@]', '0' -replace '[lI|]', '1' -replace '／', '/')
}
# 底栏那一条的文字拼成页号：先剔掉浮层提示，再把每行只留数字和斜杠后按顺序接起来。
# 「2」+「/62@」接成「2/620」；「上一页」「下一页」这种没有数字的行直接跳过。
function Get-CounterFromText([string[]]$texts) {
    $joined = ''
    foreach ($t in $texts) {
        $x = $t -replace '\s', ''
        if ($x -match '(?i)page\s*(down|up)|pgdn|pgup') { continue }
        $x = Convert-OcrDigits $x
        if ($x -notmatch '\d') { continue }
        $joined += ($x -replace '[^\d/]', '')
    }
    $mm = [regex]::Match($joined, '(\d{1,4})/(\d{1,4})')
    if (-not $mm.Success) { return $null }
    $cur = [int]$mm.Groups[1].Value; $total = [int]$mm.Groups[2].Value
    if ($cur -lt 1 -or $total -lt $cur) { return $null }
    return [pscustomobject]@{ Cur = $cur; Total = $total; Text = "$cur/$total" }
}
# 整帧兜底：一行必须从头到尾就是页号（正文里「2/620」这种分数不能拿来顶替）。
function Get-CounterFromLines($lines) {
    foreach ($l in $lines) {
        $t = Convert-OcrDigits ($l.Text -replace '\s', '')
        $mm = [regex]::Match($t, '^(\d{1,4})/(\d{1,4})$')
        if (-not $mm.Success) { continue }
        $cur = [int]$mm.Groups[1].Value; $total = [int]$mm.Groups[2].Value
        if ($cur -ge 1 -and $total -ge $cur) { return [pscustomobject]@{ Cur = $cur; Total = $total; Text = "$cur/$total" } }
    }
    return $null
}
# 只裁底栏那一条：左右到两个翻页按钮的内沿，上下各放宽一个字高；放大四倍（不行再六倍）再认。
# 只剩一个按钮可见（书首/书尾）时按它另一侧 16 个字宽估边界，和原来读原页号的做法一致。
function Get-CounterBand([string]$png, $Prev, $Next) {
    if (-not $Prev -and -not $Next) { return $null }
    $anchor = if ($Next) { $Next } else { $Prev }
    $height = [math]::Max(10, $anchor.Y2 - $anchor.Y1)
    $src = [Drawing.Bitmap]::new($png)
    $file = Join-Path (Split-Path -Parent $png) ('counter-' + [guid]::NewGuid().ToString('N').Substring(0, 8) + '.png')
    try {
        $left = [int][math]::Max(0, $(if ($Prev) { $Prev.X2 } else { $Next.X1 - 16 * $height }))
        $right = [int][math]::Min($src.Width, $(if ($Next) { $Next.X1 } else { $Prev.X2 + 16 * $height }))
        $top = [int][math]::Max(0, $anchor.Y1 - $height)
        $bottom = [int][math]::Min($src.Height, $anchor.Y2 + $height)
        $w = $right - $left; $h = $bottom - $top
        if ($w -lt 8 -or $h -lt 6) { return $null }
        $rect = [Drawing.Rectangle]::new($left, $top, $w, $h)
        # 四倍还认不出（斜杠被吞掉）就再来一次六倍：整帧读丢字符是这个字号的老毛病，
        # 放大一档基本都能救回来，比在没斜杠的一大串数字里猜拆分可靠。
        foreach ($scale in 4, 6) {
            $zoom = [Drawing.Bitmap]::new($w * $scale, $h * $scale)
            try {
                $g = [Drawing.Graphics]::FromImage($zoom)
                try {
                    $g.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                    $g.DrawImage($src, [Drawing.Rectangle]::new(0, 0, $zoom.Width, $zoom.Height), $rect, [Drawing.GraphicsUnit]::Pixel)
                } finally { $g.Dispose() }
                $zoom.Save($file, [Drawing.Imaging.ImageFormat]::Png)
                $got = Get-CounterFromText @(foreach ($line in Read-Screen $file) { $line.Text })
                if ($got) { return $got }
            } finally { $zoom.Dispose() }
        }
        return $null
    } finally {
        $src.Dispose()
        Remove-Item -LiteralPath $file -ErrorAction SilentlyContinue
    }
}
# 页号：先认底栏那一条，认不出再退回整帧单行匹配。返回 { Cur; Total; Text } 或 $null。
function Get-PageCounter($lines, [string]$png = '') {
    $prev = Find-PageNav $lines '上一页'; $next = Find-PageNav $lines '下一页'
    if ($png -and (Test-Path -LiteralPath $png) -and ($prev -or $next)) {
        $got = Get-CounterBand $png $prev $next
        if ($got) { return $got }
    }
    return Get-CounterFromLines $lines
}
