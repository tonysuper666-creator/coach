# Dot-sourced inside the isolated interactive desktop after opening the PDF.
function Read-TutorFrame([string]$name) {
    Save-OwnWindow "$name.png"
    if ($Winbox) {
        $lines = @(Read-Screen (Join-Path $OutDir "$name.png"))
        $lines | ForEach-Object Text | Set-Content (Join-Path $OutDir "$name.txt") -Encoding UTF8
        return $lines
    }
    $src = [Drawing.Bitmap]::new((Join-Path $OutDir "$name.png"))
    $bmp = [Drawing.Bitmap]::new($src.Width * 3, $src.Height * 3, [Drawing.Imaging.PixelFormat]::Format24bppRgb)
    try {
        $g = [Drawing.Graphics]::FromImage($bmp)
        try {
            $g.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $g.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::Half
            $g.DrawImage($src, 0, 0, $bmp.Width, $bmp.Height)
        } finally { $g.Dispose() }
        $file = Join-Path $tutorPrivate 'screen.bmp'
        $bmp.Save($file, [Drawing.Imaging.ImageFormat]::Bmp)
    } finally { $bmp.Dispose(); $src.Dispose() }
    $json = & python (Join-Path $PSScriptRoot 'arm-screen-text.py') $file $tutorPrivate
    if ($LASTEXITCODE -ne 0) { throw 'Window text recognition failed' }
    $rows = $json | ConvertFrom-Json
    $lines = foreach ($group in ($rows | Group-Object -Property { $_[0] -join '-' })) {
        $words = foreach ($w in $group.Group) {
            [pscustomobject]@{Text=$w[1]; BoundingRect=[pscustomobject]@{X=$w[2]/3; Y=$w[3]/3; Width=$w[4]/3; Height=$w[5]/3}}
        }
        [pscustomobject]@{Text=(($words | ForEach-Object Text) -join ''); Words=@($words)}
    }
    $lines | ForEach-Object Text | Set-Content (Join-Path $OutDir "$name.txt") -Encoding UTF8
    return $lines
}

function Scroll-TutorView([int]$notches) {
    $bounds = Get-Rect
    if ($Winbox) {
        Scroll-At ([int](($bounds.R - $bounds.L) * 0.56)) ([int](($bounds.B - $bounds.T) * 0.70)) $notches
    } else {
        # 2.0 companion on this runner is a bottom sheet. Wheel its body, not the page.
        Scroll-At ([int](($bounds.R - $bounds.L) * 0.50)) ([int](($bounds.B - $bounds.T) * 0.78)) $notches
    }
}

function Find-TutorLabel([string]$name, [string[]]$labels, [int]$scroll = 0, [int]$minX = 0, [int]$minY = 0) {
    foreach ($attempt in 0..5) {
        Start-Sleep -Milliseconds 450
        $lines = @(Read-TutorFrame $name)
        foreach ($label in $labels) {
            if ($Winbox) {
                # Prefer a stand-alone button over instructions that quote its label.
                $lines = @($lines | Sort-Object { ($_.Text -replace '\s','') -ne $label })
            }
            foreach ($line in $lines) {
                $hit = Find-Text @($line) $label
                if ($hit -and $hit.X -ge $minX -and $hit.Y -ge $minY) { return $hit }
            }
        }
        if ($scroll) { Scroll-TutorView $scroll }
    }
    throw "Missing '$($labels -join ' / ')' in $name"
}

function Select-TutorLabel([string]$name, [string[]]$labels, [int]$scroll = 0, [int]$minX = 0, [int]$minY = 0) {
    $point = Find-TutorLabel $name $labels $scroll $minX $minY
    Click-At $point.X $point.Y
}

# On the shared x64 desktop, font scale and theme follow the user's settings.
# Match the visible label in the app's own capture, including the PDF page fraction.
function Find-WinboxControl([string]$template, [string]$name, [int]$scroll) {
    $label = @{
        'empty-shelf-upload.png' = '导入文件'
        'shelf-read-first.png' = '先读'
        'reader-defer-study.png' = '暂不开启'
        'explain-consent.png' = '允许并讲解'
        'block-reply-text.png' = 'Blockexplanation.'
        'shelf-guided.png' = '边看边学'
        'guided-start.png' = '开始边看边学'
        'pdf-page-1-of-3.png' = '1/3'
        'shelf-practice.png' = '做题学'
    }[$template]
    if (-not $label) { throw "Unknown installed control $template" }
    foreach ($attempt in 1..10) {
        $lines = @(Read-TutorFrame $name)
        $exact = @($lines | Where-Object { ($_.Text -replace '\s','') -eq $label })
        $hit = Find-Text $exact $label
        if (-not $hit -and $template -eq 'block-reply-text.png') { $hit = Find-Text $lines $label }
        if ($hit) { return $hit }
        if ($scroll) { Scroll-TutorView $scroll }
        Start-Sleep -Milliseconds 500
    }
    throw "Missing $label in $name"
}

# One visual row of body text. Tesseract's sparse mode often glues a wrapped paragraph
# into a single box, and the centre of that box sits in the gap between the glyphs, so a
# horizontal drag there selects nothing.
function Find-TutorBodyRows($lines) {
    $tool = @('举个例子', '讲讲', '笔记', '看原页', '看文字') | ForEach-Object { Find-Text $lines $_ } | Where-Object { $_ } | Select-Object -First 1
    $bar = @('上一页', '下一页') | ForEach-Object { Find-Text $lines $_ } | Where-Object { $_ } | Select-Object -First 1
    # y=54 on this window is the HUD (书架 / 伴读), not a body line.
    $top = if ($tool) { $tool.Y2 + 8 } else { 96 }
    $bottom = if ($bar) { $bar.Y1 - 8 } else { 100000 }
    # A generic List throws "Argument types do not match" when this script enumerates it
    # on Windows PowerShell 5.1. Keep plain arrays.
    $buckets = @()
    foreach ($l in @($lines)) {
        foreach ($w in @($l.Words)) {
            $rect = $w.BoundingRect
            if (-not $rect -or $rect.Width -lt 2 -or $rect.Height -lt 2) { continue }
            $cy = [double]$rect.Y + ([double]$rect.Height / 2)
            if ($cy -lt $top -or $cy -gt $bottom) { continue }
            $hitIndex = -1
            for ($i = 0; $i -lt @($buckets).Count; $i++) {
                if ([Math]::Abs([double]$buckets[$i].Y - $cy) -le 9) { $hitIndex = $i; break }
            }
            if ($hitIndex -lt 0) {
                $buckets += ,([pscustomobject]@{ Y = $cy; Items = @($w) })
            } else {
                $buckets[$hitIndex].Items = @($buckets[$hitIndex].Items) + $w
            }
        }
    }
    $rows = foreach ($bucket in @($buckets)) {
        $items = @($bucket.Items)
        $text = ($items | ForEach-Object { $_.Text }) -join ''
        if (($text -replace '[^\p{L}\p{N}]', '').Length -lt 8) { continue }
        $x1 = [int](($items | ForEach-Object { $_.BoundingRect.X } | Measure-Object -Minimum).Minimum)
        $x2 = [int](($items | ForEach-Object { $_.BoundingRect.X + $_.BoundingRect.Width } | Measure-Object -Maximum).Maximum)
        $y = [int](($items | ForEach-Object { $_.BoundingRect.Y + ($_.BoundingRect.Height / 2) } | Measure-Object -Average).Average)
        if (($x2 - $x1) -lt 120) { continue }
        [pscustomobject]@{ X1 = $x1; X2 = $x2; Y = $y; Width = ($x2 - $x1) }
    }
    return @($rows | Sort-Object Width -Descending)
}

# OCR glues the two pages of a spread into one row, so a drag from that box crosses the
# gutter and selects nothing. Measure the dark glyphs in one column instead.
function Find-InkRowsInBand([Drawing.Bitmap]$bmp, [double]$x0f, [double]$x1f) {
    $w = $bmp.Width
    $h = $bmp.Height
    $x0 = [int]($w * $x0f)
    $x1 = [int]($w * $x1f)
    $y0 = [int]($h * 0.16)
    $y1 = [int]($h * 0.80)
    $hits = @()
    for ($y = $y0; $y -lt $y1; $y += 2) {
        $dark = 0
        $first = -1
        $last = -1
        for ($x = $x0; $x -lt $x1; $x += 2) {
            $c = $bmp.GetPixel($x, $y)
            if ($c.R -lt 90 -and $c.G -lt 90 -and $c.B -lt 90) {
                $dark++
                if ($first -lt 0) { $first = $x }
                $last = $x
            }
        }
        # A real glyph line is dense. A few dark specks across the column are not a line.
        if ($dark -ge 14 -and ($last - $first) -ge 100) {
            $hits += ,([pscustomobject]@{ Y = $y; Dark = $dark; X1 = $first; X2 = $last })
        }
    }
    $buckets = @()
    foreach ($hit in @($hits)) {
        $hitIndex = -1
        for ($i = 0; $i -lt @($buckets).Count; $i++) {
            if ([Math]::Abs([int]$buckets[$i].Y - [int]$hit.Y) -le 6) { $hitIndex = $i; break }
        }
        if ($hitIndex -lt 0) {
            $buckets += ,([pscustomobject]@{ Y = [int]$hit.Y; Peak = $hit })
        } elseif ([int]$hit.Dark -gt [int]$buckets[$hitIndex].Peak.Dark) {
            $buckets[$hitIndex].Peak = $hit
        }
    }
    $rows = foreach ($bucket in @($buckets)) {
        $peak = $bucket.Peak
        [pscustomobject]@{ X1 = [int]$peak.X1; X2 = [int]$peak.X2; Y = [int]$peak.Y; Dark = [int]$peak.Dark; Width = ([int]$peak.X2 - [int]$peak.X1) }
    }
    return @($rows | Sort-Object Dark -Descending)
}

