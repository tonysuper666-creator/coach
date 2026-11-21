# 离线回归：第 5 步入口那三下的重试闭环、弹窗内定位、第 2 步「上传教材」补点、第 7 步三下的状态确认（退出码：0 = 全部通过）。
#  - 状态没变才算没点成：Done 一成立就停手，不重复点；
#  - 位置找不到就不再拿旧坐标点第二下（否则会点在弹窗关掉之后的页面上）；
#  - 「边看边学」只认弹窗里和「看书」「做题学」并排的那个 chip，不认书架右栏那一行；
#  - 工具栏被 ui.busy 禁用的十几秒里按钮还在，靠多给几次机会 + 每次隔一拍扛过去；
#  - 「上传教材」只认原生选书框弹出来，没弹就重定位补点，弹过以后不重点；
#  - 第 7 步的出处/收起/切回文字点落空时补点，点满就返回假，不 throw（后面几步还要跑）；
#  - 第 4 步「讲讲」/「允许并讲解」点落空时补点，只认同意框、加载态、回答这三种看得见的状态；
#  - 同意框的按钮字小，OCR 会把「允许并讲解」认成「允许百甫」「允许并讲鱈」：判断框在不在认标题和「不允许」，
#    落点从整词退化到「允许并」再到标题下面含「允许」的那一行，「关没关」要标题和两个按钮都不在；
#  - 弹窗淡出的过渡帧里标题还剩残影、按钮已经不在这一帧：判「暂不开启」要连拍几帧，不拿单帧判死；
#  - 底栏「开始边看边学」进提交态会被 OCR 读成「始边边下」「万始边边」：认它先整词、再「开始边看」截断、
#    再「这次怎么读」下面含「始边」的最低一行（弹窗 chip 在上面，不会误认），最后才用放大两倍的暗字识别；
#  - 弹窗还在时按钮一时认不出来，就隔一拍重拍等文字恢复或弹窗自己关（-stuckSec），不因一次认不出就 break；
#    真等到头还认不出才报「认不出来了」，不拿旧坐标再点；
#  - 服务端拒答「回答缺少可核对的页码依据，暂时无法确认」要认（含 OCR 错字「依框，暫时无氵去确认」），
#    带「再试一次」就先重放一次，第二次还拒就带着原话失败，不干等满 150 秒；
#  - 追问找标题时见过「思考」就不再往回滚（那会把刚追加的回答推出视野）。
# 函数体从 friend-path-worker.ps1 里按原文截取，屏幕与输入都换成本文件的假实现。
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
. (Join-Path $repo 'tools/smoke/Win-Drive.ps1')

$results = New-Object System.Collections.ArrayList
function Check([string]$name, [bool]$ok, [string]$detail = '') {
    [void]$results.Add($ok)
    $tag = if ($ok) { 'PASS' } else { 'FAIL' }
    "$tag $name$(if ($detail) { " ($detail)" })"
}

# 把 Get-LineBox / Find-OnRow / Click-Label / Click-Until / Find-DialogPath / Find-Step5Confirm / 第 5 步拒答判定原样取出来跑。
$src = Get-Content -LiteralPath (Join-Path $repo 'tools/smoke/friend-path-worker.ps1') -Raw -Encoding UTF8
$start = $src.IndexOf('function Get-LineBox')
$end = $src.IndexOf('$env:JAVA_HOME', $start)
if ($start -lt 0 -or $end -lt 0) { throw 'friend-path-worker.ps1: step-5 entry helpers not found' }
. ([scriptblock]::Create($src.Substring($start, $end - $start)))

# 一行 OCR：一个词覆盖整行，坐标就是这一行的位置。
function New-Line([string]$text, [int]$x, [int]$y, [int]$w, [int]$h) {
    $rect = [pscustomobject]@{ X = $x; Y = $y; Width = $w; Height = $h }
    [pscustomobject]@{ Text = $text; Words = @([pscustomobject]@{ Text = $text; BoundingRect = $rect }) }
}

