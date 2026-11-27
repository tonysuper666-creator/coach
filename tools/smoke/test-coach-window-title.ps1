# Regression test for unified Coach window-title matching across tools/smoke.
$ErrorActionPreference = 'Stop'

$smokeDir = $PSScriptRoot
$scripts = @('Win-Drive.ps1', 'msi-install.ps1', 'prism-ocr.ps1', 'friend-path-worker.ps1')
$asts = @{}
foreach ($name in $scripts) {
    $path = Join-Path $smokeDir $name
    $raw = [IO.File]::ReadAllText($path, [Text.Encoding]::UTF8)
    $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseInput($raw, [ref]$null, [ref]$errors)
    if ($errors -and $errors.Count -gt 0) {
        throw "Parse error in ${name}: $($errors[0].Message)"
    }
    $asts[$name] = $ast
}

function Get-FunctionSource($ast, [string]$fnName) {
    $node = $ast.Find({
        param($n)
        $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $fnName
    }, $true)
    if (-not $node) { throw "Missing function $fnName" }
    return $node.Extent.Text
}

if (-not ('MockTitleWD' -as [type])) {
    Add-Type @'
using System;
using System.Collections.Generic;
using System.Text;

public static class MockTitleWD {
    public delegate bool EnumProc(IntPtr h, IntPtr l);
    public class WinInfo {
        public long Hwnd;
        public int Pid;
        public bool Visible;
        public string Title;
    }
    public static List<WinInfo> Windows = new List<WinInfo>();
    public static bool EnumWindows(EnumProc p, IntPtr l) {
        foreach (var w in Windows) {
            if (!p((IntPtr)w.Hwnd, l)) break;
        }
        return true;
    }
    public static uint GetWindowThreadProcessId(IntPtr h, out uint pid) {
        foreach (var w in Windows) {
            if (w.Hwnd == h.ToInt64()) { pid = (uint)w.Pid; return 1; }
        }
        pid = 0;
        return 0;
    }
    public static bool IsWindowVisible(IntPtr h) {
        foreach (var w in Windows) {
            if (w.Hwnd == h.ToInt64()) return w.Visible;
        }
        return false;
    }
    public static int GetWindowText(IntPtr h, StringBuilder s, int n) {
        foreach (var w in Windows) {
            if (w.Hwnd == h.ToInt64()) {
                string t = w.Title ?? "";
                if (t.Length >= n) t = t.Substring(0, n - 1);
                s.Append(t);
                return t.Length;
            }
        }
        return 0;
    }
}

public static class MockTitleCoachWin {
    public static string Text(IntPtr h) {
        var sb = new StringBuilder(512);
        MockTitleWD.GetWindowText(h, sb, sb.Capacity);
        return sb.ToString();
    }
}
'@
}

# Load production functions extracted from the real scripts.
. ([scriptblock]::Create((Get-FunctionSource $asts['Win-Drive.ps1'] 'Test-CoachWindowTitle')))
. ([scriptblock]::Create((Get-FunctionSource $asts['Win-Drive.ps1'] 'Find-Text')))
. ([scriptblock]::Create((Get-FunctionSource $asts['Win-Drive.ps1'] 'Find-CoachWindow').Replace('[WD]', '[MockTitleWD]')))
. ([scriptblock]::Create((Get-FunctionSource $asts['msi-install.ps1'] 'Find-InstalledWindow').Replace('[WD]', '[MockTitleWD]').Replace('[CoachWin]', '[MockTitleCoachWin]')))
. ([scriptblock]::Create((Get-FunctionSource $asts['friend-path-worker.ps1'] 'Get-LineBox')))
. ([scriptblock]::Create((Get-FunctionSource $asts['friend-path-worker.ps1'] 'Test-TasteExplanation')))
. ([scriptblock]::Create((Get-FunctionSource $asts['friend-path-worker.ps1'] 'Find-OnRow')))
. ([scriptblock]::Create((Get-FunctionSource $asts['friend-path-worker.ps1'] 'Test-Step2Home')))

$checks = New-Object System.Collections.ArrayList
function Check([string]$name, [bool]$ok, [string]$detail = '') {
    [void]$checks.Add([pscustomobject]@{ Name = $name; Ok = $ok; Detail = $detail })
    if (-not $ok) { throw "FAIL ${name}: $detail" }
    Write-Output "PASS $name"
}

# 1. Unified default title rule: ^LearningCoach(?:\s|$)
$shouldMatch = @(
    'LearningCoach',
    'LearningCoach 1.1.1',
    'LearningCoach 2.0.7',
    'LearningCoach 2.0.7-rc1',
    'LearningCoach dev'
)
foreach ($title in $shouldMatch) {
    Check "title-match-$title" (Test-CoachWindowTitle $title) "title='$title'"
}