function Find-LeftInkRows([string]$name) {
    $path = Join-Path $OutDir "$name.png"
    if (-not (Test-Path -LiteralPath $path)) { return @() }
    $bmp = [Drawing.Bitmap]::new($path)
    try {
        $left = @(Find-InkRowsInBand $bmp 0.16 0.44)
        if (@($left).Count -ge 1) { return $left }
        return @(Find-InkRowsInBand $bmp 0.56 0.84)
    } finally { $bmp.Dispose() }
}

# The installed MSI has no smoke driver. A drag has to be a real pointer gesture:
# SetCursorPos while the button is down does not deliver WM_MOUSEMOVE, so Compose
# treats it as a click and clears the selection. Absolute moves use the virtual
# screen; if that mapping misses, a one-pixel relative nudge forces the move at
# the corrected pixel. Product selection stays the main-branch mouse drag.
if (-not ('ArmMouse' -as [type])) {
    Add-Type @'
using System.Runtime.InteropServices;
public static class ArmMouse {
    [DllImport("user32.dll")] public static extern int GetSystemMetrics(int index);
}
'@
}

function Get-TutorCursor {
    $pt = New-Object WD+POINT
    [void][WD]::GetCursorPos([ref]$pt)
    return $pt
}

function Send-AbsoluteMove([int]$screenX, [int]$screenY) {
    $vx = [ArmMouse]::GetSystemMetrics(76)
    $vy = [ArmMouse]::GetSystemMetrics(77)
    $vw = [ArmMouse]::GetSystemMetrics(78)
    $vh = [ArmMouse]::GetSystemMetrics(79)
    if ($vw -lt 2 -or $vh -lt 2) { throw "virtual screen is ${vw}x${vh}" }
    $ax = [int](($screenX - $vx) * 65535.0 / ($vw - 1))
    $ay = [int](($screenY - $vy) * 65535.0 / ($vh - 1))
    if ($ax -lt 0) { $ax = 0 } elseif ($ax -gt 65535) { $ax = 65535 }
    if ($ay -lt 0) { $ay = 0 } elseif ($ay -gt 65535) { $ay = 65535 }
    [WD]::mouse_event(0xC001, $ax, $ay, 0, [IntPtr]::Zero)
    Start-Sleep -Milliseconds 30
    return Get-TutorCursor
}

function Nudge-Onto([int]$screenX, [int]$screenY) {
    [void][WD]::SetCursorPos($screenX - 1, $screenY)
    [WD]::mouse_event(0x0001, 1, 0, 0, [IntPtr]::Zero)
    Start-Sleep -Milliseconds 30
    return Get-TutorCursor
}

# Absolute mouse_event on this runner updates GetCursorPos and still does not post
# WM_MOUSEMOVE (run 45770791380: the cursor walked the glyph line, Compose saw a click).
# Park just left of the target, then walk the last pixels with relative moves.
function Post-MoveAt([int]$screenX, [int]$screenY) {
    [void](Send-AbsoluteMove ($screenX - 8) $screenY)
    foreach ($n in 1..8) {
        [WD]::mouse_event(0x0001, 1, 0, 0, [IntPtr]::Zero)
        Start-Sleep -Milliseconds 12
    }
    $pt = Get-TutorCursor
    if ([Math]::Abs($pt.X - $screenX) -gt 6 -or [Math]::Abs($pt.Y - $screenY) -gt 3) {
        [void](Send-AbsoluteMove ($screenX - 1) $screenY)
        [WD]::mouse_event(0x0001, 1, 0, 0, [IntPtr]::Zero)
        Start-Sleep -Milliseconds 20
        $pt = Get-TutorCursor
    }
    return $pt
}

function Move-TutorPointer([int]$screenX, [int]$screenY, [bool]$absoluteOk) {
    return Post-MoveAt $screenX $screenY
}