# 假屏幕：Shot 返回当前这一帧，Click-At 按本用例的脚本换屏——和真机一样，屏幕只因点击而变。
# $script:queue 非空时按帧序换屏（淡出过渡那种「这一帧没有、下一帧才有」的用例要用）。
$script:screen = @()
$script:queue = $null
$script:LastPng = 'frame.png'
$script:clicks = New-Object System.Collections.ArrayList
$script:onClick = $null
function Shot([int]$n, [string]$tag) {
    if ($script:queue -and $script:queue.Count -gt 0) {
        $script:screen = $script:queue[0]
        $script:queue = if ($script:queue.Count -gt 1) { @($script:queue[1..($script:queue.Count - 1)]) } else { @() }
    }
    $script:Last = $script:screen
    return , $script:screen
}
function Click-At($x, $y) { [void]$script:clicks.Add("$x,$y"); if ($script:onClick) { & $script:onClick } }
function Fail([int]$n, [string]$why) { throw "STEP_FAILED: $why" }
function Note([string]$m) { }
# 暗底灰字那一路（放大两倍再认）本文件不跑真缩图，默认认不出。
function Find-DimButton($png, $needle) { return $null }
# 原生选书框：本文件用计数代替真窗口——点够 $script:needClicks 下以后才算框真的开了。
$script:needClicks = 0
function Find-AppWindowByTitle([string]$prefix) {
    if ($script:clicks.Count -ge $script:needClicks) { return [IntPtr]1 }
    return [IntPtr]::Zero
}

$shelf = @(
    (New-Line '书架' 620 80 80 30),
    (New-Line '看书  翻开第 150 页，可就原文段落提问。' 620 300 520 26),
    (New-Line '边看边学  先看讲解，再选择是否做题。' 620 360 520 26),
    (New-Line '做题学  先试题，答后看讲解；原文按需打开。' 620 420 520 26),
    (New-Line '开始学' 620 520 100 30)
)
$dialog = @(
    (New-Line '书架' 620 80 80 30),
    (New-Line '看书  翻开第 150 页，可就原文段落提问。' 620 300 520 26),
    (New-Line '边看边学  先看讲解，再选择是否做题。' 620 360 520 26),
    (New-Line '做题学  先试题，答后看讲解；原文按需打开。' 620 420 520 26),
    (New-Line '开始学这本教材' 420 560 200 34),
    (New-Line '从教材原文生成学习路线，按顺序理解、回忆和继续。' 420 610 420 22),
    (New-Line '这次怎么读' 420 660 120 22),
    (New-Line '看书' 420 700 60 30),
    (New-Line '边看边学' 496 700 110 30),
    (New-Line '做题学' 622 700 80 30),
    (New-Line '开始做题学' 900 780 130 30)
)

# 1. 弹窗里的「边看边学」和「看书」「做题学」并排；书架右栏那三行是上下排开的。
$chip = Find-DialogPath $dialog '边看边学'
Check 'dialog-path-picks-the-chip-row' ($chip -and $chip.Y -eq 715 -and $chip.X -eq 551) "got=$($chip.X),$($chip.Y)"
Check 'dialog-path-ignores-shelf-rows' (-not (Find-DialogPath $shelf '边看边学')) "found=$(Find-DialogPath $shelf '边看边学')"

$done = { param($l) [bool](Find-Text $l '这次怎么读') }
$find = { param($l) Find-Text $l '开始学' }

# 2. Done 一开始就成立：一下都不点。
$script:screen = $dialog; $script:clicks.Clear(); $script:onClick = $null
Click-Until 5 $find $done '入口没开' -sec 1 -tries 2
Check 'click-until-no-click-when-already-done' ($script:clicks.Count -eq 0) "clicks=$($script:clicks.Count)"

# 3. 第一下就生效：只点一次，点的是按钮中心。
$script:screen = $shelf; $script:clicks.Clear(); $script:onClick = { $script:screen = $dialog }
Click-Until 5 $find $done '入口没开' -sec 1 -tries 3
Check 'click-until-stops-after-one-effective-click' ($script:clicks.Count -eq 1 -and $script:clicks[0] -eq '670,535') "clicks=$($script:clicks -join '|')"

