param([ValidateSet('shared','store')][string]$Phase = 'shared')
$ErrorActionPreference = 'Stop'
[Diagnostics.Process]::GetCurrentProcess().PriorityClass = 'Idle'
# Participate in the same two machine-wide slots as tools/dev/winbox.ps1.
$slots = @(0,1 | ForEach-Object { New-Object Threading.Mutex($false, "Global\CoachHeavySlot$_") })
$slot = -1
$exitCode = 70
try {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while ($slot -lt 0) {
        foreach ($n in 0,1) {
            try { $taken = $slots[$n].WaitOne(0) } catch [Threading.AbandonedMutexException] { $taken = $true }
            if ($taken) { $slot = $n; break }
        }
        if ($slot -ge 0) { break }
        if ($timer.Elapsed.TotalSeconds -ge 900) { throw 'Windows two-slot queue timeout' }
        Write-Output 'Waiting for a Windows heavy slot'
        Start-Sleep -Seconds 2
    }
    $free = (Get-Counter '\Memory\Available MBytes').CounterSamples[0].CookedValue
    if ($free -lt 4096) { throw "Available memory $free MiB < 4096 MiB; retry when memory is available" }
    Write-Output "Windows heavy slot=$slot freeMiB=$free phase=$Phase"
    $env:JAVA_HOME = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot'
    $env:GRADLE_USER_HOME = 'D:/agent/gradle-home'
    $env:ANDROID_HOME = 'D:/agent/android-sdk'
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
    $repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
    Set-Location -LiteralPath $repo
    $logs = Join-Path $repo 'build/reader-anchor-check'
    New-Item -ItemType Directory -Force -Path $logs | Out-Null
    $gradleArgs = if ($Phase -eq 'shared') { @(':shared:domain:jvmTest', ':shared:protocol:jvmTest') } else {
        # Only this isolated module. Full server tests belong on Mac.
        @(':server:store:test', '--tests', 'com.coach.store.reader.ReaderAnchorStoreTest')
    }
    $gradleArgs += @('--offline','--no-daemon','--priority=low','--max-workers=2','--console=plain')
    $ErrorActionPreference = 'Continue'
    & "$env:JAVA_HOME/bin/java.exe" '-Xmx64m' '-Xms64m' '-Dorg.gradle.appname=gradlew' '-jar' 'gradle/wrapper/gradle-wrapper.jar' @gradleArgs |
        Tee-Object -FilePath (Join-Path $logs "$Phase.log")
    $exitCode = $LASTEXITCODE
    Write-Output "READER_CHECK_EXIT=$exitCode PHASE=$Phase"
} finally {
    if ($slot -ge 0) { $slots[$slot].ReleaseMutex() }
    foreach ($mutex in $slots) { $mutex.Dispose() }
}
exit $exitCode
