#requires -Version 7.0
[CmdletBinding()]
param([Parameter(Mandatory)][ValidateSet('Start','Run','Stop')][string]$Action)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$directory = Join-Path $root '.verification/demo'
$statePath = Join-Path $directory 'state.json'
$owner = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($root))).ToLowerInvariant()
$baseUri = 'http://127.0.0.1:44004'
$oldOwner = $env:PM_DEMO_OWNER
$lock = $null
$report = [ordered]@{ action=$Action; startedUtc=[DateTime]::UtcNow.ToString('o'); result='failed'; checks=@() }

function Step([string]$Message) {
    Write-Host $Message
    $report.checks += $Message
}
function Invoke-DemoDocker([Parameter(ValueFromRemainingArguments=$true)][string[]]$Arguments) {
    $output = & docker @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Docker falló en la operación '$($Arguments[0])'." }
    return $output
}
function Compose([Parameter(ValueFromRemainingArguments=$true)][string[]]$Arguments) {
    $output = & docker compose @script:composeArgs @Arguments 2>&1
    # Keep diagnostics local; only the explicit sanitized report is uploaded to CI.
    if ($Arguments[0] -ne 'config') { $output | Out-File (Join-Path $directory 'compose.log') -Append -Encoding utf8 }
    if ($LASTEXITCODE -ne 0) {
        if ($Arguments[0] -eq 'up') {
            # Private diagnostics only. Never include application logs in the public demo report.
            & docker compose @script:composeArgs logs --no-color --tail 80 2>&1 |
                Out-File (Join-Path $directory 'startup-diagnostics.log') -Encoding utf8
        }
        throw "Compose falló en '$($Arguments[0])'. Ver .verification/demo/compose.log."
    }
    return $output
}
function Assert-Owned {
    foreach ($kind in @('container','volume','network')) {
        $arguments = switch ($kind) {
            container { @('ps','-aq','--filter',"label=com.docker.compose.project=$($script:state.project)") }
            volume { @('volume','ls','-q','--filter',"label=com.docker.compose.project=$($script:state.project)") }
            network { @('network','ls','-q','--filter',"label=com.docker.compose.project=$($script:state.project)") }
        }
        $ids = @(Invoke-DemoDocker $arguments | Where-Object { $_ })
        if ($ids.Count -eq 0) { continue }
        $resources = (Invoke-DemoDocker (@('inspect','--type',$kind) + $ids) | Out-String) | ConvertFrom-Json
        foreach ($resource in $resources) {
            $labels = if ($kind -eq 'container') { $resource.Config.Labels } else { $resource.Labels }
            if (!$labels -or !$labels.PSObject.Properties['com.pm.demo.owner'] -or $labels.'com.pm.demo.owner' -cne $owner) {
                throw 'Recursos ajenos o sin identidad de demo: no se modificó el entorno.'
            }
        }
    }
}
function Http([string]$Method, [string]$Path, $Body=$null, [string]$Token='', [string]$Key='') {
    $headers = @{}
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    if ($Key) { $headers.'Idempotency-Key' = $Key }
    $parameters = @{Uri="$baseUri$Path"; Method=$Method; Headers=$headers; TimeoutSec=12; SkipHttpErrorCheck=$true}
    if ($null -ne $Body) { $parameters.Body=($Body | ConvertTo-Json -Compress); $parameters.ContentType='application/json' }
    try { return Invoke-WebRequest @parameters }
    catch { throw "No se pudo contactar al gateway durante $Method $Path; no se registró el request ni el token." }
}
function Expect($Response, [int]$Status, [string]$Context) {
    if ([int]$Response.StatusCode -ne $Status) { throw "$Context`: se esperaba HTTP $Status; llegó $([int]$Response.StatusCode)." }
}
function Sql([string]$Database, [string]$Query) {
    $allowed = @('patient','billing','analytics')
    if ($Database -notin $allowed -or !$Query.StartsWith('SELECT ',[StringComparison]::Ordinal)) { throw 'Sólo se permiten consultas de lectura de demo.' }
    $result = Compose @('exec','-T',"$Database-db",'psql','-X','-v','ON_ERROR_STOP=1','-U','postgres','-d',"${Database}_db",'-tAc',$Query)
    return ($result | Out-String).Trim()
}
function Wait-For([scriptblock]$Condition, [string]$Description) {
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    do {
        if (& $Condition) { return }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Tiempo agotado: $Description."
}
function New-Patient {
    return @{name='Portfolio Synthetic'; email="demo-$([guid]::NewGuid().ToString('N'))@example.com";
        address='Synthetic address'; dateOfBirth='1990-01-01'; registeredDate=[DateTime]::UtcNow.ToString('yyyy-MM-dd');
        DNI=(Get-Random -Minimum 1000000 -Maximum 2000000000).ToString()}
}
function Confirm-Registration($Body, [string]$Key, [string]$Token) {
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    do {
        $response = Http 'POST' '/api/patients' $Body $Token $Key
        if ([int]$response.StatusCode -eq 200) { return ($response.Content | ConvertFrom-Json) }
        Expect $response 503 'Alta recuperable'
        Start-Sleep -Milliseconds 750
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'El alta no se confirmó dentro del plazo; se conserva la operación para diagnóstico.'
}
function Assert-Result([string]$Id, [long]$ExpectedTotal, [string]$Token) {
    $uuid = ([guid]::Parse($Id)).ToString()
    if ((Sql 'billing' "SELECT count(*) FROM billing_account WHERE patient_id='$uuid'") -ne '1') { throw 'Billing no contiene exactamente una cuenta.' }
    Wait-For {
        $response=Http 'GET' '/api/analytics/summary' $null $Token
        Expect $response 200 'Analytics'
        return [long](($response.Content | ConvertFrom-Json).patientsCreated) -eq $ExpectedTotal
    } 'resultado eventual de analytics'
    if ((Sql 'analytics' "SELECT count(*) FROM processed_patient_event WHERE patient_id='$uuid'") -ne '1') { throw 'Analytics no contiene exactamente un efecto del evento.' }
    Step "OK: patient=$uuid; cuenta billing=1; efecto analytics=1; total=$ExpectedTotal."
}

try {
    New-Item -ItemType Directory -Force $directory | Out-Null
    try { $lock=[IO.File]::Open((Join-Path $directory 'operation.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None) }
    catch { throw 'Ya hay otra acción de demo en curso.' }
    $versionText = (Invoke-DemoDocker @('compose','version','--short') | Out-String).Trim() -replace '^v',''
    if ($versionText -notmatch '^(\d+\.\d+\.\d+)' -or [version]$Matches[1] -lt [version]'2.24.4') { throw 'Se requiere Docker Compose >= 2.24.4.' }
    $null = Invoke-DemoDocker @('info','--format','{{.ServerVersion}}')
    $script:state = $null
    if (Test-Path -LiteralPath $statePath) {
        try {
            $script:state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
            if ($state.version -ne 1 -or $state.owner -cne $owner -or $state.root -cne $root -or $state.project -cnotmatch '^pm-demo-[a-f0-9]{8}$') { throw 'Invalid state' }
        } catch { throw 'Identidad de demo inválida: no se contactaron ni modificaron sus recursos.' }
    } elseif ($Action -ne 'Start') { throw 'No hay una demo registrada. Ejecutar primero Start.' }
    else { $script:state=[pscustomobject]@{version=1;owner=$owner;root=$root;project="pm-demo-$([guid]::NewGuid().ToString('N').Substring(0,8))"} }
    $env:PM_DEMO_OWNER=$owner
    $script:composeArgs=@('-p',$state.project,'-f',(Join-Path $root 'docker-compose.yml'),'-f',(Join-Path $root 'docker-compose.demo.yml'))
    Assert-Owned
    # Never connect a fresh state to resources left under an accidental name collision.
    if (!(Test-Path -LiteralPath $statePath)) {
        $existing=@(Invoke-DemoDocker @('volume','ls','-q','--filter',"label=com.docker.compose.project=$($state.project)") | Where-Object {$_})
        if ($existing.Count) { throw 'Nombre de demo ocupado: no se reutilizaron volúmenes.' }
    }
    if ($Action -eq 'Start') {
        $gateway=@(Compose @('ps','-q','--status','running','api-gateway') | Where-Object {$_})
        if (!$gateway.Count) {
            $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,44004)
            try { $listener.Start() } catch { throw 'El puerto 127.0.0.1:44004 está ocupado; no se detuvo ningún servicio.' }
            finally { $listener.Stop() }
        }
        # Validate effective bindings without printing interpolated configuration/secrets.
        $configuration=(Compose @('config','--format','json') | Out-String) | ConvertFrom-Json
        foreach ($service in $configuration.services.PSObject.Properties) {
            if ($service.Value.PSObject.Properties['ports'] -and @($service.Value.ports).Count) {
                $ports=@($service.Value.ports)
                if ($service.Name -ne 'api-gateway' -or $ports.Count -ne 1 -or $ports[0].host_ip -ne '127.0.0.1' -or [int]$ports[0].published -ne 44004) { throw 'La configuración efectiva expone puertos ajenos a la demo.' }
            }
        }
        $state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8
        Step "Iniciando $($state.project), sin utilizar volúmenes del entorno habitual."
        $null=Compose @('build')
        # Cold database migrations share a short JDBC socket timeout with runtime checks.
        # Start JVMs in order so cold migrations do not compete with every application.
        $null=Compose @('up','-d','--wait','--wait-timeout','300','patient-db','auth-db','billing-db','analytics-db','kafka')
        foreach ($service in @('billing-service','auth-service','patient-service','analytics-service','api-gateway')) {
            $null=Compose @('up','-d','--wait','--wait-timeout','300',$service)
        }
        Assert-Owned
        $page=Http 'GET' '/swagger-ui/index.html'
        Expect $page 200 'Swagger UI'
        Step "Swagger: $baseUri/swagger-ui.html"
    } elseif ($Action -eq 'Stop') {
        $null=Compose @('down')
        Step "Demo $($state.project) detenida; sus volúmenes y estado se conservaron."
    } else {
        $services=@(Compose @('ps','--status','running','--services') | Where-Object {$_})
        if ($services.Count -ne 10) { throw 'La demo requiere sus diez servicios activos. Ejecutar Start.' }
        Expect (Http 'GET' '/api/patients') 401 'Acceso sin JWT'
        Step 'OK: acceso sin JWT rechazado (HTTP 401).'
        $login=Http 'POST' '/api/auth/login' @{email='testuser@test.com';password='password123'}
        Expect $login 200 'Login de desarrollo'
        $token=($login.Content | ConvertFrom-Json).token
        if (!$token) { throw 'Login sin token; no se registró el cuerpo.' }
        Wait-For { (Sql 'patient' "SELECT count(*) FROM registration_work WHERE billing_status<>'CONFIRMED' OR event_status<>'CONFIRMED'") -eq '0' } 'trabajo anterior de la demo'
        $summary=Http 'GET' '/api/analytics/summary' $null $token
        Expect $summary 200 'Analytics inicial'
        $baseline=[long](($summary.Content | ConvertFrom-Json).patientsCreated)
        $body=New-Patient; $key=[guid]::NewGuid().ToString()
        $patient=Confirm-Registration $body $key $token
        $repeat=Http 'POST' '/api/patients' $body $token $key
        Expect $repeat 200 'Repetición idempotente'
        if (($repeat.Content | ConvertFrom-Json).id -ne $patient.id) { throw 'El reintento cambió el identificador.' }
        Assert-Result $patient.id ($baseline+1) $token
        Step 'OK: alta y repetición idempotente (HTTP 200, mismo ID).'

        $body=New-Patient; $key=[guid]::NewGuid().ToString(); $pendingId=$null
        try {
            Assert-Owned
            $null=Compose @('stop','billing-service')
            $watch=[Diagnostics.Stopwatch]::StartNew()
            $pending=Http 'POST' '/api/patients' $body $token $key
            Expect $pending 503 'Billing detenido'
            if ($watch.Elapsed.TotalSeconds -gt 10) { throw 'El fallo de billing no quedó acotado.' }
            $patients=Http 'GET' '/api/patients' $null $token
            Expect $patients 200 'Paciente pendiente visible'
            $matches=@(($patients.Content | ConvertFrom-Json) | Where-Object email -EQ $body.email)
            if ($matches.Count -ne 1) { throw 'No se encontró una única operación pendiente.' }
            $pendingId=([guid]::Parse($matches[0].id)).ToString()
            if ((Sql 'billing' "SELECT count(*) FROM billing_account WHERE patient_id='$pendingId'") -ne '0') { throw 'Se creó una cuenta mientras billing estaba detenido.' }
            if ((Sql 'patient' "SELECT count(*) FROM registration_work WHERE patient_id='$pendingId' AND billing_status IN ('PENDING','UNCERTAIN')") -ne '1') { throw 'No se conservó el trabajo pendiente.' }
            Step 'OK: billing detenido devuelve HTTP 503 y conserva el alta pendiente.'
            $null=Compose @('restart','patient-service')
            $null=Compose @('up','-d','--wait','--wait-timeout','180','patient-service')
        } finally {
            Assert-Owned
            $null=Compose @('up','-d','--wait','--wait-timeout','180','billing-service')
        }
        $recovered=Confirm-Registration $body $key $token
        if ($recovered.id -ne $pendingId) { throw 'La recuperación cambió el paciente original.' }
        $repeat=Http 'POST' '/api/patients' $body $token $key
        Expect $repeat 200 'Repetición tras recuperar'
        if (($repeat.Content | ConvertFrom-Json).id -ne $pendingId) { throw 'La recuperación no fue idempotente.' }
        Assert-Result $pendingId ($baseline+2) $token
        Step 'OK: reinicio, recuperación y nueva repetición conservan el mismo ID y efectos únicos.'
        Step "Demo completada. Swagger permanece disponible en $baseUri/swagger-ui.html"
    }
    $report.result='passed'
} catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
} finally {
    $env:PM_DEMO_OWNER=$oldOwner
    if ($null -ne $lock) {
        $report.completedUtc=[DateTime]::UtcNow.ToString('o')
        $report | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $directory ("report-{0}-{1}.json" -f $Action,[guid]::NewGuid().ToString('N'))) -Encoding utf8
        $lock.Dispose()
    }
}
if ($report.result -ne 'passed') { exit 1 }
exit 0