# 4. 点了没反应、按钮又没了：不再拿旧坐标补点，直接失败。
$script:screen = $shelf; $script:clicks.Clear()
$script:onClick = { $script:screen = @((New-Line '正在打开…' 420 660 120 22)) }
$err = ''
try { Click-Until 5 $find $done '入口没开' -sec 1 -tries 3 } catch { $err = "$_" }
Check 'click-until-never-reuses-a-stale-point' ($script:clicks.Count -eq 1 -and $err -like 'STEP_FAILED*入口没开*') "clicks=$($script:clicks -join '|') err=$err"

# 5. 按钮从来就没出现过：一下都不点，说清是找不到。
$script:screen = @((New-Line '书架' 620 80 80 30)); $script:clicks.Clear(); $script:onClick = $null
$err = ''
try { Click-Until 5 $find $done '找不到「开始学」' -sec 1 -tries 2 } catch { $err = "$_" }
Check 'click-until-no-click-when-label-absent' ($script:clicks.Count -eq 0 -and $err -like 'STEP_FAILED*找不到「开始学」*') "clicks=$($script:clicks.Count) err=$err"

# 6. 前一下没生效：按新一帧重点，直到 Done 成立。
$script:screen = $shelf; $script:clicks.Clear(); $script:onClick = $null
$script:swallow = 1
$script:onClick = { if ($script:swallow -gt 0) { $script:swallow-- } else { $script:screen = $dialog } }
Click-Until 5 $find $done '入口没开' -sec 1 -tries 4
Check 'click-until-retries-until-done' ($script:clicks.Count -eq 2) "clicks=$($script:clicks.Count)"

# 7. 工具栏被 ui.busy 禁用（第 4 步切回文字视图后后台写库）：点 5 下都没反应、第 6 下才开，
#    第 5 步入口靠 8 次机会 + 每次隔一拍扛过去。
$script:screen = $shelf; $script:clicks.Clear(); $script:swallow = 5
$script:onClick = { if ($script:swallow -gt 0) { $script:swallow-- } else { $script:screen = $dialog } }
Click-Until 5 $find $done '入口没开' -sec 1 -tries 8 -pauseMs 1
Check 'click-until-rides-out-busy-toolbar' ($script:clicks.Count -eq 6) "clicks=$($script:clicks.Count)"

# 8. 一直没反应：点满 $tries 就停手，不无限点。
$script:screen = $shelf; $script:clicks.Clear(); $script:onClick = $null
$err = ''
try { Click-Until 5 $find $done '入口没开' -sec 1 -tries 3 -pauseMs 1 } catch { $err = "$_" }
Check 'click-until-stops-after-tries' ($script:clicks.Count -eq 3 -and $err -like 'STEP_FAILED*') "clicks=$($script:clicks.Count) err=$err"

# 9. 重拍时选书框已经开着：一下都不点。
$script:screen = @((New-Line '上传教材' 300 120 120 30)); $script:clicks.Clear(); $script:needClicks = 0
Click-Until-Dialog 2 '上传教材' { (Find-AppWindowByTitle '上传教材') -ne [IntPtr]::Zero } -findSec 1 -sec 5 -tries 3 -openSec 1
Check 'upload-no-click-when-dialog-open' ($script:clicks.Count -eq 0) "clicks=$($script:clicks.Count)"

# 10. 第一下被别路抢前台吞掉（框没开）：按新一帧重定位补点，第二下开框。
$script:clicks.Clear(); $script:needClicks = 2
Click-Until-Dialog 2 '上传教材' { (Find-AppWindowByTitle '上传教材') -ne [IntPtr]::Zero } -findSec 1 -sec 20 -tries 3 -openSec 1
Check 'upload-reclicks-until-dialog-opens' ($script:clicks.Count -eq 2) "clicks=$($script:clicks.Count)"

# 11. 上传按钮根本不在：不点，直接说找不到。
$script:screen = @((New-Line '书架' 620 80 80 30)); $script:clicks.Clear(); $script:needClicks = 99
$err = ''
try { Click-Until-Dialog 2 '上传教材' { $false } -findSec 1 -sec 5 -tries 2 -openSec 1 } catch { $err = "$_" }
Check 'upload-fails-when-button-absent' ($script:clicks.Count -eq 0 -and $err -like '*找不到「上传教材」*') "clicks=$($script:clicks.Count) err=$err"

