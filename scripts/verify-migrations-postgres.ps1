$ErrorActionPreference='Stop'
$containerName='pm-r07-db-'+[guid]::NewGuid().ToString('N').Substring(0,8)
$previousMigrationUrl=$env:PM_MIGRATION_URL
$previousMigrationUser=$env:PM_MIGRATION_USER
$previousMigrationPassword=$env:PM_MIGRATION_PASSWORD
try {
    & docker run -d --name $containerName --tmpfs /var/lib/postgresql/data -e POSTGRES_DB=migration_r07 -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres -p 127.0.0.1::5432 postgres:16
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo iniciar PostgreSQL aislado.' }
    $ready=$false
    for ($attempt=0;$attempt -lt 30;$attempt++) {
        & docker exec $containerName pg_isready -U postgres -d migration_r07 *> $null
        if ($LASTEXITCODE -eq 0) { $ready=$true; break }
        Start-Sleep -Seconds 1
    }
    if (!$ready) { throw 'PostgreSQL aislado no disponible.' }
    $testPort=((& docker port $containerName 5432/tcp).Trim()).Split(':')[-1]
    foreach ($module in @('patient-service','auth-service')) {
        & "$PSScriptRoot/maven.ps1" -Module $module verify -Pisolated-tests '-Dit.test=*MigrationChecks' "-Dr07.postgres.port=$testPort" '-Duser.timezone=UTC'
        $schema=if ($module -eq 'patient-service') { 'patient_app' } else { 'auth_app' }
        $env:PM_MIGRATION_URL="jdbc:postgresql://127.0.0.1:$testPort/migration_r07?currentSchema=$schema"
        $env:PM_MIGRATION_USER='postgres'
        $env:PM_MIGRATION_PASSWORD='postgres'
        & "$PSScriptRoot/maven.ps1" -Module $module flyway:info flyway:validate '-Duser.timezone=UTC'
    }
} finally {
    & docker rm -f $containerName
    $env:PM_MIGRATION_URL=$previousMigrationUrl
    $env:PM_MIGRATION_USER=$previousMigrationUser
    $env:PM_MIGRATION_PASSWORD=$previousMigrationPassword
}
