# Real-window driver for the Windows desktop client: launch, find window, screenshot, OCR, click by label, type.
# Dot-source this file. Nothing here touches any window other than the Coach window it launched.
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System; using System.Runtime.InteropServices; using System.Text;
public class WD {
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc p, IntPtr l);
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, int dx, int dy, uint d, IntPtr e);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
}
"@
[WD]::SetProcessDPIAware() | Out-Null
[void][Windows.Media.Ocr.OcrEngine, Windows.Media.Ocr, ContentType = WindowsRuntime]
[void][Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics.Imaging, ContentType = WindowsRuntime]
[void][Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime]
[void][Windows.Storage.Streams.IRandomAccessStream, Windows.Storage.Streams, ContentType = WindowsRuntime]

Add-Type -AssemblyName System.Runtime.WindowsRuntime
$script:asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
function Await($op, [Type]$type) { $t = $script:asTask.MakeGenericMethod($type).Invoke($null, @($op)); $t.Wait(); $t.Result }

[void][Windows.Globalization.Language, Windows.Globalization, ContentType = WindowsRuntime]
$script:Ocr = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage([Windows.Globalization.Language]::new('zh-Hans-CN'))
$script:Win = [IntPtr]::Zero
$script:AppPid = 0

# Other agents may run their own Coach windows on this desktop. Only the process this script started is ever touched.
$script:Known = @()
function Get-ClientPids { @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like '*com.coach.desktop.MainKt*' } | ForEach-Object { [int]$_.ProcessId }) }
function Mark-ExistingClients { $script:Known = Get-ClientPids }
function Find-CoachWindow {
    $script:Win = [IntPtr]::Zero
    $mine = @(Get-ClientPids | Where-Object { $script:Known -notcontains $_ })
    if (-not $mine) { return $false }
    $script:AppPid = $mine[0]
    [WD]::EnumWindows({ param($x, $l)
        $p = 0; [WD]::GetWindowThreadProcessId($x, [ref]$p) | Out-Null
        if ($p -eq $script:AppPid -and [WD]::IsWindowVisible($x)) {
            $sb = New-Object Text.StringBuilder 256; [WD]::GetWindowText($x, $sb, 256) | Out-Null
            if ($sb.ToString() -eq 'Coach') { $script:Win = $x } }
        return $true }, [IntPtr]::Zero) | Out-Null
    return ($script:Win -ne [IntPtr]::Zero)
}
function Stop-MyClient { if ($script:AppPid) { Stop-Process -Id $script:AppPid -Force -ErrorAction SilentlyContinue } }

function Get-Rect { $r = New-Object WD+RECT; [WD]::GetWindowRect($script:Win, [ref]$r) | Out-Null; $r }

function Save-Shot([string]$path) {
    [WD]::SetForegroundWindow($script:Win) | Out-Null; Start-Sleep -Milliseconds 300
    $r = Get-Rect; $w = $r.R - $r.L; $h = $r.B - $r.T
    $bmp = New-Object System.Drawing.Bitmap $w, $h
    $g = [System.Drawing.Graphics]::FromImage($bmp); $g.CopyFromScreen($r.L, $r.T, 0, 0, $bmp.Size); $g.Dispose()
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
}

# OCR a saved PNG; returns lines as objects { Text, X, Y, W, H, Words } in window coordinates.
function Read-Screen([string]$png) {
    $file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($png)) ([Windows.Storage.StorageFile])
    $stream = Await ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
    $dec = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
    $bm = Await ($dec.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
    $res = Await ($script:Ocr.RecognizeAsync($bm)) ([Windows.Media.Ocr.OcrResult])
    $stream.Dispose()
    foreach ($l in $res.Lines) {
        $ws = @($l.Words)
        [pscustomobject]@{ Text = (($ws | ForEach-Object { $_.Text }) -join ''); Words = $ws }
    }
}

# Locate a substring on screen (spaces ignored); returns the centre and box of the matched words, or $null.
function Find-Text($lines, [string]$needle) {
    $n = $needle -replace '\s', ''
    foreach ($l in $lines) {
        $text = ''; $spans = New-Object System.Collections.ArrayList
        foreach ($w in $l.Words) {
            $t = $w.Text -replace '\s', ''
            [void]$spans.Add([pscustomobject]@{ From = $text.Length; To = $text.Length + $t.Length; Box = $w.BoundingRect })
            $text += $t
        }
        $i = $text.IndexOf($n)
        if ($i -lt 0) { continue }
        $x1 = 1e9; $y1 = 1e9; $x2 = -1; $y2 = -1
        foreach ($sp in $spans) {
            if ($sp.To -le $i -or $sp.From -ge ($i + $n.Length)) { continue }
            $b = $sp.Box
            if ($b.X -lt $x1) { $x1 = $b.X }; if ($b.Y -lt $y1) { $y1 = $b.Y }
            if (($b.X + $b.Width) -gt $x2) { $x2 = $b.X + $b.Width }; if (($b.Y + $b.Height) -gt $y2) { $y2 = $b.Y + $b.Height }
        }
        return [pscustomobject]@{ X = [int](($x1 + $x2) / 2); Y = [int](($y1 + $y2) / 2); X1 = [int]$x1; X2 = [int]$x2; Y1 = [int]$y1; Y2 = [int]$y2 }
    }
    return $null
}

function Click-At([int]$x, [int]$y) {
    [WD]::SetForegroundWindow($script:Win) | Out-Null; Start-Sleep -Milliseconds 150
    $r = Get-Rect
    [WD]::SetCursorPos($r.L + $x, $r.T + $y) | Out-Null; Start-Sleep -Milliseconds 100
    [WD]::mouse_event(2, 0, 0, 0, [IntPtr]::Zero); Start-Sleep -Milliseconds 60; [WD]::mouse_event(4, 0, 0, 0, [IntPtr]::Zero)
}

