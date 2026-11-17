# Run a PowerShell script in winbox's logged-in console session (agents themselves run in session 0 and cannot show or see windows).
# Uses the same mechanism as tools/dev/winbox.ps1: a one-shot Interactive scheduled task for the current user, removed afterwards.
param([Parameter(Mandatory)][string]$Script, [string[]]$ScriptArgs = @(), [ValidateRange(1,86400)][int]$TimeoutSec = 600, [Parameter(Mandatory)][string]$LogFile)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Owned-ProcessJob.ps1')
$console = (quser 2>$null | Select-String 'console' | ForEach-Object { ($_ -split '\s+') } | Where-Object { $_ -match '^\d+$' } | Select-Object -First 1)
if (-not $console) { throw 'INTERACTIVE_UNAVAILABLE: no logged-in console session' }
$id = [guid]::NewGuid().ToString('N')
$name = 'coach-friend-path-' + $id.Substring(0, 8)
$jobName = 'Global\Coach-Smoke-' + $id
$done = "$LogFile.done"; $ready = "$LogFile.job-ready"; $ack = "$LogFile.job-ack"
$receipt = "$LogFile.invocation.json"
Remove-Item $done, "$done.tmp", $ready, $ack, $receipt, $LogFile -ErrorAction SilentlyContinue
function Literal([string]$value) { "'" + $value.Replace("'", "''") + "'" }
$helper = Literal (Join-Path $PSScriptRoot 'Owned-ProcessJob.ps1')
$scriptLiteral = Literal $Script; $logLiteral = Literal $LogFile
$doneLiteral = Literal $done; $tempLiteral = Literal "$done.tmp"
$readyLiteral = Literal $ready; $ackLiteral = Literal $ack
# Create the job in the console session. Its parent opens a second handle before
# the worker joins, so closing the child handle cannot kill the worker early.
$inner = @"
`$ErrorActionPreference = 'Stop'
`$exitCode = 1
try {
    . $helper
    `$job = [CoachSmokeJob]::Create('$jobName')
    try {
        Set-Content -LiteralPath $readyLiteral -Value 'ready'
        `$handshake = [Diagnostics.Stopwatch]::StartNew()
        while (-not (Test-Path -LiteralPath $ackLiteral)) {
            if (`$handshake.Elapsed.TotalSeconds -gt 15) { throw 'INTERACTIVE_JOB: parent did not acquire cleanup handle' }
            Start-Sleep -Milliseconds 100
        }
        [CoachSmokeJob]::Join(`$job)
    } finally { [CoachSmokeJob]::CloseHandle(`$job) | Out-Null }
    `$global:LASTEXITCODE = 0
    & $scriptLiteral $($ScriptArgs -join ' ') *>&1 | Out-File -Encoding utf8 $logLiteral
    `$exitCode = [int]`$LASTEXITCODE
} catch {
    `$_ | Out-File -Append -Encoding utf8 $logLiteral
} finally {
    Set-Content -LiteralPath $tempLiteral -Value `$exitCode
    Move-Item -LiteralPath $tempLiteral -Destination $doneLiteral -Force
}
"@
$enc = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($inner))
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -STA -NonInteractive -ExecutionPolicy Bypass -EncodedCommand $enc"
$principal = New-ScheduledTaskPrincipal -UserId $identity -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit (New-TimeSpan -Seconds ($TimeoutSec + 15))
$job = [IntPtr]::Zero; $registered = $false; $exitCode = 1; $state = 'failed-before-start'
$sw = [Diagnostics.Stopwatch]::StartNew()
try {
    Register-ScheduledTask -TaskName $name -Action $action -Principal $principal -Settings $settings | Out-Null
    $registered = $true
    Start-ScheduledTask -TaskName $name
    while (-not (Test-Path $done)) {
        if ($job -eq [IntPtr]::Zero -and (Test-Path -LiteralPath $ready)) {
            $job = [CoachSmokeJob]::Open($jobName)
            Set-Content -LiteralPath $ack -Value 'acquired'
        }
        if ($sw.Elapsed.TotalSeconds -gt $TimeoutSec) { $state = 'timed-out'; throw 'interactive task timed out' }
        Start-Sleep -Milliseconds 200
    }
    $exitCode = [int](Get-Content $done)
    $state = if ($exitCode -eq 0) { 'completed' } else { 'worker-failed' }
} finally {
    if ($registered) {
        Stop-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue
        Unregister-ScheduledTask -TaskName $name -Confirm:$false -ErrorAction SilentlyContinue
    }
    if ($job -ne [IntPtr]::Zero) { [CoachSmokeJob]::CloseHandle($job) | Out-Null }
    @{state=$state;exitCode=$exitCode;seconds=$sw.Elapsed.TotalSeconds;cleanupJobAcquired=($job -ne [IntPtr]::Zero)} |
        ConvertTo-Json | Set-Content -LiteralPath $receipt -Encoding UTF8
    Remove-Item $ready, $ack -ErrorAction SilentlyContinue
}
exit $exitCode
