# 离线回归：第 5 步入口那三下的重试闭环、弹窗内定位、第 2 步「导入文件」补点、第 7 步三下的状态确认、第 6 步题卡就绪（退出码：0 = 全部通过）。
#  - 状态没变才算没点成：Done 一成立就停手，不重复点；
#  - 位置找不到就不再拿旧坐标点第二下（否则会点在弹窗关掉之后的页面上）；
#  - 「边看边学」只认弹窗里和「看书」「做题学」并排的那个 chip，不认书架右栏那一行；
#  - 工具栏被 ui.busy 禁用的十几秒里按钮还在，靠多给几次机会 + 每次隔一拍扛过去；
#  - 「导入文件」只认原生选书框弹出来，没弹就重定位补点，弹过以后不重点；
#  - 第 7 步的出处/收起/切回文字点落空时补点，点满就返回假，不 throw（后面几步还要跑）；
#  - 第 4 步「讲讲」/「允许并讲解」点落空时补点，只认同意框、加载态、回答这三种看得见的状态；
#  - 同意框的按钮字小，OCR 会把「允许并讲解」认成「允许百甫」「允许并讲鱈」：判断框在不在认标题和「不允许」，
#    落点从整词退化到「允许并」再到标题下面含「允许」的那一行，「关没关」要标题和两个按钮都不在；
#  - 弹窗淡出的过渡帧里标题还剩残影、按钮已经不在这一帧：判「暂不开启」要连拍几帧，不拿单帧判死；
#  - 底栏「开始边看边学」进提交态会被 OCR 读成「始边边下」「万始边边」：认它先整词、再「开始边看」截断、
#    再「这次怎么读」下面含「始边」的最低一行（弹窗 chip 在上面，不会误认），最后才用放大两倍的暗字识别；
#  - 弹窗还在时按钮一时认不出来，就隔一拍重拍等文字恢复或弹窗自己关（-stuckSec），不因一次认不出就 break；
#    真等到头还认不出才报「认不出来了」，不拿旧坐标再点；
#  - 弹窗隔一拍才画出来时，等完那一拍也要判 Done；点不动且屏幕上这时才挂出别的状态（写库失败提示），
#    交给 -StallAction 当场处理一次再确认，不拿旧判断继续点；
#  - 服务端拒答「回答缺少可核对的页码依据，暂时无法确认」要认（含 OCR 错字「依框，暫时无氵去确认」），
#    带「再试一次」就先重放一次，第二次还拒就带着原话失败，不干等满 150 秒；
#  - 追问找标题时见过「思考」就不再往回滚（那会把刚追加的回答推出视野）；
#  - 第 2 步要在首页核对目标书名及同卡进度，另一本书的进度或阅读器底栏都不能替代；
#  - 第 7 步出处那行解析不出页码时直接失败，不许跳过跳页断言后还 Pass 7；
#  - 同一帧有两行出处时，要点解析出页码的那一行，不点更靠下的另一页；
#  - 「讲解」页签认抽屉顶和「批注」同一行的那颗；选中的「提问」被漏识时仍算，正文里的「讲解」不算；
#  - 跳页框的数字只认紧挨在「前往」左边的那一串，读到截断数字就不是目标页；
#  - 跳页框没确认是目标页就不按回车：点回框里重敲，框收起来先重按 Ctrl+G，四遍都没确认就失败。
# 函数体从 friend-path-worker.ps1 里按原文截取，屏幕与输入都换成本文件的假实现。
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
. (Join-Path $repo 'tools/smoke/Win-Drive.ps1')
. (Join-Path $repo 'tools/smoke/Page-Counter.ps1')

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
        # 一次直接赋值、别写成 if 表达式的输出：语句输出会把整屏数组拆成一行一行，
        # 队列里按「一屏」存的用例（一帧 5 行）就会退化成每帧只有一行。
        if ($script:queue.Count -gt 1) { $script:queue = @($script:queue[1..($script:queue.Count - 1)]) } else { $script:queue = @() }
    }
    $script:Last = $script:screen
    return , $script:screen
}
function Click-At($x, $y, [scriptblock]$BeforePress = $null) {
    # 和真驱动一样：按下之前先问一句「这颗按钮还在原位吗」，不在就整下作废、不点。
    if ($BeforePress -and -not (& $BeforePress)) { return $false }
    [void]$script:clicks.Add("$x,$y"); if ($script:onClick) { & $script:onClick }
    return $true
}
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
$script:screen = @((New-Line '导入文件' 300 120 120 30)); $script:clicks.Clear(); $script:needClicks = 0
Click-Until-Dialog 2 '导入文件' { (Find-AppWindowByTitle '导入文件') -ne [IntPtr]::Zero } -findSec 1 -sec 5 -tries 3 -openSec 1
Check 'upload-no-click-when-dialog-open' ($script:clicks.Count -eq 0) "clicks=$($script:clicks.Count)"

# 10. 第一下被别路抢前台吞掉（框没开）：按新一帧重定位补点，第二下开框。
$script:clicks.Clear(); $script:needClicks = 2
Click-Until-Dialog 2 '导入文件' { (Find-AppWindowByTitle '导入文件') -ne [IntPtr]::Zero } -findSec 1 -sec 20 -tries 3 -openSec 1
Check 'upload-reclicks-until-dialog-opens' ($script:clicks.Count -eq 2) "clicks=$($script:clicks.Count)"

# 11. 上传按钮根本不在：不点，直接说找不到。
$script:screen = @((New-Line '书架' 620 80 80 30)); $script:clicks.Clear(); $script:needClicks = 99
$err = ''
try { Click-Until-Dialog 2 '导入文件' { $false } -findSec 1 -sec 5 -tries 2 -openSec 1 } catch { $err = "$_" }
Check 'upload-fails-when-button-absent' ($script:clicks.Count -eq 0 -and $err -like '*找不到「导入文件」*') "clicks=$($script:clicks.Count) err=$err"

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

# 18. 第 4 步「讲讲」浮条：一行里同时有「讲讲 这一段课文的意思」和工具条上的「讲讲」。
# 1.1.1 那个「只认工具条那一行」的 Find-ExplainButton 已随浮条改版（4b5ff054）下线，
# 现在第 4 步用 Find-ZoomText（放大 3 倍认「讲讲」）+ Find-OnRow 定位；Find-OnRow 本身在
# 上面 dialog-path-* 两条里已经覆盖，这里保留这一帧给下面的启用判定用。
$explainRow = @(
    (New-Line '讲讲 这一段课文的意思' 300 300 300 26),
    (New-Line '讲讲' 200 560 60 26),
    (New-Line '举个例子' 280 560 90 26),
    (New-Line '换个说法' 390 560 90 26)
)

# 19. 「讲讲」生效只认看得见的状态：同意框 / 加载态 / 回答出来了；工具条还挂着就是没点上。
$consentFrame = @(
    (New-Line '这段原文会发到哪里' 400 400 300 26),
    (New-Line '允许并讲解' 500 500 140 30),
    (New-Line '先不' 380 500 80 30)
)
$loadingFrame = @((New-Line '正在整理…' 300 600 90 24))
Check 'step4-started-on-consent' (Test-ExplainStarted $consentFrame) ''
Check 'step4-started-on-loading' (Test-ExplainStarted $loadingFrame) ''
Check 'step4-not-started-on-toolbar' (-not (Test-ExplainStarted $explainRow)) ''

