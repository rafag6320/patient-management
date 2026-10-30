#requires -Version 7.0
param(
    [switch]$SkipBuild,
    [ValidateSet('pm-r05-test','pm-r08-test')]
    [string]$ProjectName='pm-r05-test'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$composeArgs = @('-p',$ProjectName,'-f',"$projectRoot/docker-compose.yml",'-f',"$projectRoot/docker-compose.auth-test.yml")
$settings = @{ AUTH_HOST_PORT='24005'; GATEWAY_HOST_PORT='24004'; ANALYTICS_HOST_PORT='24002'; PATIENT_DB_HOST_PORT='25433'; AUTH_DB_HOST_PORT='25434'; BILLING_DB_HOST_PORT='25435'; ANALYTICS_DB_HOST_PORT='25436'; KAFKA_HOST_PORT='29094'; R05_GATEWAY_PROFILE='default' }
$previous = @{}
function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Arguments)
    & docker compose @composeArgs @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Falló docker compose $($Arguments -join ' ')" }
}
function Assert-Status([int]$Expected, [string]$Method, [string]$Path, [string]$Token, [string]$Body) {
    $arguments = @{ Uri="http://localhost:24004$Path"; Method=$Method; SkipHttpErrorCheck=$true; TimeoutSec=10 }
    if ($Token) { $arguments.Headers = @{Authorization="Bearer $Token"} }
    if ($Body) { $arguments.Body=$Body; $arguments.ContentType='application/json' }
    $response = Invoke-WebRequest @arguments
    if ([int]$response.StatusCode -ne $Expected) { throw "Se esperaba HTTP $Expected en $Method $Path; recibido $($response.StatusCode)." }
    return $response
}
function ConvertTo-Base64Url([byte[]]$Bytes) {
    return [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
}
function New-ExpiredToken {
    # Usa la configuración existente sólo en memoria; no modifica ni imprime claves.
    $secret = $env:JWT_SECRET
    if (!$secret) {
        $property = Get-Content "$projectRoot/auth-service/src/main/resources/application-docker.properties" |
            Where-Object { $_ -match '^jwt\.secret=' }
        if ($property -notmatch '^jwt\.secret=\$\{JWT_SECRET:(.+)\}$') { throw 'No se pudo leer la configuración local JWT.' }
        $secret = $Matches[1]
    }
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $header = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes('{"alg":"HS256","typ":"JWT"}'))
    $claims = @{sub='testuser@test.com';iat=($now-120);exp=($now-60)} | ConvertTo-Json -Compress
    $payload = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($claims))
    $signer = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($secret))
    try { $signature = ConvertTo-Base64Url ($signer.ComputeHash([Text.Encoding]::UTF8.GetBytes("$header.$payload"))) }
    finally { $signer.Dispose() }
    return "$header.$payload.$signature"
}
try {
    foreach ($key in $settings.Keys) {
        $previous[$key] = [Environment]::GetEnvironmentVariable($key,'Process')
        [Environment]::SetEnvironmentVariable($key,$settings[$key],'Process')
    }
    Invoke-Compose config --quiet
    $login = @{email='testuser@test.com';password='password123'} | ConvertTo-Json
    foreach ($profile in @('default','prod')) {
        $env:R05_GATEWAY_PROFILE = $profile
        $up = @('up','-d','--wait','--wait-timeout','300','api-gateway')
        if (!$SkipBuild -and $profile -eq 'default') { $up += '--build' }
        Invoke-Compose $up
        $response = Assert-Status 200 POST '/api/auth/login' '' $login
        $token = ($response.Content | ConvertFrom-Json).token
        if (!$token) { throw 'Login sin token.' }
        $patients = Assert-Status 200 GET '/api/patients' $token ''
        if (!$patients.Content.Trim().StartsWith('[')) { throw 'La respuesta de pacientes no es una lista.' }
        $null = Assert-Status 401 GET '/api/patients' '' ''
        $null = Assert-Status 401 GET '/api/patients' 'not.a.jwt' ''
        $null = Assert-Status 401 GET '/api/patients' (New-ExpiredToken) ''
        $parts = $token.Split('.')
        $replacement = if ($parts[2][0] -eq 'A') { 'B' } else { 'A' }
        $parts[2] = $replacement + $parts[2].Substring(1)
        $null = Assert-Status 401 GET '/api/patients' ($parts -join '.') ''
        $null = Assert-Status 401 POST '/api/auth/login' '' (@{email='testuser@test.com';password='wrong-password'} | ConvertTo-Json)
        # Un auth pausado mantiene su conexión pero no puede responder.
        Invoke-Compose pause auth-service
        try {
            foreach ($path in @('/api/patients','/api/auth/login')) {
                $watch = [Diagnostics.Stopwatch]::StartNew()
                if ($path -eq '/api/patients') { $null = Assert-Status 502 GET $path $token '' }
                else { $null = Assert-Status 502 POST $path '' $login }
                $watch.Stop()
                if ($watch.Elapsed.TotalSeconds -gt 5) { throw "Auth sin respuesta excedió el margen de prueba: $path" }
            }
        } finally { Invoke-Compose unpause auth-service }
        Invoke-Compose stop auth-service
        $null = Assert-Status 502 GET '/api/patients' $token ''
        $null = Assert-Status 502 POST '/api/auth/login' '' $login
        Write-Output "OK ($profile): login, pacientes, tokens ausentes/inválidos/expirados/adulterados, auth pausado y auth detenido."
    }
} finally {
    & docker compose @composeArgs down
    foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key,$previous[$key],'Process') }
}
