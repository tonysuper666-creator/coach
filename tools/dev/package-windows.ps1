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

.PARAMETER SableLogin
    Kept for compatibility; the "Sable account" sign-in entry is shown by default
    starting in 1.1.2 (TON-1782). Pass -NoSableLogin to build with it hidden.
    The decision is printed as sable-login=shown|hidden.

.PARAMETER WixZip
    Optional pre-downloaded wix311.zip for an offline host. Copied to
    ~/.gradle/compose-jb/wix311.zip before the build when that file is absent.

.PARAMETER Version
    Release version to package. Default: coach.release.version from
    gradle.properties. Pass it to build a version cut without editing
    gradle.properties, which would move an already-locked commit.

.PARAMETER VersionCode
    Android versionCode paired with -Version. Default: coach.release.versionCode
    from gradle.properties.

.PARAMETER ChannelFile
    Path to a private UTF-8 file holding the https URL of the published
    latest.json. Passed to Gradle as COACH_UPDATE_CHANNEL_FILE so the MSI carries
    the update channel. Without this parameter a COACH_UPDATE_CHANNEL_FILE the
    caller already set still reaches Gradle and is packaged the same way. The
    path and contents are never printed; the script reports channel=injected
    with the SHA-256 of whichever file Gradle reads, plus channel-source=
    parameter|environment, or channel=absent when neither is set.
    The variable is set only for the duration of the build and the caller's
    previous value — including "unset" — is restored before the script exits,
    whether the build succeeds or fails.

.PARAMETER UserHome
    Read build-time configuration (`Coach/config/local.properties`) from this
    directory instead of the current user's profile. Passed to Gradle as
    -Duser.home, so a release build can take one private friend-service origin
    without that value ever reaching the shared profile or the repository.
    GRADLE_OPTS alone is not enough: it configures the Gradle client JVM while
    the build runs in a forked daemon, which silently keeps the old home.

.PARAMETER UseReleaseFriendUrl
    Embed the private HTTPS origin from the single-line file
    %USERPROFILE%\Coach\config\release-friend-url.txt. The file and its value
    are used only for this build, independently of the selected UserHome.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools/dev/package-windows.ps1
#>
[CmdletBinding()]
param(
    [string]$Task = 'packageDistributionForCurrentOS',
    [string]$WixZip,
    [switch]$SableLogin,
    [switch]$NoSableLogin,
    [string]$Version,
    [string]$VersionCode,
    [string]$ChannelFile,
    [string]$UserHome,
    [switch]$UseReleaseFriendUrl
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not (Test-Path (Join-Path $root 'gradlew.bat'))) { throw "gradlew.bat not found under $root" }
if (-not (Get-Command jpackage -ErrorAction SilentlyContinue)) {
    throw 'jpackage not found: put a JDK 21 on PATH or set JAVA_HOME.'
}

# Read the same release configuration as Android and Compose. Publishing records
# v<version> tags; fetching them prevents an older checkout from emitting an old MSI.
$property = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $root 'gradle.properties')) {
    if ($line -match '^(coach\.release\.[A-Za-z]+)\s*=\s*(.+)$') { $property[$Matches[1]] = $Matches[2].Trim() }
}
if (-not $Version) { $Version = $property['coach.release.version'] }
if (-not $VersionCode) { $VersionCode = $property['coach.release.versionCode'] }
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw "Release version must be major.minor.patch: $Version" }
if ($VersionCode -notmatch '^\d+$') { throw "Release versionCode must be an integer: $VersionCode" }
$releaseFriendUrl = $null
if ($UseReleaseFriendUrl) {
    $friendFile = Join-Path $env:USERPROFILE 'Coach\config\release-friend-url.txt'
    if (-not (Test-Path -LiteralPath $friendFile -PathType Leaf)) { throw 'Missing private friend service release file.' }
    $lines = @(Get-Content -LiteralPath $friendFile -Encoding UTF8)
    if ($lines.Count -ne 1 -or $lines[0].Trim() -notmatch '^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?/?$') {
        throw 'Expected one valid HTTPS origin in the friend service release file.'
    }
    $releaseFriendUrl = $lines[0].Trim()
}
& git -C $root fetch --tags origin
if ($LASTEXITCODE -ne 0) { throw 'Cannot sync published release tags; no package built.' }