# 20. 点「讲讲」被吞掉一下：按状态重试，第二下弹出同意框。
# 落点仍按工具条那一行里最靠左的「讲讲」取（Find-OnRow），不再依赖已下线的 Find-ExplainButton。
$script:screen = $explainRow; $script:clicks.Clear(); $script:swallow = 1
$script:onClick = { if ($script:swallow -gt 0) { $script:swallow-- } else { $script:screen = $consentFrame } }
$toolbar20 = Find-Text $explainRow '举个例子'   # 同排锚：Find-OnRow 的 $ref 要带 Y 的框，不是 OCR 行本身
$ok = Click-Confirm 4 { param($l) Find-OnRow $l '讲讲' $toolbar20 } { param($l) Test-ExplainStarted $l } -tries 3 -waitSec 1 -pauseMs 1
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

# 34. 弹窗是隔一拍才画出来的：点完 1 秒的那一帧还没有、再等 2 秒的这一帧已经有了。
#     这一帧必须判 Done，否则又去认「开始学」（弹窗标题「开始学这本教材」也算命中），
#     下一轮就把刚开的弹窗点没了——11-21 07:22 L1/L4 的 5-entry5p/7p 就是这样。
$script:screen = $shelf; $script:clicks.Clear(); $script:onClick = $null
$script:queue = @($shelf, $shelf, $shelf, $dialog)
Click-Until 5 $find $done '入口没开' -sec 1 -tries 4 -pauseMs 1
Check 'click-until-done-on-pause-frame-stops-clicking' ($script:clicks.Count -eq 1) "clicks=$($script:clicks.Count)"
$script:queue = $null

# 35. 点不动是因为屏幕上这时才换了状态（写库失败后挂出的「阅读视图暂未保存」）：
#     -StallAction 在这一帧上处理一次，处理完按新一帧再确认，不再拿旧判断点第二下。
$script:screen = $shelf; $script:clicks.Clear(); $script:queue = $null
$script:stalls = 0
$stall = { param($l) $script:stalls++; $script:screen = $dialog; return $true }
Click-Until 5 $find $done '入口没开' -sec 1 -tries 3 -pauseMs 1 -StallAction $stall
Check 'click-until-stall-action-runs-then-verifies' ($script:clicks.Count -eq 1 -and $script:stalls -eq 1) "clicks=$($script:clicks.Count) stalls=$script:stalls"

# 36. 第 5 步服务端拒答「回答缺少可核对的页码依据，暂时无法确认。」并给「再试一次」：
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

# 37. Click-Confirm 的 -BeforePress：按下前那一帧状态已经变了就整下作废，且当场认 Done；
#     点落空（状态没变）时重定位再点，直到出现目标状态。
#     第 7 步点「出处」用的就是这一套——点完底栏还停在第 33 页时补点，页号一对上就停手。
$leaveScreen = @((New-Line '返回学习' 115 42 90 30))
$learnScreen = @((New-Line '返回书架' 115 42 90 30))
$script:screen = $leaveScreen; $script:Last = $leaveScreen; $script:queue = $null; $script:clicks.Clear(); $script:onClick = $null
$script:bpCalls = 0
$okVeto = Click-Confirm 6 { param($l) Find-Text $l '返回学习' } { param($l) [bool](Find-Text $l '返回书架') } -tries 3 -waitSec 1 -pauseMs 1 -BeforePress {
    $script:bpCalls++
    # 拍这一帧的时候界面已经换了：这一下不能再点，直接按新状态判定。
    $script:screen = $learnScreen; $script:Last = $learnScreen
    return $false
}
Check 'click-confirm-beforepress-vetoes-and-accepts-done' ($okVeto -and $script:clicks.Count -eq 0 -and $script:bpCalls -eq 1) "ok=$okVeto clicks=$($script:clicks.Count) bp=$($script:bpCalls)"

$script:screen = $leaveScreen; $script:Last = $leaveScreen; $script:queue = $null; $script:clicks.Clear()
$script:onClick = { if ($script:clicks.Count -ge 2) { $script:screen = $learnScreen } }
$okRetry = Click-Confirm 6 { param($l) Find-Text $l '返回学习' } { param($l) [bool](Find-Text $l '返回书架') } -tries 3 -waitSec 1 -pauseMs 1 -BeforePress {
    [bool](Find-Text $script:screen '返回学习') -and -not (Find-Text $script:screen '返回书架')
}
Check 'click-confirm-beforepress-retries-when-click-drops' ($okRetry -and $script:clicks.Count -eq 2) "ok=$okRetry clicks=$($script:clicks.Count)"

# 38. 第 2 步不能只凭「阅读器底栏读出页号」通过：书得真出现在 2.0 首页上。
#     20:05 评审指出旧逻辑在退回首页没读到书卡时仍 Pass 2，等于没验「书上首页」。
$homeRow = New-Line '6 页 · 读到第 2 页' 620 420 320 26
$v2card = Resolve-Step2HomeVerdict $homeRow '2'
$v2reader = Resolve-Step2HomeVerdict $null '2'
$v2none = Resolve-Step2HomeVerdict $null $null
Check 'step2-passes-only-with-home-card' ((-not $v2card.Fail) -and $v2card.Pass -match '书在首页上') "fail=$($v2card.Fail)"
Check 'step2-fails-when-home-card-missing' ([bool]$v2reader.Fail -and -not $v2reader.Pass) "fail=$($v2reader.Fail)"
Check 'step2-fails-when-nothing-read' ([bool]$v2none.Fail) "fail=$($v2none.Fail)"

$homeHeader = @((New-Line 'LearningCoach' 25 10 144 18), (New-Line 'LearningCoach' 80 68 144 22), (New-Line '设置' 920 68 55 22))
$booklist = @($homeHeader + @(
    (New-Line '继续' 820 235 50 22),
    (New-Line '模拟书单' 180 228 90 24),
    (New-Line '书单' 180 261 70 20),
    (New-Line 'HeaIth Economics' 180 441 240 26),
    (New-Line '620 页 · 读到第 154 页' 182 474 240 20)
))
$bookHit = Find-Step2HomeBook $booklist 'health-economics'
Check 'step2-booklist-opens-target-card-not-collection-continue' ($bookHit -and $bookHit.Click.Y -gt 400)
$focusBook = @($homeHeader + @(
    (New-Line 'Health Economics' 180 226 240 26),
    (New-Line 'Health Economics · 第 154 页' 180 261 280 20),
    (New-Line '继续' 820 247 50 22)
))
$focusHit = Find-Step2HomeBook $focusBook 'health-economics'
Check 'step2-focus-book-matches-its-own-continue' ($focusHit -and $focusHit.Click.X -gt 800)
$combinedFocus = @($homeHeader + @(
    (New-Line 'HeaIth Economics · 练习 · 第巧4页' 180 261 390 20),
    (New-Line '继续' 820 256 50 22)
))
Check 'step2-focus-book-accepts-combined-title-and-progress' ([bool](Find-Step2HomeBook $combinedFocus 'health-economics'))
Check 'step2-focus-without-continue-is-unverified' (-not (Find-Step2HomeBook $combinedFocus[0..3] 'health-economics'))
$cloudBook = @($homeHeader + @(
    (New-Line 'Health Economics' 180 226 240 24),
    (New-Line 'PDF · 云端书架 · 点击下载阅读' 180 255 280 20),
    (New-Line '其他书' 180 313 90 24),
    (New-Line '20 页 · 读到第 7 页' 180 342 240 20)
))
Check 'step2-rejects-cloud-card-and-unrelated-progress' (-not (Find-Step2HomeBook $cloudBook 'health-economics'))
Check 'step2-rejects-book-and-progress-outside-home' (-not (Find-Step2HomeBook $booklist[3..7] 'health-economics'))

