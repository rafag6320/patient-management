$ErrorActionPreference='Stop'
$containerName='pm-r09-db-'+[guid]::NewGuid().ToString('N').Substring(0,8)
try {
    & docker run -d --name $containerName --tmpfs /var/lib/postgresql/data -e POSTGRES_DB=billing_r09 -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres -p 127.0.0.1::5432 postgres:16
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo iniciar PostgreSQL aislado.' }
    $ready=$false
    for ($attempt=0;$attempt -lt 30;$attempt++) {
        & docker exec $containerName pg_isready -U postgres -d billing_r09 *> $null
        if ($LASTEXITCODE -eq 0) { $ready=$true; break }
        Start-Sleep -Seconds 1
    }
    if (!$ready) { throw 'PostgreSQL aislado no disponible.' }
    $testPort=((& docker port $containerName 5432/tcp).Trim()).Split(':')[-1]
    & "$PSScriptRoot/maven.ps1" -Module billing-service verify -Pisolated-tests '-Dit.test=BillingPostgresChecks' "-Dr09.postgres.port=$testPort" '-Duser.timezone=UTC'
} finally { & docker rm -f $containerName }
