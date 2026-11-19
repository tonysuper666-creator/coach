# After installing the x64 candidate on winbox:
# powershell -File tools/smoke/prism-ocr.ps1 -Winbox -OutDir <new-evidence-directory>
[CmdletBinding(DefaultParameterSetName='Hosted')]
param(
    [Parameter(Mandatory)][string]$OutDir,
    [Parameter(Mandatory,ParameterSetName='Hosted')][string]$Fixture,
    [Parameter(Mandatory,ParameterSetName='Hosted')][string]$InstallResult,
    [Parameter(ParameterSetName='Hosted')][string]$TestDesktop,
    [Parameter(Mandatory,ParameterSetName='Local')][switch]$Winbox
)
$ErrorActionPreference = 'Stop'
if ($Winbox) {
    if ($env:PROCESSOR_ARCHITECTURE -ne 'AMD64') { throw 'Expected an x64 Windows host' }
    New-Item -ItemType Directory -Force $OutDir | Out-Null
    $OutDir = (Resolve-Path -LiteralPath $OutDir).Path
    if ((Get-Process -Id $PID).SessionId -eq 0) {
        $quotedOut = $OutDir.Replace("'", "''")
        & (Join-Path $PSScriptRoot 'Run-Interactive.ps1') -Script $PSCommandPath -ScriptArgs "-Winbox -OutDir '$quotedOut'" -LogFile (Join-Path $OutDir 'worker.log') -TimeoutSec 900
        exit $LASTEXITCODE
    }
    $Fixture = (Resolve-Path (Join-Path $PSScriptRoot '../../desktopApp/src/test/resources/books/ocr/three-scanned-pages.pdf')).Path
    $products = @(Get-ItemProperty 'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*','HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*' -ErrorAction SilentlyContinue | Where-Object DisplayName -eq 'LearningCoach')
    if ($products.Count -ne 1) { throw 'Expected one installed LearningCoach MSI' }
    $exe = Join-Path $products[0].InstallLocation 'LearningCoach.exe'
    $env:COACH_SMOKE_ENABLED = $null
} elseif ($env:GITHUB_ACTIONS -ne 'true' -or $env:PROCESSOR_ARCHITECTURE -ne 'ARM64') {
    throw 'This probe is restricted to a disposable GitHub ARM64 runner.'
} else {
    Add-Type -Path (Join-Path $PSScriptRoot 'ArmSmokeDesktop.cs')
}
if (-not $Winbox -and -not $TestDesktop) {
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
. (Join-Path $PSScriptRoot 'arm-tutor-ui.ps1')
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
    $owner = 0
    [WD]::GetWindowThreadProcessId($handle, [ref]$owner) | Out-Null
    if (-not $script:AppPid -or $owner -ne $script:AppPid) { throw 'Capture target changed owner' }
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

function Click-CapturedControl([string]$template, [string]$shot, [switch]$VerifyOnly, [int]$ScrollNotches = 0) {
    if ($Winbox) {
        $point = Find-WinboxControl $template ([IO.Path]::GetFileNameWithoutExtension($shot)) $ScrollNotches
        if (-not $VerifyOnly) { Click-At $point.X $point.Y }
        return
    }
    # The previous file picker leaves the pointer over Upload. Compare the idle
    # appearance, on the application window that owns the next input action.
    Invoke-ForegroundStep {
        $rect = Get-Rect
        Assert-OurForeground -Expected $script:Win
        if (-not [WD]::SetCursorPos($rect.L + 40, $rect.T + 14)) { throw 'Cannot park pointer on the title bar' }
    }
    $reference = [Drawing.Bitmap]::new((Join-Path $PSScriptRoot "fixtures/$template"))
    try {
        $deadline = (Get-Date).AddSeconds(25)
        do {
            Save-OwnWindow $shot
            $frame = [Drawing.Bitmap]::new((Join-Path $OutDir $shot))
            try { $point = [InstalledShelf]::FindControl($frame, $reference) }
            finally { $frame.Dispose() }
            if (-not $point.IsEmpty) {
                $root = [System.Windows.Automation.AutomationElement]::FromHandle($script:Win)
                if ($root.Current.ProcessId -ne $script:AppPid) { throw 'Window owner changed' }
                Write-Output "Matched $template at ($($point.X),$($point.Y)). Foreground: $(Get-ForegroundSummary)"
                if (-not $VerifyOnly) { Click-At $point.X $point.Y }
                return
            }
            if ($ScrollNotches) { Scroll-At 584 490 $ScrollNotches }
            Start-Sleep -Milliseconds 400
        } while ((Get-Date) -lt $deadline)
        throw "Could not identify $template in the application screenshot"
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
    # The shared host's native file picker can display unrelated recent files.
    if (-not $Winbox) { Save-OwnWindow 'file-picker.png' $dialog }
    Write-Output "Installed file picker opened for $([IO.Path]::GetFileName($Fixture))"
    if (-not (Choose-File $title $Fixture)) { throw $script:ChooseFileWhy }
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
    $app = $null
    $window = $null
    $tutor = $null
    try {
        if ([IO.Path]::GetExtension($Fixture) -eq '.pdf') {
            $tempRoot = if ($Winbox) { [IO.Path]::GetTempPath() } else { $env:RUNNER_TEMP }
            $tutorPrivate = Join-Path $tempRoot ('coach-tutor-' + [Guid]::NewGuid().ToString('N'))
            New-Item -ItemType Directory $tutorPrivate | Out-Null
            $parameters = @((Join-Path $PSScriptRoot 'arm-loopback-tutor.py'), $tutorPrivate,
                $Fixture, (Join-Path $OutDir 'tutor-events.jsonl'), (Join-Path $env:COACH_LIBRARY_DIR 'library.sqlite')) |
                ForEach-Object { '"' + $_ + '"' }
            $tutor = Start-Process (Get-Command python.exe).Source -ArgumentList $parameters -WindowStyle Hidden -PassThru
            $portFile = Join-Path $tutorPrivate 'port'
            $limit = (Get-Date).AddSeconds(15)
            while (-not (Test-Path $portFile)) {
                if ($tutor.HasExited -or (Get-Date) -gt $limit) { throw 'Local tutor did not start' }
                Start-Sleep -Milliseconds 100
            }
            $port = [int](Get-Content -Raw $portFile)
            if ($port -le 0 -or $port -gt 65535 -or $port -eq 41000) { throw 'Unexpected local tutor port' }
            $env:COACH_URL = "http://127.0.0.1:$port"
            Write-Output "Fixture tutor listening on $env:COACH_URL"
            $env:COACH_TOKEN = $null
            $env:COACH_DEVICE_ID = 'arm-smoke'
            $env:COACH_TOKEN_FILE = Join-Path $tutorPrivate 'token'
        }
        $app = if ($Winbox) { Start-Process -FilePath $exe -PassThru } else { [ArmSmokeDesktop]::Start($exe, '', $TestDesktop) }
        $deadline = (Get-Date).AddSeconds(300)
        do {
            Start-Sleep -Milliseconds 500
            $candidates = Get-CimInstance Win32_Process -Filter "Name='LearningCoach.exe' AND (ProcessId=$($app.Id) OR ParentProcessId=$($app.Id))" |
                Where-Object ExecutablePath -eq $exe
            $window = $candidates | ForEach-Object { Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue } |
                Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle -eq 'LearningCoach' } | Select-Object -First 1
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
            Click-CapturedControl 'empty-shelf-upload.png' 'installed-window-printwindow.png'
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
        if ([IO.Path]::GetExtension($Fixture) -eq '.pdf') {
            Click-CapturedControl 'shelf-read-first.png' 'after-import-printwindow.png'
            Click-CapturedControl 'reader-defer-study.png' 'reader-first-open.png'
            Start-Sleep -Seconds 1
            Save-OwnWindow 'reader-before.png'
            # The reflow reader can lay out several screenfuls per source page.
            # Stop as soon as the book's persisted physical page changes to 2.
            foreach ($turn in 1..8) {
                Send-Keys '{RIGHT}'
                Start-Sleep -Milliseconds 900
                & python (Join-Path $PSScriptRoot 'prism-ocr-check.py') $db $Fixture --reading-page 2
                if ($LASTEXITCODE -eq 0) { break }
            }
            Save-OwnWindow 'reader-after.png'
            if ($LASTEXITCODE -ne 0) { throw 'Installed reader did not persist page 2' }
            Test-InstalledTutor
            Write-Output 'INSTALLED_FRIEND_PATH_PASS 7/7 (runner loopback tutor)'
        }
    } finally {
        if ($tutor) {
            if (-not $tutor.HasExited) { $tutor.Kill(); $tutor.WaitForExit() }
            $tutor.Dispose()
            Remove-Item -LiteralPath (Join-Path $tutorPrivate 'token') -ErrorAction SilentlyContinue
        }
        foreach ($proc in @($window, $app)) {
            if ($proc -and -not $proc.HasExited) { $proc.Kill(); $proc.WaitForExit() }
        }
    }
}

if (-not $Winbox) {
    $install = Get-Content -Raw -Encoding UTF8 $InstallResult | ConvertFrom-Json
    if (-not $install.installedTo) { throw 'The MSI smoke did not record an install location' }
    $exe = Join-Path $install.installedTo 'LearningCoach.exe'
}
if (-not (Test-Path -LiteralPath $exe)) { throw 'Installed executable missing' }
Test-InstalledScan $OutDir $Fixture
if ([IO.Path]::GetFileName($Fixture) -eq '01-clean-serif.png') {
    # Exercise the same three-page PDF under Prism as on native ARM, in a new library.
    $pdf = Join-Path (Split-Path -Parent $Fixture) 'three-scanned-pages.pdf'
    Test-InstalledScan (Join-Path $OutDir 'pdf') $pdf
}
Stop-Transcript | Out-Null
