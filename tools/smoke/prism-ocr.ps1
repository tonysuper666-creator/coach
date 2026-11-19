param(
    [Parameter(Mandatory)][string]$OutDir,
    [Parameter(Mandatory)][string]$Fixture,
    [Parameter(Mandatory)][string]$InstallResult
)
$ErrorActionPreference = 'Stop'
if ($env:GITHUB_ACTIONS -ne 'true' -or $env:PROCESSOR_ARCHITECTURE -ne 'ARM64') {
    throw 'This probe is restricted to a disposable GitHub ARM64 runner.'
}
. (Join-Path $PSScriptRoot 'Win-Drive.ps1')
Add-Type -Path (Join-Path $PSScriptRoot 'InstalledShelf.cs') -ReferencedAssemblies System.Drawing
Add-Type -AssemblyName UIAutomationClient
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class PrismCapture {
    [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr dc, uint flags);
}
'@
New-Item -ItemType Directory -Force $OutDir | Out-Null
$OutDir = (Resolve-Path $OutDir).Path
$Fixture = (Resolve-Path $Fixture).Path
$env:COACH_LIBRARY_DIR = Join-Path $OutDir 'library'
$env:COACH_CLIENT_DIR = Join-Path $OutDir 'client'
$env:COACH_TOKEN_FILE = Join-Path $OutDir 'unused-token'
if (Test-Path $env:COACH_LIBRARY_DIR) { throw 'The scan probe needs a fresh library' }
New-Item -ItemType Directory $env:COACH_LIBRARY_DIR, $env:COACH_CLIENT_DIR | Out-Null
$install = Get-Content -Raw -Encoding UTF8 $InstallResult | ConvertFrom-Json
if (-not $install.installedTo) { throw 'The MSI smoke did not record an install location' }
$exe = Join-Path $install.installedTo 'LearningCoach.exe'
if (-not (Test-Path -LiteralPath $exe)) { throw 'Installed executable missing' }
$before = @(Get-CimInstance Win32_Process -Filter "Name='LearningCoach.exe'" | ForEach-Object { [int]$_.ProcessId })
$app = Start-Process -FilePath $exe -PassThru
$own = @()
function Save-OwnWindow([string]$name) {
    $rect = New-Object WD+RECT
    if (-not [WD]::GetWindowRect($script:Win, [ref]$rect)) { throw 'Cannot get own window bounds' }
    $bmp = New-Object System.Drawing.Bitmap ($rect.R - $rect.L), ($rect.B - $rect.T)
    try {
        $graphics = [System.Drawing.Graphics]::FromImage($bmp)
        try {
            $dc = $graphics.GetHdc()
            try { if (-not [PrismCapture]::PrintWindow($script:Win, $dc, 2)) { throw 'PrintWindow failed' } }
            finally { $graphics.ReleaseHdc($dc) }
        } finally { $graphics.Dispose() }
        $bmp.Save((Join-Path $OutDir $name), [System.Drawing.Imaging.ImageFormat]::Png)
    } finally { $bmp.Dispose() }
}

function Click-CapturedUpload {
    $shot = 'installed-window-printwindow.png'
    $reference = [Drawing.Bitmap]::new((Join-Path $PSScriptRoot 'fixtures/empty-shelf-upload.png'))
    try {
        $deadline = (Get-Date).AddSeconds(25)
        do {
            Save-OwnWindow $shot
            $frame = [Drawing.Bitmap]::new((Join-Path $OutDir $shot))
            try { $point = [InstalledShelf]::FindUpload($frame, $reference) }
            finally { $frame.Dispose() }
            if (-not $point.IsEmpty) {
                $rect = New-Object WD+RECT
                if (-not [WD]::GetWindowRect($script:Win, [ref]$rect)) { throw 'Upload window disappeared' }
                $screen = [Drawing.Point]::new($rect.L + $point.X, $rect.T + $point.Y)
                [InstalledShelf]::ClickUpload($script:Win, $script:AppPid, $screen)
                return
            }
            Start-Sleep -Milliseconds 400
        } while ((Get-Date) -lt $deadline)
        throw 'Could not identify the upload button in the owned shelf screenshot'
    } finally { $reference.Dispose() }
}