# ---- 第 7 步：出处 → 切回原页 / 收起 → 露出底栏 / 看文字 → 切回文字视图，三下都要认状态 ----
# 文字视图的右上是「看原页」，原页视图的右上是「看文字」；「出处」链接在卡片里。
$textView = @(
    (New-Line '书架' 620 80 80 30),
    (New-Line '看原页' 1200 40 80 28),
    (New-Line '出处 1 · 第 154 页' 159 385 210 24)
)
$origView = @(
    (New-Line '书架' 620 80 80 30),
    (New-Line '看文字' 1200 40 80 28),
    (New-Line '出处 1 · 第 154 页' 159 385 210 24)
)
$citeAnchor = Find-Text $textView '出处'
$citeFind = { param($l) if (Find-Text $l '看原页') { $citeAnchor } }
$citeDone = { param($l) [bool](Find-Text $l '看文字') }

# 12. 点出处那下被别路抢前台吞掉（仍停在「看原页」）：按状态重试，第二下切过去。
$script:screen = $textView; $script:clicks.Clear(); $script:swallow = 1
$script:onClick = { if ($script:swallow -gt 0) { $script:swallow-- } else { $script:screen = $origView } }
$ok = Click-Confirm 7 $citeFind $citeDone -tries 4 -waitSec 1 -pauseMs 1
Check 'step7-cite-retries-until-original-view' ($ok -and $script:clicks.Count -eq 2) "ok=$ok clicks=$($script:clicks.Count)"

# 13. 已经在原页视图：一下都不点。
$script:screen = $origView; $script:clicks.Clear(); $script:onClick = $null
$ok = Click-Confirm 7 $citeFind $citeDone -tries 3 -waitSec 1 -pauseMs 1
Check 'step7-cite-no-click-when-already-original' ($ok -and $script:clicks.Count -eq 0) "ok=$ok clicks=$($script:clicks.Count)"

# 14. 两个视图按钮都认不出来：不拿旧坐标乱点，返回假让调用方报。
$script:screen = @((New-Line '书架' 620 80 80 30), (New-Line '出处 1 · 第 154 页' 159 385 210 24))
$script:clicks.Clear(); $script:onClick = $null
$ok = Click-Confirm 7 $citeFind $citeDone -tries 3 -waitSec 1 -pauseMs 1
Check 'step7-cite-no-click-when-view-unknown' ((-not $ok) -and $script:clicks.Count -eq 0) "ok=$ok clicks=$($script:clicks.Count)"

$cardOpen = @(
    (New-Line '书架' 620 80 80 30),
    (New-Line '看文字' 1200 40 80 28),
    (New-Line '收起' 682 222 70 28),
    (New-Line '上一页' 900 900 70 28),
    (New-Line '154 / 620' 980 900 90 24),
    (New-Line '下一页' 1060 900 70 28)
)
$cardClosed = @($cardOpen | Where-Object { $_.Text -ne '收起' })
$cardFind = { param($l) Find-Text $l '收起' }
$cardDone = { param($l) -not (Find-Text $l '收起') }

# 15. 点收起只留下 hover 底色、卡片没动（L2 那次）：再点，直到「收起」消失。
$script:screen = $cardOpen; $script:clicks.Clear(); $script:swallow = 1
$script:onClick = { if ($script:swallow -gt 0) { $script:swallow-- } else { $script:screen = $cardClosed } }
$ok = Click-Confirm 7 $cardFind $cardDone -tries 4 -waitSec 1 -pauseMs 1
Check 'step7-card-close-retries-until-gone' ($ok -and $script:clicks.Count -eq 2) "ok=$ok clicks=$($script:clicks.Count)"

# 16. 点满也不 throw：第 7 步的失败要留到最后报，后面几步还得跑。
$script:screen = $cardOpen; $script:clicks.Clear(); $script:onClick = $null
$err = ''; $ok = $true
try { $ok = Click-Confirm 7 $cardFind $cardDone -tries 3 -waitSec 1 -pauseMs 1 } catch { $err = "$_" }
Check 'step7-card-close-gives-up-quietly' ((-not $ok) -and -not $err -and $script:clicks.Count -eq 3) "ok=$ok clicks=$($script:clicks.Count) err=$err"