# mouse_event moves the cursor on this runner and still does not deliver WM_MOUSEMOVE
# (runs 45770791380 and 45773285745). Java Robot uses SendInput, which is the stream
# AWT turns into a drag. If that is refused, post the moves while the OS button is down.
if (-not ('Arm.Drag' -as [type])) {
    Add-Type -Namespace Arm -Name Drag -MemberDefinition @'
[StructLayout(LayoutKind.Sequential)]
public struct POINT { public int X; public int Y; }
[StructLayout(LayoutKind.Sequential)]
public struct MOUSEINPUT {
    public int dx;
    public int dy;
    public int mouseData;
    public int dwFlags;
    public int time;
    public IntPtr dwExtraInfo;
}
[StructLayout(LayoutKind.Explicit)]
public struct INPUT {
    [FieldOffset(0)] public int type;
    [FieldOffset(8)] public MOUSEINPUT mi;
}
[DllImport("user32.dll")] public static extern IntPtr WindowFromPoint(POINT p);
[DllImport("user32.dll")] public static extern bool ScreenToClient(IntPtr h, ref POINT p);
[DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetClassName(IntPtr h, System.Text.StringBuilder s, int n);
[DllImport("user32.dll", SetLastError=true)] public static extern bool PostMessage(IntPtr hWnd, int msg, IntPtr wParam, IntPtr lParam);
[DllImport("user32.dll", SetLastError=true)] public static extern uint SendInput(uint nInputs, INPUT[] inputs, int cbSize);
public static int Send(int flags, int dx, int dy) {
    INPUT[] inputs = new INPUT[1];
    inputs[0].type = 0;
    inputs[0].mi.dx = dx;
    inputs[0].mi.dy = dy;
    inputs[0].mi.mouseData = 0;
    inputs[0].mi.dwFlags = flags;
    inputs[0].mi.time = 0;
    inputs[0].mi.dwExtraInfo = IntPtr.Zero;
    uint sent = SendInput(1, inputs, System.Runtime.InteropServices.Marshal.SizeOf(typeof(INPUT)));
    if (sent == 0) return -System.Runtime.InteropServices.Marshal.GetLastWin32Error();
    return (int)sent;
}
public static IntPtr Pack(int x, int y) {
    return (IntPtr)(((y & 0xFFFF) << 16) | (x & 0xFFFF));
}
'@
}

function Send-ArmAbsolute([int]$screenX, [int]$screenY) {
    $vx = [ArmMouse]::GetSystemMetrics(76)
    $vy = [ArmMouse]::GetSystemMetrics(77)
    $vw = [ArmMouse]::GetSystemMetrics(78)
    $vh = [ArmMouse]::GetSystemMetrics(79)
    if ($vw -lt 2 -or $vh -lt 2) { return -1 }
    $ax = [int](($screenX - $vx) * 65535.0 / ($vw - 1))
    $ay = [int](($screenY - $vy) * 65535.0 / ($vh - 1))
    if ($ax -lt 0) { $ax = 0 } elseif ($ax -gt 65535) { $ax = 65535 }
    if ($ay -lt 0) { $ay = 0 } elseif ($ay -gt 65535) { $ay = 65535 }
    return [Arm.Drag]::Send(0xC001, $ax, $ay)
}

# Run 45781907707: absolute SendInput put the cursor on the left-page glyphs and
# Compose still treated the gesture as a click. The reader already selects from a
# touch long-press (hold still, then drag). Inject that. A relative SendInput
# stroke is the fallback when injection is refused; absolute moves are not used
# while the button is down.
if (-not ('ArmTouch' -as [type])) {
    Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class ArmTouch {
    public const uint Down = 0x00010006;
    public const uint Update = 0x00020006;
    public const uint Up = 0x00040002;
    static bool ready;
    static uint frame;
    static int backend;
    static uint useId;
    static bool picked;
    static IntPtr device;
    public static string Trace = "";
    [StructLayout(LayoutKind.Explicit, Size = 144)]
    public struct CONTACT {
        [FieldOffset(0)] public uint pointerType;
        [FieldOffset(4)] public uint pointerId;
        [FieldOffset(8)] public uint frameId;
        [FieldOffset(12)] public uint pointerFlags;
        [FieldOffset(16)] public IntPtr sourceDevice;
        [FieldOffset(24)] public IntPtr hwndTarget;
        [FieldOffset(32)] public int ptPixelLocationX;
        [FieldOffset(36)] public int ptPixelLocationY;
        [FieldOffset(40)] public int ptHimetricLocationX;
        [FieldOffset(44)] public int ptHimetricLocationY;
        [FieldOffset(48)] public int ptPixelLocationRawX;
        [FieldOffset(52)] public int ptPixelLocationRawY;
        [FieldOffset(56)] public int ptHimetricLocationRawX;
        [FieldOffset(60)] public int ptHimetricLocationRawY;
        [FieldOffset(64)] public uint dwTime;
        [FieldOffset(68)] public uint historyCount;
        [FieldOffset(72)] public int InputData;
        [FieldOffset(76)] public uint dwKeyStates;
        [FieldOffset(80)] public ulong PerformanceCount;
        [FieldOffset(88)] public int ButtonChangeType;
        [FieldOffset(96)] public uint touchFlags;
        [FieldOffset(100)] public uint touchMask;
        [FieldOffset(104)] public int rcLeft;
        [FieldOffset(108)] public int rcTop;
        [FieldOffset(112)] public int rcRight;
        [FieldOffset(116)] public int rcBottom;
        [FieldOffset(120)] public int rawLeft;
        [FieldOffset(124)] public int rawTop;
        [FieldOffset(128)] public int rawRight;
        [FieldOffset(132)] public int rawBottom;
        [FieldOffset(136)] public uint orientation;
        [FieldOffset(140)] public uint pressure;
    }
    [StructLayout(LayoutKind.Sequential)]
    public struct PInfo {
        public uint pointerType;
        public uint pointerId;
        public uint frameId;
        public uint pointerFlags;
        public IntPtr sourceDevice;
        public IntPtr hwndTarget;
        public int x1, y1, x2, y2, x3, y3, x4, y4;
        public uint dwTime;
        public uint historyCount;
        public int InputData;
        public uint dwKeyStates;
        public ulong PerformanceCount;
        public int ButtonChangeType;
    }
    [StructLayout(LayoutKind.Sequential)]
    public struct TInfo {
        public PInfo pointerInfo;
        public uint touchFlags;
        public uint touchMask;
        public int l, t, r, b, rl, rt, rr, rb;
        public uint orientation;
        public uint pressure;
    }
    [StructLayout(LayoutKind.Sequential)]
    public struct TypeInfo {
        public uint type;
        public TInfo touch;
    }
    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool InitializeTouchInjection(uint maxCount, uint dwMode);
    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool InjectTouchInput(uint count, IntPtr contacts);
    [DllImport("user32.dll", SetLastError = true)]
    public static extern IntPtr CreateSyntheticPointerDevice(uint pointerType, uint maxCount, uint mode);
    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool InjectSyntheticPointerInput(IntPtr device, IntPtr pointerInfo, uint count);
    [DllImport("user32.dll", EntryPoint = "SystemParametersInfoW", SetLastError = true)]
    public static extern bool SpiInt(uint action, uint param, ref int value, uint ini);
    [DllImport("user32.dll", EntryPoint = "SystemParametersInfoW", SetLastError = true)]
    public static extern bool SpiArr(uint action, uint param, int[] value, uint ini);
    [DllImport("user32.dll", EntryPoint = "SystemParametersInfoW", SetLastError = true)]
    public static extern bool SpiPtr(uint action, uint param, IntPtr value, uint ini);
    public static int Bytes() { return Marshal.SizeOf(typeof(CONTACT)); }
    public static string Layout() {
        return "pinfo=" + Marshal.SizeOf(typeof(PInfo))
            + " tinfo=" + Marshal.SizeOf(typeof(TInfo))
            + " typeinfo=" + Marshal.SizeOf(typeof(TypeInfo))
            + " touchoff=" + Marshal.OffsetOf(typeof(TypeInfo), "touch").ToInt32()
            + " flagoff=" + Marshal.OffsetOf(typeof(TInfo), "touchFlags").ToInt32();
    }
    public static int Init() {
        if (ready) return 1;
        if (!InitializeTouchInjection(10, 3)) return -Marshal.GetLastWin32Error();
        ready = true;
        return 1;
    }
    static CONTACT Make(uint flags, int x, int y, uint id, int buttonChange) {
        frame++;
        CONTACT c = new CONTACT();
        c.pointerType = 2;
        c.pointerId = id;
        c.frameId = frame;
        c.pointerFlags = flags;
        c.ptPixelLocationX = x;
        c.ptPixelLocationY = y;
        c.ptPixelLocationRawX = x;
        c.ptPixelLocationRawY = y;
        c.ButtonChangeType = buttonChange;
        c.touchMask = 7;
        c.rcLeft = x - 2;
        c.rcTop = y - 2;
        c.rcRight = x + 2;
        c.rcBottom = y + 2;
        c.rawLeft = x - 2;
        c.rawTop = y - 2;
        c.rawRight = x + 2;
        c.rawBottom = y + 2;
        c.orientation = 90;
        c.pressure = 512;
        return c;
    }
    static int Legacy(CONTACT c) {
        int bytes = Marshal.SizeOf(typeof(CONTACT));
        IntPtr mem = Marshal.AllocHGlobal(bytes);
        try {
            Marshal.Copy(new byte[bytes], 0, mem, bytes);
            Marshal.StructureToPtr(c, mem, false);
            if (!InjectTouchInput(1, mem)) return -Marshal.GetLastWin32Error();
            return 1;
        } finally {
            Marshal.FreeHGlobal(mem);
        }
    }
    static int Synth(CONTACT c) {
        if (device == IntPtr.Zero) {
            device = CreateSyntheticPointerDevice(2, 10, 3);
            if (device == IntPtr.Zero) return -Marshal.GetLastWin32Error();
        }
        if ((c.pointerFlags & 0x00010000) != 0) c.pointerFlags |= 0x00002000;
        int n = Marshal.SizeOf(typeof(CONTACT));
        IntPtr mem = Marshal.AllocHGlobal(8 + n);
        try {
            Marshal.Copy(new byte[8 + n], 0, mem, 8 + n);
            Marshal.WriteInt32(mem, 0, 2);
            Marshal.StructureToPtr(c, new IntPtr(mem.ToInt64() + 8), false);
            if (!InjectSyntheticPointerInput(device, mem, 1)) return -Marshal.GetLastWin32Error();
            return 1;
        } finally {
            Marshal.FreeHGlobal(mem);
        }
    }
    public static int Send(uint flags, int x, int y, int buttonChange) {
        Trace = "";
        if (!picked && (flags & 0x00010000) != 0) {
            int legacy0 = Legacy(Make(flags, x, y, 0, buttonChange));
            Trace = "L0=" + legacy0;
            if (legacy0 > 0) { picked = true; backend = 1; useId = 0; return 1; }
            int synth0 = Synth(Make(flags, x, y, 0, buttonChange));
            Trace += " S0=" + synth0;
            if (synth0 > 0) { picked = true; backend = 2; useId = 0; return 1; }
            int legacy1 = Legacy(Make(flags, x, y, 1, buttonChange));
            Trace += " L1=" + legacy1;
            if (legacy1 > 0) { picked = true; backend = 1; useId = 1; return 1; }
            int synth1 = Synth(Make(flags, x, y, 1, buttonChange));
            Trace += " S1=" + synth1;
            if (synth1 > 0) { picked = true; backend = 2; useId = 1; return 1; }
            return synth1;
        }
        CONTACT contact = Make(flags, x, y, picked ? useId : 0, buttonChange);
        int result = backend == 2 ? Synth(contact) : Legacy(contact);
        Trace = (backend == 2 ? "S" : "L") + (picked ? useId.ToString() : "?") + "=" + result;
        return result;
    }
    public static int[] GetMouse() {
        int[] v = new int[3];
        if (!SpiArr(0x0003, 0, v, 0)) return null;
        return v;
    }
    public static int SetMouse(int a, int b, int c) {
        int[] v = new int[3];
        v[0] = a; v[1] = b; v[2] = c;
        if (!SpiArr(0x0004, 0, v, 0)) return -Marshal.GetLastWin32Error();
        return 1;
    }
    public static int GetSpeed() {
        int speed = 0;
        if (!SpiInt(0x0070, 0, ref speed, 0)) return -Marshal.GetLastWin32Error();
        return speed;
    }
    public static int SetSpeed(int speed) {
        if (!SpiPtr(0x0071, (uint)speed, IntPtr.Zero, 0)) return -Marshal.GetLastWin32Error();
        return 1;
    }
}
'@
}

function Send-TutorTouchDrag([int]$sx1, [int]$sy1, [int]$sx2, [int]$sy2) {
    $size = 0
    $ready = 0
    try {
        $size = [ArmTouch]::Bytes()
        $ready = [ArmTouch]::Init()
    } catch {
        Write-Host "TOUCHINIT fail $($_.Exception.Message)"
        return $false
    }
    Write-Host "TOUCHINIT size=$size ptr=$([IntPtr]::Size) ready=$ready layout=$([ArmTouch]::Layout())"
    if ([IntPtr]::Size -ne 8 -or $size -ne 144 -or $ready -le 0) { return $false }
    $down = [ArmTouch]::Send([ArmTouch]::Down, $sx1, $sy1, 0)
    $downTrace = [ArmTouch]::Trace
    $hold = 0
    $move = 0
    $up = 0
    $ux = $sx1
    $uy = $sy1
    $still = ($sx1 -eq $sx2 -and $sy1 -eq $sy2)
    try {
        if ($down -gt 0) {
            foreach ($i in 1..4) {
                Start-Sleep -Milliseconds 180
                $hold = [ArmTouch]::Send([ArmTouch]::Update, $sx1, $sy1, 0)
                if ($hold -le 0) { break }
            }
            if ($hold -gt 0 -and $still) {
                $move = $hold
            } elseif ($hold -gt 0) {
                foreach ($i in 1..12) {
                    $x = [int]($sx1 + ($sx2 - $sx1) * $i / 12)
                    $y = [int]($sy1 + ($sy2 - $sy1) * $i / 12)
                    $move = [ArmTouch]::Send([ArmTouch]::Update, $x, $y, 0)
                    if ($move -le 0) { break }
                    $ux = $x
                    $uy = $y
                    Start-Sleep -Milliseconds 28
                }
            }
        }
    } finally {
        if ($down -gt 0) {
            # UP must repeat the last UPDATE pixel or the injection returns 87.
            $up = [ArmTouch]::Send([ArmTouch]::Up, $ux, $uy, 0)
        }
    }
    Write-Host "TOUCHDRAG down=$down hold=$hold move=$move up=$up still=$still trace=$downTrace holdtrace=$([ArmTouch]::Trace)"
    return ($down -gt 0 -and $hold -gt 0 -and $move -gt 0 -and $up -gt 0)
}

function Measure-TutorMickey {
    $origin = Get-TutorCursor
    $sent = [Arm.Drag]::Send(0x0001, 8, 0)
    Start-Sleep -Milliseconds 40
    $now = Get-TutorCursor
    $dx = $now.X - $origin.X
    [void](Send-ArmAbsolute $origin.X $origin.Y)
    Start-Sleep -Milliseconds 40
    if ($sent -le 0 -or $dx -eq 0) { return $null }
    return [double]($dx / 8.0)
}

function Move-TutorByMickey([int]$tx, [int]$ty, [double]$scale, [int]$maxSteps) {
    $steps = 0
    if ($scale -le 0) { $scale = 1.0 }
    while ($steps -lt $maxSteps) {
        $c = Get-TutorCursor
        $dx = $tx - $c.X
        $dy = $ty - $c.Y
        if ([Math]::Abs($dx) -le 2 -and [Math]::Abs($dy) -le 2) { break }
        $px = $dx
        $py = $dy
        if ($px -gt 16) { $px = 16 } elseif ($px -lt -16) { $px = -16 }
        if ($py -gt 16) { $py = 16 } elseif ($py -lt -16) { $py = -16 }
        $mx = [int][Math]::Round($px / $scale)
        $my = [int][Math]::Round($py / $scale)
        if ($mx -eq 0 -and $dx -ne 0) { $mx = $(if ($dx -gt 0) { 1 } else { -1 }) }
        if ($my -eq 0 -and $dy -ne 0) { $my = $(if ($dy -gt 0) { 1 } else { -1 }) }
        $sent = [Arm.Drag]::Send(0x2001, $mx, $my)
        if ($sent -le 0) { break }
        $steps++
        Start-Sleep -Milliseconds 35
    }
    return $steps
}

function Send-TutorClientMove([IntPtr]$hwnd, [int]$screenX, [int]$screenY, [int]$buttons) {
    $p = New-Object 'Arm.Drag+POINT'
    $p.X = $screenX
    $p.Y = $screenY
    if (-not [Arm.Drag]::ScreenToClient($hwnd, [ref]$p)) { return $false }
    # Queued, like a real move. SendMessage was delivered and Compose still saw a click.
    return [bool][Arm.Drag]::PostMessage($hwnd, 0x0200, [IntPtr]$buttons, [Arm.Drag]::Pack($p.X, $p.Y))
}

function Send-TutorRelativeDrag([int]$sx1, [int]$sy1, [int]$sx2, [int]$sy2) {
    # Click-At waits after SetCursorPos because Compose dispatches the press at the
    # last point it processed. Absolute SendInput updated the cursor and still did
    # not become a selection. Press with the same mouse_event, then walk with
    # relative SendInput and a queued move so the button-down stream has positions.
    [WD]::SetCursorPos($sx1 - 28, $sy1 + 18) | Out-Null
    Start-Sleep -Milliseconds 80
    [WD]::SetCursorPos($sx1, $sy1) | Out-Null
    Start-Sleep -Milliseconds 250
    $hwnd = [IntPtr]::Zero
    $hit = ''
    try {
        $p = New-Object 'Arm.Drag+POINT'
        $p.X = $sx1
        $p.Y = $sy1
        $hwnd = [Arm.Drag]::WindowFromPoint($p)
        $cls = New-Object System.Text.StringBuilder 128
        [void][Arm.Drag]::GetClassName($hwnd, $cls, $cls.Capacity)
        $hit = "$($cls.ToString()) $hwnd"
    } catch {
        $hit = "fail $($_.Exception.Message)"
    }
    $at = Get-TutorCursor
    Write-Host "DRAGHIT $hit at=$($at.X),$($at.Y) want=$sx1,$sy1"
    $posted = 0
    if ($hwnd -ne [IntPtr]::Zero) {
        try { if (Send-TutorClientMove $hwnd $sx1 $sy1 0) { $posted++ } } catch { }
    }
    Start-Sleep -Milliseconds 80
    [WD]::mouse_event(2, 0, 0, 0, [IntPtr]::Zero)
    $steps = 0
    try {
        Start-Sleep -Milliseconds 90
        $steps = Move-TutorByMickey $sx2 $sy2 1.0 40
        if ($hwnd -ne [IntPtr]::Zero) {
            $cursor = Get-TutorCursor
            try { if (Send-TutorClientMove $hwnd $cursor.X $cursor.Y 1) { $posted++ } } catch { }
            try { if (Send-TutorClientMove $hwnd $sx2 $sy2 1) { $posted++ } } catch { }
        }
        Start-Sleep -Milliseconds 120
    } finally {
        [WD]::mouse_event(4, 0, 0, 0, [IntPtr]::Zero)
    }
    $end = Get-TutorCursor
    Write-Output "RELDRAG steps=$steps posted=$posted end=$($end.X),$($end.Y) target=$sx2,$sy2"
}

function Send-TutorAgent([string]$Command) {
    $portFile = $env:COACH_DRAG_PORT_FILE
    if (-not $portFile -or -not (Test-Path -LiteralPath $portFile)) { return 'ERR agent-port-missing' }
    $port = 0
    $raw = (Get-Content -Raw -LiteralPath $portFile).Trim()
    if (-not [int]::TryParse($raw, [ref]$port)) { return "ERR bad-port $raw" }
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $client.Connect('127.0.0.1', $port)
        $stream = $client.GetStream()
        $stream.ReadTimeout = 8000
        $stream.WriteTimeout = 8000
        $bytes = [Text.Encoding]::ASCII.GetBytes("$Command`n")
        $stream.Write($bytes, 0, $bytes.Length)
        $reader = New-Object System.IO.StreamReader($stream, [Text.Encoding]::ASCII)
        $line = $reader.ReadLine()
        if (-not $line) { return 'ERR empty-reply' }
        return $line
    } catch {
        return "ERR $($_.Exception.Message)"
    } finally {
        $client.Close()
    }
}