$shouldReject = @(
    '',
    $null,
    'Coach',
    'Coach 2.0.7',
    'LearningCoachUpdater',
    'LearningCoach2',
    'LearningCoach-Helper',
    'Login LearningCoach',
    'Reader - LearningCoach'
)
foreach ($title in $shouldReject) {
    $label = if ($null -eq $title) { '<null>' } elseif ($title -eq '') { '<empty>' } else { $title }
    Check "title-reject-$label" (-not (Test-CoachWindowTitle $title)) "title='$label'"
}

# 2. Win-Drive.ps1 Find-CoachWindow (PID ownership + visibility + versioned/legacy/non-target titles)
try {
$script:MockMinePids = @()
function Get-ClientPids([switch]$Mine) { return $script:MockMinePids }
function Has-BackgroundDriver { return $false }
function Get-Process { param($Id, $ErrorAction) return [pscustomobject]@{ Id = $Id } }
function Get-TrackedProcessStartTicks($proc) { return 123456789L }

$script:Known = @()
$script:AppPid = 0
$script:AppStartTicks = 0L

# 2a. Legacy 1.1.1 title
[MockTitleWD]::Windows.Clear()
[MockTitleWD]::Windows.Add([MockTitleWD+WinInfo]@{ Hwnd = 1001L; Pid = 5001; Visible = $true; Title = 'LearningCoach' })
$script:MockMinePids = @(5001)
Check 'find-coach-window-legacy-title' ((Find-CoachWindow) -and $script:Win -eq [IntPtr]1001L -and $script:AppPid -eq 5001) "win=$($script:Win) pid=$($script:AppPid)"

# 2b. Versioned title with non-target similar-prefix and unowned windows present
[MockTitleWD]::Windows.Clear()
[MockTitleWD]::Windows.Add([MockTitleWD+WinInfo]@{ Hwnd = 2001L; Pid = 9999; Visible = $true; Title = 'LearningCoach 2.0.7' })
[MockTitleWD]::Windows.Add([MockTitleWD+WinInfo]@{ Hwnd = 2002L; Pid = 5002; Visible = $true; Title = 'LearningCoachUpdater' })
[MockTitleWD]::Windows.Add([MockTitleWD+WinInfo]@{ Hwnd = 2003L; Pid = 5002; Visible = $false; Title = 'LearningCoach 2.0.7' })
[MockTitleWD]::Windows.Add([MockTitleWD+WinInfo]@{ Hwnd = 2004L; Pid = 5002; Visible = $true; Title = 'LearningCoach 2.0.7' })
$script:MockMinePids = @(5002)
$script:AppPid = 0
Check 'find-coach-window-versioned-owned-only' ((Find-CoachWindow) -and $script:Win -eq [IntPtr]2004L -and $script:AppPid -eq 5002) "win=$($script:Win) pid=$($script:AppPid)"

# 2c. Only similar-prefix non-target window on owned PID -> must fail
[MockTitleWD]::Windows.Clear()
[MockTitleWD]::Windows.Add([MockTitleWD+WinInfo]@{ Hwnd = 3001L; Pid = 5003; Visible = $true; Title = 'LearningCoachHelper' })
$script:MockMinePids = @(5003)
$script:AppPid = 0
Check 'find-coach-window-rejects-similar-prefix' (-not (Find-CoachWindow) -and $script:Win -eq [IntPtr]::Zero) "win=$($script:Win)"
} finally {
    Remove-Item Function:\Get-ClientPids, Function:\Has-BackgroundDriver, Function:\Get-Process, Function:\Get-TrackedProcessStartTicks -ErrorAction SilentlyContinue
}

# 3. msi-install.ps1 Find-InstalledWindow
$script:TargetPids = @(6001)
$script:AppPid = 0
[MockTitleWD]::Windows.Clear()
[MockTitleWD]::Windows.Add([MockTitleWD+WinInfo]@{ Hwnd = 4001L; Pid = 9999; Visible = $true; Title = 'LearningCoach 2.0.7' })
[MockTitleWD]::Windows.Add([MockTitleWD+WinInfo]@{ Hwnd = 4002L; Pid = 6001; Visible = $true; Title = 'LearningCoach2' })
[MockTitleWD]::Windows.Add([MockTitleWD+WinInfo]@{ Hwnd = 4003L; Pid = 6001; Visible = $true; Title = 'LearningCoach 2.0.7' })
$hwnd = Find-InstalledWindow
Check 'msi-find-installed-versioned' ($hwnd -eq [IntPtr]4003L -and $script:WinPid -eq 6001 -and $script:AppPid -eq 6001) "hwnd=$hwnd winPid=$($script:WinPid) appPid=$($script:AppPid)"

