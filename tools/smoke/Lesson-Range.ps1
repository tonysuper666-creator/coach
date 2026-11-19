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
