# Shared by independent smoke workers. The mutex covers admission, never UI work.
function Get-SmokeMemoryMutexName([string]$StateDir) {
    $path = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($StateDir)
    $path = [IO.Path]::GetFullPath($path).TrimEnd([char]'\').ToUpperInvariant()
    # Keep the live name compatible with workers from older checkouts.
    if ($path -eq 'D:\AGENT\STATE\COACH-SMOKE-MEMORY') { return 'Global\Coach.SmokeMemory' }
    # An isolated ledger must not create the live mutex under a different session's token.
    $hash = [Security.Cryptography.SHA256]::Create()
    try {
        $key = [BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($path))).Replace('-', '')
        return "Global\Coach.SmokeMemory.$key"
    } finally { $hash.Dispose() }
}

function Write-SmokeReservation([string]$File, $Record) {
    # A killed worker must not leave a truncated JSON reservation for its peers.
    $Record | ConvertTo-Json | Set-Content -LiteralPath "$File.tmp" -Encoding UTF8
    Move-Item -LiteralPath "$File.tmp" -Destination $File -Force
}

function Get-SmokeProcessStartTicks($Process) {
    if (-not $Process) { return 0L }
    try {
        $st = $Process.StartTime
        if ($null -ne $st) { return ([datetime]$st).ToUniversalTime().Ticks }
    } catch { }
    return 0L
}

