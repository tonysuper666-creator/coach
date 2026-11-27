# Replay steps 1-2 against synthetic OCR frames. A click changes the screen only at the expected position.
param([string]$LegacyWorker, [string]$AfterPickerOcr)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Win-Drive.ps1')
$source = Get-Content (Join-Path $PSScriptRoot 'friend-path-worker.ps1') -Encoding UTF8 -Raw
$errors = $null
$ast = [Management.Automation.Language.Parser]::ParseInput($source, [ref]$null, [ref]$errors)
if ($errors) { throw $errors[0] }
$helpers = 'Get-LineBox','Find-Exact','Has','Await-Text','Get-HomeImportLabel','Find-TasteOption',
    'Find-TasteVerdict','Test-TasteExplanation','Complete-FirstTaste','Click-Until-Dialog','Resolve-Step2HomeVerdict',
    'Find-Step2HomeBook','Normalize-Step2Title','Test-Step2Home','Find-OnRow','Get-LineClick','Show-ReaderTop'
foreach ($name in $helpers) {
    $fn = $ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name}, $true)
    . ([scriptblock]::Create($fn.Extent.Text))
}
$start = $source.IndexOf('$deadline = (Get-Date).AddSeconds(30)', $source.IndexOf('Set-SmokeMemoryClient $memoryLease'))
$end = $source.IndexOf('    # ③ 翻页', $start)
$steps = [scriptblock]::Create($source.Substring($start, $end - $start))

function Row([string]$text, [int]$y, [int]$x = 160) {
    $word = [pscustomobject]@{ Text = $text; BoundingRect = @{ X = $x; Y = $y; Width = 120; Height = 20 } }
    [pscustomobject]@{ Text = $text; Words = @($word) }
}
function Get-Date { $script:clock = $script:clock.AddSeconds(1); $script:clock }
function Start-Sleep { param($Seconds, $Milliseconds) }
function Scroll-Point { @(400, 350) }
function Get-Rect { @{ L = 0; T = 0; R = 800; B = 600 } }
function Hover {}
function Note {}
function Fail($step, $why) { throw "Step $step - $why" }
function Pass($step, $why) { [void]$script:passes.Add($step) }
function Find-AppWindowByTitle($prefix) { if ($script:view -eq 'picker' -and $prefix -eq $dialogTitle) { return [IntPtr]1 }; [IntPtr]::Zero }
function Open-Step2ImportMenu($lines,$attempt) {
    if ($route -notin @('installedShelf', 'syncShelf') -or -not (Test-Step2Home $lines)) { throw 'Unexpected import menu' }
    [void]$script:clicks.Add('menu')
    $script:view = 'picker'
}
function Choose-File($title, $book) {
    if ($script:view -ne 'picker' -or $title -ne $dialogTitle) { return $false }
    $script:view = if ($route -eq '1.1.1') { 'added' } elseif ($route -ne 'first' -and $fault -ne 'syncFirstTaste') { 'reader' } else {
        switch ($fault) {
            'unavailable' { 'hold' }
            'retryImport' { 'importFailed' }
            'readOnly' { 'readOnly' }
            'offlineThenQuestion' { 'offlineHome' }
            'stuckOnHome' { 'offlineHome' }
            default { 'question' }
        }
    }
    return $true
}
function Get-HudCounter { if ($script:view -eq 'reader') { @{ Text = '1/8' } } }
function Scroll-At($x,$y,[int]$n) {
    if ($script:view -eq 'question' -and $n -lt 0) { $script:view = 'options' }
    if ($script:view -eq 'explanation' -and $n -lt 0) { $script:view = 'next' }
}
function Click-At($x,$y,[scriptblock]$BeforePress) {
    if ($BeforePress -and -not (& $BeforePress)) { return $false }
    [void]$script:clicks.Add("$script:view@$y")
    switch ("$script:view@$y") {
        'home@210' { $script:view = 'picker' }
        'options@250' { $script:view = 'selected' }
        'selected@300' { $script:view = if ($fault -eq 'empty') { 'empty' } else { 'explanation' } }
        'next@300' { $script:view = if ($fault -eq 'noReader') { 'shelf' } else { 'reader' } }
        'reader@70' { $script:view = 'shelf' }
        default { throw "Unexpected click at $script:view@$y" }
    }
    return $true
}
function Shot($step,$tag) {
    if ($route -eq 'syncShelf' -and $step -eq 2 -and
        (($fault -eq 'duringConfirm' -and $tag -like 'import-confirm*') -or
         ($fault -ne 'duringConfirm' -and $tag -like 'import-home*'))) { $script:synced = $true }
    $script:Last = @(switch ($script:view) {
        home {
            if ($route -eq 'first' -or ($route -eq 'syncShelf' -and -not $script:synced)) { Row '放进一本书，马上做第一道题。' 60; Row '放 进 一 本 书' 200 }
            elseif ($route -in @('installedShelf', 'syncShelf')) { Row 'LearningCoach' 50; Row '设置' 50 700; Row 'PDF·云端书架·点击下载阅读' 120 }
            elseif ($route -eq 'existing') { Row '上次停在第 2 页' 60; Row '导入文件' 200 }
            elseif ($route -eq '1.1.1') { Row '书架' 60; Row '上传教材' 200 }
            else { Row '为一门课备考' 60; Row '导入文件' 200 }
        }
        added { Row '教材已加入书架' 120; Row '先读' 200 }
        offlineHome {
            foreach ($text in $offlineHomeText) { Row $text 100 }
            if ($fault -eq 'offlineThenQuestion') { $script:view = 'loading' }
        }
        loading { Row '正在用开头几章出第一道题，大约 1 分钟。' 100; $script:view = 'question' }
        question { Row '选一个答案。做完就能看到讲解。' 60; Row '题干' 160 }
        options { Row '选项甲' 180; Row '选项乙' 240; Row '看讲解' 290 }
        selected { Row '● 选项乙' 240; Row '看讲解' 290 }
        explanation { Row '这题选的是另一项。' 80; Row '水冷却到冰点会凝固。' 120 }
        next { Row '开始读这本书' 290 }
        empty { Row '对了。' 80; Row '开始读这本书' 290; Row '设置' 350 }
        hold { Row '再 试 一 次' 120 }
        importFailed { Row '再放一次' 200 }
        readOnly { Row '先翻开这本书' 200 }
        reader { Row '书架' 60 }
        shelf {
            Row 'LearningCoach' 50; Row '设置' 50 700
            if ($fault -eq 'wrongBook') { Row '另一份教材' 130 } else { Row $BookTitle 130 }
            Row '读到第 1 页' 180
        }
    })
    return $script:Last
}