# Drag from (x1,y1) to (x2,y2) inside the window, e.g. to select text.
function Drag-Between([int]$x1, [int]$y1, [int]$x2, [int]$y2) {
    [WD]::SetForegroundWindow($script:Win) | Out-Null; Start-Sleep -Milliseconds 150
    $r = Get-Rect
    [WD]::SetCursorPos($r.L + $x1, $r.T + $y1) | Out-Null; Start-Sleep -Milliseconds 100
    [WD]::mouse_event(2, 0, 0, 0, [IntPtr]::Zero); Start-Sleep -Milliseconds 120
    [WD]::SetCursorPos($r.L + [int](($x1 + $x2) / 2), $r.T + [int](($y1 + $y2) / 2)) | Out-Null; Start-Sleep -Milliseconds 80
    [WD]::SetCursorPos($r.L + $x2, $r.T + $y2) | Out-Null; Start-Sleep -Milliseconds 120
    [WD]::mouse_event(4, 0, 0, 0, [IntPtr]::Zero)
}

function Send-Keys([string]$keys) { [WD]::SetForegroundWindow($script:Win) | Out-Null; Start-Sleep -Milliseconds 150; [System.Windows.Forms.SendKeys]::SendWait($keys) }

# Wait until a label shows on screen. Returns the match, or $null on timeout. Every poll leaves a PNG so a failure is inspectable.
function Wait-Text([string]$dir, [string]$name, [string[]]$needles, [int]$timeoutSec) {
    $deadline = (Get-Date).AddSeconds($timeoutSec); $n = 0
    while ($true) {
        $png = Join-Path $dir "$name.png"
        Save-Shot $png
        $lines = @(Read-Screen $png)
        foreach ($s in $needles) { $m = Find-Text $lines $s; if ($m) { $m | Add-Member -NotePropertyName Needle -NotePropertyValue $s -PassThru | Add-Member -NotePropertyName Lines -NotePropertyValue $lines -PassThru; return } }
        if ((Get-Date) -gt $deadline) { return $null }
        Start-Sleep -Seconds 2; $n++
    }
}

# Find a visible top-level window of the app's process whose title starts with $prefix (native file dialog etc.).
function Find-AppWindowByTitle([string]$prefix) {
    $script:Found = [IntPtr]::Zero
    [WD]::EnumWindows({ param($x, $l)
        $p = 0; [WD]::GetWindowThreadProcessId($x, [ref]$p) | Out-Null
        if ($p -eq $script:AppPid -and [WD]::IsWindowVisible($x)) {
            $sb = New-Object Text.StringBuilder 256; [WD]::GetWindowText($x, $sb, 256) | Out-Null
            if ($sb.ToString().StartsWith($prefix)) { $script:Found = $x } }
        return $true }, [IntPtr]::Zero) | Out-Null
    return $script:Found
}

# Pick a file in the app's native "open" dialog: focus the file-name box (Alt+N), type the full path, Enter.
function Choose-File([string]$dialogTitlePrefix, [string]$path, [int]$timeoutSec = 20) {
    $deadline = (Get-Date).AddSeconds($timeoutSec); $h = [IntPtr]::Zero
    while ($h -eq [IntPtr]::Zero -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500; $h = Find-AppWindowByTitle $dialogTitlePrefix }
    if ($h -eq [IntPtr]::Zero) { return $false }
    Start-Sleep -Seconds 1
    [WD]::SetForegroundWindow($h) | Out-Null; Start-Sleep -Milliseconds 400
    [System.Windows.Forms.SendKeys]::SendWait('%n'); Start-Sleep -Milliseconds 300
    [System.Windows.Forms.SendKeys]::SendWait($path.Replace('{', '{{}').Replace('}', '{}}').Replace('+', '{+}').Replace('^', '{^}').Replace('%', '{%}').Replace('~', '{~}').Replace('(', '{(}').Replace(')', '{)}'))
    Start-Sleep -Milliseconds 300
    for ($i = 0; $i -lt 3; $i++) {
        [System.Windows.Forms.SendKeys]::SendWait('{ENTER}'); Start-Sleep -Seconds 2
        if ((Find-AppWindowByTitle $dialogTitlePrefix) -eq [IntPtr]::Zero) { break }
    }
    return $true
}

function Save-Screen([string]$path) {
    $b = [System.Windows.Forms.SystemInformation]::VirtualScreen
    $bmp = New-Object System.Drawing.Bitmap $b.Width, $b.Height
    $g = [System.Drawing.Graphics]::FromImage($bmp); $g.CopyFromScreen($b.Left, $b.Top, 0, 0, $bmp.Size); $g.Dispose()
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
}
function List-AppWindows {
    $script:WinList = @()
    [WD]::EnumWindows({ param($x, $l)
        $p = 0; [WD]::GetWindowThreadProcessId($x, [ref]$p) | Out-Null
        if ($p -eq $script:AppPid) { $sb = New-Object Text.StringBuilder 256; [WD]::GetWindowText($x, $sb, 256) | Out-Null; $r = New-Object WD+RECT; [WD]::GetWindowRect($x, [ref]$r) | Out-Null
            $script:WinList += "[$($sb.ToString())] vis=$([WD]::IsWindowVisible($x)) rect=$($r.L),$($r.T),$($r.R),$($r.B)" }
        return $true }, [IntPtr]::Zero) | Out-Null
    $script:WinList
}