function Get-SmokeProcessState([int]$ProcessId, [long]$StartTimeTicks = 0L) {
    if ($ProcessId -le 0) { return [pscustomobject]@{ State = 'exited'; Process = $null } }
    $proc = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if (-not $proc) { return [pscustomobject]@{ State = 'exited'; Process = $null } }
    if ($StartTimeTicks -le 0L) { return [pscustomobject]@{ State = 'unverified'; Process = $proc } }
    $observedTicks = Get-SmokeProcessStartTicks $proc
    if ($observedTicks -le 0L) {
        # The process may exit right between Get-Process and reading StartTime.
        if (-not (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) {
            return [pscustomobject]@{ State = 'exited'; Process = $null }
        }
        return [pscustomobject]@{ State = 'unverified'; Process = $proc }
    }
    if ($observedTicks -ne $StartTimeTicks) {
        return [pscustomobject]@{ State = 'exited'; Process = $null }
    }
    return [pscustomobject]@{ State = 'alive'; Process = $proc }
}

function Enter-SmokeMemory(
    [string]$StateDir = 'D:/agent/state/coach-smoke-memory',
    [ValidateRange(1, [long]::MaxValue)][long]$BudgetBytes = 2GB,
    [ValidateRange(0, [long]::MaxValue)][long]$KeepFreeBytes = 3GB,
    [ValidateRange(0, 86400)][int]$TimeoutSec = 600
) {
    New-Item -ItemType Directory -Force $StateDir | Out-Null
    $owner = Get-Process -Id $PID
    $ownerTicks = Get-SmokeProcessStartTicks $owner
    if ($ownerTicks -le 0L) { throw 'SMOKE_MEMORY: could not read current process StartTime' }
    $file = Join-Path $StateDir "$PID.json"
    $mutex = [Threading.Mutex]::new($false, (Get-SmokeMemoryMutexName $StateDir))
    $clock = [Diagnostics.Stopwatch]::StartNew()
    $lastNotice = -30
    try {
        while ($true) {
            $held = $false
            try {
                try { $held = $mutex.WaitOne(10000) }
                catch [Threading.AbandonedMutexException] { $held = $true }
                if (!$held) { throw 'SMOKE_MEMORY: admission lock timed out' }
                $pending = 0L; $ahead = @()
                foreach ($entry in @(Get-ChildItem -LiteralPath $StateDir -Filter '*.json')) {
                    $r = Get-Content -LiteralPath $entry.FullName -Raw | ConvertFrom-Json
                    $ownerPid = if ($r -and $null -ne $r.ownerPid) { [int]$r.ownerPid } else { 0 }
                    $ownerStarted = if ($r -and $null -ne $r.ownerStarted) { [long]$r.ownerStarted } else { 0L }
                    $ownerCheck = Get-SmokeProcessState $ownerPid $ownerStarted
                    if ($ownerCheck.State -eq 'exited') {
                        Remove-Item -LiteralPath $entry.FullName -ErrorAction SilentlyContinue
                        continue
                    }
                    $used = 0L
                    $clientPid = if ($r -and $null -ne $r.clientPid) { [int]$r.clientPid } else { 0 }
                    $clientStarted = if ($r -and $null -ne $r.clientStarted) { [long]$r.clientStarted } else { 0L }
                    if ($ownerCheck.State -eq 'alive' -and $clientPid -gt 0 -and $clientStarted -gt 0L) {
                        $clientCheck = Get-SmokeProcessState $clientPid $clientStarted
                        if ($clientCheck.State -eq 'alive' -and $clientCheck.Process) {
                            try {
                                $ws = $clientCheck.Process.WorkingSet64
                                if ($null -ne $ws) { $used = [long]$ws }
                            } catch { }
                        }
                    }
                    # Available RAM already excludes the live client's RSS. Reserve
                    # only its remaining allowance, including simultaneous startups.
                    $pending += [math]::Max(0L, [long]$r.budgetBytes - $used)
                    $ahead += $ownerPid
                }
                $free = [long](Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory * 1KB
                if ($free - $pending - $BudgetBytes -ge $KeepFreeBytes) {
                    Write-SmokeReservation $file @{
                        ownerPid=$PID; ownerStarted=$ownerTicks;
                        budgetBytes=$BudgetBytes; clientPid=0; clientStarted=0}
                    return $file
                }
                if ($clock.Elapsed.TotalSeconds -ge $TimeoutSec) {
                    throw "SMOKE_MEMORY: insufficient RAM after $TimeoutSec seconds (no client launched)"
                }
                if ($clock.Elapsed.TotalSeconds - $lastNotice -ge 30) {
                    $lastNotice = $clock.Elapsed.TotalSeconds
                    Write-Host ("Smoke waiting for RAM: free={0:N2}GiB pending={1:N2}GiB; workers ahead=[{2}]; retry in 2s, at most {3:N0}s remaining" -f
                        ($free/1GB), ($pending/1GB), ($ahead -join ','), ($TimeoutSec-$clock.Elapsed.TotalSeconds))
                }
            } finally { if ($held) { $mutex.ReleaseMutex() } }
            Start-Sleep -Seconds 2
        }
    } finally { $mutex.Dispose() }
}

function Set-SmokeMemoryClient([string]$Lease, [int]$ClientPid) {
    $client = Get-Process -Id $ClientPid -ErrorAction Stop
    $mutex = [Threading.Mutex]::new($false, (Get-SmokeMemoryMutexName (Split-Path -Parent $Lease)))
    $held = $false
    try {
        try { $held = $mutex.WaitOne(10000) }
        catch [Threading.AbandonedMutexException] { $held = $true }
        if (!$held) { throw 'SMOKE_MEMORY: client registration lock timed out' }
        $r = Get-Content -LiteralPath $Lease -Raw | ConvertFrom-Json
        $selfTicks = Get-SmokeProcessStartTicks (Get-Process -Id $PID)
        if ($r.ownerPid -ne $PID -or $selfTicks -le 0L -or $r.ownerStarted -ne $selfTicks) {
            throw 'SMOKE_MEMORY: reservation belongs to another worker'
        }
        $clientTicks = Get-SmokeProcessStartTicks $client
        if ($clientTicks -gt 0L) {
            $r.clientPid = $client.Id; $r.clientStarted = $clientTicks
            Write-SmokeReservation $Lease $r
        }
    } finally { if ($held) { $mutex.ReleaseMutex() }; $mutex.Dispose() }
}

function Exit-SmokeMemory([string]$Lease) {
    # Each worker removes only its own file; admission prunes abandoned entries
    # using PID + StartTime if a Job Object terminates the worker first.
    if (!$Lease -or (Split-Path $Lease -Leaf) -ne "$PID.json") { return }
    $mutex = [Threading.Mutex]::new($false, (Get-SmokeMemoryMutexName (Split-Path -Parent $Lease)))
    $held = $false
    try {
        try { $held = $mutex.WaitOne(10000) }
        catch [Threading.AbandonedMutexException] { $held = $true }
        if (!$held) { throw 'SMOKE_MEMORY: release lock timed out' }
        Remove-Item -LiteralPath $Lease -ErrorAction SilentlyContinue
    } finally { if ($held) { $mutex.ReleaseMutex() }; $mutex.Dispose() }
}
