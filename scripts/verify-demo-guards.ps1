#requires -Version 7.0
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$statePath=Join-Path $root '.verification/demo/state.json'
$original=[IO.File]::ReadAllText($statePath)
$state=$original | ConvertFrom-Json
if($state.project -cnotmatch '^pm-demo-[a-f0-9]{8}$' -or $state.root -cne [IO.Path]::GetFullPath($root)){throw 'Unexpected demo state.'}
$demo=Join-Path $PSScriptRoot 'demo.ps1'
$shell=(Get-Process -Id $PID).Path
$listener=$null;$foreignId=$null
$inventory=@(& docker ps -aq | Sort-Object)
if($LASTEXITCODE -ne 0){throw 'Docker unavailable.'}
$running=@(& docker ps -q --filter "label=com.docker.compose.project=$($state.project)")
if($running.Count){throw 'Stop the demo before testing its guards.'}
function Reject([string]$Action,[string]$Message) {
    $output=& $shell -NoProfile -File $demo -Action $Action 2>&1
    if($LASTEXITCODE -eq 0 -or ($output | Out-String) -notmatch [regex]::Escape($Message)){throw "Guard failed: $Message"}
    Write-Output "OK: $Message"
}
try {
    [IO.File]::WriteAllText($statePath,'{"version":1,"project":"unrelated-project"}')
    Reject 'Stop' 'Identidad de demo inválida'
    [IO.File]::WriteAllText($statePath,$original)
    $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,44004)
    $listener.Start()
    Reject 'Start' 'está ocupado'
    $listener.Stop();$listener=$null
    # A container we create but never start has no database or personal data.
    $foreignId=(& docker create --label "com.docker.compose.project=$($state.project)" --label 'com.pm.demo.owner=foreign-guard-fixture' postgres:16 true).Trim()
    if($LASTEXITCODE -ne 0 -or $foreignId -notmatch '^[a-f0-9]{64}$'){throw 'Cannot prepare ownership fixture.'}
    Reject 'Stop' 'Recursos ajenos o sin identidad de demo'
} finally {
    [IO.File]::WriteAllText($statePath,$original)
    if($listener){$listener.Stop()}
    if($foreignId -match '^[a-f0-9]{64}$') {
        $label=& docker inspect --format '{{ index .Config.Labels "com.pm.demo.owner" }}' $foreignId
        if($LASTEXITCODE -ne 0 -or $label -ne 'foreign-guard-fixture'){throw 'Ownership fixture identity changed; not removed.'}
        $null=& docker rm $foreignId
        if($LASTEXITCODE -ne 0){throw 'Cannot remove guard fixture.'}
    }
}
$after=@(& docker ps -aq | Sort-Object)
if(Compare-Object $inventory $after){throw 'Container inventory changed during rejection tests.'}
if([IO.File]::ReadAllText($statePath) -cne $original){throw 'Demo identity was not restored.'}
Write-Output 'OK: invalid identity, occupied port and foreign ownership rejected; existing containers unchanged.'