# 39. 第 7 步：出处那行没解析出页码时不能跳过跳页断言（旧逻辑会一路走到 Pass 7）。
$citeOcr = New-Line '出 处 · 第 1 5 0 页' 480 600 300 30
Check 'cite-page-parsed-despite-ocr-spacing' ((Get-CitePageNumber $citeOcr.Text) -eq '150') "got=$(Get-CitePageNumber $citeOcr.Text)"
Check 'cite-page-empty-when-no-page' (-not (Get-CitePageNumber '出处 · 见上文')) "got=$(Get-CitePageNumber '出处 · 见上文')"
Check 'step7-fails-when-cite-page-missing' ([bool](Resolve-Step7PageVerdict '' '150')) "why=$(Resolve-Step7PageVerdict '' '150')"
Check 'step7-fails-when-counter-missing' ([bool](Resolve-Step7PageVerdict '150' $null)) "why=$(Resolve-Step7PageVerdict '150' $null)"
Check 'step7-fails-when-click-did-not-jump' ([bool](Resolve-Step7PageVerdict '150' '33')) "why=$(Resolve-Step7PageVerdict '150' '33')"
Check 'step7-passes-when-page-matches' (-not (Resolve-Step7PageVerdict '150' '150')) "why=$(Resolve-Step7PageVerdict '150' '150')"
Check 'step7-close-fails-when-page-unread' ([bool](Resolve-Step7AfterCloseVerdict '150' $null)) "why=$(Resolve-Step7AfterCloseVerdict '150' $null)"
Check 'step7-close-passes-on-same-page' (-not (Resolve-Step7AfterCloseVerdict '150' '150')) "why=$(Resolve-Step7AfterCloseVerdict '150' '150')"

# 40. 第 7 步的页码得从原始行里取：Find-All / Find-Text 只回坐标，旧写法 $cite.Text 一直是空，
#     页码从来没解析出来过（所以那一步一直是假绿）。抽屉里同时挂着引文行时，要挑带页码的那行。
$drawer = @(
    (New-Line '引文．"Generalhumanca••' 480 500 300 26),
    (New-Line '出处1．第154页．3段．"Generalhumanca•••' 480 600 320 26)
)
$plainCite = @((New-Line '出处' 480 600 60 26))
Check 'cite-line-picks-the-line-with-page' ((Find-CiteLine $drawer).Text -match '第154页') "got=$((Find-CiteLine $drawer).Text)"
Check 'cite-line-falls-back-to-plain-label' ((Find-CiteLine $plainCite).Text -eq '出处') "got=$((Find-CiteLine $plainCite).Text)"
Check 'cite-line-none-when-absent' (-not (Find-CiteLine @((New-Line '提问' 480 600 60 26)))) "found=$(Find-CiteLine @((New-Line '提问' 480 600 60 26)))"
Check 'step7-cite-page-from-real-drawer-line' ((Get-CitePageNumber (Find-CiteLine $drawer).Text) -eq '154') "got=$(Get-CitePageNumber (Find-CiteLine $drawer).Text)"
$drawerMisread = @((New-Line '出处2·第153贞·第12段“' 480 600 320 26))
Check 'step7-cite-page-tolerates-hidpi-ocr-misread' ((Get-CitePageNumber (Find-CiteLine $drawerMisread).Text) -eq '153') "got=$(Get-CitePageNumber (Find-CiteLine $drawerMisread).Text)"
Check 'step7-cite-page-tolerates-chong-ocr-misread' ((Get-CitePageNumber '出处3·第153虫·第4段') -eq '153') "got=$(Get-CitePageNumber '出处3·第153虫·第4段')"
Check 'step7-reports-the-ocr-text-when-page-missing' ((Resolve-Step7PageVerdict '' '150' '出处 · 见上文') -match '见上文') "why=$(Resolve-Step7PageVerdict '' '150' '出处 · 见上文')"

# 41. 两行出处时，点的是解析出的那一页，不是更靠下的另一行。15 不得匹配 154。
$twoCites = @(
    (New-Line '出处1·第154页·第3段' 900 420 280 24),
    (New-Line '出处2·第153页·第12段' 900 460 280 24)
)
$line154 = Find-CiteLineForPage $twoCites '154'
$line153 = Find-CiteLineForPage $twoCites '153'
$click154 = Get-LineClick $line154
Check 'cite-click-uses-parsed-page-line' (($line154.Text -match '第154页') -and ($click154.Y -eq 432)) "y=$($click154.Y) text=$($line154.Text)"
Check 'cite-click-does-not-use-lower-other-page' (($line153.Text -match '第153页') -and ($click154.Y -ne (Get-LineClick $line153).Y)) "y154=$($click154.Y) y153=$((Get-LineClick $line153).Y)"
Check 'cite-page-15-does-not-match-154' (-not (Find-CiteLineForPage $twoCites '15')) "found=$(Find-CiteLineForPage $twoCites '15')"
Check 'cite-page-retry-keeps-original-page' ((Find-CiteLineForPage $twoCites '154').Text -match '第154页') "text=$((Find-CiteLineForPage $twoCites '154').Text)"

# 42. 「讲解」必须和「提问」「批注」同一行。正文里的「讲解」和提问线程里的「讲的是」不能当页签。
$tabRow = @(
    (New-Line '讲解' 1600 78 48 22),
    (New-Line '提问' 1664 80 48 22),
    (New-Line '批注' 1728 79 48 22),
    (New-Line '本节讲解见原文' 400 240 180 24),
    (New-Line '讲的是：第 153-155 页' 1620 180 240 24),
    (New-Line '问这句的意思、公式推导或例子' 1620 420 280 24)
)
$tabHit = Find-CompanionTabLabel $tabRow '讲解'
Check 'explain-tab-is-the-row-with-three-labels' ([bool]$tabHit -and $tabHit.X -eq 1624 -and $tabHit.Y -eq 89) "x=$($tabHit.X) y=$($tabHit.Y)"
Check 'explain-tab-ignores-body-when-row-missing' (-not (Find-CompanionTabLabel @($tabRow[3], $tabRow[4]) '讲解')) 'body line was treated as the tab'
$fused = @((New-Line '讲解提问批注' 1600 80 180 22))
Check 'explain-tab-ignores-one-fused-token' (-not (Find-CompanionTabLabel $fused '讲解')) 'fused token was treated as three tabs'