# 17. 切回文字视图：按钮叫「看文字」，点一下变成「看原页」就收手。
$script:screen = $origView; $script:clicks.Clear(); $script:onClick = { $script:screen = $textView }
$ok = Click-Confirm 7 { param($l) Find-Text $l '看文字' } { param($l) [bool](Find-Text $l '看原页') } -tries 3 -waitSec 1 -pauseMs 1
Check 'step7-back-to-text-one-click' ($ok -and $script:clicks.Count -eq 1) "ok=$ok clicks=$($script:clicks.Count)"

# 18. 第 4 步「讲讲」只认工具条那一行里、在「举个例子」左边的那个；正文里别的「讲讲」不算。
$explainRow = @(
    (New-Line '讲讲 这一段课文的意思' 300 300 300 26),
    (New-Line '讲讲' 200 560 60 26),
    (New-Line '举个例子' 280 560 90 26),
    (New-Line '换个说法' 390 560 90 26)
)
$hit = Find-ExplainButton $explainRow
Check 'step4-explain-picks-toolbar-row' ($hit -and [Math]::Abs($hit.Y - 560) -le 14) "y=$(if ($hit) { $hit.Y } else { 'null' })"
Check 'step4-explain-none-without-toolbar' ($null -eq (Find-ExplainButton @((New-Line '讲讲' 200 560 60 26)))) ''

# 19. 「讲讲」生效只认看得见的状态：同意框 / 加载态 / 回答出来了；工具条还挂着就是没点上。
$consentFrame = @(
    (New-Line '这段原文会发到哪里' 400 400 300 26),
    (New-Line '允许并讲解' 500 500 140 30),
    (New-Line '先不' 380 500 80 30)
)
$loadingFrame = @((New-Line '思考中' 300 600 90 24))
Check 'step4-started-on-consent' (Test-ExplainStarted $consentFrame) ''
Check 'step4-started-on-loading' (Test-ExplainStarted $loadingFrame) ''
Check 'step4-not-started-on-toolbar' (-not (Test-ExplainStarted $explainRow)) ''

# 20. 点「讲讲」被吞掉一下：按状态重试，第二下弹出同意框。
$script:screen = $explainRow; $script:clicks.Clear(); $script:swallow = 1
$script:onClick = { if ($script:swallow -gt 0) { $script:swallow-- } else { $script:screen = $consentFrame } }
$ok = Click-Confirm 4 { param($l) Find-ExplainButton $l } { param($l) Test-ExplainStarted $l } -tries 3 -waitSec 1 -pauseMs 1
Check 'step4-explain-retries-until-consent' ($ok -and $script:clicks.Count -eq 2) "ok=$ok clicks=$($script:clicks.Count)"

# 21. 「允许并讲解」被吞掉一下：框没关就重定位再点，关上才算成。
$script:screen = $consentFrame; $script:clicks.Clear(); $script:swallow = 1
$script:onClick = { if ($script:swallow -gt 0) { $script:swallow-- } else { $script:screen = $loadingFrame } }
$ok = Click-Confirm 4 { param($l) Find-Text $l '允许并讲解' } { param($l) -not (Find-Text $l '允许并讲解') } -tries 3 -waitSec 1 -pauseMs 1
Check 'step4-allow-retries-until-dialog-closed' ($ok -and $script:clicks.Count -eq 2) "ok=$ok clicks=$($script:clicks.Count)"

# 22. 追问找标题：没见过加载态时下扫 9 格后往回扫 9 格再转回往下；见过加载态就一直往下，不倒滚回页顶。
$sweep = @(0, 8, 9, 17, 18) | ForEach-Object { Get-AskScrollDir $false $_ }
Check 'step4-ask-scroll-sweeps-both-ways' (($sweep -join ',') -eq '-1,-1,1,1,-1') "dirs=$($sweep -join ',')"
$downOnly = @(0, 9, 18, 27) | ForEach-Object { Get-AskScrollDir $true $_ }
Check 'step4-ask-scroll-never-backtracks-after-loading' (($downOnly -join ',') -eq '-1,-1,-1,-1') "dirs=$($downOnly -join ',')"