function Drag-TutorLine([int]$x1, [int]$y1, [int]$x2, [int]$y2, [string]$how) {
    # Cursor injection lands on SunAwtCanvas and Compose still sees a click.
    # On the ARM runner the agent posts MouseEvents to the listener a real drag uses.
    Invoke-ForegroundStep -InjectsInput -Action {
        $r = Get-Rect
        $sx1 = $r.L + $x1; $sy1 = $r.T + $y1
        $sx2 = $r.L + $x2; $sy2 = $r.T + $y2
        $portFile = $env:COACH_DRAG_PORT_FILE
        if ($portFile -and (Test-Path -LiteralPath $portFile)) {
            $reply = Send-TutorAgent "DRAG $sx1 $sy1 $sx2 $sy2"
            Write-Host "AGENTDRAG $how $reply"
            if ($reply -notlike 'OK*') { throw "Selection drag was not delivered ($reply)" }
            return
        }
        $touched = $false
        if ($how -ne 'relative' -and $how -ne 'awt') { $touched = [bool](Send-TutorTouchDrag $sx1 $sy1 $sx2 $sy2) }
        Write-Host "TOUCHUSED $touched"
        if (-not $touched) { Send-TutorRelativeDrag $sx1 $sy1 $sx2 $sy2 }
    }
}

function Hover-TutorEdge([int]$windowY) {
    $rect = Get-Rect
    $x = [int]($rect.L + (($rect.R - $rect.L) / 2))
    $y = [int]($rect.T + $windowY)
    Invoke-ForegroundStep -InjectsInput -Action {
        $away = Move-TutorPointer $x ($y + 28) $true
        Start-Sleep -Milliseconds 90
        $landed = Move-TutorPointer $x $y $true
        # Host stream only. A success-stream line becomes part of Get-TutorShownPage's
        # return, and the page check then reads the trace as the page number.
        Write-Host "HOVER $windowY -> $($landed.X),$($landed.Y) via $($away.X),$($away.Y)"
    }
}

# The app keeps COACH_LIBRARY_DIR as the legacy root and writes the open book under
# its sibling "<name>-learning-profiles/<session>/library.sqlite".
function Resolve-InstalledLibrary([string]$Configured) {
    $dir = Split-Path -Parent $Configured
    $profiles = Join-Path (Split-Path -Parent $dir) ((Split-Path -Leaf $dir) + '-learning-profiles')
    $hits = @()
    if (Test-Path -LiteralPath $profiles) {
        $hits = @(Get-ChildItem -LiteralPath $profiles -Filter library.sqlite -Recurse -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Directory.Name -like 'local-*' })
    }
    if ($hits.Count -ge 1) {
        return ($hits | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
    }
    return $Configured
}