# 43. 跳页框：只认「前往」左边的数字。154 才是目标，15 是截断，框外的页码不算。
$jumpFull = @(
    (New-Line '154' 700 500 36 20),
    (New-Line '前往' 760 500 40 20),
    (New-Line '第 33 页' 200 500 80 20)
)
$jumpShort = @(
    (New-Line '15' 700 500 24 20),
    (New-Line '前往' 760 500 40 20)
)
$jumpUnread = @((New-Line '前往' 760 500 40 20))
$jumpMerged = @((New-Line '154前往' 700 500 80 20))
Check 'jump-field-reads-digits-left-of-go' ((Get-JumpFieldText $jumpFull) -eq '154') "got=$(Get-JumpFieldText $jumpFull)"
Check 'jump-field-reports-truncated-digits' ((Get-JumpFieldText $jumpShort) -eq '15') "got=$(Get-JumpFieldText $jumpShort)"
Check 'jump-field-null-when-digits-unread' (-not (Get-JumpFieldText $jumpUnread)) "got=$(Get-JumpFieldText $jumpUnread)"
Check 'jump-field-reads-merged-go-token' ((Get-JumpFieldText $jumpMerged) -eq '154') "got=$(Get-JumpFieldText $jumpMerged)"

# 44. 整数页码要先收成字符串再逐位发送。直接在整数上拆字符会在第一位发出去之前失败。
$script:sentKeys = New-Object System.Collections.ArrayList
function Send-Keys([string]$keys) { [void]$script:sentKeys.Add($keys) }
Send-PageDigits ([int]154)
$sent = @($script:sentKeys) -join ','
Check 'page-digits-send-1-then-5-then-4' ($sent -eq '1,5,4') "sent=$sent"

# 45. 选中的「提问」被漏识时，落点仍是抽屉顶的「讲解」。长句里的「提问」、提问框和更靠下的正文「讲解」都不能抢走。
$missedAsk = @(
    (New-Line '讲解' 200 700 48 22),
    (New-Line '批注' 280 700 48 22),
    (New-Line '讲解' 1600 78 48 22),
    (New-Line '学法：逐节精读' 1664 79 140 22),
    (New-Line '批注' 1820 78 48 22),
    (New-Line '收起伴读' 1900 78 80 22),
    (New-Line '需要联网才能提问，已输入的问题已保留' 1600 200 360 24),
    (New-Line '问这句的意思、公式推导或例子' 1600 420 280 24),
    (New-Line '本节讲解' 400 640 80 24)
)
$missedHit = Find-CompanionTabLabel $missedAsk '讲解'
Check 'explain-tab-at-drawer-top-when-ask-label-missed' ([bool]$missedHit -and $missedHit.X -eq 1624 -and $missedHit.Y -eq 89) "x=$($missedHit.X) y=$($missedHit.Y)"
$askOutside = @(
    (New-Line '讲解' 1600 78 48 22),
    (New-Line '批注' 1664 78 48 22),
    (New-Line '提问' 1800 78 48 22)
)
Check 'explain-tab-skips-row-when-ask-is-not-between' (-not (Find-CompanionTabLabel $askOutside '讲解')) 'ask label outside the row was accepted'

# 46. 自动走查写入的临时身份必须带上角色。缺角色时客户端会按 owner 加载。
$pairSrc = Get-Content -LiteralPath (Join-Path $repo 'tools/smoke/friend-service-winbox.ps1') -Raw -Encoding UTF8
$fnStart = $pairSrc.IndexOf('function Format-PairedCredentials')
$fnEnd = $pairSrc.IndexOf('# end Format-PairedCredentials', $fnStart)
if ($fnStart -lt 0 -or $fnEnd -lt 0) { throw 'friend-service-winbox.ps1: Format-PairedCredentials not found' }
. ([scriptblock]::Create($pairSrc.Substring($fnStart, $fnEnd - $fnStart)))
$friendCred = Format-PairedCredentials 'http://127.0.0.1:9' 'device' ('a' * 43) 'friend'
$friendLines = @($friendCred -split "`n" | Where-Object { $_ -ne '' })
Check 'paired-credentials-write-friend-role' ($friendLines -contains 'role=friend') 'role line missing'
Check 'paired-credentials-keep-server-device-token' (($friendLines.Count -eq 4) -and ($friendLines[0] -like 'server=*') -and ($friendLines[1] -like 'deviceId=*') -and ($friendLines[2] -like 'token=*')) 'credential lines changed'
$ownerCred = Format-PairedCredentials 'http://127.0.0.1:9' 'device' ('b' * 43) 'owner'
Check 'paired-credentials-write-owner-role' ((@($ownerCred -split "`n") -contains 'role=owner')) 'owner role missing'
$rejected = $false
try { Format-PairedCredentials 'http://127.0.0.1:9' 'device' ('c' * 43) '' } catch { $rejected = $true }
Check 'paired-credentials-reject-blank-role' $rejected 'blank role was written'

# 47. 顶栏右半区同时有「伴读：逐节精读，」与独立「伴读」时，只点右侧开关；菜单说明含「批注」不得判抽屉就绪，真出现页签行才算就绪。
$topBothEntries = @(
    (New-Line '书架' 40 46 56 20),
    (New-Line '目录' 110 46 44 20),
    (New-Line '伴读' 120 320 44 20),
    (New-Line '伴读' 200 46 44 20),
    (New-Line '伴读：逐节精读，' 500 46 128 22),
    (New-Line '伴读' 646 46 44 22)
)
$toggleHit = Find-ReaderCompanionToggle $topBothEntries 752 552
Check 'step5-top-toggle-picks-standalone-right-button' ([bool]$toggleHit -and $toggleHit.X -eq 668 -and $toggleHit.Y -eq 57) "x=$($toggleHit.X) y=$($toggleHit.Y)"

$topModeOnly = @(
    (New-Line '书架' 40 46 56 20),
    (New-Line '目录' 110 46 44 20),
    (New-Line '伴读: 逐节精读' 500 46 128 22)
)
Check 'step5-top-toggle-ignores-study-mode-dropdown' ($null -eq (Find-ReaderCompanionToggle $topModeOnly 752 552)) 'study mode dropdown matched as toggle'

$studyMenuOnly = @(
    (New-Line '书架' 40 46 56 20),
    (New-Line '目录' 110 46 44 20),
    (New-Line '详略' 480 280 48 22),
    (New-Line '语言' 480 315 48 22),
    (New-Line '含批注的说明' 500 350 110 22),
    (New-Line '切换学法时，当前页码、高亮与批注全部保留。' 440 410 290 22)
)
Check 'step5-menu-note-copy-never-ready' ($null -eq (Find-CompanionTabLabel $studyMenuOnly '讲解' 18 752 552)) 'menu copy with 批注 was treated as drawer ready'

