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
  [DllImport("user32.dll")] public static extern bool EnumChildWindows(IntPtr h, EnumProc p, IntPtr l);
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
  [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr h);
  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool attach);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr hWndInsertAfter, int x, int y, int cx, int cy, uint flags);
  [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
  [DllImport("user32.dll")] public static extern IntPtr WindowFromPoint(POINT pt);
  [DllImport("user32.dll")] public static extern uint GetDpiForWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern IntPtr GetAncestor(IntPtr h, uint gaFlags);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr hdc, uint flags);
  [DllImport("dwmapi.dll")] public static extern int DwmGetWindowAttribute(IntPtr h, int attr, out int val, int size);
  public static bool IsCloaked(IntPtr h) { int v = 0; return DwmGetWindowAttribute(h, 14, out v, 4) == 0 && v != 0; }
  [StructLayout(LayoutKind.Sequential)] public struct LASTINPUTINFO { public uint cbSize; public uint dwTime; }
  [DllImport("user32.dll")] public static extern bool GetLastInputInfo(ref LASTINPUTINFO plii);
  [DllImport("kernel32.dll")] public static extern uint GetTickCount();
  public static long GetLastInputIdleMs() {
    LASTINPUTINFO lii = new LASTINPUTINFO();
    lii.cbSize = (uint)Marshal.SizeOf(typeof(LASTINPUTINFO));
    if (!GetLastInputInfo(ref lii)) return -1;
    return (long)unchecked(GetTickCount() - lii.dwTime);
  }
  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
  [DllImport("user32.dll", SetLastError = true)] public static extern IntPtr SendMessageTimeout(
    IntPtr h, uint message, UIntPtr wParam, IntPtr lParam, uint flags, uint timeoutMs, out UIntPtr result);
  [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern IntPtr SendMessageTimeout(
    IntPtr h, uint message, IntPtr wParam, StringBuilder lParam, uint flags, uint timeoutMs, out IntPtr result);
  [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern IntPtr SendMessageTimeout(
    IntPtr h, uint message, IntPtr wParam, string lParam, uint flags, uint timeoutMs, out IntPtr result);
  // 跨进程读控件正文。GetWindowText 对别的进程的窗口只回缓存标题：编辑框里敲进去的字不在缓存里，
  // 缓存只记最后一次 WM_SETTEXT。选书框打开时自己往「文件名」框写过文件夹名（11-27 730498c2 那轮
  // 是「friend-path-books」），之后敲的路径 GetWindowText 永远读不到，只会回那个旧名字。
  // 所以先问 WM_GETTEXT（系统替标准控件编组缓冲区，跨进程拿得到框里的真实内容），问不到再看缓存。
  public static string ReadControlText(IntPtr h) {
    StringBuilder sb = new StringBuilder(1024);
    IntPtr r;
    if (SendMessageTimeout(h, 0x000D, (IntPtr)1024, sb, 0x0003, 400, out r) != IntPtr.Zero && sb.Length > 0) return sb.ToString();
    sb = new StringBuilder(1024);
    GetWindowText(h, sb, 1024);
    return sb.ToString();
  }
  // 只认 WM_GETTEXT 的答案：核对「文件名」框时不能拿缓存标题冒充框里的字。
  public static string ReadEditText(IntPtr h) {
    StringBuilder sb = new StringBuilder(1024);
    IntPtr r;
    if (SendMessageTimeout(h, 0x000D, (IntPtr)1024, sb, 0x0003, 400, out r) == IntPtr.Zero) return null;
    return sb.ToString();
  }
  // WM_SETTEXT 直接写进指定控件，不经键盘，不会落到别的框或文件列表里。
  public static bool WriteControlText(IntPtr h, string text) {
    IntPtr r;
    return SendMessageTimeout(h, 0x000C, IntPtr.Zero, text, 0x0003, 1000, out r) != IntPtr.Zero && r != IntPtr.Zero;
  }
  [DllImport("user32.dll")] public static extern int GetDlgCtrlID(IntPtr h);
  [DllImport("user32.dll")] public static extern IntPtr GetParent(IntPtr h);
  [StructLayout(LayoutKind.Sequential)] public struct GUITHREADINFO {
    public int cbSize; public int flags; public IntPtr hwndActive, hwndFocus, hwndCapture, hwndMenuOwner, hwndMoveSize, hwndCaret;
    public int caretL, caretT, caretR, caretB;
  }
  [DllImport("user32.dll")] public static extern bool GetGUIThreadInfo(uint tid, ref GUITHREADINFO gi);
  // 窗口所在线程此刻键盘焦点落在哪个子控件上（跨进程可读）。
  public static IntPtr FocusedControl(IntPtr top) {
    uint pid; uint tid = GetWindowThreadProcessId(top, out pid);
    GUITHREADINFO gi = new GUITHREADINFO(); gi.cbSize = Marshal.SizeOf(typeof(GUITHREADINFO));
    return GetGUIThreadInfo(tid, ref gi) ? gi.hwndFocus : IntPtr.Zero;
  }
  public static string LastActivationFailure { get; private set; }
  public static bool LastActivationAccepted { get; private set; }
  public static bool LastActivationInjectedInput { get; private set; }
  public static IntPtr ForegroundAtConfirmation { get; private set; }
  private static uint[] ownedForegroundPids = new uint[0];
  public static void SetOwnedForegroundPids(uint[] pids) { ownedForegroundPids = pids ?? new uint[0]; }
  private static bool IsOwnedForegroundPid(uint pid) {
    if (pid == 0) return false;
    uint[] list = ownedForegroundPids;
    for (int i = 0; i < list.Length; i++) {
      if (list[i] == pid) return true;
    }
    return false;
  }
  private static IntPtr ReadForegroundSettled(bool accepted) {
    IntPtr fg = GetForegroundWindow();
    for (int i = 0; accepted && fg == IntPtr.Zero && i < 5; i++) {
      System.Threading.Thread.Sleep(10);
      fg = GetForegroundWindow();
    }
    return fg;
  }
  public static bool Activate(IntPtr h) {
    LastActivationInjectedInput = false;
    if (IsIconic(h)) ShowWindowAsync(h, 9); // SW_RESTORE
    LastActivationAccepted = SetForegroundWindow(h);
    UIntPtr ignored;
    // WM_NULL confirms the asynchronous activation has been processed. Never
    // wait indefinitely for a hung UI, and never bypass foreground ownership.
    bool responsive = SendMessageTimeout(h, 0, UIntPtr.Zero, IntPtr.Zero, 0x23, 500, out ignored) != IntPtr.Zero;
    ForegroundAtConfirmation = ReadForegroundSettled(LastActivationAccepted);
    if (!responsive) {
      LastActivationFailure = "WM_NULL confirmation failed";
      return false;
    }
    if (ForegroundAtConfirmation != h && ForegroundAtConfirmation != IntPtr.Zero) {
      // Only probe/attach when foreground ownership is confirmed to belong to an
      // owned Coach client window; never message or attach to unrelated windows.
      uint holderPid;
      uint holderTid = GetWindowThreadProcessId(ForegroundAtConfirmation, out holderPid);
      if (IsOwnedForegroundPid(holderPid) &&
          SendMessageTimeout(ForegroundAtConfirmation, 0, UIntPtr.Zero, IntPtr.Zero, 0x02, 200, out ignored) != IntPtr.Zero) {
        uint callerTid = GetCurrentThreadId();
        bool attached = holderTid != 0 && callerTid != holderTid && AttachThreadInput(callerTid, holderTid, true);
        try {
          BringWindowToTop(h);
          LastActivationAccepted = SetForegroundWindow(h);
        } finally {
          if (attached) AttachThreadInput(callerTid, holderTid, false);
        }
        responsive = SendMessageTimeout(h, 0, UIntPtr.Zero, IntPtr.Zero, 0x23, 500, out ignored) != IntPtr.Zero;
        ForegroundAtConfirmation = ReadForegroundSettled(LastActivationAccepted);
        if (!responsive) {
          LastActivationFailure = "WM_NULL confirmation failed";
          return false;
        }
      }
    }
    if (ForegroundAtConfirmation != h && IsWindowEnabled(h)) {
      // Windows 11 with ForegroundLockTimeout set rejects SetForegroundWindow from
      // headless background tasks even when the desktop is idle behind an unrelated
      // window (e.g. Edge). Raise only our own window transiently and click its
      // own caption only if WindowFromPoint confirms this HWND owns that pixel;
      // never message, attach to, move, or minimize any unrelated window.
      uint targetPid = 0;
      GetWindowThreadProcessId(h, out targetPid);
      RECT r;
      if (targetPid != 0 && GetWindowRect(h, out r)) {
        int w = r.R - r.L;
        int height = r.B - r.T;
        if (w > 40 && height > 40) {
          int offset = (w > 240) ? Math.Min(w / 3, w - 160) : (w / 2);
          int leftMargin = (w > 120) ? 48 : 16;
          int clickX = r.L + Math.Max(leftMargin, Math.Min(offset, w - 16));
          int clickY = r.T + 18;
          SetWindowPos(h, (IntPtr)(-1), 0, 0, 0, 0, 0x0043); // HWND_TOPMOST | NOMOVE | NOSIZE | SHOWWINDOW
          try {
            SendMessageTimeout(h, 0, UIntPtr.Zero, IntPtr.Zero, 0x23, 200, out ignored);
            POINT pt; pt.X = clickX; pt.Y = clickY;
            IntPtr under = WindowFromPoint(pt);
            IntPtr rootUnder = (under != IntPtr.Zero) ? GetAncestor(under, 2) : IntPtr.Zero; // GA_ROOT
            POINT actualPt = new POINT();
            if (rootUnder == h && SetCursorPos(clickX, clickY) && GetCursorPos(out actualPt)) {
              IntPtr actualUnder = WindowFromPoint(actualPt);
              IntPtr actualRoot = (actualUnder != IntPtr.Zero) ? GetAncestor(actualUnder, 2) : IntPtr.Zero; // GA_ROOT
              // Ask the target itself what the actual cursor point is (WM_NCHITTEST, screen
              // coordinates). Click only on a draggable caption (HTCAPTION = 2); close/min/max
              // buttons, borders, client area, a hung target or any other answer => zero input.
              UIntPtr hit = UIntPtr.Zero;
              bool onCaption = false;
              if (actualUnder == h && actualRoot == h) {
                IntPtr hitLParam = (IntPtr)((actualPt.Y << 16) | (actualPt.X & 0xFFFF));
                onCaption = SendMessageTimeout(h, 0x84, UIntPtr.Zero, hitLParam, 0x23, 200, out hit) != IntPtr.Zero
                  && hit.ToUInt64() == 2UL;
              }
              if (onCaption) {
                LastActivationInjectedInput = true;
                mouse_event(2, 0, 0, 0, IntPtr.Zero);
                System.Threading.Thread.Sleep(30);
                mouse_event(4, 0, 0, 0, IntPtr.Zero);
                responsive = SendMessageTimeout(h, 0, UIntPtr.Zero, IntPtr.Zero, 0x23, 500, out ignored) != IntPtr.Zero;
                ForegroundAtConfirmation = ReadForegroundSettled(true);
                if (ForegroundAtConfirmation == h) {
                  LastActivationAccepted = true;
                }
              }
            }
          } finally {
            SetWindowPos(h, (IntPtr)(-2), 0, 0, 0, 0, 0x0013); // HWND_NOTOPMOST | NOMOVE | NOSIZE | NOACTIVATE
          }
          if (!responsive) {
            LastActivationFailure = "WM_NULL confirmation failed";
            return false;
          }
        }
      }
    }
    if (ForegroundAtConfirmation != h) {
      LastActivationFailure = LastActivationAccepted ? "activation accepted but target was not foreground" : "activation declined by Windows";
      return false;
    }
    LastActivationFailure = null;
    return true;
  }
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern bool GetCursorPos(out POINT pt);
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
$script:AppStartTicks = 0L
$script:ForegroundTracePath = $null
# Real drivers all share this name. Offline regressions use a private name so they
# never contend with a supervisor's live run.
$script:ForegroundMutexName = 'Global\Coach.WinDrive.Foreground'

# Other agents may run their own Coach windows on this desktop. Only the process this script started is ever touched.
$script:Known = @()
$script:RepoMarker = ''   # 本次检出的目录；设置后只认命令行里带这个目录的客户端进程（别人的 Coach 窗口永远不是我的）
# -Runtime 起的客户端不在 <检出>/desktopApp/build 下，命令行里只有运行目录：起窗前把它也登记成归属锚点。
$script:OwnedClientRoot = ''
# Windows 路径不分大小写，\ 与 / 等价，重复分隔符视同一个。目录边界两端都要守：
# 前面必须是路径分隔符、引号、空白或参数分隔符（coach 不认 mycoach，L1 不认 L1x），后面接的是这个目录里的文件。
function ConvertTo-MarkerPath([string]$Text) { [regex]::Replace($Text.Replace('\', '/'), '/{2,}', '/') }
function Test-CommandLineUnder([string]$CommandLine, [string]$Root) {
    if (-not $CommandLine -or -not $Root) { return $false }
    $cmd = ConvertTo-MarkerPath $CommandLine
    $root = (ConvertTo-MarkerPath $Root).TrimEnd('/')
    if (-not $root) { return $false }
    $needle = $root + '/'
    $at = 0
    while (($i = $cmd.IndexOf($needle, $at, [StringComparison]::OrdinalIgnoreCase)) -ge 0) {
        if ($i -eq 0 -or '/"''=;,'.IndexOf($cmd[$i - 1]) -ge 0 -or [char]::IsWhiteSpace($cmd[$i - 1])) { return $true }
        $at = $i + 1
    }
    return $false
}
# 命令行里的构建输出是否落在这个检出目录下：必须正好是 /desktopApp/build/
# （不认相邻的 coach-other，也不认嵌套在子目录里的另一个检出）。
function Test-CheckoutCommandLine([string]$CommandLine, [string]$Repo) {
    if (-not $CommandLine -or -not $Repo) { return $false }
    $root = (ConvertTo-MarkerPath $Repo).TrimEnd('/')
    if (-not $root) { return $false }
    return (Test-CommandLineUnder $CommandLine ($root + '/desktopApp/build'))
}
function Get-ClientPids([switch]$Mine) {
    if ($Mine -and $script:InstalledClientPid) {
        if ((Get-TrackedPidState $script:InstalledClientPid $script:InstalledClientStartTicks) -ne 'alive') { return }
        # The installer launcher can delegate the window to a child with the same EXE.
        $script:InstalledClientPid
        Get-CimInstance Win32_Process -Filter "ParentProcessId=$($script:InstalledClientPid)" |
            Where-Object { $_.ExecutablePath -eq $script:InstalledClientPath } |
            ForEach-Object { [int]$_.ProcessId }
        return
    }
    @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" | Where-Object {
        ($_.CommandLine -like '*com.coach.desktop.MainKt*' -or $_.CommandLine -like '*com.coach.desktop.smoke.SmokeMain*') -and
            (-not $Mine -or -not $script:RepoMarker -or
                (Test-CheckoutCommandLine $_.CommandLine $script:RepoMarker) -or
                (Test-CommandLineUnder $_.CommandLine $script:OwnedClientRoot)) } | ForEach-Object { [int]$_.ProcessId })
}
function Mark-ExistingClients { $script:Known = Get-ClientPids }
function Test-CoachWindowTitle([string]$Title) {
    if ([string]::IsNullOrEmpty($Title)) { return $false }
    return ($Title -match '^LearningCoach(?:\s|$)')
}
function Find-CoachWindow {
    $script:Win = [IntPtr]::Zero
    $mine = @(Get-ClientPids -Mine | Where-Object { $script:Known -notcontains $_ })
    if (Has-BackgroundDriver) {
        if (-not (Test-Path -LiteralPath $env:COACH_SMOKE_CONTROL_FILE)) { return $false }
        $control = Get-Content -LiteralPath $env:COACH_SMOKE_CONTROL_FILE -Raw -Encoding UTF8 | ConvertFrom-Json
        $mine = @($mine | Where-Object { $_ -eq [int]$control.pid })
    }
    if (-not $mine) { return $false }
    [WD]::EnumWindows({ param($x, $l)
        $p = 0; [WD]::GetWindowThreadProcessId($x, [ref]$p) | Out-Null
        if ($p -in $mine -and [WD]::IsWindowVisible($x)) {
            $sb = New-Object Text.StringBuilder 256; [WD]::GetWindowText($x, $sb, 256) | Out-Null
            $title = $sb.ToString()
            if ($title -eq 'Coach' -or (Test-CoachWindowTitle $title)) {
                $script:Win = $x
                $script:AppPid = $p
                return $false
            }
        }
        return $true }, [IntPtr]::Zero) | Out-Null
    if ($script:Win -ne [IntPtr]::Zero) {
        $proc = Get-Process -Id $script:AppPid -ErrorAction SilentlyContinue
        $script:AppStartTicks = Get-TrackedProcessStartTicks $proc
    }
    return ($script:Win -ne [IntPtr]::Zero)
}
function Get-TrackedProcessStartTicks($Process) {
    if (-not $Process) { return 0L }
    try {
        $st = $Process.StartTime
        if ($st) { return ([datetime]$st).ToUniversalTime().Ticks }
    } catch { }
    return 0L
}
function Get-TrackedPidState([int]$ProcessId, [long]$StartTimeTicks = 0L) {
    if ($ProcessId -le 0) { return 'exited' }
    $proc = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if (-not $proc) { return 'exited' }
    if ($StartTimeTicks -le 0L) { return 'unverified' }
    $observedTicks = Get-TrackedProcessStartTicks $proc
    if ($observedTicks -le 0L) {
        # 查到 PID 之后、读启动时间之前进程正好退出，.NET 读启动时间会报「已退出」。
        # 重新按 PID 查一次：查不到就是已退出；还在才算身份不可核验。
        if (-not (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) { return 'exited' }
        return 'unverified'
    }
    if ($observedTicks -ne $StartTimeTicks) { return 'exited' }
    return 'alive'
}
function Test-TrackedPidExited([int]$ProcessId, [long]$StartTimeTicks = 0L) {
    return (Get-TrackedPidState $ProcessId $StartTimeTicks) -eq 'exited'
}
function Stop-TrackedPid(
    [int]$ProcessId,
    [long]$StartTimeTicks = 0L,
    [int]$WaitMs = 5000,
    [switch]$Tree,
    [string]$StdoutPath = '',
    [string]$StderrPath = ''
) {
    $state = Get-TrackedPidState $ProcessId $StartTimeTicks
    if ($state -eq 'exited') { return }
    if ($state -eq 'unverified') {
        throw "CLEANUP_UNVERIFIED: pid $ProcessId is running but identity cannot be verified (recordedStartTicks=$StartTimeTicks)"
    }
    if ($Tree) {
        $taskkillArgs = @{
            FilePath = 'taskkill.exe'
            ArgumentList = "/PID $ProcessId /T /F"
            WindowStyle = 'Hidden'
            PassThru = $true
        }
        if ($StdoutPath) { $taskkillArgs.RedirectStandardOutput = $StdoutPath }
        if ($StderrPath) { $taskkillArgs.RedirectStandardError = $StderrPath }
        $killer = Start-Process @taskkillArgs
        if (-not $killer.WaitForExit([Math]::Max(5000, $WaitMs))) {
            Stop-Process -Id $killer.Id -Force -ErrorAction SilentlyContinue
            throw "Owned launcher cleanup timed out for pid $ProcessId"
        }
    } else {
        Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
    }
    # 停止请求已发，下面只读不停：进程收尾期间启动时间可能短暂读不到，
    # 一直复查到截止时间；到点仍读不到才报 CLEANUP_UNVERIFIED，不再补发停止。
    $deadline = [DateTime]::UtcNow.AddMilliseconds($WaitMs)
    while ($true) {
        $state = Get-TrackedPidState $ProcessId $StartTimeTicks
        if ($state -eq 'exited') { return }
        if ([DateTime]::UtcNow -ge $deadline) {
            if ($state -eq 'unverified') {
                throw "CLEANUP_UNVERIFIED: pid $ProcessId identity could not be verified after stop (recordedStartTicks=$StartTimeTicks)"
            }
            throw "CLEANUP_FAILED: owned pid $ProcessId did not exit"
        }
        Start-Sleep -Milliseconds 100
    }
}
function Stop-MyClient {
    if ($script:AppPid) {
        Stop-TrackedPid -ProcessId $script:AppPid -StartTimeTicks $script:AppStartTicks -WaitMs 5000
        $script:AppPid = 0
        $script:AppStartTicks = 0L
    }
}

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
function Get-ForegroundOwner([scriptblock]$Probe = $null, [Nullable[long]]$Hwnd = $null) {
    $raw = if ($Probe) {
        if ($null -ne $Hwnd) { & $Probe ([long]$Hwnd) } else { & $Probe }
    } else {
        $fg = if ($null -ne $Hwnd) { [IntPtr][long]$Hwnd } else { [WD]::GetForegroundWindow() }
        $owner = 0
        if ($fg -ne [IntPtr]::Zero) { [WD]::GetWindowThreadProcessId($fg, [ref]$owner) | Out-Null }
        $cls = New-Object Text.StringBuilder 128
        if ($fg -ne [IntPtr]::Zero) { [WD]::GetClassName($fg, $cls, 128) | Out-Null }
        $name = if ($owner -gt 0) { (Get-Process -Id $owner -ErrorAction SilentlyContinue).ProcessName } else { '' }
        [pscustomobject]@{
            Hwnd = $fg.ToInt64()
            Pid = [int]$owner
            Process = if ($name) { [string]$name } else { '' }
            Class = $cls.ToString()
        }
    }
    $fgPid = if ($raw -and $null -ne $raw.Pid) { [int]$raw.Pid } else { 0 }
    $procName = if ($raw -and $raw.Process) { [string]$raw.Process } else { '' }
    $clsName = if ($raw -and $raw.Class) { [string]$raw.Class } else { '' }
    $hwndVal = if ($raw -and $null -ne $raw.Hwnd) { [long]$raw.Hwnd } else { 0L }
    $isOwn = [bool]($script:AppPid -and $fgPid -eq $script:AppPid)
    $whose = if ($isOwn) { 'this run' } else { 'other' }
    [pscustomobject]@{
        Hwnd = $hwndVal
        Pid = $fgPid
        Process = $procName
        Class = $clsName
        IsOwnCoach = $isOwn
        Summary = "$procName pid=$fgPid class=$clsName $whose"
    }
}
function Get-ForegroundSummary { (Get-ForegroundOwner).Summary }
function New-FocusLossException([string]$Message, $Owner) {
    $err = [System.InvalidOperationException]::new($Message)
    if ($Owner) {
        $err.Data['ForegroundOwner'] = $Owner
        $err.Data['ForegroundHwnd'] = [long]$Owner.Hwnd
        $err.Data['ForegroundPid'] = [int]$Owner.Pid
        $err.Data['ForegroundProcess'] = if ($Owner.Process) { [string]$Owner.Process } else { 'unknown' }
        $err.Data['ForegroundClass'] = [string]$Owner.Class
        $err.Data['ForegroundSummary'] = [string]$Owner.Summary
    }
    return $err
}
function Resolve-ForegroundRetryTimeout([string]$LastFocusLoss = '', $ForegroundOwner = $null, [scriptblock]$ForegroundProbe = $null) {
    $fgOwner = if ($ForegroundOwner) { $ForegroundOwner } else { Get-ForegroundOwner -Probe $ForegroundProbe }
    if ($fgOwner.Pid -gt 0 -and -not $fgOwner.IsOwnCoach) {
        $procLabel = if ($fgOwner.Process) { $fgOwner.Process } else { 'unknown' }
        $err = [System.InvalidOperationException]::new("WIN_DRIVE_FOREGROUND_CONTENTION: could not regain our foreground for this action (foreground: $($fgOwner.Summary)). Last retry: $LastFocusLoss")
        $err.Data['ForegroundHwnd'] = [long]$fgOwner.Hwnd
        $err.Data['ForegroundPid'] = [int]$fgOwner.Pid
        $err.Data['ForegroundProcess'] = $procLabel
        $err.Data['ForegroundClass'] = [string]$fgOwner.Class
        $err.Data['ForegroundSummary'] = [string]$fgOwner.Summary
        $err.Data['LastFocusLoss'] = $LastFocusLoss
        return $err
    }
    return [System.InvalidOperationException]::new("WIN_DRIVE_TIMEOUT: could not regain our foreground for this action (foreground: $($fgOwner.Summary)). Last retry: $LastFocusLoss")
}
function Find-ForeignForegroundWindow([scriptblock]$Probe = $null) {
    $shellClasses = @(
        'Progman', 'WorkerW', 'Shell_TrayWnd', 'Shell_SecondaryTrayWnd',
        'TaskListThumbnailWnd', 'MultitaskingViewFrame', 'ForegroundStaging'
    )
    $info = if ($Probe) {
        & $Probe
    } else {
        $fg = [WD]::GetForegroundWindow()
        if ($fg -eq [IntPtr]::Zero) { $null }
        else {
            $owner = 0; [WD]::GetWindowThreadProcessId($fg, [ref]$owner) | Out-Null
            $cls = New-Object Text.StringBuilder 128; [WD]::GetClassName($fg, $cls, 128) | Out-Null
            $proc = (Get-Process -Id $owner -ErrorAction SilentlyContinue).ProcessName
            [pscustomobject]@{
                Hwnd = $fg.ToInt64()
                Pid = [int]$owner
                Visible = [WD]::IsWindowVisible($fg)
                Cloaked = [WD]::IsCloaked($fg)
                Class = $cls.ToString()
                Process = $proc
            }
        }
    }
    if (-not $info -or [long]$info.Hwnd -eq 0L) { return $null }
    if (-not $info.Visible -or $info.Cloaked) { return $null }
    if ([int]$info.Pid -eq $PID) { return $null }
    if ($shellClasses -contains [string]$info.Class) { return $null }
    $procLabel = if ($info.Process) { [string]$info.Process } else { 'unknown' }
    $clsLabel = if ($info.Class) { [string]$info.Class } else { 'unknown' }
    [pscustomobject]@{
        Hwnd = [long]$info.Hwnd
        Pid = [int]$info.Pid
        Process = $procLabel
        Class = $clsLabel
        Summary = "$procLabel pid=$([int]$info.Pid) class=$clsLabel"
    }
}
$script:ExternalWindowRegistryDir = 'D:\agent\state\coach-smoke-external-windows'
$script:DriverInputStampPath = 'D:\agent\state\coach-smoke-driver-input.json'
function Record-DriverInputTimestamp(
    [datetime]$UtcTime = [DateTime]::UtcNow,
    [string]$StampFile = $script:DriverInputStampPath
) {
    if (-not $StampFile) { return }
    $utc = $UtcTime.ToUniversalTime()
    $dir = Split-Path -Parent $StampFile
    if ($dir) { New-Item -ItemType Directory -Force $dir | Out-Null }
    $tmp = "$StampFile.$PID.tmp"
    @{
        utc = $utc.ToString('o')
        utcTicks = $utc.Ticks
        pid = $PID
    } | ConvertTo-Json -Compress | Set-Content -LiteralPath $tmp -Encoding UTF8
    Move-Item -LiteralPath $tmp -Destination $StampFile -Force
}
function Get-DriverInputTimestampUtc([string]$StampFile = $script:DriverInputStampPath) {
    if (-not $StampFile -or -not (Test-Path -LiteralPath $StampFile)) { return $null }
    try {
        $data = Get-Content -LiteralPath $StampFile -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($data.PSObject.Properties.Name -contains 'utcTicks' -and [long]$data.utcTicks -gt 0L) {
            return [datetime]::new([long]$data.utcTicks, [DateTimeKind]::Utc)
        }
        if ($data.PSObject.Properties.Name -contains 'utc' -and $data.utc) {
            return ([datetime]::Parse([string]$data.utc, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::RoundtripKind)).ToUniversalTime()
        }
    } catch { }
    return $null
}
function Register-ExternalWindowPid(
    [int]$ProcessId,
    [long]$StartTimeTicks = 0L,
    [string]$Kind = 'external',
    [string]$RegistryDir = $script:ExternalWindowRegistryDir
) {
    if ($ProcessId -le 0) { throw "Register-ExternalWindowPid requires positive ProcessId (got $ProcessId)" }
    if ($StartTimeTicks -le 0L) {
        $proc = Get-Process -Id $ProcessId -ErrorAction Stop
        $StartTimeTicks = Get-TrackedProcessStartTicks $proc
    }
    if ($StartTimeTicks -le 0L) { throw "Register-ExternalWindowPid could not read StartTime for pid $ProcessId" }
    New-Item -ItemType Directory -Force $RegistryDir | Out-Null
    $ownerTicks = Get-TrackedProcessStartTicks (Get-Process -Id $PID -ErrorAction SilentlyContinue)
    $path = Join-Path $RegistryDir ("$ProcessId-$StartTimeTicks.json")
    $tmp = "$path.tmp"
    @{
        pid = $ProcessId
        startTicks = $StartTimeTicks
        kind = $Kind
        ownerPid = $PID
        ownerStartTicks = $ownerTicks
        registeredUtc = [DateTime]::UtcNow.ToString('o')
    } | ConvertTo-Json -Compress | Set-Content -LiteralPath $tmp -Encoding UTF8
    Move-Item -LiteralPath $tmp -Destination $path -Force
    return $path
}
function Unregister-ExternalWindowPid(
    [int]$ProcessId,
    [long]$StartTimeTicks = 0L,
    [string]$RegistryDir = $script:ExternalWindowRegistryDir
) {
    if (-not (Test-Path -LiteralPath $RegistryDir)) { return }
    $pattern = if ($StartTimeTicks -gt 0L) { "$ProcessId-$StartTimeTicks.json" } else { "$ProcessId-*.json" }
    foreach ($f in @(Get-ChildItem -LiteralPath $RegistryDir -Filter $pattern -ErrorAction SilentlyContinue)) {
        Remove-Item -LiteralPath $f.FullName -Force -ErrorAction SilentlyContinue
    }
}
function Get-RegisteredExternalWindows([string]$RegistryDir = $script:ExternalWindowRegistryDir) {
    if (-not (Test-Path -LiteralPath $RegistryDir)) { return @() }
    $active = New-Object System.Collections.ArrayList
    foreach ($entry in @(Get-ChildItem -LiteralPath $RegistryDir -Filter '*.json' -ErrorAction SilentlyContinue)) {
        $rec = $null
        try { $rec = Get-Content -LiteralPath $entry.FullName -Raw -Encoding UTF8 | ConvertFrom-Json } catch { continue }
        if (-not $rec -or -not $rec.pid) { continue }
        $pidVal = [int]$rec.pid
        $ticksVal = if ($rec.startTicks) { [long]$rec.startTicks } else { 0L }
        $state = Get-TrackedPidState $pidVal $ticksVal
        if ($state -eq 'exited') {
            Remove-Item -LiteralPath $entry.FullName -Force -ErrorAction SilentlyContinue
            continue
        }
        $ownerAlive = if ($rec.ownerPid) {
            (Get-TrackedPidState ([int]$rec.ownerPid) $(if ($rec.ownerStartTicks) { [long]$rec.ownerStartTicks } else { 0L })) -ne 'exited'
        } else { $false }
        [void]$active.Add([pscustomobject]@{
            Pid = $pidVal
            StartTicks = $ticksVal
            Kind = if ($rec.kind) { [string]$rec.kind } else { 'external' }
            State = $state
            OwnerPid = if ($rec.ownerPid) { [int]$rec.ownerPid } else { 0 }
            OwnerAlive = $ownerAlive
            Summary = "registered:$($rec.kind) pid=$pidVal state=$state ownerAlive=$ownerAlive"
        })
    }
    return @($active)
}
$script:ApolloLogPath = 'E:\Apollo\config\sunshine.log'
function Get-ApolloLogSession(
    [string]$LogPath = $script:ApolloLogPath,
    $ProcessStartUtc = $null
) {
    if (-not $LogPath -or -not (Test-Path -LiteralPath $LogPath) -or $null -eq $ProcessStartUtc) {
        return [pscustomobject]@{ Readable = $false; Active = $false; ActiveClients = 0 }
    }
    $activeClients = 0
    try {
        $lines = @(Get-Content -LiteralPath $LogPath -Encoding UTF8 -ErrorAction Stop)
    } catch {
        return [pscustomobject]@{ Readable = $false; Active = $false; ActiveClients = 0 }
    }
    $startUtc = ([datetime]$ProcessStartUtc).ToUniversalTime()
    foreach ($line in $lines) {
        # Apollo 0.4.6 (commit 0cd32aba): src/logging.cpp:104 timestamp format,
        # src/stream.cpp:603 "CLIENT CONNECTED", src/stream.cpp:606 "CLIENT DISCONNECTED".
        $cleanLine = $line.TrimStart([char]0xFEFF)
        if ($cleanLine -match '^\[(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}(?:\.\d+)?)\]:\s*\w+:\s*(.+)$') {
            $ts = [datetime]::MinValue
            if (-not [datetime]::TryParseExact($Matches[1], [string[]]@('yyyy-MM-dd HH:mm:ss.fff', 'yyyy-MM-dd HH:mm:ss'), [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::AssumeLocal, [ref]$ts)) {
                continue
            }
            if ($ts.ToUniversalTime() -lt $startUtc) { continue }
            $msg = $Matches[2].Trim()
            if ($msg -eq 'CLIENT CONNECTED') { $activeClients++ }
            elseif ($msg -eq 'CLIENT DISCONNECTED' -and $activeClients -gt 0) { $activeClients-- }
        }
    }
    return [pscustomobject]@{ Readable = $true; Active = ($activeClients -gt 0); ActiveClients = $activeClients }
}
function Get-ActiveStreamingSession(
    [scriptblock]$Probe = $null,
    [scriptblock]$HostProbe = $null,
    [scriptblock]$PortProbe = $null,
    [string]$LogPath = $script:ApolloLogPath
) {
    if ($Probe) { return (& $Probe) }
    $hosts = if ($HostProbe) {
        @(& $HostProbe)
    } else {
        @(Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.ProcessName -match '^(sunshine|apollo)$' })
    }
    if ($hosts.Count -eq 0) { return $null }
    $udpOk = $true; $tcpOk = $true
    $allUdp = @(); $allTcp = @()
    if (-not $PortProbe) {
        try {
            $allUdp = @(Get-NetUDPEndpoint -ErrorAction Stop)
        } catch {
            $udpOk = $false
        }
        try {
            $allTcp = @(Get-NetTCPConnection -ErrorAction Stop)
        } catch {
            $tcpOk = $false
        }
    }
    $indeterminate = $null
    foreach ($h in $hosts) {
        $hPid = [int]$h.Id
        $hName = if ($h.ProcessName) { [string]$h.ProcessName } else { 'sunshine' }
        $hostUdpOk = $udpOk
        $hostTcpOk = $tcpOk
        $ports = @()
        if ($PortProbe) {
            try {
                $pd = & $PortProbe $hPid
                $udpPorts = if ($pd -and $pd.Udp) { @($pd.Udp) } else { @() }
                $tcpPorts = if ($pd -and $pd.Tcp) { @($pd.Tcp) } else { @() }
                $ports = @(@($udpPorts) + @($tcpPorts) | Sort-Object -Unique)
            } catch {
                $hostUdpOk = $false
                $hostTcpOk = $false
            }
        } else {
            $udpPorts = if ($hostUdpOk) {
                @($allUdp | Where-Object { $_ -and $_.OwningProcess -eq $hPid -and $_.LocalPort -ge 47998 -and $_.LocalPort -le 48020 } | ForEach-Object LocalPort)
            } else { @() }
            $tcpPorts = if ($hostTcpOk) {
                @($allTcp | Where-Object {
                    $_ -and $_.OwningProcess -eq $hPid -and $_.State -eq 'Established' -and (
                        $_.LocalPort -in 47984, 47989, 48010 -or ($_.LocalPort -ge 47998 -and $_.LocalPort -le 48020)
                    )
                } | ForEach-Object LocalPort)
            } else { @() }
            $ports = @(@($udpPorts) + @($tcpPorts) | Sort-Object -Unique)
        }
        if ($ports.Count -gt 0) {
            return [pscustomobject]@{
                Tool = 'Apollo/Sunshine'
                Source = 'ports'
                Pid = $hPid
                Ports = $ports
                Summary = "$hName pid=$hPid streaming ports=$($ports -join ',')"
            }
        }
        $startTicks = Get-TrackedProcessStartTicks $h
        $startUtc = if ($startTicks -gt 0L) { [datetime]::new($startTicks, [DateTimeKind]::Utc) } else { $null }
        $logState = Get-ApolloLogSession -LogPath $LogPath -ProcessStartUtc $startUtc
        if ($logState.Readable -and $logState.Active) {
            return [pscustomobject]@{
                Tool = 'Apollo/Sunshine'
                Source = 'log'
                Pid = $hPid
                Ports = @()
                Summary = "$hName pid=$hPid streaming log=CLIENT CONNECTED active=$($logState.ActiveClients)"
            }
        }
        if ((-not $hostUdpOk -or -not $hostTcpOk) -and -not $logState.Readable -and -not $indeterminate) {
            $indeterminate = [pscustomobject]@{
                Tool = 'Apollo/Sunshine'
                Source = 'indeterminate'
                Pid = $hPid
                Ports = @()
                Summary = "$hName pid=$hPid streaming state unreadable (ports and log unavailable)"
            }
        }
    }
    return $indeterminate
}
function Get-DesktopIdleEvidence(
    [scriptblock]$IdleProbe = $null,
    [string]$DriverInputFile = $script:DriverInputStampPath,
    [scriptblock]$NowProbe = $null
) {
    $nowUtc = if ($NowProbe) { ([datetime](& $NowProbe)).ToUniversalTime() } else { [DateTime]::UtcNow }
    $idleSec = if ($IdleProbe) {
        [double](& $IdleProbe)
    } else {
        $ms = [WD]::GetLastInputIdleMs()
        if ($ms -lt 0) { -1.0 } else { [double]$ms / 1000.0 }
    }
    $matched = $false
    $driverUtc = Get-DriverInputTimestampUtc -StampFile $DriverInputFile
    if ($idleSec -ge 0 -and $driverUtc) {
        $lastOsInputUtc = $nowUtc.AddSeconds(-$idleSec)
        if ([Math]::Abs(($lastOsInputUtc - $driverUtc).TotalSeconds) -lt 2.0) { $matched = $true }
    }
    [pscustomobject]@{
        IdleSeconds = if ($idleSec -lt 0) { -1.0 } else { [math]::Round($idleSec, 1) }
        DriverInputMatched = $matched
        DriverInputUtc = if ($driverUtc) { $driverUtc.ToString('o') } else { $null }
    }
}
function Find-ActiveDesktopUser(
    [int]$MinIdleSeconds = 600,
    [scriptblock]$IdleProbe = $null,
    [scriptblock]$StreamProbe = $null,
    [string]$DriverInputFile = $script:DriverInputStampPath,
    [scriptblock]$NowProbe = $null,
    [scriptblock]$HostProbe = $null,
    [scriptblock]$PortProbe = $null,
    [string]$ApolloLogPath = $script:ApolloLogPath
) {
    # 李政 11-25 决定：Apollo/Moonlight 串流不再拦截真窗口测试，仅在 Test-DesktopPreflight.Streaming 中留证。
    return $null
}
function Find-DirtyDesktopWindow(
    [string]$RegistryDir = $script:ExternalWindowRegistryDir,
    [scriptblock]$CoachWindowProbe = $null
) {
    $dirty = New-Object System.Collections.ArrayList
    $coachWins = if ($CoachWindowProbe) {
        @(& $CoachWindowProbe)
    } else {
        $clientPids = [Collections.Generic.HashSet[int]]::new()
        foreach ($cpid in @(Get-ClientPids)) {
            if ($cpid -gt 0 -and $cpid -ne $script:AppPid) { [void]$clientPids.Add([int]$cpid) }
        }
        # Clients launched from this same checkout share its build output and data
        # scope; only those are leftovers of this run's scope.
        $samePids = [Collections.Generic.HashSet[int]]::new()
        if ($script:RepoMarker) {
            foreach ($cpid in @(Get-ClientPids -Mine)) { [void]$samePids.Add([int]$cpid) }
        }
        $found = New-Object System.Collections.ArrayList
        $seenPids = [Collections.Generic.HashSet[int]]::new()
        [WD]::EnumWindows({ param($h, $l)
            if ([WD]::IsWindowVisible($h) -and -not [WD]::IsCloaked($h)) {
                $p = 0; [WD]::GetWindowThreadProcessId($h, [ref]$p) | Out-Null
                if ($p -gt 0 -and $p -ne $script:AppPid -and -not $seenPids.Contains([int]$p)) {
                    $pn = (Get-Process -Id $p -ErrorAction SilentlyContinue).ProcessName
                    if ($clientPids.Contains([int]$p) -or $pn -eq 'LearningCoach') {
                        [void]$seenPids.Add([int]$p)
                        $same = $samePids.Contains([int]$p)
                        [void]$found.Add([pscustomobject]@{
                            Kind = 'coach-window'
                            Pid = [int]$p
                            Process = if ($pn) { $pn } else { 'java' }
                            SameCheckout = $same
                            Summary = "coach-window $(if ($pn) { $pn } else { 'java' }) pid=$p$(if ($same) { ' same-checkout' })"
                        })
                    }
                }
            }
            return $true
        }, [IntPtr]::Zero) | Out-Null
        @($found)
    }
    foreach ($cw in $coachWins) {
        if ($cw) { [void]$dirty.Add($cw) }
    }
    foreach ($rw in @(Get-RegisteredExternalWindows -RegistryDir $RegistryDir)) {
        [void]$dirty.Add($rw)
    }
    return @($dirty)
}
function Test-DesktopPreflight(
    [int]$MinIdleSeconds = 600,
    [string]$RegistryDir = $script:ExternalWindowRegistryDir,
    [scriptblock]$IdleProbe = $null,
    [scriptblock]$StreamProbe = $null,
    [scriptblock]$CoachWindowProbe = $null,
    [scriptblock]$ForegroundProbe = $null,
    [string]$DriverInputFile = $script:DriverInputStampPath,
    [scriptblock]$NowProbe = $null,
    [scriptblock]$HostProbe = $null,
    [scriptblock]$PortProbe = $null,
    [string]$ApolloLogPath = $script:ApolloLogPath
) {
    # A Coach window from another checkout is a separate client with its own scope:
    # evidence only. Per-action ownership checks and the short foreground lock keep
    # input on our own window. Apollo/Moonlight streaming is also evidence only.
    # Registered test leftovers and clients from this same checkout still reject the whole round.
    $otherCoachWindows = New-Object System.Collections.ArrayList
    $dirtyList = New-Object System.Collections.ArrayList
    foreach ($w in @(Find-DirtyDesktopWindow -RegistryDir $RegistryDir -CoachWindowProbe $CoachWindowProbe)) {
        if (-not $w) { continue }
        $same = ($w.PSObject.Properties.Name -contains 'SameCheckout') -and [bool]$w.SameCheckout
        if ($w.Kind -eq 'coach-window' -and -not $same) {
            [void]$otherCoachWindows.Add($w)
        } else {
            [void]$dirtyList.Add($w)
        }
    }
    $dirtyWindows = @($dirtyList)
    $otherCoach = @($otherCoachWindows)
    $idle = Get-DesktopIdleEvidence -IdleProbe $IdleProbe -DriverInputFile $DriverInputFile -NowProbe $NowProbe
    $stream = Get-ActiveStreamingSession -Probe $StreamProbe -HostProbe $HostProbe -PortProbe $PortProbe -LogPath $ApolloLogPath
    $streamSummary = if ($stream) { [string]$stream.Summary } else { '' }
    $activeUser = Find-ActiveDesktopUser -MinIdleSeconds $MinIdleSeconds -IdleProbe { $idle.IdleSeconds } -StreamProbe { $stream } -DriverInputFile $DriverInputFile -NowProbe $NowProbe -HostProbe $HostProbe -PortProbe $PortProbe -ApolloLogPath $ApolloLogPath
    $fg = Find-ForeignForegroundWindow -Probe $ForegroundProbe
    if ($dirtyWindows.Count -gt 0) {
        $desc = ($dirtyWindows | ForEach-Object Summary) -join '; '
        return [pscustomobject]@{
            Clean = $false
            Reason = "桌面存在测试遗留或本检出的残留 Coach 窗口（$desc）"
            DirtyWindows = $dirtyWindows
            OtherCoachWindows = $otherCoach
            ActiveUser = $activeUser
            Streaming = $stream
            StreamingSummary = $streamSummary
            IdleSeconds = $idle.IdleSeconds
            DriverInputMatched = $idle.DriverInputMatched
            Foreground = $fg
        }
    }
    if ($activeUser) {
        return [pscustomobject]@{
            Clean = $false
            Reason = $activeUser.Reason
            DirtyWindows = @()
            OtherCoachWindows = $otherCoach
            ActiveUser = $activeUser
            Streaming = $stream
            StreamingSummary = $streamSummary
            IdleSeconds = $idle.IdleSeconds
            DriverInputMatched = $idle.DriverInputMatched
            Foreground = $fg
        }
    }
    return [pscustomobject]@{
        Clean = $true
        Reason = ''
        DirtyWindows = @()
        OtherCoachWindows = $otherCoach
        ActiveUser = $null
        Streaming = $stream
        StreamingSummary = $streamSummary
        IdleSeconds = $idle.IdleSeconds
        DriverInputMatched = $idle.DriverInputMatched
        Foreground = $fg
    }
}
# 前台短锁对本机所有已登录会话开放：SSH 起的驱动和桌面会话里的驱动属于不同登录会话，
# 默认权限只给创建者那个登录会话，另一边打开同名锁会被 Windows 拒绝访问。锁已存在时沿用它原有的权限。
function New-ForegroundMutex([string]$Name = $script:ForegroundMutexName) {
    $sec = New-Object Security.AccessControl.MutexSecurity
    $users = New-Object Security.Principal.SecurityIdentifier([Security.Principal.WellKnownSidType]::AuthenticatedUserSid, $null)
    $sec.AddAccessRule((New-Object Security.AccessControl.MutexAccessRule($users, [Security.AccessControl.MutexRights]'Synchronize, Modify', 'Allow')))
    $created = $false
    [Threading.Mutex]::new($false, $Name, [ref]$created, $sec)
}
# All drivers share this mutex, but hold it for one input/capture only.
# OCR, network waits, compilation and whole smoke runs stay outside.
function Invoke-ForegroundStep(
    [scriptblock]$Action,
    [IntPtr]$Window = [IntPtr]::Zero,
    [switch]$InjectsInput,
    [scriptblock]$TargetOwnerProbe = $null,
    [scriptblock]$ActivateProbe = $null,
    [scriptblock]$ForegroundProbe = $null
) {
    if ($Window -eq [IntPtr]::Zero) { $Window = $script:Win }
    $mutex = New-ForegroundMutex
    $lastFocusLoss = ''
    $lastForegroundOwner = $null
    try {
        foreach ($attempt in 1..20) {
            $held = $false
            $declined = $false
            $trace = $null
            $ownedPids = if ($script:InstalledClientPid) {
                [uint32[]]@($script:AppPid)
            } else {
                [uint32[]]@(@(Get-ClientPids) + @($script:AppPid) | Where-Object { $_ -gt 0 } | Select-Object -Unique)
            }
            try {
                try { $held = $mutex.WaitOne(30000) } catch [Threading.AbandonedMutexException] { $held = $true }
                if (-not $held) { throw 'WIN_DRIVE_TIMEOUT: waiting for one foreground action' }
                $acquired = [DateTime]::UtcNow
                $owner = 0
                if ($TargetOwnerProbe) {
                    $owner = [int](& $TargetOwnerProbe $Window)
                } else {
                    [WD]::GetWindowThreadProcessId($Window, [ref]$owner) | Out-Null
                }
                if (-not $script:AppPid -or $owner -ne $script:AppPid) { throw 'WIN_DRIVE_TARGET: window does not belong to this run' }
                [WD]::SetOwnedForegroundPids($ownedPids)
                if ($ActivateProbe) {
                    $probeRes = & $ActivateProbe $Window $attempt
                    $ready = [bool]$probeRes.Ready
                    $observed = [IntPtr][long]$probeRes.ObservedHwnd
                    $activationFailure = [string]$probeRes.Failure
                    $accepted = if ($probeRes.PSObject.Properties.Name -contains 'Accepted') { [bool]$probeRes.Accepted } else { $false }
                } else {
                    $ready = [WD]::Activate($Window)
                    if ([WD]::LastActivationInjectedInput) {
                        try { Record-DriverInputTimestamp } catch { }
                    }
                    $observed = [WD]::ForegroundAtConfirmation
                    $activationFailure = [WD]::LastActivationFailure
                    $accepted = [WD]::LastActivationAccepted
                }
                $observedOwner = if (-not $ready) {
                    Get-ForegroundOwner -Hwnd $observed.ToInt64() -Probe $ForegroundProbe
                } else { $null }
                if ($script:ForegroundTracePath) {
                    $foregroundPid = 0
                    if ($observedOwner) {
                        $foregroundPid = $observedOwner.Pid
                    } elseif ($ActivateProbe) {
                        $foregroundPid = $owner
                    } else {
                        [WD]::GetWindowThreadProcessId($observed, [ref]$foregroundPid) | Out-Null
                    }
                    $trace = [ordered]@{ acquiredUtc=$acquired.ToString('o'); attempt=$attempt; targetHwnd=$Window.ToInt64()
                        targetPid=$owner; foregroundHwnd=$observed.ToInt64(); foregroundPid=$foregroundPid
                        accepted=$accepted; activationFailure=$activationFailure; actionReturned=$false }
                }
                if (-not $ready) {
                    $declined = $activationFailure -eq 'activation declined by Windows'
                    throw (New-FocusLossException -Message "WIN_DRIVE_FOCUS_LOST: $activationFailure" -Owner $observedOwner)
                }
                Start-Sleep -Milliseconds 150
                Assert-OurForeground -Expected $Window -ForegroundProbe $ForegroundProbe
                try {
                    & $Action
                } finally {
                    if ($InjectsInput) {
                        try { Record-DriverInputTimestamp } catch { }
                    }
                }
                if ($trace) { $trace.actionReturned = $true }
                return
            } catch {
                if ($_.Exception.Message -notlike 'WIN_DRIVE_FOCUS_LOST*') { throw }
                $lastFocusLoss = $_.Exception.Message
                # Record foreground ownership while still holding the mutex so a
                # post-unlock focus swap cannot flip exit-code attribution.
                $lastForegroundOwner = if ($_.Exception.Data -and $_.Exception.Data.Contains('ForegroundOwner')) {
                    $_.Exception.Data['ForegroundOwner']
                } else {
                    Get-ForegroundOwner -Probe $ForegroundProbe
                }
                # Retry this action, never restart/reject the run for another window.
                # Release first so the other driver can finish its action.
                Write-Verbose "Foreground action retry $attempt/20"
            } finally {
                # 先记释放时刻再交锁：交出去以后别的驱动可能立刻拿到锁并写下自己的
                # acquiredUtc，比这里晚记的 releasedUtc 还早，并行时就会假报 1 毫秒重叠。
                if ($trace) { $trace.releasedUtc = [DateTime]::UtcNow.ToString('o') }
                [WD]::SetOwnedForegroundPids([uint32[]]@())
                if ($held) { $mutex.ReleaseMutex() }
                if ($trace) { ($trace | ConvertTo-Json -Compress) | Add-Content -LiteralPath $script:ForegroundTracePath -Encoding UTF8 }
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
        throw (Resolve-ForegroundRetryTimeout -LastFocusLoss $lastFocusLoss -ForegroundOwner $lastForegroundOwner -ForegroundProbe $ForegroundProbe)
    } finally { $mutex.Dispose() }
}
function Save-Shot([string]$path, [IntPtr]$Window = [IntPtr]::Zero) {
    if (Has-BackgroundDriver) { Invoke-BackgroundAction -path "/shot" -save $path; return }
    if ($Window -eq [IntPtr]::Zero) { $Window = $script:Win }
    $owner = 0; [WD]::GetWindowThreadProcessId($Window, [ref]$owner) | Out-Null
    if (-not $script:AppPid -or $owner -ne $script:AppPid) { throw 'WIN_DRIVE_TARGET: window does not belong to this run' }
    if (-not [WD]::IsIconic($Window)) {
        $r = New-Object WD+RECT; $client = New-Object WD+RECT
        if ([WD]::GetWindowRect($Window, [ref]$r) -and [WD]::GetClientRect($Window, [ref]$client) -and $client.R -gt 0 -and $client.B -gt 0) {
            $w = $r.R - $r.L; $h = $r.B - $r.T
            if ($w -gt 0 -and $h -gt 0) {
                $bmp = New-Object System.Drawing.Bitmap $w, $h
                try {
                    $captured = $false
                    $g = [System.Drawing.Graphics]::FromImage($bmp)
                    try {
                        $hdc = $g.GetHdc()
                        try { $captured = [WD]::PrintWindow($Window, $hdc, 2) } finally { $g.ReleaseHdc($hdc) }
                    } finally { $g.Dispose() }
                    if ($captured) {
                        $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
                        return
                    }
                } finally { $bmp.Dispose() }
            }
        }
    }
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
function Get-SafeOcrScale([int]$Width, [int]$Height, [int]$DesiredScale) {
    $maxDim = [int][Windows.Media.Ocr.OcrEngine]::MaxImageDimension
    $maxSide = [Math]::Max(1, [Math]::Max($Width, $Height))
    $limit = [Math]::Max(1, [int][Math]::Floor([double]$maxDim / $maxSide))
    return [Math]::Max(1, [Math]::Min($DesiredScale, $limit))
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
    try {
        $a = @(Read-Bitmap $png); $b = @(Read-Bitmap $invPath)
        $a + $b
    } finally {
        Remove-Item $invPath -ErrorAction SilentlyContinue
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


# A notification or a non-Java window is not permission to type into it.
# The caller holds the short mutex and retries only the interrupted action.
function Assert-OurForeground([IntPtr]$Expected = [IntPtr]::Zero, [scriptblock]$ForegroundProbe = $null) {
    $fgOwner = Get-ForegroundOwner -Probe $ForegroundProbe
    if (-not $script:AppPid -or $fgOwner.Pid -ne $script:AppPid -or ($Expected -ne [IntPtr]::Zero -and $fgOwner.Hwnd -ne $Expected.ToInt64())) {
        throw (New-FocusLossException -Message 'WIN_DRIVE_FOCUS_LOST: our client is no longer foreground' -Owner $fgOwner)
    }
}
# 与 #552 的滚轮同因：光标刚被 SetCursorPos 挪过去、移动还没被处理，按下就跟着到，
# Compose 按它记住的旧落点分派这一下（或者干脆丢掉）；抬起紧跟交锁，另一路在同一毫秒
# SetForegroundWindow 抢前台，这一下也会被整个吞掉（11-21 03:26 四路并行：第 5 步入口三下
# 里 L1/L2/L3 各丢一下，L4 独跑同样三下一次过）。所以挪指针后等一帧再断言、再按下，
# 抬起后也留一小段让这一下走完再交锁。
# 抬起后只等、不再断言：此时点击已经发生，断言失败会让 Invoke-ForegroundStep 重放整段，
# 复选框这类按钮会被点成两下。
# $BeforePress 是「按下之前再看一眼」的探针：指针已经停到落点上、前台也断言过了，这时候再拍一帧判页面，
# 返回假就不按。坐标是上一帧算出来的，界面可能在这几百毫秒里切走；原文页「返回学习」和做题学左上角
# 「Coach目录返回书架」正好是同一个坐标，按下就等于把整条 Lane 送回书架（11-21 06:57 L2）。
# 返回值表示这一下到底按没按（探针否掉时是 $false），调用方据此决定要不要另找路回去。
function Click-At(
    [int]$x,
    [int]$y,
    [scriptblock]$TargetOwnerProbe = $null,
    [scriptblock]$ActivateProbe = $null,
    [scriptblock]$ForegroundProbe = $null,
    [scriptblock]$RectProbe = $null,
    [scriptblock]$MouseProbe = $null,
    [scriptblock]$BeforePress = $null
) {
    if (Has-BackgroundDriver) { Invoke-BackgroundAction "/click?x=$x&y=$y"; return $true }
    $state = @{ Pressed = $false }
    Invoke-ForegroundStep -InjectsInput -TargetOwnerProbe $TargetOwnerProbe -ActivateProbe $ActivateProbe -ForegroundProbe $ForegroundProbe -Action {
        $r = if ($RectProbe) { & $RectProbe } else { Get-Rect }
        if ($MouseProbe) { & $MouseProbe 'move' ($r.L + $x) ($r.T + $y) | Out-Null }
        else { [WD]::SetCursorPos($r.L + $x, $r.T + $y) | Out-Null }
        Start-Sleep -Milliseconds 120
        Assert-OurForeground -ForegroundProbe $ForegroundProbe
        if ($BeforePress) {
            $go = $false
            try { $go = [bool](& $BeforePress) } catch { $go = $false }
            if (-not $go) { return }
        }
        if ($MouseProbe) { & $MouseProbe 'down' ($r.L + $x) ($r.T + $y) | Out-Null }
        else { [WD]::mouse_event(2, 0, 0, 0, [IntPtr]::Zero) }
        try { Start-Sleep -Milliseconds 60 } finally {
            if ($MouseProbe) { & $MouseProbe 'up' ($r.L + $x) ($r.T + $y) | Out-Null }
            else { [WD]::mouse_event(4, 0, 0, 0, [IntPtr]::Zero) }
        }
        Start-Sleep -Milliseconds 100
        $state.Pressed = $true
    }
    return $state.Pressed
}
# A drag is one action: release the button even if foreground is interrupted.
# Focus lost before the press is retried like any other action. Focus lost while
# the button is held stops the remaining moves, releases once and fails the step
# without replaying the gesture (the target may already have seen part of it).
function Drag-Between([int]$x1, [int]$y1, [int]$x2, [int]$y2) {
    if (Has-BackgroundDriver) { Invoke-BackgroundAction "/drag?x1=$x1&y1=$y1&x2=$x2&y2=$y2"; return }
    Invoke-ForegroundStep -InjectsInput -Action {
        $r = Get-Rect
        [WD]::SetCursorPos($r.L + $x1, $r.T + $y1) | Out-Null
        Assert-OurForeground
        [WD]::mouse_event(2, 0, 0, 0, [IntPtr]::Zero)
        $interrupted = $null
        try {
            $legs = @(
                @{ X = $r.L + [int](($x1 + $x2) / 2); Y = $r.T + [int](($y1 + $y2) / 2); Wait = 120 },
                @{ X = $r.L + $x2; Y = $r.T + $y2; Wait = 80 }
            )
            foreach ($leg in $legs) {
                Start-Sleep -Milliseconds $leg.Wait
                try { Assert-OurForeground } catch { $interrupted = $_.Exception; break }
                [WD]::SetCursorPos($leg.X, $leg.Y) | Out-Null
            }
            if (-not $interrupted) { Start-Sleep -Milliseconds 120 }
        } finally { [WD]::mouse_event(4, 0, 0, 0, [IntPtr]::Zero) }
        if ($interrupted) {
            $owner = if ($interrupted.Data -and $interrupted.Data.Contains('ForegroundOwner')) { $interrupted.Data['ForegroundOwner'] } else { $null }
            # Not WIN_DRIVE_FOCUS_LOST, so Invoke-ForegroundStep does not replay the drag.
            $why = "WIN_DRIVE_DRAG_INTERRUPTED: focus lost while the button was held; drag released once, not replayed ($($interrupted.Message))"
            $err = Resolve-ForegroundRetryTimeout -LastFocusLoss $why -ForegroundOwner $owner
            if ($err.Message -like 'WIN_DRIVE_FOREGROUND_CONTENTION*') { throw $err }
            throw $why
        }
    }
}
function Send-Keys([string]$keys) {
    if (Has-BackgroundDriver) { Invoke-BackgroundAction "/keys" $keys; return }
    Invoke-ForegroundStep -InjectsInput -Action {
        Assert-OurForeground
        [System.Windows.Forms.SendKeys]::SendWait($keys)
    }
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

# 文件选择框里「文件名」是个真 Edit（有时套在 ComboBox 里）。只有把它的文字读回来，
# 才知道路径真的落进去了；读不到就按回车，是 11-21 02:17 L4 那种「按了三次回车框还没关」。
# 读要用 [WD]::ReadControlText（跨进程 WM_GETTEXT）：对话框在 Coach 进程里，驱动在
# powershell 进程里，GetWindowText 跨进程只回缓存标题（空串或对话框自己写进去的旧文件夹名），看不到敲进去的字。
function Get-DialogEditTexts([IntPtr]$h) {
    $script:DialogEdits = New-Object Collections.Generic.List[string]
    [WD]::EnumChildWindows($h, { param($c, $l)
        $cb = New-Object Text.StringBuilder 64; [WD]::GetClassName($c, $cb, 64) | Out-Null
        $cls = $cb.ToString()
        if (($cls -eq 'Edit' -or $cls -eq 'ComboBox' -or $cls -eq 'ComboBoxEx32') -and [WD]::IsWindowVisible($c)) {
            $script:DialogEdits.Add([WD]::ReadControlText($c)) }
        return $true }, [IntPtr]::Zero) | Out-Null
    return ,$script:DialogEdits
}

# 选书框的「文件名」框：对话框给它的控件号固定是 1148（cmb13），编辑框本身或它外面套的
# ComboBox / ComboBoxEx32 带这个号。找不到时返回 0，调用方退回按键加读全部框的旧做法。
function Find-FileNameEdit([IntPtr]$h) {
    $script:FileNameEdit = [IntPtr]::Zero
    [WD]::EnumChildWindows($h, { param($c, $l)
        $cb = New-Object Text.StringBuilder 64; [WD]::GetClassName($c, $cb, 64) | Out-Null
        if ($cb.ToString() -eq 'Edit' -and [WD]::IsWindowVisible($c)) {
            $x = $c
            for ($up = 0; $up -lt 3 -and $x -ne [IntPtr]::Zero -and $x -ne $h; $up++) {
                if ([WD]::GetDlgCtrlID($x) -eq 1148) { $script:FileNameEdit = $c; return $false }
                $x = [WD]::GetParent($x)
            }
        }
        return $true }, [IntPtr]::Zero) | Out-Null
    return $script:FileNameEdit
}
function Get-ControlLabel([IntPtr]$c) {
    if ($c -eq [IntPtr]::Zero) { return '无' }
    $cb = New-Object Text.StringBuilder 64; [WD]::GetClassName($c, $cb, 64) | Out-Null
    "$($cb.ToString())#$([WD]::GetDlgCtrlID($c))"
}

# Dialog discovery and waiting do not hold the mutex. Filename filling is
# idempotent on retry; each Enter is followed by an unlocked wait.
# $script:ChooseFileWhy says why it returned $false.
function Choose-File([string]$dialogTitlePrefix, [string]$path, [int]$timeoutSec = 20) {
    if (Has-BackgroundDriver) { Invoke-BackgroundAction "/choose-file" $path; return $true }
    # The native open dialog rejects 'D:/x/y.pdf' with a modal error box that
    # carries the same title; give it the Windows form of the path.
    $path = [IO.Path]::GetFullPath($path)
    $leaf = [IO.Path]::GetFileName($path)
    # 带空格的名字会被框自己加上引号，所以只认「含这本书的文件名」，不要求整串相等。
    $leafRx = [regex]::Escape($leaf)
    $script:ChooseFileWhy = $null
    $deadline = (Get-Date).AddSeconds($timeoutSec); $h = [IntPtr]::Zero
    while ($h -eq [IntPtr]::Zero -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500; $h = Find-AppWindowByTitle $dialogTitlePrefix }
    if ($h -eq [IntPtr]::Zero) { $script:ChooseFileWhy = '没有弹出选书对话框'; return $false }
    # 四路并行时四个进程同时弹这个框，谁抢到前台就把键送给谁。所以填完必须读回「文件名」框，
    # 拿到这本书的路径才按回车；框读得到、内容却不对就不回车，把框关掉，免得它扣着前台卡死其余三路。
    # 11-27 730498c2：三次都读回「friend-path-books」。框刚打开时自己往「文件名」框写过文件夹名，
    # 旧的读法先看缓存标题，敲进去的路径读不到。现在先认准「文件名」框（控件号 1148），用 WM_SETTEXT
    # 把路径直接写进这个框，再只用 WM_GETTEXT 从同一个框读回；回车前再确认键盘焦点就在这个框上。
    $box = [IntPtr]::Zero
    $boxDeadline = (Get-Date).AddSeconds(5)
    while ($box -eq [IntPtr]::Zero -and (Get-Date) -lt $boxDeadline) {
        $box = Find-FileNameEdit $h
        if ($box -eq [IntPtr]::Zero) { Start-Sleep -Milliseconds 300 }
    }
    $filled = $false; $readable = $false; $seen = ''
    for ($try = 0; $try -lt 3 -and -not $filled; $try++) {
        if ($box -ne [IntPtr]::Zero) {
            $script:ChooseFileWrote = $false
            Invoke-ForegroundStep -Window $h -InjectsInput -Action {
                [System.Windows.Forms.SendKeys]::SendWait('%n')
                Assert-OurForeground
                $script:ChooseFileWrote = [WD]::WriteControlText($box, $path)
            }
            Start-Sleep -Milliseconds 300
            $t = [WD]::ReadEditText($box)
            if ($null -ne $t -and $t -ne '') {
                $readable = $true
                if ($t -match $leafRx) { $filled = $true } elseif (-not $seen) { $seen = $t }
            }
            if (-not $filled -and -not $script:ChooseFileWrote -and -not $seen) { $seen = '写入被拒' }
            continue
        }
        Invoke-ForegroundStep -Window $h -InjectsInput -Action {
            [System.Windows.Forms.SendKeys]::SendWait('%n')
            Assert-OurForeground
            [System.Windows.Forms.SendKeys]::SendWait('^a')
            Assert-OurForeground
            [System.Windows.Forms.SendKeys]::SendWait((ConvertTo-SendKeysLiteral $path))
        }
        # 长路径会带出自动补全列表，框里的字要等它落定再读。
        Start-Sleep -Milliseconds 900
        foreach ($t in (Get-DialogEditTexts $h)) {
            if ($t -eq '') { continue }
            $readable = $true
            if ($t -match $leafRx) { $filled = $true; break }
            if (-not $seen) { $seen = $t }
        }
    }
    # 认准了「文件名」框却读不回这本书：不回车。没找到这个框、一个框都读不出字（布局里没有 Edit/ComboBox）
    # 时退回修复前的做法：直接按回车，不能因此判失败。
    if (($box -ne [IntPtr]::Zero -or $readable) -and -not $filled) {
        if ($seen.Length -gt 60) { $seen = $seen.Substring(0, 60) + '…' }
        $script:ChooseFileWhy = "选书对话框的「文件名」框没拿到 $leaf（框里读到「$seen」），没有按回车"
        try { Invoke-ForegroundStep -Window $h -InjectsInput -Action { [System.Windows.Forms.SendKeys]::SendWait('{ESC}') } } catch { }
        return $false
    }
    for ($i = 0; $i -lt 3; $i++) {
        $all = Find-AppWindowsByTitle $dialogTitlePrefix
        if (-not $all.Count) { return $true }
        if ($all.Count -gt 1 -or -not [WD]::IsWindowEnabled($all[0])) {
            # The dialog answered with its own error box. Close both now: a modal
            # box left behind keeps taking the foreground from every other run.
            $script:ChooseFileWhy = '选书对话框弹出了错误提示，没有接受这本书的路径'
            foreach ($w in @($all | Where-Object { [WD]::IsWindowEnabled($_) }) + @($all | Where-Object { -not [WD]::IsWindowEnabled($_) })) {
                try { Invoke-ForegroundStep -Window $w -InjectsInput -Action { [System.Windows.Forms.SendKeys]::SendWait('{ESC}') } } catch { }
                Start-Sleep -Milliseconds 500
            }
            return $false
        }
        if ($box -ne [IntPtr]::Zero) {
            # 回车只交给装着这本书的「文件名」框：再读一次框里的字，焦点不在这个框上就不按。
            $script:ChooseFileEnterWhy = $null
            Invoke-ForegroundStep -Window $all[0] -InjectsInput -Action {
                [System.Windows.Forms.SendKeys]::SendWait('%n')
                Assert-OurForeground
                $now = [WD]::ReadEditText($box)
                $focus = [WD]::FocusedControl($all[0])
                if ($null -eq $now -or $now -notmatch $leafRx) {
                    $script:ChooseFileEnterWhy = "回车前「文件名」框里已不是 $leaf"
                } elseif ($focus -ne $box) {
                    $script:ChooseFileEnterWhy = "回车前焦点在 $(Get-ControlLabel $focus)，不在「文件名」框"
                } else {
                    [System.Windows.Forms.SendKeys]::SendWait('{ENTER}')
                }
            }
            if ($script:ChooseFileEnterWhy) {
                $script:ChooseFileWhy = "$($script:ChooseFileEnterWhy)，没有按回车"
                try { Invoke-ForegroundStep -Window $all[0] -InjectsInput -Action { [System.Windows.Forms.SendKeys]::SendWait('{ESC}') } } catch { }
                return $false
            }
        } else {
            Invoke-ForegroundStep -Window $all[0] -InjectsInput -Action {
                Assert-OurForeground; [System.Windows.Forms.SendKeys]::SendWait('{ENTER}')
            }
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
# Compose 按自己记住的指针位置决定一格滚轮交给哪个可滚区。指针刚被 SetCursorPos 挪过去、
# 滚轮同一批消息跟着到，那边还没处理移动，格子就落空（11-21 02:21 四路并行：同一点同方向
# 连发 11 次，学习页一次没动）。所以先落指针、等它被处理，再一格一格送滚轮。
function Scroll-At([int]$x, [int]$y, [int]$notches) {
    if (Has-BackgroundDriver) { Invoke-BackgroundAction "/scroll?x=$x&y=$y&notches=$notches"; return }
    $step = if ($notches -ge 0) { 1 } else { -1 }
    Invoke-ForegroundStep -InjectsInput -Action {
        $r = Get-Rect
        [WD]::SetCursorPos($r.L + $x, $r.T + $y) | Out-Null
        Start-Sleep -Milliseconds 150
        Assert-OurForeground
        $delta = [BitConverter]::ToUInt32([BitConverter]::GetBytes([int](120 * $step)), 0)
        for ($i = 0; $i -lt [Math]::Abs($notches); $i++) {
            [WD]::mouse_event(0x800, 0, 0, $delta, [IntPtr]::Zero)
            Start-Sleep -Milliseconds 60
        }
    }
}
# Clipboard write and paste are one action. Keep it stable until consumed.
function Paste-Text([string]$text) {
    if (Has-BackgroundDriver) { Invoke-BackgroundAction "/text" $text; return }
    Invoke-ForegroundStep -InjectsInput -Action {
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

# 撤掉本轮测试书架里所有书的「发送原文」同意，并确认真的没有了。删不掉（被占用、没权限）就抛错：
# 同意还在，客户端进入整节就会自动发请求，调用方必须停。
function Clear-ExplainConsent([string]$library) {
    $dir = Join-Path $library 'explain-consent'
    if (-not (Test-Path -LiteralPath $dir)) { return }
    Get-ChildItem -LiteralPath $dir -Force | Remove-Item -Force -Recurse -ErrorAction Stop
    $left = @(Get-ChildItem -LiteralPath $dir -Force)
    if ($left.Count -gt 0) { throw "同意文件还剩 $($left.Count) 个" }
}