function Get-PersistedReaderPage {
    $db = Resolve-InstalledLibrary (Join-Path $env:COACH_LIBRARY_DIR 'library.sqlite')
    $py = Join-Path $OutDir 'read-page.py'
    if (-not (Test-Path -LiteralPath $py)) {
        Set-Content -LiteralPath $py -Encoding ASCII -Value "import sqlite3, sys`nrow = sqlite3.connect(sys.argv[1]).execute('select reading_page from books').fetchone()`nprint('' if row is None else row[0])`n"
    }
    $raw = & python $py $db
    if ($LASTEXITCODE -ne 0) { return $null }
    $text = "$raw".Trim()
    if ($text -match '^\d+$') { return $text }
    return $null
}

function Get-ShownPageToken($value) {
    foreach ($item in @($value)) {
        $text = "$item".Trim()
        if ($text -match '^\d+$') { return $text }
    }
    return $null
}

function Get-CitePageNumber([string]$CiteText) {
    $m = [regex]::Match(($CiteText -replace '\s', ''), '第(\d+)[页頁贞貞虫]')
    if ($m.Success) { return $m.Groups[1].Value }
    return $null
}

function Get-TutorShownPage([string]$name) {
    $rect = Get-Rect
    $height = [int]($rect.B - $rect.T)
    Hover-TutorEdge ([int]($height - 18))
    Start-Sleep -Milliseconds 400
    $lines = @(Read-TutorFrame $name)
    $floor = [int]($height * 0.72)
    $parts = foreach ($line in $lines) {
        $low = $false
        foreach ($word in @($line.Words)) {
            if ($word.BoundingRect -and [double]$word.BoundingRect.Y -ge $floor) { $low = $true; break }
        }
        if ($low) { $line.Text }
    }
    $compact = (($parts -join '') -replace '\s', '')
    $m = [regex]::Match($compact, '第(\d{1,4})/')
    if (-not $m.Success) { $m = [regex]::Match($compact, '第(\d{1,4})页') }
    if (-not $m.Success) {
        # The closed bar is 「第 2 / 3 页」. chi_sim keeps 「第2」 and 「页」 and
        # drops the slash, so they are not adjacent. Read that pair only from
        # the bottom strip, where the page body cannot supply another 第N.
        $barFloor = [Math]::Max(0, [int]($height - 80))
        $bar = foreach ($line in $lines) {
            $inBar = $false
            foreach ($word in @($line.Words)) {
                if ($word.BoundingRect -and [double]$word.BoundingRect.Y -ge $barFloor) { $inBar = $true; break }
            }
            if ($inBar) { $line.Text }
        }
        $barCompact = (($bar -join '') -replace '\s', '')
        if ($barCompact -match '页') {
            $m = [regex]::Match($barCompact, '第(\d{1,4})')
            if ($m.Success) { Write-Host "PAGEBAR $barCompact" }
        }
    }
    if ($m.Success) { return $m.Groups[1].Value }
    return $null
}