$wrongRegionTabs = @(
    (New-Line '讲解' 90 46 48 22),
    (New-Line '提问' 154 46 48 22),
    (New-Line '批注' 218 46 48 22)
)
Check 'step5-drawer-tabs-require-expected-region' ($null -eq (Find-CompanionTabLabel $wrongRegionTabs '讲解' 18 752 552)) 'top-left false match was accepted'

$bottomSheetReady = @(
    (New-Line '书架' 40 46 56 20),
    (New-Line '讲解' 24 280 48 22),
    (New-Line '提问' 88 280 48 22),
    (New-Line '批注' 152 280 48 22)
)
$bottomTabHit = Find-CompanionTabLabel $bottomSheetReady '讲解' 18 752 552
Check 'step5-bottom-drawer-tabs-ready-in-expected-region' ([bool]$bottomTabHit -and $bottomTabHit.X -eq 48 -and $bottomTabHit.Y -eq 291) "x=$($bottomTabHit.X) y=$($bottomTabHit.Y)"

$wideRightReady = @(
    (New-Line '书架' 60 46 56 20),
    (New-Line '讲解' 1600 78 48 22),
    (New-Line '提问' 1664 78 48 22),
    (New-Line '批注' 1728 78 48 22)
)
$wideTabHit = Find-CompanionTabLabel $wideRightReady '讲解' 18 2160 1350
Check 'step5-wide-drawer-tabs-ready-in-top-right' ([bool]$wideTabHit -and $wideTabHit.X -eq 1624 -and $wideTabHit.Y -eq 89) "x=$($wideTabHit.X) y=$($wideTabHit.Y)"
Check 'step5-wide-drawer-rejects-top-left-tabs' ($null -eq (Find-CompanionTabLabel $wrongRegionTabs '讲解' 18 2160 1350)) 'top-left tabs accepted on wide window'

function Show-ReaderTop([int]$n, [string]$tag, [int]$Tries = 6) {
    $script:Last = $script:screen
    return , $script:screen
}
function Hover([int]$x, [int]$y) { }
function Start-Sleep([int]$Seconds = 0, [int]$Milliseconds = 0) { }

$script:screen = $topBothEntries; $script:Last = $topBothEntries; $script:queue = $null; $script:clicks.Clear()
$script:onClick = {
    if ($script:clicks[0] -eq '668,57') { $script:screen = $bottomSheetReady }
    else { $script:screen = $studyMenuOnly }
}
$openedOk = Ensure-CompanionDrawerOpen 5 752 552 -tries 3 -missTag 'explain-tab0' -openTagPrefix 'explain-tab'
Check 'step5-open-drawer-clicks-right-toggle-and-detects-tab-row' ($script:clicks.Count -eq 1 -and $script:clicks[0] -eq '668,57' -and [bool]$openedOk.Tab -and $openedOk.Tab.X -eq 48 -and $openedOk.Tab.Y -eq 291) "clicks=$($script:clicks -join '|')"

$script:screen = $topBothEntries; $script:Last = $topBothEntries; $script:queue = $null; $script:clicks.Clear()
$script:onClick = { $script:screen = $studyMenuOnly }
$openedMenu = Ensure-CompanionDrawerOpen 5 752 552 -tries 2 -missTag 'explain-tab0' -openTagPrefix 'explain-tab'
Check 'step5-open-drawer-stays-unready-when-only-menu-shows' ($script:clicks.Count -eq 1 -and $null -eq $openedMenu.Tab) "clicks=$($script:clicks -join '|') tab=$($openedMenu.Tab)"

# 48. 抽屉页签行被 OCR 无空格合并成「提问批注」时，同排「讲解」+「提问批注」在预期区域仍可就绪（步骤 6 不误判未开），反序「批注提问」与含「批注」菜单说明仍不通过。
$wideMergedAskNotes = @(
    (New-Line '书架' 60 46 56 20),
    (New-Line '目录' 130 46 44 20),
    (New-Line '讲解' 1600 78 48 22),
    (New-Line '伴读：逐节精读，' 1750 46 128 22),
    (New-Line '提问批注' 1664 78 96 22),
    (New-Line '收起伴读' 1900 46 80 22)
)
$wideMergedHit = Find-CompanionTabLabel $wideMergedAskNotes '讲解' 18 2160 1350
Check 'step6-wide-drawer-accepts-explain-plus-merged-ask-notes' ([bool]$wideMergedHit -and $wideMergedHit.X -eq 1624 -and $wideMergedHit.Y -eq 89) "x=$($wideMergedHit.X) y=$($wideMergedHit.Y)"

$bottomMergedAskNotes = @(
    (New-Line '书架' 40 46 56 20),
    (New-Line '讲解' 24 280 48 22),
    (New-Line '提问批注' 88 280 96 22)
)
$bottomMergedHit = Find-CompanionTabLabel $bottomMergedAskNotes '讲解' 18 752 552
Check 'step6-bottom-drawer-accepts-explain-plus-merged-ask-notes' ([bool]$bottomMergedHit -and $bottomMergedHit.X -eq 48 -and $bottomMergedHit.Y -eq 291) "x=$($bottomMergedHit.X) y=$($bottomMergedHit.Y)"

$wrongOrderMerged = @(
    (New-Line '讲解' 1600 78 48 22),
    (New-Line '批注提问' 1664 78 96 22)
)
Check 'step6-drawer-rejects-swapped-notes-ask-token' ($null -eq (Find-CompanionTabLabel $wrongOrderMerged '讲解' 18 2160 1350)) 'swapped 批注提问 was accepted'

$script:screen = $wideMergedAskNotes; $script:Last = $wideMergedAskNotes; $script:queue = $null; $script:clicks.Clear(); $script:onClick = $null
$openedWideMerged = Ensure-CompanionDrawerOpen 6 2160 1350 -tries 1 -missTag 'top0' -openTagPrefix 'drawer'
Check 'step6-open-drawer-ready-without-click-on-merged-ask-notes' ($script:clicks.Count -eq 0 -and [bool]$openedWideMerged.Tab -and $openedWideMerged.Tab.X -eq 1624 -and $openedWideMerged.Tab.Y -eq 89) "clicks=$($script:clicks.Count)"

# 49. 无空格匹配仅放行纯「提问批注」，拒绝「提问和批注」「提问批注说明」；步骤 6 滚动后模块条上移时按当前帧模块条与提交按钮定位选项。
foreach ($badToken in @('提问和批注', '提问批注说明', '提问 和 批注')) {
    $badRow = @(
        (New-Line '讲解' 1630 136 80 20),
        (New-Line $badToken 1715 136 80 20)
    )
    Check "step6-drawer-rejects-nonpure-$($badToken -replace '\s', '')" ($null -eq (Find-CompanionTabLabel $badRow '讲解' 18 2160 1350)) "accepted $badToken"
}