[MockTitleWD]::Windows.Clear()
[MockTitleWD]::Windows.Add([MockTitleWD+WinInfo]@{ Hwnd = 4004L; Pid = 6001; Visible = $true; Title = 'LearningCoach' })
$hwnd = Find-InstalledWindow
Check 'msi-find-installed-legacy' ($hwnd -eq [IntPtr]4004L -and $script:WinPid -eq 6001 -and $script:AppPid -eq 6001) "hwnd=$hwnd"

[MockTitleWD]::Windows.Clear()
[MockTitleWD]::Windows.Add([MockTitleWD+WinInfo]@{ Hwnd = 4005L; Pid = 6001; Visible = $true; Title = 'LearningCoachUpdater' })
$script:AppPid = 0
$hwnd = Find-InstalledWindow
Check 'msi-find-installed-rejects-similar-prefix' ($hwnd -eq [IntPtr]::Zero -and $script:AppPid -eq 0) "hwnd=$hwnd"

# 4. prism-ocr.ps1 window-filter pipeline (extracted directly from AST)
$prismFilterNode = $asts['prism-ocr.ps1'].Find({
    param($n)
    $n -is [Management.Automation.Language.ScriptBlockExpressionAst] -and
    $n.Extent.Text -match 'MainWindowHandle' -and
    $n.Extent.Text -match 'MainWindowTitle'
}, $true)
if (-not $prismFilterNode) { throw 'Missing MainWindowTitle filter in prism-ocr.ps1' }
$prismFilterText = $prismFilterNode.Extent.Text
Check 'prism-ocr-uses-unified-title-helper' ($prismFilterText -match 'Test-CoachWindowTitle') $prismFilterText
$prismPredicate = [scriptblock]::Create($prismFilterText.Substring(1, $prismFilterText.Length - 2))
$mockProcs = @(
    [pscustomobject]@{ Id = 1; MainWindowHandle = 0; MainWindowTitle = 'LearningCoach 2.0.7' },
    [pscustomobject]@{ Id = 2; MainWindowHandle = 501; MainWindowTitle = 'LearningCoachUpdater' },
    [pscustomobject]@{ Id = 3; MainWindowHandle = 502; MainWindowTitle = 'LearningCoach 2.0.7' },
    [pscustomobject]@{ Id = 4; MainWindowHandle = 503; MainWindowTitle = 'LearningCoach' }
)
$prismMatched = @($mockProcs | Where-Object $prismPredicate)
Check 'prism-ocr-filter-matches-versioned-and-legacy' ($prismMatched.Count -eq 2 -and ($prismMatched.Id -join ',') -eq '3,4') "ids=$($prismMatched.Id -join ',')"

# 5. friend-path-worker.ps1 Test-TasteExplanation and Test-Step2Home
function New-OcrRow([string]$text, [int]$y, [int]$x = 120) {
    $word = [pscustomobject]@{ Text = $text; BoundingRect = @{ X = $x; Y = $y; Width = 140; Height = 20 } }
    [pscustomobject]@{ Text = $text; Words = @($word) }
}
$verdict = Get-LineBox (New-OcrRow '对了。' 80)
$button = Get-LineBox (New-OcrRow '开始读这本书' 290)
Check 'taste-explanation-ignores-legacy-chrome' (-not (Test-TasteExplanation @((New-OcrRow 'LearningCoach' 140)) $verdict $button)) 'legacy chrome treated as explanation'
Check 'taste-explanation-ignores-versioned-chrome' (-not (Test-TasteExplanation @((New-OcrRow 'LearningCoach 2.0.7' 140)) $verdict $button)) 'versioned chrome treated as explanation'
Check 'taste-explanation-ignores-joined-ocr-version' (-not (Test-TasteExplanation @((New-OcrRow 'LearningCoach2.0.7' 140)) $verdict $button)) 'OCR-joined versioned chrome treated as explanation'
Check 'taste-explanation-accepts-body-text' (Test-TasteExplanation @((New-OcrRow 'LearningCoach 2.0.7' 140), (New-OcrRow '水冷却到冰点会凝固。' 180)) $verdict $button) 'valid body rejected'

$homeLines = @(
    (New-OcrRow 'LearningCoach 2.0.7' 10 25),
    (New-OcrRow 'LearningCoach' 68 80),
    (New-OcrRow '设置' 68 900)
)
Check 'step2-home-matches-inapp-header-with-versioned-titlebar' (Test-Step2Home $homeLines) 'Test-Step2Home failed'

Write-Output "COACH_WINDOW_TITLE $($checks.Count)/$($checks.Count) PASS"
