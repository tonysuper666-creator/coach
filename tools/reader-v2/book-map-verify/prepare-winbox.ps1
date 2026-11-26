#Requires -Version 5.1
# One-command winbox prep for TON-1889 ("这本书讲什么"全书地图) acceptance.
# Imports 10+ local books into an isolated library outside the checkout, starts an isolated
# non-production Coach server (when -BridgeUrl is provided) or targets an isolated server URL,
# optionally pre-generates maps via Haiku 5.5 Batch, and emits launch-window.ps1 / stop-server.ps1.
param(
    [Parameter(Mandatory = $true)]
    [string]$BooksFile,
    [string]$WorkDir = 'D:\agent\_trash\book-map-1889',
    [int]$ServerPort = 18889,
    [string]$BridgeUrl = '',
    [string]$RemoteServerUrl = '',
    [string]$TokenFile = '',
    [string]$DeviceId = 'desktop',
    [switch]$SkipGenerate
)

$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
Set-Location $RepoRoot

if ($ServerPort -in @(18791, 8790, 8791, 3790, 19540)) {
    throw "Refusing to use production/reserved port $ServerPort."
}
if (-not (Test-Path -LiteralPath $BooksFile)) {
    throw "Books file not found: $BooksFile"
}
$resolvedWork = [IO.Path]::GetFullPath($WorkDir)
$resolvedRepo = [IO.Path]::GetFullPath($RepoRoot)
if ($resolvedWork.StartsWith($resolvedRepo, [StringComparison]::OrdinalIgnoreCase)) {
    throw "WorkDir must be outside the git checkout: $resolvedWork"
}
$learnerBooks = [IO.Path]::GetFullPath((Join-Path $env:USERPROFILE 'Coach\books'))
if ($resolvedWork.Equals($learnerBooks, [StringComparison]::OrdinalIgnoreCase)) {
    throw "WorkDir must not point to the learner's library."
}

if (-not $env:JAVA_HOME) {
    $adoptium = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
    if (Test-Path (Join-Path $adoptium 'bin\java.exe')) { $env:JAVA_HOME = $adoptium }
}
if (-not $env:GRADLE_USER_HOME -and (Test-Path 'D:\agent\gradle-home')) {
    $env:GRADLE_USER_HOME = 'D:\agent\gradle-home'
}

$libDir = Join-Path $resolvedWork 'books'
$clientDir = Join-Path $resolvedWork 'client'
$serverDataDir = Join-Path $resolvedWork 'server-data'
New-Item -ItemType Directory -Force $libDir, $clientDir, $serverDataDir | Out-Null

$effectiveServerUrl = $RemoteServerUrl.Trim().TrimEnd('/')
$effectiveTokenFile = $TokenFile

if (-not $effectiveServerUrl -and $BridgeUrl.Trim()) {
    Write-Host "[1/3] Building isolated server distribution..."
    & (Join-Path $RepoRoot 'gradlew.bat') ':server:app:installDist' '--no-daemon'
    if ($LASTEXITCODE -ne 0) { throw "Failed to build :server:app:installDist" }

    $serverLibDir = Join-Path $RepoRoot 'server\app\build\install\app\lib'
    if (-not (Test-Path -LiteralPath $serverLibDir)) {
        throw "Server lib directory not found: $serverLibDir"
    }
    $javaExe = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java.exe' }
    $serverOutLog = Join-Path $resolvedWork 'server-stdout.log'
    $serverErrLog = Join-Path $resolvedWork 'server-stderr.log'
    $pidFile = Join-Path $resolvedWork 'server.pid'
    $effectiveTokenFile = Join-Path $serverDataDir 'device-token'
    $effectiveServerUrl = "http://127.0.0.1:$ServerPort"

    $oldEnv = @{
        COACH_DATA_DIR = $env:COACH_DATA_DIR
        COACH_PORT = $env:COACH_PORT
        COACH_BRIDGE_URL = $env:COACH_BRIDGE_URL
        COACH_TRAFFIC = $env:COACH_TRAFFIC
        COACH_TOKEN_FILE = $env:COACH_TOKEN_FILE
        COACH_DEVICE_ID = $env:COACH_DEVICE_ID
    }
    try {
        $env:COACH_DATA_DIR = $serverDataDir
        $env:COACH_PORT = "$ServerPort"
        $env:COACH_BRIDGE_URL = $BridgeUrl.Trim()
        $env:COACH_TRAFFIC = 'test'
        $env:COACH_TOKEN_FILE = $effectiveTokenFile
        $env:COACH_DEVICE_ID = $DeviceId
        # Launch java directly with wildcard classpath to avoid Windows app.bat "input line too long".
        $proc = Start-Process -FilePath $javaExe -ArgumentList @('-Dfile.encoding=UTF-8', '-cp', "$serverLibDir\*", 'com.coach.server.MainKt') `
            -PassThru -WindowStyle Hidden `
            -RedirectStandardOutput $serverOutLog -RedirectStandardError $serverErrLog
        Set-Content -LiteralPath $pidFile -Value $proc.Id -Encoding Ascii
    } finally {
        foreach ($k in $oldEnv.Keys) {
            if ($null -eq $oldEnv[$k]) { Remove-Item "Env:$k" -ErrorAction SilentlyContinue }
            else { Set-Item "Env:$k" $oldEnv[$k] }
        }
    }

    $deadline = (Get-Date).AddSeconds(30)
    $ready = $false
    while ((Get-Date) -lt $deadline) {
        if (Test-Path -LiteralPath $effectiveTokenFile) {
            try {
                Invoke-WebRequest -Uri "$effectiveServerUrl/v1/hello" -UseBasicParsing -TimeoutSec 2 -ErrorAction Stop | Out-Null
                $ready = $true; break
            } catch {
                if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 401) {
                    $ready = $true; break
                }
            }
        }
        Start-Sleep -Milliseconds 400
    }
    if (-not $ready) {
        throw "Isolated server did not become ready on $effectiveServerUrl (see $serverErrLog)"
    }
    Write-Host "Isolated server ready at $effectiveServerUrl (PID $(Get-Content $pidFile))"

    $stopScript = @"
