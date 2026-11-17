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
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint from, uint to, bool attach);
  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
  [DllImport("user32.dll")] public static extern uint GetQueueStatus(uint flags);
  [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr h);
  public static void Activate(IntPtr h) {
    if (GetForegroundWindow() == h) return;
    uint ignored;
    uint foreground = GetWindowThreadProcessId(GetForegroundWindow(), out ignored);
    uint caller = GetCurrentThreadId();
    GetQueueStatus(0x04FF); // Ensure the driving thread has an input queue.
    bool attached = foreground != 0 && foreground != caller && AttachThreadInput(caller, foreground, true);
    try { BringWindowToTop(h); SetForegroundWindow(h); }
    finally { if (attached) AttachThreadInput(caller, foreground, false); }
  }
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
$script:RepoMarker = ''   # 本次检出的目录；设置后只认命令行里带这个目录的客户端进程（别人的 Coach 窗口永远不是我的）
function Get-ClientPids([switch]$Mine) {
    @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
        $_.CommandLine -like '*com.coach.desktop.MainKt*' -and (-not $Mine -or -not $script:RepoMarker -or $_.CommandLine.Contains($script:RepoMarker)) } | ForEach-Object { [int]$_.ProcessId })
}
function Mark-ExistingClients { $script:Known = Get-ClientPids }
function Find-CoachWindow {
    $script:Win = [IntPtr]::Zero
    $mine = @(Get-ClientPids -Mine | Where-Object { $script:Known -notcontains $_ })
    if (-not $mine) { return $false }
    $script:AppPid = $mine[0]
    [WD]::EnumWindows({ param($x, $l)
        $p = 0; [WD]::GetWindowThreadProcessId($x, [ref]$p) | Out-Null
        if ($p -eq $script:AppPid -and [WD]::IsWindowVisible($x)) {
            $sb = New-Object Text.StringBuilder 256; [WD]::GetWindowText($x, $sb, 256) | Out-Null
            if (@('Coach', 'LearningCoach') -contains $sb.ToString()) { $script:Win = $x } }   # 产品改名后窗口标题是 LearningCoach；仍只认本次启动的进程
        return $true }, [IntPtr]::Zero) | Out-Null
    return ($script:Win -ne [IntPtr]::Zero)
}
function Stop-MyClient { if ($script:AppPid) { Stop-Process -Id $script:AppPid -Force -ErrorAction SilentlyContinue } }

function Get-Rect { $r = New-Object WD+RECT; [WD]::GetWindowRect($script:Win, [ref]$r) | Out-Null; $r }

# All drivers share this mutex, but hold it for one input/capture only.
# OCR, network waits, compilation and whole smoke runs stay outside.
function Invoke-ForegroundStep([scriptblock]$Action, [IntPtr]$Window = [IntPtr]::Zero) {
    if ($Window -eq [IntPtr]::Zero) { $Window = $script:Win }
    $owner = 0; [WD]::GetWindowThreadProcessId($Window, [ref]$owner) | Out-Null
    if (-not $script:AppPid -or $owner -ne $script:AppPid) { throw 'WIN_DRIVE_TARGET: window does not belong to this run' }
    $mutex = New-Object Threading.Mutex($false, 'Global\Coach.WinDrive.Foreground')
    try {
        foreach ($attempt in 1..20) {
            $held = $false
            try {
                try { $held = $mutex.WaitOne(30000) } catch [Threading.AbandonedMutexException] { $held = $true }
                if (-not $held) { throw 'WIN_DRIVE_TIMEOUT: waiting for one foreground action' }
                [WD]::Activate($Window)
                Start-Sleep -Milliseconds 150
                Assert-OurForeground
                & $Action
                return
            } catch {
                if ($_.Exception.Message -notlike 'WIN_DRIVE_FOCUS_LOST*') { throw }
                # Retry this action, never restart/reject the run for another window.
                # Release first so the other driver can finish its action.
                Write-Verbose "Foreground action retry $attempt/20"
            } finally {
                if ($held) { $mutex.ReleaseMutex() }
            }
            Start-Sleep -Milliseconds (Get-Random -Minimum 50 -Maximum 200)
        }
        throw 'WIN_DRIVE_TIMEOUT: could not regain our foreground for this action'
    } finally { $mutex.Dispose() }
}
function Save-Shot([string]$path, [IntPtr]$Window = [IntPtr]::Zero) {
    if ($Window -eq [IntPtr]::Zero) { $Window = $script:Win }
    Invoke-ForegroundStep -Window $Window -Action {
        $r = New-Object WD+RECT; [WD]::GetWindowRect($Window, [ref]$r) | Out-Null; $w = $r.R - $r.L; $h = $r.B - $r.T
        $bmp = New-Object System.Drawing.Bitmap $w, $h
        try {
            $g = [System.Drawing.Graphics]::FromImage($bmp)
            try {
                Assert-OurForeground
                $g.CopyFromScreen($r.L, $r.T, 0, 0, $bmp.Size)
                Assert-OurForeground
            } finally { $g.Dispose() }
            $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
        } finally { $bmp.Dispose() }
    }
}

