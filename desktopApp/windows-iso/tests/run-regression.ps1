param([switch]$Worker, [string]$RunRoot, [string]$ProductionExe, [string]$RegressionExe)
$ErrorActionPreference = 'Stop'
if ($Worker) {
    & $RegressionExe $RunRoot $ProductionExe *> (Join-Path $RunRoot 'worker.log')
    $code = $LASTEXITCODE
    [IO.File]::WriteAllText((Join-Path $RunRoot 'worker.exit'), [string]$code)
    exit $code
}

# All inputs are synthetic; no authentication, model, account or real library access.
$run = Join-Path $env:TEMP ('expert-iso-regression-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $run | Out-Null
$prod = Join-Path $run 'coach-iso.exe'
$test = Join-Path $run 'coach-iso-regression.exe'
$source = Join-Path $PSScriptRoot '..\CoachIso.cs'
$harness = Join-Path $PSScriptRoot 'CoachIsoRegression.cs'
$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
$task = 'Expert-Iso-Regression-' + [guid]::NewGuid().ToString('N')
$result = 1
try {
    Write-Output "COMMAND: $csc /nologo /optimize+ /out:$prod $source"
    & $csc /nologo /optimize+ "/out:$prod" $source
    if ($LASTEXITCODE -ne 0) { throw "production_compile_exit=$LASTEXITCODE" }
    Write-Output 'production_compile_exit=0'
    Write-Output "COMMAND: $csc /nologo /optimize+ /define:COACH_ISO_TEST /main:CoachIsoRegression /r:System.Web.Extensions.dll /out:$test $source $harness"
    & $csc /nologo /optimize+ /define:COACH_ISO_TEST /main:CoachIsoRegression /r:System.Web.Extensions.dll "/out:$test" $source $harness
    if ($LASTEXITCODE -ne 0) { throw "regression_compile_exit=$LASTEXITCODE" }
    Write-Output 'regression_compile_exit=0'
    $hash = (Get-FileHash $source -Algorithm SHA256).Hash
    Write-Output "source_sha256=$hash"
    $action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Worker -RunRoot `"$run`" -ProductionExe `"$prod`" -RegressionExe `"$test`""
    $principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Limited
    $settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit (New-TimeSpan -Seconds 120)
    Register-ScheduledTask -TaskName $task -Action $action -Principal $principal -Settings $settings | Out-Null
    Write-Output "COMMAND (interactive worker): $test $run $prod"
    Start-ScheduledTask -TaskName $task
    $deadline = (Get-Date).AddSeconds(110)
    while (!(Test-Path (Join-Path $run 'worker.exit')) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500 }
    if (!(Test-Path (Join-Path $run 'worker.exit'))) { throw 'worker timed out' }
    Get-Content (Join-Path $run 'worker.log')
    Get-Content (Join-Path $run 'result.json')
    $result = [int](Get-Content (Join-Path $run 'worker.exit'))
    Write-Output "worker_exit=$result"
} finally {
    $scheduled = Get-ScheduledTask -TaskName $task -ErrorAction SilentlyContinue
    if ($scheduled) {
        if ($scheduled.State -eq 'Running') { Stop-ScheduledTask -TaskName $task }
        Unregister-ScheduledTask -TaskName $task -Confirm:$false
    }
    Write-Output ('task_residual=' + [int][bool](Get-ScheduledTask -TaskName $task -ErrorAction SilentlyContinue))
    # Processes/ACLs/profiles are checked per case in the harness before recycling files.
    $remaining = @(Get-CimInstance Win32_Process | Where-Object { $_.ProcessId -ne $PID -and $_.CommandLine -like "*$run*" })
    Write-Output ('process_residual=' + $remaining.Count)
    if ($remaining.Count -ne 0) { $result = 1 } else {
        Add-Type -AssemblyName Microsoft.VisualBasic
        [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteDirectory($run, 'OnlyErrorDialogs', 'SendToRecycleBin')
    }
    Write-Output ('run_directory_residual=' + [int](Test-Path $run))
}
exit $result
