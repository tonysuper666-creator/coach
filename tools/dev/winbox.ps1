param([Parameter(Mandatory=$true)][ValidatePattern('^heavy-[a-f0-9]{32}$')][string]$Job,
      [switch]$InteractiveWorker, [int]$ExpectedSessionId = -1)
$ErrorActionPreference = 'Stop'
[Diagnostics.Process]::GetCurrentProcess().PriorityClass = 'Idle'
$inbox = "D:/agent/logs/$Job"
$config = Get-Content "$inbox/request.json" -Raw | ConvertFrom-Json
$repo = 'D:/agent/git/coach-heavy.git'
$work = "D:/agent/work/$Job"
$trash = "D:/agent/_trash/$Job"
$env:JAVA_HOME = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot'

function Get-ActiveConsole {
    if (!('CoachConsole' -as [type])) {
        Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class CoachConsole {
    [DllImport("kernel32.dll")] public static extern uint WTSGetActiveConsoleSessionId();
    [DllImport("wtsapi32.dll", CharSet=CharSet.Unicode)]
    public static extern bool WTSQuerySessionInformationW(IntPtr server, uint session, int info, out IntPtr buffer, out int bytes);
    [DllImport("wtsapi32.dll")] public static extern void WTSFreeMemory(IntPtr buffer);
    public static string User(uint session) {
        IntPtr buffer; int bytes;
        if (!WTSQuerySessionInformationW(IntPtr.Zero, session, 5, out buffer, out bytes)) return "";
        try { return Marshal.PtrToStringUni(buffer); } finally { WTSFreeMemory(buffer); }
    }
    public static bool Active(uint session) {
        IntPtr buffer; int bytes;
        if (!WTSQuerySessionInformationW(IntPtr.Zero, session, 8, out buffer, out bytes)) return false;
        try { return Marshal.ReadInt32(buffer) == 0; } finally { WTSFreeMemory(buffer); }
    }
}
'@
    }
    $id = [CoachConsole]::WTSGetActiveConsoleSessionId()
    $owner = [Security.Principal.WindowsIdentity]::GetCurrent()
    if ($id -eq [uint32]::MaxValue -or $id -eq 0 -or ![CoachConsole]::Active($id) -or
        [CoachConsole]::User($id) -ine ($owner.Name -split '\\')[-1]) {
        throw 'INTERACTIVE_UNAVAILABLE: an active logged-in console owned by the runner user is required'
    }
    if ($null -ne $config.consoleSessionId -and $id -ne $config.consoleSessionId) {
        throw "INTERACTIVE_UNAVAILABLE: requested console $($config.consoleSessionId), active console is $id"
    }
    return [int]$id
}

