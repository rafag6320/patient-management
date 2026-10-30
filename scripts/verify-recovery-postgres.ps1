$ErrorActionPreference='Stop'
$containerName='pm-recovery-db-'+[guid]::NewGuid().ToString('N').Substring(0,8)
try {
    & docker run -d --name $containerName --tmpfs /var/lib/postgresql/data -e POSTGRES_DB=remaining_tests -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres -p 127.0.0.1::5432 postgres:16
    if($LASTEXITCODE -ne 0){throw 'No se pudo crear PostgreSQL aislado.'}
    $ready=$false
    for($i=0;$i -lt 30;$i++) {
        & docker exec $containerName pg_isready -U postgres -d remaining_tests *> $null
        if($LASTEXITCODE -eq 0){$ready=$true;break};Start-Sleep -Seconds 1
    }
    if(!$ready){throw 'PostgreSQL no disponible.'}
    $testPort=((& docker port $containerName 5432/tcp).Trim()).Split(':')[-1]
    & "$PSScriptRoot/maven.ps1" -Module patient-service verify -Pisolated-tests '-Dit.test=RegistrationPostgresChecks' "-Dremaining.postgres.port=$testPort" '-Duser.timezone=UTC'
    & "$PSScriptRoot/maven.ps1" -Module analytics-service verify -Pisolated-tests '-Dit.test=AnalyticsPostgresChecks' "-Dremaining.postgres.port=$testPort" '-Duser.timezone=UTC'
} finally { & docker rm -f $containerName }
