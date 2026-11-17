# Run through Run-Interactive.ps1 with a runtime spec for an owned development checkout.
param([Parameter(Mandatory)][string]$RuntimeSpec, [Parameter(Mandatory)][string]$OutDir)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
. (Join-Path $PSScriptRoot 'Win-Drive.ps1')
$spec=Get-Content -LiteralPath $RuntimeSpec -Raw -Encoding UTF8|ConvertFrom-Json
New-Item -ItemType Directory -Force $OutDir|Out-Null
$OutDir=(Resolve-Path $OutDir).Path
$env:COACH_LIBRARY_DIR=Join-Path $OutDir 'books'
$env:COACH_CLIENT_DIR=Join-Path $OutDir 'client'
$env:COACH_TOKEN_FILE=Join-Path $OutDir 'unused-token'
$env:COACH_URL='http://127.0.0.1:59998'
$script:RepoMarker=$spec.repo
Mark-ExistingClients
$checks=New-Object Collections.ArrayList
function Check([string]$name,[bool]$ok){
    [void]$checks.Add([pscustomobject]@{name=$name;ok=$ok})
    if(-not$ok){throw "FAIL $name"}
}
function Minimize-Own {
    [WD]::ShowWindow($script:Win,6)|Out-Null
    Start-Sleep -Milliseconds 200
    if(-not[WD]::IsIconic($script:Win)){throw 'Test did not minimize its owned window'}
}
$app=Start-Process $spec.executable -ArgumentList $spec.arguments -WorkingDirectory $spec.repo -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $OutDir 'client.out.log') -RedirectStandardError (Join-Path $OutDir 'client.err.log')
$errorText=''
try {
    $until=(Get-Date).AddSeconds(45)
    while(-not(Find-CoachWindow) -and (Get-Date) -lt $until){Start-Sleep -Milliseconds 300}
    Check 'only the started Java process is selected' ($script:AppPid -eq $app.Id -and $script:Win -ne [IntPtr]::Zero)
    Minimize-Own
    $m=Wait-Text $OutDir 'ready' @('书架') 15
    Check 'first minimized window is restored and shelf ready' ($null -ne $m -and -not[WD]::IsIconic($script:Win))
    $script:RepoMarker=$repo
    Check 'parent checkout excludes nested child runtime' (@(Get-ClientPids -Mine) -notcontains $app.Id)
    $script:RepoMarker=$spec.repo
    Check 'exact checkout includes own runtime' (@(Get-ClientPids -Mine) -contains $app.Id)
    foreach($i in 1..3){
        Minimize-Own
        $before=Get-Rect
        $png=Join-Path $OutDir "restored-$i.png"
        Save-Shot $png
        $lines=@(Read-Screen $png)
        Check "minimized screenshot $i restores and reads shelf" (-not[WD]::IsIconic($script:Win) -and $null -ne (Find-Text $lines '书架'))
    }
    $png=Join-Path $OutDir 'before-click.png'
    Save-Shot $png
    $button=Find-Text @(Read-Screen $png) '设置'
    if(-not$button){throw 'Settings button absent'}
    Minimize-Own
    Click-At $button.X $button.Y
    Start-Sleep -Milliseconds 700
    $png=Join-Path $OutDir 'settings.png'
    Save-Shot $png
    Check 'click restores minimized window and opens settings' ($null -ne (Find-Text @(Read-Screen $png) '教材位置'))
    Minimize-Own
    Send-Keys '{ESC}'
    Start-Sleep -Milliseconds 500
    $png=Join-Path $OutDir 'escaped.png'
    Save-Shot $png
    $lines=@(Read-Screen $png)
    Check 'keyboard restores minimized window and closes settings' (-not[WD]::IsIconic($script:Win) -and $null -eq (Find-Text $lines '教材位置') -and $null -ne (Find-Text $lines '书架'))
} catch {$errorText=$_.Exception.Message} finally {
    Stop-Process -Id $app.Id -Force -ErrorAction SilentlyContinue
    @{checks=$checks;error=$errorText;time=(Get-Date).ToUniversalTime().ToString('o')}|ConvertTo-Json -Depth 5|Set-Content -Encoding UTF8 (Join-Path $OutDir 'result.json')
}
"WINDOW_RECOVERY $(@($checks|Where-Object ok).Count)/$($checks.Count) error=$errorText"
$global:LASTEXITCODE=if($errorText){1}else{0}