# COACH_UPDATE_CHANNEL_FILE is process state, not a parameter: it outlives the
# Gradle call and every child this script starts. Save the caller's value before
# touching it and put it back on the way out, including failures raised below
# the assignment, so one build never changes what the next one packages.
$channel = 'absent'
$channelDigest = 'none'
$channelBefore = [Environment]::GetEnvironmentVariable('COACH_UPDATE_CHANNEL_FILE', 'Process')
$channelOverridden = $false
try {
    $channelSource = 'none'
    if ($ChannelFile) {
        if (-not (Test-Path -LiteralPath $ChannelFile -PathType Leaf)) { throw 'Missing Windows update channel file' }
        $env:COACH_UPDATE_CHANNEL_FILE = (Resolve-Path -LiteralPath $ChannelFile).Path
        $channelOverridden = $true
        $channelSource = 'parameter'
    } elseif ($channelBefore) {
        $channelSource = 'environment'
    }
    # Report the file Gradle will actually read, however it arrived. A variable
    # inherited from the caller packages the channel exactly like -ChannelFile, so
    # calling that build channel=absent would pass a private MSI off as a public
    # test build. Gradle resolves a relative path against the desktopApp project,
    # so the digest is taken from the same place.
    $effectiveChannel = [Environment]::GetEnvironmentVariable('COACH_UPDATE_CHANNEL_FILE', 'Process')
    if ($effectiveChannel) {
        $channelPath = if ([IO.Path]::IsPathRooted($effectiveChannel)) { $effectiveChannel } else { Join-Path (Join-Path $root 'desktopApp') $effectiveChannel }
        if (-not (Test-Path -LiteralPath $channelPath -PathType Leaf)) { throw 'Missing Windows update channel file' }
        $channel = 'injected'
        $channelDigest = (Get-FileHash -LiteralPath $channelPath -Algorithm SHA256).Hash.ToLowerInvariant().Substring(0, 12)
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

    $homeArg = @()
    if ($UserHome) {
        if (-not (Test-Path -LiteralPath $UserHome)) { throw 'Missing private build config home' }
        $homeArg = @("-Duser.home=$((Resolve-Path -LiteralPath $UserHome).Path)")
    }

    $previousPriority = (Get-Process -Id $PID).PriorityClass
    $friendProperty = 'ORG_GRADLE_PROJECT_coach.friendUrl'
    $friendBefore = [Environment]::GetEnvironmentVariable($friendProperty, 'Process')
    Push-Location $root
    try {
        (Get-Process -Id $PID).PriorityClass = 'BelowNormal'
        if ($UseReleaseFriendUrl) { [Environment]::SetEnvironmentVariable($friendProperty, $releaseFriendUrl, 'Process') }
        # --no-daemon: the build JVM may be told to read another home, and a reused
        # daemon would keep the previous one.
        $showSable = -not $NoSableLogin
        & (Join-Path $root 'gradlew.bat') --console=plain --no-daemon --priority=low --max-workers=2 @homeArg `
            '-Pcoach.release=true' "-Pcoach.release.version=$Version" "-Pcoach.release.versionCode=$VersionCode" `
            "-Pcoach.sableLogin=$(if ($showSable) { 'true' } else { 'false' })" `
            :desktopApp:verifyReleaseVersion ":desktopApp:$Task"
        $code = $LASTEXITCODE
    } finally {
        if ($UseReleaseFriendUrl) { [Environment]::SetEnvironmentVariable($friendProperty, $friendBefore, 'Process') }
        Pop-Location
        (Get-Process -Id $PID).PriorityClass = $previousPriority
    }
    if ($code -ne 0) { throw "Gradle exited with code $code (task :desktopApp:$Task)" }
} finally {
    if ($channelOverridden) {
        if ($null -eq $channelBefore) {
            Remove-Item Env:\COACH_UPDATE_CHANNEL_FILE -ErrorAction SilentlyContinue
        } else {
            $env:COACH_UPDATE_CHANNEL_FILE = $channelBefore
        }
    }
}

$msiDir = Join-Path $root 'desktopApp\build\compose\binaries\main\msi'
# desktopApp sets packageName = LearningCoach; the old Coach-*.msi lookup never matched.
$installers = @(Get-ChildItem -LiteralPath (Join-Path $msiDir "LearningCoach-$Version.msi") -ErrorAction SilentlyContinue)
if ($installers.Count -eq 0) { throw "No MSI produced under $msiDir" }

foreach ($installer in $installers) {
    $hash = (Get-FileHash -Path $installer.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $sig = Get-AuthenticodeSignature -FilePath $installer.FullName
    $signer = if ($sig.SignerCertificate) { $sig.SignerCertificate.Subject } else { '(none)' }
    Write-Host ("msi={0}" -f $installer.FullName)
    Write-Host ("version={0} ({1})" -f $Version, $VersionCode)
    Write-Host ("size={0}" -f $installer.Length)
    Write-Host ("sha256={0}" -f $hash)
    Write-Host ("signature={0} {1}" -f $sig.Status, $signer)
    Write-Host ("sable-login={0}" -f $(if (-not $NoSableLogin) { 'shown' } else { 'hidden' }))
    Write-Host ("channel={0} sha256={1}" -f $channel, $channelDigest)
    Write-Host ("channel-source={0}" -f $channelSource)
    Write-Host ("home={0}" -f $(if ($UserHome) { 'private' } else { 'profile' }))
}
