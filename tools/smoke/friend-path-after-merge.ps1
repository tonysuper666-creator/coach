# 监工每轮合并后调用的一条命令：把 origin/main 的当前提交检出到干净目录，跑一次朋友路径七步（-KeepGoing），
# 把 N/7 和每步结果追加到 D:\agent\evidence\friend-path\progress.jsonl，并打印一行摘要。
#   powershell -File tools\smoke\friend-path-after-merge.ps1 -Url http://<Mac>:<隔离端口> -ClientSeed <已配对目录> -Selection <可信选段.json>
# 退出码：7/7 为 0，否则 1；无法跑（检出或会话失败）为 2。截图只留在 winbox。
param(
    [Parameter(Mandatory)][string]$Url, [Parameter(Mandatory)][string]$ClientSeed, [Parameter(Mandatory)][string]$Selection,
    [string]$Remote = 'origin', [string]$Branch = 'main',
    [string]$Evidence = 'D:\agent\evidence\friend-path', [string]$Workroot = 'D:\agent\friend-path-runs'
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
try {
    git -C $repo fetch -q $Remote $Branch
    $sha = (git -C $repo rev-parse "$Remote/$Branch").Trim()
    $work = Join-Path $Workroot $sha.Substring(0, 8)
    if (-not (Test-Path $work)) { New-Item -ItemType Directory -Force $Workroot | Out-Null; git -C $repo worktree add -q --detach $work $sha }
    Set-Content -Encoding Ascii (Join-Path $work 'local.properties') 'sdk.dir=D\:\agent\android-sdk'
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $work 'tools\smoke\friend-path.ps1') -Url $Url -ClientSeed $ClientSeed -Selection $Selection -Evidence $Evidence -KeepGoing | Out-Host
    $dir = Get-ChildItem $Evidence -Directory | Where-Object { $_.Name -match '^\d{8}-\d{6}$' } | Sort-Object Name | Select-Object -Last 1
    $r = Get-Content (Join-Path $dir.FullName 'result.json') -Raw | ConvertFrom-Json
    $row = [ordered]@{ time = (Get-Date -Format 'o'); sha = $sha; passed = $r.passed; total = 7; evidence = $dir.FullName
        steps = @($r.steps | ForEach-Object { [ordered]@{ n = $_.Step; ok = $_.Ok; why = $_.Why } }) }
    ($row | ConvertTo-Json -Depth 5 -Compress) | Add-Content -Encoding utf8 (Join-Path $Evidence 'progress.jsonl')
    "friend-path @ $($sha.Substring(0,8)): $($r.passed)/7"
    if ($r.passed -eq 7) { exit 0 } else { exit 1 }
} catch { "friend-path-after-merge 无法运行：$_"; exit 2 }
