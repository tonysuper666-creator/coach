# 离线回归：动作中途失焦不重放（退出码：0 = 全部通过）。由 test-dirty-preflight.ps1 以子进程调用，
# 因为这里会替换 Start-Sleep、Drag-Between 等函数，不能污染调用方。
#  - 拖拽：按住期间失焦就停止后续移动、只松键一次，并且不被 Invoke-ForegroundStep 当成可重试动作整段重放。
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
. (Join-Path $repo 'tools/smoke/Win-Drive.ps1')

$results = New-Object System.Collections.ArrayList
function Check([string]$name, [bool]$ok, [string]$detail = '') {
    [void]$results.Add($ok)
    $tag = if ($ok) { 'PASS' } else { 'FAIL' }
    "$tag $name$(if ($detail) { " ($detail)" })"
}

$savedSmoke = $env:COACH_SMOKE_ENABLED
function Start-Sleep {}

# 1. 拖拽：按下后立即失焦。截取真实 Drag-Between，替换原生输入。
$env:COACH_SMOKE_ENABLED = '0'
Add-Type @'
using System;
public class CoachDragInterruptCheck {
    public static bool Lost;
    public static int Presses, Releases, MovesAfterLoss;
    public static void Reset() { Lost = false; Presses = 0; Releases = 0; MovesAfterLoss = 0; }
    public static bool SetCursorPos(int x, int y) { if (Lost) MovesAfterLoss++; return true; }
    public static void mouse_event(uint f, int dx, int dy, uint data, IntPtr extra) {
        if (f == 2) { Presses++; Lost = true; }
        if (f == 4) Releases++;
    }
}
'@
$dragDef = (Get-Command Drag-Between).Definition.Replace('[WD]', '[CoachDragInterruptCheck]')
Set-Item Function:Drag-Between ([scriptblock]::Create($dragDef))
function Get-Rect { [pscustomobject]@{ L = 0; T = 0 } }
$script:dragAttempts = 0
# 和真实 Invoke-ForegroundStep 一样：只有 WIN_DRIVE_FOCUS_LOST 开头的异常才重试本动作。
function Invoke-ForegroundStep([switch]$InjectsInput, [scriptblock]$Action) {
    for ($i = 1; $i -le 3; $i++) {
        $script:dragAttempts = $i
        try { & $Action; return } catch { if ($_.Exception.Message -notlike 'WIN_DRIVE_FOCUS_LOST*') { throw } }
    }
    throw 'retries exhausted'
}
function Assert-OurForeground {
    if ([CoachDragInterruptCheck]::Lost) { throw 'WIN_DRIVE_FOCUS_LOST: our client is no longer foreground' }
}
function Resolve-ForegroundRetryTimeout([string]$LastFocusLoss = '', $ForegroundOwner = $null) {
    [System.InvalidOperationException]::new("WIN_DRIVE_TIMEOUT: $LastFocusLoss")
}
[CoachDragInterruptCheck]::Reset()
$outcome = 'returned'
try { Drag-Between 10 10 50 50 } catch { $outcome = $_.Exception.Message }
Check 'drag-focus-lost-while-held-stops-releases-once-no-replay' (
    [CoachDragInterruptCheck]::MovesAfterLoss -eq 0 -and [CoachDragInterruptCheck]::Releases -eq 1 -and
    [CoachDragInterruptCheck]::Presses -eq 1 -and $script:dragAttempts -eq 1 -and
    $outcome -like '*WIN_DRIVE_DRAG_INTERRUPTED*'
) "moves=$([CoachDragInterruptCheck]::MovesAfterLoss) presses=$([CoachDragInterruptCheck]::Presses) releases=$([CoachDragInterruptCheck]::Releases) attempts=$script:dragAttempts outcome=$outcome"

# 3. 拖拽正例：全程持有前台，中点、终点各移动一次，按下、松键各一次。
[CoachDragInterruptCheck]::Reset()
function Assert-OurForeground {}
$script:dragAttempts = 0
$outcome = 'returned'
try { Drag-Between 10 10 50 50 } catch { $outcome = $_.Exception.Message }
Check 'drag-stable-completes-once' (
    [CoachDragInterruptCheck]::Presses -eq 1 -and [CoachDragInterruptCheck]::Releases -eq 1 -and
    $script:dragAttempts -eq 1 -and $outcome -eq 'returned'
) "presses=$([CoachDragInterruptCheck]::Presses) releases=$([CoachDragInterruptCheck]::Releases) attempts=$script:dragAttempts outcome=$outcome"
$env:COACH_SMOKE_ENABLED = $savedSmoke

$failed = @($results | Where-Object { -not $_ }).Count
"RESULT $($results.Count - $failed)/$($results.Count)"
exit $(if ($failed) { 1 } else { 0 })
