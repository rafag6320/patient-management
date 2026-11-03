#requires -Version 7.0
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$directory=Join-Path $root '.verification'
New-Item -ItemType Directory -Force $directory | Out-Null
$fixture=Join-Path $PSScriptRoot 'tests/readiness-fixture.mjs'
$options=@{FilePath=(Get-Command node -ErrorAction Stop).Source;ArgumentList=@(('"'+$fixture+'"'));PassThru=$true;
    RedirectStandardOutput=(Join-Path $directory 'readiness-fixture.stdout.log');RedirectStandardError=(Join-Path $directory 'readiness-fixture.stderr.log')}
if($IsWindows){$options.WindowStyle='Hidden'}
$process=$null
try {
    $process=Start-Process @options
    $ready=$false
    for($attempt=0;$attempt -lt 40;$attempt++) {
        if($process.HasExited){throw 'Readiness fixture could not bind its isolated port.'}
        if((Get-Content $options.RedirectStandardOutput -Raw -ErrorAction SilentlyContinue) -match 'READY'){$ready=$true;break}
        Start-Sleep -Milliseconds 100
    }
    if(!$ready){throw 'Readiness fixture did not start.'}
    $output=& "$PSScriptRoot/wait-system-ready.ps1" -GatewayUri 'http://127.0.0.1:34004/'
    $stats=Invoke-RestMethod 'http://127.0.0.1:34004/control/stats'
    if($stats.attempts -ne 2 -or ($output | Out-String) -match 'synthetic-fixture-token'){throw 'Readiness did not handle one transient response safely.'}
    $null=Invoke-RestMethod 'http://127.0.0.1:34004/control/unauthorized'
    $rejected=$false
    try {& "$PSScriptRoot/wait-system-ready.ps1" -GatewayUri 'http://127.0.0.1:34004/'}
    catch {if($_.Exception.Message -match 'Unexpected login readiness HTTP 401'){$rejected=$true}else{throw}}
    if(!$rejected){throw 'Readiness hid invalid credentials.'}
    $rejected=$false
    try {& "$PSScriptRoot/wait-system-ready.ps1" -GatewayUri 'http://example.com:34004/'}
    catch {if($_.Exception.Message -match 'explicit isolated loopback'){$rejected=$true}else{throw}}
    if(!$rejected){throw 'Readiness allowed a non-loopback credential target.'}
    Write-Output 'OK: readiness retries a transient 502, requires authenticated HTTP 200, rejects 401 and non-loopback targets, and does not print its token.'
} finally {
    if($process -and !$process.HasExited){$process.Kill();$process.WaitForExit()}
}
