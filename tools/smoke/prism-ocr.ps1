param(
    [Parameter(Mandatory)][string]$OutDir,
    [Parameter(Mandatory)][string]$Fixture,
    [Parameter(Mandatory)][string]$InstallResult,
    [string]$TestDesktop
)
$ErrorActionPreference = 'Stop'
if ($env:GITHUB_ACTIONS -ne 'true' -or $env:PROCESSOR_ARCHITECTURE -ne 'ARM64') {
    throw 'This probe is restricted to a disposable GitHub ARM64 runner.'
}
Add-Type -Path (Join-Path $PSScriptRoot 'ArmSmokeDesktop.cs')
if (-not $TestDesktop) {
    New-Item -ItemType Directory -Force $OutDir | Out-Null
    $outPath = (Resolve-Path -LiteralPath $OutDir).Path
    $fixturePath = (Resolve-Path -LiteralPath $Fixture).Path
    $resultPath = (Resolve-Path -LiteralPath $InstallResult).Path
    $name = 'CoachSmoke-' + [Guid]::NewGuid().ToString('N')
    $argsForDriver = '-NoProfile -NonInteractive -File "{0}" -OutDir "{1}" -Fixture "{2}" -InstallResult "{3}" -TestDesktop {4}' -f `
        $PSCommandPath, $outPath, $fixturePath, $resultPath, $name
    $code = [ArmSmokeDesktop]::Run((Join-Path $PSHOME 'powershell.exe'), $argsForDriver, $name)
    $log = Join-Path $outPath 'desktop.log'
    if (Test-Path $log) { Get-Content -LiteralPath $log -Encoding UTF8 }
    if ($code -ne 0) { throw "Installed desktop probe exited $code" }
    return
}
Start-Transcript -LiteralPath (Join-Path $OutDir 'desktop.log') -ErrorAction Stop | Out-Null
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
function Save-OwnWindow([string]$name, [IntPtr]$handle = $script:Win) {
    $rect = New-Object WD+RECT
    if (-not [WD]::GetWindowRect($handle, [ref]$rect)) { throw 'Cannot get own window bounds' }
    $bmp = New-Object System.Drawing.Bitmap ($rect.R - $rect.L), ($rect.B - $rect.T)
    try {
        $graphics = [System.Drawing.Graphics]::FromImage($bmp)
        try {
            $dc = $graphics.GetHdc()
            try { if (-not [PrismCapture]::PrintWindow($handle, $dc, 2)) { throw 'PrintWindow failed' } }
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
                $root = [System.Windows.Automation.AutomationElement]::FromHandle($script:Win)
                if ($root.Current.ProcessId -ne $script:AppPid) { throw 'Upload window owner changed' }
                Write-Output "Matched upload at ($($point.X),$($point.Y)). Foreground: $(Get-ForegroundSummary)"
                try { $root.SetFocus() } catch { Write-Output "UI Automation focus: $($_.Exception.Message)" }
                Click-At $point.X $point.Y
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
    if ($dialog -eq [IntPtr]::Zero) {
        List-AppWindows | Write-Output
        throw 'Click did not open the installed app file picker'
    }
    Save-OwnWindow 'file-picker.png' $dialog
    Write-Output "Installed file picker opened for $([IO.Path]::GetFileName($Fixture))"
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
function Test-InstalledScan([string]$OutDir, [string]$Fixture) {
    New-Item -ItemType Directory -Force $OutDir | Out-Null
    $OutDir = (Resolve-Path $OutDir).Path
    $Fixture = (Resolve-Path $Fixture).Path
    $env:COACH_LIBRARY_DIR = Join-Path $OutDir 'library'
    $env:COACH_CLIENT_DIR = Join-Path $OutDir 'client'
    $env:COACH_TOKEN_FILE = Join-Path $OutDir 'unused-token'
    if (Test-Path $env:COACH_LIBRARY_DIR) { throw 'The scan probe needs a fresh library' }
    New-Item -ItemType Directory $env:COACH_LIBRARY_DIR, $env:COACH_CLIENT_DIR | Out-Null
    $before = @(Get-CimInstance Win32_Process -Filter "Name='LearningCoach.exe'" | ForEach-Object { [int]$_.ProcessId })
    $app = [ArmSmokeDesktop]::Start($exe, '', $TestDesktop)
    $own = @()
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

        $upload = -join ([char[]](0x4e0a,0x4f20,0x6559,0x6750))
        $invoked = $false
        try {
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
        Write-Output 'INSTALLED_SCAN_IMPORT_PASS'
        Save-OwnWindow 'after-import-printwindow.png'
    } finally {
        foreach ($id in $own) {
            $proc = Get-Process -Id $id -ErrorAction SilentlyContinue
            if ($proc -and $proc.ProcessName -eq 'LearningCoach') { Stop-Process -Id $id -Force -ErrorAction SilentlyContinue }
        }
    }
}

$install = Get-Content -Raw -Encoding UTF8 $InstallResult | ConvertFrom-Json
if (-not $install.installedTo) { throw 'The MSI smoke did not record an install location' }
$exe = Join-Path $install.installedTo 'LearningCoach.exe'
if (-not (Test-Path -LiteralPath $exe)) { throw 'Installed executable missing' }
Test-InstalledScan $OutDir $Fixture
if ([IO.Path]::GetFileName($Fixture) -eq '01-clean-serif.png') {
    # Exercise the same three-page PDF under Prism as on native ARM, in a new library.
    $pdf = Join-Path (Split-Path -Parent $Fixture) 'three-scanned-pages.pdf'
    Test-InstalledScan (Join-Path $OutDir 'pdf') $pdf
}
Stop-Transcript | Out-Null