$staleStrip = [pscustomobject]@{ X = 1750; Y = 1160 }
$pretestScrolledFrame = @(
    (New-Line '要点' 1650 556 48 20),
    (New-Line '自测' 1710 556 48 20),
    (New-Line '推导' 1770 556 48 20),
    (New-Line '第 1 / 2 题' 1620 610 120 22),
    (New-Line 'A. 第一项' 1620 820 140 24),
    (New-Line 'B. 最后一项' 1620 886 150 24),
    (New-Line '这题是猜的' 1620 960 120 24),
    (New-Line '提交' 2020 960 64 24)
)
$state6Scrolled = Find-Step6PretestState $pretestScrolledFrame 2160 1350 $staleStrip
Check 'step6-pretest-uses-current-strip-after-scroll' ([bool]$state6Scrolled.Strip -and $state6Scrolled.Strip.Y -eq 566 -and [bool]$state6Scrolled.Submit -and $state6Scrolled.Submit.Y -eq 972 -and [bool]$state6Scrolled.Option -and $state6Scrolled.Option.Text -eq 'B. 最后一项') "stripY=$($state6Scrolled.Strip.Y) subY=$($state6Scrolled.Submit.Y) opt=$($state6Scrolled.Option.Text)"

$pretestNoStripFrame = @($pretestScrolledFrame | Where-Object { $_.Text -notin @('要点', '自测', '推导') })
$state6NoStrip = Find-Step6PretestState $pretestNoStripFrame 2160 1350 $staleStrip
Check 'step6-pretest-ignores-stale-strip-below-submit' ([bool]$state6NoStrip.Submit -and [bool]$state6NoStrip.Option -and $state6NoStrip.Option.Text -eq 'B. 最后一项') "opt=$($state6NoStrip.Option.Text)"

# 50. 步骤 6 题卡就绪：题号行认不出（11-27 07:51 那帧）但同帧「这题是猜的」+ 模块条 + 选项 + 提交齐全也算；
#     只有下面「用自己的话说」那张卡的「提交」、没有「这题是猜的」时不算（不能把它当题卡去点）。
$cardNoNumber = @(
    (New-Line '要点' 1650 654 48 20),
    (New-Line '自测' 1710 654 48 20),
    (New-Line '推导' 1770 654 48 20),
    (New-Line '占位题干第一行' 1620 720 300 24),
    (New-Line 'A. 甲' 1620 820 140 24),
    (New-Line 'B. 乙' 1620 886 140 24),
    (New-Line 'C. 丙' 1620 952 140 24),
    (New-Line 'D. 丁' 1620 1007 140 24),
    (New-Line '这题是猜的' 1620 1082 120 24),
    (New-Line '提交' 2020 1082 64 24),
    (New-Line '这一章，用自己的话说' 1620 1160 260 24)
)
$card6 = Find-Step6QuestionCard $cardNoNumber 2160 1350 $null
Check 'step6-card-ready-without-question-number' ([bool]$card6 -and $card6.Text -eq 'D. 丁') "card=$($card6.Text)"
$cardWithNumber = @((New-Line '第 1 / 3 题' 1620 690 120 22)) + $cardNoNumber
$card6n = Find-Step6QuestionCard $cardWithNumber 2160 1350 $null
Check 'step6-card-ready-by-question-number' ([bool]$card6n -and $card6n.Text -eq '第 1 / 3 题') "card=$($card6n.Text)"
$ownWordsOnly = @(
    (New-Line '要点' 1650 654 48 20),
    (New-Line '自测' 1710 654 48 20),
    (New-Line '读前·先做几道' 1620 720 200 24),
    (New-Line '这一章，用自己的话说' 1620 1160 260 24),
    (New-Line '占位输入提示' 1620 1220 260 24),
    (New-Line '提交' 2020 1258 64 24)
)
Check 'step6-card-not-ready-on-own-words-submit' ($null -eq (Find-Step6QuestionCard $ownWordsOnly 2160 1350 $null)) 'own-words submit accepted as question card'
$guessNoOption = @(
    (New-Line '要点' 1650 654 48 20),
    (New-Line '自测' 1710 654 48 20),
    (New-Line '这题是猜的' 1620 700 120 24),
    (New-Line '提交' 2020 700 64 24)
)
Check 'step6-card-not-ready-without-option' ($null -eq (Find-Step6QuestionCard $guessNoOption 2160 1350 $null)) 'accepted with no option above submit'

# 51. 跳页框放大重认：只要整行是数字的，取最右边那串，坐标按放大倍数换回整帧；光标竖线粘在后面时 Alt 去掉它。
$cropLines = @(
    (New-Line '8' 20 40 30 60),
    (New-Line '154' 120 40 120 64),
    (New-Line '前往' 300 40 80 60)
)
$pick = Select-JumpFieldDigits $cropLines 1900 1280 4
Check 'jump-crop-picks-rightmost-digits' ([bool]$pick -and $pick.Text -eq '154' -and $pick.X -eq 1945 -and $pick.Y -eq 1298) "text=$($pick.Text) x=$($pick.X) y=$($pick.Y)"
$caret = Select-JumpFieldDigits @((New-Line '154|' 120 40 140 60)) 1900 1280 4
Check 'jump-crop-alt-drops-caret' ([bool]$caret -and $caret.Text -eq '1541' -and $caret.Alt -eq '154') "text=$($caret.Text) alt=$($caret.Alt)"
Check 'jump-crop-null-without-digits' ($null -eq (Select-JumpFieldDigits @((New-Line '前往' 300 40 80 60)) 1900 1280 4)) 'non-digit line accepted'
Check 'jump-crop-null-when-png-missing' ($null -eq (Read-JumpFieldCrop (Join-Path $PSScriptRoot 'no-such-frame.png') ([pscustomobject]@{ X1 = 2063; X2 = 2098; Y1 = 1300; Y2 = 1320; Y = 1310 }))) 'missing png read'

# 52. 点回跳页框的落点：默认在「前往」左边 56dp；放大重认拿到的框内数字在同一行、左边百来 dp 内才用它。
$goBox = [pscustomobject]@{ X1 = 2063; X2 = 2098; Y1 = 1300; Y2 = 1320; Y = 1310 }
$ptDefault = Get-JumpFieldPoint $goBox $null 1.5
Check 'jump-point-default-left-of-go' ($ptDefault.X -eq 1979 -and $ptDefault.Y -eq 1310) "x=$($ptDefault.X) y=$($ptDefault.Y)"
$ptField = Get-JumpFieldPoint $goBox ([pscustomobject]@{ X = 1974; Y = 1312 }) 1.5
Check 'jump-point-uses-field-on-row' ($ptField.X -eq 1974 -and $ptField.Y -eq 1310) "x=$($ptField.X)"
$ptFar = Get-JumpFieldPoint $goBox ([pscustomobject]@{ X = 1500; Y = 1310 }) 1.5
$ptOffRow = Get-JumpFieldPoint $goBox ([pscustomobject]@{ X = 1974; Y = 1200 }) 1.5
$ptRight = Get-JumpFieldPoint $goBox ([pscustomobject]@{ X = 2120; Y = 1310 }) 1.5
Check 'jump-point-ignores-far-offrow-or-right-field' ($ptFar.X -eq 1979 -and $ptOffRow.X -eq 1979 -and $ptRight.X -eq 1979) "far=$($ptFar.X) off=$($ptOffRow.X) right=$($ptRight.X)"

