#Requires -Version 5.1
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$Root,
      [Parameter(Mandatory=$true)][ValidatePattern('^[a-f0-9]{40}$')][string]$SourceHead)
$ErrorActionPreference = 'Stop'
[Diagnostics.Process]::GetCurrentProcess().PriorityClass = 'Idle'
$Root = [IO.Path]::GetFullPath($Root)
$out = Join-Path $Root 'artifacts'
New-Item -ItemType Directory -Path $out -Force | Out-Null
$slots = @(0,1 | ForEach-Object { New-Object Threading.Mutex($false, "Global\CoachHeavySlot$_") })
$slot = -1
$clock = [Diagnostics.Stopwatch]::StartNew()
try {
    while ($slot -lt 0) {
        foreach ($n in 0,1) {
            try { $taken = $slots[$n].WaitOne(0) }
            catch [Threading.AbandonedMutexException] { $taken = $true }
            if ($taken) { $slot = $n; break }
        }
        if ($slot -ge 0) { break }
        if ($clock.Elapsed.TotalSeconds -gt 55) { throw 'Both existing Coach slots occupied; no compiler started.' }
        Start-Sleep -Seconds 2
    }
    $free = (Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1MB
    if ($free -lt 4) { throw 'Less than 4 GiB free; no compiler started.' }
    $compiler = Join-Path $env:SystemRoot 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
    $builds = @()
    foreach ($component in @(@('CoachIso','coach-iso.exe'), @('CoachAuthCommit','coach-auth.exe'))) {
        $source = Join-Path $Root ('source\' + $component[0] + '.cs')
        $target = Join-Path $out $component[1]
        if (Test-Path -LiteralPath $target) { throw 'Output already exists; use a fresh owned directory.' }
        $arguments = @('/nologo','/target:exe',('/main:'+$component[0]),('/out:'+$target),$source)
        # Production entry only. No COACH_ISO_TEST define or harness source.
        $log = & $compiler @arguments 2>&1
        $code = $LASTEXITCODE
        $log | Set-Content -Encoding UTF8 -LiteralPath ($target + '.compile.log')
        if ($code -ne 0) { throw ('Compiler failed: ' + $code) }
        $builds += [ordered]@{name=$component[1];sourceSha256=(Get-FileHash $source -Algorithm SHA256).Hash.ToLowerInvariant();
            compiler=$compiler;compilerSha256=(Get-FileHash $compiler -Algorithm SHA256).Hash.ToLowerInvariant();
            arguments=$arguments;exit=$code;size=(Get-Item $target).Length;
            sha256=(Get-FileHash $target -Algorithm SHA256).Hash.ToLowerInvariant();
            authenticode=(Get-AuthenticodeSignature $target).Status.ToString()}
    }
    # Metadata-only application discovery, following LocalCodexBookTransport.kt:35-47.
    # No CLI invocation and no auth/config/history/book reads.
    $candidates = @()
    foreach ($entry in ($env:PATH -split ';')) {
        if ([string]::IsNullOrWhiteSpace($entry)) { continue }
        $entry = $entry.Trim('"')
        $paths = @((Join-Path $entry 'codex.exe'))
        foreach ($pair in @(@('codex-win32-x64','x86_64'),@('codex-win32-arm64','aarch64'))) {
            $paths += Join-Path $entry ('node_modules\@openai\codex\node_modules\@openai\'+$pair[0]+'\vendor\'+$pair[1]+'-pc-windows-msvc\bin\codex.exe')
        }
        foreach ($p in $paths) {
            if (!(Test-Path -LiteralPath $p -PathType Leaf)) { continue }
            $candidates += [ordered]@{path=[IO.Path]::GetFullPath($p);size=(Get-Item $p).Length;
                sha256=(Get-FileHash $p -Algorithm SHA256).Hash.ToLowerInvariant();
                authenticode=(Get-AuthenticodeSignature $p).Status.ToString();approvedForManifest=$false}
        }
    }
    # Legacy csc embeds a new PE timestamp and MVID. Source/compiler provenance
    # does not authorize another build's bytes. Never normalize those fields.
    $receipt = [ordered]@{sourceHead=$SourceHead;
        approvalPolicy='exact-artifact-sha256-every-rebuild-requires-new-manifest-approval';
        byteReproducibilityClaim=$false;metadataDifferenceExemptions=@();
        utc=[DateTime]::UtcNow.ToString('o');os=[Environment]::OSVersion.VersionString;
        slot=$slot;freeGiB=[math]::Round($free,2);priority='Idle';builds=$builds;
        codexCandidates=$candidates;releaseKeyRead=0;authRead=0;bookRead=0;componentLaunch=0}
    $receipt | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $Root 'build-receipt.json')
    $receipt | ConvertTo-Json -Depth 8
} finally {
    if ($slot -ge 0) { $slots[$slot].ReleaseMutex() }
    foreach ($mutex in $slots) { $mutex.Dispose() }
}
