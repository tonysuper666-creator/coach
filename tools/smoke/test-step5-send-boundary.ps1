# 第⑤步发送边界：④ 已同意的起点下，默认首节和落在授权外的节发出 0 次整节请求，核准页之后的授权节正好发 1 次。
# 真客户端 + 真服务，中间夹一个只记次数和页码的计数代理（explain-count-proxy.py，不存原文和凭据）。
# 在登录会话里跑（真窗口）：powershell -File tools\smoke\test-step5-send-boundary.ps1 -ClientSeed <已配对目录> -Selection <可信选段.json>
# 退出码 0 = 三条都满足。会真调一次整节讲解 + 每轮 ④ 的两次调用，只对授权的隔离服务做。
param(
    [Parameter(Mandatory)][string]$ClientSeed, [Parameter(Mandatory)][string]$Selection,
    [string]$Book = 'D:\agent\friend-path-books\health-economics.pdf',
    [int]$GoodLesson = 181, [int]$WrongLesson = 10, [int]$Page = 150,
    [string]$Evidence = 'D:\agent\evidence\friend-path'
)
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$upstream = (Get-Content (Join-Path $ClientSeed 'pairing\server-url') -Raw).Trim()
$work = Join-Path $Evidence ('step5-boundary-' + (Get-Date -Format 'yyyyMMdd-HHmmss')); New-Item -ItemType Directory -Force $work | Out-Null
$log = Join-Path $work 'counts.jsonl'; $portFile = Join-Path $work 'port.txt'
$proxy = Start-Process python -ArgumentList @((Join-Path $here 'explain-count-proxy.py'), '--upstream', $upstream, '--log', $log, '--port-file', $portFile) -WindowStyle Hidden -PassThru
try {
    foreach ($i in 1..50) { if (Test-Path $portFile) { break }; Start-Sleep -Milliseconds 200 }
    $port = (Get-Content $portFile -Raw).Trim(); $proxyUrl = "http://127.0.0.1:$port"
    # 配对身份绑定服务地址：复制一份把地址改成代理，原配对目录不动
    $seed = Join-Path $work 'seed'; Copy-Item -Recurse $ClientSeed $seed
    $cred = Join-Path $seed 'pairing\credentials.properties'
    (Get-Content $cred -Raw) -replace '(?m)^server=.*$', ('server=' + ($proxyUrl -replace ':', '\:')) | Set-Content -NoNewline -Encoding ascii $cred
    Set-Content -NoNewline -Encoding ascii (Join-Path $seed 'pairing\server-url') $proxyUrl
    $verdicts = @()
    foreach ($case in @(@{ n = '选错的节（第 13 页目录项）'; lesson = $WrongLesson; expect = 0 }, @{ n = '授权节（核准第 150 页后）'; lesson = $GoodLesson; expect = 1 })) {
        Remove-Item $log -ErrorAction SilentlyContinue
        $ev = Join-Path $work ('run-' + $case.lesson)
        & (Join-Path $here 'friend-path.ps1') -Url $proxyUrl -ClientSeed $seed -Selection $Selection -Book $Book -Evidence $ev `
            -LessonNumber $case.lesson -LessonPage $Page -StopAfter 5 | Out-Host
        $recs = if (Test-Path $log) { @(Get-Content $log | ForEach-Object { $_ | ConvertFrom-Json }) } else { @() }
        $lessons = @($recs | Where-Object { $_.kind -eq 'lesson' })
        $outside = @($lessons | Where-Object { ($_.pages | Where-Object { $_ -ne $Page }).Count -gt 0 -or $_.pages.Count -eq 0 })
        $result = Get-ChildItem $ev -Recurse -Filter result.json | Select-Object -First 1
        $five = if ($result) { (Get-Content $result.FullName -Raw | ConvertFrom-Json).steps | Where-Object { $_.Step -eq 5 } } else { $null }
        $ok = ($lessons.Count -eq $case.expect) -and ($outside.Count -eq 0) -and $(if ($case.expect -eq 1) { $five -and $five.Ok } else { $five -and -not $five.Ok })
        $verdicts += [pscustomobject]@{ case = $case.n; lessonRequests = $lessons.Count; expected = $case.expect; outsideAuthorizedPage = $outside.Count
            otherKinds = (($recs | Where-Object { $_.kind -ne 'lesson' } | ForEach-Object kind) -join ','); step5 = $(if ($five) { $five.Ok } else { 'n/a' }); pass = $ok }
    }
    $verdicts | Format-Table -AutoSize | Out-String | Write-Host
    $verdicts | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $work 'verdicts.json')
    if (@($verdicts | Where-Object { -not $_.pass }).Count -gt 0) { "FAIL (见 $work)"; exit 1 } else { "PASS (见 $work)"; exit 0 }
} finally { if ($proxy -and -not $proxy.HasExited) { Stop-Process -Id $proxy.Id -Force -ErrorAction SilentlyContinue } }
