# 离线验证「提问对话框里找选段段落」的定位：自己画一张短页（不开客户端、不调模型），OCR 之后跑 Find-PickerParagraph。
# 三个用例：现在的副标题；旧副标题；同一段只出现在对话框后面的页面上（必须找不到）。退出码 0 = 全过。
. (Join-Path $PSScriptRoot 'Win-Drive.ps1')
$out = Join-Path ([IO.Path]::GetTempPath()) ('ask-picker-' + [guid]::NewGuid().ToString('N').Substring(0, 8)); New-Item -ItemType Directory $out | Out-Null
$sel = 'quiet river bends around the old stone bridge'
$words = (($sel -replace '[^A-Za-z ]', ' ').Trim() -split '\s+' | Select-Object -First 4) -join ''
function Draw([string]$name, [string]$subtitle, [bool]$inDialog) {
    $bmp = New-Object System.Drawing.Bitmap 1600, 1000; $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.Clear([System.Drawing.Color]::FromArgb(15, 15, 15)); $g.TextRenderingHint = 'AntiAliasGridFit'
    $white = [System.Drawing.Brushes]::White; $panel = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(36, 33, 30))
    $big = New-Object System.Drawing.Font 'Microsoft YaHei', 30; $mid = New-Object System.Drawing.Font 'Microsoft YaHei', 22; $latin = New-Object System.Drawing.Font 'Georgia', 26
    # 页面背景里的那份同样文字（在对话框外面）
    $g.DrawString('Quiet river bends around the old stone bridge', $latin, $white, 40, 300)
    $g.FillRectangle($panel, 500, 120, 700, 760)
    $g.DrawString('就这段问一句', $big, $white, 530, 150)
    $g.DrawString($subtitle, $mid, $white, 530, 230)
    $g.DrawString('LOCAL GARDEN NOTES 77', $latin, $white, 560, 320)
    if ($inDialog) { $g.DrawString('Quiet river bends around the old stone bridge', $latin, $white, 560, 450) }
    else { $g.DrawString('Tomatoes need steady sun and patient watering', $latin, $white, 560, 450) }
    $g.DrawString('回到阅读', $mid, $white, 1000, 800)
    $g.Dispose(); $p = Join-Path $out "$name.png"; $bmp.Save($p, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose(); $p
}
$cases = @(
    @{ n = 'new-subtitle'; sub = '点一段，再问一句'; inDialog = $true; expect = $true },
    @{ n = 'old-subtitle'; sub = '选择要问的原文段落'; inDialog = $true; expect = $true },
    @{ n = 'only-behind'; sub = '点一段，再问一句'; inDialog = $false; expect = $false })
$fail = 0
foreach ($c in $cases) {
    $png = Draw $c.n $c.sub $c.inDialog; $lines = @(Read-Screen $png)
    $hit = Find-PickerParagraph $lines $words
    $ok = ([bool]$hit) -eq $c.expect
    '{0} {1}: found={2} expected={3}' -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $c.n, [bool]$hit, $c.expect
    if (-not $ok) { $fail++ }
}
Remove-Item -Recurse -Force $out
"RESULT $($cases.Count - $fail)/$($cases.Count)"
exit $(if ($fail) { 1 } else { 0 })