function Submit-ScanFile([string]$title) {
    $deadline = (Get-Date).AddSeconds(20)
    do {
        $dialog = Find-AppWindowByTitle $title
        if ($dialog -ne [IntPtr]::Zero) { break }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    if ($dialog -eq [IntPtr]::Zero) { throw 'Click did not open the installed app file picker' }
    $element = [System.Windows.Automation.AutomationElement]::FromHandle($dialog)
    $scope = [System.Windows.Automation.TreeScope]::Descendants
    $idProp = [System.Windows.Automation.AutomationElement]::AutomationIdProperty
    $editType = [System.Windows.Automation.PropertyCondition]::new(
        [System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::Edit)
    # Windows exposes the editable filename inside the cmb13 (1148) combo.
    $edit = $null
    foreach ($controlId in @('FileNameControlHost','1148')) {
        $combo = $element.FindFirst($scope, [System.Windows.Automation.PropertyCondition]::new($idProp, $controlId))
        if ($combo) {
            $edit = if ($combo.Current.ControlType -eq [System.Windows.Automation.ControlType]::Edit) { $combo } else { $combo.FindFirst($scope, $editType) }
            if ($edit) { break }
        }
    }
    if (-not $edit) {
        $edit = $element.FindFirst($scope, [System.Windows.Automation.AndCondition]::new(
            $editType, [System.Windows.Automation.PropertyCondition]::new($idProp, '1152')))
    }
    $open = $element.FindFirst($scope, [System.Windows.Automation.PropertyCondition]::new($idProp, '1'))
    if (-not $edit -or -not $open) { throw 'File name or Open control missing from the owned file picker' }
    $value = $edit.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern)
    $value.SetValue($Fixture)
    if ($value.Current.Value -ne $Fixture) { throw 'File picker did not accept the fixture path' }
    $open.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
}
try {
    $deadline = (Get-Date).AddSeconds(300)
    do {
        Start-Sleep -Milliseconds 500
        $own = @(Get-CimInstance Win32_Process -Filter "Name='LearningCoach.exe'" | Where-Object { $before -notcontains [int]$_.ProcessId } | ForEach-Object { [int]$_.ProcessId })
        $window = if ($own.Count) { @(Get-Process -Id $own -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle -eq 'LearningCoach' }) | Select-Object -First 1 } else { $null }
    } while (-not $window -and (Get-Date) -lt $deadline)
    if (-not $window) { throw 'Installed app window did not appear' }
    $script:AppPid = $window.Id
    $script:Win = $window.MainWindowHandle
    Save-OwnWindow 'installed-window-printwindow.png'
    Write-Output 'Captured installed app window via PrintWindow; image requires visual review.'

    # On this disposable runner, dismiss only a foreground WWAHost as explicitly
    # authorized. Input still uses Win-Drive's foreground and PID guards.
    $foreground = [WD]::GetForegroundWindow(); $owner = 0
    [WD]::GetWindowThreadProcessId($foreground, [ref]$owner) | Out-Null
    $front = Get-Process -Id $owner -ErrorAction SilentlyContinue
    if ($front -and $front.ProcessName -eq 'WWAHost') {
        Stop-Process -Id $owner -Force
        Write-Output "Closed foreground WWAHost pid=$owner on the disposable runner"
    }
    $upload = -join ([char[]](0x4e0a,0x4f20,0x6559,0x6750))
    $invoked = $false
    try {
        Add-Type -AssemblyName UIAutomationClient
        $root = [System.Windows.Automation.AutomationElement]::FromHandle($script:Win)
        $condition = New-Object System.Windows.Automation.PropertyCondition ([System.Windows.Automation.AutomationElement]::NameProperty), $upload
        $button = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $condition)
        if ($button) {
            $pattern = $button.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
            if ($pattern) { $pattern.Invoke(); $invoked = $true }
        }
    } catch { Write-Output "UI Automation unavailable: $($_.Exception.Message)" }
    if (-not $invoked) {
        Click-CapturedUpload
    }
    Submit-ScanFile $upload
    $db = Join-Path $env:COACH_LIBRARY_DIR 'library.sqlite'
    $deadline = (Get-Date).AddSeconds(300)
    $recognized = $false
    do {
        Start-Sleep -Seconds 3
        & python (Join-Path $PSScriptRoot 'prism-ocr-check.py') $db $Fixture
        if ($LASTEXITCODE -eq 0) { $recognized = $true; break }
    } while ((Get-Date) -lt $deadline)
    if (-not $recognized) { throw 'No expected OCR text was saved from the imported fixture' }
    Write-Output 'PRISM_SCAN_IMPORT_PASS'
    Save-OwnWindow 'after-import-printwindow.png'
} finally {
    foreach ($id in $own) {
        $proc = Get-Process -Id $id -ErrorAction SilentlyContinue
        if ($proc -and $proc.ProcessName -eq 'LearningCoach') { Stop-Process -Id $id -Force -ErrorAction SilentlyContinue }
    }
}