# 23. 同意框的按钮标签被 OCR 认错字（11-21 05:58 L1 实测两帧）：「允许百甫」「允许并讲鱈」。
$consentMis1 = @(
    (New-Line '这段原文会发到哪里' 400 380 300 26),
    (New-Line '允许百甫' 520 520 120 30)
)
$consentMis2 = @(
    (New-Line '这段原文会发到哪里' 400 380 300 26),
    (New-Line '不允许' 300 520 90 30),
    (New-Line '允许并讲鱈' 460 520 140 30)
)
Check 'step4-consent-up-on-misread-frames' ((Test-ConsentUp $consentMis1) -and (Test-ConsentUp $consentMis2)) ''
Check 'step4-consent-up-not-on-toolbar' (-not (Test-ConsentUp $explainRow)) ''

# 24. 认错字也必须落到「允许…」那个按钮上，不能点到左边的「不允许」。
$hit1 = Find-ConsentAllow $consentMis1
$hit2 = Find-ConsentAllow $consentMis2
$noBtn = Find-Text $consentMis2 '不允许'
Check 'step4-allow-found-when-third-char-garbled' ($hit1 -and [Math]::Abs($hit1.Y - 520) -le 20) "y=$(if ($hit1) { $hit1.Y } else { 'null' })"
Check 'step4-allow-not-the-cancel-button' ($hit2 -and $hit2.X -gt ([int]$noBtn.X + 40)) "x=$(if ($hit2) { $hit2.X } else { 'null' }) cancel=$([int]$noBtn.X)"

# 24b. 没有标题当锚就不往正文里的「允许」上点：那张框没认出来，宁可不点等下一帧。
$bodyAllow = @((New-Line '本课程允许携带计算器进入考场。' 300 620 400 28))
Check 'step4-allow-no-title-anchor-no-click' ($null -eq (Find-ConsentAllow $bodyAllow)) ''

# 25. 框关没关：错字帧算「还开着」（只看整词就会当成关了）；进了加载态才算关。
Check 'step4-consent-not-gone-on-misread-frame' (-not (Test-ConsentGone $consentMis1)) ''
Check 'step4-consent-gone-when-thinking' ((Test-ConsentGone $loadingFrame) -and (Test-ConsentGone $explainRow)) ''

# 26. 错字帧上点一下、屏换成加载态：一次就成，不再空等。
$script:screen = $consentMis2; $script:Last = $consentMis2; $script:queue = $null; $script:clicks.Clear()
$script:onClick = { $script:screen = $loadingFrame }
$ok = Click-Confirm 4 { param($l) Find-ConsentAllow $l } { param($l) Test-ConsentGone $l } -tries 3 -waitSec 1 -pauseMs 1
Check 'step4-allow-click-through-misread-frame' ($ok -and $script:clicks.Count -eq 1) "ok=$ok clicks=$($script:clicks.Count)"

# 27. 第 3 步弹窗淡出过渡：这一帧只有标题残影，下一帧按钮才齐 → 多拍一帧就拿到，不判死。
$ghost = @((New-Line '要开始学吗？' 380 300 200 26))
$dismissFull = @(
    (New-Line '要开始学吗？' 380 300 200 26),
    (New-Line '暂不开启' 380 420 120 30),
    (New-Line '开始学' 560 420 100 30)
)
$readerFrame = @((New-Line '下一页' 1060 900 70 28))
$findOff = { param($l) Find-Text $l '暂不开启' }
$goneOff = { param($l) -not (Find-Text $l '要开始学吗') }
$script:screen = $ghost; $script:Last = $ghost; $script:queue = @($dismissFull); $script:clicks.Clear()
$off = Find-AcrossFrames 3 'dismiss' $findOff $goneOff
Check 'step4-dismiss-waits-for-next-frame' ($off -and [Math]::Abs($off.Y - 420) -le 20 -and $script:clicks.Count -eq 0) "y=$(if ($off) { $off.Y } else { 'null' })"