# 53. 跳页整段（TON-1861 11-27 09:32 那轮）：框里数字一直认不出时绝不按回车，第 2–4 遍先点回框里；
#     第一遍读到原页 33、点回框里后读到 154 才回车，回车后仍核对底栏页码；框收起来了先重按 Ctrl+G。
function Await-Text([int]$n, [string]$tag, [string[]]$needles, [int]$sec) {
    $l = Shot $n $tag
    foreach ($nd in $needles) { if (Find-Text $l $nd) { return , $l } }
    return $null
}
$script:cropQueue = @()
function Read-JumpFieldCrop([string]$png, $go) {
    if ($script:cropQueue.Count -eq 0) { return $null }
    $hit = $script:cropQueue[0]
    if ($script:cropQueue.Count -gt 1) { $script:cropQueue = @($script:cropQueue[1..($script:cropQueue.Count - 1)]) } else { $script:cropQueue = @() }
    return $hit
}
$script:hud = $null
function Get-HudCounter([string]$png) { return $script:hud }
$jumpOpen = @((New-Line '前往' 2063 1300 35 20), (New-Line '第 33 页' 200 1300 80 20))
$jumpGone = @((New-Line '第 33 页' 200 1300 80 20))
function Invoke-JumpCase {
    try { Jump-VerifiedReaderPage 4 154 1080 1200 'initial'; return 'ok' } catch { return "$_" }
}

$script:screen = $jumpOpen; $script:Last = $jumpOpen; $script:queue = $null; $script:clicks.Clear(); $script:onClick = $null
$script:sentKeys.Clear(); $script:cropQueue = @(); $script:hud = [pscustomobject]@{ Cur = 33; Total = 620 }
$unread = Invoke-JumpCase
$enterCount = @($script:sentKeys | Where-Object { $_ -eq '{ENTER}' }).Count
Check 'jump-unread-never-presses-enter' ($enterCount -eq 0 -and $unread -like 'STEP_FAILED:*没按回车*') "enter=$enterCount result=$unread"
Check 'jump-unread-clicks-back-into-field' ($script:clicks.Count -eq 3 -and @($script:clicks | Where-Object { $_ -ne '2007,1310' }).Count -eq 0) "clicks=$($script:clicks -join '|')"
$typedRounds = @($script:sentKeys | Where-Object { $_ -eq '^a' }).Count
Check 'jump-unread-retypes-four-times' ($typedRounds -eq 4) "rounds=$typedRounds"

$script:screen = $jumpOpen; $script:Last = $jumpOpen; $script:queue = $null; $script:clicks.Clear()
$script:sentKeys.Clear(); $script:hud = [pscustomobject]@{ Cur = 154; Total = 620 }
$script:cropQueue = @(
    [pscustomobject]@{ Text = '33'; Alt = '33'; X = 1974; Y = 1310; Right = 1990 },
    [pscustomobject]@{ Text = '154'; Alt = '154'; X = 1974; Y = 1310; Right = 1995 }
)
$retyped = Invoke-JumpCase
$enterAt = [array]::IndexOf(@($script:sentKeys), '{ENTER}')
$lastDigit = [array]::LastIndexOf(@($script:sentKeys), '4')
Check 'jump-stale-page-reclicks-then-enters' ($retyped -eq 'ok' -and $script:clicks.Count -eq 1 -and $script:clicks[0] -eq '1974,1310' -and $enterAt -gt $lastDigit) "result=$retyped clicks=$($script:clicks -join '|') keys=$($script:sentKeys -join ',')"

$script:screen = $jumpOpen; $script:Last = $jumpOpen; $script:queue = $null; $script:clicks.Clear()
$script:sentKeys.Clear(); $script:hud = [pscustomobject]@{ Cur = 33; Total = 620 }
$script:cropQueue = @([pscustomobject]@{ Text = '154'; Alt = '154'; X = 1974; Y = 1310; Right = 1995 })
$noJump = Invoke-JumpCase
Check 'jump-confirmed-still-checks-page-after-enter' ($noJump -like 'STEP_FAILED:*页码没跳到 154*') "result=$noJump"

$script:screen = $jumpOpen; $script:Last = $jumpOpen; $script:clicks.Clear()
$script:queue = @($jumpOpen, $jumpGone, $jumpOpen, $jumpOpen)
$script:sentKeys.Clear(); $script:hud = [pscustomobject]@{ Cur = 154; Total = 620 }
$script:cropQueue = @([pscustomobject]@{ Text = '154'; Alt = '154'; X = 1974; Y = 1310; Right = 1995 })
$reopened = Invoke-JumpCase
$ctrlG = @($script:sentKeys | Where-Object { $_ -eq '^g' }).Count
Check 'jump-collapsed-box-reopens-with-ctrl-g' ($reopened -eq 'ok' -and $ctrlG -eq 2 -and $script:clicks.Count -eq 1 -and $script:clicks[0] -eq '2007,1310') "result=$reopened ctrlG=$ctrlG clicks=$($script:clicks -join '|')"

$script:screen = $jumpOpen; $script:Last = $jumpOpen; $script:queue = $null; $script:clicks.Clear()
$script:sentKeys.Clear(); $script:hud = [pscustomobject]@{ Cur = 154; Total = 620 }
$script:cropQueue = @([pscustomobject]@{ Text = '1541'; Alt = '154'; X = 1974; Y = 1310; Right = 1999 })
$caretOk = Invoke-JumpCase
Check 'jump-caret-glued-digits-accepted-first-try' ($caretOk -eq 'ok' -and $script:clicks.Count -eq 0) "result=$caretOk clicks=$($script:clicks.Count)"

