# 离线验证第③步的页号读取（不开客户端、不调模型）：整帧 OCR 会把底栏「2 / 620」拆成「2」+「/62@」
# 两行、把 0 认成 @（11-21 03:49 的 2b 四路全栽在这儿），所以页号改成裁「上一页」「下一页」之间
# 那一条放大认。这里先用纯文本喂拆行/容错用例，再画一张带浮层的底栏走一遍裁剪 + OCR。退出码 0 = 全过。
param([string]$BatchDir = '')
. (Join-Path $PSScriptRoot 'Win-Drive.ps1')
. (Join-Path $PSScriptRoot 'Page-Counter.ps1')
$out = Join-Path ([IO.Path]::GetTempPath()) ('page-counter-' + [guid]::NewGuid().ToString('N').Substring(0, 8)); New-Item -ItemType Directory $out | Out-Null
$fail = 0; $checks = 0
function Check([string]$name, $got, [string]$want) {
    $script:checks++
    $gotText = if ($null -eq $got) { 'none' } else { "$got" }
    $ok = $gotText -eq $want
    '{0} {1}: got={2} expected={3}' -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $name, $gotText, $want
    if (-not $ok) { $script:fail++ }
}
function Text-Of($c) { if ($c) { $c.Text } else { $null } }
# 实测形状：拆成「2」「/62@」两行；0 被认成 @；还有把整个页号接成一行但带别的字符的。
Check 'split-at-sign' (Text-Of (Get-CounterFromText @('2', '/62@'))) '2/620'
Check 'single-line-spaced' (Text-Of (Get-CounterFromText @('2 / 620'))) '2/620'
Check 'with-tooltip-line' (Text-Of (Get-CounterFromText @('下一页 → PageDown', '2', '/62@'))) '2/620'
Check 'letters-as-digits' (Text-Of (Get-CounterFromText @('12', '/62O'))) '12/620'
Check 'fullwidth-slash' (Text-Of (Get-CounterFromText @('154／620'))) '154/620'
# 斜杠整个丢了就不猜拆分：一大串数字里分不出哪几个是当前页。
Check 'lost-slash-is-null' (Text-Of (Get-CounterFromText @('2', '62@'))) 'none'
Check 'no-digits-is-null' (Text-Of (Get-CounterFromText @('上一页', '下一页'))) 'none'
# 整帧兜底只认「一行就是页号」，正文里的「3/620」不能被当成底栏。
$body = @(
    [pscustomobject]@{ Text = '公式 (3/620) 说明'; Words = @() },
    [pscustomobject]@{ Text = '正文'; Words = @() }
)
Check 'full-frame-ignores-body-fraction' (Text-Of (Get-CounterFromLines $body)) 'none'
$exact = @(
    [pscustomobject]@{ Text = '12 / 620'; Words = @() },
    [pscustomobject]@{ Text = '正文'; Words = @() }
)
Check 'full-frame-exact-line' (Text-Of (Get-CounterFromLines $exact)) '12/620'
# 裁剪 + OCR 走一遍：底栏画小字，上面还压着「下一页 → PageDown」的浮层（实测就是这样）。
$bmp = New-Object System.Drawing.Bitmap 1600, 600
$g = [System.Drawing.Graphics]::FromImage($bmp)
try {
    $g.Clear([System.Drawing.Color]::FromArgb(250, 247, 240)); $g.TextRenderingHint = 'AntiAliasGridFit'
    $ink = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(120, 116, 110))
    $body2 = New-Object System.Drawing.Font 'Microsoft YaHei', 22
    $small = New-Object System.Drawing.Font 'Microsoft YaHei', 13
    $g.DrawString('本节导读', $body2, $ink, 80, 120)
    $g.DrawString('PageDown', $small, $ink, 1290, 500)
    $g.DrawString('上一页', $small, $ink, 120, 556)
    $g.DrawString('12 / 620', $small, $ink, 780, 556)
    $g.DrawString('下一页', $small, $ink, 1400, 556)
} finally { $g.Dispose() }
$png = Join-Path $out 'footer.png'; $bmp.Save($png, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
$lines = @(Read-Screen $png)
$c = Get-PageCounter $lines $png
Check 'cropped-footer-ocr' (Text-Of $c) '12/620'
# 认不出两个翻页按钮时不能瞎读（书首/书尾才允许单边），此时要退回整帧那一行。
$only = @([pscustomobject]@{ Text = '12/620'; Words = @() })
Check 'no-nav-falls-back-to-frame' (Text-Of (Get-PageCounter $only $png)) '12/620'
Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
"RESULT checks=$checks fail=$fail"
exit ($(if ($fail -gt 0) { 1 } else { 0 }))