# 28. 弹窗自己退掉了（按钮和标题都没了）：不点，也不再空拍。
$script:screen = $ghost; $script:Last = $ghost; $script:queue = @($readerFrame); $script:clicks.Clear()
$off = Find-AcrossFrames 3 'dismiss' $findOff $goneOff
Check 'step4-dismiss-stops-when-dialog-gone' (($null -eq $off) -and $script:queue.Count -eq 0) "off=$off left=$($script:queue.Count)"

# 29. 三帧都只有残影：返回空，由调用方决定（不在这里 throw），留着的标题能被调用方读到。
$script:screen = $ghost; $script:Last = $ghost; $script:queue = @($ghost, $ghost); $script:clicks.Clear()
$off = Find-AcrossFrames 3 'dismiss' $findOff $goneOff
Check 'step4-dismiss-null-while-still-up' (($null -eq $off) -and (Find-Text $script:Last '要开始学吗')) "off=$off"

# 30. 第 5 步底栏那个提交按钮在提交态被 OCR 读成「始边边下」「万始边边」（11-21 06:2x L2/L3/L4 都是）。
#     认它要退到「始边」残片，而且取标题下面最低那一行——不能落到弹窗里的「边看边学」chip 上。
$step5Verify = @(
    (New-Line '这次怎么读' 420 660 120 22),
    (New-Line '看书' 420 700 60 30),
    (New-Line '边看边学' 496 700 110 30),
    (New-Line '做题学' 622 700 80 30),
    (New-Line '开始边看边学' 900 780 130 30)
)
$step5Mis1 = @(
    (New-Line '这次怎么读' 420 660 120 22),
    (New-Line '边看边学' 496 700 110 30),
    (New-Line '始边边下' 900 780 110 30)
)
$step5Mis2 = @(
    (New-Line '这次怎么读' 420 660 120 22),
    (New-Line '边看边学' 496 700 110 30),
    (New-Line '万始边边' 900 780 110 30)
)
$step5ChipOnly = @(
    (New-Line '这次怎么读' 420 660 120 22),
    (New-Line '边看边学' 496 700 110 30)
)
$c0 = Find-Step5Confirm $step5Verify 'frame.png'
$c1 = Find-Step5Confirm $step5Mis1 'frame.png'
$c2 = Find-Step5Confirm $step5Mis2 'frame.png'
Check 'step5-confirm-exact-on-normal-frame' ($c0 -and $c0.Y -eq 795) "y=$(if ($c0) { $c0.Y } else { 'null' })"
Check 'step5-confirm-misread-still-finds-bottom-button' ($c1 -and $c1.Y -eq 795) "y=$(if ($c1) { $c1.Y } else { 'null' })"
Check 'step5-confirm-second-misread' ($c2 -and $c2.Y -eq 795) "y=$(if ($c2) { $c2.Y } else { 'null' })"
Check 'step5-confirm-ignores-dialog-chip' ($null -eq (Find-Step5Confirm $step5ChipOnly 'frame.png')) ''
Check 'step5-confirm-done-on-misread-frame' ([bool](Find-Step5Confirm $step5Mis1 'frame.png')) ''

# 31. 点完进了提交态、按钮标签一时认不出来（OCR 错字）：弹窗还开着就别当场 break，
#     等一拍文字恢复后补点第二下，第二下弹窗关掉才算成（旧实现只点 1 次就判死）。
$step5Closed = @((New-Line '书架' 620 80 80 30), (New-Line '开始学' 620 520 100 30))
$script:blind = 0
$findBlind = { param($l) if ($script:blind -gt 0) { $script:blind--; return $null }; Find-Step5Confirm $l 'frame.png' }
$done5 = { param($l) -not (Find-Text $l '这次怎么读') }
$script:screen = @($step5Verify); $script:Last = $step5Verify; $script:queue = $null; $script:clicks.Clear()
$script:onClick = { if ($script:clicks.Count -eq 1) { $script:blind = 2 }; if ($script:clicks.Count -eq 2) { $script:screen = $step5Closed } }
$err = ''
try { Click-Until 5 $findBlind $done5 '点了「开始边看边学」但弹窗没关' -sec 1 -tries 8 -pauseMs 1 -stuckSec 1 } catch { $err = "$_" }
Check 'click-until-waits-out-unreadable-button' ($script:clicks.Count -eq 2 -and -not $err) "clicks=$($script:clicks.Count) err=$err"

