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
  [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetClassName(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool IsWindowEnabled(IntPtr h);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int command);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool ShowWindowAsync(IntPtr h, int command);
  [DllImport("user32.dll", SetLastError = true)] public static extern IntPtr SendMessageTimeout(
    IntPtr h, uint message, UIntPtr wParam, IntPtr lParam, uint flags, uint timeoutMs, out UIntPtr result);
  public static string LastActivationFailure { get; private set; }
  public static bool LastActivationAccepted { get; private set; }
  public static IntPtr ForegroundAtConfirmation { get; private set; }
  public static bool Activate(IntPtr h) {
    // Keep the automation queue independent: attaching to a busy foreground
    // thread can block BringWindowToTop/SetForegroundWindow while holding our mutex.
    if (IsIconic(h)) ShowWindowAsync(h, 9); // SW_RESTORE
    LastActivationAccepted = SetForegroundWindow(h);
    UIntPtr ignored;
    // WM_NULL confirms the asynchronous activation has been processed. Never
    // wait indefinitely for a hung UI, and never bypass foreground ownership.
    bool responsive = SendMessageTimeout(h, 0, UIntPtr.Zero, IntPtr.Zero, 0x23, 500, out ignored) != IntPtr.Zero;
    ForegroundAtConfirmation = GetForegroundWindow();
    if (!responsive) {
      LastActivationFailure = "WM_NULL confirmation failed";
      return false;
    }
    if (ForegroundAtConfirmation != h) {
      LastActivationFailure = LastActivationAccepted ? "activation accepted but target was not foreground" : "activation declined by Windows";
      return false;
    }
    LastActivationFailure = null;
    return true;
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
$script:ForegroundTracePath = $null

# Other agents may run their own Coach windows on this desktop. Only the process this script started is ever touched.
$script:Known = @()
$script:RepoMarker = ''   # 本次检出的目录；设置后只认命令行里带这个目录的客户端进程（别人的 Coach 窗口永远不是我的）
function Get-ClientPids([switch]$Mine) {
    @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" | Where-Object {
        ($_.CommandLine -like '*com.coach.desktop.MainKt*' -or $_.CommandLine -like '*com.coach.desktop.smoke.SmokeMain*') -and (-not $Mine -or -not $script:RepoMarker -or $_.CommandLine.Replace('\','/').Contains($script:RepoMarker.TrimEnd([char[]]'\/').Replace('\','/') + '/desktopApp/build/')) } | ForEach-Object { [int]$_.ProcessId })
}
function Mark-ExistingClients { $script:Known = Get-ClientPids }
function Find-CoachWindow {
    $script:Win = [IntPtr]::Zero
    $mine = @(Get-ClientPids -Mine | Where-Object { $script:Known -notcontains $_ })
    if (Has-BackgroundDriver) {
        if (-not (Test-Path -LiteralPath $env:COACH_SMOKE_CONTROL_FILE)) { return $false }
        $control = Get-Content -LiteralPath $env:COACH_SMOKE_CONTROL_FILE -Raw -Encoding UTF8 | ConvertFrom-Json
        $mine = @($mine | Where-Object { $_ -eq [int]$control.pid })
    }
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

# The development launcher owns its port/token. No foreground, cursor,
# clipboard or screen resource is used by this backend.
function Has-BackgroundDriver { $env:COACH_SMOKE_ENABLED -eq '1' }
function Invoke-BackgroundAction([string]$path, [string]$value = '', [string]$save = '') {
    $control = Get-Content -LiteralPath $env:COACH_SMOKE_CONTROL_FILE -Raw -Encoding UTF8 | ConvertFrom-Json
    $uri = [uri]$control.url
    if ($uri.Scheme -ne 'http' -or $uri.Host -ne '127.0.0.1' -or $control.pid -ne $script:AppPid -or -not $control.token) {
        throw 'WIN_DRIVE_TARGET: background control does not belong to this run'
    }
    $deadline = (Get-Date).AddSeconds(15)
    while ($true) {
        $request = [Net.HttpWebRequest]::Create($control.url + $path)
        $request.Proxy = $null
        $request.Timeout = 15000; $request.ReadWriteTimeout = 15000
        $request.Headers['Authorization'] = 'Bearer ' + $control.token
        if (-not $save) {
            $request.Method = 'POST'; $request.ContentType = 'text/plain; charset=utf-8'
            $bytes = [Text.Encoding]::UTF8.GetBytes($value)
            $request.ContentLength = $bytes.Length
            $stream = $request.GetRequestStream()
            try { $stream.Write($bytes, 0, $bytes.Length) } finally { $stream.Dispose() }
        }
        $response = $null
        try {
            $response = $request.GetResponse()
            if ($save) {
                $stream = $response.GetResponseStream()
                $file = [IO.File]::Create($save)
                try { $stream.CopyTo($file) } finally { $file.Dispose(); $stream.Dispose() }
            }
            return
        } catch [Net.WebException] {
            $failed = $_.Exception.Response
            if (-not $failed -or [int]$failed.StatusCode -ne 503 -or (Get-Date) -ge $deadline) { throw }
            $failed.Dispose()
        } finally { if ($response) { $response.Dispose() } }
        Start-Sleep -Milliseconds 200
    }
}
function Get-Rect { $r = New-Object WD+RECT; [WD]::GetWindowRect($script:Win, [ref]$r) | Out-Null; $r }

# Names who holds the foreground when we give up, so a timeout can be attributed
# to another run or program. Process name and window class only; never titles.
function Get-ForegroundSummary {
    $fg = [WD]::GetForegroundWindow(); $owner = 0
    [WD]::GetWindowThreadProcessId($fg, [ref]$owner) | Out-Null
    $cls = New-Object Text.StringBuilder 128; [WD]::GetClassName($fg, $cls, 128) | Out-Null
    $name = (Get-Process -Id $owner -ErrorAction SilentlyContinue).ProcessName
    $whose = if ($owner -eq $script:AppPid) { 'this run' } else { 'other' }
    "$name pid=$owner class=$($cls.ToString()) $whose"
}
# All drivers share this mutex, but hold it for one input/capture only.
# OCR, network waits, compilation and whole smoke runs stay outside.
function Invoke-ForegroundStep([scriptblock]$Action, [IntPtr]$Window = [IntPtr]::Zero) {
    if ($Window -eq [IntPtr]::Zero) { $Window = $script:Win }
    $mutex = New-Object Threading.Mutex($false, 'Global\Coach.WinDrive.Foreground')
    try {
        foreach ($attempt in 1..20) {
            $held = $false
            $declined = $false
            $trace = $null
            try {
                try { $held = $mutex.WaitOne(30000) } catch [Threading.AbandonedMutexException] { $held = $true }
                if (-not $held) { throw 'WIN_DRIVE_TIMEOUT: waiting for one foreground action' }
                $acquired = [DateTime]::UtcNow
                $owner = 0; [WD]::GetWindowThreadProcessId($Window, [ref]$owner) | Out-Null
                if (-not $script:AppPid -or $owner -ne $script:AppPid) { throw 'WIN_DRIVE_TARGET: window does not belong to this run' }
                $ready = [WD]::Activate($Window)
                $observed = [WD]::ForegroundAtConfirmation
                if ($script:ForegroundTracePath) {
                    $foregroundPid = 0; [WD]::GetWindowThreadProcessId($observed, [ref]$foregroundPid) | Out-Null
                    $trace = [ordered]@{ acquiredUtc=$acquired.ToString('o'); attempt=$attempt; targetHwnd=$Window.ToInt64()
                        targetPid=$owner; foregroundHwnd=$observed.ToInt64(); foregroundPid=$foregroundPid
                        accepted=[WD]::LastActivationAccepted; activationFailure=[WD]::LastActivationFailure; actionReturned=$false }
                }
                if (-not $ready) {
                    $declined = [WD]::LastActivationFailure -eq 'activation declined by Windows'
                    throw "WIN_DRIVE_FOCUS_LOST: $([WD]::LastActivationFailure)"
                }
                Start-Sleep -Milliseconds 150
                Assert-OurForeground -Expected $Window
                & $Action
                if ($trace) { $trace.actionReturned = $true }
                return
            } catch {
                if ($_.Exception.Message -notlike 'WIN_DRIVE_FOCUS_LOST*') { throw }
                $lastFocusLoss = $_.Exception.Message
                # Retry this action, never restart/reject the run for another window.
                # Release first so the other driver can finish its action.
                Write-Verbose "Foreground action retry $attempt/20"
            } finally {
                if ($held) { $mutex.ReleaseMutex() }
                if ($trace) {
                    $trace.releasedUtc = [DateTime]::UtcNow.ToString('o')
                    ($trace | ConvertTo-Json -Compress) | Add-Content -LiteralPath $script:ForegroundTracePath -Encoding UTF8
                }
            }
            if ($attempt -lt 20 -and $declined) {
                # Give the current owner time to finish. Wake early on a real
                # foreground change. Never hold the input mutex while waiting.
                $wait = [Diagnostics.Stopwatch]::StartNew()
                while ([WD]::GetForegroundWindow() -eq $observed -and $wait.ElapsedMilliseconds -lt 1000) {
                    Start-Sleep -Milliseconds 50
                }
            } elseif ($attempt -lt 20) {
                Start-Sleep -Milliseconds (Get-Random -Minimum 50 -Maximum 200)
            }
        }
        throw "WIN_DRIVE_TIMEOUT: could not regain our foreground for this action (foreground: $(Get-ForegroundSummary)). Last retry: $lastFocusLoss"
    } finally { $mutex.Dispose() }
}
function Save-Shot([string]$path, [IntPtr]$Window = [IntPtr]::Zero) {
    if (Has-BackgroundDriver) { Invoke-BackgroundAction -path "/shot" -save $path; return }
    if ($Window -eq [IntPtr]::Zero) { $Window = $script:Win }
    Invoke-ForegroundStep -Window $Window -Action {
        $r = New-Object WD+RECT; [WD]::GetWindowRect($Window, [ref]$r) | Out-Null; $w = $r.R - $r.L; $h = $r.B - $r.T
        $client = New-Object WD+RECT
        if ([WD]::IsIconic($Window) -or -not [WD]::GetClientRect($Window, [ref]$client) -or $client.R -le 0 -or $client.B -le 0) {
            throw 'WIN_DRIVE_FOCUS_LOST: window has no drawable client area; retry this capture'
        }
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
    $png = [IO.Path]::GetFullPath($png)
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
function Assert-OurForeground([IntPtr]$Expected = [IntPtr]::Zero) {
    $fg = [WD]::GetForegroundWindow(); $owner = 0
    [WD]::GetWindowThreadProcessId($fg, [ref]$owner) | Out-Null
    if (-not $script:AppPid -or $owner -ne $script:AppPid -or ($Expected -ne [IntPtr]::Zero -and $fg -ne $Expected)) {
        throw 'WIN_DRIVE_FOCUS_LOST: our client is no longer foreground'
    }
}
function Click-At([int]$x, [int]$y) {
    if (Has-BackgroundDriver) { Invoke-BackgroundAction "/click?x=$x&y=$y"; return }
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
    if (Has-BackgroundDriver) { Invoke-BackgroundAction "/drag?x1=$x1&y1=$y1&x2=$x2&y2=$y2"; return }
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
    if (Has-BackgroundDriver) { Invoke-BackgroundAction "/keys" $keys; return }
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
function Find-AppWindowsByTitle([string]$prefix) {
    $script:FoundAll = New-Object Collections.Generic.List[IntPtr]
    [WD]::EnumWindows({ param($x, $l)
        $p = 0; [WD]::GetWindowThreadProcessId($x, [ref]$p) | Out-Null
        if ($p -eq $script:AppPid -and [WD]::IsWindowVisible($x)) {
            $sb = New-Object Text.StringBuilder 256; [WD]::GetWindowText($x, $sb, 256) | Out-Null
            if ($sb.ToString().StartsWith($prefix)) { $script:FoundAll.Add($x) } }
        return $true }, [IntPtr]::Zero) | Out-Null
    return ,$script:FoundAll
}
function Find-AppWindowByTitle([string]$prefix) {
    $all = Find-AppWindowsByTitle $prefix
    if ($all.Count) { return $all[$all.Count - 1] }
    return [IntPtr]::Zero
}

# Dialog discovery and waiting do not hold the mutex. Filename filling is
# idempotent on retry; each Enter is followed by an unlocked wait.
# $script:ChooseFileWhy says why it returned $false.
function Choose-File([string]$dialogTitlePrefix, [string]$path, [int]$timeoutSec = 20) {
    if (Has-BackgroundDriver) { Invoke-BackgroundAction "/choose-file" $path; return $true }
    # The native open dialog rejects 'D:/x/y.pdf' with a modal error box that
    # carries the same title; give it the Windows form of the path.
    $path = [IO.Path]::GetFullPath($path)
    $script:ChooseFileWhy = $null
    $deadline = (Get-Date).AddSeconds($timeoutSec); $h = [IntPtr]::Zero
    while ($h -eq [IntPtr]::Zero -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500; $h = Find-AppWindowByTitle $dialogTitlePrefix }
    if ($h -eq [IntPtr]::Zero) { $script:ChooseFileWhy = '没有弹出选书对话框'; return $false }
    Invoke-ForegroundStep -Window $h -Action {
        [System.Windows.Forms.SendKeys]::SendWait('%n')
        Assert-OurForeground
        [System.Windows.Forms.SendKeys]::SendWait('^a')
        Assert-OurForeground
        [System.Windows.Forms.SendKeys]::SendWait((ConvertTo-SendKeysLiteral $path))
    }
    for ($i = 0; $i -lt 3; $i++) {
        $all = Find-AppWindowsByTitle $dialogTitlePrefix
        if (-not $all.Count) { return $true }
        if ($all.Count -gt 1 -or -not [WD]::IsWindowEnabled($all[0])) {
            # The dialog answered with its own error box. Close both now: a modal
            # box left behind keeps taking the foreground from every other run.
            $script:ChooseFileWhy = '选书对话框弹出了错误提示，没有接受这本书的路径'
            foreach ($w in @($all | Where-Object { [WD]::IsWindowEnabled($_) }) + @($all | Where-Object { -not [WD]::IsWindowEnabled($_) })) {
                try { Invoke-ForegroundStep -Window $w -Action { [System.Windows.Forms.SendKeys]::SendWait('{ESC}') } } catch { }
                Start-Sleep -Milliseconds 500
            }
            return $false
        }
        Invoke-ForegroundStep -Window $all[0] -Action {
            Assert-OurForeground; [System.Windows.Forms.SendKeys]::SendWait('{ENTER}')
        }
        Start-Sleep -Seconds 2
    }
    if ((Find-AppWindowByTitle $dialogTitlePrefix) -eq [IntPtr]::Zero) { return $true }
    $script:ChooseFileWhy = '选书对话框按回车后没有关闭'
    return $false
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
    if (Has-BackgroundDriver) { Invoke-BackgroundAction "/scroll?x=$x&y=$y&notches=$notches"; return }
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
    if (Has-BackgroundDriver) { Invoke-BackgroundAction "/text" $text; return }
    Invoke-ForegroundStep {
        [System.Windows.Forms.Clipboard]::SetText($text)
        Assert-OurForeground
        [System.Windows.Forms.SendKeys]::SendWait('^v')
        Start-Sleep -Milliseconds 150
    }
}

# 「就这段问一句」对话框里找可信选段所在的段落。同一段文字也在对话框后面的页面上，点那份会把对话框关掉，
# 所以命中必须落在对话框自己的上下边界之内。上边界用对话框里任一稳定文案：副标题改过一次
# （「选择要问的原文段落」→「点一段，再问一句」），所以副标题和标题都认。
function Find-PickerParagraph($lines, [string]$words) {
    $title = Find-Text $lines '就这段问一句'
    $sub = Find-Text $lines '选择要问的原文段落'
    if (-not $sub) { $sub = Find-Text $lines '再问一句' }
    $close = Find-Text $lines '回到阅读'
    $top = @($title, $sub | Where-Object { $_ })
    if (-not $top -or -not $close) { return $null }
    $yTop = ($top | ForEach-Object { $_.Y2 } | Measure-Object -Maximum).Maximum
    # 标题是左对齐的，用它的左边当 X 下限；只认到副标题时，副标题的匹配起点在行中间，放宽一段
    $xLeft = if ($title) { $title.X1 } else { $sub.X1 - 120 }
    foreach ($l in $lines) {
        if (($l.Text -replace '[^A-Za-z]', '') -notlike "*$words*") { continue }
        $candidate = Find-Text @($l) ($l.Text.Substring(0, [Math]::Min(6, $l.Text.Length)))
        if ($candidate -and $candidate.X1 -ge $xLeft -and $candidate.X2 -le $close.X2 -and
            $candidate.Y1 -gt $yTop -and $candidate.Y2 -lt $close.Y1) { return $candidate }
    }
    return $null
}
