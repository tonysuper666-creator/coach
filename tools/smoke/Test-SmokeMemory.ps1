param([Parameter(Mandatory)][string]$OutDir)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Smoke-Memory.ps1')
New-Item -ItemType Directory -Force $OutDir | Out-Null
$state = Join-Path $OutDir ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $state | Out-Null
$checks = 0; $children = @(); $lease = $null
function Check([bool]$Ok, [string]$Name) {
    if (!$Ok) { throw "FAIL: $Name" }
    $script:checks++; Write-Host "PASS: $Name"
}
function Free-Bytes { [long](Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory * 1KB }
function Wait-File([string]$File) {
    $until = (Get-Date).AddSeconds(20)
    while (!(Test-Path $File) -and (Get-Date) -lt $until) { Start-Sleep -Milliseconds 100 }
    if (!(Test-Path $File)) { throw 'Child did not reach memory admission' }
}
try {
    $denied = $false
    try { $lease = Enter-SmokeMemory -StateDir $state -BudgetBytes ((Free-Bytes) + 1GB) -TimeoutSec 0 }
    catch { $denied = $_.Exception.Message -like 'SMOKE_MEMORY: insufficient RAM*' }
    Check ($denied -and @(Get-ChildItem $state -Filter '*.json').Count -eq 0) 'insufficient RAM rejects before creating a reservation'

    # A reused PID must not keep a dead worker's allowance reserved.
    @{ownerPid=$PID;ownerStarted=1;budgetBytes=[long]::MaxValue;clientPid=0;clientStarted=0} |
        ConvertTo-Json | Set-Content (Join-Path $state 'stale.json') -Encoding UTF8
    $lease = Enter-SmokeMemory -StateDir $state -BudgetBytes 1MB -TimeoutSec 0
    Check (!(Test-Path (Join-Path $state 'stale.json'))) 'PID plus StartTime prunes an abandoned reservation'
    Set-SmokeMemoryClient $lease $PID
    $record = Get-Content $lease -Raw | ConvertFrom-Json
    Check ($record.clientPid -eq $PID -and $record.clientStarted -eq (Get-Process -Id $PID).StartTime.ToUniversalTime().Ticks) 'client registration records the exact process lifetime'
    Exit-SmokeMemory $lease; $lease = $null
    Check (@(Get-ChildItem $state -Filter '*.json').Count -eq 0) 'normal completion releases its reservation'

    # Reservations use no actual memory. Two processes each fit alone but their
    # combined allowances must queue until the first releases its reservation.
    $budget = [long](((Free-Bytes) - 3GB) * 0.75)
    if ($budget -lt 1GB) { throw 'Not enough free RAM to exercise concurrent admission' }
    $child = Join-Path $state 'child.ps1'
    @'
param($Source,$State,[long]$Budget,$Name,[int]$HoldSeconds)
$ErrorActionPreference='Stop'
. $Source
$lease=Enter-SmokeMemory -StateDir $State -BudgetBytes $Budget -TimeoutSec 20
try {
 [DateTime]::UtcNow.ToString('o')|Set-Content (Join-Path $State "$Name.enter")
 Start-Sleep -Seconds $HoldSeconds
} finally {
 [DateTime]::UtcNow.ToString('o')|Set-Content (Join-Path $State "$Name.release")
 Exit-SmokeMemory $lease
}
[DateTime]::UtcNow.ToString('o')|Set-Content (Join-Path $State "$Name.exit")
'@ | Set-Content $child -Encoding UTF8
    foreach ($name in @('first','second')) {
        $hold = if ($name -eq 'first') { 6 } else { 0 }
        $args = '-NoProfile -NonInteractive -WindowStyle Hidden -File "{0}" -Source "{1}" -State "{2}" -Budget {3} -Name {4} -HoldSeconds {5}' -f
            $child, (Join-Path $PSScriptRoot 'Smoke-Memory.ps1'), $state, $budget, $name, $hold
        $p = Start-Process powershell.exe -ArgumentList $args -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $state "$name.out.log") -RedirectStandardError (Join-Path $state "$name.err.log")
        # Retain the handle before exit; Windows PowerShell otherwise loses the
        # exit code when Start-Process's process has already terminated.
        $null = $p.Handle
        $children += $p
        if ($name -eq 'first') { Wait-File (Join-Path $state 'first.enter') }
    }
    Start-Sleep -Seconds 2
    Check (!(Test-Path (Join-Path $state 'second.enter'))) 'a second process queues instead of overcommitting startup RAM'
    foreach ($p in $children) { if (!$p.WaitForExit(30000)) { throw 'Admission child timed out' }; if ($p.ExitCode -ne 0) { throw 'Admission child failed' } }
    $firstExit = [DateTime](Get-Content (Join-Path $state 'first.release'))
    $secondEnter = [DateTime](Get-Content (Join-Path $state 'second.enter'))
    Check ($secondEnter -ge $firstExit -and @(Get-ChildItem $state -Filter '*.json').Count -eq 0) 'queued process resumes after release and both reservations are removed'
    Write-Host "SMOKE_MEMORY $checks/6"
} finally {
    Exit-SmokeMemory $lease
    foreach ($p in $children) {
        $live = Get-Process -Id $p.Id -ErrorAction SilentlyContinue
        if ($live -and $live.StartTime -eq $p.StartTime) { Stop-Process -Id $p.Id -Force }
    }
}