function Wait-TutorText([string]$name, [string[]]$labels, [int]$seconds, [int]$scroll = -2) {
    $deadline = (Get-Date).AddSeconds($seconds)
    $spins = 0
    do {
        $frame = @(Read-TutorFrame $name)
        foreach ($label in $labels) {
            $hit = Find-Text $frame $label
            if ($hit) { return $hit }
        }
        if ($scroll -and $spins -lt 8) { Scroll-TutorView $scroll; $spins++ }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    return $null
}

# The selection toolbar draws its four actions in one row; take the one left of a known neighbour.
function Find-OnTutorRow($lines, [string]$needle, $ref) {
    if (-not $ref) { return $null }
    $best = $null
    foreach ($l in $lines) {
        $m = Find-Text @($l) $needle
        if ($m -and [Math]::Abs($m.Y - $ref.Y) -le 16 -and (-not $best -or $m.X -lt $best.X)) { $best = $m }
    }
    return $best
}

function Focus-ReaderPage {
    $rect = Get-Rect
    Click-At ([int](($rect.R - $rect.L) / 2)) ([int](($rect.B - $rect.T) / 2)) | Out-Null
    Start-Sleep -Milliseconds 300
}

function Test-ReaderTextView {
    $db = Resolve-InstalledLibrary (Join-Path $env:COACH_LIBRARY_DIR 'library.sqlite')
    $py = Join-Path $OutDir 'read-view.py'
    if (-not (Test-Path -LiteralPath $py)) {
        Set-Content -LiteralPath $py -Encoding ASCII -Value "import sqlite3, sys`nrow = sqlite3.connect(sys.argv[1]).execute('select text_view from book_reader_views').fetchone()`nprint('' if row is None else row[0])`n"
    }
    $raw = & python $py $db
    return ("$raw".Trim() -eq '1')
}

function Find-HighestText($lines, [string]$needle) {
    $best = $null
    foreach ($line in @($lines)) {
        $hit = Find-Text @($line) $needle
        if ($hit -and (-not $best -or $hit.Y -lt $best.Y)) { $best = $hit }
    }
    return $best
}

function Find-TextBeside($lines, [string]$needle, $ref) {
    if (-not $ref) { return $null }
    foreach ($line in @($lines)) {
        $hit = Find-Text @($line) $needle
        if ($hit -and [Math]::Abs($hit.Y - $ref.Y) -le 18 -and $hit.X -gt ($ref.X + 8)) { return $hit }
    }
    return $null
}

# OCR a captured window. Coordinates stay in that bitmap.
function Read-PngLines([string]$png) {
    if ($Winbox) { return @(Read-Screen $png) }
    $src = [Drawing.Bitmap]::new($png)
    $bmp = [Drawing.Bitmap]::new($src.Width * 3, $src.Height * 3, [Drawing.Imaging.PixelFormat]::Format24bppRgb)
    try {
        $g = [Drawing.Graphics]::FromImage($bmp)
        try {
            $g.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $g.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::Half
            $g.DrawImage($src, 0, 0, $bmp.Width, $bmp.Height)
        } finally { $g.Dispose() }
        $file = Join-Path $tutorPrivate 'screen.bmp'
        $bmp.Save($file, [Drawing.Imaging.ImageFormat]::Bmp)
    } finally { $bmp.Dispose(); $src.Dispose() }
    $json = & python (Join-Path $PSScriptRoot 'arm-screen-text.py') $file $tutorPrivate
    if ($LASTEXITCODE -ne 0) { throw 'Window text recognition failed' }
    $rows = $json | ConvertFrom-Json
    foreach ($group in ($rows | Group-Object -Property { $_[0] -join '-' })) {
        $words = foreach ($w in $group.Group) {
            [pscustomobject]@{Text=$w[1]; BoundingRect=[pscustomobject]@{X=$w[2]/3; Y=$w[3]/3; Width=$w[4]/3; Height=$w[5]/3}}
        }
        [pscustomobject]@{Text=(($words | ForEach-Object Text) -join ''); Words=@($words)}
    }
}

function Get-AppMenuWindows {
    $script:ViewMenus = New-Object System.Collections.Generic.List[IntPtr]
    [WD]::EnumWindows({ param($x, $l)
        $p = 0
        [WD]::GetWindowThreadProcessId($x, [ref]$p) | Out-Null
        if ($p -eq $script:AppPid -and [WD]::IsWindowVisible($x) -and $x -ne $script:Win -and -not [WD]::IsCloaked($x)) {
            $rect = New-Object WD+RECT
            if ([WD]::GetWindowRect($x, [ref]$rect)) {
                $w = $rect.R - $rect.L; $h = $rect.B - $rect.T
                if ($w -ge 80 -and $w -le 480 -and $h -ge 40 -and $h -le 480) { $script:ViewMenus.Add($x) }
            }
        }
        return $true }, [IntPtr]::Zero) | Out-Null
    return @($script:ViewMenus)
}

# ⋯ sits 50px in from the window's right edge and 54px down on the 976x672 runner
# (title bar, then the 44px bar). 伴读's glyph centre is 64px left of those dots.
function Get-MoreMenuPoint($hud) {
    $rect = Get-Rect
    $point = [pscustomobject]@{ X = [int](($rect.R - $rect.L) - 50); Y = 54 }
    $companion = $null
    foreach ($line in @($hud)) {
        if (($line.Text -replace '\s','') -eq '伴读') { $companion = Find-Text @($line) '伴读'; break }
    }
    if (-not $companion) { $companion = Find-HighestText $hud '伴读' }
    if ($companion) {
        $beside = [pscustomobject]@{ X = [int]($companion.X + 64); Y = [int]$companion.Y }
        Write-Host "VIEW more beside 伴读 $($beside.X),$($beside.Y) fallback $($point.X),$($point.Y)"
        return @($beside, $point)
    }
    Write-Host "VIEW more at $($point.X),$($point.Y)"
    return @($point)
}

function Find-ViewTextPoint([string]$name, $more) {
    $frames = @()
    $main = @(Read-TutorFrame $name)
    $frames += ,([pscustomobject]@{ Lines = $main; Dx = 0; Dy = 0 })
    $mainRect = Get-Rect
    foreach ($popup in @(Get-AppMenuWindows)) {
        $shot = "$name-popup.png"
        Save-OwnWindow $shot $popup
        $popupRect = New-Object WD+RECT
        [void][WD]::GetWindowRect($popup, [ref]$popupRect)
        $frames += ,([pscustomobject]@{
            Lines = @(Read-PngLines (Join-Path $OutDir $shot))
            Dx = [int]($popupRect.L - $mainRect.L)
            Dy = [int]($popupRect.T - $mainRect.T)
        })
        Write-Host "VIEW popup $name origin $($popupRect.L - $mainRect.L),$($popupRect.T - $mainRect.T)"
    }
    foreach ($frame in $frames) {
        $original = Find-HighestText $frame.Lines '原页'
        if (-not $original) { continue }
        $text = Find-TextBeside $frame.Lines '文字' $original
        if (-not $text) {
            $text = [pscustomobject]@{ X = [int]($original.X + 48); Y = [int]$original.Y }
            Write-Host "VIEW text beside 原页 $($text.X),$($text.Y)"
        }
        return [pscustomobject]@{ X = [int]($text.X + $frame.Dx); Y = [int]($text.Y + $frame.Dy) }
    }
    return $null
}

# The scan stays on the original page. Smoke needs the text view, so open the
# reader's own ⋯ menu and click 文字. Do not switch the view from product code.
function Switch-ReaderToText {
    if (Test-ReaderTextView) { Write-Output 'VIEW text already'; return }
    $switched = $false
    foreach ($try in 1..3) {
        Hover-TutorEdge 10
        Start-Sleep -Milliseconds 450
        $hud = @(Read-TutorFrame "view-hud$try")
        $points = @(Get-MoreMenuPoint $hud)
        $text = $null
        foreach ($more in $points) {
            Click-At $more.X $more.Y | Out-Null
            Start-Sleep -Milliseconds 500
            $text = Find-ViewTextPoint "view-menu$try" $more
            if ($text) { break }
            Click-At $more.X $more.Y | Out-Null
        }
        if (-not $text) {
            Write-Output "VIEW menu missed 文字 try=$try"
            continue
        }
        Click-At $text.X $text.Y | Out-Null
        foreach ($wait in 1..8) {
            Start-Sleep -Milliseconds 300
            if (Test-ReaderTextView) { $switched = $true; break }
        }
        if ($switched) { break }
        Write-Output "VIEW click did not persist text_view try=$try at $($text.X),$($text.Y)"
    }
    if (-not $switched) { throw '没有点到阅读器里的「文字」，仍停在原页（见 view-menu）' }
    Write-Output 'VIEW text'
}

# 2.0 selection toolbar is 「讲讲 / 拆句 / 举个例子 / 问… / 笔记」.
# It sits at 14dp until the pointer enters the top edge, then drops to 54dp with the HUD.
# Locate 讲讲 only after that move, or the click lands on the page and clears the selection.
function Find-ToolbarAction($lines, [string]$action) {
    $direct = Find-Text $lines $action
    if ($direct) { return $direct }
    $ref = $null
    foreach ($label in @('拆句', '举个例子', '笔记', '讲讲')) {
        $ref = Find-Text $lines $label
        if ($ref) { break }
    }
    if (-not $ref) { return $null }
    $best = $null
    foreach ($line in $lines) {
        foreach ($word in @($line.Words)) {
            $rect = $word.BoundingRect
            if (-not $rect) { continue }
            $cx = [double]$rect.X + ([double]$rect.Width / 2)
            $cy = [double]$rect.Y + ([double]$rect.Height / 2)
            if ([Math]::Abs($cy - $ref.Y) -gt 18) { continue }
            if ($cx -ge ($ref.X1 - 8)) { continue }
            if (-not $best -or $cx -lt $best.X) { $best = [pscustomobject]@{ X = [int]$cx; Y = [int]$cy } }
        }
    }
    return $best
}

function Test-ToolbarUp($lines) {
    foreach ($label in @('举个例子', '笔记', '拆句', '讲讲')) {
        if (Find-Text $lines $label) { return $true }
    }
    return $false
}

function Open-SelectionToolbar([string]$name) {
    $raised = $null
    foreach ($drag in 1..3) {
        [void]@(Read-TutorFrame "$name-body$drag")
        $rows = @(Find-LeftInkRows "$name-body$drag")
        if (@($rows).Count -lt 1) { throw "No body line to drag in $name-body$drag" }
        $row = $rows[[Math]::Min($drag - 1, @($rows).Count - 1)]
        $bounds = Get-Rect
        $width = [int]($bounds.R - $bounds.L)
        $onRight = [int]$row.X1 -gt ($width * 0.50)
        $pageL = [int]($width * $(if ($onRight) { 0.56 } else { 0.16 }))
        $pageR = [int]($width * $(if ($onRight) { 0.84 } else { 0.44 }))
        $x1 = [Math]::Max($pageL, [int]$row.X1 + 8)
        $x2 = [Math]::Min($pageR, [int]$row.X2 - 8)
        if (($x2 - $x1) -lt 70) { throw "Ink line is too short to drag ($name x=$x1..$x2)" }
        $y = [int]$row.Y
        # Windows paged selection reads SelectionContainer from an AWT mouse drag.
        Write-Output "DRAG row $drag how=awt y=$y x=$x1..$x2"
        if ($drag -eq 1 -and $env:COACH_DRAG_PORT_FILE -and (Test-Path -LiteralPath $env:COACH_DRAG_PORT_FILE)) {
            Write-Host (Send-TutorAgent 'PING')
        }
        Drag-TutorLine $x1 $y $x2 $y 'awt'
        foreach ($spin in 1..4) {
            Start-Sleep -Milliseconds 400
            $shot = @(Read-TutorFrame "$name-selected$drag-$spin")
            if (Test-ToolbarUp $shot) { $raised = $shot; break }
        }
        if ($raised) { break }
    }
    if (-not $raised) { throw "Dragging a line did not raise the selection toolbar ($name)" }
    # Pin the HUD first. The pill moves down with it; a click aimed at the old frame misses.
    Hover-TutorEdge 10
    Start-Sleep -Milliseconds 500
    $pinned = @(Read-TutorFrame "$name-pinned")
    $button = Find-ToolbarAction $pinned '讲讲'
    if (-not $button) { throw "选择工具条上没有「讲讲」($name-pinned)" }
    Click-At $button.X $button.Y | Out-Null
    Write-Output "CLICK 讲讲 $($button.X),$($button.Y)"
}

function Test-BlockAndCitation {
    # 2.0 dropped the per-book consent wall. 「讲讲」 explains immediately unless a
    # consent label is actually on screen. Do not click 出处 here: that link belongs
    # to the answer, after the question.
    Open-SelectionToolbar '04'
    $reply = $null
    $deadline = (Get-Date).AddSeconds(30)
    $spins = 0
    do {
        $lines = @(Read-TutorFrame '04-after-explain')
        $reply = Find-Text $lines 'Blockexplanation'
        if (-not $reply) { $reply = Find-Text $lines '对照原文' }
        if (-not $reply) { $reply = Find-Text $lines '独立回答' }
        if (-not $reply) { $reply = Find-Text $lines '出处' }
        if ($reply) { break }
        $consent = Find-Text $lines '允许并讲解'
        if (-not $consent) { $consent = Find-Text $lines '开启讲解' }
        if ($consent) { Click-At $consent.X $consent.Y | Out-Null }
        elseif ($spins -lt 4) { Scroll-TutorView 3 }
        if (-not $consent) { $spins++ }
        Start-Sleep -Milliseconds 700
    } while ((Get-Date) -lt $deadline)
    if (-not $reply) { throw '讲解没有出现（Block explanation）' }
    Write-Output 'STEP4 explain visible'
}

function Get-LineCenterY($line) {
    $sum = 0.0
    $n = 0
    foreach ($w in @($line.Words)) {
        $rect = $w.BoundingRect
        if (-not $rect) { continue }
        $sum += [double]$rect.Y + ([double]$rect.Height / 2)
        $n++
    }
    if ($n -eq 0) { return $null }
    return [int]($sum / $n)
}

function Find-AnswerCite($lines, [int]$answerY) {
    # chi_sim splits one citation chip. Run 45844270111 read 「出处1」 on its own
    # line and 「第2页 · 第6段」 on the next, so a page read from the 出处 line
    # alone is empty. The page still has to be 第N页 on that chip. A bare index
    # after 出处 is not a page, and a page from another control does not count.
    $rows = @($lines)
    $best = $null
    for ($i = 0; $i -lt $rows.Count; $i++) {
        $line = $rows[$i]
        $compact = ($line.Text -replace '\s', '')
        if ($compact -notmatch '出处') { continue }
        $hit = Find-Text @($line) '出处'
        if (-not $hit) { continue }
        $joined = [string]$line.Text
        $page = Get-CitePageNumber $joined
        if (-not $page) {
            $originY = Get-LineCenterY $line
            if (-not $originY) { $originY = $hit.Y }
            $cursorY = $originY
            $last = [Math]::Min($i + 3, $rows.Count - 1)
            for ($j = $i + 1; $j -le $last; $j++) {
                $nextY = Get-LineCenterY $rows[$j]
                if ($nextY -and ($nextY -lt ($cursorY - 6) -or ($nextY - $originY) -gt 72)) { break }
                $piece = ($rows[$j].Text -replace '\s', '')
                if ($piece -match '先输入问题|问这句|本节|已读书目|读书目|讲解|提问|批注|发送') { break }
                if ($piece -notmatch '第|段|概率|“|”') { break }
                $joined = $joined + $rows[$j].Text
                $page = Get-CitePageNumber $joined
                if ($nextY) { $cursorY = $nextY }
                if ($page) { break }
            }
        }
        $score = 0
        if ($page) { $score += 10 }
        if ($hit.Y -ge ($answerY - 8)) { $score += 5 }
        if (-not $best -or $score -gt $best.Score -or ($score -eq $best.Score -and $hit.Y -gt $best.Hit.Y)) {
            $best = [pscustomobject]@{ Hit = $hit; Page = $page; Text = $joined; Score = $score }
        }
    }
    return $best
}

function Close-TutorDrawer {
    $rect = Get-Rect
    $winH = [int]($rect.B - $rect.T)
    foreach ($try in 1..4) {
        Hover-TutorEdge 12
        Start-Sleep -Milliseconds 300
        $lines = @(Read-TutorFrame "07-close$try")
        $drawer = (Find-Text $lines '提问') -or (Find-Text $lines '批注') -or (Find-Text $lines '引文')
        if (-not $drawer) { return }
        $fold = Find-Text $lines '收起'
        if ($fold -and $fold.Y -lt [int]($winH * 0.25)) {
            Click-At $fold.X $fold.Y | Out-Null
        } else {
            $tab = Find-Text $lines '讲解'
            if ($tab) { Click-At $tab.X $tab.Y | Out-Null; Start-Sleep -Milliseconds 500 }
            Send-Keys 'l'
        }
        Start-Sleep -Milliseconds 800
    }
    $left = @(Read-TutorFrame '07-close-left')
    if ((Find-Text $left '提问') -or (Find-Text $left '引文')) {
        throw '收起伴读后抽屉还在（见 07-close-left）'
    }
}

function Assert-CitationPage([string]$citePage, [string]$citeText, [string]$beforePage) {
    if (-not $citePage) {
        $said = ($citeText -replace '\s', '')
        if (-not $said) { $said = '这一帧没读到出处那一行' }
        throw "回答的「出处」没解析出页码（读到：$said）"
    }
    $shown = $null
    $persisted = $null
    $notice = $false
    foreach ($try in 1..3) {
        Start-Sleep -Milliseconds 700
        $frame = @(Read-TutorFrame $(if ($try -eq 1) { '07-cite' } else { "07-cite$try" }))
        # chi_sim drops the leading 「无」, so the toast 「无法确认原句：无法读取这一页的文字位置。」
        # comes back as 「法确认原句:无法读取…」. Either fragment is that toast.
        foreach ($label in @('无法确认原句', '无法确认', '确认原句', '无法读取', '正在加载')) {
            if (Find-Text $frame $label) {
                $notice = $true
                Write-Output "CITATION_NOTICE $label try=$try"
                break
            }
        }
        $persisted = Get-PersistedReaderPage
        $shown = Get-ShownPageToken (Get-TutorShownPage "07-bar$try")
        Write-Output "STEP7 open try=$try cite=$citePage shown=$shown persisted=$persisted before=$beforePage notice=$notice"
        if ($shown -and $shown -ne $citePage) {
            throw "点了出处没停在第 $citePage 页（底栏第 $shown 页，见 07-cite）"
        }
        if ($persisted -and $persisted -ne $citePage) {
            throw "点了出处没停在第 $citePage 页（阅读位置第 $persisted 页，见 07-cite）"
        }
        $landed = ($shown -eq $citePage) -or ($persisted -eq $citePage)
        $moved = $beforePage -and $persisted -and ($beforePage -ne $persisted)
        # This fixture is a scan with no PDF text layer. A real jump onto the
        # page that was already open switches to the original and says it cannot
        # mark the sentence. Staying on the same page with no notice means the
        # click did not run.
        if ($landed -and ($moved -or $notice -or ($beforePage -and $beforePage -ne $citePage))) { break }
    }
    if ($beforePage -eq $citePage -and -not $notice -and $persisted -eq $citePage) {
        throw "出处写的就是当前第 $citePage 页，但点击后没有进入原页（见 07-cite）"
    }
    Close-TutorDrawer
    $persisted = Get-PersistedReaderPage
    $shown = $null
    foreach ($try in 1..4) {
        $shown = Get-ShownPageToken (Get-TutorShownPage "07-card$try")
        if ($shown) { break }
    }
    Write-Output "STEP7 closed cite=$citePage shown=$shown persisted=$persisted"
    if ($persisted -ne $citePage) {
        throw "收起伴读后阅读位置不是出处写的第 $citePage 页（现在第 $persisted 页，见 07-card）"
    }
    if (-not $shown) { throw "收起伴读后底栏读不到页号，没法确认还停在第 $citePage 页（见 07-card）" }
    if ($shown -ne $citePage) {
        throw "收起伴读后底栏不是出处写的第 $citePage 页（现在第 $shown 页，见 07-card）"
    }
}

function Test-PassageQuestion {
    $lines = @(Read-TutorFrame '04-question-field')
    $field = $null
    foreach ($label in @('问这句的意思', '想问什么', '先输入问题', '输入问题', '公式推导')) {
        $field = Find-Text $lines $label
        if ($field) { break }
    }
    $bounds = Get-Rect
    if ($field) {
        Click-At $field.X ($field.Y + 8) | Out-Null
        Write-Output 'ASKFIELD ocr'
    } else {
        # The placeholder is on screen and chi_sim misreads it. The box is the wide field
        # left of 发送, just above the window edge (measured on the 976x672 capture).
        Click-At ([int](($bounds.R - $bounds.L) * 0.42)) ([int](($bounds.B - $bounds.T) * 0.93)) | Out-Null
        Write-Output 'ASKFIELD measured'
    }
    Start-Sleep -Milliseconds 300
    $question = 'What does this passage mean?'
    Send-Keys $question
    Send-Keys '{ENTER}'
    # The loopback answer is 「这段先用自己的话讲，再和原文核对。」.
    # kind=ask in the event log is not enough: this sentence has to be on screen.
    # The sentence is painted above its citation. A one-line thread shows only the
    # citation, and wheeling the page (or downward) never brings the sentence in.
    # Wait for the reply to land, then wheel the thread strip upward.
    $answer = $null
    $deadline = (Get-Date).AddSeconds(25)
    $scrolls = 0
    $askedAt = Get-Date
    do {
        $shot = @(Read-TutorFrame '04-answer')
        foreach ($label in @('自己的话', '再和原文核对')) {
            $answer = Find-Text $shot $label
            if ($answer) { break }
        }
        if ($answer) { break }
        $waited = ((Get-Date) - $askedAt).TotalSeconds
        if ($scrolls -lt 6 -and $waited -ge 2) {
            $citeLine = Find-Text $shot '出处'
            $at = Get-Rect
            $y = if ($citeLine) { [int]$citeLine.Y } else { [int](($at.B - $at.T) * 0.62) }
            Scroll-At ([int](($at.R - $at.L) * 0.50)) $y 2
            $scrolls++
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    if (-not $answer) { throw '本题回答没有出现在屏幕上（要看到「自己的话」或「再和原文核对」，见 04-answer）' }
    Write-Output 'STEP4 answer visible'
    $cite = $null
    foreach ($pass in 1..4) {
        $frame = @(Read-TutorFrame "07-answer-cite$pass")
        foreach ($label in @('自己的话', '再和原文核对')) {
            $again = Find-Text $frame $label
            if ($again) { $answer = $again; break }
        }
        $cite = Find-AnswerCite $frame $answer.Y
        if ($cite -and $cite.Page -and $cite.Hit.Y -ge ($answer.Y - 8)) { break }
        # The citation is the line under the sentence. Wheel that strip downward.
        $at = Get-Rect
        Scroll-At ([int](($at.R - $at.L) * 0.50)) ([int]$answer.Y) -2
        Start-Sleep -Milliseconds 400
    }
    if (-not $cite -or -not $cite.Page -or $cite.Hit.Y -lt ($answer.Y - 8)) {
        $said = if ($cite) { $cite.Text } else { '没有读到出处' }
        throw "回答下面没有带页码的「出处」（读到：$said，见 07-answer-cite）"
    }
    Write-Output "STEP7 cite-line $($cite.Text)"
    $beforePage = Get-PersistedReaderPage
    Click-At $cite.Hit.X $cite.Hit.Y | Out-Null
    Assert-CitationPage $cite.Page $cite.Text $beforePage
}

function Test-WholeLesson {
    # 2.0 has no shelf cards 「边看边学」. The lesson is the companion drawer.
    # A page-only drawer calls explainLesson; a chapter drawer calls companion/chapter.
    # 「讲整章」 is the control that leaves page-only mode when the chapter is wider than this page.
    # Escape is the reader back key: it returns to the shelf. 「伴读」 is the drawer toggle.
    $opened = @(Read-TutorFrame '05-hud')
    $btn = Find-Text $opened '伴读'
    if (-not $btn) { $btn = Find-Text $opened '收起' }
    if ($btn) {
        Click-At $btn.X $btn.Y | Out-Null
    } else {
        Send-Keys 'l'
    }
    Start-Sleep -Milliseconds 800
    $opened = @(Read-TutorFrame '05-companion')
    # The question step leaves the drawer on 提问. The chapter lesson is the 讲解 tab.
    $explainTab = Find-Text $opened '讲解'
    if ($explainTab) {
        Click-At $explainTab.X $explainTab.Y | Out-Null
        Start-Sleep -Milliseconds 800
        $opened = @(Read-TutorFrame '05-companion-tab')
    }
    $wider = Find-Text $opened '讲整章'
    if ($wider) { Click-At $wider.X $wider.Y | Out-Null }
    # The pack's core question sits under the module header. On a short bottom sheet
    # it starts just below the fold, so wheel the sheet until the sentence is painted.
    $hit = Wait-TutorText '05-lesson' @('Lessonoverview', '先阅读原文') 45 -2
    if (-not $hit) {
        $fail = Find-Text @(Read-TutorFrame '05-lesson-fail') '连不上'
        if ($fail) { throw '本章伴读连不上（见 05-lesson）' }
        throw '本章讲解没有出现'
    }
    $null = Find-TutorLabel '05-lesson-source' @('出处', '原文第') -3
}

# 「提交」 is a short black pill. On the ARM runner the OCR reads the card titles
# and drops both the white word and, on the later sheet, 「这题是猜的」. The pill is
# the only short near-black run in the lower right of the window capture.
# The empty-shelf import control is a short black pill. ARM OCR reads the light cards
# and misses the white word on that pill (run 45779598059 still showed 导入文件).
function Find-DarkImportPill([string]$name) {
    $path = Join-Path $OutDir "$name.png"
    if (-not (Test-Path -LiteralPath $path)) {
        Write-Host "DARKPILL missing $path"
        return $null
    }
    $bmp = [Drawing.Bitmap]::new($path)
    try {
        $w = $bmp.Width
        $h = $bmp.Height
        $hits = @()
        for ($y = [int]($h * 0.40); $y -lt [int]($h * 0.82); $y += 2) {
            $run = 0
            $runStart = 0
            for ($x = [int]($w * 0.25); $x -lt [int]($w * 0.75); $x++) {
                $c = $bmp.GetPixel($x, $y)
                if ($c.R -lt 50 -and $c.G -lt 50 -and $c.B -lt 50) {
                    if ($run -eq 0) { $runStart = $x }
                    $run++
                } else {
                    if ($run -ge 60 -and $run -le 180) {
                        $hits += ,([pscustomobject]@{ X = [int]($runStart + ($run / 2)); Y = $y })
                    }
                    $run = 0
                }
            }
            if ($run -ge 60 -and $run -le 180) {
                $hits += ,([pscustomobject]@{ X = [int]($runStart + ($run / 2)); Y = $y })
            }
        }
        if (@($hits).Count -lt 6) {
            Write-Host "DARKPILL hits=$(@($hits).Count) ${w}x${h}"
            return $null
        }
        $xs = @($hits | ForEach-Object { $_.X })
        $xmin = ($xs | Measure-Object -Minimum).Minimum
        $xmax = ($xs | Measure-Object -Maximum).Maximum
        if (($xmax - $xmin) -gt 40) {
            Write-Host "DARKPILL spread=$($xmax - $xmin) hits=$(@($hits).Count)"
            return $null
        }
        $ys = @($hits | ForEach-Object { $_.Y })
        return [pscustomobject]@{
            X = [int](($xs | Measure-Object -Average).Average)
            Y = [int](($ys | Measure-Object -Average).Average)
        }
    } finally { $bmp.Dispose() }
}

function Find-SubmitPill([string]$name) {
    $path = Join-Path $OutDir "$name.png"
    if (-not (Test-Path $path)) { return $null }
    $bmp = [Drawing.Bitmap]::new($path)
    try {
        $w = $bmp.Width
        $h = $bmp.Height
        $xs = @()
        $ys = @()
        for ($y = [int]($h * 0.55); $y -lt ($h - 8); $y += 2) {
            $run = 0
            $runStart = 0
            for ($x = [int]($w * 0.7); $x -lt $w; $x++) {
                $c = $bmp.GetPixel($x, $y)
                $dark = ($c.R -lt 45 -and $c.G -lt 45 -and $c.B -lt 45)
                if ($dark) {
                    if ($run -eq 0) { $runStart = $x }
                    $run++
                } else {
                    if ($run -ge 40 -and $run -le 90 -and ($runStart + $run) -lt ($w - 8)) {
                        $xs += ($runStart + ($run / 2))
                        $ys += $y
                    }
                    $run = 0
                }
            }
        }
        if (@($ys).Count -lt 6) { return $null }
        return [pscustomobject]@{
            X = [int](($xs | Measure-Object -Average).Average)
            Y = [int](($ys | Measure-Object -Average).Average)
        }
    } finally { $bmp.Dispose() }
}

function Test-Practice {
    # 2.0 grades the pretest locally from the companion pack. The pack itself is the
    # loopback request that carries the question, the option, and the feedback.
    $lines = @(Read-TutorFrame '06-open')
    $tab = Find-Text $lines '自测'
    if (-not $tab) { $tab = Find-Text $lines '读前自测' }
    # ARM OCR reads the light card titles and misreads 「自测」 (it came back as 「自疯」).
    # The module strip is 要点 / 自测 / 推导. 「推导」 is stable; the pill to its left is 自测.
    if (-not $tab) {
        $derive = Find-Text $lines '推导'
        if ($derive) { $tab = [pscustomobject]@{ X = [int]($derive.X - 52); Y = [int]$derive.Y } }
    }
    if (-not $tab) {
        $points = Find-Text $lines '要点'
        if ($points) { $tab = [pscustomobject]@{ X = [int]($points.X + 52); Y = [int]$points.Y } }
    }
    if (-not $tab) { throw '伴读里没有读前自测' }
    Click-At $tab.X $tab.Y | Out-Null
    # The lesson step wheels this short sheet downward. The pretest then opens
    # mid-card: the options are painted and 「Which method checks recall?」 sits
    # just above them. Wheel back up until that stem is on screen, and stop at
    # the module top so another wheel cannot push the stem off. The options
    # alone are not this question.
    $stem = $null
    $deadline = (Get-Date).AddSeconds(30)
    $scrolls = 0
    do {
        $frame = @(Read-TutorFrame '06-question')
        foreach ($label in @('Whichmethod', 'checksrecall')) {
            $stem = Find-Text $frame $label
            if ($stem) { break }
        }
        if ($stem) { break }
        $atTop = (Find-Text $frame '先猜一猜') -or (Find-Text $frame '不计错题') -or (Find-Text $frame '第1/')
        if (-not $atTop -and $scrolls -lt 6) {
            Scroll-TutorView 1
            $scrolls++
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    if (-not $stem) {
        $fail = Find-Text @(Read-TutorFrame '06-question-fail') '连不上'
        if ($fail) { throw '读前自测连不上（见 06-question）' }
        throw '读前自测题没有出现'
    }
    $frame = @(Read-TutorFrame '06-options')
    $opt = Find-Text $frame 'Recallbefore'
    if (-not $opt) { $opt = Find-Text $frame 'beforechecking' }
    if (-not $opt) {
        Scroll-TutorView -2
        $frame = @(Read-TutorFrame '06-options-down')
        $opt = Find-Text $frame 'Recallbefore'
        if (-not $opt) { $opt = Find-Text $frame 'beforechecking' }
    }
    if (-not $opt) { throw '读前自测没有选项 Recall before checking' }
    Click-At $opt.X $opt.Y | Out-Null
    Start-Sleep -Milliseconds 400
    # The multiple-choice action is 「核对」, a black pill. OCR drops it. Wheel the
    # sheet body (a point on the page scrollbar does not move this sheet) until the
    # pill is in the capture, then click that.
    $submit = $null
    foreach ($pass in 1..4) {
        $shot = if ($pass -eq 1) { '06-selected' } else { "06-selected-$pass" }
        $frame = @(Read-TutorFrame $shot)
        $submit = Find-Text $frame '核对'
        if (-not $submit) { $submit = Find-Text $frame '提交' }
        if (-not $submit) { $submit = Find-SubmitPill $shot }
        if ($submit) { break }
        Scroll-TutorView -3
    }
    if (-not $submit) { throw '读前自测没有提交' }
    Click-At $submit.X $submit.Y | Out-Null
    $fb = Wait-TutorText '06-feedback' @('Practicefeedback') 20 -2
    if (-not $fb) { throw '提交后没有练习解析' }
    $frame = @(Read-TutorFrame '06-feedback-source')
    $src = Find-Text $frame '原文第'
    if (-not $src) { $src = Find-Text $frame '出处' }
    if (-not $src) {
        Scroll-TutorView -2
        $frame = @(Read-TutorFrame '06-feedback-source-down')
        $src = Find-Text $frame '原文第'
        if (-not $src) { $src = Find-Text $frame '出处' }
    }
    if (-not $src) { throw '练习解析没有出处' }
}

function Test-InstalledTutor {
    $errors = @()
    foreach ($scenario in @('Test-BlockAndCitation', 'Test-PassageQuestion', 'Test-WholeLesson', 'Test-Practice')) {
        try { & $scenario; Write-Output "UI_PASS $scenario" }
        catch {
            $errors += "$scenario - $($_.Exception.Message)"
            Write-Output $errors[-1]
        }
    }
    if ($errors) { throw ($errors -join ' | ') }
    $events = @(Get-Content -LiteralPath (Join-Path $OutDir 'tutor-events.jsonl') -Encoding UTF8 | ForEach-Object { $_ | ConvertFrom-Json })
    foreach ($kind in @('explain','ask','lesson','practice')) {
        if ($kind -notin $events.kind) { throw "Missing loopback request: $kind" }
    }
    # Step 4 is an in-process AWT drag into the installed app, not a Windows cursor drag.
    Write-Output 'INSTALLED_LOOPBACK_UI_PASS steps=4(awt-injected),5,6,7'
}
