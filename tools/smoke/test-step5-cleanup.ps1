# 第⑤步两条异常路径的本机验证，全程自写文件，不开客户端，不调模型：
#  A. 同意文件撤不掉（被占用）时，Clear-ExplainConsent 必须报错，文件仍在，调用方据此停下；放开后能清干净。
#  B. 代理发送边界测试遇到异常（输入文件不存在）：代理端口关闭、原配对文件不变、测试新建的配对副本被清掉，退出码非 0。
# 退出码 0 = 全部满足。
. (Join-Path $PSScriptRoot 'Win-Drive.ps1')
$tmp = Join-Path ([IO.Path]::GetTempPath()) ('step5-clean-' + [guid]::NewGuid().ToString('N').Substring(0, 8)); New-Item -ItemType Directory $tmp | Out-Null
$fail = 0
function Check([string]$name, [bool]$ok) { "{0} {1}" -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $name; if (-not $ok) { $script:fail++ } }
try {
    # A
    $lib = Join-Path $tmp 'lib'; $dir = Join-Path $lib 'explain-consent'; New-Item -ItemType Directory $dir | Out-Null
    $file = Join-Path $dir 'self-written-book'; Set-Content $file 'allowed'
    $lock = [IO.File]::Open($file, 'Open', 'Read', 'None')
    $threw = $false; try { Clear-ExplainConsent $lib } catch { $threw = $true }
    Check 'A1 同意文件被占用时撤除报错（worker 据此 Fail 5 并停）' $threw
    Check 'A2 被占用时文件仍在（所以确实不能往下走）' (Test-Path -LiteralPath $file)
    $lock.Close()
    $threw2 = $false; try { Clear-ExplainConsent $lib } catch { $threw2 = $true }
    Check 'A3 放开后撤除成功，目录里一个同意文件也不剩' ((-not $threw2) -and @(Get-ChildItem -LiteralPath $dir -Force).Count -eq 0)
    $noDir = $false; try { Clear-ExplainConsent (Join-Path $tmp 'nolib') } catch { $noDir = $true }
    Check 'A4 书架里本来没有同意目录时不报错' (-not $noDir)

    # B
    $seedSrc = Join-Path $tmp 'seed-src'; New-Item -ItemType Directory (Join-Path $seedSrc 'pairing') | Out-Null
    Set-Content -Encoding ascii (Join-Path $seedSrc 'pairing\server-url') 'http://127.0.0.1:9'
    Set-Content -Encoding ascii (Join-Path $seedSrc 'pairing\credentials.properties') "deviceId=fake`nrole=friend`nserver=http\://127.0.0.1\:9`ntoken=fake"
    $before = (Get-FileHash (Join-Path $seedSrc 'pairing\credentials.properties')).Hash
    $ev = Join-Path $tmp 'ev'
    $p = Start-Process powershell -ArgumentList @('-NoProfile', '-File', (Join-Path $PSScriptRoot 'test-step5-send-boundary.ps1'), '-ClientSeed', $seedSrc,
        '-Selection', (Join-Path $tmp 'missing-selection.json'), '-Book', (Join-Path $tmp 'missing-book.pdf'), '-Evidence', $ev) -Wait -PassThru -WindowStyle Hidden
    Check 'B1 异常时测试退出码非 0' ($p.ExitCode -ne 0)
    $work = Get-ChildItem $ev -Directory | Select-Object -First 1
    Check 'B2 测试新建的配对副本已清掉' (-not (Test-Path -LiteralPath (Join-Path $work.FullName 'seed')))
    Check 'B3 原配对文件不变' ((Get-FileHash (Join-Path $seedSrc 'pairing\credentials.properties')).Hash -eq $before)
    $port = [int](Get-Content (Join-Path $work.FullName 'port.txt') -Raw).Trim()
    $open = $false; try { $c = New-Object Net.Sockets.TcpClient; $open = $c.ConnectAsync('127.0.0.1', $port).Wait(1500) -and $c.Connected; $c.Close() } catch { }
    Check 'B4 代理端口已关闭' (-not $open)
} finally { Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue }
"RESULT fail=$fail"; exit $(if ($fail) { 1 } else { 0 })
