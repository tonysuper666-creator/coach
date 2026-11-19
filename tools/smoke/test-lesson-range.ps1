# 离线验证第⑥步的落点核对（不开客户端、不调模型）：按 TextbookReader.kt 的版式画出「对应教材第 F–L 页」，OCR 后判断是否含目标页。
# 用例取 #495 那次的两个相邻节：第 150–151 页不含第 154 页（旧判据按起始页距离误判为已落点），第 153–157 页含；读不到页段必须返回空。退出码 0 = 全过。
. (Join-Path $PSScriptRoot 'Win-Drive.ps1')
. (Join-Path $PSScriptRoot 'Lesson-Range.ps1')
$out = Join-Path ([IO.Path]::GetTempPath()) ('lesson-range-' + [guid]::NewGuid().ToString('N').Substring(0, 8)); New-Item -ItemType Directory $out | Out-Null
function Draw([string]$name, [string]$line) {
    $bmp = New-Object System.Drawing.Bitmap 1600, 600; $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.Clear([System.Drawing.Color]::FromArgb(250, 247, 240)); $g.TextRenderingHint = 'AntiAliasGridFit'
    $ink = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(40, 36, 32))
    $big = New-Object System.Drawing.Font 'Microsoft YaHei', 26; $small = New-Object System.Drawing.Font 'Microsoft YaHei', 16
    $g.DrawString('返回学习', $small, $ink, 40, 40)
    $g.DrawString('学习原文 · 第三章 第二节', $big, $ink, 40, 120)
    if ($line) { $g.DrawString($line, $small, $ink, 40, 220) }
    $g.DrawString('上一页      154 / 620      下一页', $small, $ink, 40, 500)
    $g.Dispose(); $p = Join-Path $out "$name.png"; $bmp.Save($p, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose(); $p
}
$target = 154
$cases = @(
    @{ n = 'neighbour-150-151'; line = '对应教材第 150–151 页 · 返回后保留学习进度'; range = '150..151'; has = $false },
    @{ n = 'target-153-157'; line = '对应教材第 153–157 页 · 返回后保留学习进度'; range = '153..157'; has = $true },
    @{ n = 'no-range-line'; line = ''; range = ''; has = $false })
$fail = 0
foreach ($c in $cases) {
    $lines = @(Read-Screen (Draw $c.n $c.line))
    $r = Get-LessonSourceRange $lines
    $got = if ($r) { '{0}..{1}' -f $r.First, $r.Last } else { '' }
    $has = Test-RangeHasPage $r $target
    $ok = ($got -eq $c.range) -and ($has -eq $c.has)
    '{0} {1}: range={2} containsTarget={3} expected={4}/{5}' -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $c.n, $(if ($got) { $got } else { 'none' }), $has, $(if ($c.range) { $c.range } else { 'none' }), $c.has
    if (-not $ok) { $fail++ }
}
# 「本节原文 · 第X页」按钮：题干和出题中提示也含「本节原文」，只能点按钮那一行。
function Draw-Learn([string]$name, [bool]$withButton) {
    $bmp = New-Object System.Drawing.Bitmap 1600, 600; $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.Clear([System.Drawing.Color]::FromArgb(250, 247, 240)); $g.TextRenderingHint = 'AntiAliasGridFit'
    $ink = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(40, 36, 32))
    $f = New-Object System.Drawing.Font 'Microsoft YaHei', 16
    $g.DrawString('正在按本节原文出题…', $f, $ink, 40, 60)
    $g.DrawString('根据本节原文，补全这句话：', $f, $ink, 40, 160)
    if ($withButton) { $g.DrawString('本节原文 · 第150页', $f, $ink, 40, 400) }
    $g.Dispose(); $p = Join-Path $out "$name.png"; $bmp.Save($p, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose(); $p
}
$btn = Find-LessonSourceButton @(Read-Screen (Draw-Learn 'button' $true))
$ok = [bool]($btn -and $btn.Y -gt 380 -and $btn.Y -lt 440)
'{0} source-button: y={1} expected=380..440' -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $(if ($btn) { $btn.Y } else { 'none' })
if (-not $ok) { $fail++ }
$none = Find-LessonSourceButton @(Read-Screen (Draw-Learn 'no-button' $false))
'{0} question-text-only: button={1} expected=none' -f $(if (-not $none) { 'PASS' } else { 'FAIL' }), $(if ($none) { $none.Y } else { 'none' })
if ($none) { $fail++ }
# OCR 有时把上方提示和按钮并成一行（#495 复跑的 6-sec-3），按钮仍要找得到。
$bmp = New-Object System.Drawing.Bitmap 1600, 300; $g = [System.Drawing.Graphics]::FromImage($bmp)
$g.Clear([System.Drawing.Color]::FromArgb(250, 247, 240)); $g.TextRenderingHint = 'AntiAliasGridFit'
$g.DrawString('先试着回答，答后再看讲解；原文可以主动打开。  本节原文 · 第157页', (New-Object System.Drawing.Font 'Microsoft YaHei', 16), (New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(40, 36, 32))), 40, 120)
$g.Dispose(); $mp = Join-Path $out 'merged.png'; $bmp.Save($mp, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
$merged = Find-LessonSourceButton @(Read-Screen $mp)
$ok = [bool]($merged -and $merged.X1 -gt 500)
'{0} merged-line-button: x1={1} expected>500' -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $(if ($merged) { $merged.X1 } else { 'none' })
if (-not $ok) { $fail++ }
# OCR 把同一颗按钮拆成两块（QA 回放 #495 第 5 跳失败帧：标签从 (848,376)、页码从 (961,376) 开始，且「页」被读成「贞」）。按 OCR 记录的形状造两块直接测。
function Rec([string]$text, [int]$x, [int]$y, [int]$charW = 21) {
    $ws = @(); $cx = $x
    foreach ($ch in $text.ToCharArray()) { $ws += [pscustomobject]@{ Text = [string]$ch; BoundingRect = [pscustomobject]@{ X = $cx; Y = $y; Width = $charW - 2; Height = 22 } }; $cx += $charW }
    [pscustomobject]@{ Text = $text; Words = $ws }
}
$split = Find-LessonSourceButton @((Rec '根据本节原文，补全这句话：' 300 160), (Rec '本节原文' 848 376), (Rec '第152贞' 961 376))
$ok = [bool]($split -and $split.X1 -eq 848 -and $split.X2 -ge 1000 -and $split.Y -gt 376 -and $split.Y -lt 400)
'{0} split-blocks-button: x1={1} x2={2} y={3} expected x1=848 x2>=1000' -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $split.X1, $split.X2, $split.Y
if (-not $ok) { $fail++ }
# 反例：出题中提示和不相干的「第X页」挨在同一行，或标签和页码不在同一行，都不能拼成按钮。
$busy = Find-LessonSourceButton @((Rec '正在按本节原文出题…' 300 60), (Rec '第150页' 520 60), (Rec '本节原文·' 848 376), (Rec '第153页' 961 470))
'{0} split-blocks-reject: button={1} expected=none' -f $(if (-not $busy) { 'PASS' } else { 'FAIL' }), $(if ($busy) { $busy.Y } else { 'none' })
if ($busy) { $fail++ }
$total = $cases.Count + 5
Remove-Item -Recurse -Force $out
"RESULT $($total - $fail)/$total target=$target"
exit $(if ($fail) { 1 } else { 0 })
