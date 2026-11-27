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
    $liveName = Get-SmokeMemoryMutexName 'd:/agent/state/coach-smoke-memory/'
    $testName = Get-SmokeMemoryMutexName $state
    Check ($liveName -ceq 'Global\Coach.SmokeMemory' -and $testName -cne $liveName) 'an isolated run does not open the live mutex'
    Check ($testName -ceq (Get-SmokeMemoryMutexName (Join-Path $state '.'))) 'equivalent paths share the same mutex'
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

    # Unreadable or null StartTime must not throw InvokeMethodOnNull or discard live protection.
    function New-SpySmokeProc([int]$ProcId, $StartTime, [long]$WorkingSet = 0L, [switch]$Unreadable) {
        $proc = [pscustomobject]@{ Id = $ProcId; WorkingSet64 = $WorkingSet }
        if ($Unreadable) { $proc | Add-Member ScriptProperty StartTime { throw 'Access is denied' } }
        else { $proc | Add-Member NoteProperty StartTime $StartTime }
        return $proc
    }
    $tRef = [datetime]::SpecifyKind([datetime]'2026-11-27T00:00:00', [DateTimeKind]::Utc)
    $mockState = Join-Path $state 'mock-start-time'
    New-Item -ItemType Directory -Force $mockState | Out-Null
    $script:MockSmoke = $null
    function Invoke-MockAdmissionCase([string]$CaseName, [scriptblock]$ProcLookup, [hashtable]$Record, [long]$BudgetBytes, [bool]$ExpectAdmit, [bool]$ExpectEntryKept) {
        Get-ChildItem -LiteralPath $mockState -Filter '*.json' | Remove-Item -Force -ErrorAction SilentlyContinue
        $entryPath = Join-Path $mockState 'peer.json'
        $Record | ConvertTo-Json | Set-Content -LiteralPath $entryPath -Encoding UTF8
        $script:MockSmoke = @{ lookup = $ProcLookup; calls = 0 }
        function global:Get-CimInstance([string]$ClassName) {
            return [pscustomobject]@{ FreePhysicalMemory = [uint64](10 * 1024 * 1024) }
        }
        function global:Get-Process([int]$Id, [string]$ErrorAction) {
            if ($Id -eq $PID) { return (Microsoft.PowerShell.Management\Get-Process -Id $PID) }
            $script:MockSmoke.calls++
            return (& $script:MockSmoke.lookup $Id $script:MockSmoke)
        }
        $admitted = $false; $deniedMsg = ''; $caseLease = $null
        try {
            $caseLease = Enter-SmokeMemory -StateDir $mockState -BudgetBytes $BudgetBytes -KeepFreeBytes 3GB -TimeoutSec 0
            $admitted = $true
        } catch {
            $deniedMsg = $_.Exception.Message
        } finally {
            if ($caseLease) { Exit-SmokeMemory $caseLease }
            Remove-Item Function:\Get-CimInstance, Function:\Get-Process -ErrorAction SilentlyContinue
        }
        $entryExists = Test-Path -LiteralPath $entryPath
        $ok = ($admitted -eq $ExpectAdmit) -and ($entryExists -eq $ExpectEntryKept) -and ($ExpectAdmit -or $deniedMsg -like 'SMOKE_MEMORY: insufficient RAM*')
        Check $ok "$CaseName (admitted=$admitted/$ExpectAdmit entryExists=$entryExists/$ExpectEntryKept err=$deniedMsg)"
    }
    $workerRec = @{ ownerPid = 42424; ownerStarted = $tRef.Ticks; budgetBytes = [long]8GB; clientPid = 0; clientStarted = 0 }
    Invoke-MockAdmissionCase 'worker start time null preserves reservation' { param($id, $s) if ($id -eq 42424) { New-SpySmokeProc 42424 $null } } $workerRec 1GB $false $true
    Invoke-MockAdmissionCase 'worker start time throw preserves reservation' { param($id, $s) if ($id -eq 42424) { New-SpySmokeProc 42424 $null -Unreadable } } $workerRec 1GB $false $true
    Invoke-MockAdmissionCase 'worker start time normal preserves reservation' { param($id, $s) if ($id -eq 42424) { New-SpySmokeProc 42424 $tRef } } $workerRec 1GB $false $true

    $clientRec = @{ ownerPid = 42424; ownerStarted = $tRef.Ticks; budgetBytes = [long]6GB; clientPid = 42425; clientStarted = $tRef.Ticks }
    Invoke-MockAdmissionCase 'client start time null keeps full budget' {
        param($id, $s)
        if ($id -eq 42424) { return (New-SpySmokeProc 42424 $tRef) }
        if ($id -eq 42425) { return (New-SpySmokeProc 42425 $null -WorkingSet 2GB) }
    } $clientRec 2GB $false $true
    Invoke-MockAdmissionCase 'client start time throw keeps full budget' {
        param($id, $s)
        if ($id -eq 42424) { return (New-SpySmokeProc 42424 $tRef) }
        if ($id -eq 42425) { return (New-SpySmokeProc 42425 $null -WorkingSet 2GB -Unreadable) }
    } $clientRec 2GB $false $true
    Invoke-MockAdmissionCase 'client start time normal deducts working set' {
        param($id, $s)
        if ($id -eq 42424) { return (New-SpySmokeProc 42424 $tRef) }
        if ($id -eq 42425) { return (New-SpySmokeProc 42425 $tRef -WorkingSet 2GB) }
    } $clientRec 2GB $true $true

    # Real exiting child process: stale Process handle yields 0L, and concurrent sampling exit prunes and admits.
    $raceChild = Start-Process powershell.exe -ArgumentList '-NoProfile -NonInteractive -Command "Start-Sleep -Seconds 2"' -PassThru -WindowStyle Hidden
    $raceId = $raceChild.Id
    $raceTicks = Get-SmokeProcessStartTicks $raceChild
    $raceStale = Microsoft.PowerShell.Management\Get-Process -Id $raceId -ErrorAction SilentlyContinue
    $raceGone = $raceChild.WaitForExit(20000)
    $raceChild.Dispose(); $raceChild = $null; [GC]::Collect(); [GC]::WaitForPendingFinalizers()
    Check ($raceTicks -gt 0L -and $raceGone -and $null -ne $raceStale -and (Get-SmokeProcessStartTicks $raceStale) -eq 0L) "exited process handle yields 0 ticks without throwing (ticks=$raceTicks gone=$raceGone)"
    Invoke-MockAdmissionCase 'worker exiting during sampling is pruned and admits' {
        param($id, $s)
        if ($id -eq 42424) {
            if ($s.calls -eq 1) { return $raceStale }
            return $null
        }
    } @{ ownerPid = 42424; ownerStarted = $raceTicks; budgetBytes = [long]8GB; clientPid = 0; clientStarted = 0 } 1GB $true $false

    $exitingState = Join-Path $state 'concurrent-exit'
    New-Item -ItemType Directory -Force $exitingState | Out-Null
    $exitingScript = Join-Path $exitingState 'exiting-peer.ps1'
    @'
