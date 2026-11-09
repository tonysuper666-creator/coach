param([Parameter(Mandatory=$true)][ValidatePattern('^heavy-[a-f0-9]{32}$')][string]$Job)
$ErrorActionPreference = 'Stop'
[Diagnostics.Process]::GetCurrentProcess().PriorityClass = 'Idle'
$inbox = "D:/agent/logs/$Job"
$config = Get-Content "$inbox/request.json" -Raw | ConvertFrom-Json
$repo = 'D:/agent/git/coach-heavy.git'
$work = "D:/agent/work/$Job"
$trash = "D:/agent/_trash/$Job"
$admin = New-Object Threading.Mutex($false, 'Global\CoachHeavyGit')
$slots = @(0,1 | ForEach-Object { New-Object Threading.Mutex($false, "Global\CoachHeavySlot$_") })
$slot = -1
$created = $false
$exitCode = 70

function Take-Mutex($mutex, [int]$milliseconds) {
    try { return $mutex.WaitOne($milliseconds) }
    catch [Threading.AbandonedMutexException] { return $true }
}
function Git-Checked([string[]]$Arguments) {
    $ErrorActionPreference = 'Continue'
    $output = & git @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "git exit=$LASTEXITCODE $output" }
}

try {
    $clock = [Diagnostics.Stopwatch]::StartNew()
    while ($slot -lt 0) {
        foreach ($n in 0,1) {
            if (Take-Mutex $slots[$n] 0) { $slot = $n; break }
        }
        if ($slot -ge 0) { break }
        if ($clock.Elapsed.TotalSeconds -ge $config.queueSeconds) {
            'Coach heavy: winbox queue timeout'; $exitCode = 75; break
        }
        'Coach heavy: winbox waiting for one of two slots'
        Start-Sleep -Seconds 2
    }
    if ($slot -ge 0) {
        $free = (Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1MB
        if ($free -lt 4) {
            "Coach heavy: winbox free memory $([math]::Round($free,2)) GiB < 4 GiB"
            $exitCode = 75
        } else {
            "Coach heavy: winbox slot=$slot pid=$PID freeGiB=$([math]::Round($free,2)) priority=Idle"
            if (!(Take-Mutex $admin 60000)) { throw 'QUEUE75: git administration lock timeout' }
            try {
                if (!(Test-Path $repo)) { Git-Checked @('-c', 'init.defaultBranch=main', 'init', '--bare', $repo) }
                Git-Checked @('--git-dir', $repo, 'fetch', '--quiet', "$inbox/source.bundle", $config.sha)
                Git-Checked @('--git-dir', $repo, '-c', 'core.autocrlf=false', 'worktree', 'add', '--quiet', '--detach', $work, $config.sha)
                $created = $true
            } finally { $admin.ReleaseMutex() }
            $env:GRADLE_USER_HOME = 'D:/agent/gradle-home'
            $env:JAVA_HOME = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot'
            $env:ANDROID_HOME = 'D:/agent/android-sdk'
            $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
            Set-Content -Encoding Ascii "$work/local.properties" 'sdk.dir=D:/agent/android-sdk'
            Set-Location $work
            $gradleArgs = @('--build-cache') + @($config.args)
            if ($config.mode -eq 'reuse') {
                $gradleArgs += @('--daemon', '--configuration-cache')
            } else {
                $gradleArgs += @('--no-daemon', '--no-configuration-cache')
            }
            $gradleArgs += @('--priority=low', '--max-workers=2', '--console=plain')
            $timer = [Diagnostics.Stopwatch]::StartNew()
            # Invoke Java directly: no cmd.exe interpolation of task/property values.
            $ErrorActionPreference = 'Continue'
            & "$env:JAVA_HOME/bin/java.exe" '-Xmx64m' '-Xms64m' '-Dorg.gradle.appname=gradlew' '-jar' "$work/gradle/wrapper/gradle-wrapper.jar" @gradleArgs 2>&1 |
                ForEach-Object { "$_" } | Tee-Object -FilePath "$inbox/gradle.log"
            $exitCode = $LASTEXITCODE
            $ErrorActionPreference = 'Stop'
            $seconds = [math]::Round($timer.Elapsed.TotalSeconds, 3)
            Add-Type -AssemblyName System.IO.Compression.FileSystem
            $zip = [IO.Compression.ZipFile]::Open("$inbox/artifacts.zip", 'Create')
            try {
                Get-ChildItem $work -Recurse -File | Where-Object {
                    $_.FullName -match '\\build\\(outputs|reports|test-results)\\'
                } | ForEach-Object {
                    $relative = $_.FullName.Substring($work.Length + 1).Replace('\','/')
                    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $relative) | Out-Null
                }
            } finally { $zip.Dispose() }
            @{exit=$exitCode; seconds=$seconds; sha=$config.sha; slot=$slot; artifacts=$true; mode=$config.mode} |
                ConvertTo-Json | Set-Content -Encoding UTF8 "$inbox/result.json"
            "Coach heavy: winbox GRADLE_EXIT=$exitCode SECONDS=$seconds"
        }
    }
} catch {
    Write-Output "Coach heavy: winbox runner failed: $_"
    if ("$_" -like 'QUEUE75:*') { $exitCode = 75 } else { $exitCode = 70 }
} finally {
    Set-Location D:/agent
    # Keep files in quarantine; prune only the missing worktree's Git metadata.
    if ($created -and (Test-Path $work)) {
        if (Take-Mutex $admin 60000) {
            try {
                New-Item -ItemType Directory -Force $trash | Out-Null
                Move-Item $work "$trash/worktree"
                Git-Checked @('--git-dir', $repo, 'worktree', 'prune', '--expire', 'now')
                Move-Item "$inbox/source.bundle" "$trash/source.bundle"
            } catch { Write-Output "Coach heavy: quarantine failed: $_"; $exitCode = 70 }
            finally { $admin.ReleaseMutex() }
        } else { $exitCode = 70 }
    }
    if ($slot -ge 0) { $slots[$slot].ReleaseMutex() }
    foreach ($mutex in $slots) { $mutex.Dispose() }
    $admin.Dispose()
}
exit $exitCode
