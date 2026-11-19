# 朋友路径七步冒烟（winbox 真窗口）。一条命令：
#   powershell -File tools\smoke\friend-path.ps1 [-Url http://<mac>:<隔离端口>] [-PairCode 123456]
# 输出 N/7，每步一张截图在 D:\agent\evidence\friend-path\<时间>\（含教材原文，只留在 winbox，不上传）。
param(
    [string]$Url = '', [string]$PairCode = '', [string]$ClientSeed = '', [string]$Selection = '', [switch]$KeepGoing, [int]$LessonNumber = 181, [int]$LessonPage = 150, [int]$StopAfter = 7,
    [switch]$Background,
    [string]$Runtime = '',
    [ValidatePattern('^[\p{L}\p{N}_-]{1,32}$')][string]$Marker = '',
    [string]$Book = 'D:\agent\friend-path-books\health-economics.pdf',   # 健康经济学教材，不进 Git
    [string]$Evidence = 'D:\agent\evidence\friend-path',
    [int]$StartTimeoutSec = 150,
    [int]$BuildTimeoutSec = 900,
    [int]$TimeoutSec = 1500
)
$ErrorActionPreference = 'Stop'
if (-not $PSBoundParameters.ContainsKey('TimeoutSec')) { $TimeoutSec = 600 + $BuildTimeoutSec }
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$out = Join-Path $Evidence ((Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force $out | Out-Null
$out = (Resolve-Path $out).Path
$args2 = "-Repo '$repo' -OutDir '$out' -Book '$Book' -StartTimeoutSec $StartTimeoutSec -BuildTimeoutSec $BuildTimeoutSec"
if ($Url) { $args2 += " -Url '$Url'" }
if ($KeepGoing) { $args2 += ' -KeepGoing' }
$args2 += " -LessonNumber $LessonNumber -LessonPage $LessonPage -StopAfter $StopAfter"
if ($Background) { $args2 += ' -Background' }
if ($Runtime) { $args2 += " -Runtime '$($Runtime.Replace("'", "''"))'" }
if ($Marker) { $args2 += " -Marker '$Marker'" }
if ($ClientSeed) { $args2 += " -ClientSeed '$ClientSeed'" }
if ($Selection) { $args2 += " -Selection '$Selection'" }
if ($PairCode) { $args2 += " -PairCode '$PairCode'" }
& (Join-Path $PSScriptRoot 'Run-Interactive.ps1') -Script (Join-Path $PSScriptRoot 'friend-path-worker.ps1') -ScriptArgs $args2 -LogFile (Join-Path $out 'worker.log') -TimeoutSec $TimeoutSec
$invocationExit = $LASTEXITCODE
$dirtyFile = Join-Path $out 'environment-dirty.json'
if ($invocationExit -eq 2 -or (Test-Path -LiteralPath $dirtyFile)) {
    $dirtyObj = if (Test-Path -LiteralPath $dirtyFile) {
        Get-Content -LiteralPath $dirtyFile -Raw -Encoding UTF8 | ConvertFrom-Json
    } else { $null }
    $reason = if ($dirtyObj -and $dirtyObj.reason) { $dirtyObj.reason } else { '桌面有人使用或存在测试遗留窗口' }
    if ($dirtyObj -and $reason -eq 'foreground-contention') {
        $holder = if ($dirtyObj.foregroundProcess -or $dirtyObj.foregroundPid) { " ($($dirtyObj.foregroundProcess) pid=$($dirtyObj.foregroundPid))" } else { '' }
        "ENVIRONMENT_DIRTY: $reason$holder （环境被占，不计 N/7；退出码 2；日志：$out）"
    } else {
        "ENVIRONMENT_DIRTY: $reason （未开跑，不计 N/7；退出码 2；日志：$out）"
    }
    exit 2
}
if (-not (Test-Path -LiteralPath (Join-Path $out 'result.json'))) {
    throw 'FRIEND_PATH_NO_RESULT: 调用失败，无有效 result；不记 N/7（见 worker.log.invocation.json）'
}
$r = Get-Content (Join-Path $out 'result.json') -Raw | ConvertFrom-Json
"$($r.passed)/7  （截图与日志：$out）"
foreach ($s in $r.steps) { '{0} 步骤{1} {2}：{3}' -f $(if ($s.Ok) { '✓' } else { '✗' }), $s.Step, $s.Name, $s.Why }
if ($r.passed -lt 7) { exit 1 }   # 未满 7/7 必须非 0，监工不能只看退出码 0
if ($invocationExit -ne 0) { exit $invocationExit }