# 32. 按钮真的没了、弹窗也没关：等满 $stuckSec 还是认不出来就停手，消息里说清是「认不出来了」，
#     别谎报「点了 N 次状态没变」。
$script:blind = 0
$script:screen = @($step5Verify); $script:Last = $step5Verify; $script:queue = $null; $script:clicks.Clear()
$script:onClick = { if ($script:clicks.Count -eq 1) { $script:blind = 99 } }
$err = ''
try { Click-Until 5 $findBlind $done5 '点了「开始边看边学」但弹窗没关' -sec 1 -tries 8 -pauseMs 1 -stuckSec 1 } catch { $err = "$_" }
Check 'click-until-reports-lost-button' ($script:clicks.Count -eq 1 -and $err -like '*认不出来*') "clicks=$($script:clicks.Count) err=$err"

# 33. 等待期间弹窗自己关掉了：不再补点，直接算成。
$script:blind = 0
$script:screen = @($step5Verify); $script:Last = $step5Verify; $script:queue = $null; $script:clicks.Clear()
$script:onClick = { $script:screen = $step5Closed }
$err = ''
try { Click-Until 5 $findBlind $done5 '点了「开始边看边学」但弹窗没关' -sec 1 -tries 8 -pauseMs 1 -stuckSec 3 } catch { $err = "$_" }
Check 'click-until-done-while-waiting' ($script:clicks.Count -eq 1 -and -not $err) "clicks=$($script:clicks.Count) err=$err"

# 34. 第 5 步服务端拒答「回答缺少可核对的页码依据，暂时无法确认。」并给「再试一次」：
#     第一次点它重放，第二次还拒就把原话写进失败，不再干等满 150 秒（11-21 06:37 L1）。
#     OCR 会读成「依框，暫时无氵去确认」，所以认拒答要按残片和容错骨架认。
$refusalFull = @(
    (New-Line '回答缺少可核对的页码依据，暂时无法确认。' 480 520 700 30),
    (New-Line '再试一次' 700 570 120 30)
)
$refusalOcr = @(
    (New-Line '依框，暫时无氵去确认' 480 520 420 30),
    (New-Line '再试一次' 700 570 120 30)
)
$refusalNoRetry = @((New-Line '回答缺少可核对的页码依据，暂时无法确认。' 480 520 700 30))
$lectureBody = @(
    (New-Line '这段话的意思是作者用比喻说明时间的流逝。' 480 520 700 30),
    (New-Line '出处：第 150 页' 480 600 300 30)
)
$r0 = Resolve-LectureRefusalAction $refusalFull $false
$r1 = Resolve-LectureRefusalAction $refusalOcr $false
$r2 = Resolve-LectureRefusalAction $refusalFull $true
$r3 = Resolve-LectureRefusalAction $refusalNoRetry $false
$r4 = Resolve-LectureRefusalAction $lectureBody $false
Check 'lecture-refusal-retries-once-on-server-refusal' ($r0.Action -eq 'retry' -and [bool]$r0.Button) "action=$($r0.Action)"
Check 'lecture-refusal-matches-ocr-garble' ($r1.Action -eq 'retry' -and $r1.Reason -match '确认') "action=$($r1.Action) reason=$($r1.Reason)"
Check 'lecture-refusal-second-time-fails-fast' ($r2.Action -eq 'fail' -and [bool]$r2.Reason) "action=$($r2.Action)"
Check 'lecture-refusal-fails-when-no-retry-button' ($r3.Action -eq 'fail') "action=$($r3.Action)"
Check 'lecture-body-not-mistaken-for-refusal' ($r4.Action -eq 'none') "action=$($r4.Action)"

$fail = @($results | Where-Object { -not $_ }).Count
"RESULT checks=$($results.Count) fail=$fail"
exit ([int]($fail -gt 0))
