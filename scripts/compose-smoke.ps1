param(
    [switch]$SkipBuild,
    [ValidateSet('pm-roadmap-test','pm-r08-full-test','pm-r09-full-test')]
    [string]$ProjectName='pm-roadmap-test'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$composeArgs = @('-p',$ProjectName,'-f',"$projectRoot/docker-compose.yml",'-f',"$projectRoot/docker-compose.test.yml")
$testPorts = @{ AUTH_HOST_PORT='14005'; ANALYTICS_HOST_PORT='14002'; GATEWAY_HOST_PORT='14004'; PATIENT_DB_HOST_PORT='15433'; AUTH_DB_HOST_PORT='15434'; BILLING_DB_HOST_PORT='15435'; ANALYTICS_DB_HOST_PORT='15436'; KAFKA_HOST_PORT='19094' }
$previous = @{}
function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Arguments)
    & docker compose @composeArgs @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Falló docker compose $($Arguments -join ' ')" }
}
function Assert-Patient([string]$Id) {
    $patients = Invoke-RestMethod 'http://localhost:14000/patients' -TimeoutSec 15
    if (@($patients.id) -notcontains $Id) { throw 'El paciente de prueba no se conservó.' }
}
function Assert-Auth([string]$Id) {
    $value = & docker compose @composeArgs exec -T auth-db psql -U postgres -d auth_db -tAc "SELECT id FROM users WHERE id='$Id'"
    if ($LASTEXITCODE -ne 0 -or ($value -join '').Trim() -ne $Id) { throw 'El registro de auth de prueba no se conservó.' }
}
function Assert-Billing([string]$PatientId,[string]$ExpectedId) {
    $patientUuid=([guid]::Parse($PatientId)).ToString()
    $value = & docker compose @composeArgs exec -T billing-db psql -U postgres -d billing_db -tAc "SELECT id FROM billing_account WHERE patient_id='$patientUuid'"
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo consultar la cuenta de billing.' }
    $accountId=($value -join '').Trim()
    $null=[guid]::Parse($accountId)
    if ($ExpectedId -and $accountId -ne $ExpectedId) { throw 'Billing no conservó la cuenta original.' }
    return $accountId
}
try {
    foreach ($key in $testPorts.Keys) {
        $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, $testPorts[$key], 'Process')
    }
    Invoke-Compose @('config','--quiet')
    $up = @('up','-d','--wait','--wait-timeout','300')
    if (!$SkipBuild) { $up += '--build' }
    Invoke-Compose $up
    foreach ($port in 14000,14005,14002,14004) {
        $health = Invoke-RestMethod "http://localhost:$port/actuator/health" -TimeoutSec 15
        if ($health.status -ne 'UP') { throw "Healthcheck falló en $port" }
    }
    $schema = Invoke-RestMethod 'http://localhost:14005/v3/api-docs' -TimeoutSec 30
    if (!$schema.paths.'/login'.post -or !$schema.paths.'/validate'.get) { throw 'OpenAPI incompleto.' }
    # Datos sintéticos; sólo se escribe al proyecto Compose de pruebas anterior.
    $suffix = [guid]::NewGuid().ToString('N')
    $body = @{name='Compose Test';email="compose-$suffix@example.com";address='Test Address';dateOfBirth='1990-01-15';registeredDate='2026-10-28';DNI=(Get-Random -Minimum 10000000 -Maximum 99999999).ToString()} | ConvertTo-Json
    $patient = Invoke-RestMethod 'http://localhost:14000/patients' -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 30
    if (!$patient.id) { throw 'Alta sin identificador.' }
    Assert-Patient $patient.id
    $accountId=Assert-Billing $patient.id ''
    $authId = [guid]::NewGuid().ToString()
    # Copia el hash ya existente sin imprimirlo ni cambiar la cuenta del seed.
    & docker compose @composeArgs exec -T auth-db psql -v ON_ERROR_STOP=1 -U postgres -d auth_db -c "INSERT INTO users (id,email,password,role) SELECT '$authId','compose-$suffix@example.com',password,role FROM users LIMIT 1"
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo preparar el registro aislado de auth.' }
    Assert-Auth $authId
    # R10: 200 confirms billing + durable intent; wait for the independent consumer.
    $patientUuid=([guid]::Parse($patient.id)).ToString()
    $deadline=(Get-Date).AddSeconds(60)
    do {
        $count=& docker compose @composeArgs exec -T analytics-db psql -U postgres -d analytics_db -tAc "SELECT count(*) FROM processed_patient_event WHERE patient_id='$patientUuid'"
        if($LASTEXITCODE -ne 0){throw 'No se pudo consultar el resultado de analytics.'}
        $consumed=($count -join '').Trim() -eq '1'
        if(!$consumed){Start-Sleep -Seconds 1}
    } while(!$consumed -and (Get-Date) -lt $deadline)
    if(!$consumed){throw 'Analytics no confirmó el resultado del paciente.'}
    $offsetsBefore = & docker compose @composeArgs exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic patient-events
    if ($LASTEXITCODE -ne 0 -or !$offsetsBefore) { throw 'No se pudieron comprobar los offsets Kafka.' }
    Invoke-Compose @('restart','patient-db','auth-db','billing-db','analytics-db','kafka','billing-service','patient-service','auth-service','analytics-service','api-gateway')
    Invoke-Compose @('up','-d','--wait','--wait-timeout','300')
    Assert-Patient $patient.id
    Assert-Auth $authId
    $null=Assert-Billing $patient.id $accountId
    # Recreación de contenedores: comprueba que los datos están en volúmenes.
    Invoke-Compose @('down')
    Invoke-Compose @('up','-d','--wait','--wait-timeout','300')
    Assert-Patient $patient.id
    Assert-Auth $authId
    $null=Assert-Billing $patient.id $accountId
    $offsetsAfter = & docker compose @composeArgs exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic patient-events
    if ($LASTEXITCODE -ne 0 -or (Compare-Object @($offsetsBefore) @($offsetsAfter))) { throw 'Kafka no conservó los offsets tras recrear contenedores.' }
    Write-Output 'OK: diez servicios disponibles, cuenta billing real, OpenAPI, alta con gRPC/Kafka, resultado persistente de analytics y persistencia tras restart/down/up.'
} finally {
    # No se eliminan volúmenes, ni siquiera cuando falla una comprobación.
    & docker compose @composeArgs down
    foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
}
