# 朋友路径七步冒烟（winbox 真窗口）。一条命令：
#   powershell -File tools\smoke\friend-path.ps1 [-Url http://<mac>:<隔离端口>] [-PairCode 123456]
# 输出 N/7，每步一张截图在 D:\agent\evidence\friend-path\<时间>\（含教材原文，只留在 winbox，不上传）。
param(
    [string]$Url = '', [string]$PairCode = '', [string]$ClientSeed = '', [string]$Selection = '', [switch]$KeepGoing,
    [string]$Book = 'D:\agent\friend-path-books\health-economics.pdf',   # 健康经济学教材，不进 Git
    [string]$Evidence = 'D:\agent\evidence\friend-path', [int]$TimeoutSec = 600
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$out = Join-Path $Evidence ((Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force $out | Out-Null
$out = (Resolve-Path $out).Path
$args2 = "-Repo '$repo' -OutDir '$out' -Book '$Book'"
if ($Url) { $args2 += " -Url '$Url'" }
if ($KeepGoing) { $args2 += ' -KeepGoing' }
if ($ClientSeed) { $args2 += " -ClientSeed '$ClientSeed'" }
if ($Selection) { $args2 += " -Selection '$Selection'" }
if ($PairCode) { $args2 += " -PairCode '$PairCode'" }
& (Join-Path $PSScriptRoot 'Run-Interactive.ps1') -Script (Join-Path $PSScriptRoot 'friend-path-worker.ps1') -ScriptArgs $args2 -LogFile (Join-Path $out 'worker.log') -TimeoutSec $TimeoutSec
$r = Get-Content (Join-Path $out 'result.json') -Raw | ConvertFrom-Json
"$($r.passed)/7  （截图与日志：$out）"
foreach ($s in $r.steps) { '{0} 步骤{1} {2}：{3}' -f $(if ($s.Ok) { '✓' } else { '✗' }), $s.Step, $s.Name, $s.Why }
if ($r.passed -lt 7) { exit 1 }   # 未满 7/7 必须非 0，监工不能只看退出码 0
