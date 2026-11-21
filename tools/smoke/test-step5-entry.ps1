# 离线回归：第 5 步入口那三下的重试闭环和弹窗内定位（退出码：0 = 全部通过）。
#  - 状态没变才算没点成：Done 一成立就停手，不重复点；
#  - 位置找不到就不再拿旧坐标点第二下（否则会点在弹窗关掉之后的页面上）；
#  - 「边看边学」只认弹窗里和「看书」「做题学」并排的那个 chip，不认书架右栏那一行。
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

# 把 Find-OnRow / Click-Label / Click-Until / Find-DialogPath 原样取出来跑。
$src = Get-Content -LiteralPath (Join-Path $repo 'tools/smoke/friend-path-worker.ps1') -Raw -Encoding UTF8
$start = $src.IndexOf('function Find-OnRow')
$end = $src.IndexOf('$env:JAVA_HOME', $start)
if ($start -lt 0 -or $end -lt 0) { throw 'friend-path-worker.ps1: step-5 entry helpers not found' }
. ([scriptblock]::Create($src.Substring($start, $end - $start)))

# 一行 OCR：一个词覆盖整行，坐标就是这一行的位置。
function New-Line([string]$text, [int]$x, [int]$y, [int]$w, [int]$h) {
    $rect = [pscustomobject]@{ X = $x; Y = $y; Width = $w; Height = $h }
    [pscustomobject]@{ Text = $text; Words = @([pscustomobject]@{ Text = $text; BoundingRect = $rect }) }
}

# 假屏幕：Shot 返回当前这一帧，Click-At 按本用例的脚本换屏——和真机一样，屏幕只因点击而变。
$script:screen = @()
$script:clicks = New-Object System.Collections.ArrayList
$script:onClick = $null
function Shot([int]$n, [string]$tag) { return , $script:screen }
function Click-At($x, $y) { [void]$script:clicks.Add("$x,$y"); if ($script:onClick) { & $script:onClick } }
function Fail([int]$n, [string]$why) { throw "STEP_FAILED: $why" }
function Note([string]$m) { }

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

$fail = @($results | Where-Object { -not $_ }).Count
"RESULT checks=$($results.Count) fail=$fail"
exit ([int]($fail -gt 0))
