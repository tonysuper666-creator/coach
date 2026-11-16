# Run a PowerShell script in winbox's logged-in console session (agents themselves run in session 0 and cannot show or see windows).
# Uses the same mechanism as tools/dev/winbox.ps1: a one-shot Interactive scheduled task for the current user, removed afterwards.
param([Parameter(Mandatory)][string]$Script, [string[]]$ScriptArgs = @(), [int]$TimeoutSec = 600, [Parameter(Mandatory)][string]$LogFile)
$ErrorActionPreference = 'Stop'
$console = (quser 2>$null | Select-String 'console' | ForEach-Object { ($_ -split '\s+') } | Where-Object { $_ -match '^\d+$' } | Select-Object -First 1)
if (-not $console) { throw 'INTERACTIVE_UNAVAILABLE: no logged-in console session' }
$name = 'coach-friend-path-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$done = "$LogFile.done"; Remove-Item $done, $LogFile -ErrorAction SilentlyContinue
$inner = "& '$Script' $($ScriptArgs -join ' ') *>&1 | Out-File -Encoding utf8 '$LogFile'; Set-Content '$done' `$LASTEXITCODE"
$enc = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($inner))
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -STA -NonInteractive -ExecutionPolicy Bypass -EncodedCommand $enc"
$principal = New-ScheduledTaskPrincipal -UserId $identity -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit (New-TimeSpan -Seconds $TimeoutSec)
Register-ScheduledTask -TaskName $name -Action $action -Principal $principal -Settings $settings | Out-Null
try {
    Start-ScheduledTask -TaskName $name
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while (-not (Test-Path $done)) {
        if ($sw.Elapsed.TotalSeconds -gt $TimeoutSec) { Stop-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue; throw 'interactive task timed out' }
        Start-Sleep -Seconds 2
    }
    exit ([int](Get-Content $done))
} finally { Unregister-ScheduledTask -TaskName $name -Confirm:$false -ErrorAction SilentlyContinue }
