# Run on winbox through PowerShell, outside an interactive task. All fixtures
# are disposable descendants; no other process is enumerated or terminated.
param([Parameter(Mandatory)][string]$OutDir)
$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Force $OutDir | Out-Null
$OutDir=(Resolve-Path $OutDir).Path
$runner=Join-Path $PSScriptRoot 'Run-Interactive.ps1'
$checks=New-Object Collections.ArrayList
function Check([string]$name,[bool]$ok) {
    [void]$checks.Add(@{name=$name;ok=$ok})
    if(-not $ok){throw "FAIL $name"}
}
function Run-Case([string]$name,[string]$body,[int]$timeout=20) {
    $path=Join-Path $OutDir "$name.ps1"
    $body | Set-Content -LiteralPath $path -Encoding UTF8
    $script:caseExit=$null; $script:caseThrew=$false
    try { & $runner -Script $path -LogFile (Join-Path $OutDir "$name.log") -TimeoutSec $timeout; $script:caseExit=$LASTEXITCODE }
    catch { $script:caseThrew=$true }
    $receipt=Get-Content (Join-Path $OutDir "$name.log.invocation.json") -Raw | ConvertFrom-Json
    Check "$name acquired owned job" $receipt.cleanupJobAcquired
    return $receipt
}
$errorText=''; $sentinel=$null
try {
    $sentinel=Start-Process powershell.exe -ArgumentList '-NoProfile -NonInteractive -Command "Start-Sleep -Seconds 120"' -PassThru -WindowStyle Hidden
    $sentinelStart=$sentinel.StartTime
    $r=Run-Case 'success' '$global:LASTEXITCODE=0'
    Check 'success preserves exit 0' ($caseExit -eq 0 -and $r.state -eq 'completed')
    $r=Run-Case 'failure' '$global:LASTEXITCODE=7'
    Check 'worker failure preserves exit 7' ($caseExit -eq 7 -and $r.state -eq 'worker-failed')
    $r=Run-Case 'exception' "throw 'fixture exception'"
    Check 'worker exception is a failure' ($caseExit -eq 1 -and $r.state -eq 'worker-failed')
    # This worker never discovers an AppPid and never reaches its own finally.
    # A child would start a delayed grandchild if the runner leaked the tree.
    $pidFile=(Join-Path $OutDir 'delayed-child.json').Replace("'","''")
    $lateFile=(Join-Path $OutDir 'late-grandchild.txt').Replace("'","''")
    $grandchildFile=(Join-Path $OutDir 'grandchild.json').Replace("'","''")
    $grandchildCode="Start-Sleep -Seconds 12; Set-Content -LiteralPath '$lateFile' -Value 'leaked'; Start-Sleep -Seconds 60"
    $grandchildEncoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($grandchildCode))
    $childCode=@"
`$grandchild=Start-Process powershell.exe -ArgumentList '-NoProfile -NonInteractive -EncodedCommand $grandchildEncoded' -PassThru -WindowStyle Hidden
@{pid=`$grandchild.Id;start=`$grandchild.StartTime.ToUniversalTime().ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath '$grandchildFile'
"@
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($childCode))
    $body=@"
`$child=Start-Process powershell.exe -ArgumentList '-NoProfile -NonInteractive -EncodedCommand $encoded' -PassThru -WindowStyle Hidden
@{pid=`$child.Id;start=`$child.StartTime.ToUniversalTime().ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath '$pidFile'
Start-Sleep -Seconds 60
"@
    $r=Run-Case 'timeout' $body 7
    Check 'timeout has explicit invocation failure' ($caseThrew -and $r.state -eq 'timed-out' -and $r.exitCode -eq 1)
    Check 'timeout cannot produce a successful completion receipt' (-not (Test-Path (Join-Path $OutDir 'timeout.log.done')))
    $owned=Get-Content (Join-Path $OutDir 'delayed-child.json') -Raw | ConvertFrom-Json
    $until=(Get-Date).AddSeconds(3)
    do {
        $child=Get-Process -Id $owned.pid -ErrorAction SilentlyContinue
        $same=$child -and $child.StartTime.ToUniversalTime().ToString('o') -eq $owned.start
        if($same){Start-Sleep -Milliseconds 100}
    } while($same -and (Get-Date) -lt $until)
    Check 'timeout removes child before AppPid discovery' (-not $same)
    $owned=Get-Content (Join-Path $OutDir 'grandchild.json') -Raw | ConvertFrom-Json
    $grandchild=Get-Process -Id $owned.pid -ErrorAction SilentlyContinue
    Check 'timeout removes descendant whose parent already exited' (-not ($grandchild -and $grandchild.StartTime.ToUniversalTime().ToString('o') -eq $owned.start))
    Check 'delayed launch never ran' (-not (Test-Path (Join-Path $OutDir 'late-grandchild.txt')))
    $sentinel.Refresh()
    Check 'process outside the invocation survives' (-not $sentinel.HasExited -and $sentinel.StartTime -eq $sentinelStart)
} catch { $errorText=$_.Exception.Message } finally {
    if($sentinel){$sentinel.Refresh();if(-not $sentinel.HasExited -and $sentinel.StartTime -eq $sentinelStart){Stop-Process -Id $sentinel.Id -Force}}
    @{checks=$checks;error=$errorText;time=(Get-Date).ToUniversalTime().ToString('o')} | ConvertTo-Json -Depth 5 |
        Set-Content (Join-Path $OutDir 'cleanup-result.json') -Encoding UTF8
}
"INTERACTIVE_CLEANUP $(@($checks|Where-Object ok).Count)/$($checks.Count) error=$errorText"
if($errorText){exit 1}