# OCR a saved PNG; returns lines as objects { Text, Words } in window coordinates.
# Dark themes lose small low-contrast labels, so the picture is read twice (as is and inverted) and the lines are merged.
function Read-Bitmap([string]$png) {
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
function Read-Screen([string]$png) {
    $src = New-Object System.Drawing.Bitmap $png
    $inv = New-Object System.Drawing.Bitmap $src.Width, $src.Height
    $g = [System.Drawing.Graphics]::FromImage($inv)
    $cm = New-Object System.Drawing.Imaging.ColorMatrix (, [single[][]]@(@(-1,0,0,0,0), @(0,-1,0,0,0), @(0,0,-1,0,0), @(0,0,0,1,0), @(1,1,1,0,1)))
    $ia = New-Object System.Drawing.Imaging.ImageAttributes; $ia.SetColorMatrix($cm)
    $g.DrawImage($src, (New-Object System.Drawing.Rectangle 0, 0, $src.Width, $src.Height), 0, 0, $src.Width, $src.Height, [System.Drawing.GraphicsUnit]::Pixel, $ia)
    $g.Dispose(); $src.Dispose()
    $invPath = [IO.Path]::ChangeExtension($png, 'inv.png'); $inv.Save($invPath, [System.Drawing.Imaging.ImageFormat]::Png); $inv.Dispose()
    $a = @(Read-Bitmap $png); $b = @(Read-Bitmap $invPath)
    Remove-Item $invPath -ErrorAction SilentlyContinue
    $a + $b
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


# A notification or a non-Java window is not permission to type into it.
# The caller holds the short mutex and retries only the interrupted action.
function Assert-OurForeground {
    $fg = [WD]::GetForegroundWindow(); $owner = 0
    [WD]::GetWindowThreadProcessId($fg, [ref]$owner) | Out-Null
    if (-not $script:AppPid -or $owner -ne $script:AppPid) {
        throw 'WIN_DRIVE_FOCUS_LOST: our client is no longer foreground'
    }
}
function Click-At([int]$x, [int]$y) {
    Invoke-ForegroundStep {
        $r = Get-Rect
        [WD]::SetCursorPos($r.L + $x, $r.T + $y) | Out-Null
        Assert-OurForeground
        [WD]::mouse_event(2, 0, 0, 0, [IntPtr]::Zero)
        try { Start-Sleep -Milliseconds 60 } finally { [WD]::mouse_event(4, 0, 0, 0, [IntPtr]::Zero) }
    }
}
# A drag is one action: release the button even if foreground is interrupted.
function Drag-Between([int]$x1, [int]$y1, [int]$x2, [int]$y2) {
    Invoke-ForegroundStep {
        $r = Get-Rect
        [WD]::SetCursorPos($r.L + $x1, $r.T + $y1) | Out-Null
        Assert-OurForeground
        [WD]::mouse_event(2, 0, 0, 0, [IntPtr]::Zero)
        try {
            Start-Sleep -Milliseconds 120; Assert-OurForeground
            [WD]::SetCursorPos($r.L + [int](($x1 + $x2) / 2), $r.T + [int](($y1 + $y2) / 2)) | Out-Null
            Start-Sleep -Milliseconds 80; Assert-OurForeground
            [WD]::SetCursorPos($r.L + $x2, $r.T + $y2) | Out-Null
            Start-Sleep -Milliseconds 120; Assert-OurForeground
        } finally { [WD]::mouse_event(4, 0, 0, 0, [IntPtr]::Zero) }
    }
}
function Send-Keys([string]$keys) {
    Invoke-ForegroundStep { Assert-OurForeground; [System.Windows.Forms.SendKeys]::SendWait($keys) }
}

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

# Dialog discovery and waiting do not hold the mutex. Filename filling is
# idempotent on retry; each Enter is followed by an unlocked wait.
function Choose-File([string]$dialogTitlePrefix, [string]$path, [int]$timeoutSec = 20) {
    $deadline = (Get-Date).AddSeconds($timeoutSec); $h = [IntPtr]::Zero
    while ($h -eq [IntPtr]::Zero -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500; $h = Find-AppWindowByTitle $dialogTitlePrefix }
    if ($h -eq [IntPtr]::Zero) { return $false }
    Invoke-ForegroundStep -Window $h -Action {
        [System.Windows.Forms.SendKeys]::SendWait('%n')
        Assert-OurForeground
        [System.Windows.Forms.SendKeys]::SendWait('^a')
        Assert-OurForeground
        [System.Windows.Forms.SendKeys]::SendWait((ConvertTo-SendKeysLiteral $path))
    }
    for ($i = 0; $i -lt 3; $i++) {
        $h = Find-AppWindowByTitle $dialogTitlePrefix
        if ($h -eq [IntPtr]::Zero) { return $true }
        Invoke-ForegroundStep -Window $h -Action {
            Assert-OurForeground; [System.Windows.Forms.SendKeys]::SendWait('{ENTER}')
        }
        Start-Sleep -Seconds 2
    }
    return ((Find-AppWindowByTitle $dialogTitlePrefix) -eq [IntPtr]::Zero)
}
function ConvertTo-SendKeysLiteral([string]$text) {
    # One pass avoids corrupting brace escapes with later replacements.
    [regex]::Replace($text, '[+^%~(){}\[\]]', { param($m)
        if ($m.Value -eq '{') { '{{}' } elseif ($m.Value -eq '}') { '{}}' } else { '{' + $m.Value + '}' }
    })
}

function Save-Screen([string]$path) { Save-Shot $path }

function List-AppWindows {
    $script:WinList = @()
    [WD]::EnumWindows({ param($x, $l)
        $p = 0; [WD]::GetWindowThreadProcessId($x, [ref]$p) | Out-Null
        if ($p -eq $script:AppPid) { $sb = New-Object Text.StringBuilder 256; [WD]::GetWindowText($x, $sb, 256) | Out-Null; $r = New-Object WD+RECT; [WD]::GetWindowRect($x, [ref]$r) | Out-Null
            $script:WinList += "[$($sb.ToString())] vis=$([WD]::IsWindowVisible($x)) rect=$($r.L),$($r.T),$($r.R),$($r.B)" }
        return $true }, [IntPtr]::Zero) | Out-Null
    $script:WinList
}

# Mouse-wheel inside the window; negative delta scrolls down.
function Scroll-At([int]$x, [int]$y, [int]$notches) {
    Invoke-ForegroundStep {
        $r = Get-Rect
        [WD]::SetCursorPos($r.L + $x, $r.T + $y) | Out-Null
        $delta = [BitConverter]::ToUInt32([BitConverter]::GetBytes([int](120 * $notches)), 0)
        Assert-OurForeground
        [WD]::mouse_event(0x800, 0, 0, $delta, [IntPtr]::Zero)
    }
}
# Clipboard write and paste are one action. Keep it stable until consumed.
function Paste-Text([string]$text) {
    Invoke-ForegroundStep {
        [System.Windows.Forms.Clipboard]::SetText($text)
        Assert-OurForeground
        [System.Windows.Forms.SendKeys]::SendWait('^v')
        Start-Sleep -Milliseconds 150
    }
}