function Quote-NativeArgument([string]$Value) {
    # Windows CommandLineToArgvW quoting, including trailing backslashes.
    return '"' + (($Value -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"'
}

function Stop-OwnedGradle {
    if (!(Test-Path "$inbox/gradle-process.json")) { return }
    $owned = Get-Content "$inbox/gradle-process.json" -Raw | ConvertFrom-Json
    $running = Get-Process -Id $owned.pid -ErrorAction SilentlyContinue
    if (!$running -or $running.StartTime.ToUniversalTime().Ticks -ne $owned.started) { return }
    $details = Get-CimInstance Win32_Process -Filter "ProcessId = $($owned.pid)"
    if ($details.Name -ne 'java.exe' -or !$details.CommandLine.Contains("$work/gradle/wrapper/gradle-wrapper.jar")) {
        throw 'refusing to stop a process that does not match this run'
    }
    & taskkill.exe /PID $owned.pid /T /F | Out-Null
    if ($LASTEXITCODE -ne 0 -and (Get-Process -Id $owned.pid -ErrorAction SilentlyContinue)) {
        throw 'could not stop this run''s Gradle process tree'
    }
}

function Invoke-Gradle {
    $env:GRADLE_USER_HOME = 'D:/agent/gradle-home'
    $env:ANDROID_HOME = 'D:/agent/android-sdk'
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
    $gradleArgs = @($config.args)
    if (($gradleArgs -notcontains '--build-cache') -and ($gradleArgs -notcontains '--no-build-cache')) {
        $gradleArgs += '--build-cache'
    }
    if ($config.mode -eq 'reuse') { $gradleArgs += @('--daemon', '--configuration-cache') }
    else { $gradleArgs += @('--no-daemon', '--no-configuration-cache') }
    $gradleArgs += @('--priority=low', '--max-workers=2', '--console=plain')
    $arguments = @('-Xmx64m', '-Xms64m', '-Dorg.gradle.appname=gradlew', '-jar',
                   "$work/gradle/wrapper/gradle-wrapper.jar") + $gradleArgs
    $start = New-Object Diagnostics.ProcessStartInfo
    $start.FileName = "$env:JAVA_HOME/bin/java.exe"
    $start.Arguments = ($arguments | ForEach-Object { Quote-NativeArgument $_ }) -join ' '
    $start.WorkingDirectory = $work
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $start
    $stdout = [IO.File]::Open("$inbox/stdout.log", 'Create', 'Write', 'Read')
    $stderr = [IO.File]::Open("$inbox/stderr.log", 'Create', 'Write', 'Read')
    $timer = [Diagnostics.Stopwatch]::StartNew()
    try {
        $null = $process.Start()
        @{pid=$process.Id; started=$process.StartTime.ToUniversalTime().Ticks} |
            ConvertTo-Json | Set-Content -Encoding UTF8 "$inbox/gradle-process.json"
        $process.PriorityClass = 'Idle'
        $copyOut = $process.StandardOutput.BaseStream.CopyToAsync($stdout)
        $copyErr = $process.StandardError.BaseStream.CopyToAsync($stderr)
        $process.WaitForExit()
        $null = $copyOut.GetAwaiter().GetResult()
        $null = $copyErr.GetAwaiter().GetResult()
        $result = @{exit=$process.ExitCode; seconds=[math]::Round($timer.Elapsed.TotalSeconds,3);
                    sessionId=[Diagnostics.Process]::GetCurrentProcess().SessionId}
    } finally {
        try { Stop-OwnedGradle }
        finally { $stdout.Dispose(); $stderr.Dispose(); $process.Dispose() }
    }
    return $result
}

# The scheduled worker uses the prepared worktree. The SSH parent owns both locks,
# waits for completion, collects artifacts and unregisters this run's task.
if ($InteractiveWorker) {
    try {
        $console = Get-ActiveConsole
        if ($ExpectedSessionId -ne $console -or
            [Diagnostics.Process]::GetCurrentProcess().SessionId -ne $console -or ![Environment]::UserInteractive) {
            throw 'INTERACTIVE_UNAVAILABLE: worker is not in the requested active interactive console'
        }
        $result = Invoke-Gradle
    } catch {
        $code = if ("$_" -like 'INTERACTIVE_UNAVAILABLE:*') { 69 } else { 70 }
        [IO.File]::WriteAllText("$inbox/stderr.log", "$_")
        if (!(Test-Path "$inbox/stdout.log")) { [IO.File]::WriteAllText("$inbox/stdout.log", '') }
        $result = @{exit=$code; seconds=0; sessionId=[Diagnostics.Process]::GetCurrentProcess().SessionId}
    }
    $result | ConvertTo-Json | Set-Content -Encoding UTF8 "$inbox/execution.tmp"
    Move-Item "$inbox/execution.tmp" "$inbox/execution.json"
    exit $result.exit
}

$admin = New-Object Threading.Mutex($false, 'Global\CoachHeavyGit')
$slots = @(0,1 | ForEach-Object { New-Object Threading.Mutex($false, "Global\CoachHeavySlot$_") })
$slot = -1
$exitCode = 70
$phase = 'prepare'
$reported = $false
$taskName = "Coach-$Job"
$taskRegistered = $false

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
    if ($config.interactive) {
        $console = Get-ActiveConsole
        if ($config.interactiveSeconds -lt 1 -or $config.interactiveSeconds -gt 3600) {
            throw 'interactiveSeconds must be between 1 and 3600'
        }
    }
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
            if ($null -ne $config.androidConfig) {
                $fingerprint = & "$env:JAVA_HOME/bin/java.exe" "$inbox/WinboxConfig.java"
                if ($LASTEXITCODE -ne 0) { throw 'cannot fingerprint Windows Android configuration' }
                $actual = $fingerprint | ConvertFrom-Json
                foreach ($key in @('coach.tailHost', 'coach.course', 'coach.port')) {
                    $expectedHash = $config.androidConfig.$key
                    if (!$expectedHash -or $expectedHash -cne $actual.$key) {
                        throw "Android configuration differs: $key (values withheld)"
                    }
                }
                'Coach heavy: Android configuration hashes match (3 keys)'
            }
            if (!(Take-Mutex $admin 60000)) { throw 'QUEUE75: git administration lock timeout' }
            try {
                if (!(Test-Path $repo)) { Git-Checked @('-c', 'init.defaultBranch=main', 'init', '--bare', $repo) }
                Git-Checked @('--git-dir', $repo, 'fetch', '--quiet', "$inbox/source.bundle", $config.sha)
                Git-Checked @('--git-dir', $repo, '-c', 'core.autocrlf=false', 'worktree', 'add', '--quiet', '--detach', $work, $config.sha)
            } finally { $admin.ReleaseMutex() }
            Set-Content -Encoding Ascii "$work/local.properties" 'sdk.dir=D:/agent/android-sdk'
            $phase = 'gradle'
            if ($config.interactive) {
                $console = Get-ActiveConsole  # Recheck after queue/preparation.
                $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
                $action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument (
                    "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File $inbox/runner.ps1 -Job $Job -InteractiveWorker -ExpectedSessionId $console")
                $principal = New-ScheduledTaskPrincipal -UserId $identity -LogonType Interactive -RunLevel Limited
                $settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit (New-TimeSpan -Seconds $config.interactiveSeconds)
                Register-ScheduledTask -TaskName $taskName -Action $action -Principal $principal -Settings $settings | Out-Null
                $taskRegistered = $true
                Start-ScheduledTask -TaskName $taskName
                $wait = [Diagnostics.Stopwatch]::StartNew()
                while (!(Test-Path "$inbox/execution.json")) {
                    if ($wait.Elapsed.TotalSeconds -gt $config.interactiveSeconds) {
                        throw 'interactive task timed out before reporting completion'
                    }
                    if ([int]$wait.Elapsed.TotalSeconds % 10 -lt 2) {
                        "Coach heavy: waiting for interactive console=$console elapsed=$([int]$wait.Elapsed.TotalSeconds)s"
                    }
                    Start-Sleep -Seconds 2
                }
                $execution = Get-Content "$inbox/execution.json" -Raw | ConvertFrom-Json
                if ($execution.exit -notin @(69,70) -and $execution.sessionId -ne $console) {
                    throw 'interactive worker returned an unexpected session ID'
                }
            } else {
                $execution = Invoke-Gradle
            }
            $exitCode = [int]$execution.exit
            $seconds = $execution.seconds
            $combined = [IO.File]::ReadAllText("$inbox/stdout.log") + [IO.File]::ReadAllText("$inbox/stderr.log")
            [IO.File]::WriteAllText("$inbox/gradle.log", $combined)
            Write-Output $combined
            $phase = 'artifacts'
            # Keep the child's original native stdout/stderr beside the JUnit reports.
            $nativeLogs = "$work/desktopApp/build/reports/winbox-native"
            if (Test-Path "$work/desktopApp/build") {
                New-Item -ItemType Directory -Force $nativeLogs | Out-Null
                Get-ChildItem "$work/desktopApp/build" -File | Where-Object {
                    $_.Name -like '*-native.log' -or $_.Name -like '*-native.stderr.log'
                } | Copy-Item -Destination $nativeLogs
            }
            Add-Type -AssemblyName System.IO.Compression.FileSystem
            $zip = [IO.Compression.ZipFile]::Open("$inbox/artifacts.zip", 'Create')
            try {
                Get-ChildItem $work -Recurse -File | Where-Object {
                    $_.FullName -match '\\build\\(outputs|reports|test-results|screens)\\'
                } | ForEach-Object {
                    $relative = $_.FullName.Substring($work.Length + 1).Replace('\','/')
                    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $relative) | Out-Null
                }
            } finally { $zip.Dispose() }
            @{exit=$exitCode; seconds=$seconds; sha=$config.sha; slot=$slot; artifacts=$true; mode=$config.mode;
              interactive=[bool]$config.interactive; sessionId=$execution.sessionId; logs=$true} |
                ConvertTo-Json | Set-Content -Encoding UTF8 "$inbox/result.json"
            $reported = $true
            "Coach heavy: winbox GRADLE_EXIT=$exitCode SECONDS=$seconds"
        }
    }
} catch {
    Write-Output "Coach heavy: winbox runner failed: $_"
    if ("$_" -like 'INTERACTIVE_UNAVAILABLE:*') {
        $exitCode = 69
        [IO.File]::WriteAllText("$inbox/stdout.log", '')
        [IO.File]::WriteAllText("$inbox/stderr.log", "$_")
        [IO.File]::WriteAllText("$inbox/gradle.log", "$_")
        @{exit=$exitCode; seconds=0; sha=$config.sha; artifacts=$false; interactive=$true; logs=$true} |
            ConvertTo-Json | Set-Content -Encoding UTF8 "$inbox/result.json"
    } elseif ($phase -eq 'prepare') { $exitCode = 75 } else { $exitCode = 70 }
} finally {
    if ($taskRegistered) {
        try {
            if ((Get-ScheduledTask -TaskName $taskName).State -eq 'Running') { Stop-ScheduledTask -TaskName $taskName }
            Stop-OwnedGradle
        } catch { Write-Output "Coach heavy: task cleanup failed ($taskName): $_"; $exitCode = 70 }
        finally {
            try { Unregister-ScheduledTask -TaskName $taskName -Confirm:$false }
            catch { Write-Output "Coach heavy: task unregister failed ($taskName): $_"; $exitCode = 70 }
        }
    }
    Set-Location D:/agent
    # Keep files in quarantine; prune only the missing worktree's Git metadata.
    if (Test-Path $work) {
        if (Take-Mutex $admin 60000) {
            try {
                New-Item -ItemType Directory -Force $trash | Out-Null
                Move-Item $work "$trash/worktree"
                Git-Checked @('--git-dir', $repo, 'worktree', 'prune', '--expire', 'now')
                Move-Item "$inbox/source.bundle" "$trash/source.bundle"
            } catch {
                Write-Output "Coach heavy: quarantine deferred (inspect $work): $_"
                if (!$reported -and $phase -ne 'prepare') { $exitCode = 70 }
            }
            finally { $admin.ReleaseMutex() }
        } else {
            Write-Output "Coach heavy: quarantine deferred (administration lock; inspect $work)"
            if (!$reported -and $phase -ne 'prepare') { $exitCode = 70 }
        }
    }
    if ($slot -ge 0) { $slots[$slot].ReleaseMutex() }
    foreach ($mutex in $slots) { $mutex.Dispose() }
    $admin.Dispose()
    if ($config.interactive) {
        New-Item -ItemType Directory -Force $trash | Out-Null
        Move-Item "$inbox/runner.ps1" "$trash/runner.ps1"
        Move-Item "$inbox/WinboxConfig.java" "$trash/WinboxConfig.java"
    }
}
exit $exitCode
