# 安装包验收：装 MSI → 起真窗口 → 截图（标题栏、任务栏、开始菜单）→ 卸载 → 留日志和 result.json。
#
# 在 winbox 上必须经 tools/smoke/Run-Interactive.ps1 在登录会话里跑：智能体自己在 session 0，
# 看不到也截不到窗口。CI 的 windows-2022 运行机直接跑。
#   powershell -File tools\smoke\msi-install.ps1 -Msi <x.msi> -OutDir <目录>
#
# 只装 MSI、只起它装的 exe；书架和客户端目录指到 $OutDir 下，不碰使用者自己的书和配对身份。
param(
    [Parameter(Mandatory)][string]$Msi,
    [Parameter(Mandatory)][string]$OutDir,
    [switch]$KeepInstalled,
    [int]$StartTimeoutSec = 180
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Win-Drive.ps1')
New-Item -ItemType Directory -Force $OutDir | Out-Null
$msiPath = (Resolve-Path $Msi).Path
$results = New-Object System.Collections.ArrayList
$location = $null
$script:TargetPids = @()
function Note([string]$m) { "$(Get-Date -Format 'HH:mm:ss') $m" | Tee-Object -FilePath (Join-Path $OutDir 'install.log') -Append }
function Pass([string]$name, [string]$what) { [void]$results.Add([pscustomobject]@{ Step = $name; Ok = $true; Why = $what }); Note "$name 通过：$what" }
# 失败也要留下 result.json：调用方靠它判断，而不是靠退出码去猜哪一步断了
function Save-Result {
    $product = Get-LearningCoachProducts
    $productInfo = $null
    if ($product) {
        $productInfo = [pscustomobject]@{ name = $product[0].DisplayName; version = $product[0].DisplayVersion; publisher = $product[0].Publisher }
    }
    [pscustomobject]@{
        msi = $msiPath
        msiBytes = (Get-Item $msiPath).Length
        installedTo = $location
        product = $productInfo
        steps = $results
        passed = @($results | Where-Object { $_.Ok }).Count
        total = $results.Count
    } | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8 (Join-Path $OutDir 'result.json')
    "$(@($results | Where-Object { $_.Ok }).Count)/$($results.Count)（截图与日志：$OutDir）"
}
function Fail([string]$name, [string]$why) { [void]$results.Add([pscustomobject]@{ Step = $name; Ok = $false; Why = $why }); Note "$name 失败：$why"; Stop-OwnClients; Undo-Install; Save-Result; exit 1 }
# 意料之外的终止错误也要落盘并退出，否则外层 Run-Interactive 只能等到超时
trap { Note "中断：$_"; Stop-OwnClients; Undo-Install; Save-Result; exit 1 }
function Stop-OwnClients { foreach ($own in @($script:TargetPids)) { Stop-Process -Id $own -Force -ErrorAction SilentlyContinue } }
# 半路失败也要把机器还原：已经装上的 LearningCoach 卸掉，卸载日志留档，下次从干净状态开始。
function Undo-Install {
    $installed = Get-LearningCoachProducts
    if (-not $installed) { return }
    if ($KeepInstalled) { Note 'KeepInstalled：失败也不卸载'; return }
    try {
        $code = $installed[0].PSChildName
        $log = Join-Path $OutDir 'msiexec-uninstall.log'
        $u = Start-Process msiexec.exe -ArgumentList @('/x', $code, '/qn', '/norestart', '/l*v', "`"$log`"") -Wait -PassThru
        $left = if (Get-LearningCoachProducts) { '卸载列表里还有 LearningCoach' } else { '没有残留' }
        Note "失败后清理：msiexec /x 退出码 $($u.ExitCode)，$left"
        "残留：$left（失败后清理）" | Set-Content -Encoding utf8 (Join-Path $OutDir 'uninstall-leftovers.txt')
    } catch {
        Note "失败后清理没做完：$_"
    }
}
# 窗口截图走共享驱动 Win-Drive 的 Save-Shot：它只在自己抢到前台、且窗口属于本次进程时才截，
# 抢不到就报错，不拿别人的窗口冒充。SendKeys 不认 Win 键，开始菜单用真的 Win 键。
if (-not ('CoachWin' -as [type])) {
    Add-Type @"
using System; using System.Runtime.InteropServices;
public class CoachWin {
  [StructLayout(LayoutKind.Sequential)]
  public struct POINT { public int X, Y; }
  [DllImport("user32.dll")] static extern IntPtr WindowFromPoint(POINT point);
  [DllImport("user32.dll", EntryPoint="SendMessageTimeoutW")]
  static extern IntPtr HitTest(IntPtr h, uint msg, UIntPtr wp, IntPtr lp, uint flags, uint timeout, out IntPtr result);
  public static bool IsExposedCaption(IntPtr h, int x, int y) {
    if (WindowFromPoint(new POINT { X = x, Y = y }) != h) return false;
    IntPtr part;
    return HitTest(h, 0x84 /* WM_NCHITTEST */, UIntPtr.Zero, (IntPtr)((y << 16) | (x & 65535)),
                   2, 300, out part) != IntPtr.Zero && part.ToInt64() == 2 /* HTCAPTION */;
  }
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, IntPtr extra);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
  [DllImport("user32.dll")] public static extern int GetWindowTextLength(IntPtr h);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, IntPtr pid);
  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint attach, uint attachTo, bool on);
  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
  // 别的窗口（哪怕是同名的旧实例）卡住时 GetWindowText 会把我们吊死；带超时地问，问不到就当没标题。
  [DllImport("user32.dll", CharSet = CharSet.Unicode)]
  public static extern IntPtr SendMessageTimeout(IntPtr h, uint msg, IntPtr wParam, System.Text.StringBuilder lParam, uint flags, uint timeout, out IntPtr result);
  public static string Text(IntPtr h) {
    var sb = new System.Text.StringBuilder(512);
    IntPtr result;
    SendMessageTimeout(h, 0x000D /* WM_GETTEXT */, (IntPtr)sb.Capacity, sb, 0x0002 /* SMTO_ABORTIFHUNG */, 300, out result);
    return sb.ToString();
  }
  [DllImport("user32.dll")] public static extern uint GetDpiForWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr hdc, uint flags);
  public static readonly IntPtr TOPMOST = new IntPtr(-1);
  public static readonly IntPtr NOTOPMOST = new IntPtr(-2);
}
"@
}
function Open-StartMenu {
    [CoachWin]::keybd_event(0x5B, 0, 0, [IntPtr]::Zero)
    Start-Sleep -Milliseconds 80
    [CoachWin]::keybd_event(0x5B, 0, 2, [IntPtr]::Zero)
}

# On hosted ARM, Start may already be open over the app. Dismiss it with Esc
# only when it owns the foreground. Then use the installed window's exposed
# title bar to activate it as a user would. Save-Shot independently verifies focus.
function Select-InstalledWindow {
    $foreground = [WD]::GetForegroundWindow()
    $owner = 0
    [WD]::GetWindowThreadProcessId($foreground, [ref]$owner) | Out-Null
    $shell = Get-Process -Id $owner -ErrorAction SilentlyContinue
    if ($shell.ProcessName -in 'SearchHost','StartMenuExperienceHost' -and [WD]::GetForegroundWindow() -eq $foreground) {
        [System.Windows.Forms.SendKeys]::SendWait('{ESC}')
        Start-Sleep -Milliseconds 400
    }
    if ([WD]::GetForegroundWindow() -eq $script:Win) { return }
    $owner = 0
    [WD]::GetWindowThreadProcessId($script:Win, [ref]$owner) | Out-Null
    if ($owner -ne $script:WinPid -or $script:TargetPids -notcontains $owner) { throw 'Installed window owner changed' }
    $r = Get-Rect
    $x = $r.L + [int](($r.R - $r.L) / 2)
    $y = $r.T + [int]([System.Windows.Forms.SystemInformation]::CaptionHeight / 2)
    if (-not [CoachWin]::IsExposedCaption($script:Win, $x, $y)) { throw 'Installed window title bar is covered' }
    if (-not [WD]::SetCursorPos($x, $y) -or -not [CoachWin]::IsExposedCaption($script:Win, $x, $y)) {
        throw 'Cannot select the installed title bar'
    }
    [WD]::mouse_event(2, 0, 0, 0, [IntPtr]::Zero)
    [WD]::mouse_event(4, 0, 0, 0, [IntPtr]::Zero)
    Start-Sleep -Milliseconds 300
    Note "Selected installed title bar; foreground: $(Get-ForegroundSummary)"
}

function Get-LearningCoachProducts {
    $paths = @('HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*', 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*')
    @(Get-ItemProperty $paths -ErrorAction SilentlyContinue | Where-Object { $_.DisplayName -eq 'LearningCoach' })
}
function Get-StartMenuShortcuts {
    @(Get-ChildItem (Join-Path ([Environment]::GetFolderPath('Programs')) 'LearningCoach') -Filter *.lnk -ErrorAction SilentlyContinue)
}

# 截图：窗口整张、标题栏左上角放大、任务栏条带放大，都是屏幕实拍，不是离屏渲染。
function Save-Png([string]$path, [System.Drawing.Bitmap]$bmp) { $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose() }
function Save-Crop([string]$src, [string]$dst, [int]$x, [int]$y, [int]$w, [int]$h, [int]$scale = 1) {
    $img = [System.Drawing.Image]::FromFile($src)
    try {
        $bmp = New-Object System.Drawing.Bitmap ($w * $scale), ($h * $scale)
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
        $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
        $g.DrawImage($img, (New-Object System.Drawing.Rectangle 0, 0, ($w * $scale), ($h * $scale)), (New-Object System.Drawing.Rectangle $x, $y, $w, $h), [System.Drawing.GraphicsUnit]::Pixel)
        $g.Dispose()
        Save-Png $dst $bmp
    } finally { $img.Dispose() }
}
# 桌面整屏：任务栏和开始菜单由系统绘制，只有全屏实拍才含它们。这里只读屏、不激活任何窗口，
# 所以不会把别人的窗口抢成前台；拍到的就是我们这张桌面的真实样子。
function Save-Desktop([string]$path) {
    $vs = [System.Windows.Forms.SystemInformation]::VirtualScreen
    $bmp = New-Object System.Drawing.Bitmap $vs.Width, $vs.Height
    try {
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        try { $g.CopyFromScreen($vs.X, $vs.Y, 0, 0, $bmp.Size) } finally { $g.Dispose() }
        $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally { $bmp.Dispose() }
}

# 只认这次装的 exe 起的窗口。jpackage 的启动器会把 JVM 交给一个子进程，窗口挂在子进程上，
# 所以按“启动后新出现的 LearningCoach.exe”认人；进程集合存脚本作用域，EnumWindows 的回调看不见函数的局部变量。
function Find-InstalledWindow {
    $script:Win = [IntPtr]::Zero
    $script:WinPid = 0
    [WD]::EnumWindows({ param($h, $l)
        $p = 0; [WD]::GetWindowThreadProcessId($h, [ref]$p) | Out-Null
        if ($script:TargetPids -contains $p -and [WD]::IsWindowVisible($h)) {
            if ([CoachWin]::Text($h) -eq 'LearningCoach') { $script:Win = $h; $script:WinPid = $p }
        }
        return $true }, [IntPtr]::Zero) | Out-Null
    # Win-Drive 的窗口归属保护认 $script:AppPid：把这次起的、真正拥有窗口的那个进程绑上去。
    # 不绑就是 0，Save-Shot 会以“window does not belong to this run”拒绝，等于没保护也没截图。
    if ($script:Win -ne [IntPtr]::Zero) { $script:AppPid = $script:WinPid }
    return $script:Win
}

# ---- 0. 装之前先把机器状态记下来，装完、卸完各自对照 ----
"MSI: $msiPath" | Set-Content -Encoding utf8 (Join-Path $OutDir 'environment.txt')
"MSI bytes: $((Get-Item $msiPath).Length)" | Add-Content -Encoding utf8 (Join-Path $OutDir 'environment.txt')
$os = Get-CimInstance Win32_OperatingSystem
"OS: $($os.Caption) $($os.Version)" | Add-Content -Encoding utf8 (Join-Path $OutDir 'environment.txt')
"User: $([Security.Principal.WindowsIdentity]::GetCurrent().Name) session=$((Get-Process -Id $PID).SessionId)" | Add-Content -Encoding utf8 (Join-Path $OutDir 'environment.txt')
$screen = [System.Windows.Forms.SystemInformation]::VirtualScreen
"Screen: $($screen.Width)x$($screen.Height)" | Add-Content -Encoding utf8 (Join-Path $OutDir 'environment.txt')
"Admin: $((New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator))" | Add-Content -Encoding utf8 (Join-Path $OutDir 'environment.txt')

# ---- 1. 安装（静默，不要管理员）----
$before = Get-LearningCoachProducts
if ($before) {
    Note "先卸掉机器上已有的 LearningCoach（$($before[0].DisplayVersion)），从干净状态开始"
    $code = $before[0].PSChildName
    Start-Process msiexec.exe -ArgumentList @('/x', $code, '/qn', '/norestart') -Wait | Out-Null
}
$installLog = Join-Path $OutDir 'msiexec-install.log'
$p = Start-Process msiexec.exe -ArgumentList @('/i', "`"$msiPath`"", '/qn', '/norestart', '/l*v', "`"$installLog`"") -Wait -PassThru
if ($p.ExitCode -ne 0) { Fail '安装' "msiexec 退出码 $($p.ExitCode)（日志：msiexec-install.log）" }
$product = Get-LearningCoachProducts
if (-not $product) { Fail '安装' '装完在卸载列表里找不到 LearningCoach' }
$location = $product[0].InstallLocation.TrimEnd('\')
$exe = Join-Path $location 'LearningCoach.exe'
if (-not (Test-Path $exe)) { Fail '安装' "装完找不到 $exe" }
$shortcut = Get-StartMenuShortcuts
if (-not $shortcut) { Fail '安装' '开始菜单里没有 LearningCoach 快捷方式' }
Pass '安装' "DisplayName=$($product[0].DisplayName) 版本=$($product[0].DisplayVersion) 发布者=$($product[0].Publisher)；装到 $location；快捷方式 $($shortcut[0].FullName)"

# 装进去的启动配置：证明包是按没课的样子打的（朋友的机器没有 config/local.properties）
$cfg = Join-Path $location 'app\LearningCoach.cfg'
if (Test-Path $cfg) { Copy-Item $cfg (Join-Path $OutDir 'launcher.cfg') -Force }

# exe 自带的图标是资源管理器、开始菜单、固定到任务栏时用的那张
$appIcon = Join-Path $OutDir 'exe-icon.png'
$icon = [System.Drawing.Icon]::ExtractAssociatedIcon($exe)
if ($icon) { Save-Png $appIcon $icon.ToBitmap() }

# ---- 2. 启动真窗口 ----
$library = Join-Path $OutDir 'library'
$clientDir = Join-Path $OutDir 'client'
New-Item -ItemType Directory -Force $library, $clientDir | Out-Null
$env:COACH_LIBRARY_DIR = $library
$env:COACH_CLIENT_DIR = $clientDir
$env:COACH_TOKEN_FILE = Join-Path $OutDir 'unused-token'
$script:BeforePids = @(Get-CimInstance Win32_Process -Filter "Name='LearningCoach.exe'" -ErrorAction SilentlyContinue | ForEach-Object { [int]$_.ProcessId })
$app = Start-Process $exe -PassThru
$script:TargetPids = @()
$script:WinPid = 0
$deadline = (Get-Date).AddSeconds($StartTimeoutSec)
$hwnd = [IntPtr]::Zero
do {
    Start-Sleep -Milliseconds 500
    $script:TargetPids = @(Get-CimInstance Win32_Process -Filter "Name='LearningCoach.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $script:BeforePids -notcontains [int]$_.ProcessId } | ForEach-Object { [int]$_.ProcessId })
    if ($app.HasExited -and $script:TargetPids.Count -eq 0) { Fail '启动' "装的 exe 启动后就退出了（退出码 $($app.ExitCode)）" }
    $hwnd = Find-InstalledWindow
} while ($hwnd -eq [IntPtr]::Zero -and (Get-Date) -lt $deadline)
if ($hwnd -eq [IntPtr]::Zero) { Fail '启动' "$StartTimeoutSec 秒内没有出现 LearningCoach 窗口（本次新起的进程：$($script:TargetPids -join ',')）" }
Pass '启动' "窗口出来了（启动器 pid $($app.Id)，窗口进程 $($script:WinPid)，句柄 $hwnd）"
$dpi = try { [int][CoachWin]::GetDpiForWindow($hwnd) } catch { 0 }
if ($dpi -gt 0) { "Dpi: $dpi ($([math]::Round(100 * $dpi / 96))%)" | Add-Content -Encoding utf8 (Join-Path $OutDir 'environment.txt') }

Start-Sleep -Seconds 2
$hostedArm = $env:GITHUB_ACTIONS -eq 'true' -and $env:RUNNER_ENVIRONMENT -eq 'github-hosted' -and $env:PROCESSOR_ARCHITECTURE -eq 'ARM64'
try {
    if ($hostedArm -and -not [WD]::Activate($hwnd)) { Select-InstalledWindow }
    Save-Shot (Join-Path $OutDir '01-window.png')
} catch {
    $why = $_.Exception.Message
    if ($hostedArm) { Save-Desktop (Join-Path $OutDir 'failed-desktop.png') }
    Fail '截图' "整窗实拍失败：$why"
}
$r = Get-Rect
# 桌面整屏 + 任务栏。裁剪按这次运行机的实际屏幕尺寸算，不写死 winbox 的 5120×2160 坐标。
$vs = [System.Windows.Forms.SystemInformation]::VirtualScreen
# 任务栏高度用工作区底边实测（RC1 那台 768 高的机器是 48px，按 4% 估成 40，04 少 8px、06 多带一条任务栏）；
# Auto-hide 或量不到时再退回按屏幕高度估。
$tbMeasured = [int]([System.Windows.Forms.Screen]::PrimaryScreen.Bounds.Bottom - [System.Windows.Forms.Screen]::PrimaryScreen.WorkingArea.Bottom)
$taskbar = if ($tbMeasured -gt 16 -and $tbMeasured -lt [int]($vs.Height / 4)) { $tbMeasured } else { [int][Math]::Max(40, [Math]::Min(72, [Math]::Round($vs.Height * 0.04))) }
$zoom = if ($vs.Width -le 1400) { 3 } else { 2 }
Save-Desktop (Join-Path $OutDir '02-desktop-taskbar.png')
# 标题栏左上角那枚 16/24/32px 图标放大 4 倍看，边缘糊不糊一眼就知道
Save-Crop (Join-Path $OutDir '01-window.png') (Join-Path $OutDir '03-titlebar-icon-x4.png') 0 0 ([Math]::Min(220, $r.R - $r.L)) ([Math]::Min(36, $r.B - $r.T)) 4
# 任务栏：运行中的应用按钮用同一枚图标
Save-Crop (Join-Path $OutDir '02-desktop-taskbar.png') (Join-Path $OutDir "04-taskbar-x$zoom.png") 0 ($vs.Height - $taskbar) $vs.Width $taskbar $zoom
Pass '截图' "窗口、桌面整屏 $($vs.Width)x$($vs.Height)（含任务栏）、标题栏图标放大、任务栏放大"

# ---- 3. 开始菜单搜索里看到的图标和名字 ----
Open-StartMenu
Start-Sleep -Seconds 2
[System.Windows.Forms.SendKeys]::SendWait('LearningCoach')
Start-Sleep -Seconds 3
Save-Desktop (Join-Path $OutDir '05-start-menu.png')
# 结果行在菜单里的位置随 Win10 左对齐 / Win11 居中而变，别按任务栏往上倒推：
# RC1 就是那样在 768 高的运行机上从 y=306 起裁，切掉了 y≈199 的结果行，只剩空白菜单。
# 改成从屏幕顶端起、一直裁到任务栏上沿，菜单整块和搜索框都留在里面。
$band = [int][Math]::Max(1, $vs.Height - $taskbar)
$bandZoom = if ($vs.Width -le 1400) { 2 } else { 1 }
Save-Crop (Join-Path $OutDir '05-start-menu.png') (Join-Path $OutDir '06-start-menu-result.png') 0 0 $vs.Width $band $bandZoom
[System.Windows.Forms.SendKeys]::SendWait('{ESC}')
Start-Sleep -Seconds 1
[System.Windows.Forms.SendKeys]::SendWait('{ESC}')
Pass '开始菜单' '开始菜单打开时的整屏 + 任务栏以上横带（图标 + 名字）'

# ---- 4. 关窗口、卸载 ----
$client = Get-Process -Id $script:WinPid -ErrorAction SilentlyContinue
$closedByUser = $false
if ($client) {
    $null = $client.CloseMainWindow()
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while (-not $client.HasExited -and $sw.Elapsed.TotalSeconds -lt 20) { Start-Sleep -Milliseconds 300; $client.Refresh() }
    $closedByUser = $client.HasExited
}
# 收掉这次自己起的进程（含启动器），别留在桌面上下一次误认
foreach ($own in @($script:TargetPids + $app.Id)) { Stop-Process -Id $own -Force -ErrorAction SilentlyContinue }
if (-not $closedByUser) { Fail '退出' "点关闭后窗口进程 $($script:WinPid) 20 秒内没退出" }
Pass '退出' "点关闭后窗口进程 $($script:WinPid) 自己退出"

if ($KeepInstalled) {
    Note 'KeepInstalled：不卸载'
} else {
    $product = Get-LearningCoachProducts
    $code = $product[0].PSChildName
    $uninstallLog = Join-Path $OutDir 'msiexec-uninstall.log'
    $u = Start-Process msiexec.exe -ArgumentList @('/x', $code, '/qn', '/norestart', '/l*v', "`"$uninstallLog`"") -Wait -PassThru
    if ($u.ExitCode -ne 0) { Fail '卸载' "msiexec /x 退出码 $($u.ExitCode)（日志：msiexec-uninstall.log）" }
    $leftovers = New-Object System.Collections.ArrayList
    if (Get-LearningCoachProducts) { [void]$leftovers.Add('卸载列表里还有 LearningCoach') }
    if (Get-StartMenuShortcuts) { [void]$leftovers.Add('开始菜单快捷方式没删') }
    if (Test-Path (Join-Path $location 'LearningCoach.exe')) { [void]$leftovers.Add("程序文件没删：$location") }
    $desktopLnk = Join-Path ([Environment]::GetFolderPath('Desktop')) 'LearningCoach.lnk'
    if (Test-Path $desktopLnk) { [void]$leftovers.Add('桌面快捷方式没删') }
    $leftoverText = if ($leftovers.Count -eq 0) { '没有残留' } else { $leftovers -join '；' }
    "残留：$leftoverText" | Set-Content -Encoding utf8 (Join-Path $OutDir 'uninstall-leftovers.txt')
    "安装目录还在：$(Test-Path $location)" | Add-Content -Encoding utf8 (Join-Path $OutDir 'uninstall-leftovers.txt')
    if (Test-Path $location) { (Get-ChildItem $location -Recurse -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName) | Add-Content -Encoding utf8 (Join-Path $OutDir 'uninstall-leftovers.txt') }
    if ($leftovers.Count -gt 0) { Fail '卸载' $leftoverText }
    Pass '卸载' '卸载列表、开始菜单、程序文件都清干净了'
}

$failed = @($results | Where-Object { -not $_.Ok }).Count
Save-Result
if ($failed -gt 0) { exit 1 }
