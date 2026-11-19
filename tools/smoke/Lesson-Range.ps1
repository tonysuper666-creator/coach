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
        if (($l.Text -replace '\s', '') -match '(本节原文[^第，,]{0,3}第.{1,8}页)$') { $m = Find-Text @($l) $Matches[1]; if ($m) { return $m } }
    }
    return $null
}
