# Called by friend-service.py pair --winbox. A fresh code arrives on stdin only.
# No UI, book consent, learned state or quiz admission is changed.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
try {
    $inputConfig = [Console]::In.ReadToEnd() | ConvertFrom-Json
    $url = [uri]$inputConfig.url
    $octets = $url.Host.Split('.')
    if ($url.Scheme -ne 'http' -or $octets.Count -ne 4 -or $octets[0] -ne '100' -or
        [int]$octets[1] -lt 64 -or [int]$octets[1] -gt 127 -or
        $url.Port -lt 1024 -or $url.Port -in @(18791,8790,8791) -or $url.UserInfo -or
        $url.Query -or $url.Fragment -or $url.AbsolutePath -ne '/') { throw 'Not a private service URL' }
    $root = 'D:/agent/friend-path-service'
    $client = "$root/client"
    $owner = [Security.Principal.WindowsIdentity]::GetCurrent().User
    function Protect-Private([string]$Path, [bool]$Directory) {
        if ((Get-Item $Path).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'No links' }
        if ($Directory) {
            $acl = New-Object Security.AccessControl.DirectorySecurity
            $rule = New-Object Security.AccessControl.FileSystemAccessRule($owner, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
        } else {
            $acl = New-Object Security.AccessControl.FileSecurity
            $rule = New-Object Security.AccessControl.FileSystemAccessRule($owner, 'FullControl', 'Allow')
        }
        $acl.SetAccessRuleProtection($true, $false)
        $acl.AddAccessRule($rule)
        Set-Acl -LiteralPath $Path -AclObject $acl
    }
    foreach ($directory in @($root, $client, "$client/pairing")) {
        New-Item -ItemType Directory -Force $directory | Out-Null
        Protect-Private $directory $true
    }
    function Save-Private([string]$Path, [string]$Text) {
        if (!(Test-Path $Path)) { [IO.File]::WriteAllText($Path, '') }
        Protect-Private $Path $false
        [IO.File]::WriteAllText($Path, $Text, (New-Object Text.UTF8Encoding($false)))
    }
    Add-Type -AssemblyName System.Net.Http
    $handler = New-Object Net.Http.HttpClientHandler
    $handler.UseProxy = $false
    $handler.AllowAutoRedirect = $false
    $http = New-Object Net.Http.HttpClient($handler)
    $http.Timeout = [TimeSpan]::FromSeconds(20)
    $clock = [Diagnostics.Stopwatch]::StartNew()
    function Call-Service([string]$Path, $Body = $null) {
        if ($null -eq $Body) { $response = $http.GetAsync("$($inputConfig.url)$Path").GetAwaiter().GetResult() }
        else {
            $content = New-Object Net.Http.StringContent(($Body | ConvertTo-Json -Compress), [Text.Encoding]::UTF8, 'application/json')
            $response = $http.PostAsync("$($inputConfig.url)$Path", $content).GetAwaiter().GetResult()
        }
        try {
            $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            $bodyResult = if ($response.IsSuccessStatusCode) { $text | ConvertFrom-Json } else { $null }
            return @{status=[int]$response.StatusCode; body=$bodyResult}
        } finally { $response.Dispose() }
    }
    $unauth = Call-Service '/v1/hello'
    if ($unauth.status -ne 401) { throw 'Unauthenticated request must fail' }
    $paired = Call-Service '/v1/pair' @{code=$inputConfig.code; name='TON-1649 isolated Windows QA'}
    if ($paired.status -ne 200 -or $paired.body.token -notmatch '^[A-Za-z0-9_-]{43}$' -or
        $paired.body.deviceId -notmatch '^[A-Za-z0-9_-]{1,128}$') { throw 'Pairing failed' }
    $reuse = Call-Service '/v1/pair' @{code=$inputConfig.code; name='TON-1649 replay check'}
    if ($reuse.status -ne 401) { throw 'Code reuse must fail' }
    $http.DefaultRequestHeaders.Add('Authorization', "Bearer $($paired.body.token)")
    $http.DefaultRequestHeaders.Add('X-Coach-Device', [string]$paired.body.deviceId)
    $hello = Call-Service '/v1/hello'
    if ($hello.status -ne 200) { throw 'Authenticated health failed' }
    Save-Private "$client/pairing/credentials.properties" ("server=$($inputConfig.url)`ndeviceId=$($paired.body.deviceId)`ntoken=$($paired.body.token)`n")
    Save-Private "$client/pairing/server-url" $inputConfig.url
    $receipt = @{passed=$true; unauthenticated=401; paired=200; replay=401; authenticated=200;
        elapsedMs=$clock.ElapsedMilliseconds; clientDir=$client; url=$inputConfig.url}
    Save-Private "$root/connection.json" (($receipt + @{deviceId=$paired.body.deviceId; token=$paired.body.token}) | ConvertTo-Json)
    Save-Private "$root/result.json" ($receipt | ConvertTo-Json)
    $http.Dispose()
    $receipt | ConvertTo-Json -Compress
} catch {
    [Console]::Error.WriteLine('Private Windows handoff failed; no credential printed.')
    exit 1
}
