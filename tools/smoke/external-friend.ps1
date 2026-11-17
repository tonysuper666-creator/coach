param([Parameter(Mandatory)][ValidateSet('Install','Probe','Revoke')][string]$Phase)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$private = Join-Path $env:RUNNER_TEMP 'coach-external-private'
if (!(Test-Path $private)) { New-Item -ItemType Directory $private | Out-Null }
# No inherited read access for pairing files or the runner's saved endpoint.
$acl = [System.Security.AccessControl.DirectorySecurity]::new()
$acl.SetAccessRuleProtection($true, $false)
$user = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
$rule = [System.Security.AccessControl.FileSystemAccessRule]::new($user, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
$acl.AddAccessRule($rule)
Set-Acl $private $acl
if ($Phase -eq 'Install') {
    if ((Get-Content bundle/commit.txt -Raw).Trim() -ne $env:GITHUB_SHA) { throw 'Commit mismatch' }
    $packages = @(Get-ChildItem bundle/*.msi)
    if ($packages.Count -ne 1) { throw 'Expected one MSI' }
    $target = Join-Path $private 'installed'
    $install = Start-Process msiexec.exe -ArgumentList @('/i', "`"$($packages[0].FullName)`"", '/qn', '/norestart', "INSTALLDIR=`"$target`"") -PassThru -Wait
    if ($install.ExitCode -ne 0) { throw "MSI installation failed ($($install.ExitCode))" }
    $executables = @(Get-ChildItem $target -Filter LearningCoach.exe -Recurse)
    if ($executables.Count -ne 1) { throw 'Installed executable missing' }
    $exe = $executables[0].FullName
    Set-Content (Join-Path $private 'exe.txt') $exe -NoNewline
    $app = Start-Process $exe -PassThru
    try {
        $deadline = [DateTime]::UtcNow.AddSeconds(45)
        do {
            Start-Sleep -Milliseconds 250
            $app.Refresh()
            if ($app.HasExited) { throw 'Installed executable exited before window' }
        } until ($app.MainWindowHandle -ne 0 -or [DateTime]::UtcNow -ge $deadline)
        if ($app.MainWindowHandle -eq 0) { throw 'Installed window not visible' }
        Write-Output 'EXTERNAL_INSTALL PASS MSI=0 installedWindow=visible'
    } finally {
        if (!$app.HasExited) { $null = $app.CloseMainWindow(); if (!$app.WaitForExit(10000)) { $app.Kill(); $app.WaitForExit() } }
    }
} else {
    # No Gradle, installer, network dependency download or secret-bearing arguments here.
    $env:COACH_EXTERNAL_PRIVATE = $private
    $env:COACH_EXTERNAL_EXE = Get-Content (Join-Path $private 'exe.txt') -Raw
    $env:COACH_EXTERNAL_EVIDENCE = Join-Path $PWD 'evidence'
    $driver = (Resolve-Path 'bundle/driver').Path
    $classpath = ((Get-Content "$driver/classpath.txt") | ForEach-Object { Join-Path $driver $_ }) -join ';'
    & "$env:JAVA_HOME/bin/java.exe" -cp $classpath com.coach.desktop.ExternalFriendSmoke ($Phase.ToLowerInvariant())
    if ($LASTEXITCODE -ne 0) { throw "External friend phase failed ($Phase)" }
}
