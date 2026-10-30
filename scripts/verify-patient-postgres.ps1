$ErrorActionPreference='Stop'
$containerName='pm-r06-db-'+[guid]::NewGuid().ToString('N').Substring(0,8)
try {
    & docker run -d --name $containerName --tmpfs /var/lib/postgresql/data -e POSTGRES_DB=patient_r06 -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres -p 127.0.0.1::5432 postgres:16
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo iniciar PostgreSQL aislado.' }
    $ready=$false
    for ($attempt=0;$attempt -lt 30;$attempt++) {
        & docker exec $containerName pg_isready -U postgres -d patient_r06 *> $null
        if ($LASTEXITCODE -eq 0) { $ready=$true; break }
        Start-Sleep -Seconds 1
    }
    if (!$ready) { throw 'PostgreSQL aislado no disponible.' }
    $binding=(& docker port $containerName 5432/tcp).Trim()
    $testPort=$binding.Split(':')[-1]
    & "$PSScriptRoot/maven.ps1" -Module patient-service verify -Pisolated-tests '-Dit.test=PatientPostgresChecks' "-Dr06.postgres.port=$testPort" '-Duser.timezone=UTC'
} finally {
    # Sólo el contenedor efímero generado por esta ejecución; no toca volúmenes existentes.
    & docker rm -f $containerName
}