# 第 5 步：先从自测切回要点，不能把阅读器标题里的章节编号认成要点。
$step5SelfTest = @(
    (New-Line '6．7 Example' 700 70 160 22),
    (New-Line '要点' 1600 550 48 22),
    (New-Line '自测' 1660 550 48 22),
    (New-Line '读前自测' 1600 610 120 22)
)
$step5Page = @(
    (New-Line '要点' 1600 550 48 22),
    (New-Line '自测' 1660 550 48 22),
    (New-Line '讲的是：第 150 页' 1600 610 200 22)
)
$step5Chapter = @(
    (New-Line '6．7 Example' 700 70 160 22),
    (New-Line '本章要点·示例' 1600 610 200 22),
    (New-Line '1．合成要点' 1600 660 200 22)
)
Check 'step5-title-number-is-not-a-chapter-point' ($null -eq (Find-Step5ChapterPoint $step5SelfTest 2160 1350)) 'reader title accepted'
Check 'step5-pretest-is-not-keypoint-view' (-not (Test-Step5KeyPointView $step5SelfTest 2160 1350)) 'pretest accepted'
Check 'step5-page-view-recognized' (Test-Step5KeyPointView $step5Page 2160 1350) 'page heading missed'
Check 'step5-chapter-point-requires-drawer-heading' ((Find-Step5ChapterPoint $step5Chapter 2160 1350).Text -eq '1．合成要点') 'chapter point missed'
$outsideKey = @((New-Line '要点' 200 550 48 22), (New-Line '自测' 260 550 48 22))
Check 'step5-keypoint-tab-requires-drawer' ($null -eq (Find-Step5KeyPointTab $outsideKey 2160 1350)) 'outside tab accepted'
$loopStart = $src.IndexOf('        $keyView = $false')
$loopEnd = $src.IndexOf('        # 讲解页的内容', $loopStart)
if ($loopStart -lt 0 -or $loopEnd -lt 0) { throw 'step 5 key point switch not found' }
$switchKeyPoints = [scriptblock]::Create($src.Substring($loopStart, $loopEnd - $loopStart))
$winW = 2160; $winH = 1350; $midX = 1080
$script:screen = $step5SelfTest; $script:queue = $null; $script:clicks.Clear()
$script:onClick = { $script:screen = $step5Page }
& $switchKeyPoints
Check 'step5-clicks-keypoint-before-recognizing-page' ($script:clicks.Count -eq 1 -and $script:clicks[0] -eq '1624,561') "clicks=$($script:clicks -join '|')"
$script:screen = $step5SelfTest; $script:clicks.Clear(); $script:onClick = $null
$switchError = ''
try { & $switchKeyPoints } catch { $switchError = $_.Exception.Message }
Check 'step5-stops-if-keypoint-never-opens' ($script:clicks.Count -eq 3 -and $switchError -like '*未能从抽屉模块条切到*') "clicks=$($script:clicks.Count)"

# 54. 第 5 步模块条 OCR 粘连（25422758 / 5-key-tab-find1..3）：同排「要点」+ 单块「自测推导」+「练习习题」仍能定位并切到要点；
#     正文与提示语仍被纯词判据与区域过滤排除；若严格判据 Test-Step5KeyPointView 已确认在「要点」则无需重复点击，直接继续核查出处。
$gluedSelfTest = @(
    (New-Line '6．7 Example' 700 70 160 22),
    (New-Line '要点' 1600 550 48 22),
    (New-Line '自测推导' 1660 550 96 22),
    (New-Line '练习习题' 1770 550 96 22),
    (New-Line '读前自测' 1600 610 120 22)
)
$gluedHit = Find-Step5KeyPointTab $gluedSelfTest 2160 1350
Check 'step5-keypoint-tab-accepts-glued-selftest-and-practice' ([bool]$gluedHit -and $gluedHit.X -eq 1624 -and $gluedHit.Y -eq 561) "hit=$($gluedHit.X),$($gluedHit.Y)"

$gluedNarrow = @(
    (New-Line '要点' 40 280 48 22),
    (New-Line '自测推导' 100 280 96 22),
    (New-Line '练习习题' 208 280 96 22)
)
$gluedNarrowHit = Find-Step5KeyPointTab $gluedNarrow 752 552
Check 'step5-keypoint-tab-accepts-glued-strip-in-narrow-drawer' ([bool]$gluedNarrowHit -and $gluedNarrowHit.X -eq 64 -and $gluedNarrowHit.Y -eq 291) "hit=$($gluedNarrowHit.X),$($gluedNarrowHit.Y)"

$gluedSingleLine = @((New-Line '要点自测推导' 1600 550 180 22))
$gluedSingleHit = Find-Step5KeyPointTab $gluedSingleLine 2160 1350
Check 'step5-keypoint-tab-accepts-single-glued-strip-line' ([bool]$gluedSingleHit -and $gluedSingleHit.X -ge 1600 -and $gluedSingleHit.X -lt 1660) "x=$($gluedSingleHit.X)"
Check 'step5-keypoint-tab-rejects-reversed-glued-strip' ($null -eq (Find-Step5KeyPointTab @((New-Line '自测要点' 1600 550 96 22)) 2160 1350)) 'reversed strip accepted'

$gluedPromptNoise = @((New-Line '要点' 1600 550 48 22), (New-Line '自测推导说明' 1660 550 130 22))
Check 'step5-keypoint-tab-rejects-prompt-with-module-words' ($null -eq (Find-Step5KeyPointTab $gluedPromptNoise 2160 1350)) 'prompt text accepted as strip'
$outsideGlued = @((New-Line '要点' 200 550 48 22), (New-Line '自测推导' 260 550 96 22))
Check 'step5-keypoint-tab-rejects-glued-strip-outside-drawer' ($null -eq (Find-Step5KeyPointTab $outsideGlued 2160 1350)) 'outside glued strip accepted'

$script:screen = $gluedSelfTest; $script:queue = $null; $script:clicks.Clear()
$script:onClick = { $script:screen = $step5Page }
& $switchKeyPoints
Check 'step5-glued-strip-clicks-keypoint-and-reaches-view' ($keyView -and $script:clicks.Count -eq 1 -and $script:clicks[0] -eq '1624,561') "keyView=$keyView clicks=$($script:clicks -join '|')"

$bodyMisreadSelfTest = @(
    (New-Line '6．7 Example' 700 70 160 22),
    (New-Line '讲的是：第 150 页' 200 550 200 22),
    (New-Line '要点' 1600 550 48 22),
    (New-Line '自测推导' 1660 550 96 22),
    (New-Line '读前自测' 1600 610 120 22)
)
Check 'step5-body-misread-outside-drawer-not-keypoint-view' (-not (Test-Step5KeyPointView $bodyMisreadSelfTest 2160 1350)) 'left body misread as keypoint view'
$script:screen = $bodyMisreadSelfTest; $script:queue = $null; $script:clicks.Clear()
$script:onClick = { $script:screen = $step5Page }
& $switchKeyPoints
Check 'step5-body-misread-still-clicks-keypoint-tab' ($keyView -and $script:clicks.Count -eq 1 -and $script:clicks[0] -eq '1624,561') "keyView=$keyView clicks=$($script:clicks -join '|')"

$alreadyKeyPointGlued = @(
    (New-Line '要点' 1600 550 48 22),
    (New-Line '自测推导' 1660 550 96 22),
    (New-Line '练习习题' 1770 550 96 22),
    (New-Line '讲的是：第 154 页' 1600 610 200 22)
)
$script:screen = $alreadyKeyPointGlued; $script:queue = $null; $script:clicks.Clear(); $script:onClick = $null
& $switchKeyPoints
Check 'step5-already-on-keypoint-view-proceeds-without-click' ($keyView -and $script:clicks.Count -eq 0) "keyView=$keyView clicks=$($script:clicks.Count)"

$script:screen = $step5Chapter; $script:queue = $null; $script:clicks.Clear(); $script:onClick = $null
& $switchKeyPoints
Check 'step5-already-on-chapter-keypoint-proceeds-without-strip' ($keyView -and $script:clicks.Count -eq 0) "keyView=$keyView clicks=$($script:clicks.Count)"

$fail = @($results | Where-Object { -not $_ }).Count
"RESULT checks=$($results.Count) fail=$fail"
exit ([int]($fail -gt 0))