function Replay($route, $fault, $expectedFailure, [switch]$Installed) {
    $InstalledExe = if ($Installed) { 'LearningCoach.exe' }
    $BookTitle = '测试教材'
    $dialogTitle = if ($route -eq '1.1.1') { '上传教材' } else { '导入文件' }
    $script:clock = [datetime]::UtcNow
    $script:view = 'home'
    $script:synced = $false
    $script:clicks = New-Object Collections.ArrayList
    $script:passes = New-Object Collections.ArrayList
    $failure = ''
    try { & $steps } catch { $failure = $_.Exception.Message }
    if ($expectedFailure) {
        if ($failure -notlike "*$expectedFailure*" -or ($script:passes -join ',') -ne '1') { throw "$route/$fault : $failure (view=$script:view clicks=$script:clicks)" }
    } else {
        if ($failure -or ($script:passes -join ',') -ne '1,2') { throw "$route/$fault : $failure (view=$script:view clicks=$script:clicks)" }
        $expected = if ($fault -eq 'syncFirstTaste') { 'menu,options@250,selected@300,next@300,reader@70' } elseif ($route -eq 'first') { 'home@210,options@250,selected@300,next@300,reader@70' } elseif ($route -eq '1.1.1') { 'home@210' } elseif ($route -in @('installedShelf', 'syncShelf')) { 'menu,reader@70' } else { 'home@210,reader@70' }
        if (($script:clicks -join ',') -ne $expected) { throw "$route wrong clicks: $script:clicks" }
    }
    "PASS $route $fault (installed=$([bool]$Installed))"
}
$offlineHomeText = @('放进一本书，马上做第一道题。', '现在没联网。书可以先放进来；出题和讲解要联网，连上后点再试一次。', '放进一本书')
if ($AfterPickerOcr) { $offlineHomeText = Get-Content -LiteralPath $AfterPickerOcr -Encoding UTF8 }
Replay first '' ''
Replay first '' '' -Installed
Replay installedShelf '' '' -Installed
Replay syncShelf '' '' -Installed
Replay syncShelf duringConfirm '' -Installed
Replay syncShelf syncFirstTaste '' -Installed
Replay first wrongBook '没有在首页确认目标书名和同卡进度' -Installed
Replay existing '' ''
Replay legacyImport '' ''
Replay first offlineThenQuestion ''
Replay first stuckOnHome '240 秒仍在空书架'
Replay first unavailable '出现「再试一次」按钮'
Replay first retryImport '出现「再放一次」按钮'
Replay first readOnly '出现「先翻开这本书」按钮'
Replay first empty '未同时验证'
Replay first noReader '未确认阅读器'
if (Get-HomeImportLabel @((Row '放进一本书，马上做第一道题。' 60))) { throw 'Heading matched the button' }
if (Get-HomeImportLabel @((Row '登录 LearningCoach' 60))) { throw 'Login matched the home screen' }
if ((Find-TasteOption @((Row '选一个答案' 60), (Row '○ 最先显示的选项' 240)) $null).Y -ne 250) { throw 'Missing marked option' }
'PASS heading-only, login, marked option'


# The 1.1.1 queue supplies its own worker with the old upload dialog and shelf confirmation.
if ($LegacyWorker) {
    $old = Get-Content -LiteralPath $LegacyWorker -Encoding UTF8 -Raw
    $start = $old.IndexOf('$m = Await-Text 1 ''start''')
    $end = $old.IndexOf('    # ③ 翻页', $start)
    $steps = [scriptblock]::Create($old.Substring($start, $end - $start))
    $oldAst = [Management.Automation.Language.Parser]::ParseInput($old, [ref]$null, [ref]$errors)
    if ($errors) { throw $errors[0] }
    foreach ($name in 'Click-Label','Click-Until-Dialog') {
        $fn = $oldAst.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name}, $true)
        if ($fn) { . ([scriptblock]::Create($fn.Extent.Text)) }
    }
    Replay '1.1.1' '' ''
}
