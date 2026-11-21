# 离线验证第⑥步的落点核对（不开客户端、不调模型）：按 TextbookReader.kt 的版式画出「对应教材第 F–L 页」，OCR 后判断是否含目标页。
# 用例取 #495 那次的两个相邻节：第 150–151 页不含第 154 页（旧判据按起始页距离误判为已落点），第 153–157 页含；读不到页段必须返回空。退出码 0 = 全过。
param([string]$BatchDir = '')
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

# 换节按钮可用性：ui.busy=true 时按钮按 Material3 disabled 降透明度（如 6-sec-4 的 0.38 对比度），必须判不可点。
function Draw-ThemeLearn([string]$name, [string]$title, [string]$btnLabel, [bool]$dark, [int]$btnAlpha) {
    $bmp = New-Object System.Drawing.Bitmap 1600, 500; $g = [System.Drawing.Graphics]::FromImage($bmp)
    $bg = if ($dark) { [System.Drawing.Color]::FromArgb(14, 13, 11) } else { [System.Drawing.Color]::FromArgb(250, 247, 240) }
    $g.Clear($bg); $g.TextRenderingHint = 'AntiAliasGridFit'
    $fg = if ($dark) { [System.Drawing.Color]::FromArgb(221, 215, 204) } else { [System.Drawing.Color]::FromArgb(26, 24, 20) }
    $sec = if ($dark) { [System.Drawing.Color]::FromArgb(140, 134, 125) } else { [System.Drawing.Color]::FromArgb(110, 105, 98) }
    $btnCol = [System.Drawing.Color]::FromArgb($btnAlpha, $fg.R, $fg.G, $fg.B)
    $g.DrawString($title, (New-Object System.Drawing.Font 'Microsoft YaHei', 22), (New-Object System.Drawing.SolidBrush $fg), 389, 275)
    $g.DrawString('先试着回答，答后再看讲解；原文可以主动打开。', (New-Object System.Drawing.Font 'Microsoft YaHei', 15), (New-Object System.Drawing.SolidBrush $sec), 389, 375)
    $g.DrawString($btnLabel, (New-Object System.Drawing.Font 'Microsoft YaHei', 15), (New-Object System.Drawing.SolidBrush $btnCol), 848, 375)
    $g.Dispose(); $p = Join-Path $out "$name.png"; $bmp.Save($p, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose(); $p
}
$extra = 0
foreach ($tc in @(
    @{ n = 'dark-disabled-6sec4'; title = '6.7 Section 2'; btn = '本节原文 · 第151页'; dark = $true; alpha = 97; expectEnabled = $false },
    @{ n = 'dark-enabled-6busy4'; title = '6.7 Section 3'; btn = '本节原文 · 第152页'; dark = $true; alpha = 255; expectEnabled = $true },
    @{ n = 'light-disabled'; title = '6.7 Section 2'; btn = '本节原文 · 第151页'; dark = $false; alpha = 97; expectEnabled = $false },
    @{ n = 'light-enabled'; title = '6.7 Section 3'; btn = '本节原文 · 第152页'; dark = $false; alpha = 255; expectEnabled = $true }
)) {
    $png = Draw-ThemeLearn $tc.n $tc.title $tc.btn $tc.dark $tc.alpha
    $st = Get-LessonLearningState @(Read-Screen $png) $png
    $ok = ($st.HasSourceButton -and ($st.ButtonEnabled -eq $tc.expectEnabled))
    '{0} {1}: enabled={2} expected={3}' -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $tc.n, $st.ButtonEnabled, $tc.expectEnabled
    if (-not $ok) { $fail++ }; $extra++
}

# 相邻节状态转移与 6-busy-3 / 6-find 原文页动作判定
$sPrev = [pscustomobject]@{ OnSourcePage = $false; SourceRange = $null; BusyGenerating = $false; HasSourceButton = $true; Button = @{ X = 947; Y = 385 }; ButtonEnabled = $true; ButtonPage = 151; Title = '6·7Section·2' }
$sStaleDisabled = [pscustomobject]@{ OnSourcePage = $false; SourceRange = $null; BusyGenerating = $false; HasSourceButton = $true; Button = @{ X = 946; Y = 385 }; ButtonEnabled = $false; ButtonPage = 151; Title = '6·7Section·2' }
$sStaleEnabled = [pscustomobject]@{ OnSourcePage = $false; SourceRange = $null; BusyGenerating = $false; HasSourceButton = $true; Button = @{ X = 947; Y = 385 }; ButtonEnabled = $true; ButtonPage = 151; Title = '6·7Section·2' }
$sNewEnabled = [pscustomobject]@{ OnSourcePage = $false; SourceRange = $null; BusyGenerating = $false; HasSourceButton = $true; Button = @{ X = 947; Y = 385 }; ButtonEnabled = $true; ButtonPage = 152; Title = '6·7Section·3' }
$sSamePageNewTitle = [pscustomobject]@{ OnSourcePage = $false; SourceRange = $null; BusyGenerating = $false; HasSourceButton = $true; Button = @{ X = 947; Y = 385 }; ButtonEnabled = $true; ButtonPage = 151; Title = '6·7Section·3' }
foreach ($t in @(
    @{ n = 'adjacent-reject-disabled-stale'; got = (Test-AdjacentLessonReady $sPrev $sStaleDisabled); expect = $false },
    @{ n = 'adjacent-reject-same-section'; got = (Test-AdjacentLessonReady $sPrev $sStaleEnabled); expect = $false },
    @{ n = 'adjacent-accept-new-section'; got = (Test-AdjacentLessonReady $sPrev $sNewEnabled); expect = $true },
    @{ n = 'adjacent-accept-title-changed'; got = (Test-AdjacentLessonReady $sPrev $sSamePageNewTitle); expect = $true }
)) {
    '{0} {1}: ready={2} expected={3}' -f $(if ($t.got -eq $t.expect) { 'PASS' } else { 'FAIL' }), $t.n, $t.got, $t.expect
    if ($t.got -ne $t.expect) { $fail++ }; $extra++
}

$sFade = [pscustomobject]@{ OnSourcePage = $true; SourceRange = $null; BusyGenerating = $false; HasSourceButton = $false; Button = $null; ButtonEnabled = $false; ButtonPage = 0; Title = '' }
$sFind152 = [pscustomobject]@{ OnSourcePage = $true; SourceRange = [pscustomobject]@{ First = 152; Last = 153 }; BusyGenerating = $false; HasSourceButton = $false; Button = $null; ButtonEnabled = $false; ButtonPage = 0; Title = '' }
$actWait = Resolve-SourcePageRangeAction $sStaleDisabled
$actClick = Resolve-SourcePageRangeAction $sNewEnabled
$actFade = Resolve-SourcePageRangeAction $sFade
$actFind = Resolve-SourcePageRangeAction $sFind152
$retReclick = Resolve-SourceReturnAction $false $true ([pscustomobject]@{ X = 91; Y = 42 })
foreach ($a in @(
    @{ n = 'action-wait-disabled'; ok = ($actWait.Action -eq 'wait_learning') },
    @{ n = 'action-click-enabled'; ok = ($actClick.Action -eq 'click_source') },
    @{ n = 'action-wait-fade-6busy3'; ok = ($actFade.Action -eq 'wait_range_fade') },
    @{ n = 'action-read-before-return-6find'; ok = ($actFind.Action -eq 'return_range' -and $actFind.Range.First -eq 152 -and $actFind.Range.Last -eq 153 -and -not (Test-RangeHasPage $actFind.Range $target)) },
    @{ n = 'return-already-back-ignores-focus-loss'; ok = ((Resolve-SourceReturnAction $true $true ([pscustomobject]@{ X = 91; Y = 42 })).Action -eq 'done') },
    @{ n = 'return-reclicks-fresh-coords-after-focus-loss'; ok = ($retReclick.Action -eq 'reclick' -and $retReclick.Button.X -eq 91 -and $retReclick.Button.Y -eq 42) },
    @{ n = 'return-no-reclick-without-focus-loss'; ok = ((Resolve-SourceReturnAction $false $false ([pscustomobject]@{ X = 91; Y = 42 })).Action -eq 'wait_learning') },
    @{ n = 'return-waits-when-lost-and-no-button'; ok = ((Resolve-SourceReturnAction $false $true $null).Action -eq 'wait_learning') }
)) {
    '{0} {1}' -f $(if ($a.ok) { 'PASS' } else { 'FAIL' }), $a.n
    if (-not $a.ok) { $fail++ }; $extra++
}

# 补点前的页面身份复核：放锁后界面可能已切回做题学，旧坐标落在「Coach目录返回书架」上，不能盲点。
function Ocr([string]$text, [int]$x, [int]$y) {
    [pscustomobject]@{ Text = $text; Words = @([pscustomobject]@{ Text = $text; BoundingRect = [pscustomobject]@{ X = $x; Y = $y; Width = 120; Height = 22 } }) }
}
$reDone = Resolve-SourceReturnRecheck @((Ocr '本节原文 · 第150页' 940 385), (Ocr '在做题学' 700 40))
$reSrc = Resolve-SourceReturnRecheck @((Ocr '返回学习' 40 40), (Ocr '学习原文 · 第三章 第二节' 40 120))
$reBlank = Resolve-SourceReturnRecheck @((Ocr 'Coach目录' 40 40))
$reBoth = Resolve-SourceReturnRecheck @((Ocr '返回学习' 40 40), (Ocr '本节原文' 940 385))
foreach ($a in @(
    @{ n = 'return-recheck-done-on-learning'; ok = ($reDone.Action -eq 'done') },
    @{ n = 'return-recheck-reclick-on-source'; ok = ($reSrc.Action -eq 'reclick' -and $reSrc.Button.X -eq 100 -and $reSrc.Button.Y -eq 51) },
    @{ n = 'return-recheck-waits-on-transition'; ok = ($reBlank.Action -eq 'wait') },
    @{ n = 'return-recheck-prefers-learning-when-both'; ok = ($reBoth.Action -eq 'done') }
)) {
    '{0} {1}' -f $(if ($a.ok) { 'PASS' } else { 'FAIL' }), $a.n
    if (-not $a.ok) { $fail++ }; $extra++
}

# 第⑥步选择题选项点击：能读出字母时走 letter；深色主题漏读 A-D 字母时兜底点「查看对应原文」右侧缩进正文。
$optWithLetter = Find-PracticeOptionClick @(
    (Rec '查看对应原文' 121 433),
    (Rec '这题我是猜的' 237 433),
    (Rec 'A' 120 497),
    (Rec '教育行业' 148 497)
) 121 449
$optDarkNoLetter = Find-PracticeOptionClick @(
    (Rec '根据这段材料提供的信息，下列哪一项是正确的？' 108 110),
    (Rec '查看对应原文' 121 151),
    (Rec '这题我是猜的' 237 151),
    (Rec '这是医疗卫生部门的人力资本研究。' 148 210),
    (Rec '这是一本书中的章节。' 149 296),
    (Rec '继续下一节' 209 493)
) 121 167
$optShortMode = Find-PracticeOptionClick @(
    (Rec '简答自检' 108 110),
    (Rec '查看对应原文' 121 151),
    (Rec '写下你的理解' 148 210)
) 121 167
$optAnswered = Find-PracticeOptionClick @(
    (Rec '查看对应原文' 121 151),
    (Rec '你选择：这是医疗卫生部门的人力资本研究。' 148 210)
) 121 167
$optBottomNavOnly = Find-PracticeOptionClick @(
    (Rec '查看对应原文' 121 151),
    (Rec '继续下一节' 148 493)
) 121 167
foreach ($oc in @(
    @{ n = 'practice-opt-letter-mode'; ok = ($optWithLetter -and $optWithLetter.Mode -eq 'letter' -and $optWithLetter.X -eq 165 -and $optWithLetter.Y -eq 509) },
    @{ n = 'practice-opt-text-fallback'; ok = ($optDarkNoLetter -and $optDarkNoLetter.Mode -eq 'option-text' -and $optDarkNoLetter.X -eq 172 -and $optDarkNoLetter.Y -eq 221) },
    @{ n = 'practice-opt-reject-short-mode'; ok = (-not $optShortMode) },
    @{ n = 'practice-opt-reject-answered'; ok = (-not $optAnswered) },
    @{ n = 'practice-opt-reject-nav-only'; ok = (-not $optBottomNavOnly) }
)) {
    '{0} {1}' -f $(if ($oc.ok) { 'PASS' } else { 'FAIL' }), $oc.n
    if (-not $oc.ok) { $fail++ }; $extra++
}

# 书架右栏的「做题学」入口：整词要认，OCR 把「继续做题学」读成「续做韪学」也要认（11-21 06:57 L2），
# 但不能把同栏的「边看边学」「开始学」「看书」当成它。
$shelfWhole = Find-ShelfPracticeEntry @(
    (Rec '看书  翻开第 150 页，可就原文段落提问。' 620 300),
    (Rec '边看边学  先看讲解，再选择是否做题。' 620 360),
    (Rec '做题学  先试题，答后看讲解；原文按需打开。' 620 420)
)
$shelfOcr = Find-ShelfPracticeEntry @(
    (Rec '边看边学  先看讲解，再选择是否做题。' 620 360),
    (Rec '续做韪学  先试题，答后看讲解；原文按需打开。' 620 420)
)
$shelfNone = Find-ShelfPracticeEntry @(
    (Rec '看书  翻开第 150 页，可就原文段落提问。' 620 300),
    (Rec '边看边学  先看讲解，再选择是否做题。' 620 360),
    (Rec '开始学' 620 520)
)
foreach ($sc in @(
    @{ n = 'shelf-entry-whole-label'; ok = ([bool]$shelfWhole -and $shelfWhole.Y -gt 400 -and $shelfWhole.Y -lt 440) },
    @{ n = 'shelf-entry-ocr-variant'; ok = ([bool]$shelfOcr -and $shelfOcr.Y -gt 400 -and $shelfOcr.Y -lt 440) },
    @{ n = 'shelf-entry-ignores-others'; ok = (-not $shelfNone) }
)) {
    '{0} {1}' -f $(if ($sc.ok) { 'PASS' } else { 'FAIL' }), $sc.n
    if (-not $sc.ok) { $fail++ }; $extra++
}

# 第 4 步切视图写库失败那条提示（viewSaveError）不是 toast，等不会消失；按提示切一次视图才是重试。
# 这一帧 OCR 会把「阅读视图暂未保存」读成「克读视图兩耒保存」（11-21 06:53 L4）。
$vsClean = @(
    (Rec '看原页' 120 96),
    (Rec '开始学' 300 96),
    (Rec '第一节 法与经济学' 620 200)
)
$vsText = @(
    (Rec '看原页' 120 96),
    (Rec '开始学' 300 96),
    (Rec '阅读视图暂未保存。切换视图后会再试一次。' 120 150)
)
$vsOcr = @(
    (Rec '看文字' 120 96),
    (Rec '开始学' 300 96),
    (Rec '克读视图兩耒保存。切换视图后会再试一次。' 120 150)
)
$vsNoToggle = @( (Rec '阅读视图暂未保存。切换视图后会再试一次。' 120 150) )
$actClean = Resolve-ViewSaveErrorAction $vsClean 0
$actText = Resolve-ViewSaveErrorAction $vsText 0
$actOcr = Resolve-ViewSaveErrorAction $vsOcr 0
$actTwice = Resolve-ViewSaveErrorAction $vsText 2
$actBlind = Resolve-ViewSaveErrorAction $vsNoToggle 0
foreach ($vc in @(
    @{ n = 'viewsave-none-when-clean'; ok = ($actClean.Action -eq 'none') },
    @{ n = 'viewsave-retry-text-view'; ok = ($actText.Action -eq 'retry' -and $actText.Flip -eq '看文字' -and $actText.Button.X -gt 100 -and $actText.Button.X -lt 200) },
    @{ n = 'viewsave-retry-ocr-variant'; ok = ($actOcr.Action -eq 'retry' -and $actOcr.Flip -eq '看原页' -and $actOcr.Button.X -gt 100 -and $actOcr.Button.X -lt 200) },
    @{ n = 'viewsave-fails-after-two'; ok = ($actTwice.Action -eq 'fail' -and $actTwice.Reason -match '视图') },
    @{ n = 'viewsave-fails-without-toggle'; ok = ($actBlind.Action -eq 'fail' -and $actBlind.Reason -match '视图') }
)) {
    '{0} {1}' -f $(if ($vc.ok) { 'PASS' } else { 'FAIL' }), $vc.n
    if (-not $vc.ok) { $fail++ }; $extra++
}

if ($BatchDir) {
    $bStep4 = Join-Path $BatchDir '6-busy-step-4.png'
    $bSec4 = Join-Path $BatchDir '6-sec-4.png'
    $bBusy4 = Join-Path $BatchDir '6-busy-4.png'
    $bBusy3 = Join-Path $BatchDir '6-busy-3.png'
    $bFind = Join-Path $BatchDir '6-find.png'
    $stStep4 = Get-LessonLearningState @(Read-Screen $bStep4) $bStep4
    $stSec4 = Get-LessonLearningState @(Read-Screen $bSec4) $bSec4
    $stBusy4 = Get-LessonLearningState @(Read-Screen $bBusy4) $bBusy4
    $stBusy3 = Get-LessonLearningState @(Read-Screen $bBusy3) $bBusy3
    $stFind = Get-LessonLearningState @(Read-Screen $bFind) $bFind
    $rFind = Resolve-SourcePageRangeAction $stFind
    foreach ($bc in @(
        @{ n = 'batch-6sec4-not-ready'; ok = (-not $stSec4.ButtonEnabled -and -not (Test-AdjacentLessonReady $stStep4 $stSec4)) },
        @{ n = 'batch-6busy4-ready'; ok = ($stBusy4.ButtonEnabled -and (Test-AdjacentLessonReady $stStep4 $stBusy4) -and $stBusy4.ButtonPage -eq 152) },
        @{ n = 'batch-6busy3-fade'; ok = ((Resolve-SourcePageRangeAction $stBusy3).Action -eq 'wait_range_fade') },
        @{ n = 'batch-6find-reads-152-153'; ok = ($rFind.Action -eq 'return_range' -and $rFind.Range.First -eq 152 -and $rFind.Range.Last -eq 153 -and -not (Test-RangeHasPage $rFind.Range $target)) }
    )) {
        '{0} {1}' -f $(if ($bc.ok) { 'PASS' } else { 'FAIL' }), $bc.n
        if (-not $bc.ok) { $fail++ }; $extra++
    }
}

$total = $cases.Count + 5 + $extra
Remove-Item -Recurse -Force $out
"RESULT $($total - $fail)/$total target=$target"
exit $(if ($fail) { 1 } else { 0 })
