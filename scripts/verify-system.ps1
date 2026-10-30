#requires -Version 7.0
param([switch]$SkipBuild)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$projectName='pm-acceptance-'+[guid]::NewGuid().ToString('N').Substring(0,8)
$composeArgs=@('-p',$projectName,'-f',"$projectRoot/docker-compose.yml",'-f',"$projectRoot/docker-compose.acceptance.yml")
$ports=@{AUTH_HOST_PORT='34005';ANALYTICS_HOST_PORT='34002';GATEWAY_HOST_PORT='34004';PATIENT_DB_HOST_PORT='35433';AUTH_DB_HOST_PORT='35434';BILLING_DB_HOST_PORT='35435';ANALYTICS_DB_HOST_PORT='35436';KAFKA_HOST_PORT='39094'}
$previous=@{}
function Invoke-Compose {
    & docker compose @composeArgs @args
    if($LASTEXITCODE -ne 0){throw "Falló Compose de aceptación: $($args -join ' ')"}
}
try {
    foreach($key in $ports.Keys){$previous[$key]=[Environment]::GetEnvironmentVariable($key,'Process');[Environment]::SetEnvironmentVariable($key,$ports[$key],'Process')}
    Invoke-Compose config --quiet
    $up=@('up','-d','--wait','--wait-timeout','300');if(!$SkipBuild){$up+='--build'}
    Invoke-Compose @up
    $readonlyEmail='readonly-'+[guid]::NewGuid().ToString('N')+'@example.com'
    # New synthetic USER fixture; no existing account, password or role is changed.
    Invoke-Compose exec -T auth-db psql -v ON_ERROR_STOP=1 -U postgres -d auth_db -c "INSERT INTO users (id,email,password,role) SELECT gen_random_uuid(),'$readonlyEmail',password,'USER' FROM users WHERE email='testuser@test.com'"
    & "$PSScriptRoot/maven.ps1" -Module integration-tests verify -Pexternal-tests '-Dpm.integration.confirm=isolated' '-Dgateway.baseUri=http://127.0.0.1:34004' "-Dtest.readonly.email=$readonlyEmail" '-Dbilling.jdbc.url=jdbc:postgresql://127.0.0.1:35435/billing_db' '-Danalytics.jdbc.url=jdbc:postgresql://127.0.0.1:35436/analytics_db' '-Dkafka.bootstrap.servers=127.0.0.1:39094' "-Dcompose.project=$projectName" "-Dproject.root=$projectRoot" '-Duser.timezone=UTC'
    $before=Invoke-RestMethod http://127.0.0.1:34002/analytics/summary -TimeoutSec 10
    Invoke-Compose down
    Invoke-Compose up -d --wait --wait-timeout 300
    $after=Invoke-RestMethod http://127.0.0.1:34002/analytics/summary -TimeoutSec 10
    if($before.patientsCreated -ne $after.patientsCreated){throw 'Analytics no conservó el resultado tras recrear contenedores.'}
    Write-Output "OK: aceptación integral aprobada en $projectName; diez servicios y cinco volúmenes propios, conservados tras down/up."
} finally {
    $ids=& docker compose @composeArgs ps -aq
    if($ids){& docker inspect --format '{{.Name}} {{json .State}}' @ids *> "$projectRoot/.verification/acceptance-state.log"}
    & docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' *> "$projectRoot/.verification/acceptance-resources.log"
    & docker compose @composeArgs logs --no-color *> "$projectRoot/.verification/acceptance-services.log"
    & docker compose @composeArgs down
    foreach($key in $previous.Keys){[Environment]::SetEnvironmentVariable($key,$previous[$key],'Process')}
}