`$pidFile = '$pidFile'
if (Test-Path -LiteralPath `$pidFile) {
    `$serverPid = [int](Get-Content -LiteralPath `$pidFile)
    Stop-Process -Id `$serverPid -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath `$pidFile -Force -ErrorAction SilentlyContinue
    Write-Host "Stopped isolated server PID `$serverPid"
}
"@
    Set-Content -LiteralPath (Join-Path $resolvedWork 'stop-server.ps1') -Value $stopScript -Encoding UTF8
}

Write-Host "[2/3] Importing books into isolated library ($libDir)..."
$prepArgs = New-Object System.Collections.Generic.List[string]
$prepArgs.Add('prepare')
$prepArgs.Add('--library'); $prepArgs.Add($libDir)
$prepArgs.Add('--books-file'); $prepArgs.Add([IO.Path]::GetFullPath($BooksFile))
$prepArgs.Add('--out'); $prepArgs.Add((Join-Path $resolvedWork 'manifest.json'))
if ($effectiveServerUrl) {
    $prepArgs.Add('--server-url'); $prepArgs.Add($effectiveServerUrl)
}
if ($effectiveTokenFile -and (Test-Path -LiteralPath $effectiveTokenFile)) {
    $prepArgs.Add('--token-file'); $prepArgs.Add($effectiveTokenFile)
    $prepArgs.Add('--device-id'); $prepArgs.Add($DeviceId)
}
if ($SkipGenerate) { $prepArgs.Add('--skip-generate') }

$argLine = ($prepArgs | ForEach-Object {
    if ($_ -match '\s') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ }
}) -join ' '
& (Join-Path $RepoRoot 'gradlew.bat') ':desktopApp:bookMapVerify' "--args=$argLine" '--no-daemon'
if ($LASTEXITCODE -ne 0) { throw "bookMapVerify prepare failed with exit code $LASTEXITCODE" }

Write-Host "[3/3] Writing isolated window launcher..."
$launchLines = @(
    '#Requires -Version 5.1',
    "`$ErrorActionPreference = 'Stop'",
    "Set-Location '$resolvedRepo'",
    "if (-not `$env:JAVA_HOME -and (Test-Path 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java.exe')) { `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot' }",
    "if (-not `$env:GRADLE_USER_HOME -and (Test-Path 'D:\agent\gradle-home')) { `$env:GRADLE_USER_HOME = 'D:\agent\gradle-home' }",
    "`$env:COACH_DEV = '1'",
    "`$env:COACH_LIBRARY_DIR = '$libDir'",
    "`$env:COACH_CLIENT_DIR = '$clientDir'"
)
if ($effectiveServerUrl) {
    $launchLines += "`$env:COACH_URL = '$effectiveServerUrl'"
}
if ($effectiveTokenFile) {
    $launchLines += "`$env:COACH_TOKEN_FILE = '$effectiveTokenFile'"
    $launchLines += "`$env:COACH_DEVICE_ID = '$DeviceId'"
}
$launchLines += "& '$resolvedRepo\gradlew.bat' ':desktopApp:run' '--no-daemon'"
$launchPath = Join-Path $resolvedWork 'launch-window.ps1'
Set-Content -LiteralPath $launchPath -Value ($launchLines -join "`n") -Encoding UTF8

Write-Host ""
Write-Host "Ready for TON-1889 acceptance:"
Write-Host "  Manifest : $(Join-Path $resolvedWork 'manifest.json')"
Write-Host "  Window   : powershell -ExecutionPolicy Bypass -File `"$launchPath`""
Write-Host "  Audit    : gradlew.bat :desktopApp:bookMapVerify --args=`"sample-audit --library $libDir --out $(Join-Path $resolvedWork 'sample-report.json')`""
if (Test-Path (Join-Path $resolvedWork 'stop-server.ps1')) {
    Write-Host "  Stop srv : powershell -ExecutionPolicy Bypass -File `"$(Join-Path $resolvedWork 'stop-server.ps1')`""
}