param($Source, $State)
$ErrorActionPreference = 'Stop'
. $Source
$self = Get-Process -Id $PID
$ticks = Get-SmokeProcessStartTicks $self
Write-SmokeReservation (Join-Path $State "$PID.json") @{
    ownerPid = $PID; ownerStarted = $ticks;
    budgetBytes = [long]::MaxValue; clientPid = 0; clientStarted = 0
}
Start-Sleep -Seconds 2
'@ | Set-Content -LiteralPath $exitingScript -Encoding UTF8
    $exitingArgs = '-NoProfile -NonInteractive -WindowStyle Hidden -File "{0}" -Source "{1}" -State "{2}"' -f
        $exitingScript, (Join-Path $PSScriptRoot 'Smoke-Memory.ps1'), $exitingState
    $exitingProc = Start-Process powershell.exe -ArgumentList $exitingArgs -WindowStyle Hidden -PassThru
    $null = $exitingProc.Handle
    $children += $exitingProc
    Wait-File (Join-Path $exitingState "$($exitingProc.Id).json")
    $admittedAfterExit = $false
    try {
        $lease = Enter-SmokeMemory -StateDir $exitingState -BudgetBytes 1MB -TimeoutSec 20
        $admittedAfterExit = $true
        Exit-SmokeMemory $lease; $lease = $null
    } finally {
        if ($lease) { Exit-SmokeMemory $lease; $lease = $null }
    }
    if (-not $exitingProc.WaitForExit(20000)) { throw 'Exiting peer child did not finish in time' }
    Check ($admittedAfterExit -and @(Get-ChildItem -LiteralPath $exitingState -Filter '*.json').Count -eq 0) "concurrent exiting worker unblocks queued admission cleanly (admitted=$admittedAfterExit)"
    Write-Host "SMOKE_MEMORY $checks/17"
} finally {
    Exit-SmokeMemory $lease
    foreach ($p in $children) {
        $live = Get-Process -Id $p.Id -ErrorAction SilentlyContinue
        $liveTicks = Get-SmokeProcessStartTicks $live
        $pTicks = Get-SmokeProcessStartTicks $p
        if ($live -and $liveTicks -gt 0L -and $liveTicks -eq $pTicks) { Stop-Process -Id $p.Id -Force }
    }
}
