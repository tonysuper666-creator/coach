# Runs inside winbox's console session (started by friend-path.ps1). Drives the real Windows client through the seven friend-path steps.
# Stops at the first step that fails and says why. Screenshots hold book text: they stay in $OutDir on winbox and are never uploaded.
param(
    [Parameter(Mandatory)][string]$Repo,       # checkout to build and run
    [Parameter(Mandatory)][string]$OutDir,     # D:\agent\evidence\friend-path\<time>
    [Parameter(Mandatory)][string]$Book,       # PDF on winbox, kept out of Git
    [string]$Url = '',                          # isolated test server on the Mac (never the production 18791)
    [string]$PairCode = '',
    [int]$StartTimeoutSec = 150
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Win-Drive.ps1')
New-Item -ItemType Directory -Force $OutDir | Out-Null
$steps = @('启动', '上传一本书', '翻页', '选中就问出讲解', '边看边学出讲解', '做题（答完出讲解和小结）', '点出处弹原文卡')
$results = New-Object System.Collections.ArrayList
$script:Last = $null
function Note([string]$m) { "$(Get-Date -Format 'HH:mm:ss') $m" | Tee-Object -FilePath (Join-Path $OutDir 'run.log') -Append }
function Shot([int]$n, [string]$tag) {
    $png = Join-Path $OutDir ('{0}-{1}.png' -f $n, $tag); Save-Shot $png
    $lines = @(Read-Screen $png); $script:Last = $lines
    ($lines | ForEach-Object Text) -join "`n" | Set-Content -Encoding utf8 ([IO.Path]::ChangeExtension($png, 'txt'))
    return $lines
}
function Fail([int]$n, [string]$why) {
    [void]$results.Add([pscustomobject]@{ Step = $n; Name = $steps[$n - 1]; Ok = $false; Why = $why }); Note "步骤$n 失败：$why"; throw 'STEP_FAILED'
}
function Pass([int]$n, [string]$what) { [void]$results.Add([pscustomobject]@{ Step = $n; Name = $steps[$n - 1]; Ok = $true; Why = $what }); Note "步骤$n 通过：$what" }
function Has($lines, [string[]]$needles) { foreach ($s in $needles) { $m = Find-Text $lines $s; if ($m) { return $m } }; $null }
function Await-Text([int]$n, [string]$tag, [string[]]$needles, [int]$sec) {
    $deadline = (Get-Date).AddSeconds($sec)
    do { $lines = Shot $n $tag; $m = Has $lines $needles; if ($m) { return $m }; Start-Sleep -Seconds 2 } while ((Get-Date) -lt $deadline)
    return $null
}
function Click-Label([int]$n, [string[]]$labels, [string]$why, [int]$sec = 15) {
    $m = Await-Text $n 'find' $labels $sec
    if (-not $m) { Fail $n "$why（屏幕上找不到「$($labels -join '」或「')」）" }
    Click-At $m.X $m.Y; Start-Sleep -Milliseconds 800
}

$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'; $env:GRADLE_USER_HOME = 'D:\agent\gradle-home'; $env:Path = "$env:JAVA_HOME\bin;$env:Path"
$lib = Join-Path $OutDir 'bookshelf'; $client = Join-Path $OutDir 'client'
New-Item -ItemType Directory -Force $lib, $client | Out-Null
$env:COACH_LIBRARY_DIR = $lib; $env:COACH_CLIENT_DIR = $client; $env:COACH_TOKEN_FILE = (Join-Path $OutDir 'unused-token')
$env:COACH_URL = if ($Url) { $Url } else { 'http://127.0.0.1:18999' }
$serverPort = ([uri]$env:COACH_URL).Port
if ($serverPort -in 18791, 8791, 8790) { throw "拒绝：$serverPort 是生产端口，冒烟只用隔离测试端口。" }
Mark-ExistingClients
$gradle = Start-Process (Join-Path $Repo 'gradlew.bat') -ArgumentList ':desktopApp:run', '-Pcoach.course=', '--console=plain', '--offline' -WorkingDirectory $Repo `
    -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $OutDir 'client.out.log') -RedirectStandardError (Join-Path $OutDir 'client.err.log')
try {
    # ① 启动
    $deadline = (Get-Date).AddSeconds($StartTimeoutSec)
    while (-not (Find-CoachWindow) -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 2 }
    if ($script:Win -eq [IntPtr]::Zero) { Fail 1 "客户端窗口 $StartTimeoutSec 秒内没有出现（见 client.err.log）" }
    $m = Await-Text 1 'start' @('上传教材', '书架') 30
    if (-not $m) { Fail 1 '窗口出来了但没有看到书架' }
    Pass 1 '客户端窗口出现，书架可见'

    # ② 上传一本书
    Click-Label 2 @('上传教材') '找不到上传按钮'
    if (-not (Choose-File '上传教材' $Book)) { Fail 2 '没有弹出选书对话框' }
    $m = Await-Text 2 'shelf' @('教材已加入书架', '先读') 120
    if (-not $m) { Fail 2 '选了书但 120 秒内书架里没出现这本书' }
    Pass 2 '书出现在书架'

    # ③ 翻页
    Click-Label 3 @('先读', '继续看书') '找不到「先读」'
    $m = Await-Text 3 'reader' @('下一页') 60
    if (-not $m) { Fail 3 '没有进到阅读器' }
    $before = Shot 3 'page-before'
    $page0 = ($before | Where-Object { $_.Text -match '^\d+[/／]\d+$' } | Select-Object -First 1).Text
    $page1 = $page0
    foreach ($try in 1..3) {
        Click-At $m.X $m.Y
        $stop = (Get-Date).AddSeconds(8)
        while ((Get-Date) -lt $stop -and $page1 -eq $page0) {
            Start-Sleep -Seconds 2; $after = Shot 3 'page-after'
            $page1 = ($after | Where-Object { $_.Text -match '^\d+[/／]\d+$' } | Select-Object -First 1).Text
        }
        if ($page1 -ne $page0) { break }
    }
    if (-not $page0 -or -not $page1 -or $page0 -eq $page1) { Fail 3 "点了下一页页码没变（前 $page0，后 $page1）" }
    Pass 3 "页码 $page0 → $page1"

    # ④ 选中就问：需要 Mac 上的隔离测试服务（配对 + AI）。先确认它连得上，连不上就停在这一步并说清楚。
    if (-not $Url) { Fail 4 '没有给隔离测试服务地址（-Url），问讲解必须配对 Mac 上的测试服务' }
    $u = [uri]$Url
    $tcp = New-Object Net.Sockets.TcpClient
    $reach = $false
    try { $reach = $tcp.ConnectAsync($u.Host, $u.Port).Wait(5000) -and $tcp.Connected } catch { }
    $tcp.Close()
    if (-not $reach) { Fail 4 "测试服务 $($u.Host):$($u.Port) 5 秒内连不上，讲解问不出去（端口写错或服务没起）" }
    if (-not $PairCode) { Fail 4 '没有配对码（-PairCode），客户端无法配对测试服务' }
    Fail 4 '服务连得上，但「选中就问」的界面驱动还没写完（步骤 4–7 待补）'
} catch {
    if ("$_" -ne 'STEP_FAILED') { Note "脚本异常：$_"; [void]$results.Add([pscustomobject]@{ Step = $results.Count + 1; Name = $steps[[Math]::Min($results.Count, 6)]; Ok = $false; Why = "脚本异常：$_" }) }
} finally {
    $passed = @($results | Where-Object Ok).Count
    $summary = [pscustomobject]@{ passed = $passed; total = 7; time = (Get-Date -Format 'o'); outDir = $OutDir; server = $env:COACH_URL; steps = $results }
    $summary | ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 (Join-Path $OutDir 'result.json')
    Stop-MyClient
    Stop-Process -Id $gradle.Id -Force -ErrorAction SilentlyContinue
    Note "结果 $passed/7"
}
