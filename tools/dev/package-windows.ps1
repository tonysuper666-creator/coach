#Requires -Version 5.1
<#
.SYNOPSIS
    Builds the Coach desktop Windows installer and prints its evidence.

.DESCRIPTION
    Runs the Compose desktop packaging task configured with TargetFormat.Msi in
    desktopApp/build.gradle.kts. jpackage needs the WiX 3 toolset; the Compose
    Gradle plugin downloads it into ~/.gradle/compose-jb on first use, so no
    machine-wide install and no PATH change is required.

    The script lowers its own process priority before launching Gradle; child
    processes inherit it, so packaging never competes with the user's foreground
    work, matching the winbox build slots.

    Output lands in desktopApp/build/compose/binaries/main/msi. The script prints
    the MSI path, size, SHA-256 and Authenticode state for the delivery record.

.PARAMETER Task
    Gradle task. Default: packageDistributionForCurrentOS (MSI on Windows).

.PARAMETER WixZip
    Optional pre-downloaded wix311.zip for an offline host. Copied to
    ~/.gradle/compose-jb/wix311.zip before the build when that file is absent.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools/dev/package-windows.ps1
#>
[CmdletBinding()]
param(
    [string]$Task = 'packageDistributionForCurrentOS',
    [string]$WixZip
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not (Test-Path (Join-Path $root 'gradlew.bat'))) { throw "gradlew.bat not found under $root" }
if (-not (Get-Command jpackage -ErrorAction SilentlyContinue)) {
    throw 'jpackage not found: put a JDK 21 on PATH or set JAVA_HOME.'
}

if ($WixZip) {
    $cache = Join-Path $env:USERPROFILE '.gradle\compose-jb'
    $target = Join-Path $cache 'wix311.zip'
    if (-not (Test-Path $target)) {
        New-Item -ItemType Directory -Force -Path $cache | Out-Null
        Copy-Item -Path $WixZip -Destination $target
        Write-Host "wix-cache=seeded from $WixZip"
    }
}

$previousPriority = (Get-Process -Id $PID).PriorityClass
Push-Location $root
try {
    (Get-Process -Id $PID).PriorityClass = 'BelowNormal'
    & (Join-Path $root 'gradlew.bat') --console=plain ":desktopApp:$Task"
    $code = $LASTEXITCODE
} finally {
    Pop-Location
    (Get-Process -Id $PID).PriorityClass = $previousPriority
}
if ($code -ne 0) { throw "Gradle exited with code $code (task :desktopApp:$Task)" }

$msiDir = Join-Path $root 'desktopApp\build\compose\binaries\main\msi'
$installers = @(Get-ChildItem -Path $msiDir -Filter '*.msi' -ErrorAction SilentlyContinue)
if ($installers.Count -eq 0) { throw "No MSI produced under $msiDir" }

foreach ($installer in $installers) {
    $hash = (Get-FileHash -Path $installer.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $sig = Get-AuthenticodeSignature -FilePath $installer.FullName
    $signer = if ($sig.SignerCertificate) { $sig.SignerCertificate.Subject } else { '(none)' }
    Write-Host ("msi={0}" -f $installer.FullName)
    Write-Host ("size={0}" -f $installer.Length)
    Write-Host ("sha256={0}" -f $hash)
    Write-Host ("signature={0} {1}" -f $sig.Status, $signer)
}
