param(
    [Parameter(Mandatory=$true)]
    [ValidateSet('patient-service','auth-service','billing-service','analytics-service','api-gateway','integration-tests','infrastructure')]
    [string]$Module,
    [Parameter(ValueFromRemainingArguments=$true)]
    [string[]]$MavenArgs
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:PATH
try {
    $candidates = @($env:JDK21_HOME, $env:JAVA_HOME)
    if ($IsWindows -or $env:OS -eq 'Windows_NT') {
        $candidates += Get-ChildItem 'C:/Program Files/Java','C:/Program Files/Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue |
            Where-Object Name -Like '*21*' | Select-Object -ExpandProperty FullName
    }
    $jdk = $candidates | Where-Object {
        $_ -and (Test-Path (Join-Path $_ 'release')) -and
        ((Get-Content (Join-Path $_ 'release')) -match '^JAVA_VERSION="21[."]')
    } | Select-Object -First 1
    if (!$jdk) { throw 'Definir JDK21_HOME con la ruta de un JDK 21 completo.' }
    $env:JAVA_HOME = $jdk
    $env:PATH = (Join-Path $jdk 'bin') + [IO.Path]::PathSeparator + $previousPath
    $wrapper = if ($env:OS -eq 'Windows_NT') { 'mvnw.cmd' } else { 'mvnw' }
    # El IDE puede limpiar target durante la compilación. Esta salida es exclusiva de CLI.
    $buildDirectory = Join-Path $projectRoot ".verification/$Module"
    & (Join-Path $projectRoot "patient-service/$wrapper") -B -ntp -f (Join-Path $projectRoot "$Module/pom.xml") "-Dpm.build.directory=$buildDirectory" @MavenArgs
    if ($LASTEXITCODE -ne 0) { throw "Maven falló en $Module (código $LASTEXITCODE)." }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:PATH = $previousPath
}
