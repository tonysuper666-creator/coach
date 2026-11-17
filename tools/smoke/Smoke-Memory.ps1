# Shared by independent smoke workers. The mutex covers admission, never UI work.
function Write-SmokeReservation([string]$File, $Record) {
    # A killed worker must not leave a truncated JSON reservation for its peers.
    $Record | ConvertTo-Json | Set-Content -LiteralPath "$File.tmp" -Encoding UTF8
    Move-Item -LiteralPath "$File.tmp" -Destination $File -Force
}

function Enter-SmokeMemory(
    [string]$StateDir = 'D:/agent/state/coach-smoke-memory',
    [ValidateRange(1, [long]::MaxValue)][long]$BudgetBytes = 2GB,
    [ValidateRange(0, [long]::MaxValue)][long]$KeepFreeBytes = 3GB,
    [ValidateRange(0, 86400)][int]$TimeoutSec = 600
) {
    New-Item -ItemType Directory -Force $StateDir | Out-Null
    $owner = Get-Process -Id $PID
    $file = Join-Path $StateDir "$PID.json"
    $mutex = [Threading.Mutex]::new($false, 'Global\Coach.SmokeMemory')
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
                    $p = Get-Process -Id $r.ownerPid -ErrorAction SilentlyContinue
                    if (!$p -or $p.StartTime.ToUniversalTime().Ticks -ne $r.ownerStarted) {
                        Remove-Item -LiteralPath $entry.FullName
                        continue
                    }
                    $used = 0L
                    if ($r.clientPid) {
                        $client = Get-Process -Id $r.clientPid -ErrorAction SilentlyContinue
                        if ($client -and $client.StartTime.ToUniversalTime().Ticks -eq $r.clientStarted) {
                            $used = $client.WorkingSet64
                        }
                    }
                    # Available RAM already excludes the live client's RSS. Reserve
                    # only its remaining allowance, including simultaneous startups.
                    $pending += [math]::Max(0L, [long]$r.budgetBytes - $used)
                    $ahead += [int]$r.ownerPid
                }
                $free = [long](Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory * 1KB
                if ($free - $pending - $BudgetBytes -ge $KeepFreeBytes) {
                    Write-SmokeReservation $file @{
                        ownerPid=$PID; ownerStarted=$owner.StartTime.ToUniversalTime().Ticks;
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
    $mutex = [Threading.Mutex]::new($false, 'Global\Coach.SmokeMemory')
    $held = $false
    try {
        try { $held = $mutex.WaitOne(10000) }
        catch [Threading.AbandonedMutexException] { $held = $true }
        if (!$held) { throw 'SMOKE_MEMORY: client registration lock timed out' }
        $r = Get-Content -LiteralPath $Lease -Raw | ConvertFrom-Json
        if ($r.ownerPid -ne $PID -or $r.ownerStarted -ne (Get-Process -Id $PID).StartTime.ToUniversalTime().Ticks) {
            throw 'SMOKE_MEMORY: reservation belongs to another worker'
        }
        $r.clientPid = $client.Id; $r.clientStarted = $client.StartTime.ToUniversalTime().Ticks
        Write-SmokeReservation $Lease $r
    } finally { if ($held) { $mutex.ReleaseMutex() }; $mutex.Dispose() }
}

function Exit-SmokeMemory([string]$Lease) {
    # Each worker removes only its own file; admission prunes abandoned entries
    # using PID + StartTime if a Job Object terminates the worker first.
    if (!$Lease -or (Split-Path $Lease -Leaf) -ne "$PID.json") { return }
    $mutex = [Threading.Mutex]::new($false, 'Global\Coach.SmokeMemory')
    $held = $false
    try {
        try { $held = $mutex.WaitOne(10000) }
        catch [Threading.AbandonedMutexException] { $held = $true }
        if (!$held) { throw 'SMOKE_MEMORY: release lock timed out' }
        Remove-Item -LiteralPath $Lease -ErrorAction SilentlyContinue
    } finally { if ($held) { $mutex.ReleaseMutex() }; $mutex.Dispose() }
}
